package ru.jux.launcher.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.writeText

class GameEventsTest {

    @Test
    fun `joining a server shows its address, default port hidden`() {
        assertEquals(
            GameEvent.JoinedServer("mc.virtusmine.fun"),
            GameEvents.parse("[20:41:50] [Render thread/INFO]: Connecting to mc.virtusmine.fun, 25565"),
        )
        assertEquals(
            GameEvent.JoinedServer("play.example.org:25570"),
            GameEvents.parse("""    <log4j:Message><![CDATA[Connecting to Play.Example.org, 25570]]></log4j:Message>"""),
        )
    }

    @Test
    fun `voice chat and other noise are ignored, singleplayer is noticed`() {
        assertNull(GameEvents.parse("[20:15:15] [Render thread/INFO]: [voicechat] Connecting to voice chat server: '95.182.102.54:24450'"))
        assertNull(GameEvents.parse("[20:36:01] [Render thread/INFO]: Loaded 10 advancements"))
        assertEquals(GameEvent.Singleplayer, GameEvents.parse("[21:00:00] [Server thread/INFO]: Starting integrated minecraft server version 1.21.11"))
    }

    @Test
    fun `launcher button and in-game join read the same`() {
        assertEquals("mc.virtusmine.fun", GameEvents.display("mc.virtusmine.fun"))
        assertEquals("mc.virtusmine.fun", GameEvents.display("MC.VirtusMine.fun:25565"))
        assertEquals("mc.example.org:25570", GameEvents.display("mc.example.org:25570"))
    }

    @Test
    fun `the tail reads only new complete lines`(@TempDir dir: Path) {
        val log = dir.resolve("latest-game.log").apply { writeText("first\nsecond") }
        val tail = GameEvents.Tail(log)
        assertEquals(listOf("first"), tail.lines())
        log.appendText(" half\nthird\n")
        assertEquals(listOf("second half", "third"), tail.lines())
        assertEquals(emptyList<String>(), tail.lines())
    }
}
