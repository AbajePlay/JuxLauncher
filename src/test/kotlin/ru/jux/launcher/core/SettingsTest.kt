package ru.jux.launcher.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.jux.launcher.meta.LoaderKind

class SettingsTest {

    @Test
    fun `a modded instance is named after game version and loader, not the loader build`() {
        assertEquals("26.3-fabric", Settings.gameDir("26.3", LoaderKind.FABRIC).fileName.toString())
        assertEquals("26.3-neoforge", Settings.gameDir("26.3", LoaderKind.NEOFORGE).fileName.toString())
    }

    @Test
    fun `recommended memory follows the RAM size on the box, not the few MB Windows keeps`() {
        assertEquals(8192, SettingsDefaults.recommendedMemory(32559))
        assertEquals(4096, SettingsDefaults.recommendedMemory(16264))
        assertEquals(2048, SettingsDefaults.recommendedMemory(8032))
        assertEquals(8192, SettingsDefaults.recommendedMemory(65300))
        assertEquals(2048, SettingsDefaults.recommendedMemory(3900))
    }

    @Test
    fun `a vanilla instance keeps the plain version name`() {
        assertEquals("26.3", Settings.gameDir("26.3").fileName.toString())
    }
}
