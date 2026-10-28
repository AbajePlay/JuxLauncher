package ru.jux.launcher.core

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import ru.jux.launcher.link.LaunchLink
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText

object UrlProtocol {

    private const val KEY = "Software\\Classes\\${LaunchLink.SCHEME}"
    private const val DESKTOP_FILE = "juxlauncher-link.desktop"

    /** На macOS схему объявляет Info.plist приложения, здесь регистрировать нечего. */
    fun register() {
        val exe = Shell.appExecutable ?: return
        when {
            Shell.isWindows -> registerInRegistry(exe)
            Shell.isLinux -> registerDesktopEntry(exe)
        }
    }

    private fun registerInRegistry(exe: Path) {
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

    /** Linux: скрытый ярлык приложения с обработчиком x-scheme-handler/jux, как у браузеров и Steam. */
    private fun registerDesktopEntry(exe: Path) {
        runCatching {
            val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".local", "share")
            val file = dataHome.resolve("applications").resolve(DESKTOP_FILE)
            val entry = desktopEntry(exe)
            if (file.exists() && file.readText() == entry) return
            file.writeAtomically(entry)
            val mime = ProcessBuilder("xdg-mime", "default", DESKTOP_FILE, "x-scheme-handler/${LaunchLink.SCHEME}")
                .redirectErrorStream(true)
                .start()
            if (!mime.waitFor(10, TimeUnit.SECONDS)) mime.destroyForcibly()
            Log.info("${LaunchLink.SCHEME}:// links now open $exe")
        }.onFailure { Log.warn("could not register ${LaunchLink.SCHEME}:// links: ${it.message}") }
    }

    internal fun desktopEntry(exe: Path): String {
        // jpackage кладёт иконку рядом с bin: <папка>/lib/JuxLauncher.png
        val icon = exe.parent?.parent?.resolve("lib")?.resolve("JuxLauncher.png")?.takeIf { it.exists() }
        return listOfNotNull(
            "[Desktop Entry]",
            "Type=Application",
            "Name=JuxLauncher",
            "Exec=\"$exe\" %u",
            icon?.let { "Icon=$it" },
            "Terminal=false",
            "NoDisplay=true",
            "MimeType=x-scheme-handler/${LaunchLink.SCHEME};",
        ).joinToString("\n", postfix = "\n")
    }
}
