package ru.jux.launcher.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.jux.launcher.activity.ActivityStats
import ru.jux.launcher.activity.PlayHistory
import ru.jux.launcher.auth.Account
import ru.jux.launcher.auth.AccountManager
import ru.jux.launcher.core.Backdrop
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Notice
import ru.jux.launcher.core.NoticeAction
import ru.jux.launcher.core.NoticeLevel
import ru.jux.launcher.core.Notices
import ru.jux.launcher.core.PlayRequest
import ru.jux.launcher.core.PreloadResult
import ru.jux.launcher.core.Preloader
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.SettingsDefaults
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.Shortcuts
import ru.jux.launcher.core.Storage
import ru.jux.launcher.core.VerifyCache
import ru.jux.launcher.core.plural
import ru.jux.launcher.core.tr
import ru.jux.launcher.discord.DiscordPresence
import ru.jux.launcher.discord.GameEvent
import ru.jux.launcher.discord.GameEvents
import ru.jux.launcher.discord.Presence
import ru.jux.launcher.friends.Friend
import ru.jux.launcher.friends.FriendAction
import ru.jux.launcher.friends.Friends
import ru.jux.launcher.friends.FriendsException
import ru.jux.launcher.friends.LinkPoll
import ru.jux.launcher.friends.LinkStart
import ru.jux.launcher.instance.CarryOver
import ru.jux.launcher.instance.CarryPlan
import ru.jux.launcher.instance.CarryResult
import ru.jux.launcher.instance.CarrySource
import ru.jux.launcher.instance.InstanceOptions
import ru.jux.launcher.instance.InstanceStore
import ru.jux.launcher.instance.WorldBackup
import ru.jux.launcher.instance.WorldInfo
import ru.jux.launcher.instance.Worlds
import ru.jux.launcher.launch.GameLauncher
import ru.jux.launcher.launch.GameWindow
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.link.LinkContent
import ru.jux.launcher.logs.CrashHints
import ru.jux.launcher.logs.GameLogs
import ru.jux.launcher.logs.LogSource
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.meta.LoaderSupport
import ru.jux.launcher.meta.ManifestVersion
import ru.jux.launcher.meta.VersionKind
import ru.jux.launcher.meta.VersionManifest
import ru.jux.launcher.mods.ContentKind
import ru.jux.launcher.mods.GameOptions
import ru.jux.launcher.mods.GraphicsPreset
import ru.jux.launcher.mods.InstalledItem
import ru.jux.launcher.mods.ModCompat
import ru.jux.launcher.mods.ModManager
import ru.jux.launcher.mods.Modrinth
import ru.jux.launcher.mods.PerformancePack
import ru.jux.launcher.online.OnlineCounter
import ru.jux.launcher.online.ServerJoins
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.net.HttpStatusException
import ru.jux.launcher.packs.Modpack
import ru.jux.launcher.packs.Modpacks
import ru.jux.launcher.packs.PackImport
import ru.jux.launcher.packs.PackLinks
import ru.jux.launcher.packs.PackShare
import ru.jux.launcher.packs.PackSource
import ru.jux.launcher.servers.ServerEntry
import ru.jux.launcher.servers.ServerList
import ru.jux.launcher.servers.ServerPing
import ru.jux.launcher.servers.ServerStatus
import ru.jux.launcher.servers.Servers
import ru.jux.launcher.ui.components.Backdrops
import ru.jux.launcher.ui.components.formatMemory
import ru.jux.launcher.ui.components.formatTotalMemory
import ru.jux.launcher.update.UpdateManifest
import ru.jux.launcher.update.Updater

enum class Screen { HOME, CATALOG, FRIENDS, ACTIVITY, NOTICES, SETTINGS, ACCOUNTS }

// Неизменяемые: строка списка с тем же содержимым пропускает перерисовку при пересборке групп
@Immutable
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
val PACKS_GROUP: String get() = tr("Сборки", "Packs")

@Immutable
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
    data class Join(val link: LaunchLink.Join, val choices: List<VersionEntry>, val note: String? = null) : Modal
    data class Install(val link: LaunchLink.Install, val plan: InstallPlan? = null) : Modal
    data class WorldList(val entry: VersionEntry) : Modal
    data class PackEditor(val mode: PackMode) : Modal
    data class Share(val entry: VersionEntry) : Modal
    data class Graphics(val entry: VersionEntry) : Modal
    data class ImportPack(val link: LaunchLink.SharedPack, val plan: PackImport? = null, val error: String? = null) : Modal
    data class FriendLink(val start: LinkStart, val failure: String? = null) : Modal
    data class FriendVersions(val friend: Friend) : Modal
    data class FriendPack(val friend: Friend, val link: LaunchLink.SharedPack, val plan: PackImport? = null, val error: String? = null) : Modal
}

/** Запущенный клиент игры: их может быть несколько сразу, в том числе одной сборки под разными аккаунтами. */
class RunningGame(
    val entry: VersionEntry,
    val process: Process,
    val logFile: Path,
    val startedAt: Long,
    presence: Presence.Playing,
    /** Ник аккаунта Minecraft, под которым запущен клиент. */
    val nick: String = "",
) {
    /** Окно игры уже на экране; до этого игра только грузится. */
    var opened by mutableStateOf(false)
        internal set

    // Статус Discord этого клиента: когда другой клиент закроется, показываем его
    @Volatile
    var presence: Presence.Playing = presence
        internal set

    /** Ссылка на эту сборку для друзей: считается один раз за запуск и только у сборки с модами. */
    @Volatile
    var packLink: String? = null
        internal set
}

sealed interface PackMode {
    data object Create : PackMode
    data class Copy(val from: VersionEntry) : PackMode
    data class Edit(val pack: Modpack) : PackMode
}

sealed interface InstallPlan {
    data class Content(
        val project: Modrinth.Project,
        val kind: ContentKind,
        val targets: List<VersionEntry>,
        val unfit: Int,
    ) : InstallPlan
    data class Pack(val project: Modrinth.Project, val installed: Modpack?) : InstallPlan
    data class Failed(val message: String) : InstallPlan
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
    link: LaunchLink? = null,
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

    /** Сколько модов можно обновить, по ключу сборки. */
    var modUpdates by mutableStateOf<Map<String, Int>>(emptyMap())
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
        internal set
    var stage by mutableStateOf("")
        private set
    var progress by mutableStateOf<DownloadProgress?>(null)
        internal set

    var busyEntry by mutableStateOf<VersionEntry?>(null)
        private set

    var signingIn by mutableStateOf(false)
        private set
    var signInStage by mutableStateOf("")
        private set

    /** Друг, к которому сейчас заходим: его кнопка «Присоединиться» показывает загрузку. */
    var joiningFriend by mutableStateOf<String?>(null)
        internal set

    var friendLinkStarting by mutableStateOf(false)
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

    /** Окно не свёрнуто. Пинг серверов и обновление активности раз в минуту ждут его: свёрнутым их никто не видит. */
    var windowShown by mutableStateOf(true)

    suspend fun awaitWindowShown() {
        snapshotFlow { windowShown }.first { it }
    }

    val searchFocus = FocusRequester()
    var searchFocused by mutableStateOf(false)

    var keyboardMoves by mutableStateOf(0)
        private set

    var serverStatus by mutableStateOf<Map<String, PingState>>(emptyMap())
        private set

    var games by mutableStateOf<List<RunningGame>>(emptyList())
        internal set

    val gameRunning: Boolean get() = games.isNotEmpty()

    val listState = LazyListState()

    var onGameStarted: (Process) -> Unit = {}

    var onQuit: () -> Unit = {}

    private var job: Job? = null
    private var jobToken: Any? = null
    private var pendingPlay: Boolean = false
    private var carryAnswer: CompletableDeferred<Pair<CarrySource, CarryPlan>?>? = null
    private var linkJob: Job? = null

    val accounts = AccountManager.accounts
    val selectedAccount = AccountManager.selected
    val servers = Servers.all
    val updates = Updater.state
    val friendsAccount = Friends.account
    val friendsSnapshot = Friends.snapshot

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
        link?.let(::openLink)
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
            settings.showSnapshots, settings.showOldVersions, settings.language,
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
                if (request.pack != null) tr("Сборки из ярлыка больше нет", "The shortcut's pack no longer exists")
                else tr("Версии ${request.versionId} из ярлыка больше нет", "The shortcut's version ${request.versionId} no longer exists")
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
                    fail(e.message ?: e::class.simpleName, entry, title = failTitle(what))
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
        joiningFriend = null
        stage = ""
        progress = null
        job = null
        jobToken = null
    }

    fun cancel() {
        job?.cancel()
        finishJob()
    }

    /** Клиент [process] закрылся. true — лаунчер стоит показать: это была ошибка или игр больше не осталось. */
    fun gameExited(process: Process, exitCode: Int): Boolean {
        val launched = games.firstOrNull { it.process === process } ?: return games.isEmpty()
        games = games - launched
        val left = games.lastOrNull()
        if (left != null) DiscordPresence.show(left.presence) else DiscordPresence.show(Presence.Launcher)
        OnlineCounter.setPlaying(left != null)
        updateFriendPresence()
        val opened = launched.opened
        // Нулевой код — обычный выход, если только окно игры так и не появилось
        if (exitCode == 0 && opened) return left == null
        val entry = launched.entry
        Log.warn(if (opened) "game exited with code $exitCode" else "game exited with code $exitCode before its window opened")
        val crashReport = GameLogs.locate(LogSource.CRASH, gameDirOf(entry))
            ?.takeIf { runCatching { it.getLastModifiedTime().toMillis() >= launched.startedAt }.getOrDefault(false) }
        val diagnosis = CrashHints.diagnose(launched.logFile, crashReport, entry.loader, entry.id, exitCode)
        val actions = buildList {
            diagnosis.fix?.let { add(it.action) }
            add(NoticeAction.LOGS)
            when {
                !entry.loader.hasMods || diagnosis.fix != null -> Unit
                hasModConflicts(entry) -> add(NoticeAction.FIX_MODS)
                else -> add(NoticeAction.MODS)
            }
        }
        val title = if (opened) tr("Игра закрылась с ошибкой", "Game exited with an error") else tr("Игра не открылась", "The game didn't open")
        fail(diagnosis.reason, entry, title, actions, diagnosis.fix?.target)
        return true
    }

    fun isRunning(entry: VersionEntry): Boolean = games.any { it.entry.key == entry.key }

    /** Клиент этой сборки запущен, но окна ещё нет: второй до этого не запускаем, иначе повторные нажатия плодят клиенты. */
    fun isStarting(entry: VersionEntry): Boolean = games.any { it.entry.key == entry.key && !it.opened }

    private fun hasModConflicts(entry: VersionEntry): Boolean =
        runCatching { ModCompat.conflictsIn(ModManager.modsDir(gameDirOf(entry))).isNotEmpty() }.getOrDefault(false)

    fun fail(
        text: String?,
        entry: VersionEntry? = null,
        title: String? = null,
        actions: List<NoticeAction> = emptyList(),
        actionTarget: String? = null,
    ) = post(NoticeLevel.ERROR, text ?: tr("Неизвестная ошибка", "Unknown error"), title, entry, actions, actionTarget)

    fun inform(
        text: String,
        entry: VersionEntry? = null,
        title: String? = null,
        level: NoticeLevel = NoticeLevel.SUCCESS,
    ) = post(level, text, title, entry, emptyList())

    private fun post(
        level: NoticeLevel,
        text: String,
        title: String?,
        entry: VersionEntry?,
        actions: List<NoticeAction>,
        actionTarget: String? = null,
    ) {
        val (notice, repeated) = Notices.post(level, text, title, entry?.key, entry?.label, actions, actionTarget = actionTarget)
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
            NoticeAction.MORE_MEMORY, NoticeAction.RESET_MEMORY, NoticeAction.VERIFY_FILES ->
                entry?.let { fixSettingsAndRetry(it, notice, action) }
            NoticeAction.DISABLE_MOD -> entry?.let { disableCrashedMod(it, notice) }
            NoticeAction.INSTALL_MOD -> entry?.let { installMissingMod(it, notice) }
        }
    }

    private fun fixSettingsAndRetry(entry: VersionEntry, notice: Notice, action: NoticeAction) {
        val total = SettingsDefaults.totalSystemMemoryMb()
        val current = Settings.current.memoryMb
        val message = when (action) {
            NoticeAction.MORE_MEMORY -> {
                val more = SettingsDefaults.memoryPresets(total).firstOrNull { it > current } ?: run {
                    fail(tr("Больше памяти не выделить: в компьютере ${formatTotalMemory(total)}", "Can't allocate more memory: this PC has ${formatTotalMemory(total)}"), entry)
                    return
                }
                Settings.update { it.copy(memoryMb = more) }
                tr("Память: ${formatMemory(more)}", "Memory: ${formatMemory(more)}")
            }
            NoticeAction.RESET_MEMORY -> {
                val recommended = SettingsDefaults.recommendedMemory(total)
                Settings.update { it.copy(memoryMb = recommended) }
                tr("Память: ${formatMemory(recommended)}", "Memory: ${formatMemory(recommended)}")
            }
            else -> {
                Settings.update { it.copy(forceVerify = true) }
                tr("Перепроверю файлы игры", "Will re-check game files")
            }
        }
        Notices.resolve(notice.id)
        inform(message, entry, tr("Исправлено, запускаю", "Fixed, launching"), NoticeLevel.INFO)
        selectEntry(entry)
        play()
    }

    private fun disableCrashedMod(entry: VersionEntry, notice: Notice) {
        val modId = notice.actionTarget ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ModManager.disableById(gameDirOf(entry), modId) } }
                .onSuccess { file ->
                    if (file == null) {
                        fail(tr("Мода «$modId» уже нет в папке mods", "Mod “$modId” is no longer in the mods folder"), entry, actions = listOf(NoticeAction.MODS))
                        return@onSuccess
                    }
                    Notices.resolve(notice.id)
                    modsChanged(entry)
                    inform(tr("$file выключен", "$file disabled"), entry, tr("Исправлено, запускаю", "Fixed, launching"), NoticeLevel.INFO)
                    selectEntry(entry)
                    play()
                }
                .onFailure { fail(it.message, entry, tr("Мод не выключился", "Couldn't disable the mod")) }
        }
    }

    private fun installMissingMod(entry: VersionEntry, notice: Notice) {
        val modId = notice.actionTarget ?: return
        startJob(entry, "content") {
            stageChanged(tr("Ищу «$modId» на Modrinth", "Looking for “$modId” on Modrinth"))
            val project = try {
                withContext(Dispatchers.IO) { Modrinth.project(MOD_ALIASES[modId] ?: modId) }
            } catch (e: HttpStatusException) {
                if (e.code != 404) throw e
                throw IOException(tr("«$modId» нет на Modrinth", "“$modId” isn't on Modrinth"))
            }
            val present = ModManager.scan(gameDirOf(entry), entry.loader, entry.id).mapNotNull { it.projectId }.toSet()
            val message = installInto(entry, ContentKind.MOD, project.id, project.title, present) { progress = it }
            Notices.resolve(notice.id)
            modsChanged(entry)
            inform(message, entry, tr("Зависимость поставлена", "Dependency installed"))
        }
    }

    fun fixMods(entry: VersionEntry, source: Notice? = null) {
        startJob(entry, "fix mods") {
            backupWorldsBeforeChange(entry)
            stageChanged(tr("Подбираю совместимые версии модов", "Finding compatible mod versions"))
            val changes = try {
                ModManager.fixConflicts(gameDirOf(entry), entry.loader, entry.id) { progress = it }
            } catch (e: IOException) {
                fail(e.message, entry, tr("Моды не исправлены", "Mods not fixed"), listOf(NoticeAction.MODS))
                return@startJob
            }
            source?.let { Notices.resolve(it.id) }
            modsChanged(entry)
            inform(changes.joinToString("\n").ifEmpty { tr("Несовместимых модов не нашлось", "No incompatible mods found") }, entry, tr("Моды исправлены", "Mods fixed"))
        }
    }

    /** Сборки, где моды ставил сам игрок: у сборок с Modrinth моды обновляются вместе со сборкой. */
    private fun modTargets(): List<VersionEntry> =
        catalogTargets().filter { it.loader.hasMods && it.pack?.custom != false }

    private suspend fun pendingModUpdates(entry: VersionEntry): List<InstalledItem> =
        ModManager.scan(gameDirOf(entry), entry.loader, entry.id, ContentKind.MOD, withUpdates = true).filter { it.canUpdate }

    /** Тихо проверяет, вышли ли новые версии модов; результат виден значком у сборки. */
    fun checkModUpdates() {
        scope.launch {
            delay(MOD_CHECK_DELAY_MILLIS)
            for (entry in modTargets()) {
                try {
                    if (withContext(Dispatchers.IO) { ModManager.count(gameDirOf(entry)) } == 0) continue
                    setModUpdates(entry, pendingModUpdates(entry).size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.warn("mod update check for ${entry.label}: ${e.message}")
                }
            }
        }
    }

    /** Каталог только что пересканировал моды [entry] — значок должен показывать то же самое. */
    fun modsScanned(entry: VersionEntry, mods: List<InstalledItem>) = setModUpdates(entry, mods.count { it.canUpdate })

    private fun setModUpdates(entry: VersionEntry, count: Int) {
        if ((modUpdates[entry.key] ?: 0) != count) modUpdates = modUpdates + (entry.key to count)
    }

    fun updateMods(entry: VersionEntry) {
        if (busy) return
        if (isRunning(entry)) {
            fail(tr("Закрой игру — моды сейчас заняты", "Close the game — the mods are in use"), entry)
            return
        }
        startJob(entry, "mod updates") {
            stageChanged(tr("Ищу обновления модов", "Looking for mod updates"))
            val mods = pendingModUpdates(entry)
            if (mods.isEmpty()) {
                setModUpdates(entry, 0)
                inform(tr("Все моды уже последних версий", "All mods are up to date"), entry, level = NoticeLevel.INFO)
                return@startJob
            }
            backupWorldsBeforeChange(entry)
            stageChanged(tr("Обновляю моды: ${mods.size}", "Updating mods: ${mods.size}"))
            val report = try {
                ModManager.updateAll(mods) { progress = it }
            } finally {
                modsChanged(entry)
            }
            setModUpdates(entry, pendingModUpdates(entry).size)
            val done = tr("Обновлено модов: ${report.updated}", "Mods updated: ${report.updated}")
            if (report.skipped.isEmpty()) {
                inform(done, entry, tr("Моды обновлены", "Mods updated"))
            } else {
                fail((listOf(done) + report.skipped).joinToString("\n"), entry, tr("Обновилось не всё", "Not everything was updated"), listOf(NoticeAction.MODS))
            }
        }
    }

    fun applyGraphics(entry: VersionEntry, preset: GraphicsPreset) {
        if (isRunning(entry)) {
            fail(tr("Закрой игру — при выходе она перезапишет настройки", "Close the game — it overwrites the settings when it exits"), entry)
            return
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { GameOptions.applyPreset(gameDirOf(entry), entry.id, preset) } }
                .onSuccess { inform(preset.summary.replaceFirstChar { it.uppercase() }, entry, tr("Графика: ${preset.title.lowercase()}", "Graphics: ${preset.title.lowercase()}")) }
                .onFailure { fail(it.message, entry, tr("Настройки графики не записались", "Graphics settings were not saved")) }
        }
    }

    fun consumePendingPlay(): Boolean = pendingPlay.also { pendingPlay = false }

    fun play(server: ServerEntry? = null, skipModCheck: Boolean = false) {
        if (busy) return
        val entry = currentEntry() ?: run {
            fail(tr("Выбери версию", "Pick a version"))
            return
        }
        if (isStarting(entry)) return
        val account = selectedAccount.value ?: run {
            fail(tr("Добавь аккаунт", "Add an account"))
            screen = Screen.ACCOUNTS
            return
        }
        startJob(entry, "launch") { launch(entry, account, server, skipModCheck) }
    }

    private suspend fun launch(entry: VersionEntry, account: Account, server: ServerEntry?, skipModCheck: Boolean) {
        val gameDir = gameDirOf(entry)
        if (!offerCarryOver(entry, gameDir)) return
        if (!skipModCheck && entry.loader.hasMods) {
            val conflicts = withContext(Dispatchers.IO) {
                runCatching { ModCompat.conflictsIn(ModManager.modsDir(gameDir)) }.getOrDefault(emptyList())
            }
            if (conflicts.isNotEmpty()) {
                fail(
                    conflicts.take(3).joinToString("\n") { it.text },
                    entry,
                    tr("Игра не запущена: моды несовместимы", "Game not launched: incompatible mods"),
                    listOf(NoticeAction.FIX_MODS, NoticeAction.PLAY_ANYWAY),
                )
                return
            }
        }
        server?.let { addToServerList(gameDir, it) }
        // Второй клиент той же сборки пишет свой лог, иначе перетрёт лог первого
        val copies = games.count { it.entry.key == entry.key }
        val logName = if (copies == 0) GameLauncher.GAME_LOG else GameLauncher.GAME_LOG.replace(".log", "-${copies + 1}.log")
        val notices = ArrayList<String>()
        val result = GameLauncher.launch(
            versionId = entry.id,
            account = account,
            loader = entry.loader,
            serverAddress = server?.address,
            gameDir = gameDir,
            loaderVersion = entry.pack?.loaderVersion,
            logName = logName,
            onStage = ::stageChanged,
            onProgress = { progress = it },
            onNotice = { notices += it },
        )
        Settings.update { it.copy(lastVersionId = entry.id, lastLoader = entry.loader.name, lastPack = entry.pack?.id) }
        notices.forEach { inform(it, entry, level = NoticeLevel.INFO) }
        refreshInstalled()
        instanceChanged(entry)
        val startedAt = System.currentTimeMillis()
        val playing = Presence.Playing(
            versionId = entry.id,
            loaderLabel = entry.loader.takeIf { it.isModded }?.label,
            server = server?.address?.let(GameEvents::display),
            mods = if (entry.loader.hasMods) ModManager.count(gameDir) else 0,
            startedAt = startedAt,
            pack = entry.pack?.title,
        )
        DiscordPresence.show(playing)
        OnlineCounter.setPlaying(true)
        server?.let { ServerJoins.report(it.address) }
        val game = RunningGame(entry, result.process, result.logFile, startedAt, playing, account.name)
        games = games + game
        updateFriendPresence()
        sharePackWithFriends(game)
        watchGame(game, result.gameDir)
        watchGameWindow(game)
        onGameStarted(result.process)
    }

    private fun watchGameWindow(game: RunningGame) {
        scope.launch {
            if (GameWindow.awaitShown(game.process) && game in games) {
                Log.info("game window of ${game.entry.label} is open")
                game.opened = true
            }
        }
    }

    private suspend fun addToServerList(gameDir: Path, server: ServerEntry) = withContext(Dispatchers.IO) {
        runCatching { ServerList.add(gameDir, server) }
            .onSuccess { added -> if (added) Log.info("added ${server.address} to the multiplayer list of $gameDir") }
            .onFailure { Log.warn("could not add ${server.address} to $gameDir: ${it.message}") }
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
        stageChanged(tr("Ждёт выбора: что перенести", "Waiting for you to choose what to copy"))
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
                inform(tr("Переносить нечего", "Nothing to copy"), entry, level = NoticeLevel.INFO)
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
        stageChanged(tr("Переношу из ${source.label}", "Copying from ${source.label}"))
        val result = withContext(Dispatchers.IO) {
            CarryOver.apply(source, gameDir, plan, onProgress = { progress = it }, checkCancelled = { ensureActive() })
        }
        stageChanged("")
        instanceChanged(entry)
        carried(result)?.let { inform(tr("Из ${source.label}: $it", "From ${source.label}: $it"), entry, tr("Перенесено в ${entry.label}", "Copied to ${entry.label}")) }
        if (result.failed.isNotEmpty()) {
            fail(tr("Не скопировалось: ${result.failed.joinToString(", ")}", "Not copied: ${result.failed.joinToString(", ")}"), entry, tr("Перенесено не всё", "Not everything was copied"))
        }
    }

    private fun carried(result: CarryResult): String? {
        val parts = listOfNotNull(
            tr("настройки", "settings").takeIf { result.options },
            result.servers.takeIf { it > 0 }?.let { "$it ${plural(it, "сервер", "сервера", "серверов", "server", "servers")}" },
            result.packs.takeIf { it > 0 }?.let { "$it ${plural(it, "пак", "пака", "паков", "pack", "packs")}" },
            result.worlds.takeIf { it > 0 }?.let { "$it ${plural(it, "мир", "мира", "миров", "world", "worlds")}" },
        )
        return when (parts.size) {
            0 -> null
            1 -> parts.single()
            else -> parts.dropLast(1).joinToString(", ") + tr(" и ", " and ") + parts.last()
        }
    }

    fun createPack(title: String, gameVersion: String, loader: LoaderKind, copyFrom: VersionEntry? = null) {
        if (busy) return
        modal = null
        startJob(copyFrom, "pack create") {
            copyFrom?.let { stageChanged(tr("Копирую ${it.label}", "Copying ${it.label}")) }
            val pack = withContext(Dispatchers.IO) {
                Modpacks.create(title, gameVersion, loader, copyFrom?.pack?.loaderVersion, copyFrom?.let(::gameDirOf))
            }
            reloadPacks()
            val entry = entryFor(pack)
            selectEntry(entry)
            expandedGroups = expandedGroups + PACKS_GROUP
            inform(
                copyFrom?.let { tr("Копия ${it.label}", "Copy of ${it.label}") } ?: "${entry.id} ${loader.label}",
                entry,
                tr("Сборка «${pack.title}» создана", "Pack “${pack.title}” created"),
            )
        }
    }

    fun editPack(pack: Modpack, title: String) {
        modal = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Modpacks.rename(pack, title) } }
                .onSuccess { reloadPacks() }
                .onFailure { fail(it.message, entryFor(pack), tr("Сборка не переименована", "Pack not renamed")) }
        }
    }

    fun choosePackIcon(pack: Modpack) {
        val image = FilePicker.open(tr("Иконка сборки", "Pack icon"), FilePicker.IMAGES) ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Modpacks.setIcon(pack, image) } }
                .onSuccess { reloadPacks() }
                .onFailure { fail(it.message, entryFor(pack), tr("Иконка не поменялась", "Icon not changed")) }
        }
    }

    fun packIconUrl(pack: Modpack): String? = ModIcons.localUrl(Modpacks.iconOf(pack)) ?: pack.iconUrl

    private suspend fun reloadPacks() {
        packs = withContext(Dispatchers.IO) { Modpacks.list() }
        instanceRevision++
    }

    fun showWorlds(entry: VersionEntry) {
        modal = Modal.WorldList(entry)
    }

    fun backupWorld(entry: VersionEntry, world: WorldInfo) = worldJob(entry, tr("Бэкап сохранён", "Backup saved")) { dir ->
        stageChanged(tr("Сохраняю бэкап «${world.name}»", "Saving a backup of “${world.name}”"))
        Worlds.backup(dir, world.folder) { progress = it }
        tr("«${world.name}»", "“${world.name}”")
    }

    fun restoreWorld(entry: VersionEntry, backup: WorldBackup) = worldJob(entry, tr("Мир восстановлен", "World restored")) { dir ->
        stageChanged(tr("Восстанавливаю «${backup.world}»", "Restoring “${backup.world}”"))
        Worlds.restore(dir, backup) { progress = it }
        tr("«${backup.world}»", "“${backup.world}”")
    }

    fun importWorld(entry: VersionEntry) {
        val archive = FilePicker.open(tr("Архив с миром", "World archive"), listOf(".zip")) ?: return
        worldJob(entry, tr("Мир добавлен", "World added")) { dir ->
            stageChanged(tr("Распаковываю мир", "Unpacking world"))
            val folder = Worlds.import(dir, archive) { progress = it }
            tr("«$folder» появился в одиночной игре", "“$folder” is now in Singleplayer")
        }
    }

    fun deleteWorld(entry: VersionEntry, world: WorldInfo) = worldJob(entry, tr("Мир удалён", "World deleted")) { dir ->
        Worlds.delete(dir, world.folder)
        if (Storage.trashAvailable) tr("«${world.name}» в корзине", "“${world.name}” moved to trash") else tr("«${world.name}»", "“${world.name}”")
    }

    fun deleteBackup(entry: VersionEntry, backup: WorldBackup) = worldJob(entry, tr("Бэкап удалён", "Backup deleted")) {
        Files.deleteIfExists(backup.file)
        backup.file.fileName.toString()
    }

    private fun worldJob(entry: VersionEntry, title: String, block: suspend (Path) -> String) {
        if (isRunning(entry)) {
            fail(tr("Закрой игру — миры заняты", "Close the game — worlds are in use"), entry)
            return
        }
        startJob(entry, "worlds") {
            val message = withContext(Dispatchers.IO) { block(gameDirOf(entry)) }
            instanceChanged(entry)
            inform(message, entry, title)
        }
    }

    private suspend fun backupWorldsBeforeChange(entry: VersionEntry) {
        stageChanged(tr("Сохраняю бэкап миров", "Backing up worlds"))
        val made = withContext(Dispatchers.IO) { Worlds.autoBackup(gameDirOf(entry)) { progress = it } }
        if (made > 0) Log.info("auto-backed up $made world(s) of ${entry.label}")
    }

    fun updatePack(pack: Modpack) {
        packUpdates[pack.id]?.let { installPack(it) }
    }

    fun installPack(source: PackSource) =
        installPack(source.projectId, packs.firstOrNull { it.id == source.id }?.let(::entryFor)) { source }

    private fun installPack(projectId: String, entry: VersionEntry?, source: suspend () -> PackSource) {
        if (busy) return
        installingPack = projectId
        startJob(entry, "pack") {
            try {
                entry?.let { backupWorldsBeforeChange(it) }
                val pack = Modpacks.install(source(), onStage = ::stageChanged, onProgress = { progress = it })
                reloadPacks()
                packUpdates = packUpdates - pack.id
                val entry = entryFor(pack)
                selectEntry(entry)
                instanceChanged(entry)
                inform(tr("Сборка ${pack.title} ${pack.version} установлена", "Pack ${pack.title} ${pack.version} installed"), entry)
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
                .onFailure { if (it !is CancellationException) fail(it.message, entryFor(pack), tr("Сборка не переустановилась", "Pack not reinstalled")) }
        }
    }

    private fun checkPackUpdates() {
        scope.launch {
            val found = packs.filterNot { it.custom }.mapNotNull { pack ->
                runCatching { Modpacks.update(pack) }
                    .onFailure { if (it !is CancellationException) Log.warn("pack update check for ${pack.id}: ${it.message}") }
                    .getOrNull()
                    ?.let { pack.id to it }
            }.toMap()
            packUpdates = found
        }
    }

    private fun watchGame(game: RunningGame, gameDir: Path) {
        val process = game.process
        scope.launch(Dispatchers.IO) {
            val tail = GameEvents.Tail(game.logFile)
            val serverList = ServerList.Guard(gameDir)
            var shown = game.presence
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
                    val moved = next.server != shown.server
                    shown = next
                    game.presence = next
                    DiscordPresence.show(next)
                    if (moved) updateFriendPresence()
                }
            }
        }
    }

    private suspend fun serverIcon(address: String): String? =
        ServerPing.ping(address)?.favicon?.let { DiscordPresence.serverIcon(address) }

    fun playFromShortcut(request: PlayRequest) {
        if (busy) {
            fail(tr("Дождись загрузки ${busyEntry?.label.orEmpty()}".trimEnd(), "Wait for ${busyEntry?.label ?: "loading"} to finish"))
            return
        }
        val entry = requested(request) ?: return
        selectEntry(entry)
        play()
    }

    fun playOnServer(server: ServerEntry) {
        if (busy) return
        if (selectedVersionId == null) server.entryKey?.let(::entryByKey)?.let(::selectEntry)
        play(server)
    }

    fun openLink(link: LaunchLink) {
        if (carryAnswer != null) {
            inform(tr("Сначала выбери, что перенести", "Choose what to copy first"), level = NoticeLevel.INFO)
            return
        }
        screen = Screen.HOME
        when (link) {
            LaunchLink.Open -> Unit
            is LaunchLink.Join -> modal = joinModal(link)
            is LaunchLink.Install -> showInstall(link)
            is LaunchLink.SharedPack -> showSharedPack(link)
        }
    }

    private fun showSharedPack(link: LaunchLink.SharedPack) {
        val shown = Modal.ImportPack(link)
        modal = shown
        scope.launch {
            val resolved = try {
                shown.copy(plan = PackLinks.resolve(link))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.warn("shared pack unresolved: ${e.message}")
                shown.copy(error = tr("Modrinth не ответил — проверь интернет", "Modrinth didn't respond — check your connection"))
            }
            if (modal === shown) modal = resolved
        }
    }

    fun importSharedPack(link: LaunchLink.SharedPack, plan: PackImport) {
        if (busy) return
        modal = null
        startJob(null, "pack import") {
            stageChanged(tr("Создаю сборку ${link.name}", "Creating the pack ${link.name}"))
            val pack = PackLinks.install(link, plan, onStage = ::stageChanged, onProgress = { progress = it })
            reloadPacks()
            val entry = entryFor(pack)
            selectEntry(entry)
            expandedGroups = expandedGroups + PACKS_GROUP
            inform(PackLinks.summary(link), entry, tr("Сборка «${pack.title}» готова", "Pack “${pack.title}” is ready"))
        }
    }

    suspend fun packShare(entry: VersionEntry): PackShare =
        PackLinks.share(gameDirOf(entry), entry.id, entry.loader, entry.pack, entry.pack?.title ?: entry.label)

    private fun showInstall(link: LaunchLink.Install) {
        val shown = Modal.Install(link)
        modal = shown
        if (link.content == LinkContent.DATAPACK) return
        scope.launch {
            val plan = try {
                installPlan(link)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.warn("link to ${link.project} unresolved: ${e.message}")
                InstallPlan.Failed(tr("Modrinth не ответил — проверь интернет", "Modrinth didn't respond — check your connection"))
            }
            if (modal === shown) modal = shown.copy(plan = plan)
        }
    }

    fun joinServer(link: LaunchLink.Join, entry: VersionEntry) {
        if (busy) return
        modal = null
        selectEntry(entry)
        play(ServerEntry(link.title, link.address))
    }

    /** «Войти через juxmc.ru»: сайт выдаёт код, браузер открывает страницу подтверждения, лаунчер ждёт ответа. */
    fun signInFriends() {
        if (linkJob?.isActive == true) return
        linkJob = scope.launch {
            friendLinkStarting = true
            val start = try {
                Friends.startLink()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.warn("friends link start failed: ${e.message}")
                fail(e.message, title = tr("Вход через juxmc.ru", "Sign in with juxmc.ru"))
                return@launch
            } finally {
                friendLinkStarting = false
            }
            val shown = Modal.FriendLink(start)
            modal = shown
            Shell.browse(start.url)
            val deadline = System.currentTimeMillis() + start.expiresIn * 1000L
            while (true) {
                delay(start.interval.coerceIn(2, 10) * 1000L)
                val answer = try {
                    Friends.poll(start)
                } catch (e: FriendsException) {
                    when (e.code) {
                        "expired" -> LinkPoll.Expired
                        "denied" -> LinkPoll.Denied
                        else -> LinkPoll.Pending
                    }
                }
                val failure = when (answer) {
                    LinkPoll.Pending -> if (System.currentTimeMillis() < deadline) continue else FriendsException.textOf("expired")
                    LinkPoll.Denied -> FriendsException.textOf("denied")
                    LinkPoll.Expired -> FriendsException.textOf("expired")
                    is LinkPoll.Linked -> {
                        Friends.adopt(answer)
                        if (modal is Modal.FriendLink) modal = null
                        return@launch
                    }
                }
                if (modal is Modal.FriendLink) modal = shown.copy(failure = failure)
                return@launch
            }
        }
    }

    fun cancelFriendLink() {
        linkJob?.cancel()
        linkJob = null
        if (modal is Modal.FriendLink) modal = null
    }

    fun signOutFriends() {
        scope.launch { Friends.unlink() }
    }

    /** Действие с другом или заявкой. Ошибку получает [onError], без него она идёт в уведомления. */
    fun friendAction(action: FriendAction, nick: String, onError: ((String) -> Unit)? = null, onDone: () -> Unit = {}) {
        scope.launch {
            try {
                Friends.act(action, nick)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = e.message ?: FriendsException.textOf("")
                if (onError != null) onError(text) else fail(text, title = tr("Друзья", "Friends"))
            }
        }
    }

    fun changeFriendSettings(showWhere: Boolean? = null, showOnline: Boolean? = null, acceptRequests: Boolean? = null) {
        scope.launch {
            try {
                Friends.changeSettings(showWhere, showOnline, acceptRequests)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message, title = tr("Настройки друзей не сохранились", "Friend settings were not saved"))
            }
        }
    }

    /** «Присоединиться»: своя сборка друга — она же или окно «Скачать и играть», иначе запомненная версия или версия друга. */
    fun joinFriend(friend: Friend) {
        if (busy || !friend.joinable) return
        val link = friend.sharedPack
        if (link == null) {
            joinFriendWith(friend, friendJoinEntry(friend), remember = false)
            return
        }
        val owned = ownedPackOf(link)
        if (owned != null) joinFriendWith(friend, entryFor(owned), remember = false) else showFriendPack(friend, link)
    }

    /** Заход с выбранной версией; выбор из меню или окна запоминается для этого сервера. */
    fun joinFriendWith(friend: Friend, entry: VersionEntry, remember: Boolean = true) {
        val address = friend.address ?: return
        if (busy) return
        if (remember) Settings.update { it.copy(serverVersions = it.serverVersions + (address to entry.key)) }
        modal = null
        selectEntry(entry)
        joiningFriend = friend.nick
        play(ServerEntry(friend.serverTitle ?: address, address))
        if (!busy) joiningFriend = null
    }

    fun showFriendVersions(friend: Friend) {
        modal = Modal.FriendVersions(friend)
    }

    fun showFriendPack(friend: Friend, link: LaunchLink.SharedPack) {
        val shown = Modal.FriendPack(friend, link)
        modal = shown
        scope.launch {
            val resolved = try {
                shown.copy(plan = PackLinks.resolve(link))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.warn("friend's pack unresolved: ${e.message}")
                shown.copy(error = tr("Modrinth не ответил — проверь интернет", "Modrinth didn't respond — check your connection"))
            }
            if (modal === shown) modal = resolved
        }
    }

    /** «Скачать и играть»: сборка друга ставится по его ссылке, и игра сразу заходит к нему на сервер. */
    fun joinFriendPack(friend: Friend, link: LaunchLink.SharedPack, plan: PackImport) {
        val address = friend.address ?: return
        if (busy) return
        val account = selectedAccount.value ?: run {
            fail(tr("Добавь аккаунт", "Add an account"))
            screen = Screen.ACCOUNTS
            return
        }
        modal = null
        joiningFriend = friend.nick
        startJob(null, "pack import") {
            stageChanged(tr("Создаю сборку ${link.name}", "Creating the pack ${link.name}"))
            val pack = PackLinks.install(link, plan, onStage = ::stageChanged, onProgress = { progress = it })
            reloadPacks()
            val entry = entryFor(pack)
            selectEntry(entry)
            expandedGroups = expandedGroups + PACKS_GROUP
            busyEntry = entry
            launch(entry, account, ServerEntry(friend.serverTitle ?: address, address), skipModCheck = false)
        }
    }

    private fun updateFriendPresence() = Friends.setPresence(friendPresenceOf(games))

    private fun sharePackWithFriends(game: RunningGame) {
        if (!game.entry.loader.hasMods || Friends.account.value == null) return
        scope.launch {
            try {
                game.packLink = packShare(game.entry).uri
                if (game in games) updateFriendPresence()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.info("pack link for friends not built: ${e.message}")
            }
        }
    }

    /** Иконки и версии серверов, где играют друзья: тот же пинг, что у серверов на главной. */
    fun pingFriendServers() {
        val now = System.currentTimeMillis()
        Friends.snapshot.value?.friends.orEmpty()
            .filter { it.joinable }
            .mapNotNull { it.address }
            .distinct()
            .filter { now - (lastPingAt[it] ?: 0L) >= SERVER_PING_MILLIS }
            .forEach { pingServer(ServerEntry(it, it)) }
    }

    fun installFromLink(entry: VersionEntry, project: Modrinth.Project, kind: ContentKind) {
        if (busy) return
        modal = null
        selectEntry(entry)
        startJob(entry, "content") {
            stageChanged(tr("Ставлю ${project.title}", "Installing ${project.title}"))
            val present = listOf(ContentKind.MOD, kind).distinct()
                .flatMap { ModManager.scan(gameDirOf(entry), entry.loader, entry.id, it) }
                .mapNotNull { it.projectId }
                .toSet()
            if (project.id in present) {
                inform(tr("${project.title} уже стоит в ${entry.label}", "${project.title} is already in ${entry.label}"), entry, level = NoticeLevel.INFO)
                return@startJob
            }
            val message = installInto(entry, kind, project.id, project.title, present) { progress = it }
            instanceChanged(entry)
            inform(message, entry, tr("Установлено в ${entry.label}", "Installed to ${entry.label}"))
        }
    }

    fun installPackFromLink(project: Modrinth.Project) {
        modal = null
        installPack(project.id, null) {
            Modpacks.source(project.id, project.slug.ifBlank { project.id }, project.title, project.iconUrl)
        }
    }

    suspend fun installInto(
        entry: VersionEntry,
        kind: ContentKind,
        projectId: String,
        title: String,
        present: Set<String>,
        onProgress: (DownloadProgress) -> Unit,
    ): String {
        val dir = gameDirOf(entry)
        return when (kind) {
            ContentKind.SHADER -> {
                val titles = ModManager.installShader(dir, entry.loader, entry.id, projectId, title, present, onProgress)
                val boosted = entry.loader == LoaderKind.VANILLA && enableBoost(entry)
                listOfNotNull(
                    tr("$title установлен и включён", "$title installed and enabled"),
                    tr("вместе с ${titles.dropLast(1).joinToString()}", "along with ${titles.dropLast(1).joinToString()}").takeIf { titles.size > 1 },
                    tr("FPS-буст включён", "FPS boost enabled").takeIf { boosted },
                ).joinToString(", ")
            }
            ContentKind.RESOURCE_PACK -> {
                ModManager.installResourcePack(dir, entry.id, projectId, title, onProgress)
                tr("$title установлен и включён", "$title installed and enabled")
            }
            ContentKind.MOD -> {
                val titles = ModManager.install(dir, entry.loader, entry.id, projectId, title, present, onProgress)
                if (titles.size <= 1) tr("$title установлен", "$title installed") else tr("Установлено: ${titles.joinToString()}", "Installed: ${titles.joinToString()}")
            }
        }
    }

    fun reinstall(entry: VersionEntry) {
        if (busy) return
        entry.pack?.takeIf { !it.custom }?.let {
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
            inform((listOf(tr("${entry.label} переустановлена", "${entry.label} reinstalled")) + notices).joinToString("\n"), entry)
        }
    }

    fun delete(entry: VersionEntry, withGameDir: Boolean) {
        if (busy && busyEntry == entry) {
            fail(tr("Дождись загрузки ${entry.label}", "Wait for ${entry.label} to finish"), entry)
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
            if (withGameDir) modUpdates = modUpdates - entry.key
            inform(
                when {
                    !withGameDir -> tr("Файлы ${entry.label} удалены, миры на месте", "${entry.label} files deleted, worlds kept")
                    trashed -> tr("${entry.label} удалена, папка в корзине", "${entry.label} deleted, folder moved to trash")
                    else -> tr("${entry.label} удалена вместе с папкой сборки", "${entry.label} deleted along with its folder")
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
            reloadPacks()
            packUpdates = packUpdates - pack.id
            modUpdates = modUpdates - (PACK_KEY + pack.id)
            inform(if (trashed) tr("Сборка ${pack.title} в корзине", "Pack ${pack.title} moved to trash") else tr("Сборка ${pack.title} удалена", "Pack ${pack.title} deleted"))
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

    /** Возвращает новую ревизию, чтобы каталог не сканировал папку второй раз из-за своего же изменения. */
    fun modsChanged(entry: VersionEntry): Int {
        instanceChanged(entry)
        return instanceRevision
    }

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
                .onSuccess { inform(tr("Ярлык «${it.fileName.toString().removeSuffix(".lnk")}» на рабочем столе", "Desktop shortcut “${it.fileName.toString().removeSuffix(".lnk")}” created"), entry) }
                .onFailure { fail(it.message, entry) }
        }
    }

    fun trackMenu(open: Boolean) {
        openMenus = (openMenus + if (open) 1 else -1).coerceAtLeast(0)
    }

    private val lastPingAt = HashMap<String, Long>()

    /** [force] — кнопка обновления; без него сервер, пингованный меньше минуты назад, не трогаем (возврат на главную). */
    fun refreshServers(force: Boolean = true) {
        val now = System.currentTimeMillis()
        servers.filter { force || now - (lastPingAt[it.address] ?: 0L) >= SERVER_PING_MILLIS }.forEach(::pingServer)
    }

    private fun pingServer(server: ServerEntry) {
        lastPingAt[server.address] = System.currentTimeMillis()
        if (serverStatus[server.address] !is PingState.Online) setPing(server.address, PingState.Pinging)
        scope.launch {
            val status = ServerPing.ping(server.address)
            val previous = (serverStatus[server.address] as? PingState.Online)?.status
            // Та же иконка сервера — тот же массив: картинка не декодируется заново каждую минуту
            val stable = if (status != null && previous?.favicon != null && previous.favicon.contentEquals(status.favicon)) {
                status.withFavicon(previous.favicon)
            } else {
                status
            }
            setPing(server.address, stable?.let { PingState.Online(it) } ?: PingState.Offline)
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
                .onFailure { if (it !is CancellationException) fail(it.message, title = tr("Обновление не установилось", "Update failed to install")) }
        }
    }

    fun signInMicrosoft() {
        if (signingIn) return
        signingIn = true
        scope.launch {
            runCatching { AccountManager.signInMicrosoft { signInStage = it } }
                .onSuccess { inform(tr("Вход выполнен: ${it.name}", "Signed in: ${it.name}")) }
                .onFailure { failure ->
                    if (failure !is CancellationException) {
                        Log.error("microsoft sign-in failed", failure)
                        fail(failure.message ?: tr("Вход не удался", "Sign-in failed"), title = tr("Вход через Microsoft", "Microsoft sign-in"))
                    }
                }
            signingIn = false
            signInStage = ""
        }
    }

    fun chooseBackdropImage() {
        val file = FilePicker.open(tr("Картинка для фона", "Background image"), FilePicker.IMAGES) ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Backdrops.importImage(file) } }
                .onSuccess { Settings.update { it.copy(backdrop = Backdrop.IMAGE) } }
                .onFailure { fail(it.message, title = tr("Фон не поменялся", "Background not changed")) }
        }
    }

    fun addOffline(nickname: String): String? =
        runCatching { AccountManager.addOffline(nickname) }.fold(
            onSuccess = {
                inform(tr("Добавлен офлайн-аккаунт ${it.name}", "Added offline account ${it.name}"))
                null
            },
            onFailure = { it.message ?: tr("Не получилось добавить аккаунт", "Couldn't add account") },
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
        const val SERVER_PING_MILLIS = 60_000L
        // Проверка модов ждёт, пока лаунчер догрузится и перестанет занимать сеть
        const val MOD_CHECK_DELAY_MILLIS = 5_000L
        val MOD_ALIASES = mapOf("fabric" to "fabric-api")
        fun failTitle(what: String): String? = when (what) {
            "launch" -> tr("Игра не запустилась", "Game failed to launch")
            "reinstall" -> tr("Переустановка не удалась", "Reinstall failed")
            "fix mods" -> tr("Моды не исправлены", "Mods not fixed")
            "pack" -> tr("Сборка не установилась", "Pack failed to install")
            "carry" -> tr("Перенос не удался", "Copy failed")
            "content" -> tr("Не установилось", "Install failed")
            "worlds" -> tr("С миром не получилось", "World action failed")
            "pack create" -> tr("Сборка не создалась", "Pack not created")
            "pack import" -> tr("Сборка по ссылке не создалась", "Pack from the link was not created")
            "mod updates" -> tr("Моды не обновились", "Mods were not updated")
            else -> null
        }
        val NO_VERSIONS: String get() = tr("Нет списка версий — проверь интернет и перезапусти лаунчер", "No version list — check your connection and restart the launcher")
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
    WEEKLY_SNAPSHOT.find(version.id)?.let { return tr("Снапшоты ${it.groupValues[1]}w", "Snapshots ${it.groupValues[1]}w") }
    RELEASE_LINE.find(version.id)?.let { return "${it.groupValues[1]}.${it.groupValues[2]}" }
    return tr("Прочее", "Other")
}
