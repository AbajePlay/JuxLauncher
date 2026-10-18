package ru.jux.launcher.mods

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.writeAtomically
import ru.jux.launcher.meta.LoaderKind
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.readLines

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
