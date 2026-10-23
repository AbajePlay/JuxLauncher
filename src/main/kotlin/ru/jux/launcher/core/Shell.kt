package ru.jux.launcher.core

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinUser
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.system.exitProcess

object Shell {

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    val appExecutable: Path? by lazy {
        System.getProperty("jpackage.app-path")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?.takeIf { it.exists() }
    }

    fun openFolder(dir: Path, onError: (String) -> Unit) {
        runCatching { dir.createDirectories() }
            .onFailure {
                onError(tr("Не удалось создать папку $dir: ${it.message}", "Could not create the folder $dir: ${it.message}"))
                return
            }

        val path = dir.toAbsolutePath().toString()
        if (isWindows) {
            val started = runCatching { ProcessBuilder("explorer.exe", path).start() }
                .onFailure { Log.warn("explorer.exe failed: ${it.message}") }
                .isSuccess
            if (started) return
        }

        val viaDesktop = runCatching {
            if (!Desktop.isDesktopSupported()) return@runCatching false
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.OPEN)) return@runCatching false
            desktop.open(dir.toFile())
            true
        }.onFailure { Log.warn("Desktop.open failed: ${it.message}") }.getOrDefault(false)

        if (!viaDesktop) onError(tr("Не удалось открыть проводник. Папка: $dir", "Could not open Explorer. Folder: $dir"))
    }

    /** Открывает в браузере только http(s): ShellExecute по произвольной строке мог бы запустить файл. */
    fun browse(url: String): Boolean {
        if (!isWebLink(url)) {
            Log.warn("not opening a non-web link: $url")
            return false
        }
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return true
            }
        }.onFailure { Log.debug("Desktop.browse failed: ${it.message}") }
        if (!isWindows) return false
        return runCatching {
            val result = Shell32.INSTANCE.ShellExecute(null, "open", url, null, null, WinUser.SW_SHOWNORMAL)
            (result?.toLong() ?: 0L) > 32
        }.onFailure { Log.warn("ShellExecute failed: ${it.message}") }.getOrDefault(false)
    }

    internal fun isWebLink(url: String): Boolean =
        runCatching { URI(url) }.getOrNull()?.let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() } ?: false

    /**
     * Завершает процесс в обход обычного выхода JVM. Нужен короткоживущему процессу, который только передал
     * аргументы уже запущенному лаунчеру: при обычном выходе JVM записала бы архив классов (CDS) почти пустым,
     * и все следующие запуски грузились бы без него в несколько раз медленнее.
     */
    fun exitWithoutClassArchive(code: Int): Nothing {
        if (isWindows) runCatching { Kernel32.INSTANCE.TerminateProcess(Kernel32.INSTANCE.GetCurrentProcess(), code) }
        exitProcess(code)
    }
}
