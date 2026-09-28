package ru.jux.launcher.discord

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscordPresenceTest {

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun `in the launcher the card shows the logo and both buttons`() {
        val activity = DiscordPresence.activity(Presence.Launcher)
        assertEquals("В лаунчере", activity.text("details"))
        assertEquals(DiscordPresence.LOGO, activity["assets"]!!.jsonObject.text("large_image"))
        assertFalse("timestamps" in activity)
        val buttons = activity["buttons"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(DiscordPresence.DOWNLOAD_LABEL), buttons.map { it.text("label") })
        assertEquals(listOf("https://juxmc.ru/launcher/"), buttons.map { it.text("url") })
        assertTrue(buttons.all { it.text("label").length <= 32 })
    }

    @Test
    fun `in game it shows version, loader, mods and a timer`() {
        val activity = DiscordPresence.activity(
            Presence.Playing("1.21.11", "Fabric", server = null, mods = 21, startedAt = 1_790_000_000_123),
        )
        assertEquals("Minecraft 1.21.11 · Fabric", activity.text("details"))
        assertEquals("21 мод", activity.text("state"))
        assertEquals(1_790_000_000L, activity["timestamps"]!!.jsonObject["start"]!!.jsonPrimitive.long)
        val assets = activity["assets"]!!.jsonObject
        assertEquals(DiscordPresence.LOGO, assets.text("large_image"))
        assertFalse("small_image" in assets)
        assertEquals(1, activity["buttons"]!!.jsonArray.size)
    }

    @Test
    fun `server wins over mods, vanilla says so`() {
        val onServer = DiscordPresence.activity(Presence.Playing("1.21.11", "Fabric", "mc.virtusmine.fun", 20, 0))
        assertEquals("На сервере mc.virtusmine.fun", onServer.text("state"))
        val vanilla = DiscordPresence.activity(Presence.Playing("26.3", null, null, 0, 0))
        assertEquals("Minecraft 26.3", vanilla.text("details"))
        assertEquals("Ванильная игра", vanilla.text("state"))
        val emptyFabric = DiscordPresence.activity(Presence.Playing("26.3", "Fabric", null, 0, 0))
        assertEquals("Без модов", emptyFabric.text("state"))
    }

    @Test
    fun `a pack shows its name before the version`() {
        val activity = DiscordPresence.activity(Presence.Playing("26.2", "Fabric", null, 12, 0, pack = "Cobblemon"))
        assertEquals("Cobblemon · Minecraft 26.2", activity.text("details"))
        assertEquals("12 модов", activity.text("state"))
    }

    @Test
    fun `russian plural for mods`() {
        mapOf(1 to "мод", 2 to "мода", 4 to "мода", 5 to "модов", 11 to "модов", 12 to "модов", 21 to "мод", 22 to "мода", 111 to "модов", 101 to "мод")
            .forEach { (count, word) -> assertEquals(word, DiscordPresence.modsWord(count), "count $count") }
    }
}
