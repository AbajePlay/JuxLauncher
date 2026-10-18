package ru.jux.launcher.meta

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OptiFineTest {

    private val page = """
        <td class='colFile'>Preview OptiFine 26.2 HD U K2 pre4</td>
        <td class='colMirror'><a href="http://optifine.net/adloadx?f=preview_OptiFine_26.2_HD_U_K2_pre4.jar">(Mirror)</a></td>
        <td class='colDownload'><a href="http://adfoc.us/serve/sitelinks/?id=475250&url=http://optifine.net/adloadx?f=OptiFine_1.21.11_HD_U_J9.jar&x=8351">Download</a></td>
        <td class='colMirror'><a href="http://optifine.net/adloadx?f=OptiFine_1.21.11_HD_U_J9.jar">(Mirror)</a></td>
        <td class='colMirror'><a href="http://optifine.net/adloadx?f=OptiFine_1.21.11_HD_U_J8.jar">(Mirror)</a></td>
        <td class='colMirror'><a href="http://optifine.net/adloadx?f=OptiFine_1.8.0_HD_U_M5.jar">(Mirror)</a></td>
    """.trimIndent()

    @Test
    fun `builds are read from the downloads page, newest first and without repeats`() {
        val builds = OptiFineRepository.parse(page)
        assertEquals(
            listOf(
                OptiFineBuild("preview_OptiFine_26.2_HD_U_K2_pre4.jar", "26.2", "HD_U_K2_pre4", preview = true),
                OptiFineBuild("OptiFine_1.21.11_HD_U_J9.jar", "1.21.11", "HD_U_J9", preview = false),
                OptiFineBuild("OptiFine_1.21.11_HD_U_J8.jar", "1.21.11", "HD_U_J8", preview = false),
                OptiFineBuild("OptiFine_1.8.0_HD_U_M5.jar", "1.8", "HD_U_M5", preview = false),
            ),
            builds,
        )
    }

    @Test
    fun `optifine names its own profiles after the game version`() {
        assertTrue(LoaderKind.OPTIFINE.ownsProfile("1.21.11-OptiFine_HD_U_J9", "1.21.11"))
        assertTrue(LoaderKind.OPTIFINE.ownsProfile("1.8.0-OptiFine_HD_U_M5", "1.8"))
        assertFalse(LoaderKind.OPTIFINE.ownsProfile("1.21.1-OptiFine_HD_U_J1", "1.21.11"))
        assertFalse(LoaderKind.OPTIFINE.hasMods)
        assertTrue(LoaderKind.OPTIFINE.isModded)
    }

    @Test
    fun `support set includes optifine`() {
        val support = LoaderSupport(optifine = setOf("1.21.11"))
        assertEquals(listOf(LoaderKind.VANILLA, LoaderKind.OPTIFINE), support.loadersFor("1.21.11"))
        assertFalse(support.isEmpty)
    }
}
