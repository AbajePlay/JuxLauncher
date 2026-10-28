package ru.jux.launcher.friends

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.meta.LoaderKind

/** Где игрок сейчас: раз в минуту уходит на juxmc.ru, и друзья видят это в своём лаунчере. */
@Serializable
data class FriendPresence(
    val state: String,
    val address: String? = null,
    val version: String? = null,
    val loader: String? = null,
    @SerialName("game_nick") val gameNick: String? = null,
    val pack: String? = null,
    @SerialName("pack_name") val packName: String? = null,
) {
    companion object {
        val LAUNCHER = FriendPresence("launcher")

        private const val PACK_PREFIX = "${LaunchLink.SCHEME}://pack?"
        private const val MAX_PACK_NAME = 64
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".internal", ".home", ".home.arpa")

        /**
         * Игра запущена. [server] — адрес из лога игры; адрес в локальной сети друзьям не нужен и не уходит:
         * такая игра считается одиночной. Ссылка на сборку — только у сборки с модами.
         */
        fun playing(
            server: String?,
            version: String,
            loader: LoaderKind,
            gameNick: String?,
            pack: String? = null,
            packName: String? = null,
        ): FriendPresence {
            val address = server?.let(::publicAddress)
            val shared = pack?.takeIf { loader.hasMods && isPackLink(it) }
            return FriendPresence(
                state = if (address != null) "server" else "single",
                address = address,
                version = version,
                loader = loader.name.lowercase(),
                gameNick = gameNick?.takeIf { it.isNotBlank() },
                pack = shared,
                packName = shared?.let { packName?.trim()?.take(MAX_PACK_NAME)?.ifEmpty { null } },
            )
        }

        /** Ссылка вида «Поделиться сборкой», в которой есть хоть один мод. */
        internal fun isPackLink(uri: String): Boolean {
            if (!uri.startsWith(PACK_PREFIX) || uri.length > LaunchLink.MAX_LENGTH) return false
            return (LaunchLink.parse(uri) as? LaunchLink.SharedPack)?.mods?.isNotEmpty() == true
        }

        /** Адрес сервера в интернете в виде host[:port]; null — локальный, частный или вовсе не адрес. */
        internal fun publicAddress(raw: String): String? {
            val address = raw.trim().lowercase().removeSuffix(".")
            if (!LaunchLink.isAddress(address)) return null
            val host = address.substringBefore(':').removeSuffix(".")
            if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return null
            if (IPV4.matches(host)) return address.takeIf { isPublicIpv4(host) }
            // Имя без точки — компьютер в локальной сети, а не сервер в интернете
            if ('.' !in host || host.all { it.isDigit() || it == '.' }) return null
            return address
        }

        private fun isPublicIpv4(host: String): Boolean {
            val octets = host.split('.').map { it.toInt() }
            if (octets.any { it > 255 }) return false
            val (a, b, c) = octets
            return when {
                a == 0 || a == 10 || a == 127 || a >= 224 -> false
                a == 100 && b in 64..127 -> false
                a == 169 && b == 254 -> false
                a == 172 && b in 16..31 -> false
                a == 192 && b == 168 -> false
                a == 192 && b == 0 && (c == 0 || c == 2) -> false
                a == 198 && (b == 18 || b == 19) -> false
                a == 198 && b == 51 && c == 100 -> false
                a == 203 && b == 0 && c == 113 -> false
                else -> true
            }
        }
    }
}
