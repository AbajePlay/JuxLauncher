package ru.jux.launcher.friends

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.jux.launcher.core.Json
import ru.jux.launcher.online.JuxSite
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

class FriendsApiTest {

    private class Seen(val path: String, val auth: String?, val body: String)

    private val seen = ConcurrentLinkedQueue<Seen>()
    private val answers = HashMap<String, Pair<Int, String>>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            seen += Seen(path, exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readBytes().decodeToString())
            val (code, body) = answers[path] ?: (404 to """{"error": "bad_request"}""")
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }
    private val origin = "http://127.0.0.1:${server.address.port}"
    private val api = FriendsApi(origin)

    @AfterEach
    fun stop() = server.stop(0)

    private fun answer(path: String, code: Int, body: String) {
        answers["/api/v1/$path"] = code to body
    }

    @Test
    fun `link start sends the install and opens only the own site`() {
        answer("launcher/link/start/", 200, """{"code": "JX4-7KQ", "poll": "secret", "url": "$origin/launcher/link/?code=JX4-7KQ", "expires_in": 600, "interval": 3}""")
        val start = api.start("0123456789abcdef0123456789abcdef", "1.9.0", "Windows")
        assertEquals(LinkStart("JX4-7KQ", "secret", "$origin/launcher/link/?code=JX4-7KQ", 600, 3), start)
        val body = Json.parseToJsonElement(seen.single().body).jsonObject
        assertEquals(setOf("install", "version", "os"), body.keys)
        assertNull(seen.single().auth)

        answer("launcher/link/start/", 200, """{"code": "JX4-7KQ", "poll": "secret", "url": "https://evil.example/phish"}""")
        assertEquals("$origin/launcher/link/?code=JX4-7KQ", api.start("0123456789abcdef0123456789abcdef", "1.9.0", "Windows").url)
    }

    @Test
    fun `poll reads every status of the contract`() {
        val path = "launcher/link/poll/"
        answer(path, 200, """{"status": "pending"}""")
        assertEquals(LinkPoll.Pending, api.poll("secret"))
        assertEquals("""{"poll":"secret"}""", seen.last().body)
        answer(path, 200, """{"status": "linked", "token": "tok", "nick": "Abaje"}""")
        assertEquals(LinkPoll.Linked("tok", "Abaje"), api.poll("secret"))
        answer(path, 200, """{"status": "denied"}""")
        assertEquals(LinkPoll.Denied, api.poll("secret"))
        answer(path, 200, """{"status": "expired"}""")
        assertEquals(LinkPoll.Expired, api.poll("secret"))
        answer(path, 200, """{"status": "linked"}""")
        assertThrows(FriendsException::class.java) { api.poll("secret") }
    }

    @Test
    fun `heartbeat carries the token and the presence and returns the list`() {
        answer("launcher/friends/", 200, SAMPLE)
        val presence = FriendPresence.playing("mc.virtusmine.fun", "26.2", ru.jux.launcher.meta.LoaderKind.VANILLA, "Abaje")
        val snapshot = api.heartbeat("tok", presence)

        val request = seen.single()
        assertEquals("Bearer tok", request.auth)
        assertEquals(
            """{"presence":{"state":"server","address":"mc.virtusmine.fun","version":"26.2","loader":"vanilla","game_nick":"Abaje"}}""",
            request.body,
        )
        assertEquals("Abaje", snapshot.me?.nick)
        assertEquals(FriendSettings(showWhere = true, showOnline = false, acceptRequests = true), snapshot.settings)
        val kirill = snapshot.friends.first()
        assertEquals(FriendState.SERVER, kirill.state)
        assertEquals("VirtusMine", kirill.serverName)
        assertEquals("1.21.4", kirill.serverMin)
        assertEquals("26.2", kirill.serverMax)
        assertTrue(kirill.joinable)
        assertEquals(2, snapshot.onlineCount)
        assertEquals(listOf("Polina"), snapshot.incoming.map { it.nick })
        assertEquals(listOf("Egor_PvP"), snapshot.outgoing.map { it.nick })
    }

    @Test
    fun `the list is read defensively`() {
        val snapshot = FriendsApi.parseSnapshot(SAMPLE)
        val (_, nastya, lexa, future) = snapshot.friends
        // Голова и профиль только из сети: file:// прочитал бы или открыл файл с диска
        assertNull(nastya.head)
        assertNull(nastya.profile)
        assertEquals("https://juxmc.ru/profile/kirill_play-2/", snapshot.friends.first().profile)
        assertEquals(1, snapshot.blocked)
        assertEquals(ru.jux.launcher.meta.LoaderKind.FABRIC, nastya.loaderKind)
        assertEquals("Выживание с друзьями", nastya.sharedPack?.name)
        assertEquals(FriendState.OFFLINE, lexa.state)
        assertNull(lexa.seen)
        assertFalse(lexa.joinable)
        // Статус из будущей версии сайта — не в сети, а не падение
        assertEquals(FriendState.OFFLINE, future.state)
        assertEquals(ru.jux.launcher.meta.LoaderKind.VANILLA, future.loaderKind)
    }

    @Test
    fun `refusals come back with the code of the contract`() {
        answer("launcher/friends/request/", 404, """{"error": "not_found"}""")
        val missing = assertThrows(FriendsException::class.java) { api.act("tok", FriendAction.REQUEST, "Nobody") }
        assertEquals("not_found", missing.code)
        assertEquals("""{"nick":"Nobody"}""", seen.last().body)
        assertEquals("Bearer tok", seen.last().auth)

        answer("launcher/friends/accept/", 401, "")
        assertTrue(assertThrows(FriendsException::class.java) { api.act("tok", FriendAction.ACCEPT, "Polina") }.unauthorized)
        answer("launcher/friends/remove/", 429, "")
        assertEquals("rate_limited", assertThrows(FriendsException::class.java) { api.act("tok", FriendAction.REMOVE, "Lexa") }.code)
        answer("launcher/friends/block/", 500, "<html>oops</html>")
        assertEquals("unreachable", assertThrows(FriendsException::class.java) { api.act("tok", FriendAction.BLOCK, "Lexa") }.code)

        answer("launcher/friends/cancel/", 200, """{"ok": true}""")
        api.act("tok", FriendAction.CANCEL, "Egor_PvP")
        assertEquals("/api/v1/launcher/friends/cancel/", seen.last().path)
    }

    @Test
    fun `settings send only what changed`() {
        answer("launcher/friends/settings/", 200, """{"settings": {"show_where": false, "show_online": true, "accept_requests": true}}""")
        val settings = api.settings("tok", showWhere = false)
        assertEquals("""{"show_where":false}""", seen.last().body)
        assertFalse(settings.showWhere)

        answer("launcher/unlink/", 200, """{"ok": true}""")
        api.unlink("tok")
        assertEquals("/api/v1/launcher/unlink/", seen.last().path)
        assertEquals("Bearer tok", seen.last().auth)
    }

    @Test
    fun `a dead site is unreachable, not a crash`() {
        server.stop(0)
        assertEquals("unreachable", assertThrows(FriendsException::class.java) { api.heartbeat("tok", FriendPresence.LAUNCHER) }.code)
    }

    @Test
    fun `the site origin can be pointed at a local copy`() {
        assertEquals(JuxSite.ORIGIN, JuxSite.originOf(null))
        assertEquals(JuxSite.ORIGIN, JuxSite.originOf("ftp://example.org"))
        assertEquals("http://127.0.0.1:8000", JuxSite.originOf(" http://127.0.0.1:8000/ "))
        assertEquals("https://juxmc.ru/profile/abaje/", JuxSite.page("profile/abaje/"))
    }

    private companion object {
        val SAMPLE = """
            {
              "me": {"nick": "Abaje", "head": "https://juxmc.ru/forum/avatar/Abaje/64.png"},
              "settings": {"show_where": true, "show_online": false, "accept_requests": true},
              "blocked": 1,
              "friends": [
                {"nick": "Kirill_Play", "head": "https://juxmc.ru/forum/avatar/Kirill_Play/64.png", "profile": "https://juxmc.ru/profile/kirill_play-2/", "state": "server",
                 "address": "mc.virtusmine.fun", "server_name": "VirtusMine", "server_min": "1.21.4", "server_max": "26.2",
                 "version": "26.2", "loader": "vanilla", "game_nick": "Kirill_Play", "pack": null, "pack_name": null, "seen": 1761650000},
                {"nick": "Nastya", "head": "file:///C:/Windows/win.ini", "profile": "file:///C:/Windows/win.ini", "state": "server", "address": "mc.virtusmine.fun",
                 "version": "1.21.11", "loader": "fabric", "game_nick": "nastya_mc",
                 "pack": "juxlauncher://pack?name=%D0%92%D1%8B%D0%B6%D0%B8%D0%B2%D0%B0%D0%BD%D0%B8%D0%B5+%D1%81+%D0%B4%D1%80%D1%83%D0%B7%D1%8C%D1%8F%D0%BC%D0%B8&version=1.21.11&loader=fabric&mods=AANobbMI",
                 "pack_name": "Выживание с друзьями"},
                {"nick": "Lexa", "state": "offline", "seen": null},
                {"nick": "Future", "state": "teleporting", "loader": "something"}
              ],
              "incoming": [{"nick": "Polina", "head": "https://juxmc.ru/forum/avatar/Polina/64.png", "at": 1761650000}],
              "outgoing": [{"nick": "Egor_PvP", "at": 1761650000}, {"nick": ""}]
            }
        """.trimIndent()
    }
}
