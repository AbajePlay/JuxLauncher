package ru.jux.launcher.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.jux.launcher.meta.RuleEnvironment
import java.nio.file.Path

class DownloadsTest {

    private fun env(os: String, arch: String = "x86_64") = RuleEnvironment(os, "", arch)

    @Test
    fun `each computer gets its own installer from the latest release`() {
        val latest = "https://github.com/AbajePlay/JuxLauncher/releases/latest/download"
        assertEquals("$latest/JuxLauncher.msi", Downloads.forThisComputer(env("windows"), null))
        assertEquals("$latest/JuxLauncher-macOS-AppleSilicon.dmg", Downloads.forThisComputer(env("osx", "arm64"), null))
        assertEquals("$latest/JuxLauncher-macOS-Intel.dmg", Downloads.forThisComputer(env("osx"), null))
    }

    @Test
    fun `linux gets the package it was installed from`() {
        val packaged = Path.of("/opt/juxlauncher/bin/JuxLauncher")
        assertEquals(Downloads.LINUX_DEB, Downloads.linuxPackage(packaged, hasDpkg = true))
        assertEquals(Downloads.LINUX_RPM, Downloads.linuxPackage(packaged, hasDpkg = false))
        assertEquals(Downloads.LINUX_TAR, Downloads.linuxPackage(Path.of("/home/player/Games/JuxLauncher/bin/JuxLauncher"), hasDpkg = true))
        assertEquals(Downloads.LINUX_TAR, Downloads.linuxPackage(null, hasDpkg = true))
    }
}
