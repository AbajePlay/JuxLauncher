package ru.jux.launcher.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.jux.launcher.core.toHex
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes

class UpdaterTest {

    private val sha = "a".repeat(64)

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertTrue(Updater.isNewer("1.0.1", "1.0"))
        assertTrue(Updater.isNewer("v2.0.0", "1.9.0"))
        assertTrue(Updater.isNewer("1.1.0", "1.1.0-beta"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.1.0-beta", "1.1.0"))
        assertFalse(Updater.isNewer("0.9", "1.0.0"))
    }

    @Test
    fun `an update does not bring back a removed desktop shortcut`() {
        val msi = Path.of("JuxLauncher-1.5.5.msi")
        val log = Path.of("install.log")
        assertEquals("/i \"JuxLauncher-1.5.5.msi\" /qn /norestart /l*v \"install.log\"", Updater.msiArguments(msi, desktopShortcut = true, log))
        assertEquals(
            "/i \"JuxLauncher-1.5.5.msi\" /qn /norestart JP_INSTALL_DESKTOP_SHORTCUT=\"\" /l*v \"install.log\"",
            Updater.msiArguments(msi, desktopShortcut = false, log),
        )
    }

    @Test
    fun `a feed has to name an https installer and its hash`() {
        Updater.validate(UpdateManifest("1.2.0", "https://example.com/JuxLauncher-1.2.0.msi", sha))
        assertThrows<IOException> { Updater.validate(UpdateManifest("1.2.0", "http://example.com/a.msi", sha)) }
        assertThrows<IOException> { Updater.validate(UpdateManifest("1.2.0", "https://example.com/a.msi", "abc")) }
        assertThrows<IOException> { Updater.validate(UpdateManifest("../1.2.0", "https://example.com/a.msi", sha)) }
    }

    @Test
    fun `the installer is kept only when its hash matches`() {
        val payload = ByteArray(300_000) { (it * 31).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/JuxLauncher-9.9.9.msi") { exchange ->
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/JuxLauncher-9.9.9.msi"
            val hash = MessageDigest.getInstance("SHA-256").digest(payload).toHex()

            val file = runBlocking { Updater.download(UpdateManifest("9.9.9", url, hash)) }
            assertArrayEquals(payload, file.readBytes())

            val wrong = UpdateManifest("9.9.8", url, sha)
            assertThrows<IOException> { runBlocking { Updater.download(wrong) } }
            assertTrue(file.parent.listDirectoryEntries().none { it.fileName.toString().startsWith("JuxLauncher-9.9.8") })
            assertTrue(file.exists())
        } finally {
            server.stop(0)
        }
    }
}
