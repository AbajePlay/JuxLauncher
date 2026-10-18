package ru.jux.launcher.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.jux.launcher.core.NoticeAction
import ru.jux.launcher.meta.LoaderKind
import java.nio.file.Path
import kotlin.io.path.writeText

class CrashHintsTest {

    @Test
    fun `quilt on a too new java points to fabric`() {
        val reason = CrashHints.reason("IllegalArgumentException: Unsupported class file major version 69", LoaderKind.QUILT, "26.3")!!.reason
        assertTrue(reason.contains("Quilt пока не поддерживает Minecraft 26.3"))
        assertTrue(reason.contains("Fabric"))
    }

    @Test
    fun `memory problems come with the matching fix`() {
        assertEquals(
            CrashFix(NoticeAction.MORE_MEMORY),
            CrashHints.reason("java.lang.OutOfMemoryError: Java heap space", LoaderKind.FABRIC, "1.21.1")!!.fix,
        )
        assertEquals(
            CrashFix(NoticeAction.RESET_MEMORY),
            CrashHints.reason("Could not reserve enough space for 8388608KB object heap", LoaderKind.VANILLA, "1.21.1")!!.fix,
        )
    }

    @Test
    fun `a missing fabric dependency is offered for install`() {
        val log = """
            Incompatible mods found!
            - Mod 'Sodium Extra' (sodium-extra) 0.6.0 requires version 0.6.0 or later of mod 'Sodium' (sodium), which is missing!
            - Mod 'Sodium Extra' (sodium-extra) 0.6.0 requires any version of minecraft, which is missing!
        """.trimIndent()
        assertEquals(CrashFix(NoticeAction.INSTALL_MOD, "sodium"), CrashHints.reason(log, LoaderKind.FABRIC, "1.21.1")!!.fix)
        assertEquals(
            CrashFix(NoticeAction.INSTALL_MOD, "fabric-api"),
            CrashHints.reason("Mod 'Lithium' (lithium) 0.15 requires any version of fabric-api, which is missing!", LoaderKind.FABRIC, "1.21.1")!!.fix,
        )
    }

    @Test
    fun `a missing forge dependency names who asked for it`() {
        val log = "Missing or unsupported mandatory dependencies:\n\tMod ID: 'architectury', Requested by: 'rei', Expected range: '[13,)'"
        val diagnosis = CrashHints.reason(log, LoaderKind.NEOFORGE, "1.21.1")!!
        assertEquals(CrashFix(NoticeAction.INSTALL_MOD, "architectury"), diagnosis.fix)
        assertTrue(diagnosis.reason.contains("«rei»"))
    }

    @Test
    fun `a mod whose mixins fail can be switched off`() {
        val log = "org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: Mixin apply for mod betterclouds failed betterclouds.mixins.json"
        assertEquals(CrashFix(NoticeAction.DISABLE_MOD, "betterclouds"), CrashHints.reason(log, LoaderKind.FABRIC, "1.21.4")!!.fix)
    }

    @Test
    fun `broken game files ask for a check, driver troubles only explain`() {
        assertEquals(CrashFix(NoticeAction.VERIFY_FILES), CrashHints.reason("java.util.zip.ZipException: zip END header not found", LoaderKind.VANILLA, "1.21.1")!!.fix)
        val driver = CrashHints.reason("GLFW error 65542: WGL: The driver does not appear to support OpenGL", LoaderKind.VANILLA, "1.21.1")!!
        assertNull(driver.fix)
        assertTrue(driver.reason.contains("драйвер"))
    }

    @Test
    fun `a crash report is read along with the log`(@TempDir dir: Path) {
        val log = dir.resolve("latest-game.log").apply { writeText("[Render thread/INFO]: Stopping!") }
        val report = dir.resolve("crash-2026-10-18.txt").apply { writeText("java.lang.OutOfMemoryError: Java heap space") }
        assertEquals(CrashFix(NoticeAction.MORE_MEMORY), CrashHints.diagnose(log, report, LoaderKind.FABRIC, "1.21.1", 1).fix)
    }

    @Test
    fun `unknown crash falls back to the exit code`(@TempDir dir: Path) {
        val log = dir.resolve("latest-game.log").apply { writeText("something unexpected") }
        assertNull(CrashHints.reason("something unexpected", LoaderKind.FORGE, "1.20.1"))
        assertEquals(Diagnosis("Код выхода 1 — подробности в логах"), CrashHints.diagnose(log, null, LoaderKind.FORGE, "1.20.1", 1))
        assertTrue(CrashHints.diagnose(null, null, LoaderKind.VANILLA, "1.21.1", -1).reason.contains("-1"))
    }
}
