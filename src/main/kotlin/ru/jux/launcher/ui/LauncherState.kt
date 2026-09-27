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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.jux.launcher.auth.AccountManager
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.PlayRequest
import ru.jux.launcher.core.PreloadResult
import ru.jux.launcher.core.Preloader
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.Shortcuts
import ru.jux.launcher.core.Storage
import ru.jux.launcher.core.VerifyCache
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
import ru.jux.launcher.mods.PerformancePack
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.servers.ServerEntry
import ru.jux.launcher.servers.ServerPing
import ru.jux.launcher.servers.ServerStatus
import ru.jux.launcher.servers.Servers
import ru.jux.launcher.update.UpdateManifest
import ru.jux.launcher.update.Updater
import java.io.IOException
import java.nio.file.Path

enum class Screen { HOME, SETTINGS, ACCOUNTS }

data class VersionEntry(
    val version: ManifestVersion,
    val loader: LoaderKind,
) {
    val id: String get() = version.id
    val key: String get() = "${version.id}#${loader.name}"
    val label: String get() = if (loader.isModded) "$id ${loader.label}" else id
}

data class VersionGroup(
    val key: String,
    val entries: List<VersionEntry>,
)

sealed interface Modal {
    data class InstanceSettings(val entry: VersionEntry) : Modal
    data class Mods(val entry: VersionEntry) : Modal
    data class Delete(val entry: VersionEntry) : Modal
    data class Logs(val gameDir: Path?, val title: String, val source: LogSource) : Modal
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

    var installed by mutableStateOf<Map<String, Long>>(emptyMap())

    var profiles by mutableStateOf<Set<String>>(emptySet())
    var expandedGroups by mutableStateOf<Set<String>>(emptySet())

    var selectedLoader by mutableStateOf(LoaderKind.VANILLA)

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

    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var crashed by mutableStateOf<VersionEntry?>(null)

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
            val version = versions.firstOrNull { it.id == request.versionId }
            if (version != null) {
                selectEntry(VersionEntry(version, request.loader))
                pendingPlay = true
            } else {
                error = "Версии ${request.versionId} из ярлыка нет в списке Mojang"
            }
        }
    }

    private fun adopt(preloaded: PreloadResult) {
        versions = preloaded.manifest.versions
        installed = preloaded.installed
        profiles = preloaded.profiles
        loaderSupport = preloaded.loaderSupport
        selectStartingEntry(preloaded.manifest.latest.release)
        expandedGroups = setOfNotNull(groups().firstOrNull()?.key)
        if (versions.isEmpty()) error = NO_VERSIONS
    }

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
            expandedGroups = setOfNotNull(groups().firstOrNull()?.key)
            if (error == NO_VERSIONS) error = null
        }
        return true
    }

    private fun selectStartingEntry(latestRelease: String) {
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
                when (version.kind) {
                    VersionKind.RELEASE -> true
                    VersionKind.SNAPSHOT -> settings.showSnapshots
                    VersionKind.OLD_BETA, VersionKind.OLD_ALPHA, VersionKind.OTHER -> settings.showOldVersions
                }
            }
            .filter { query.isEmpty() || it.id.lowercase().contains(query) }
            .toList()
    }

    fun groups(): List<VersionGroup> =
        visibleVersions()
            .groupBy { groupKeyOf(it) }
            .map { (key, items) ->
                VersionGroup(
                    key = key,
                    entries = items.flatMap { version ->
                        loaderSupport.loadersFor(version.id).map { VersionEntry(version, it) }
                    },
                )
            }

    fun isExpanded(group: VersionGroup): Boolean = searchQuery.isNotBlank() || group.key in expandedGroups

    fun toggleGroup(key: String) {
        expandedGroups = if (key in expandedGroups) expandedGroups - key else expandedGroups + key
    }

    fun selectEntry(entry: VersionEntry) {
        selectedVersionId = entry.version.id
        selectedLoader = entry.loader
        refreshSelectedOptions()
    }

    fun isSelected(entry: VersionEntry): Boolean =
        entry.version.id == selectedVersionId && entry.loader == selectedLoader

    fun currentEntry(): VersionEntry? {
        val id = selectedVersionId ?: return null
        return VersionEntry(versionById(id), selectedLoader)
    }

    fun entryFor(versionId: String, loader: LoaderKind): VersionEntry = VersionEntry(versionById(versionId), loader)

    fun entryByKey(key: String): VersionEntry? {
        val id = key.substringBeforeLast('#')
        val loader = LoaderKind.entries.firstOrNull { it.name == key.substringAfterLast('#') } ?: return null
        return entryFor(id, loader)
    }

    private fun versionById(id: String): ManifestVersion =
        versions.firstOrNull { it.id == id } ?: ManifestVersion(id = id, url = "")

    fun gameDirOf(entry: VersionEntry): Path = Settings.gameDir(entry.id, entry.loader)

    private fun refreshSelectedOptions() {
        val id = selectedVersionId ?: return
        selectedOptions = InstanceStore.get(Settings.gameDir(id, selectedLoader))
    }

    private fun instanceChanged(entry: VersionEntry) {
        instanceRevision++
        if (isSelected(entry)) refreshSelectedOptions()
    }

    private fun startJob(entry: VersionEntry?, what: String, block: suspend () -> Unit) {
        if (busy) return
        val token = Any()
        jobToken = token
        error = null
        notice = null
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
                    error = e.message ?: e::class.simpleName ?: "Неизвестная ошибка"
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
        error = CrashHints.explain(logFile, entry.loader, entry.id, exitCode)
        crashed = entry
    }

    fun consumePendingPlay(): Boolean = pendingPlay.also { pendingPlay = false }

    fun play(serverAddress: String? = null) {
        if (busy) return
        val versionId = selectedVersionId ?: run {
            error = "Выберите версию"
            return
        }
        val account = selectedAccount.value ?: run {
            error = "Добавьте аккаунт"
            screen = Screen.ACCOUNTS
            return
        }
        val loader = selectedLoader
        val entry = currentEntry()

        startJob(entry, "launch") {
            val notices = ArrayList<String>()
            val result = GameLauncher.launch(
                versionId = versionId,
                account = account,
                loader = loader,
                serverAddress = serverAddress,
                onStage = ::stageChanged,
                onProgress = { progress = it },
                onNotice = { notices += it },
            )
            Settings.update { it.copy(lastVersionId = versionId, lastLoader = loader.name) }
            if (notices.isNotEmpty()) notice = notices.joinToString("\n")
            refreshInstalled()
            entry?.let(::instanceChanged)
            lastLaunch = entry?.let { it to result.logFile }
            onGameStarted(result.process)
        }
    }

    fun playFromShortcut(request: PlayRequest, gameRunning: Boolean) {
        val version = versions.firstOrNull { it.id == request.versionId } ?: run {
            error = "Версии ${request.versionId} из ярлыка нет в списке Mojang"
            return
        }
        if (busy) {
            error = "Сначала дождитесь окончания загрузки ${busyEntry?.label.orEmpty()}".trimEnd()
            return
        }
        val entry = VersionEntry(version, request.loader)
        selectEntry(entry)
        if (gameRunning) notice = "Игра уже запущена. ${entry.label} выбрана — запустите её, когда закончите" else play()
    }

    fun playOnServer(server: ServerEntry) {
        if (busy) return
        server.entryKey?.let(::entryByKey)?.let(::selectEntry)
        play(serverAddress = server.address)
    }

    fun reinstall(entry: VersionEntry) {
        if (busy) return
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
                onStage = ::stageChanged,
                onProgress = { progress = it },
                onNotice = { notices += it },
            )
            refreshInstalled()
            instanceChanged(entry)
            notice = (listOf("${entry.label} переустановлена") + notices).joinToString("\n")
        }
    }

    fun delete(entry: VersionEntry, withGameDir: Boolean) {
        if (busy && busyEntry == entry) {
            error = "Сначала дождитесь окончания загрузки ${entry.label}"
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
                error = e.message
                return@launch
            }
            refreshInstalled()
            instanceChanged(entry)
            notice = when {
                !withGameDir -> "Файлы ${entry.label} удалены, миры и настройки на месте"
                trashed -> "${entry.label} удалена, папка сборки в корзине"
                else -> "${entry.label} удалена вместе с папкой сборки"
            }
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

    fun modsChanged(entry: VersionEntry) = instanceChanged(entry)

    fun setInstanceMemory(entry: VersionEntry, memoryMb: Int?) {
        InstanceStore.update(gameDirOf(entry)) { it.copy(memoryMb = memoryMb) }
        instanceChanged(entry)
    }

    fun openFolder(dir: Path) = Shell.openFolder(dir) { error = it }

    fun showLogs(entry: VersionEntry?, source: LogSource = LogSource.GAME) {
        modal = Modal.Logs(entry?.let(::gameDirOf), entry?.label ?: "JuxLauncher", source)
    }

    fun createShortcut(entry: VersionEntry) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { Shortcuts.createOnDesktop("Minecraft ${entry.label}", entry.id, entry.loader) }
            }
                .onSuccess { notice = "Ярлык «${it.fileName.toString().removeSuffix(".lnk")}» на рабочем столе" }
                .onFailure { error = it.message }
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
                .onFailure { if (it !is CancellationException) error = it.message }
        }
    }

    fun signInMicrosoft() {
        if (signingIn) return
        signingIn = true
        error = null
        scope.launch {
            runCatching { AccountManager.signInMicrosoft { signInStage = it } }
                .onSuccess { notice = "Вход выполнен: ${it.name}" }
                .onFailure { failure ->
                    if (failure !is CancellationException) {
                        Log.error("microsoft sign-in failed", failure)
                        error = failure.message ?: "Вход не удался"
                    }
                }
            signingIn = false
            signInStage = ""
        }
    }

    fun addOffline(nickname: String): String? =
        runCatching { AccountManager.addOffline(nickname) }.fold(
            onSuccess = {
                notice = "Добавлен офлайн-аккаунт ${it.name}"
                error = null
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
