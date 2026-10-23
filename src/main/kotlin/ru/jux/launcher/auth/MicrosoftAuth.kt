package ru.jux.launcher.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.tr
import ru.jux.launcher.net.Http
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class MicrosoftAuth(private val clientId: String = AuthConfig.clientId) {

    private companion object {
        const val AUTHORIZE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
        const val TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        const val XBL = "https://user.auth.xboxlive.com/user/authenticate"
        const val XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize"
        const val MC_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox"
        const val MC_ENTITLEMENTS = "https://api.minecraftservices.com/entitlements/mcstore"
        const val MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile"
        const val SCOPE = "XboxLive.signin offline_access"

        val JSON_MEDIA = "application/json".toMediaType()
    }

    suspend fun signIn(onStage: (String) -> Unit = {}): Account = withContext(Dispatchers.IO) {
        if (!AuthConfig.isConfigured) throw AuthException(AuthConfig.SETUP_HINT)

        val verifier = randomUrlSafe(64)
        val challenge = codeChallenge(verifier)
        val state = randomUrlSafe(24)

        LoopbackReceiver().use { receiver ->
            val url = AUTHORIZE.toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", receiver.redirectUri)
                .addQueryParameter("scope", SCOPE)
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("state", state)
                .addQueryParameter("prompt", "select_account")
                .build()
                .toString()

            onStage(tr("Открываю браузер для входа", "Opening the browser to sign in"))
            openBrowser(url)

            val code = receiver.awaitCode(expectedState = state, timeoutMillis = 5 * 60_000)

            onStage(tr("Обмен кода на токен", "Exchanging the code for a token"))
            val msToken = exchangeCode(code, verifier, receiver.redirectUri)
            finish(msToken, onStage)
        }
    }

    suspend fun refresh(refreshToken: String, onStage: (String) -> Unit = {}): Account =
        withContext(Dispatchers.IO) {
            if (!AuthConfig.isConfigured) throw AuthException(AuthConfig.SETUP_HINT)
            onStage(tr("Обновление сессии", "Refreshing the session"))
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .add("scope", SCOPE)
                .build()
            val response = postForm(TOKEN, body)
            val parsed = Json.decodeFromString<MsTokenResponse>(response)
            if (parsed.error != null) {
                throw AuthException(tr("Сессия истекла, войдите заново (${parsed.error})", "Session expired, sign in again (${parsed.error})"))
            }
            finish(parsed, onStage)
        }

    private fun finish(msToken: MsTokenResponse, onStage: (String) -> Unit): Account {
        onStage(tr("Вход в Xbox Live", "Signing in to Xbox Live"))
        val (xblToken, userHash) = authenticateXbl(msToken.accessToken)

        onStage(tr("Проверка XSTS", "Checking XSTS"))
        val xstsToken = authorizeXsts(xblToken)

        onStage(tr("Вход в Minecraft", "Signing in to Minecraft"))
        val mcToken = loginWithXbox(userHash, xstsToken)

        onStage(tr("Проверка лицензии", "Checking the license"))
        requireOwnership(mcToken.accessToken)

        onStage(tr("Загрузка профиля", "Loading the profile"))
        val profile = fetchProfile(mcToken.accessToken)

        return Account(
            uuid = profile.id,
            name = profile.name,
            type = AccountType.MICROSOFT,
            accessToken = mcToken.accessToken,
            refreshToken = TokenStore.protect(msToken.refreshToken),
            expiresAt = System.currentTimeMillis() + mcToken.expiresIn * 1000,
            skinUrl = profile.skins.firstOrNull { it.state.equals("ACTIVE", true) }?.url,
        )
    }

    private fun exchangeCode(code: String, verifier: String, redirectUri: String): MsTokenResponse {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .add("code_verifier", verifier)
            .add("scope", SCOPE)
            .build()
        val parsed = Json.decodeFromString<MsTokenResponse>(postForm(TOKEN, body))
        if (parsed.error != null) {
            throw AuthException(tr("Microsoft отклонил вход: ${parsed.errorDescription ?: parsed.error}", "Microsoft rejected the sign-in: ${parsed.errorDescription ?: parsed.error}"))
        }
        if (parsed.accessToken.isBlank()) throw AuthException(tr("Microsoft не вернул токен доступа", "Microsoft returned no access token"))
        return parsed
    }

    private fun authenticateXbl(msAccessToken: String): Pair<String, String> {
        val payload = """
            {"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=$msAccessToken"},
             "RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}
        """.trimIndent()
        val parsed = Json.decodeFromString<XboxResponse>(postJson(XBL, payload))
        val hash = parsed.displayClaims?.xui?.firstOrNull()?.uhs
            ?: throw AuthException(tr("Xbox Live не вернул идентификатор пользователя", "Xbox Live returned no user ID"))
        if (parsed.token.isBlank()) throw AuthException(tr("Xbox Live не вернул токен", "Xbox Live returned no token"))
        return parsed.token to hash
    }

    private fun authorizeXsts(xblToken: String): String {
        val payload = """
            {"Properties":{"SandboxId":"RETAIL","UserTokens":["$xblToken"]},
             "RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}
        """.trimIndent()

        val (code, body) = postJsonRaw(XSTS, payload)
        if (code == 401) {
            val error = runCatching { Json.decodeFromString<XboxResponse>(body) }.getOrNull()
            throw AuthException(describeXErr(error?.xErr))
        }
        if (code !in 200..299) throw AuthException(tr("XSTS вернул HTTP $code", "XSTS returned HTTP $code"))

        val parsed = Json.decodeFromString<XboxResponse>(body)
        if (parsed.token.isBlank()) throw AuthException(tr("XSTS не вернул токен", "XSTS returned no token"))
        return parsed.token
    }

    private fun describeXErr(xErr: Long?): String = when (xErr) {
        2148916233L -> tr(
            "К этому аккаунту Microsoft не привязан профиль Xbox. " +
                "Зайдите на minecraft.net под этим аккаунтом и создайте профиль.",
            "This Microsoft account has no Xbox profile. " +
                "Sign in to minecraft.net with this account and create a profile.",
        )
        2148916235L -> tr("Xbox Live недоступен в стране вашего аккаунта.", "Xbox Live is not available in your account's country.")
        2148916236L, 2148916237L -> tr("Аккаунту требуется подтверждение возраста (Xbox adult verification).", "The account needs age verification (Xbox adult verification).")
        2148916238L -> tr("Детский аккаунт: добавьте его в семейную группу Microsoft.", "Child account: add it to a Microsoft family group.")
        2148916227L -> tr("Аккаунт заблокирован за нарушение правил Xbox Live.", "The account is banned for breaking Xbox Live rules.")
        else -> tr("Xbox Live отклонил вход", "Xbox Live rejected the sign-in") + (xErr?.let { tr(" (код $it)", " (code $it)") } ?: "")
    }

    private fun loginWithXbox(userHash: String, xstsToken: String): McLoginResponse {
        val payload = """{"identityToken":"XBL3.0 x=$userHash;$xstsToken"}"""
        val (code, body) = postJsonRaw(MC_LOGIN, payload)
        if (code == 403) {
            throw AuthException(
                tr(
                    "Minecraft не пускает это приложение Azure (HTTP 403). Новое приложение нужно " +
                        "отдельно одобрить у Mojang: заявка на aka.ms/mce-reviewappid, подробности в README.",
                    "Minecraft does not allow this Azure app (HTTP 403). A new app must be " +
                        "approved by Mojang separately: apply at aka.ms/mce-reviewappid, details in the README.",
                )
            )
        }
        if (code !in 200..299) throw AuthException(tr("Minecraft отклонил вход (HTTP $code)", "Minecraft rejected the sign-in (HTTP $code)"))

        val parsed = Json.decodeFromString<McLoginResponse>(body)
        if (parsed.accessToken.isBlank()) throw AuthException(tr("Minecraft не выдал сессионный токен", "Minecraft returned no session token"))
        return parsed
    }

    private fun requireOwnership(mcAccessToken: String) {
        val body = get(MC_ENTITLEMENTS, mcAccessToken)
        val entitlements = runCatching { Json.decodeFromString<Entitlements>(body) }.getOrNull()
        val owns = entitlements?.items.orEmpty().any {
            it.name == "product_minecraft" || it.name == "game_minecraft"
        }
        if (!owns) {
            throw AuthException(
                tr(
                    "На этом аккаунте Microsoft нет лицензии Minecraft: Java Edition. " +
                        "Если игра куплена на другом аккаунте — войдите под ним.",
                    "This Microsoft account doesn't own Minecraft: Java Edition. " +
                        "If the game was bought on another account, sign in with that one.",
                )
            )
        }
    }

    private fun fetchProfile(mcAccessToken: String): McProfile {
        val (code, body) = getRaw(MC_PROFILE, mcAccessToken)
        if (code == 404) {
            throw AuthException(
                tr("У аккаунта нет профиля Minecraft. Зайдите на minecraft.net и задайте никнейм.", "The account has no Minecraft profile. Go to minecraft.net and set a nickname.")
            )
        }
        if (code !in 200..299) throw AuthException(tr("Не удалось получить профиль (HTTP $code)", "Could not get the profile (HTTP $code)"))
        val profile = Json.decodeFromString<McProfile>(body)
        if (profile.id.isBlank() || profile.name.isBlank()) {
            throw AuthException(tr("Сервис Minecraft вернул пустой профиль", "Minecraft returned an empty profile"))
        }
        return profile
    }

    private fun postForm(url: String, body: FormBody): String {
        Http.client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code != 400) {
                throw AuthException(tr("HTTP ${response.code} от $url", "HTTP ${response.code} from $url"))
            }
            return text
        }
    }

    private fun postJson(url: String, payload: String): String {
        val (code, body) = postJsonRaw(url, payload)
        if (code !in 200..299) throw AuthException(tr("HTTP $code от $url", "HTTP $code from $url"))
        return body
    }

    private fun postJsonRaw(url: String, payload: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .build()
        Http.client.newCall(request).execute().use { response ->
            return response.code to response.body?.string().orEmpty()
        }
    }

    private fun get(url: String, bearer: String): String {
        val (code, body) = getRaw(url, bearer)
        if (code !in 200..299) throw AuthException(tr("HTTP $code от $url", "HTTP $code from $url"))
        return body
    }

    private fun getRaw(url: String, bearer: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .build()
        Http.client.newCall(request).execute().use { response ->
            return response.code to response.body?.string().orEmpty()
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun openBrowser(url: String) {
        if (!Shell.browse(url)) {
            throw AuthException(tr("Не удалось открыть браузер. Откройте ссылку вручную:\n$url", "Could not open the browser. Open the link manually:\n$url"))
        }
    }
}

@Serializable
private data class MsTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
private data class XboxResponse(
    @SerialName("Token") val token: String = "",
    @SerialName("DisplayClaims") val displayClaims: DisplayClaims? = null,
    @SerialName("XErr") val xErr: Long? = null,
)

@Serializable
private data class DisplayClaims(val xui: List<Xui> = emptyList())

@Serializable
private data class Xui(val uhs: String = "")

@Serializable
private data class McLoginResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 86_400,
)

@Serializable
private data class McProfile(
    val id: String = "",
    val name: String = "",
    val skins: List<McSkin> = emptyList(),
)

@Serializable
private data class McSkin(val url: String = "", val state: String = "")

@Serializable
private data class Entitlements(val items: List<EntitlementItem> = emptyList())

@Serializable
private data class EntitlementItem(val name: String = "")
