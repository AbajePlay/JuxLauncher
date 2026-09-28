package ru.jux.launcher.auth

import com.sun.jna.platform.win32.Crypt32Util
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Shell
import java.util.Base64

object TokenStore {

    private const val PLAIN_PREFIX = "plain:"
    private const val DPAPI_PREFIX = "dpapi:"

    fun protect(secret: String): String {
        if (secret.isEmpty()) return ""
        if (Shell.isWindows) {
            runCatching {
                val encrypted = Crypt32Util.cryptProtectData(secret.toByteArray(Charsets.UTF_8))
                return DPAPI_PREFIX + Base64.getEncoder().encodeToString(encrypted)
            }.onFailure { Log.warn("DPAPI encryption unavailable, storing token unprotected", it) }
        }
        return PLAIN_PREFIX + Base64.getEncoder().encodeToString(secret.toByteArray(Charsets.UTF_8))
    }

    fun reveal(stored: String): String {
        if (stored.isEmpty()) return ""
        return when {
            stored.startsWith(DPAPI_PREFIX) -> runCatching {
                val blob = Base64.getDecoder().decode(stored.removePrefix(DPAPI_PREFIX))
                String(Crypt32Util.cryptUnprotectData(blob), Charsets.UTF_8)
            }.getOrElse {
                Log.warn("could not decrypt stored token, sign-in required: ${it.message}")
                ""
            }

            stored.startsWith(PLAIN_PREFIX) -> runCatching {
                String(Base64.getDecoder().decode(stored.removePrefix(PLAIN_PREFIX)), Charsets.UTF_8)
            }.getOrDefault("")

            else -> stored
        }
    }
}
