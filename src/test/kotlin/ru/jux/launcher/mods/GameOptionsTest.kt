package ru.jux.launcher.mods

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.jux.launcher.meta.LoaderKind
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

class GameOptionsTest {

    @Test
    fun `a resource pack is switched on and off without losing other settings`(@TempDir game: Path) {
        game.resolve("options.txt").writeText("lang:ru_ru\nresourcePacks:[\"vanilla\",\"fabric\"]\nfov:0.5\n")

        GameOptions.setResourcePack(game, "Fresh Animations.zip", enabled = true)
        assertEquals(listOf("vanilla", "fabric", "file/Fresh Animations.zip"), GameOptions.resourcePacks(game))
        assertTrue(GameOptions.isResourcePackOn(game, "Fresh Animations.zip"))
        assertEquals(listOf("lang:ru_ru", "fov:0.5"), game.resolve("options.txt").readLines().filterNot { it.startsWith("resourcePacks:") })

        GameOptions.setResourcePack(game, "Fresh Animations.zip", enabled = false)
        assertEquals(listOf("vanilla", "fabric"), GameOptions.resourcePacks(game))
    }

    private fun options(game: Path): Map<String, String> =
        game.resolve("options.txt").readLines().associate { it.substringBefore(':') to it.substringAfter(':') }

    @Test
    fun `a preset keeps the formats of a modern options file and the rest of it`(@TempDir game: Path) {
        game.resolve("options.txt").writeText(
            "version:4189\nrenderDistance:12\nsimulationDistance:12\ngraphicsMode:2\nao:true\nrenderClouds:\"true\"\n" +
                "particles:0\nentityShadows:true\nmipmapLevels:4\nbiomeBlendRadius:2\nentityDistanceScaling:1.0\nfov:0.5\nlang:ru_ru\n",
        )
        GameOptions.applyPreset(game, "1.21.4", GraphicsPreset.LOW)

        val written = options(game)
        assertEquals("4189", written["version"])
        assertEquals("6", written["renderDistance"])
        assertEquals("5", written["simulationDistance"])
        assertEquals("0", written["graphicsMode"])
        assertEquals("false", written["ao"])
        assertEquals("\"false\"", written["renderClouds"])
        assertEquals("2", written["particles"])
        assertEquals("false", written["entityShadows"])
        assertEquals("0", written["mipmapLevels"])
        assertEquals("0.75", written["entityDistanceScaling"])
        assertEquals("0.5", written["fov"])
        assertEquals("ru_ru", written["lang"])
        assertFalse("fancyGraphics" in written)
    }

    @Test
    fun `a preset speaks the old options format of 1_12`(@TempDir game: Path) {
        game.resolve("options.txt").writeText("renderDistance:8\nfancyGraphics:true\nao:2\nrenderClouds:true\nparticles:0\n")
        GameOptions.applyPreset(game, "1.12.2", GraphicsPreset.LOW)

        val written = options(game)
        assertEquals("false", written["fancyGraphics"])
        assertEquals("0", written["ao"])
        assertEquals("false", written["renderClouds"])
        // Ключей, которых в 1.12 ещё не было, не добавляем
        listOf("graphicsMode", "simulationDistance", "biomeBlendRadius", "entityDistanceScaling").forEach { assertFalse(it in written, it) }

        GameOptions.applyPreset(game, "1.12.2", GraphicsPreset.HIGH)
        assertEquals("true", options(game)["fancyGraphics"])
        assertEquals("2", options(game)["ao"])
    }

    @Test
    fun `a preset on a fresh folder writes only keys that read the same in every version`(@TempDir game: Path) {
        GameOptions.applyPreset(game, "1.21.4", GraphicsPreset.MEDIUM)
        val modern = options(game)
        assertEquals("10", modern["renderDistance"])
        assertEquals("8", modern["simulationDistance"])
        assertEquals("1", modern["graphicsMode"])
        // Формат облаков и сглаживания менялся между версиями — без файла игры их не трогаем
        assertFalse("ao" in modern)
        assertFalse("renderClouds" in modern)

        val old = game.resolve("old").createDirectories()
        GameOptions.applyPreset(old, "1.8.9", GraphicsPreset.MEDIUM)
        assertEquals("true", options(old)["fancyGraphics"])
        assertFalse("graphicsMode" in options(old))
    }

    @Test
    fun `a fresh folder gets an options file with just the pack`(@TempDir game: Path) {
        GameOptions.setResourcePack(game, "Faithful.zip", enabled = true)
        assertEquals(listOf("resourcePacks:[\"vanilla\",\"file/Faithful.zip\"]"), game.resolve("options.txt").readLines())
    }

    @Test
    fun `the chosen shader is written for iris and can be turned off`(@TempDir game: Path) {
        assertNull(GameOptions.activeShader(game))
        GameOptions.setShader(game, "ComplementaryReimagined_r5.9.3.zip")
        assertEquals("ComplementaryReimagined_r5.9.3.zip", GameOptions.activeShader(game))
        GameOptions.setShader(game, null)
        assertNull(GameOptions.activeShader(game))
    }

    @Test
    fun `optifine keeps its shader in its own file`(@TempDir game: Path) {
        GameOptions.setShader(game, "BSL.zip")
        assertEquals("BSL.zip", GameOptions.activeShader(game, LoaderKind.OPTIFINE))
        assertTrue(game.resolve("optionsshaders.txt").exists())

        game.resolve("optionsshaders.txt").writeText("shaderPack=OFF\n")
        assertNull(GameOptions.activeShader(game, LoaderKind.OPTIFINE), "switched off in the game")
        assertEquals("BSL.zip", GameOptions.activeShader(game), "iris has its own choice")
    }

    @Test
    fun `switching and removing packs goes through the game settings`(@TempDir game: Path) {
        val shader = ContentKind.SHADER.dir(game).createDirectories().resolve("BSL.zip").apply { writeText("x") }
        val pack = ContentKind.RESOURCE_PACK.dir(game).createDirectories().resolve("Faithful").apply { writeText("y") }
        fun item(file: Path, kind: ContentKind, enabled: Boolean) = InstalledItem(
            file = file, kind = kind, enabled = enabled, sha1 = "", projectId = null, versionId = null, title = file.fileName.toString(),
            versionNumber = "", iconUrl = null, fromBoost = false, update = null,
        )

        ModManager.setEnabled(item(shader, ContentKind.SHADER, enabled = false), enabled = true)
        assertEquals("BSL.zip", GameOptions.activeShader(game))
        ModManager.setEnabled(item(pack, ContentKind.RESOURCE_PACK, enabled = false), enabled = true)
        assertTrue(GameOptions.isResourcePackOn(game, "Faithful"))

        ModManager.remove(item(shader, ContentKind.SHADER, enabled = true))
        ModManager.remove(item(pack, ContentKind.RESOURCE_PACK, enabled = true))
        assertFalse(shader.exists())
        assertFalse(pack.exists())
        assertNull(GameOptions.activeShader(game))
        assertFalse(GameOptions.isResourcePackOn(game, "Faithful"))
    }
}
