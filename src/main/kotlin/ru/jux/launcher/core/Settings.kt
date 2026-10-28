package ru.jux.launcher.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.jux.launcher.meta.LoaderKind
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
data class LauncherSettings(
    val memoryMb: Int = SettingsDefaults.memoryMb(),
    val jvmArgs: String = SettingsDefaults.JVM_ARGS,
    val showSnapshots: Boolean = false,
    val showOldVersions: Boolean = false,
    val onlyInstalled: Boolean = false,
    val downloadConcurrency: Int = 0,
    val customGameDir: String? = null,
    val lastVersionId: String? = null,
    val lastLoader: String? = null,
    val lastPack: String? = null,
    val forceVerify: Boolean = false,
    val installId: String? = null,
    val language: Language = systemLanguage(),
    val theme: ThemeMode = ThemeMode.DARK,
    val accentColor: Int = DEFAULT_ACCENT,
    val backdrop: Backdrop = Backdrop.NONE,
    val backdropColor: Int = DEFAULT_BACKDROP_COLOR,
    val backdropDim: Float = 0.6f,
    val gameWindow: GameWindowMode = GameWindowMode.HIDE,
    /** Чем заходить на сервер друга, если игрок выбрал сам: адрес → ключ версии или сборки. */
    val serverVersions: Map<String, String> = emptyMap(),
)

/** Что делать с окном лаунчера, когда окно игры открылось. */
enum class GameWindowMode {
    HIDE, MINIMIZE, KEEP;

    val title: String
        get() = when (this) {
            HIDE -> tr("Скрыть", "Hide")
            MINIMIZE -> tr("Свернуть", "Minimize")
            KEEP -> tr("Оставить открытым", "Keep open")
        }
}

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
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        scheduleSave()
    }

    // Ползунок шлёт десятки изменений в секунду: пишем файл один раз, когда он отпущен, и не на UI-потоке.
    // На выходе лаунчер всё равно сохраняет настройки сразу.
    private val saver = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "settings-save").apply { isDaemon = true } }
    private val savePending = AtomicBoolean(false)

    private fun scheduleSave() {
        if (savePending.compareAndSet(false, true)) {
            saver.schedule({
                savePending.set(false)
                save()
            }, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun save() {
        runCatching { Paths.settingsFile.writeAtomically(PrettyJson.encodeToString(_state.value)) }
            .onFailure { Log.error("could not save settings", it) }
    }

    fun gameDir(gameVersion: String, loader: LoaderKind = LoaderKind.VANILLA): Path {
        val name = if (loader.isModded) "$gameVersion-${loader.name.lowercase()}" else gameVersion
        return customGameRoot()?.resolve(name) ?: Paths.instanceDir(name)
    }

    fun packsDir(): Path = (customGameRoot() ?: Paths.instances).resolve(PACKS)

    fun gameRoots(): List<Path> {
        val roots = listOfNotNull(Paths.instances, customGameRoot()).distinct()
        return (roots + roots.map { it.resolve(PACKS) }).distinct()
    }

    private fun customGameRoot(): Path? = current.customGameDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    private const val PACKS = "packs"
    private const val SAVE_DELAY_MILLIS = 300L
}
