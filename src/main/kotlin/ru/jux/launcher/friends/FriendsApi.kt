package ru.jux.launcher.friends

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.tr
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.net.Http
import ru.jux.launcher.online.JuxSite
import java.io.IOException
import java.net.URLEncoder

/** Ответ на начало входа: код для сайта и секрет, по которому лаунчер спрашивает, подтвердили ли его. */
@Serializable
data class LinkStart(
    val code: String,
    val poll: String,
    val url: String,
    @SerialName("expires_in") val expiresIn: Int = 600,
    val interval: Int = 3,
)

sealed interface LinkPoll {
    data object Pending : LinkPoll
    data class Linked(val token: String, val nick: String) : LinkPoll
    data object Denied : LinkPoll
    data object Expired : LinkPoll
}

@Serializable
enum class FriendState {
    @SerialName("server") SERVER,
    @SerialName("single") SINGLE,
    @SerialName("launcher") LAUNCHER,
    @SerialName("online") ONLINE,
    @SerialName("offline") OFFLINE;

    val playing: Boolean get() = this == SERVER || this == SINGLE
}

@Immutable
@Serializable
data class Friend(
    val nick: String,
    val head: String? = null,
    val state: FriendState = FriendState.OFFLINE,
    val address: String? = null,
    @SerialName("server_name") val serverName: String? = null,
    @SerialName("server_min") val serverMin: String? = null,
    @SerialName("server_max") val serverMax: String? = null,
    val version: String? = null,
    val loader: String? = null,
    @SerialName("game_nick") val gameNick: String? = null,
    val pack: String? = null,
    @SerialName("pack_name") val packName: String? = null,
    val seen: Long? = null,
    val profile: String? = null,
) {
    val loaderKind: LoaderKind get() = LoaderKind.entries.firstOrNull { it.name.equals(loader, ignoreCase = true) } ?: LoaderKind.VANILLA

    val serverTitle: String? get() = serverName ?: address

    /** Своя сборка друга с модами, если он ей делится. */
    val sharedPack: LaunchLink.SharedPack?
        get() = pack?.let(LaunchLink::parse) as? LaunchLink.SharedPack

    /** Можно зайти к нему на сервер. */
    val joinable: Boolean get() = state == FriendState.SERVER && address != null
}

@Immutable
@Serializable
data class FriendRequest(val nick: String, val head: String? = null, val at: Long? = null, val profile: String? = null)

@Serializable
data class FriendMe(val nick: String, val head: String? = null, val profile: String? = null)

@Serializable
data class FriendSettings(
    @SerialName("show_where") val showWhere: Boolean = true,
    @SerialName("show_online") val showOnline: Boolean = true,
    @SerialName("accept_requests") val acceptRequests: Boolean = true,
)

@Immutable
@Serializable
data class FriendsSnapshot(
    val me: FriendMe? = null,
    val settings: FriendSettings = FriendSettings(),
    val blocked: Int = 0,
    val friends: List<Friend> = emptyList(),
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
) {
    val onlineCount: Int get() = friends.count { it.state != FriendState.OFFLINE }
}

/** Отказ сайта: [code] — код из договора (`not_found`, `unauthorized`…), по нему показывается текст. */
class FriendsException(val code: String, val status: Int = 0) : IOException(textOf(code)) {
    val unauthorized: Boolean get() = code == UNAUTHORIZED

    companion object {
        const val UNAUTHORIZED = "unauthorized"
        const val RATE_LIMITED = "rate_limited"
        const val UNREACHABLE = "unreachable"

        fun textOf(code: String): String = when (code) {
            "bad_json", "bad_request" -> tr("Сайт не понял запрос — обнови лаунчер", "The site didn't understand the request — update the launcher")
            UNAUTHORIZED -> tr("Вход в juxmc.ru слетел — войди заново", "Your juxmc.ru sign-in has expired — sign in again")
            RATE_LIMITED -> tr("Слишком часто — подожди минуту", "Too many requests — wait a minute")
            "not_found" -> tr("Такого ника на juxmc.ru нет", "There's no such nickname on juxmc.ru")
            "self" -> tr("Это твой ник", "That's your own nickname")
            "blocked" -> tr("Этот игрок у тебя в заблокированных — разблокируй на сайте", "You've blocked this player — unblock them on the site")
            "closed" -> tr("Этот игрок не принимает заявки", "This player doesn't accept friend requests")
            "already_friends" -> tr("Вы уже друзья", "You're already friends")
            "limit" -> tr("Слишком много друзей или заявок", "Too many friends or pending requests")
            "expired" -> tr("Код устарел — начни вход заново", "The code has expired — start signing in again")
            "denied" -> tr("Вход отклонён на сайте", "Sign-in was declined on the site")
            else -> tr("juxmc.ru не ответил — проверь интернет", "juxmc.ru didn't respond — check your connection")
        }
    }
}

enum class FriendAction(val path: String) {
    REQUEST("launcher/friends/request/"),
    ACCEPT("launcher/friends/accept/"),
    DECLINE("launcher/friends/decline/"),
    CANCEL("launcher/friends/cancel/"),
    REMOVE("launcher/friends/remove/"),
    BLOCK("launcher/friends/block/"),
    UNBLOCK("launcher/friends/unblock/"),
}

/** Запросы к juxmc.ru по договору «Друзья»; [origin] подменяется в тестах и через JUX_SITE_ORIGIN. */
class FriendsApi(private val origin: String = JuxSite.origin) {

    fun start(install: String, version: String, os: String): LinkStart {
        val body = buildJsonObject {
            put("install", install)
            put("version", version)
            put("os", os)
        }
        val start = Json.decodeFromString<LinkStart>(post("launcher/link/start/", body.toString()))
        // Браузер открываем только на своём сайте, что бы ни пришло в ответе
        val url = start.url.takeIf { it.startsWith("$origin/") && Shell.isWebLink(it) }
            ?: "$origin/launcher/link/?code=" + URLEncoder.encode(start.code, Charsets.UTF_8)
        return start.copy(url = url)
    }

    fun poll(secret: String): LinkPoll {
        val reply = post("launcher/link/poll/", buildJsonObject { put("poll", secret) }.toString())
        val json = Json.parseToJsonElement(reply).jsonObject
        return when (json.string("status")) {
            "pending" -> LinkPoll.Pending
            "linked" -> LinkPoll.Linked(
                token = json.string("token")?.takeIf { it.isNotBlank() } ?: throw FriendsException("bad_reply"),
                nick = json.string("nick").orEmpty(),
            )
            "denied" -> LinkPoll.Denied
            "expired" -> LinkPoll.Expired
            else -> throw FriendsException("bad_reply")
        }
    }

    fun heartbeat(token: String, presence: FriendPresence): FriendsSnapshot {
        val body = buildJsonObject { put("presence", Json.encodeToJsonElement(FriendPresence.serializer(), presence)) }
        return parseSnapshot(post("launcher/friends/", body.toString(), token))
    }

    fun act(token: String, action: FriendAction, nick: String) {
        post(action.path, buildJsonObject { put("nick", nick) }.toString(), token)
    }

    fun settings(token: String, showWhere: Boolean? = null, showOnline: Boolean? = null, acceptRequests: Boolean? = null): FriendSettings {
        val body = buildJsonObject {
            showWhere?.let { put("show_where", it) }
            showOnline?.let { put("show_online", it) }
            acceptRequests?.let { put("accept_requests", it) }
        }
        val reply = Json.parseToJsonElement(post("launcher/friends/settings/", body.toString(), token)).jsonObject
        return reply["settings"]?.let { Json.decodeFromJsonElement(FriendSettings.serializer(), it) } ?: throw FriendsException("bad_reply")
    }

    fun unlink(token: String) {
        post("launcher/unlink/", "{}", token)
    }

    private fun post(path: String, body: String, token: String? = null): String {
        val reply = try {
            Http.postJsonReply("$origin/api/v1/$path", body, token)
        } catch (e: IOException) {
            throw FriendsException(FriendsException.UNREACHABLE).apply { initCause(e) }
        }
        if (reply.isSuccessful) return reply.body
        val code = runCatching { Json.parseToJsonElement(reply.body).jsonObject.string("error") }.getOrNull()
            ?: when (reply.code) {
                401 -> FriendsException.UNAUTHORIZED
                429 -> FriendsException.RATE_LIMITED
                else -> FriendsException.UNREACHABLE
            }
        throw FriendsException(if (reply.code == 401) FriendsException.UNAUTHORIZED else code, reply.code)
    }

    companion object {

        internal fun parseSnapshot(text: String): FriendsSnapshot {
            val parsed = Json.decodeFromString<FriendsSnapshot>(text)
            // Картинки и профили берём только из сети: адрес file:// в ответе прочитал бы или открыл файл с диска
            return parsed.copy(
                me = parsed.me?.let { it.copy(head = webOnly(it.head), profile = webOnly(it.profile)) },
                blocked = parsed.blocked.coerceAtLeast(0),
                friends = parsed.friends.filter { it.nick.isNotBlank() }
                    .map { it.copy(head = webOnly(it.head), profile = webOnly(it.profile), seen = it.seen?.takeIf { s -> s > 0 }) },
                incoming = parsed.incoming.filter { it.nick.isNotBlank() }.map { it.copy(head = webOnly(it.head), profile = webOnly(it.profile)) },
                outgoing = parsed.outgoing.filter { it.nick.isNotBlank() }.map { it.copy(head = webOnly(it.head), profile = webOnly(it.profile)) },
            )
        }

        private fun webOnly(url: String?): String? = url?.takeIf(Shell::isWebLink)

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
