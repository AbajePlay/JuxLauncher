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
import ru.jux.launcher.net.Http
import java.awt.Desktop
import java.net.URI
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

            onStage("Открываю браузер для входа")
            openBrowser(url)

            val code = receiver.awaitCode(expectedState = state, timeoutMillis = 5 * 60_000)

            onStage("Обмен кода на токен")
            val msToken = exchangeCode(code, verifier, receiver.redirectUri)
            finish(msToken, onStage)
        }
    }

    suspend fun refresh(refreshToken: String, onStage: (String) -> Unit = {}): Account =
        withContext(Dispatchers.IO) {
            if (!AuthConfig.isConfigured) throw AuthException(AuthConfig.SETUP_HINT)
            onStage("Обновление сессии")
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .add("scope", SCOPE)
                .build()
            val response = postForm(TOKEN, body)
            val parsed = Json.decodeFromString<MsTokenResponse>(response)
            if (parsed.error != null) {
                throw AuthException("Сессия истекла, войдите заново (${parsed.error})")
            }
            finish(parsed, onStage)
        }

    private fun finish(msToken: MsTokenResponse, onStage: (String) -> Unit): Account {
        onStage("Вход в Xbox Live")
        val (xblToken, userHash) = authenticateXbl(msToken.accessToken)

        onStage("Проверка XSTS")
        val xstsToken = authorizeXsts(xblToken)

        onStage("Вход в Minecraft")
        val mcToken = loginWithXbox(userHash, xstsToken)

        onStage("Проверка лицензии")
        requireOwnership(mcToken.accessToken)

        onStage("Загрузка профиля")
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
            throw AuthException("Microsoft отклонил вход: ${parsed.errorDescription ?: parsed.error}")
        }
        if (parsed.accessToken.isBlank()) throw AuthException("Microsoft не вернул токен доступа")
        return parsed
    }

    private fun authenticateXbl(msAccessToken: String): Pair<String, String> {
        val payload = """
            {"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=$msAccessToken"},
             "RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}
        """.trimIndent()
        val parsed = Json.decodeFromString<XboxResponse>(postJson(XBL, payload))
        val hash = parsed.displayClaims?.xui?.firstOrNull()?.uhs
            ?: throw AuthException("Xbox Live не вернул идентификатор пользователя")
        if (parsed.token.isBlank()) throw AuthException("Xbox Live не вернул токен")
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
        if (code !in 200..299) throw AuthException("XSTS вернул HTTP $code")

        val parsed = Json.decodeFromString<XboxResponse>(body)
        if (parsed.token.isBlank()) throw AuthException("XSTS не вернул токен")
        return parsed.token
    }

    private fun describeXErr(xErr: Long?): String = when (xErr) {
        2148916233L -> "К этому аккаунту Microsoft не привязан профиль Xbox. " +
            "Зайдите на minecraft.net под этим аккаунтом и создайте профиль."
        2148916235L -> "Xbox Live недоступен в стране вашего аккаунта."
        2148916236L, 2148916237L -> "Аккаунту требуется подтверждение возраста (Xbox adult verification)."
        2148916238L -> "Детский аккаунт: добавьте его в семейную группу Microsoft."
        2148916227L -> "Аккаунт заблокирован за нарушение правил Xbox Live."
        else -> "Xbox Live отклонил вход" + (xErr?.let { " (код $it)" } ?: "")
    }

    private fun loginWithXbox(userHash: String, xstsToken: String): McLoginResponse {
        val payload = """{"identityToken":"XBL3.0 x=$userHash;$xstsToken"}"""
        val (code, body) = postJsonRaw(MC_LOGIN, payload)
        if (code == 403) {
            throw AuthException(
                "Minecraft не пускает это приложение Azure (HTTP 403). Новое приложение нужно " +
                    "отдельно одобрить у Mojang: заявка на aka.ms/mce-reviewappid, подробности в README."
            )
        }
        if (code !in 200..299) throw AuthException("Minecraft отклонил вход (HTTP $code)")

        val parsed = Json.decodeFromString<McLoginResponse>(body)
        if (parsed.accessToken.isBlank()) throw AuthException("Minecraft не выдал сессионный токен")
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
                "На этом аккаунте Microsoft нет лицензии Minecraft: Java Edition. " +
                    "Если игра куплена на другом аккаунте — войдите под ним."
            )
        }
    }

    private fun fetchProfile(mcAccessToken: String): McProfile {
        val (code, body) = getRaw(MC_PROFILE, mcAccessToken)
        if (code == 404) {
            throw AuthException(
                "У аккаунта нет профиля Minecraft. Зайдите на minecraft.net и задайте никнейм."
            )
        }
        if (code !in 200..299) throw AuthException("Не удалось получить профиль (HTTP $code)")
        val profile = Json.decodeFromString<McProfile>(body)
        if (profile.id.isBlank() || profile.name.isBlank()) {
            throw AuthException("Сервис Minecraft вернул пустой профиль")
        }
        return profile
    }

    private fun postForm(url: String, body: FormBody): String {
        Http.client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code != 400) {
                throw AuthException("HTTP ${response.code} от $url")
            }
            return text
        }
    }

    private fun postJson(url: String, payload: String): String {
        val (code, body) = postJsonRaw(url, payload)
        if (code !in 200..299) throw AuthException("HTTP $code от $url")
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
        if (code !in 200..299) throw AuthException("HTTP $code от $url")
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
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }.onFailure { Log.debug("Desktop.browse failed: ${it.message}") }

        runCatching {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
        }.onFailure {
            throw AuthException("Не удалось открыть браузер. Откройте ссылку вручную:\n$url", it)
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
