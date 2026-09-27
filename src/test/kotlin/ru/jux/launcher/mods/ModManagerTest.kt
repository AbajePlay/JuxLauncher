package ru.jux.launcher.mods

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.jux.launcher.meta.LoaderKind
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class ModManagerTest {

    private fun mod(file: Path, enabled: Boolean) = InstalledMod(
        file = file, enabled = enabled, sha1 = "", projectId = null, title = "Test", versionNumber = "",
        iconUrl = null, fromBoost = false, update = null,
    )

    @Test
    fun `quilt also takes fabric mods, vanilla takes none`() {
        assertEquals(listOf("quilt", "fabric"), ModManager.loadersFor(LoaderKind.QUILT))
        assertEquals(listOf("neoforge"), ModManager.loadersFor(LoaderKind.NEOFORGE))
        assertTrue(ModManager.loadersFor(LoaderKind.VANILLA).isEmpty())
    }

    @Test
    fun `disabling renames the jar and counts only enabled mods`(@TempDir game: Path) {
        val mods = ModManager.modsDir(game).createDirectories()
        val jar = mods.resolve("sodium.jar").apply { writeText("x") }
        mods.resolve("lithium.jar").writeText("y")
        assertEquals(2, ModManager.count(game))

        ModManager.setEnabled(mod(jar, enabled = true), enabled = false)
        assertFalse(jar.exists())
        val disabled = mods.resolve("sodium.jar.disabled")
        assertTrue(disabled.exists())
        assertEquals(1, ModManager.count(game))

        ModManager.setEnabled(mod(disabled, enabled = false), enabled = true)
        assertTrue(jar.exists())
        assertEquals(2, ModManager.count(game))
    }

    @Test
    fun `removing deletes the file`(@TempDir game: Path) {
        val jar = ModManager.modsDir(game).createDirectories().resolve("jei.jar").apply { writeText("z") }
        ModManager.remove(mod(jar, enabled = true))
        assertFalse(jar.exists())
        assertEquals(0, ModManager.count(game))
    }
}
