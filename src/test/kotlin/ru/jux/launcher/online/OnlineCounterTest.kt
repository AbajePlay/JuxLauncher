package ru.jux.launcher.online

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.jux.launcher.core.Json

class OnlineCounterTest {

    private fun parse(text: String) = OnlineCounter.parse(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun `reads the counts the site answers with`() {
        assertEquals(OnlineCount(128, 47), parse("""{"online": 128, "playing": 47}"""))
    }

    @Test
    fun `never shows less than the launcher itself or more players than online`() {
        assertEquals(OnlineCount(1, 0), parse("""{"online": 0, "playing": 0}"""))
        assertEquals(OnlineCount(3, 3), parse("""{"online": 3, "playing": 9}"""))
        assertEquals(OnlineCount(5, 0), parse("""{"online": 5}"""))
        assertThrows(IllegalStateException::class.java) { parse("""{"error": "bad id"}""") }
    }

    @Test
    fun `install ids look like what the site accepts`() {
        val id = OnlineCounter.newId()
        assertTrue(OnlineCounter.isValidId(id), id)
        assertEquals(32, id.length)
        assertFalse(OnlineCounter.isValidId("A".repeat(32)))
        assertFalse(OnlineCounter.isValidId("abc"))
    }
}
