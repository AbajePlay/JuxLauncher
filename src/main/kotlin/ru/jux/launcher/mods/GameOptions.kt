package ru.jux.launcher.mods

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.tr
import ru.jux.launcher.core.writeAtomically
import ru.jux.launcher.meta.GameVersions
import ru.jux.launcher.meta.LoaderKind
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.readLines

enum class GraphicsPreset(
    val renderDistance: Int,
    val simulationDistance: Int,
    val fancy: Boolean,
    val particles: Int,
    val clouds: String,
    val smoothLighting: Boolean,
    val entityShadows: Boolean,
    val mipmapLevels: Int,
    val biomeBlendRadius: Int,
    val entityDistance: Double,
) {
    LOW(6, 5, fancy = false, particles = 2, clouds = "false", smoothLighting = false, entityShadows = false, mipmapLevels = 0, biomeBlendRadius = 0, entityDistance = 0.75),
    MEDIUM(10, 8, fancy = true, particles = 1, clouds = "fast", smoothLighting = true, entityShadows = true, mipmapLevels = 2, biomeBlendRadius = 1, entityDistance = 1.0),
    HIGH(16, 12, fancy = true, particles = 0, clouds = "true", smoothLighting = true, entityShadows = true, mipmapLevels = 4, biomeBlendRadius = 2, entityDistance = 1.0);

    val title: String
        get() = when (this) {
            LOW -> tr("Слабый ПК", "Low-end PC")
            MEDIUM -> tr("Средняя", "Medium")
            HIGH -> tr("Высокая", "High")
        }

    val summary: String
        get() = when (this) {
            LOW -> tr("дальность $renderDistance, быстрая графика, без облаков и теней", "render distance $renderDistance, fast graphics, no clouds or shadows")
            MEDIUM -> tr("дальность $renderDistance, красивая графика, быстрые облака", "render distance $renderDistance, fancy graphics, fast clouds")
            HIGH -> tr("дальность $renderDistance, всё включено", "render distance $renderDistance, everything on")
        }
}

object GameOptions {

    private const val OPTIONS = "options.txt"
    private const val PACKS_KEY = "resourcePacks:"
    private const val VANILLA = "vanilla"
    private const val IRIS = "config/iris.properties"
    private const val SHADER_PACK = "shaderPack"
    private const val SHADERS_ON = "enableShaders"
    private const val OPTIFINE = "optionsshaders.txt"
    private val OPTIFINE_OFF = listOf("OFF", "(internal)")

    fun resourcePacks(gameDir: Path): List<String> =
        lines(gameDir.resolve(OPTIONS)).firstOrNull { it.startsWith(PACKS_KEY) }?.let(::parsePacks) ?: listOf(VANILLA)

    fun isResourcePackOn(gameDir: Path, fileName: String): Boolean = packEntry(fileName) in resourcePacks(gameDir)

    fun setResourcePack(gameDir: Path, fileName: String, enabled: Boolean) {
        val file = gameDir.resolve(OPTIONS)
        val lines = lines(file)
        val current = lines.firstOrNull { it.startsWith(PACKS_KEY) }?.let(::parsePacks) ?: listOf(VANILLA)
        val entry = packEntry(fileName)
        val next = current.filter { it != entry } + listOfNotNull(entry.takeIf { enabled })
        if (next == current) return
        val line = PACKS_KEY + Json.encodeToString(next)
        val updated = if (lines.any { it.startsWith(PACKS_KEY) }) {
            lines.map { if (it.startsWith(PACKS_KEY)) line else it }
        } else {
            lines + line
        }
        file.writeAtomically(updated.joinToString("\n", postfix = "\n"))
    }

    fun activeShader(gameDir: Path, loader: LoaderKind = LoaderKind.FABRIC): String? {
        if (loader == LoaderKind.OPTIFINE) {
            return properties(gameDir, OPTIFINE).getProperty(SHADER_PACK)?.takeIf { it.isNotBlank() && it !in OPTIFINE_OFF }
        }
        val properties = properties(gameDir, IRIS)
        if (properties.getProperty(SHADERS_ON, "true") == "false") return null
        return properties.getProperty(SHADER_PACK)?.takeIf { it.isNotBlank() }
    }

    // Iris и OptiFine хранят выбранный шейдер в разных файлах: пишем в оба, каждый читает свой
    fun setShader(gameDir: Path, fileName: String?) {
        val iris = properties(gameDir, IRIS)
        if (fileName != null) iris.setProperty(SHADER_PACK, fileName)
        iris.setProperty(SHADERS_ON, (fileName != null).toString())
        store(gameDir, IRIS, iris)

        val optiFine = properties(gameDir, OPTIFINE)
        optiFine.setProperty(SHADER_PACK, fileName ?: OPTIFINE_OFF.first())
        store(gameDir, OPTIFINE, optiFine)
    }

    /**
     * Пишет в options.txt настройки графики из [preset]. Ключи, что уже есть в файле, меняются в их же формате;
     * недостающие добавляются, только если их формат одинаков во всех версиях, где они бывают.
     */
    fun applyPreset(gameDir: Path, gameVersion: String, preset: GraphicsPreset) {
        val file = gameDir.resolve(OPTIONS)
        val lines = lines(file).toMutableList()
        fun index(key: String) = lines.indexOfFirst { it.startsWith("$key:") }
        fun value(key: String) = index(key).takeIf { it >= 0 }?.let { lines[it].substringAfter(':') }
        fun put(key: String, value: String, addIfMissing: Boolean = true) {
            val at = index(key)
            when {
                at >= 0 -> lines[at] = "$key:$value"
                addIfMissing -> lines += "$key:$value"
            }
        }

        put("renderDistance", preset.renderDistance.toString())
        put("simulationDistance", preset.simulationDistance.toString(), GameVersions.atLeast(gameVersion, 1, 18))
        put("particles", preset.particles.toString())
        put("entityShadows", preset.entityShadows.toString())
        put("mipmapLevels", preset.mipmapLevels.toString())
        put("biomeBlendRadius", preset.biomeBlendRadius.toString(), GameVersions.atLeast(gameVersion, 1, 13))
        put("entityDistanceScaling", preset.entityDistance.toString(), GameVersions.atLeast(gameVersion, 1, 16))

        // До 1.16 графика — флажок fancyGraphics, после — число graphicsMode
        when {
            value("graphicsMode") != null -> put("graphicsMode", if (preset.fancy) "1" else "0")
            value("fancyGraphics") != null -> put("fancyGraphics", preset.fancy.toString())
            GameVersions.atLeast(gameVersion, 1, 16) -> put("graphicsMode", if (preset.fancy) "1" else "0")
            else -> put("fancyGraphics", preset.fancy.toString())
        }
        // Тени от блоков были числом 0–2, стали флажком; облака то в кавычках, то без — повторяем как в файле
        value("ao")?.let { ao ->
            put("ao", if (ao == "true" || ao == "false") preset.smoothLighting.toString() else if (preset.smoothLighting) "2" else "0")
        }
        value("renderClouds")?.let { clouds ->
            put("renderClouds", if (clouds.startsWith("\"")) "\"${preset.clouds}\"" else preset.clouds)
        }
        file.writeAtomically(lines.joinToString("\n", postfix = "\n"))
    }

    private fun store(gameDir: Path, file: String, properties: Properties) {
        val bytes = ByteArrayOutputStream().also { properties.store(it, null) }.toByteArray()
        gameDir.resolve(file).writeAtomically(bytes)
    }

    internal fun parsePacks(line: String): List<String> = runCatching {
        Json.parseToJsonElement(line.removePrefix(PACKS_KEY)).jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }.getOrDefault(listOf(VANILLA))

    private fun packEntry(fileName: String) = "file/$fileName"

    private fun lines(file: Path): List<String> =
        if (file.exists()) runCatching { file.readLines() }.getOrDefault(emptyList()) else emptyList()

    private fun properties(gameDir: Path, name: String): Properties = Properties().apply {
        val file = gameDir.resolve(name)
        if (file.exists()) runCatching { Files.newInputStream(file).use(::load) }
    }
}
