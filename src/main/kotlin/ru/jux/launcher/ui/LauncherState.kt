package ru.jux.launcher.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.jux.launcher.activity.ActivityStats
import ru.jux.launcher.activity.PlayHistory
import ru.jux.launcher.auth.Account
import ru.jux.launcher.auth.AccountManager
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Notice
import ru.jux.launcher.core.NoticeAction
import ru.jux.launcher.core.NoticeLevel
import ru.jux.launcher.core.Notices
import ru.jux.launcher.core.PlayRequest
import ru.jux.launcher.core.PreloadResult
import ru.jux.launcher.core.Preloader
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.Shortcuts
import ru.jux.launcher.core.Storage
import ru.jux.launcher.core.VerifyCache
import ru.jux.launcher.discord.DiscordPresence
import ru.jux.launcher.discord.GameEvent
import ru.jux.launcher.discord.GameEvents
import ru.jux.launcher.discord.Presence
import ru.jux.launcher.instance.CarryOver
import ru.jux.launcher.instance.CarryPlan
import ru.jux.launcher.instance.CarryResult
import ru.jux.launcher.instance.CarrySource
import ru.jux.launcher.instance.InstanceOptions
import ru.jux.launcher.instance.InstanceStore
import ru.jux.launcher.launch.GameLauncher
import ru.jux.launcher.logs.CrashHints
import ru.jux.launcher.logs.LogSource
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.meta.LoaderSupport
import ru.jux.launcher.meta.ManifestVersion
import ru.jux.launcher.meta.VersionKind
import ru.jux.launcher.meta.VersionManifest
import ru.jux.launcher.mods.ModCompat
import ru.jux.launcher.mods.ModManager
import ru.jux.launcher.mods.PerformancePack
import ru.jux.launcher.online.OnlineCounter
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.packs.Modpack
import ru.jux.launcher.packs.Modpacks
import ru.jux.launcher.packs.PackSource
import ru.jux.launcher.servers.ServerEntry
import ru.jux.launcher.servers.ServerList
import ru.jux.launcher.servers.ServerPing
import ru.jux.launcher.servers.ServerStatus
import ru.jux.launcher.servers.Servers
import ru.jux.launcher.ui.screens.plural
import ru.jux.launcher.update.UpdateManifest
import ru.jux.launcher.update.Updater

enum class Screen { HOME, CATALOG, ACTIVITY, NOTICES, SETTINGS, ACCOUNTS }

data class VersionEntry(
    val version: ManifestVersion,
    val loader: LoaderKind,
    val pack: Modpack? = null,
) {
    val id: String get() = version.id
    val key: String get() = pack?.let { PACK_KEY + it.id } ?: "${version.id}#${loader.name}"
    val title: String get() = pack?.title ?: id
    val label: String get() = pack?.title ?: if (loader.isModded) "$id ${loader.label}" else id
}

private const val PACK_KEY = "pack:"
const val PACKS_GROUP = "Сборки"

data class VersionGroup(
    val key: String,
    val entries: List<VersionEntry>,
)

enum class CatalogTab { PACKS, MODS, SHADERS, RESOURCE_PACKS, INSTALLED }

sealed interface Modal {
    data class Delete(val entry: VersionEntry) : Modal
    data class Logs(val gameDir: Path?, val title: String, val source: LogSource) : Modal
    data class Carry(
        val entry: VersionEntry,
        val sources: List<CarrySource>,
        val firstLaunch: Boolean,
        val targetHasOptions: Boolean,
    ) : Modal
}

sealed interface PingState {
    data object Pinging : PingState
    data object Offline : PingState
    class Online(val status: ServerStatus) : PingState
}

class LauncherState(
    private val scope: CoroutineScope,
    preloaded: PreloadResult? = null,
    playRequest: PlayRequest? = null,
) {

    var screen by mutableStateOf(Screen.HOME)
    var versions by mutableStateOf<List<ManifestVersion>>(emptyList())
    var selectedVersionId by mutableStateOf(Settings.current.lastVersionId)
    var searchQuery by mutableStateOf("")
    var onlyInstalled by mutableStateOf(Settings.current.onlyInstalled)
        private set

    var installed by mutableStateOf<Map<String, Long>>(emptyMap())

    var profiles by mutableStateOf<Set<String>>(emptySet())
    var expandedGroups by mutableStateOf<Set<String>>(emptySet())

    var selectedLoader by mutableStateOf(LoaderKind.VANILLA)

    var selectedPackId by mutableStateOf<String?>(null)
        private set

    var packs by mutableStateOf<List<Modpack>>(emptyList())
        private set

    var packUpdates by mutableStateOf<Map<String, PackSource>>(emptyMap())
        private set

    var installingPack by mutableStateOf<String?>(null)
        private set

    var catalogTab by mutableStateOf(CatalogTab.PACKS)
    var catalogTarget by mutableStateOf<VersionEntry?>(null)

    var loaderSupport by mutableStateOf(LoaderSupport())

    var selectedOptions by mutableStateOf(InstanceOptions())
        private set

    var instanceRevision by mutableStateOf(0)
        private set

    var busy by mutableStateOf(false)
        private set
    var stage by mutableStateOf("")
        private set
    var progress by mutableStateOf<DownloadProgress?>(null)
        private set

    var busyEntry by mutableStateOf<VersionEntry?>(null)
        private set

    var signingIn by mutableStateOf(false)
        private set
    var signInStage by mutableStateOf("")
        private set

    var toast by mutableStateOf<Notice?>(null)
        private set

    val notices = Notices.items
    val noticesSeen = Notices.seen

    var seenBeforeOpen by mutableStateOf(0L)
        private set

    var activity by mutableStateOf<ActivityStats?>(null)
        private set

    var modal by mutableStateOf<Modal?>(null)

    var openMenus by mutableStateOf(0)
        private set

    val searchFocus = FocusRequester()
    var searchFocused by mutableStateOf(false)

    var keyboardMoves by mutableStateOf(0)
        private set

    var serverStatus by mutableStateOf<Map<String, PingState>>(emptyMap())
        private set

    val listState = LazyListState()

    var onGameStarted: (Process) -> Unit = {}

    var onQuit: () -> Unit = {}

    private var lastLaunch: Pair<VersionEntry, Path>? = null

    private var job: Job? = null
    private var jobToken: Any? = null
    private var pendingPlay: Boolean = false
    private var carryAnswer: CompletableDeferred<Pair<CarrySource, CarryPlan>?>? = null

    val accounts = AccountManager.accounts
    val selectedAccount = AccountManager.selected
    val servers = Servers.all
    val updates = Updater.state

    init {
        preloaded?.let {
            adopt(it)
            val refreshVersions = it.manifestStale || it.manifest.versions.isEmpty()
            if (refreshVersions || it.loaderSupportStale) {
                refreshInBackground(refreshVersions, it.loaderSupportStale)
            }
        }
        playRequest?.let { request ->
            val entry = requested(request)
            if (entry != null) {
                selectEntry(entry)
                pendingPlay = true
            }
        }
    }

    private fun adopt(preloaded: PreloadResult) {
        versions = preloaded.manifest.versions
        installed = preloaded.installed
        profiles = preloaded.profiles
        loaderSupport = preloaded.loaderSupport
        packs = preloaded.packs
        selectStartingEntry(preloaded.manifest.latest.release)
        expandedGroups = defaultExpanded()
        if (versions.isEmpty()) fail(NO_VERSIONS)
        if (packs.isNotEmpty()) checkPackUpdates()
    }

    private fun defaultExpanded(): Set<String> =
        setOfNotNull(PACKS_GROUP, groups().firstOrNull { it.key != PACKS_GROUP }?.key)

    private fun refreshInBackground(refreshVersions: Boolean, refreshLoaders: Boolean) {
        scope.launch {
            val manifest = if (refreshVersions) async { Preloader.refreshManifest() } else null
            val support = if (refreshLoaders) async { Preloader.refreshLoaderSupport() } else null

            val versionsOutcome = when (manifest) {
                null -> "skipped"
                else -> manifest.await()?.let { if (applyManifest(it)) "updated" else "unchanged" } ?: "unreachable"
            }
            val loadersOutcome = when (support) {
                null -> "skipped"
                else -> {
                    val fresh = support.await()
                    if (fresh != loaderSupport) {
                        loaderSupport = fresh
                        "updated"
                    } else {
                        "unchanged"
                    }
                }
            }
            Log.info("background refresh: versions $versionsOutcome, loaders $loadersOutcome")
        }
    }

    private fun applyManifest(fresh: VersionManifest): Boolean {
        if (fresh.versions.isEmpty() || fresh.versions == versions) return false
        val wasEmpty = versions.isEmpty()
        versions = fresh.versions
        if (wasEmpty) {
            selectStartingEntry(fresh.latest.release)
            expandedGroups = defaultExpanded()
            toast?.takeIf { it.text == NO_VERSIONS }?.let { dismissToast(it.id) }
        }
        return true
    }

    private fun selectStartingEntry(latestRelease: String) {
        Settings.current.lastPack?.let { id -> packs.firstOrNull { it.id == id } }?.let { pack ->
            selectEntry(entryFor(pack))
            return
        }
        val remembered = selectedVersionId
        val rememberedLoader = LoaderKind.entries.firstOrNull { it.name == Settings.current.lastLoader }
            ?: LoaderKind.VANILLA

        val (version, loader) = when {
            remembered != null && isInstalled(remembered, rememberedLoader) -> remembered to rememberedLoader
            remembered != null && isInstalled(remembered) -> remembered to LoaderKind.VANILLA
            else -> {
                val fallback = versions.firstOrNull { isInstalled(it.id) }?.id
                    ?: latestRelease.takeIf { it.isNotBlank() }
                    ?: versions.firstOrNull { it.kind == VersionKind.RELEASE }?.id
                    ?: versions.firstOrNull()?.id
                    ?: remembered
                fallback to LoaderKind.VANILLA
            }
        }
        selectedVersionId = version
        selectedLoader = loader
        selectedPackId = null
        refreshSelectedOptions()
    }

    fun refreshInstalled() {
        scope.launch {
            val (jars, found) = withContext(Dispatchers.IO) { Preloader.scanInstalled() }
            installed = jars
            profiles = found
        }
    }

    fun isInstalled(id: String?, loader: LoaderKind = LoaderKind.VANILLA): Boolean = when {
        id == null -> false
        loader == LoaderKind.VANILLA -> installed.containsKey(id)
        else -> profiles.any { loader.ownsProfile(it, id) }
    }

    fun isEntryInstalled(entry: VersionEntry): Boolean = isInstalled(entry.id, entry.loader)

    private fun visibleVersions(): List<ManifestVersion> {
        val settings = Settings.current
        val query = searchQuery.trim().lowercase()
        return versions.asSequence()
            .filter { version ->
                onlyInstalled || when (version.kind) {
                    VersionKind.RELEASE -> true
                    VersionKind.SNAPSHOT -> settings.showSnapshots
                    VersionKind.OLD_BETA, VersionKind.OLD_ALPHA, VersionKind.OTHER -> settings.showOldVersions
                }
            }
            .filter { query.isEmpty() || it.id.lowercase().contains(query) }
            .toList()
    }

    private var groupsCache: Pair<List<Any?>, List<VersionGroup>>? = null

    fun groups(): List<VersionGroup> {
        val settings = Settings.current
        val inputs = listOf(
            versions, searchQuery, onlyInstalled, packs, loaderSupport, installed, profiles,
            settings.showSnapshots, settings.showOldVersions,
        )
        groupsCache?.takeIf { it.first == inputs }?.let { return it.second }
        return buildGroups().also { groupsCache = inputs to it }
    }

    private fun buildGroups(): List<VersionGroup> {
        val query = searchQuery.trim().lowercase()
        val packEntries = packs
            .filter { query.isEmpty() || query in it.title.lowercase() || query in it.gameVersion.lowercase() }
            .map(::entryFor)
        val versionGroups = visibleVersions()
            .groupBy { groupKeyOf(it) }
            .map { (key, items) ->
                VersionGroup(
                    key = key,
                    entries = items.flatMap { version ->
                        loaderSupport.loadersFor(version.id).map { VersionEntry(version, it) }
                    }.filter { !onlyInstalled || isEntryInstalled(it) },
                )
            }
            .filter { it.entries.isNotEmpty() }
        return listOfNotNull(VersionGroup(PACKS_GROUP, packEntries).takeIf { packEntries.isNotEmpty() }) + versionGroups
    }

    fun isExpanded(group: VersionGroup): Boolean = searchQuery.isNotBlank() || onlyInstalled || group.key in expandedGroups

    fun toggleOnlyInstalled() {
        onlyInstalled = !onlyInstalled
        Settings.update { it.copy(onlyInstalled = onlyInstalled) }
    }

    fun toggleGroup(key: String) {
        expandedGroups = if (key in expandedGroups) expandedGroups - key else expandedGroups + key
    }

    fun selectEntry(entry: VersionEntry) {
        selectedVersionId = entry.version.id
        selectedLoader = entry.loader
        selectedPackId = entry.pack?.id
        refreshSelectedOptions()
    }

    fun isSelected(entry: VersionEntry): Boolean =
        entry.pack?.id == selectedPackId && entry.version.id == selectedVersionId && entry.loader == selectedLoader

    fun currentEntry(): VersionEntry? {
        selectedPackId?.let { id -> packs.firstOrNull { it.id == id } }?.let { return entryFor(it) }
        val id = selectedVersionId ?: return null
        return VersionEntry(versionById(id), selectedLoader)
    }

    fun entryFor(pack: Modpack): VersionEntry = VersionEntry(versionById(pack.gameVersion), pack.loader, pack)

    fun entryByKey(key: String): VersionEntry? {
        if (key.startsWith(PACK_KEY)) return packs.firstOrNull { it.id == key.removePrefix(PACK_KEY) }?.let(::entryFor)
        val id = key.substringBeforeLast('#')
        val loader = LoaderKind.entries.firstOrNull { it.name == key.substringAfterLast('#') } ?: return null
        return entryFor(id, loader)
    }

    fun entryFor(versionId: String, loader: LoaderKind): VersionEntry = VersionEntry(versionById(versionId), loader)

    private fun requested(request: PlayRequest): VersionEntry? {
        val entry = when (val id = request.pack) {
            null -> versions.firstOrNull { it.id == request.versionId }?.let { VersionEntry(it, request.loader) }
            else -> packs.firstOrNull { it.id == id }?.let(::entryFor)
        }
        if (entry == null) {
            fail(
                if (request.pack != null) "Сборки из ярлыка больше нет — установи её заново во вкладке «Сборки»"
                else "Версии ${request.versionId} из ярлыка нет в списке Mojang"
            )
        }
        return entry
    }

    private fun versionById(id: String): ManifestVersion =
        versions.firstOrNull { it.id == id } ?: ManifestVersion(id = id, url = "")

    fun gameDirOf(entry: VersionEntry): Path = entry.pack?.let(Modpacks::dirOf) ?: Settings.gameDir(entry.id, entry.loader)

    private fun refreshSelectedOptions() {
        val entry = currentEntry() ?: return
        selectedOptions = InstanceStore.get(gameDirOf(entry))
    }

    private fun instanceChanged(entry: VersionEntry) {
        instanceRevision++
        if (isSelected(entry)) refreshSelectedOptions()
    }

    private fun startJob(entry: VersionEntry?, what: String, block: suspend () -> Unit) {
        if (busy) return
        val token = Any()
        jobToken = token
        busy = true
        busyEntry = entry
        stage = ""
        progress = null
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (jobToken === token) {
                    Log.error("$what failed", e)
                    fail(e.message ?: e::class.simpleName, entry, title = FAIL_TITLES[what])
                }
            } finally {
                if (jobToken === token) finishJob()
            }
        }
    }

    private fun stageChanged(text: String) {
        stage = text
        progress = null
    }

    private fun finishJob() {
        busy = false
        busyEntry = null
        stage = ""
        progress = null
        job = null
        jobToken = null
    }

    fun cancel() {
        job?.cancel()
        finishJob()
    }

    fun gameExited(exitCode: Int) {
        if (exitCode == 0) return
        val (entry, logFile) = lastLaunch ?: return
        Log.warn("game exited with code $exitCode")
        val actions = buildList {
            add(NoticeAction.LOGS)
            when {
                !entry.loader.isModded -> Unit
                hasModConflicts(entry) -> add(NoticeAction.FIX_MODS)
                else -> add(NoticeAction.MODS)
            }
        }
        fail(CrashHints.detail(logFile, entry.loader, entry.id, exitCode), entry, "Игра закрылась с ошибкой", actions)
    }

    private fun hasModConflicts(entry: VersionEntry): Boolean =
        runCatching { ModCompat.conflictsIn(ModManager.modsDir(gameDirOf(entry))).isNotEmpty() }.getOrDefault(false)

    fun fail(
        text: String?,
        entry: VersionEntry? = null,
        title: String? = null,
        actions: List<NoticeAction> = emptyList(),
    ) = post(NoticeLevel.ERROR, text ?: "Неизвестная ошибка", title, entry, actions)

    fun inform(
        text: String,
        entry: VersionEntry? = null,
        title: String? = null,
        level: NoticeLevel = NoticeLevel.SUCCESS,
    ) = post(level, text, title, entry, emptyList())

    private fun post(level: NoticeLevel, text: String, title: String?, entry: VersionEntry?, actions: List<NoticeAction>) {
        val (notice, repeated) = Notices.post(level, text, title, entry?.key, entry?.label, actions)
        when {
            screen == Screen.NOTICES -> Notices.markSeen()
            !repeated || level == NoticeLevel.ERROR -> toast = notice
        }
    }

    fun dismissToast(id: Long) {
        if (toast?.id == id) toast = null
    }

    fun refreshActivity() {
        scope.launch { loadActivity() }
    }

    suspend fun loadActivity() {
        val roots = Settings.gameRoots()
        activity = withContext(Dispatchers.IO) {
            runCatching { ActivityStats.of(PlayHistory.scan(roots)) }
                .onFailure { Log.warn("activity scan failed: ${it.message}") }
                .getOrNull()
        } ?: activity ?: ActivityStats.of(emptyList())
    }

    fun openNotices() {
        toast = null
        if (screen != Screen.NOTICES) seenBeforeOpen = Notices.seen.value
        screen = Screen.NOTICES
        Notices.markSeen()
    }

    fun removeNotice(notice: Notice) {
        Notices.remove(notice.id)
        dismissToast(notice.id)
    }

    fun clearNotices() {
        Notices.clear()
        toast = null
    }

    fun runAction(notice: Notice, action: NoticeAction) {
        dismissToast(notice.id)
        val entry = notice.entryKey?.let(::entryByKey)
        when (action) {
            NoticeAction.LOGS -> showLogs(entry)
            NoticeAction.MODS -> entry?.let { openCatalog(it, CatalogTab.INSTALLED) }
            NoticeAction.FIX_MODS -> entry?.let { fixMods(it, notice) }
            NoticeAction.PLAY_ANYWAY -> entry?.let {
                Notices.resolve(notice.id)
                selectEntry(it)
                play(skipModCheck = true)
            }
        }
    }

    fun fixMods(entry: VersionEntry, source: Notice? = null) {
        startJob(entry, "fix mods") {
            stageChanged("Подбираю совместимые версии модов")
            val changes = try {
                ModManager.fixConflicts(gameDirOf(entry), entry.loader, entry.id) { progress = it }
            } catch (e: IOException) {
                fail(e.message, entry, "Моды не исправлены", listOf(NoticeAction.MODS))
                return@startJob
            }
            source?.let { Notices.resolve(it.id) }
            modsChanged(entry)
            inform(changes.joinToString("\n").ifEmpty { "Несовместимых модов не нашлось" }, entry, "Моды исправлены")
        }
    }

    fun consumePendingPlay(): Boolean = pendingPlay.also { pendingPlay = false }

    fun play(serverAddress: String? = null, skipModCheck: Boolean = false) {
        if (busy) return
        val entry = currentEntry() ?: run {
            fail("Выберите версию")
            return
        }
        val account = selectedAccount.value ?: run {
            fail("Добавьте аккаунт")
            screen = Screen.ACCOUNTS
            return
        }
        startJob(entry, "launch") { launch(entry, account, serverAddress, skipModCheck) }
    }

    private suspend fun launch(entry: VersionEntry, account: Account, serverAddress: String?, skipModCheck: Boolean) {
        val gameDir = gameDirOf(entry)
        if (!offerCarryOver(entry, gameDir)) return
        if (!skipModCheck && entry.loader.isModded) {
            val conflicts = withContext(Dispatchers.IO) {
                runCatching { ModCompat.conflictsIn(ModManager.modsDir(gameDir)) }.getOrDefault(emptyList())
            }
            if (conflicts.isNotEmpty()) {
                fail(
                    conflicts.take(3).joinToString("\n") { it.text },
                    entry,
                    "Игра не запущена: моды несовместимы",
                    listOf(NoticeAction.FIX_MODS, NoticeAction.PLAY_ANYWAY),
                )
                return
            }
        }
        val notices = ArrayList<String>()
        val result = GameLauncher.launch(
            versionId = entry.id,
            account = account,
            loader = entry.loader,
            serverAddress = serverAddress,
            gameDir = gameDir,
            loaderVersion = entry.pack?.loaderVersion,
            onStage = ::stageChanged,
            onProgress = { progress = it },
            onNotice = { notices += it },
        )
        Settings.update { it.copy(lastVersionId = entry.id, lastLoader = entry.loader.name, lastPack = entry.pack?.id) }
        notices.forEach { inform(it, entry, level = NoticeLevel.INFO) }
        refreshInstalled()
        instanceChanged(entry)
        lastLaunch = entry to result.logFile
        val playing = Presence.Playing(
            versionId = entry.id,
            loaderLabel = entry.loader.takeIf { it.isModded }?.label,
            server = serverAddress?.let(GameEvents::display),
            mods = if (entry.loader.isModded) ModManager.count(gameDir) else 0,
            startedAt = System.currentTimeMillis(),
            pack = entry.pack?.title,
        )
        DiscordPresence.show(playing)
        OnlineCounter.setPlaying(true)
        watchGame(result.process, result.logFile, result.gameDir, playing)
        onGameStarted(result.process)
    }

    private suspend fun offerCarryOver(entry: VersionEntry, gameDir: Path): Boolean {
        val withShaders = supportsShaders(entry)
        val found = withContext(Dispatchers.IO) {
            if (!CarryOver.isFresh(gameDir)) return@withContext null
            val hasOptions = CarryOver.hasOwnOptions(gameDir)
            val sources = runCatching { CarryOver.sources(gameDir, withShaders) }
                .onFailure { Log.warn("carry-over lookup failed: ${it.message}") }
                .getOrDefault(emptyList())
                .filter { !hasOptions || !it.onlyOptions }
            sources.takeIf { it.isNotEmpty() }?.let { it to hasOptions }
        } ?: return true
        val answer = CompletableDeferred<Pair<CarrySource, CarryPlan>?>()
        carryAnswer = answer
        stageChanged("Ждёт выбора: что перенести")
        modal = Modal.Carry(entry, found.first, firstLaunch = true, targetHasOptions = found.second)
        val choice = try {
            answer.await()
        } finally {
            carryAnswer = null
            if (modal is Modal.Carry) modal = null
        }
        val (source, plan) = choice ?: return false
        if (!plan.isEmpty) carry(entry, source, plan, gameDir)
        return true
    }

    fun answerCarry(source: CarrySource, plan: CarryPlan) {
        carryAnswer?.complete(source to plan)
        if (modal is Modal.Carry) modal = null
    }

    fun dismissCarry() {
        carryAnswer?.complete(null)
        if (modal is Modal.Carry) modal = null
    }

    fun offerCarryInto(entry: VersionEntry) {
        if (busy) return
        scope.launch {
            val gameDir = gameDirOf(entry)
            val withShaders = supportsShaders(entry)
            val (sources, hasOptions) = withContext(Dispatchers.IO) {
                val sources = runCatching { CarryOver.sources(gameDir, withShaders) }
                    .onFailure { Log.warn("carry-over lookup failed: ${it.message}") }
                    .getOrDefault(emptyList())
                sources to CarryOver.hasOwnOptions(gameDir)
            }
            if (sources.isEmpty()) {
                inform("Не нашёл других версий с настройками, серверами или мирами", entry, level = NoticeLevel.INFO)
            } else if (!busy) {
                modal = Modal.Carry(entry, sources, firstLaunch = false, targetHasOptions = hasOptions)
            }
        }
    }

    fun carryInto(entry: VersionEntry, source: CarrySource, plan: CarryPlan) {
        if (modal is Modal.Carry) modal = null
        if (plan.isEmpty) return
        startJob(entry, "carry") { carry(entry, source, plan, gameDirOf(entry)) }
    }

    private suspend fun carry(entry: VersionEntry, source: CarrySource, plan: CarryPlan, gameDir: Path) {
        stageChanged("Переношу из ${source.label}")
        val result = withContext(Dispatchers.IO) {
            CarryOver.apply(source, gameDir, plan, onProgress = { progress = it }, checkCancelled = { ensureActive() })
        }
        stageChanged("")
        instanceChanged(entry)
        carried(result)?.let { inform("Из ${source.label}: $it", entry, "Перенесено в ${entry.label}") }
        if (result.failed.isNotEmpty()) {
            fail("Не скопировалось: ${result.failed.joinToString(", ")}. Подробности в логе лаунчера.", entry, "Перенесено не всё")
        }
    }

    private fun carried(result: CarryResult): String? {
        val parts = listOfNotNull(
            "настройки".takeIf { result.options },
            result.servers.takeIf { it > 0 }?.let { "$it ${plural(it, "сервер", "сервера", "серверов")}" },
            result.packs.takeIf { it > 0 }?.let { "$it ${plural(it, "пак", "пака", "паков")}" },
            result.worlds.takeIf { it > 0 }?.let { "$it ${plural(it, "мир", "мира", "миров")}" },
        )
        return when (parts.size) {
            0 -> null
            1 -> parts.single()
            else -> parts.dropLast(1).joinToString(", ") + " и " + parts.last()
        }
    }

    fun updatePack(pack: Modpack) {
        packUpdates[pack.id]?.let { installPack(it) }
    }

    fun installPack(source: PackSource) {
        if (busy) return
        installingPack = source.projectId
        startJob(packs.firstOrNull { it.id == source.id }?.let(::entryFor), "pack") {
            try {
                val pack = Modpacks.install(source, onStage = ::stageChanged, onProgress = { progress = it })
                packs = withContext(Dispatchers.IO) { Modpacks.list() }
                packUpdates = packUpdates - pack.id
                val entry = entryFor(pack)
                selectEntry(entry)
                instanceChanged(entry)
                inform("Сборка ${pack.title} ${pack.version} установлена", entry)
            } finally {
                installingPack = null
            }
        }
    }

    private fun reinstallPack(pack: Modpack) {
        if (busy) return
        scope.launch {
            runCatching { Modpacks.reinstallSource(pack) }
                .onSuccess { installPack(it) }
                .onFailure { if (it !is CancellationException) fail(it.message, entryFor(pack), "Сборка не переустановилась") }
        }
    }

    private fun checkPackUpdates() {
        scope.launch {
            val found = packs.mapNotNull { pack ->
                runCatching { Modpacks.update(pack) }
                    .onFailure { if (it !is CancellationException) Log.warn("pack update check for ${pack.id}: ${it.message}") }
                    .getOrNull()
                    ?.let { pack.id to it }
            }.toMap()
            packUpdates = found
        }
    }

    private fun watchGame(process: Process, logFile: Path, gameDir: Path, start: Presence.Playing) {
        scope.launch(Dispatchers.IO) {
            val tail = GameEvents.Tail(logFile)
            val serverList = ServerList.Guard(gameDir)
            var shown = start
            var iconOf: String? = null
            while (process.isAlive) {
                delay(GAME_LOG_POLL_MILLIS)
                serverList.check()
                var next = shown
                tail.lines().forEach { line ->
                    when (val event = GameEvents.parse(line)) {
                        is GameEvent.JoinedServer -> next = next.copy(server = event.address)
                        GameEvent.Singleplayer -> next = next.copy(server = null)
                        null -> Unit
                    }
                }
                if (next.server != iconOf) {
                    iconOf = next.server
                    next = next.copy(serverIcon = next.server?.let { serverIcon(it) })
                }
                if (next != shown && process.isAlive) {
                    shown = next
                    DiscordPresence.show(next)
                }
            }
        }
    }

    private suspend fun serverIcon(address: String): String? =
        ServerPing.ping(address)?.favicon?.let { DiscordPresence.serverIcon(address) }

    fun playFromShortcut(request: PlayRequest, gameRunning: Boolean) {
        if (busy) {
            fail("Сначала дождитесь окончания загрузки ${busyEntry?.label.orEmpty()}".trimEnd())
            return
        }
        val entry = requested(request) ?: return
        selectEntry(entry)
        if (gameRunning) inform("Игра уже запущена. ${entry.label} выбрана — запустите её, когда закончите", entry, level = NoticeLevel.INFO) else play()
    }

    fun playOnServer(server: ServerEntry) {
        if (busy) return
        if (selectedVersionId == null) server.entryKey?.let(::entryByKey)?.let(::selectEntry)
        play(serverAddress = server.address)
    }

    fun reinstall(entry: VersionEntry) {
        if (busy) return
        entry.pack?.let {
            reinstallPack(it)
            return
        }
        selectEntry(entry)
        startJob(entry, "reinstall") {
            val notices = ArrayList<String>()
            withContext(Dispatchers.IO) {
                Storage.deleteVersionFiles(Storage.versionIdsOf(entry.id, entry.loader, versions.map { it.id }))
                VerifyCache.clear()
            }
            GameLauncher.prepare(
                versionId = entry.id,
                loader = entry.loader,
                gameDir = gameDirOf(entry),
                onStage = ::stageChanged,
                onProgress = { progress = it },
                onNotice = { notices += it },
            )
            refreshInstalled()
            instanceChanged(entry)
            inform((listOf("${entry.label} переустановлена") + notices).joinToString("\n"), entry)
        }
    }

    fun delete(entry: VersionEntry, withGameDir: Boolean) {
        if (busy && busyEntry == entry) {
            fail("Сначала дождитесь окончания загрузки ${entry.label}", entry)
            return
        }
        entry.pack?.let {
            deletePack(it)
            return
        }
        scope.launch {
            val trashed = try {
                withContext(Dispatchers.IO) {
                    Storage.deleteVersionFiles(Storage.versionIdsOf(entry.id, entry.loader, versions.map { it.id }))
                    if (withGameDir) Storage.deleteGameDir(gameDirOf(entry)) else false
                }
            } catch (e: IOException) {
                refreshInstalled()
                instanceChanged(entry)
                fail(e.message, entry)
                return@launch
            }
            refreshInstalled()
            instanceChanged(entry)
            inform(
                when {
                    !withGameDir -> "Файлы ${entry.label} удалены, миры и настройки на месте"
                    trashed -> "${entry.label} удалена, папка сборки в корзине"
                    else -> "${entry.label} удалена вместе с папкой сборки"
                },
            )
        }
    }

    private fun deletePack(pack: Modpack) {
        scope.launch {
            val trashed = try {
                withContext(Dispatchers.IO) { Storage.deleteGameDir(Modpacks.dirOf(pack)) }
            } catch (e: IOException) {
                fail(e.message, entryFor(pack))
                return@launch
            }
            if (selectedPackId == pack.id) {
                selectedPackId = null
                refreshSelectedOptions()
            }
            if (catalogTarget?.pack?.id == pack.id) catalogTarget = null
            if (Settings.current.lastPack == pack.id) Settings.update { it.copy(lastPack = null) }
            packs = withContext(Dispatchers.IO) { Modpacks.list() }
            packUpdates = packUpdates - pack.id
            inform(if (trashed) "Сборка ${pack.title} в корзине" else "Сборка ${pack.title} удалена")
        }
    }

    fun setBoost(entry: VersionEntry, enabled: Boolean) {
        val dir = gameDirOf(entry)
        if (enabled) {
            InstanceStore.update(dir) { it.copy(fpsBoost = true, boostCheckedAt = 0) }
        } else {
            PerformancePack.remove(dir)
        }
        instanceChanged(entry)
    }

    fun enableBoost(entry: VersionEntry): Boolean {
        if (InstanceStore.get(gameDirOf(entry)).fpsBoost) return false
        setBoost(entry, true)
        return true
    }

    fun supportsShaders(entry: VersionEntry): Boolean = when (entry.loader) {
        LoaderKind.VANILLA -> entry.pack == null && loaderSupport.supports(LoaderKind.FABRIC, entry.id)
        LoaderKind.FORGE -> false
        else -> true
    }

    fun modsChanged(entry: VersionEntry) = instanceChanged(entry)

    fun openCatalog(entry: VersionEntry? = null, tab: CatalogTab? = null) {
        catalogTarget = entry
        tab?.let { catalogTab = it }
        screen = Screen.CATALOG
    }

    fun catalogTargets(): List<VersionEntry> {
        val installedVersions = versions
            .flatMap { version -> loaderSupport.loadersFor(version.id).map { VersionEntry(version, it) } }
            .filter(::isEntryInstalled)
        return (packs.map(::entryFor) + installedVersions + listOfNotNull(currentEntry())).distinctBy { it.key }
    }

    fun openFolder(dir: Path) = Shell.openFolder(dir) { fail(it) }

    fun showLogs(entry: VersionEntry?, source: LogSource = LogSource.GAME) {
        modal = Modal.Logs(entry?.let(::gameDirOf), entry?.label ?: "JuxLauncher", source)
    }

    fun createShortcut(entry: VersionEntry) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    Shortcuts.createOnDesktop(entry.pack?.title ?: "Minecraft ${entry.label}", entry.id, entry.loader, entry.pack?.id)
                }
            }
                .onSuccess { inform("Ярлык «${it.fileName.toString().removeSuffix(".lnk")}» на рабочем столе", entry) }
                .onFailure { fail(it.message, entry) }
        }
    }

    fun trackMenu(open: Boolean) {
        openMenus = (openMenus + if (open) 1 else -1).coerceAtLeast(0)
    }

    fun refreshServers() {
        servers.forEach(::pingServer)
    }

    private fun pingServer(server: ServerEntry) {
        if (serverStatus[server.address] !is PingState.Online) setPing(server.address, PingState.Pinging)
        scope.launch {
            val status = ServerPing.ping(server.address)
            setPing(server.address, status?.let { PingState.Online(it) } ?: PingState.Offline)
        }
    }

    private val pingLock = Any()

    private fun setPing(id: String, value: PingState) {
        synchronized(pingLock) { serverStatus = serverStatus + (id to value) }
    }

    fun checkForUpdates() {
        scope.launch { Updater.check() }
    }

    fun installUpdate(update: UpdateManifest) {
        scope.launch {
            runCatching { Updater.install(update) }
                .onSuccess { onQuit() }
                .onFailure { if (it !is CancellationException) fail(it.message, title = "Обновление не установилось") }
        }
    }

    fun signInMicrosoft() {
        if (signingIn) return
        signingIn = true
        scope.launch {
            runCatching { AccountManager.signInMicrosoft { signInStage = it } }
                .onSuccess { inform("Вход выполнен: ${it.name}") }
                .onFailure { failure ->
                    if (failure !is CancellationException) {
                        Log.error("microsoft sign-in failed", failure)
                        fail(failure.message ?: "Вход не удался", title = "Вход через Microsoft")
                    }
                }
            signingIn = false
            signInStage = ""
        }
    }

    fun addOffline(nickname: String): String? =
        runCatching { AccountManager.addOffline(nickname) }.fold(
            onSuccess = {
                inform("Добавлен офлайн-аккаунт ${it.name}")
                null
            },
            onFailure = { it.message ?: "Не получилось добавить аккаунт" },
        )

    fun removeAccount(uuid: String) = AccountManager.remove(uuid)

    fun selectAccount(uuid: String) = AccountManager.select(uuid)

    private fun visibleEntries(): List<VersionEntry> = groups().filter(::isExpanded).flatMap { it.entries }

    fun moveSelection(delta: Int) {
        val entries = visibleEntries()
        if (entries.isEmpty()) return
        val current = entries.indexOfFirst(::isSelected)
        val next = when {
            current < 0 -> if (delta > 0) 0 else entries.lastIndex
            else -> (current + delta).coerceIn(0, entries.lastIndex)
        }
        selectEntry(entries[next])
        keyboardMoves++
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (screen != Screen.HOME || modal != null || openMenus > 0) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false

        when (event.key) {
            Key.Enter, Key.NumPadEnter -> {
                if (!busy && selectedVersionId != null) play()
                return true
            }
            Key.DirectionDown -> {
                moveSelection(+1)
                return true
            }
            Key.DirectionUp -> {
                moveSelection(-1)
                return true
            }
            Key.Escape -> {
                if (searchQuery.isEmpty()) return false
                searchQuery = ""
                return true
            }
        }

        val char = event.awtEventOrNull?.keyChar ?: return false
        if (!searchFocused && char != java.awt.event.KeyEvent.CHAR_UNDEFINED && !char.isISOControl() && !char.isWhitespace()) {
            runCatching { searchFocus.requestFocus() }
        }
        return false
    }

    private companion object {
        const val GAME_LOG_POLL_MILLIS = 2_000L
        val FAIL_TITLES = mapOf(
            "launch" to "Игра не запустилась",
            "reinstall" to "Переустановка не удалась",
            "fix mods" to "Моды не исправлены",
            "pack" to "Сборка не установилась",
            "carry" to "Перенос не удался",
        )
        const val NO_VERSIONS = "Не удалось получить список версий. Проверьте интернет и перезапустите лаунчер."
    }
}

private val RELEASE_LINE = Regex("""^(\d+)\.(\d+)""")
private val WEEKLY_SNAPSHOT = Regex("""^(\d{2})w\d{2}[a-z~]""")

fun groupKeyOf(version: ManifestVersion): String {
    when (version.kind) {
        VersionKind.OLD_BETA -> return "Beta"
        VersionKind.OLD_ALPHA -> return "Alpha"
        else -> Unit
    }
    WEEKLY_SNAPSHOT.find(version.id)?.let { return "Снапшоты ${it.groupValues[1]}w" }
    RELEASE_LINE.find(version.id)?.let { return "${it.groupValues[1]}.${it.groupValues[2]}" }
    return "Прочее"
}
