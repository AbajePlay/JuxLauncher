package ru.jux.launcher.dev

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import ru.jux.launcher.instance.CarryPack
import ru.jux.launcher.instance.CarrySource
import ru.jux.launcher.instance.CarryWorld
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.link.LinkContent
import ru.jux.launcher.logs.LogSource
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.meta.OptiFineRepository
import ru.jux.launcher.mods.ContentKind
import ru.jux.launcher.mods.InstalledItem
import ru.jux.launcher.mods.Modrinth
import ru.jux.launcher.packs.PackLinks
import ru.jux.launcher.packs.Modpack
import ru.jux.launcher.online.OnlineCount
import ru.jux.launcher.online.OnlineCounter
import ru.jux.launcher.ui.App
import ru.jux.launcher.ui.CatalogTab
import ru.jux.launcher.ui.InstallPlan
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.ModIcons
import ru.jux.launcher.ui.PackMode
import ru.jux.launcher.ui.RunningGame
import ru.jux.launcher.discord.Presence
import ru.jux.launcher.ui.Screen
import ru.jux.launcher.ui.SplashContent
import ru.jux.launcher.ui.installPlan
import ru.jux.launcher.ui.joinModal
import ru.jux.launcher.ui.components.ConfirmDialog
import ru.jux.launcher.ui.components.JuxDropdownMenu
import ru.jux.launcher.ui.screens.EntryMenuItems
import ru.jux.launcher.ui.theme.JuxTheme
import java.io.File
import java.nio.file.Path
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
        .let { it.copy(loaderSupport = it.loaderSupport.copy(optifine = runCatching { OptiFineRepository.builds().map { b -> b.gameVersion }.toSet() }.getOrDefault(emptySet()))) }
    val state = LauncherState(CoroutineScope(Dispatchers.Unconfined), preloaded)
    OnlineCounter.show(OnlineCount(online = 128, playing = 47))

    fun render(name: String, width: Int, height: Int, realMillis: Long = 0, content: @Composable () -> Unit) {
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
            // Окнам, которые сами ходят в сеть, даём настоящее время дождаться ответа
            val until = System.currentTimeMillis() + realMillis
            while (System.currentTimeMillis() < until) {
                Thread.sleep(50)
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
        entry?.let { "dialog-carry-first" to Modal.Carry(it, previewSources(state, first = true), firstLaunch = true, targetHasOptions = false) },
        entry?.let { "dialog-carry-manual" to Modal.Carry(it, previewSources(state, first = false), firstLaunch = false, targetHasOptions = true) },
    )
    for ((name, modal) in dialogs) {
        state.modal = modal
        render(name, 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    }
    state.modal = null

    val links = listOf(
        "dialog-join" to LaunchLink.Join("mc.example.org", "1.21.4", "Test"),
        "dialog-join-unknown" to LaunchLink.Join("play.example.org:25570", "99.9", null),
        "dialog-install-mod" to LaunchLink.Install("sodium", LinkContent.MOD, "1.21.4", "fabric", "Sodium"),
        "dialog-install-shader" to LaunchLink.Install("complementary-reimagined", LinkContent.SHADER, null, "iris", null),
        "dialog-install-resourcepack" to LaunchLink.Install("fresh-animations", LinkContent.RESOURCE_PACK, null, null, null),
        "dialog-install-pack" to LaunchLink.Install("fabulously-optimized", LinkContent.MODPACK, null, null, null),
        "dialog-install-datapack" to LaunchLink.Install("terralith", LinkContent.DATAPACK, null, null, "Terralith"),
        "dialog-install-missing" to LaunchLink.Install("no-such-project-jux", LinkContent.MOD, "1.21.4", null, null),
        "dialog-pack-link" to LaunchLink.SharedPack(
            name = "Выживание с друзьями",
            version = "1.21.4",
            loader = LoaderKind.FABRIC,
            mods = listOf("c3YkZvne", "Ca054sTe", "zzzzzzzz"),
            shaders = listOf("Bqen1mJX"),
            resourcePacks = listOf("aQjwvDfY"),
        ),
    )
    for ((name, link) in links) {
        state.modal = when (link) {
            is LaunchLink.Join -> state.joinModal(link)
            is LaunchLink.Install -> Modal.Install(link, if (link.content == LinkContent.DATAPACK) null else runBlocking { state.installPlan(link) })
            is LaunchLink.SharedPack -> Modal.ImportPack(link, runBlocking { PackLinks.resolve(link) })
            LaunchLink.Open -> null
        }
        val plan = (state.modal as? Modal.Install)?.plan
        listOfNotNull((plan as? InstallPlan.Content)?.project?.iconUrl, (plan as? InstallPlan.Pack)?.project?.iconUrl)
            .forEach { runBlocking { ModIcons.load(it) } }
        println("$name: ${state.modal}")
        render(name, 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    }
    state.modal = Modal.Install(links.first { it.first == "dialog-install-mod" }.second as LaunchLink.Install)
    render("dialog-install-loading", 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    state.modal = null

    val menuEntry = state.entryFor("26.3", LoaderKind.FABRIC)
    render("menu-entry", 1040, 660) {
        JuxTheme {
            Box(Modifier.fillMaxSize()) {
                App(state, onGameStarted = {})
                Box(Modifier.offset(x = 470.dp, y = 372.dp).size(1.dp)) {
                    JuxDropdownMenu(expanded = true, onDismissRequest = {}) {
                        EntryMenuItems(state, menuEntry, close = {})
                    }
                }
            }
        }
    }

    for (tab in listOf(CatalogTab.MODS, CatalogTab.SHADERS, CatalogTab.INSTALLED)) {
        state.openCatalog(tab = tab)
        render("catalog-${tab.name.lowercase()}", 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    }
    state.screen = Screen.HOME

    // Значок и кнопка обновлений модов: каталог сообщает, сколько модов можно обновить
    val fabric = state.entryFor("26.3", LoaderKind.FABRIC)
    state.selectEntry(fabric)
    val newer = Modrinth.Version(id = "newerver", projectId = "AANobbMI", versionNumber = "0.7.0")
    val stale = List(3) { index ->
        InstalledItem(
            file = Path.of("mod$index.jar"), kind = ContentKind.MOD, enabled = true, sha1 = "", projectId = "AANobbMI",
            versionId = "olderver", title = "Mod $index", versionNumber = "0.6", iconUrl = null, fromBoost = false, update = newer,
        )
    }
    state.modsScanned(fabric, stale)
    render("home-mod-updates", 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }

    // Два клиента одной сборки: первый уже в игре, второй ещё грузится
    val presence = Presence.Playing(versionId = fabric.id, loaderLabel = "Fabric", server = null, mods = 3, startedAt = 0)
    val idle = ProcessBuilder("cmd", "/c", "exit").start()
    state.games = listOf(
        RunningGame(fabric, idle, Path.of("latest-game.log"), 0, presence).apply { opened = true },
        RunningGame(fabric, idle, Path.of("latest-game-2.log"), 0, presence),
    )
    render("home-running", 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    state.games = state.games.take(1)
    render("home-running-one", 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    state.games = emptyList()

    state.modal = Modal.PackEditor(PackMode.Create)
    render("dialog-pack-create", 1040, 760) { JuxTheme { App(state, onGameStarted = {}) } }
    state.modal = Modal.Graphics(fabric)
    render("dialog-graphics", 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    state.modal = Modal.Share(fabric)
    render("dialog-share", 1040, 660, realMillis = 8_000) { JuxTheme { App(state, onGameStarted = {}) } }
    state.modal = null
    render("dialog-confirm", 1040, 660) {
        JuxTheme {
            Box(Modifier.fillMaxSize()) {
                App(state, onGameStarted = {})
                ConfirmDialog("Удалить аккаунт Abaje?", "Его можно будет добавить снова по нику", "Удалить", onDismiss = {}, onConfirm = {})
            }
        }
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
    renderNewFeatures(preloaded) { name, width, height, content -> render(name, width, height, content = content) }
    exitProcess(0)
}

private fun previewSources(state: LauncherState, first: Boolean): List<CarrySource> {
    val mb = 1024L * 1024
    val newest = state.versions.firstOrNull()?.id
    val current = state.currentEntry()?.id ?: "26.2"
    val versions = CarrySource(
        dir = Path.of("26.2"),
        label = "26.2",
        official = false,
        lastPlayed = 0,
        options = true,
        servers = 3,
        packs = listOf(CarryPack("resourcepacks/Faithful 32x.zip", 42 * mb)),
        worlds = listOf(
            CarryWorld("Survival", "Выживание с другом", current, 1_380 * mb, false),
            CarryWorld("Creative", "Креатив", current, 240 * mb, false),
            CarryWorld("Snapshot", "Тест снапшота", newest, 18 * mb, false),
            CarryWorld("New World", "New World", "26.1", 64 * mb, true),
        ),
    )
    val fabric = versions.copy(dir = Path.of("26.2-fabric"), label = "26.2 Fabric", servers = 0, packs = emptyList())
    val official = versions.copy(dir = Path.of(".minecraft"), label = ".minecraft", official = true)
    return if (first) listOf(versions, fabric, official) else listOf(official)
}
