package ru.jux.launcher.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.jux.launcher.meta.RuleEnvironment

class JavaPlatformTest {

    private val appleSilicon = RuleEnvironment("osx", "14.5", "arm64")
    private val intelMac = RuleEnvironment("osx", "13.0", "x86_64")

    // 1.18.2 и раньше: LWJGL только для Intel
    private val legacy = listOf(
        "org.lwjgl:lwjgl:3.2.2",
        "org.lwjgl:lwjgl:3.2.2:natives-macos",
        "com.mojang:blocklist:1.0.10",
    )

    // 1.19 и новее: рядом лежат и arm64-библиотеки
    private val modern = listOf(
        "org.lwjgl:lwjgl:3.3.3",
        "org.lwjgl:lwjgl:3.3.3:natives-macos",
        "org.lwjgl:lwjgl:3.3.3:natives-macos-arm64",
    )

    @Test
    fun `apple silicon runs pre-1_19 versions on intel java`() {
        assertTrue(JavaPlatform.needsIntel(legacy, appleSilicon))
        assertTrue(JavaPlatform.needsIntel(listOf("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209"), appleSilicon))
        assertFalse(JavaPlatform.needsIntel(modern, appleSilicon))
    }

    @Test
    fun `other computers keep their own java`() {
        assertFalse(JavaPlatform.needsIntel(legacy, intelMac))
        assertFalse(JavaPlatform.needsIntel(legacy, RuleEnvironment("windows", "10.0", "x86_64")))
        assertFalse(JavaPlatform.needsIntel(legacy, RuleEnvironment("linux", "6.8", "x86_64")))
    }

    @Test
    fun `apple silicon falls back to intel builds Mojang does not ship for arm64`() {
        assertEquals("mac-os-arm64", JavaPlatform.key(appleSilicon))
        assertEquals(listOf("mac-os-arm64", "mac-os"), JavaPlatform.fallbacks(appleSilicon))
        assertEquals(listOf("mac-os"), JavaPlatform.fallbacks(intelMac))
        assertEquals(listOf("linux"), JavaPlatform.fallbacks(RuleEnvironment("linux", "6.8", "x86_64")))
    }
}
