package ru.jux.launcher.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class UrlProtocolTest {

    @Test
    fun `links open the installed exe with the link as its only argument`() {
        val exe = Path.of("C:\\Users\\Игрок\\AppData\\Local\\JuxLauncher\\JuxLauncher.exe")
        val entries = UrlProtocol.entries(exe)

        assertEquals(
            listOf(
                "Software\\Classes\\juxlauncher",
                "Software\\Classes\\juxlauncher\\DefaultIcon",
                "Software\\Classes\\juxlauncher\\shell\\open\\command",
            ),
            entries.keys.toList(),
        )
        assertEquals(mapOf("" to "URL:JuxLauncher", "URL Protocol" to ""), entries.getValue("Software\\Classes\\juxlauncher"))
        assertEquals("$exe,0", entries.getValue("Software\\Classes\\juxlauncher\\DefaultIcon").getValue(""))
        assertEquals(
            "\"C:\\Users\\Игрок\\AppData\\Local\\JuxLauncher\\JuxLauncher.exe\" \"%1\"",
            entries.getValue("Software\\Classes\\juxlauncher\\shell\\open\\command").getValue(""),
        )
    }

    @Test
    fun `linux links open the launcher through a hidden desktop entry`() {
        val entry = UrlProtocol.desktopEntry(Path.of("/opt/juxlauncher/bin/JuxLauncher"))
        val lines = entry.lines()

        assertEquals("[Desktop Entry]", lines.first())
        assertTrue("Exec=\"${Path.of("/opt/juxlauncher/bin/JuxLauncher")}\" %u" in lines)
        assertTrue("MimeType=x-scheme-handler/juxlauncher;" in lines)
        assertTrue("NoDisplay=true" in lines)
        assertTrue(entry.endsWith("\n"))
    }
}
