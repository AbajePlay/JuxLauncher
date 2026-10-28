package ru.jux.launcher.friends

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.jux.launcher.core.Paths
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.exists
import kotlin.io.path.readText

class FriendsTest {

    @Test
    fun `the token is stored encrypted and a revoked one signs out`() {
        val auth = ConcurrentLinkedQueue<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/api/v1/launcher/friends/accept/") { exchange ->
                auth += exchange.requestHeaders.getFirst("Authorization").orEmpty()
                val body = """{"error": "unauthorized"}""".toByteArray()
                exchange.sendResponseHeaders(401, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            start()
        }
        val previous = Friends.api
        Friends.api = FriendsApi("http://127.0.0.1:${server.address.port}")
        try {
            runBlocking { Friends.adopt(LinkPoll.Linked("secret-token-123", "Abaje")) }
            assertTrue(Paths.friendsFile.exists())
            assertFalse("secret-token-123" in Paths.friendsFile.readText())
            assertEquals("Abaje", Friends.account.value?.nick)

            Friends.show(null, null)
            Friends.load()
            assertEquals("Abaje", Friends.account.value?.nick)

            val refused = assertThrows(FriendsException::class.java) { runBlocking { Friends.act(FriendAction.ACCEPT, "Polina") } }
            assertTrue(refused.unauthorized)
            assertEquals(listOf("Bearer secret-token-123"), auth.toList())
            assertNull(Friends.account.value)
            assertFalse(Paths.friendsFile.exists())
        } finally {
            Friends.api = previous
            server.stop(0)
        }
    }
}
