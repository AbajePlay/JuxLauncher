package ru.jux.launcher.core

import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.PointerByReference
import ru.jux.launcher.meta.LoaderKind
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists

object Shortcuts {

    val isAvailable: Boolean get() = Shell.isWindows && Shell.appExecutable != null

    private val ILLEGAL = Regex("""[\\/:*?"<>|]""")

    fun createOnDesktop(title: String, versionId: String, loader: LoaderKind, pack: String? = null): Path {
        val exe = Shell.appExecutable
            ?: throw IOException(tr("Ярлыки создаются только в установленном лаунчере, не при запуске из IDE", "Shortcuts work only in the installed launcher, not when run from an IDE"))
        val link = desktop().resolve(title.replace(ILLEGAL, "_").trim() + ".lnk")
        create(link, exe, PlayArguments.of(versionId, loader, pack), "JuxLauncher: $title")
        Log.info("desktop shortcut created: $link")
        return link
    }

    fun appOnDesktop(app: Path): Path = desktop().resolve(app.fileName.toString().substringBeforeLast('.') + ".lnk")

    internal fun create(link: Path, target: Path, arguments: String, description: String) {
        // Ярлык пишет сам Windows (IShellLink через COM), без PowerShell. COM поднимаем в отдельном
        // потоке, чтобы не менять модель потоков у пула корутин.
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            runCatching { ShellLink.save(link, target, arguments, target.parent, description) }.onFailure { failure.set(it) }
        }, "shortcut-writer")
        worker.start()
        worker.join(20_000)
        failure.get()?.let { throw IOException(tr("Не удалось создать ярлык: ${it.message}", "Could not create the shortcut: ${it.message}"), it) }
        if (worker.isAlive || !link.exists()) {
            throw IOException(tr("Не удалось создать ярлык", "Could not create the shortcut"))
        }
    }

    private fun desktop(): Path =
        runCatching { Path.of(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Desktop)) }
            .getOrElse { Path.of(System.getProperty("user.home"), "Desktop") }

    private object ShellLink {
        private val CLSID_SHELL_LINK = Guid.CLSID("{00021401-0000-0000-C000-000000000046}")
        private val IID_SHELL_LINK_W = Guid.IID("{000214F9-0000-0000-C000-000000000046}")
        private val IID_PERSIST_FILE = Guid.IID("{0000010B-0000-0000-C000-000000000046}")

        // Номера методов в vtable IShellLinkW и IPersistFile (0–2 — методы IUnknown).
        private const val SET_DESCRIPTION = 7
        private const val SET_WORKING_DIRECTORY = 9
        private const val SET_ARGUMENTS = 11
        private const val SET_ICON_LOCATION = 17
        private const val SET_PATH = 20
        private const val SAVE = 6

        fun save(link: Path, target: Path, arguments: String, workingDir: Path, description: String) {
            val init = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
            try {
                val created = PointerByReference()
                COMUtils.checkRC(Ole32.INSTANCE.CoCreateInstance(CLSID_SHELL_LINK, null, WTypes.CLSCTX_INPROC_SERVER, IID_SHELL_LINK_W, created))
                val shellLink = Com(created.value)
                try {
                    shellLink.call(SET_PATH, WString(target.toString()))
                    shellLink.call(SET_ARGUMENTS, WString(arguments))
                    shellLink.call(SET_WORKING_DIRECTORY, WString(workingDir.toString()))
                    shellLink.call(SET_ICON_LOCATION, WString(target.toString()), 0)
                    shellLink.call(SET_DESCRIPTION, WString(description))
                    val queried = PointerByReference()
                    COMUtils.checkRC(shellLink.QueryInterface(Guid.REFIID(IID_PERSIST_FILE), queried))
                    val persistFile = Com(queried.value)
                    try {
                        persistFile.call(SAVE, WString(link.toAbsolutePath().toString()), 1)
                    } finally {
                        persistFile.Release()
                    }
                } finally {
                    shellLink.Release()
                }
            } finally {
                if (COMUtils.SUCCEEDED(init)) Ole32.INSTANCE.CoUninitialize()
            }
        }

        private class Com(pointer: Pointer) : Unknown(pointer) {
            fun call(method: Int, vararg args: Any) {
                COMUtils.checkRC(WinNT.HRESULT(_invokeNativeInt(method, arrayOf(this.pointer, *args))))
            }
        }
    }
}

data class PlayRequest(val versionId: String, val loader: LoaderKind, val pack: String? = null)

object PlayArguments {

    fun of(versionId: String, loader: LoaderKind, pack: String? = null): String =
        "--play \"$versionId\"" +
            (if (loader.isModded) " --loader ${loader.name.lowercase()}" else "") +
            (if (pack != null) " --pack \"$pack\"" else "")

    fun parse(args: Array<String>): PlayRequest? {
        val version = args.valueAfter("--play")?.takeIf { it.isNotBlank() } ?: return null
        val loaderName = args.valueAfter("--loader")
        val loader = LoaderKind.entries.firstOrNull { it.name.equals(loaderName, ignoreCase = true) }
            ?: LoaderKind.VANILLA
        return PlayRequest(version, loader, args.valueAfter("--pack")?.takeIf { it.isNotBlank() })
    }

    private fun Array<String>.valueAfter(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }
}
