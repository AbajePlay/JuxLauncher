package ru.jux.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.tr
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.mods.ContentKind
import ru.jux.launcher.mods.IncompatibleModException
import ru.jux.launcher.mods.InstalledItem
import ru.jux.launcher.mods.ModManager
import ru.jux.launcher.mods.Modrinth
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.packs.Modpacks
import ru.jux.launcher.ui.CatalogSearch
import ru.jux.launcher.ui.CatalogTab
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.VersionEntry
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.ChoiceChip
import ru.jux.launcher.ui.components.ContentRow
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDropdownMenu
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.JuxMenuItem
import ru.jux.launcher.ui.components.JuxSwitch
import ru.jux.launcher.ui.components.ListBox
import ru.jux.launcher.ui.components.ListHint
import ru.jux.launcher.ui.components.Panel
import ru.jux.launcher.ui.components.ReportOpen
import ru.jux.launcher.ui.components.SearchField
import ru.jux.launcher.ui.components.SearchResults
import ru.jux.launcher.ui.components.StatusStrip
import ru.jux.launcher.ui.components.Tag
import ru.jux.launcher.ui.components.WithTooltip
import ru.jux.launcher.ui.components.formatCount
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens

private val CatalogTab.kind: ContentKind?
    get() = when (this) {
        CatalogTab.MODS -> ContentKind.MOD
        CatalogTab.SHADERS -> ContentKind.SHADER
        CatalogTab.RESOURCE_PACKS -> ContentKind.RESOURCE_PACK
        CatalogTab.PACKS, CatalogTab.INSTALLED -> null
    }

private val CatalogTab.title: String
    get() = when (this) {
        CatalogTab.PACKS -> tr("Сборки", "Packs")
        CatalogTab.MODS -> tr("Моды", "Mods")
        CatalogTab.SHADERS -> tr("Шейдеры", "Shaders")
        CatalogTab.RESOURCE_PACKS -> tr("Ресурспаки", "Resource packs")
        CatalogTab.INSTALLED -> tr("Установленные", "Installed")
    }

private val ContentKind.title: String
    get() = when (this) {
        ContentKind.MOD -> tr("Моды", "Mods")
        ContentKind.SHADER -> tr("Шейдеры", "Shaders")
        ContentKind.RESOURCE_PACK -> tr("Ресурспаки", "Resource packs")
    }

private val ContentKind.searchHint: String
    get() = when (this) {
        ContentKind.MOD -> tr("Найти мод", "Search mods")
        ContentKind.SHADER -> tr("Найти шейдер", "Search shaders")
        ContentKind.RESOURCE_PACK -> tr("Найти ресурспак", "Search resource packs")
    }

private class PacksModel(private val scope: CoroutineScope) {
    val search = CatalogSearch(scope) { query, offset -> Modrinth.search("modpack", query, emptyList(), null, offset) }
    var resolving by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    fun install(state: LauncherState, hit: Modrinth.SearchHit) {
        if (state.busy || resolving != null) return
        resolving = hit.projectId
        error = null
        scope.launch {
            try {
                state.installPack(Modpacks.source(hit.projectId, hit.slug.ifBlank { hit.projectId }, hit.title, hit.iconUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: tr("Не получилось", "Something went wrong")
            } finally {
                resolving = null
            }
        }
    }
}

private class ContentModel(
    private val dir: Path,
    private val loader: LoaderKind,
    private val gameVersion: String,
    private val scope: CoroutineScope,
    private val installer: suspend (ContentKind, Modrinth.SearchHit, Set<String>, (DownloadProgress) -> Unit) -> String,
    private val onChanged: () -> Unit,
) {
    val searches = ContentKind.entries.associateWith { kind ->
        CatalogSearch(scope) { query, offset ->
            Modrinth.search(kind.projectType, query, ModManager.catalogLoaders(kind, loader), gameVersion, offset)
        }
    }
    var installed by mutableStateOf<Map<ContentKind, List<InstalledItem>>>(emptyMap())
    var scanned by mutableStateOf(false)
    var working by mutableStateOf<Set<String>>(emptySet())
    var progress by mutableStateOf<DownloadProgress?>(null)
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    val installedProjects: Set<String> get() = installed.values.flatten().mapNotNull { it.projectId }.toSet()
    val updates: List<InstalledItem> get() = installed[ContentKind.MOD].orEmpty().filter { it.update != null }

    fun rescan() {
        scope.launch {
            installed = scanAll()
            scanned = true
        }
    }

    private suspend fun scanAll(): Map<ContentKind, List<InstalledItem>> =
        ContentKind.entries.associateWith { kind ->
            ModManager.scan(dir, loader, gameVersion, kind, withUpdates = kind == ContentKind.MOD && loader.hasMods)
        }

    fun install(kind: ContentKind, hit: Modrinth.SearchHit) = work(hit.projectId) {
        message = installer(kind, hit, installedProjects) { progress = it }
    }

    fun update(mod: InstalledItem) = work(mod.fileName) {
        ModManager.update(mod) { progress = it }
        message = tr("${mod.title} обновлён до ${mod.update?.versionNumber.orEmpty()}", "${mod.title} updated to ${mod.update?.versionNumber.orEmpty()}").trimEnd()
    }

    fun updateAll() = work(ALL) {
        var pending = updates
        var done = 0
        var skipped = emptyList<String>()
        do {
            val before = done
            val failed = ArrayList<InstalledItem>()
            val reasons = ArrayList<String>()
            for (mod in pending) {
                try {
                    ModManager.update(mod) { progress = it }
                    done++
                } catch (e: IncompatibleModException) {
                    failed += mod
                    reasons += e.message.orEmpty()
                }
            }
            pending = failed
            skipped = reasons
        } while (pending.isNotEmpty() && done > before)
        if (skipped.isEmpty()) {
            message = tr("Обновлено модов: $done", "Mods updated: $done")
        } else {
            error = (listOf(tr("Обновлено модов: $done", "Mods updated: $done")) + skipped).joinToString(". ")
        }
    }

    fun toggle(item: InstalledItem, enabled: Boolean) = work(item.fileName) {
        withContext(Dispatchers.IO) { ModManager.setEnabled(item, enabled) }
    }

    fun remove(item: InstalledItem) = work(item.fileName) {
        withContext(Dispatchers.IO) { ModManager.remove(item) }
        message = tr("${item.title} удалён", "${item.title} removed")
    }

    fun folder(kind: ContentKind?): Path = (kind?.dir(dir) ?: dir).also { runCatching { it.createDirectories() } }

    private fun work(key: String, block: suspend () -> Unit) {
        if (key in working) return
        working = working + key
        error = null
        message = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: tr("Не получилось", "Something went wrong")
            } finally {
                progress = null
            }
            installed = scanAll()
            working = working - key
            onChanged()
        }
    }

    companion object {
        const val ALL = "*"
    }
}

@Composable
fun CatalogScreen(state: LauncherState) {
    val scope = rememberCoroutineScope()
    val target = state.catalogTarget ?: state.currentEntry()
    val tabs = listOfNotNull(
        CatalogTab.PACKS,
        CatalogTab.MODS.takeIf { target?.loader?.hasMods == true },
        CatalogTab.SHADERS.takeIf { target != null },
        CatalogTab.RESOURCE_PACKS.takeIf { target != null },
        CatalogTab.INSTALLED.takeIf { target != null },
    )
    val tab = state.catalogTab.takeIf { it in tabs } ?: CatalogTab.PACKS
    val packs = remember { PacksModel(scope) }
    val content = target?.let { entry ->
        remember(entry.key) {
            ContentModel(
                dir = state.gameDirOf(entry),
                loader = entry.loader,
                gameVersion = entry.id,
                scope = scope,
                installer = { kind, hit, present, onProgress ->
                    state.installInto(entry, kind, hit.projectId, hit.title, present, onProgress)
                },
                onChanged = { state.modsChanged(entry) },
            )
        }
    }
    LaunchedEffect(content) { content?.rescan() }

    Column(Modifier.fillMaxSize().padding(JuxDimens.Gutter)) {
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.forEach { ChoiceChip(it.title, selected = tab == it, onClick = { state.catalogTab = it }) }
                }
                Spacer(Modifier.height(14.dp))
                when {
                    tab == CatalogTab.PACKS -> PacksTab(state, packs)
                    target != null && content != null -> ContentTab(state, target, content, tab)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PacksTab(state: LauncherState, model: PacksModel) {
    LaunchedEffect(model.search.query) {
        if (model.search.query.isNotBlank()) delay(350)
        model.search.run()
    }
    SearchField(
        value = model.search.query,
        onValueChange = { model.search.query = it },
        placeholder = tr("Найти сборку", "Search packs"),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    ListBox(Modifier.weight(1f)) { listState ->
        SearchResults(model.search, listState) { hit -> PackRow(state, model, hit) }
    }
    StatusStrip(state.progress.takeIf { state.installingPack != null }, model.error, null)
}

@Composable
private fun PackRow(state: LauncherState, model: PacksModel, hit: Modrinth.SearchHit) {
    val pack = state.packs.firstOrNull { it.projectId == hit.projectId }
    val busy = model.resolving == hit.projectId || state.installingPack == hit.projectId
    ContentRow(
        icon = hit.iconUrl,
        title = hit.title,
        byline = listOfNotNull(
            hit.author.takeIf { it.isNotBlank() },
            tr("${formatCount(hit.downloads)} загрузок", "${formatCount(hit.downloads)} downloads"),
            listOfNotNull(loaderOf(hit)?.label, hit.versions.lastOrNull()).joinToString(" ").ifBlank { null },
        ).joinToString(" · "),
        details = hit.description,
        onOpen = { Shell.browse("https://modrinth.com/modpack/${hit.slug.ifBlank { hit.projectId }}") },
    ) {
        when {
            busy -> Text(tr("Ставлю…", "Installing…"), style = MaterialTheme.typography.labelLarge, color = JuxColors.TextMuted)
            pack != null && pack.id in state.packUpdates ->
                JuxButton(tr("Обновить", "Update"), icon = Icons.Default.Refresh, enabled = !state.busy, onClick = { state.updatePack(pack) })
            pack != null -> JuxButton(tr("Играть", "Play"), icon = Icons.Default.PlayArrow, style = ButtonStyle.PRIMARY, enabled = !state.busy, onClick = {
                state.selectEntry(state.entryFor(pack))
                state.play()
            })
            else -> JuxButton(tr("Установить", "Install"), icon = JuxIcons.Download, enabled = !state.busy && model.resolving == null, onClick = {
                model.install(state, hit)
            })
        }
    }
}

private fun loaderOf(hit: Modrinth.SearchHit): LoaderKind? =
    LoaderKind.entries.firstOrNull { it.hasMods && it.name.lowercase() in hit.categories }

@Composable
private fun ColumnScope.ContentTab(state: LauncherState, target: VersionEntry, model: ContentModel, tab: CatalogTab) {
    val kind = tab.kind
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TargetPicker(state, target)
        Spacer(Modifier.weight(1f))
        val updates = model.updates
        if (tab == CatalogTab.INSTALLED && updates.isNotEmpty()) {
            JuxButton(tr("Обновить все · ${updates.size}", "Update all · ${updates.size}"), icon = Icons.Default.Refresh, enabled = model.working.isEmpty(), onClick = model::updateAll)
        }
        WithTooltip(tr("Открыть папку", "Open folder")) {
            IconButton(onClick = { state.openFolder(model.folder(kind)) }, modifier = Modifier.size(36.dp)) {
                Icon(JuxIcons.Folder, tr("Открыть папку", "Open folder"), tint = JuxColors.TextMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    when {
        kind == null -> ListBox(Modifier.weight(1f)) { InstalledList(state, target, model, it) }
        kind == ContentKind.SHADER && !state.supportsShaders(target) -> ListBox(Modifier.weight(1f)) {
            ListHint(
                if (target.loader == LoaderKind.FORGE) tr("Шейдеры не работают с Forge — выбери Fabric или NeoForge", "Shaders don't work with Forge — pick Fabric or NeoForge")
                else tr("Fabric пока не поддерживает ${target.id} — шейдеров нет", "Fabric doesn't support ${target.id} yet — no shaders")
            )
        }
        else -> {
            val search = model.searches.getValue(kind)
            LaunchedEffect(search, search.query) {
                if (search.query.isNotBlank()) delay(350)
                search.run()
            }
            SearchField(
                value = search.query,
                onValueChange = { search.query = it },
                placeholder = kind.searchHint,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            ListBox(Modifier.weight(1f)) { listState ->
                SearchResults(search, listState) { hit -> ContentHitRow(model, kind, hit) }
            }
        }
    }
    StatusStrip(model.progress, model.error, model.message)
}

@Composable
private fun TargetPicker(state: LauncherState, target: VersionEntry) {
    var open by remember { mutableStateOf(false) }
    ReportOpen(open, state::trackMenu)
    Box {
        JuxButton(tr("Для ${target.label}", "For ${target.label}"), icon = Icons.Default.KeyboardArrowDown, onClick = { open = true })
        JuxDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.catalogTargets().forEach { entry ->
                JuxMenuItem(entry.label, onClick = {
                    open = false
                    state.catalogTarget = entry
                })
            }
        }
    }
}

@Composable
private fun ContentHitRow(model: ContentModel, kind: ContentKind, hit: Modrinth.SearchHit) {
    val installed = hit.projectId in model.installedProjects
    val busy = hit.projectId in model.working
    ContentRow(
        icon = hit.iconUrl,
        title = hit.title,
        byline = hit.author.takeIf { it.isNotBlank() }?.let { tr("$it · ${formatCount(hit.downloads)} загрузок", "$it · ${formatCount(hit.downloads)} downloads") }
            ?: tr("${formatCount(hit.downloads)} загрузок", "${formatCount(hit.downloads)} downloads"),
        details = hit.description,
        onOpen = { Shell.browse(kind.page(hit.slug.ifBlank { hit.projectId })) },
    ) {
        when {
            installed -> Tag(tr("Установлен", "Installed"), JuxColors.Success)
            busy -> Text(tr("Ставлю…", "Installing…"), style = MaterialTheme.typography.labelLarge, color = JuxColors.TextMuted)
            else -> JuxButton(
                tr("Установить", "Install"),
                icon = JuxIcons.Download,
                enabled = ContentModel.ALL !in model.working,
                onClick = { model.install(kind, hit) },
            )
        }
    }
}

@Composable
private fun InstalledList(state: LauncherState, target: VersionEntry, model: ContentModel, listState: LazyListState) {
    val kinds = listOfNotNull(ContentKind.MOD.takeIf { target.loader.hasMods }, ContentKind.SHADER, ContentKind.RESOURCE_PACK)
    val sections = kinds.map { it to model.installed[it].orEmpty() }.filter { it.second.isNotEmpty() }
    when {
        !model.scanned -> ListHint(tr("Смотрю, что стоит в ${target.label}…", "Checking what's in ${target.label}…"))
        sections.isEmpty() -> ListHint(tr("Пока пусто", "Nothing here yet"), tr("Открыть каталог", "Open catalog")) {
            state.catalogTab = if (target.loader.hasMods) CatalogTab.MODS else CatalogTab.SHADERS
        }
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            sections.forEach { (kind, items) ->
                item(key = "header-$kind") {
                    Text(
                        "${kind.title.uppercase()} · ${items.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = JuxColors.TextMuted,
                        modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 2.dp),
                    )
                }
                items(items, key = { it.file.toString() }) { item -> InstalledRow(model, item) }
            }
        }
    }
}

@Composable
private fun InstalledRow(model: ContentModel, item: InstalledItem) {
    val busy = item.fileName in model.working || ContentModel.ALL in model.working
    val update = item.update
    val locked = item.fromBoost
    ContentRow(
        icon = item.iconUrl,
        title = item.title,
        byline = listOfNotNull(item.versionNumber.takeIf { it.isNotBlank() }, item.fileName).joinToString(" · "),
        details = update?.let { tr("Обновление: ${it.versionNumber}", "Update: ${it.versionNumber}") },
        detailsAccent = update != null,
        dimmed = !item.enabled,
        badge = if (locked) tr("FPS-буст", "FPS boost") else null,
    ) {
        if (update != null && !locked) {
            JuxButton(tr("Обновить", "Update"), icon = Icons.Default.Refresh, enabled = !busy, onClick = { model.update(item) })
            Spacer(Modifier.width(8.dp))
        }
        WithTooltip(if (locked) tr("Этим модом управляет FPS-буст", "Managed by FPS boost") else if (item.enabled) tr("Выключить", "Disable") else tr("Включить", "Enable")) {
            JuxSwitch(item.enabled, enabled = !busy && !locked) { model.toggle(item, it) }
        }
        Spacer(Modifier.width(4.dp))
        WithTooltip(if (locked) tr("Мод FPS-буста", "FPS boost mod") else tr("Удалить", "Delete")) {
            IconButton(onClick = { model.remove(item) }, enabled = !busy && !locked, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    tr("Удалить", "Delete"),
                    tint = if (!busy && !locked) JuxColors.Danger else JuxColors.TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
