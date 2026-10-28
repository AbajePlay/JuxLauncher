package ru.jux.launcher.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class PathsTest {

    private val home = Path.of("/home/player")

    @Test
    fun `official minecraft folder is where each system keeps it`() {
        assertEquals(Path.of("C:/Users/player/AppData/Roaming/.minecraft"), Paths.officialMinecraft(home, "C:/Users/player/AppData/Roaming", windows = true, mac = false))
        assertEquals(home.resolve(".minecraft"), Paths.officialMinecraft(home, null, windows = true, mac = false))
        assertEquals(home.resolve("Library/Application Support/minecraft"), Paths.officialMinecraft(home, null, windows = false, mac = true))
        assertEquals(home.resolve(".minecraft"), Paths.officialMinecraft(home, "ignored", windows = false, mac = false))
    }

    @Test
    fun `owner only files are hidden from other users`(@TempDir dir: Path) {
        assumeTrue(dir.fileSystem.supportedFileAttributeViews().contains("posix"))
        val file = dir.resolve("accounts.json")
        file.writeAtomically("{}", ownerOnly = true)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        file.writeAtomically("{\"accounts\":[]}", ownerOnly = true)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }
}
