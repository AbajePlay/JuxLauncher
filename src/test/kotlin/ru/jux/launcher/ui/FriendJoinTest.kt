package ru.jux.launcher.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.jux.launcher.core.PreloadResult
import ru.jux.launcher.core.Settings
import ru.jux.launcher.discord.Presence
import ru.jux.launcher.friends.Friend
import ru.jux.launcher.friends.FriendPresence
import ru.jux.launcher.friends.FriendState
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.meta.LoaderSupport
import ru.jux.launcher.meta.ManifestVersion
import ru.jux.launcher.meta.VersionManifest
import ru.jux.launcher.packs.Modpack
import java.nio.file.Path

class FriendJoinTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val survival = Modpack(
        id = "survival",
        title = "Выживание с друзьями",
        version = "",
        gameVersion = "1.21.11",
        loader = LoaderKind.FABRIC,
        projectId = "",
        versionId = "",
    )

    private fun state(packs: List<Modpack> = emptyList()) = LauncherState(
        scope,
        PreloadResult(
            manifest = VersionManifest(
                latest = VersionManifest.Latest(release = "26.3"),
                versions = listOf("26.3", "26.2", "26.1", "1.21.11", "1.21.10", "1.21.4", "1.21.3", "1.20.1")
                    .map { ManifestVersion(id = it, url = "https://example.invalid/$it.json") },
            ),
            installed = mapOf("1.21.11" to 1L, "26.3" to 1L),
            profiles = setOf("1.21.11", "26.3"),
            loaderSupport = LoaderSupport(fabric = setOf("26.3", "1.21.11")),
            packs = packs,
        ),
    )

    private val kirill = Friend(
        nick = "Kirill_Play",
        state = FriendState.SERVER,
        address = "mc.virtusmine.fun",
        serverName = "VirtusMine",
        serverMin = "1.21.4",
        serverMax = "26.2",
        version = "26.2",
        loader = "vanilla",
        gameNick = "Kirill_Play",
    )

    @BeforeEach
    @AfterEach
    fun forget() = Settings.update { it.copy(serverVersions = emptyMap(), lastVersionId = null, lastPack = null) }

    @Test
    fun `join goes in with the friend's version and loader`() {
        val state = state()
        assertEquals("26.2#VANILLA", state.friendJoinEntry(kirill).key)
        assertEquals("1.21.11#FABRIC", state.friendJoinEntry(kirill.copy(version = "1.21.11", loader = "fabric")).key)
        // Версии друг не показал — заходим с верхней, которую пускает сервер
        assertEquals("26.2#VANILLA", state.friendJoinEntry(kirill.copy(version = null)).key)
    }

    @Test
    fun `a choice made for the server is remembered while the server accepts it`() {
        val state = state()
        Settings.update { it.copy(serverVersions = mapOf("mc.virtusmine.fun" to "1.21.11#VANILLA")) }
        assertEquals("1.21.11#VANILLA", state.friendJoinEntry(kirill).key)
        assertEquals("1.21.11#VANILLA", state.friendJoinKey(kirill))

        // Сервер перестал пускать эту версию — снова как у друга
        assertEquals("26.2#VANILLA", state.friendJoinEntry(kirill.copy(serverMin = "26.1")).key)
        // Выбор для другого сервера сюда не переходит
        assertEquals("26.2#VANILLA", state.friendJoinEntry(kirill.copy(address = "play.example.org")).key)
    }

    @Test
    fun `the menu puts the friend's version first and greys out what the server won't take`() {
        val state = state(listOf(survival))
        val menu = state.friendJoinMenu(kirill)
        assertEquals("26.2", menu.first().title)
        assertEquals("как у Kirill_Play", menu.first().note)
        val installed = menu.drop(1).associateBy { it.entry?.key }
        assertTrue(installed.getValue("1.21.11#VANILLA").fits)
        assertTrue(installed.getValue("pack:survival").fits)
        assertFalse(installed.getValue("26.3#VANILLA").fits)
        assertEquals("сервер не пустит", installed.getValue("26.3#VANILLA").note)
        // Подходящие выше неподходящих
        assertEquals(menu.drop(1).sortedBy { !it.fits }, menu.drop(1))
    }

    @Test
    fun `another version offers every release in range and own packs on them`() {
        val state = state(listOf(survival))
        val options = state.friendJoinOptions(kirill)
        assertEquals(
            listOf("26.2#VANILLA", "26.1#VANILLA", "1.21.11#VANILLA", "pack:survival", "1.21.10#VANILLA", "1.21.4#VANILLA"),
            options.map { it.entry?.key },
        )
        assertEquals("как у Kirill_Play · новая", options.first().note)
        assertEquals("есть", options[2].note)
        assertEquals("сборка", options[3].note)
    }

    @Test
    fun `a server without a known range takes its versions from the ping`() {
        assertEquals("1.21.4" to "26.2", rangeOf("1.21.4 - 26.2"))
        assertEquals("1.7.2" to "1.21.4", rangeOf("Velocity 3.4.0 1.7.2-1.21.4"))
        assertEquals(null to "1.21.4", rangeOf("Paper 1.21.4"))
        assertNull(rangeOf("Velocity 3.4.0-SNAPSHOT"))
        assertNull(rangeOf(""))

        val state = state()
        val unknown = kirill.copy(serverMin = null, serverMax = null)
        val server = state.friendServer(unknown)
        assertNull(server.version)
        assertTrue(server.fits("1.20.1"))
        assertEquals(8, state.friendJoinOptions(unknown).count { it.entry?.pack == null })
    }

    @Test
    fun `the friend's own pack is joined straight away once it is downloaded`() {
        val link = LaunchLink.SharedPack("Выживание с друзьями", "1.21.11", LoaderKind.FABRIC, mods = listOf("AANobbMI"))
        val nastya = kirill.copy(nick = "Nastya", version = "1.21.11", loader = "fabric", pack = link.toUri(), packName = link.name)

        val without = state()
        assertNull(without.ownedPackOf(link))
        assertNull(without.friendJoinKey(nastya))
        val first = without.friendJoinMenu(nastya).first()
        assertEquals("Выживание с друзьями", first.title)
        assertNull(first.entry)

        val with = state(listOf(survival))
        assertEquals(survival, with.ownedPackOf(link))
        assertEquals("pack:survival", with.friendJoinKey(nastya))
        // Другая версия игры у той же сборки — уже другая сборка
        assertNull(with.ownedPackOf(link.copy(version = "1.21.10")))
    }

    @Test
    fun `friends see the client that is on a server`() {
        val state = state()
        val idle = ProcessBuilder("cmd", "/c", "exit").start()
        val single = RunningGame(state.entryFor("26.3", LoaderKind.FABRIC), idle, Path.of("a.log"), 0, presence(null), "Abaje")
        val online = RunningGame(state.entryFor("26.2", LoaderKind.VANILLA), idle, Path.of("b.log"), 0, presence("mc.virtusmine.fun"), "Abaje_2")
        val lan = RunningGame(state.entryFor("26.2", LoaderKind.VANILLA), idle, Path.of("c.log"), 0, presence("192.168.0.4"), "Abaje")

        assertEquals(FriendPresence.LAUNCHER, friendPresenceOf(emptyList()))
        assertEquals(FriendPresence("single", null, "26.3", "fabric", "Abaje"), friendPresenceOf(listOf(single)))
        assertEquals(FriendPresence("server", "mc.virtusmine.fun", "26.2", "vanilla", "Abaje_2"), friendPresenceOf(listOf(online, single)))
        assertEquals("single", friendPresenceOf(listOf(lan)).state)
        assertNull(friendPresenceOf(listOf(lan)).address)
    }

    private fun presence(server: String?) = Presence.Playing(versionId = "26.2", loaderLabel = null, server = server, mods = 0, startedAt = 0)
}
