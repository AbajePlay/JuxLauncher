package ru.jux.launcher.friends

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.jux.launcher.core.Json
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.meta.LoaderKind

class FriendPresenceTest {

    private val packLink = LaunchLink.SharedPack("Выживание с друзьями", "1.21.11", LoaderKind.FABRIC, mods = listOf("AANobbMI")).toUri()

    @Test
    fun `a server in the internet is sent as host and port`() {
        val presence = FriendPresence.playing("Mc.VirtusMine.fun.", "26.2", LoaderKind.VANILLA, "Abaje")
        assertEquals("server", presence.state)
        assertEquals("mc.virtusmine.fun", presence.address)
        assertEquals("play.example.org:25570", FriendPresence.publicAddress("play.example.org:25570"))
        assertEquals("8.8.8.8", FriendPresence.publicAddress("8.8.8.8"))
    }

    @Test
    fun `lan and private addresses never leave the computer`() {
        listOf(
            "192.168.1.5", "192.168.1.5:25565", "10.0.0.2", "172.16.4.1", "172.31.255.255", "127.0.0.1", "0.0.0.0",
            "169.254.10.1", "100.64.0.1", "224.0.0.1", "255.255.255.255", "198.18.0.1",
            "localhost", "localhost:25565", "minecraft.local", "server.lan", "box.home.arpa", "nas", "DESKTOP-ABC123",
            "999.1.1.1", "1.2.3", "[::1]", "fe80::1", "two words", "",
        ).forEach { address ->
            assertNull(FriendPresence.publicAddress(address), address)
            val presence = FriendPresence.playing(address, "26.2", LoaderKind.VANILLA, "Abaje")
            assertEquals("single", presence.state, address)
            assertNull(presence.address, address)
        }
        assertEquals("172.32.0.1", FriendPresence.publicAddress("172.32.0.1"))
    }

    @Test
    fun `singleplayer carries the version but no address`() {
        val presence = FriendPresence.playing(null, "26.3", LoaderKind.FABRIC, "Abaje")
        assertEquals(FriendPresence("single", null, "26.3", "fabric", "Abaje"), presence)
        assertEquals("""{"state":"single","version":"26.3","loader":"fabric","game_nick":"Abaje"}""", Json.encodeToString(presence))
        assertEquals("""{"state":"launcher"}""", Json.encodeToString(FriendPresence.LAUNCHER))
    }

    @Test
    fun `the pack link goes only with a pack that has mods`() {
        val modded = FriendPresence.playing("mc.virtusmine.fun", "1.21.11", LoaderKind.FABRIC, "nastya_mc", packLink, "Выживание с друзьями")
        assertEquals(packLink, modded.pack)
        assertEquals("Выживание с друзьями", modded.packName)

        assertNull(FriendPresence.playing("mc.virtusmine.fun", "26.2", LoaderKind.VANILLA, "Abaje", packLink, "26.2").pack)
        assertNull(FriendPresence.playing("mc.virtusmine.fun", "26.2", LoaderKind.OPTIFINE, "Abaje", packLink, "26.2").pack)
        val empty = LaunchLink.SharedPack("Пустая", "1.21.11", LoaderKind.FABRIC).toUri()
        assertNull(FriendPresence.playing(null, "1.21.11", LoaderKind.FABRIC, "Abaje", empty, "Пустая").pack)
        // Сборка с Modrinth делится ссылкой на установку, а сайт принимает только ссылку на свою сборку
        val install = LaunchLink.installUri("fabulously-optimized", ru.jux.launcher.link.LinkContent.MODPACK, "Fabulously Optimized")
        val fromModrinth = FriendPresence.playing(null, "1.21.11", LoaderKind.FABRIC, "Abaje", install, "Fabulously Optimized")
        assertNull(fromModrinth.pack)
        assertNull(fromModrinth.packName)
        assertNull(FriendPresence.playing(null, "1.21.11", LoaderKind.FABRIC, "Abaje", packLink + "x".repeat(LaunchLink.MAX_LENGTH), "x").pack)
    }

    @Test
    fun `a blank minecraft name is not sent`() {
        assertNull(FriendPresence.playing(null, "26.2", LoaderKind.VANILLA, " ").gameNick)
    }
}
