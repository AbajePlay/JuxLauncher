package ru.jux.launcher.dev

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import ru.jux.launcher.activity.PlayHistory
import ru.jux.launcher.core.NoticeAction
import ru.jux.launcher.core.NoticeLevel
import ru.jux.launcher.core.Notices
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.Preloader
import ru.jux.launcher.logs.LogSource
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.packs.Modpack
import ru.jux.launcher.online.OnlineCount
import ru.jux.launcher.online.OnlineCounter
import ru.jux.launcher.ui.App
import ru.jux.launcher.ui.CatalogTab
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.Screen
import ru.jux.launcher.ui.SplashContent
import ru.jux.launcher.ui.theme.JuxTheme
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

private const val FRAME_NANOS = 16_000_000L
private const val SETTLE_NANOS = 600_000_000L

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/preview").apply { mkdirs() }
    val scale = args.getOrNull(1)?.toFloatOrNull() ?: 1.25f

    Notices.file = null
    PlayHistory.cacheFile = null
    PlayHistory.historyFile = File(out, "activity-preview.json").toPath().also { copy ->
        runCatching { Files.copy(Paths.root.resolve("activity.json"), copy, StandardCopyOption.REPLACE_EXISTING) }
    }
    val preloaded = runBlocking { Preloader.run { _, _ -> } }
        .copy(manifestStale = false, loaderSupportStale = false)
    val state = LauncherState(CoroutineScope(Dispatchers.Unconfined), preloaded)
    OnlineCounter.show(OnlineCount(online = 128, playing = 47))

    fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(
            width = (width * scale).toInt(),
            height = (height * scale).toInt(),
            density = Density(scale),
            content = content,
        )
        try {
            var time = 0L
            while (time < SETTLE_NANOS) {
                scene.render(time)
                time += FRAME_NANOS
            }
            val image = scene.render(time)
            val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed for $name")
            File(out, "$name.png").writeBytes(png.bytes)
        } finally {
            scene.close()
        }
        println("rendered ${File(out, "$name.png")}")
    }

    render("splash", 520, 180) { JuxTheme { SplashContent(0.7f, "Подтягиваю загрузчики модов") } }
    render("site-home", 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }

    val sample = state.currentEntry()
    val day = 24L * 60 * 60 * 1000
    val now = System.currentTimeMillis()
    Notices.post(
        NoticeLevel.ERROR, "Игре не хватило памяти. Добавь памяти в настройках сборки.", "Игра закрылась с ошибкой",
        sample?.key, sample?.label, listOf(NoticeAction.LOGS, NoticeAction.MODS), now = now - day - 3_600_000,
    )
    Notices.post(NoticeLevel.SUCCESS, "Вход выполнен: Abaje", now = now - day)
    Notices.post(
        NoticeLevel.INFO, "Для ${sample?.id} модов FPS-буста пока нет. Игра запущена без него.", null,
        sample?.key, sample?.label, now = now - 7_200_000,
    )
    Notices.post(NoticeLevel.INFO, "Для ${sample?.id} модов FPS-буста пока нет. Игра запущена без него.", null, sample?.key, sample?.label, now = now - 3_600_000)
    Notices.post(NoticeLevel.SUCCESS, "Sodium 0.8.15-beta.1 → 0.8.12", "Моды исправлены", sample?.key, sample?.label, now = now - 60_000)
    Notices.markSeen()
    state.fail(
        "Sodium 0.8.15-beta.1 несовместим с Iris 1.10.7",
        sample,
        "Игра не запущена: моды несовместимы",
        listOf(NoticeAction.FIX_MODS, NoticeAction.PLAY_ANYWAY),
    )
    render("home-toast", 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    state.toast?.let { state.dismissToast(it.id) }

    runBlocking { state.loadActivity() }
    for (screen in Screen.entries) {
        if (screen == Screen.NOTICES) state.openNotices() else state.screen = screen
        render(screen.name.lowercase(), 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    }

    state.screen = Screen.ACTIVITY
    render("activity-tall", 1040, 1000) { JuxTheme { App(state, onGameStarted = {}) } }

    state.screen = Screen.HOME
    val entry = state.currentEntry()
    val dialogs = listOfNotNull(
        entry?.let { "dialog-delete" to Modal.Delete(it) },
        entry?.let { "dialog-logs" to Modal.Logs(state.gameDirOf(it), it.label, LogSource.GAME) },
    )
    for ((name, modal) in dialogs) {
        state.modal = modal
        render(name, 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    }
    state.modal = null

    for (tab in listOf(CatalogTab.MODS, CatalogTab.SHADERS, CatalogTab.INSTALLED)) {
        state.openCatalog(tab = tab)
        render("catalog-${tab.name.lowercase()}", 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    }

    val pack = Modpack(
        id = "fabulously-optimized",
        title = "Fabulously Optimized",
        version = "14.1.0",
        gameVersion = "26.2",
        loader = LoaderKind.FABRIC,
        loaderVersion = "0.19.5",
        projectId = "1KVo5zza",
        versionId = "ssWn7YI0",
    )
    val withPack = LauncherState(CoroutineScope(Dispatchers.Unconfined), preloaded.copy(packs = listOf(pack)))
    withPack.selectEntry(withPack.entryFor(pack))
    render("home-pack", 1040, 660) { JuxTheme { App(withPack, onGameStarted = {}) } }
    withPack.openCatalog(tab = CatalogTab.PACKS)
    render("catalog-packs-installed", 1040, 660) { JuxTheme { App(withPack, onGameStarted = {}) } }
    exitProcess(0)
}
