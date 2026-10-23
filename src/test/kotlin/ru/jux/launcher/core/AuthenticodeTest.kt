package ru.jux.launcher.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

@EnabledOnOs(OS.WINDOWS)
class AuthenticodeTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `files without a signature read as unsigned`() {
        val text = dir.resolve("notes.txt").also { it.writeText("hello") }
        assertEquals(Authenticode.Status.Unsigned, Authenticode.status(text))

        val splash = Path.of(System.getProperty("compose.application.resources.dir"), "JuxUpdate.exe")
        assertEquals(Authenticode.Status.Unsigned, Authenticode.status(splash))
    }

    @Test
    fun `a signed exe is valid and a tampered copy is not`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
        assumeTrue(java.exists() && Authenticode.status(java) == Authenticode.Status.Valid, "the test JDK is not signed")

        val bytes = java.readBytes()
        val middle = bytes.size / 2
        bytes[middle] = (bytes[middle].toInt() xor 0xFF).toByte()
        val tampered = dir.resolve("java.exe").also { it.writeBytes(bytes) }
        assertEquals(Authenticode.Status.Invalid, Authenticode.status(tampered))
        Files.delete(tampered)
    }
}
