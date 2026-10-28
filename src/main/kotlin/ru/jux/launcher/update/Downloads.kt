package ru.jux.launcher.update

import ru.jux.launcher.core.Shell
import ru.jux.launcher.meta.RuleEnvironment
import java.nio.file.Files
import java.nio.file.Path

/** Установщики лежат в последнем релизе под постоянными именами, без номера версии. */
object Downloads {

    const val PAGE = "https://juxmc.ru/launcher/"
    private const val LATEST = "https://github.com/AbajePlay/JuxLauncher/releases/latest/download"

    const val WINDOWS = "JuxLauncher.msi"
    const val MAC_APPLE_SILICON = "JuxLauncher-macOS-AppleSilicon.dmg"
    const val MAC_INTEL = "JuxLauncher-macOS-Intel.dmg"
    const val LINUX_DEB = "JuxLauncher-Linux.deb"
    const val LINUX_RPM = "JuxLauncher-Linux.rpm"
    const val LINUX_TAR = "JuxLauncher-Linux.tar.gz"

    fun url(file: String): String = "$LATEST/$file"

    fun forThisComputer(env: RuleEnvironment = RuleEnvironment.current, app: Path? = Shell.appExecutable): String = url(
        when (env.osName) {
            "windows" -> WINDOWS
            "osx" -> if (env.osArch == "arm64") MAC_APPLE_SILICON else MAC_INTEL
            else -> linuxPackage(app)
        }
    )

    /** Пакеты deb и rpm ставят лаунчер в /opt/juxlauncher, а где-то ещё он лежит распакованным из архива. */
    internal fun linuxPackage(app: Path?, hasDpkg: Boolean = Files.exists(Path.of("/usr/bin/dpkg"))): String = when {
        app == null || !app.startsWith("/opt/juxlauncher") -> LINUX_TAR
        hasDpkg -> LINUX_DEB
        else -> LINUX_RPM
    }
}
