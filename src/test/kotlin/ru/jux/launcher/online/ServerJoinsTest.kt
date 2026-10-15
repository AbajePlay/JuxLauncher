package ru.jux.launcher.online

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.jux.launcher.core.Json
import ru.jux.launcher.net.Http
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

class ServerJoinsTest {

    @Test
    fun `the report carries the address and nothing else`() {
        val body = Json.parseToJsonElement(ServerJoins.body("mc.virtusmine.fun")!!).jsonObject
        assertEquals(setOf("address"), body.keys)
        assertEquals("""{"address":"mc.virtusmine.fun"}""", ServerJoins.body("mc.virtusmine.fun"))
    }

    @Test
    fun `the address is sent the way the player would type it`() {
        assertEquals("""{"address":"play.example.org:25570"}""", ServerJoins.body("  Play.Example.ORG:25570 "))
        assertEquals("""{"address":"mc.example.org:25565"}""", ServerJoins.body("mc.example.org:25565"))
    }

    @Test
    fun `nothing is sent for an address that is not one`() {
        assertNull(ServerJoins.body(""))
        assertNull(ServerJoins.body("mc.example.org:99999"))
        assertNull(ServerJoins.body("two words"))
    }

    @Test
    fun `the report is a json post from the launcher and a dead site stays quiet`() {
        val seen = ConcurrentLinkedQueue<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/v1/launcher/join/") { exchange ->
            val headers = exchange.requestHeaders
            seen += listOf(
                exchange.requestMethod,
                headers.getFirst("Content-Type"),
                headers.getFirst("User-Agent"),
                exchange.requestBody.readBytes().decodeToString(),
            ).joinToString(" | ")
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/api/v1/launcher/join/"
        try {
            runBlocking { ServerJoins.report("mc.example.org", url)!!.join() }
        } finally {
            server.stop(0)
        }
        assertEquals(
            listOf("POST | application/json; charset=utf-8 | ${Http.USER_AGENT} | {\"address\":\"mc.example.org\"}"),
            seen.toList(),
        )

        runBlocking { ServerJoins.report("mc.example.org", url)!!.join() }
        assertNull(ServerJoins.report("not an address", url))
    }

    @Test
    fun `the site is asked at the same origin as the online counter`() {
        assertEquals("https://juxmc.ru/api/v1/launcher/join/", JuxSite.api("launcher/join/"))
        assertEquals("https://juxmc.ru/api/v1/launcher/online/", JuxSite.api("launcher/online/"))
    }
}
