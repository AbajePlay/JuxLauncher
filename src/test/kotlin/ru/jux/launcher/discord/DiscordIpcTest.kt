package ru.jux.launcher.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DiscordIpcTest {

    @Test
    fun `windows talks to discord through named pipes`() {
        val pipes = DiscordIpc.addresses(env = { null }, windows = true)
        assertEquals("""\\.\pipe\discord-ipc-0""", pipes.first())
        assertEquals(10, pipes.size)
    }

    @Test
    fun `macos and linux look for the socket in the runtime and temp folders`() {
        val env = mapOf("XDG_RUNTIME_DIR" to "/run/user/1000", "TMPDIR" to "/var/folders/xy/T/")
        val sockets = DiscordIpc.addresses(env = env::get, windows = false)

        assertEquals(Path.of("/run/user/1000", "discord-ipc-0").toString(), sockets.first())
        assertTrue(Path.of("/run/user/1000", "app/com.discordapp.Discord", "discord-ipc-0").toString() in sockets)
        assertTrue(Path.of("/var/folders/xy/T/", "discord-ipc-3").toString() in sockets)
        assertTrue(Path.of("/tmp", "snap.discord", "discord-ipc-0").toString() in sockets)
        assertEquals(sockets.distinct(), sockets)
    }
}
