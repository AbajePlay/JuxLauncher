package ru.jux.launcher.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.jux.launcher.meta.LoaderKind
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
data class LauncherSettings(
    val memoryMb: Int = SettingsDefaults.memoryMb(),
    val jvmArgs: String = SettingsDefaults.JVM_ARGS,
    val closeOnLaunch: Boolean = false,
    val showSnapshots: Boolean = false,
    val showOldVersions: Boolean = false,
    val downloadConcurrency: Int = 0,
    val customGameDir: String? = null,
    val lastVersionId: String? = null,
    val lastLoader: String? = null,
    val forceVerify: Boolean = false,
    val discordPresence: Boolean = true,
)

object SettingsDefaults {

    const val JVM_ARGS =
        "-XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:MaxGCPauseMillis=50 " +
            "-XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:G1HeapRegionSize=32M -XX:+DisableExplicitGC"

    fun memoryMb(): Int = recommendedMemory(totalSystemMemoryMb())

    fun recommendedMemory(totalMb: Int): Int {
        val nominalMb = (totalMb + 512) / 1024 * 1024
        val quarter = (nominalMb / 4).coerceIn(2048, 8192)
        return memoryPresets(totalMb).filter { it <= quarter }.maxOrNull() ?: 2048
    }

    fun memoryPresets(totalMb: Int): List<Int> =
        listOf(2, 4, 6, 8, 12, 16).map { it * 1024 }.filter { it <= maxOf(4096, totalMb - 2048) }

    fun totalSystemMemoryMb(): Int = runCatching {
        val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                as com.sun.management.OperatingSystemMXBean
        (bean.totalMemorySize / 1024 / 1024).toInt()
    }.getOrDefault(8192)
}

object Settings {

    private val _state = MutableStateFlow(LauncherSettings())
    val state: StateFlow<LauncherSettings> = _state.asStateFlow()

    val current: LauncherSettings get() = _state.value

    fun load() {
        val file = Paths.settingsFile
        if (!file.exists()) return
        runCatching { _state.value = Json.decodeFromString<LauncherSettings>(file.readText()) }
            .onFailure { Log.warn("launcher.json unreadable, using defaults", it) }
    }

    fun update(transform: (LauncherSettings) -> LauncherSettings) {
        _state.value = transform(_state.value)
        save()
    }

    fun save() {
        runCatching { Paths.settingsFile.writeAtomically(PrettyJson.encodeToString(_state.value)) }
            .onFailure { Log.error("could not save settings", it) }
    }

    fun gameDir(gameVersion: String, loader: LoaderKind = LoaderKind.VANILLA): Path {
        val name = if (loader.isModded) "$gameVersion-${loader.name.lowercase()}" else gameVersion
        current.customGameDir?.takeIf { it.isNotBlank() }?.let { custom ->
            return Path.of(custom).resolve(name)
        }
        return Paths.instanceDir(name)
    }
}
