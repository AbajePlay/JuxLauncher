package ru.jux.launcher.core

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import ru.jux.launcher.link.LaunchLink
import java.nio.file.Path

object UrlProtocol {

    private const val KEY = "Software\\Classes\\${LaunchLink.SCHEME}"

    fun register() {
        if (!Shell.isWindows) return
        val exe = Shell.appExecutable ?: return
        runCatching {
            val stale = entries(exe).filter { (key, values) -> values.any { (name, value) -> read(key, name) != value } }
            stale.forEach { (key, values) ->
                Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, key)
                values.forEach { (name, value) -> Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, name, value) }
            }
            if (stale.isNotEmpty()) Log.info("${LaunchLink.SCHEME}:// links now open $exe")
        }.onFailure { Log.warn("could not register ${LaunchLink.SCHEME}:// links: ${it.message}") }
    }

    internal fun entries(exe: Path): Map<String, Map<String, String>> = linkedMapOf(
        KEY to linkedMapOf("" to "URL:JuxLauncher", "URL Protocol" to ""),
        "$KEY\\DefaultIcon" to mapOf("" to "$exe,0"),
        "$KEY\\shell\\open\\command" to mapOf("" to "\"$exe\" \"%1\""),
    )

    private fun read(key: String, name: String): String? = runCatching {
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, name)) {
            Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, name)
        } else {
            null
        }
    }.getOrNull()
}
