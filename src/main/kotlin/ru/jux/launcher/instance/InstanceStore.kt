package ru.jux.launcher.instance

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.PrettyJson
import ru.jux.launcher.core.writeAtomically
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
data class ManagedMod(
    val projectId: String,
    val versionId: String,
    val fileName: String,
    val sha1: String,
    val title: String = "",
)

@Serializable
data class InstanceOptions(
    val fpsBoost: Boolean = false,
    val boostMods: List<ManagedMod> = emptyList(),
    val boostMissing: List<String> = emptyList(),
    val boostOwned: List<String> = emptyList(),
    val boostCheckedAt: Long = 0,
    val blockedUpdates: List<String> = emptyList(),
)

object InstanceStore {

    const val FILE_NAME = "jux-instance.json"

    private val cache = ConcurrentHashMap<String, InstanceOptions>()

    private fun key(gameDir: Path): String = gameDir.toAbsolutePath().normalize().toString()

    fun get(gameDir: Path): InstanceOptions = cache.getOrPut(key(gameDir)) { read(gameDir) }

    fun update(gameDir: Path, transform: (InstanceOptions) -> InstanceOptions): InstanceOptions {
        val updated = transform(get(gameDir))
        cache[key(gameDir)] = updated
        runCatching { gameDir.resolve(FILE_NAME).writeAtomically(PrettyJson.encodeToString(updated)) }
            .onFailure { Log.error("could not save instance options for $gameDir", it) }
        return updated
    }

    fun forget(gameDir: Path) {
        cache.remove(key(gameDir))
    }

    private fun read(gameDir: Path): InstanceOptions {
        val file = gameDir.resolve(FILE_NAME)
        if (!file.exists()) return InstanceOptions()
        return runCatching { Json.decodeFromString<InstanceOptions>(file.readText()) }
            .onFailure { Log.warn("$file unreadable, using defaults", it) }
            .getOrDefault(InstanceOptions())
    }
}
