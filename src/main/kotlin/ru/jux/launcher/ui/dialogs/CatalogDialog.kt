package ru.jux.launcher.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.mods.ContentKind
import ru.jux.launcher.mods.IncompatibleModException
import ru.jux.launcher.mods.InstalledItem
import ru.jux.launcher.mods.ModManager
import ru.jux.launcher.mods.Modrinth
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.ui.CatalogSearch
import ru.jux.launcher.ui.CatalogTab
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.VersionEntry
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.ChoiceChip
import ru.jux.launcher.ui.components.ContentRow
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDialog
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.JuxSwitch
import ru.jux.launcher.ui.components.ListBox
import ru.jux.launcher.ui.components.ListHint
import ru.jux.launcher.ui.components.SearchField
import ru.jux.launcher.ui.components.SearchResults
import ru.jux.launcher.ui.components.StatusStrip
import ru.jux.launcher.ui.components.Tag
import ru.jux.launcher.ui.components.WithTooltip
import ru.jux.launcher.ui.components.formatCount
import ru.jux.launcher.ui.theme.JuxColors

private val CatalogTab.kind: ContentKind?
    get() = when (this) {
        CatalogTab.MODS -> ContentKind.MOD
        CatalogTab.SHADERS -> ContentKind.SHADER
        CatalogTab.RESOURCE_PACKS -> ContentKind.RESOURCE_PACK
        CatalogTab.INSTALLED -> null
    }

private val ContentKind.title: String
    get() = when (this) {
        ContentKind.MOD -> "Моды"
        ContentKind.SHADER -> "Шейдеры"
        ContentKind.RESOURCE_PACK -> "Ресурспаки"
    }

private val ContentKind.searchHint: String
    get() = when (this) {
        ContentKind.MOD -> "Найти мод: Sodium, JEI, Xaero's Minimap…"
        ContentKind.SHADER -> "Найти шейдер: Complementary, BSL, Photon…"
        ContentKind.RESOURCE_PACK -> "Найти ресурспак: Fresh Animations, Faithful…"
    }

private class CatalogModel(
    private val dir: Path,
    private val loader: LoaderKind,
    private val gameVersion: String,
    private val scope: CoroutineScope,
    val tabs: List<CatalogTab>,
    start: CatalogTab,
    private val enableBoost: () -> Boolean,
) {
    var tab by mutableStateOf(start)
    val search = CatalogSearch(scope) { query, offset ->
        tab.kind?.let { kind -> Modrinth.search(kind.projectType, query, ModManager.catalogLoaders(kind, loader), gameVersion, offset) }
            ?: Modrinth.SearchPage()
    }
    var installed by mutableStateOf<Map<ContentKind, List<InstalledItem>>>(emptyMap())
    var scanned by mutableStateOf(false)
    var working by mutableStateOf<Set<String>>(emptySet())
    var progress by mutableStateOf<DownloadProgress?>(null)
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    val shownKinds: List<ContentKind> get() = tabs.mapNotNull { it.kind }
    val installedProjects: Set<String> get() = installed.values.flatten().mapNotNull { it.projectId }.toSet()
    val updates: List<InstalledItem> get() = installed[ContentKind.MOD].orEmpty().filter { it.update != null }

    fun open(next: CatalogTab) {
        if (tab == next) return
        tab = next
        search.reset()
    }

    fun rescan() {
        scope.launch {
            installed = scanAll()
            scanned = true
        }
    }

    private suspend fun scanAll(): Map<ContentKind, List<InstalledItem>> =
        ContentKind.entries.associateWith { kind ->
            ModManager.scan(dir, loader, gameVersion, kind, withUpdates = kind == ContentKind.MOD && loader.isModded)
        }

    fun install(hit: Modrinth.SearchHit) = work(hit.projectId) {
        message = when (tab.kind) {
            ContentKind.SHADER -> {
                val titles = ModManager.installShader(dir, loader, gameVersion, hit.projectId, hit.title, installedProjects) { progress = it }
                val boosted = loader == LoaderKind.VANILLA && enableBoost()
                listOfNotNull(
                    "${hit.title} установлен и включён",
                    "вместе с ${titles.dropLast(1).joinToString()}".takeIf { titles.size > 1 },
                    "шейдеры работают через FPS-буст, он включён".takeIf { boosted },
                ).joinToString(", ")
            }
            ContentKind.RESOURCE_PACK -> {
                ModManager.installResourcePack(dir, gameVersion, hit.projectId, hit.title) { progress = it }
                "${hit.title} установлен и включён"
            }
            else -> {
                val titles = ModManager.install(dir, loader, gameVersion, hit.projectId, hit.title, installedProjects) { progress = it }
                if (titles.size <= 1) "${hit.title} установлен" else "Установлено: ${titles.joinToString()}"
            }
        }
    }

    fun update(mod: InstalledItem) = work(mod.fileName) {
        ModManager.update(mod) { progress = it }
        message = "${mod.title} обновлён до ${mod.update?.versionNumber.orEmpty()}".trimEnd()
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
            message = "Обновлено модов: $done"
        } else {
            error = (listOf("Обновлено модов: $done") + skipped).joinToString(". ")
        }
    }

    fun toggle(item: InstalledItem, enabled: Boolean) = work(item.fileName) {
        withContext(Dispatchers.IO) { ModManager.setEnabled(item, enabled) }
    }

    fun remove(item: InstalledItem) = work(item.fileName) {
        withContext(Dispatchers.IO) { ModManager.remove(item) }
        message = "${item.title} удалён"
    }

    fun openFolder(state: LauncherState) {
        val folder = tab.kind?.dir(dir) ?: dir
        state.openFolder(folder.also { runCatching { it.createDirectories() } })
    }

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
                error = e.message ?: "Не получилось"
            } finally {
                progress = null
            }
            installed = scanAll()
            working = working - key
        }
    }

    companion object {
        const val ALL = "*"
    }
}

@Composable
fun CatalogDialog(state: LauncherState, entry: VersionEntry, start: CatalogTab?) {
    val scope = rememberCoroutineScope()
    val model = remember(entry.key) {
        val tabs = listOfNotNull(CatalogTab.MODS.takeIf { entry.loader.isModded }, CatalogTab.SHADERS, CatalogTab.RESOURCE_PACKS, CatalogTab.INSTALLED)
        CatalogModel(
            dir = state.gameDirOf(entry),
            loader = entry.loader,
            gameVersion = entry.id,
            scope = scope,
            tabs = tabs,
            start = start?.takeIf { it in tabs } ?: tabs.first(),
            enableBoost = { state.enableBoost(entry) },
        )
    }
    val shadersPossible = state.supportsShaders(entry)
    LaunchedEffect(model) { model.rescan() }
    LaunchedEffect(model, model.tab, model.search.query) {
        if (model.tab.kind == null || (model.tab == CatalogTab.SHADERS && !shadersPossible)) return@LaunchedEffect
        if (model.search.query.isNotBlank()) delay(350)
        model.search.run()
    }
    val close = {
        state.modal = null
        state.modsChanged(entry)
    }

    JuxDialog(
        title = "Каталог",
        subtitle = "${entry.label} · Modrinth",
        onDismiss = close,
        width = 860.dp,
        actions = {
            JuxButton("Открыть папку", icon = JuxIcons.Folder, onClick = { model.openFolder(state) })
            JuxButton("Готово", style = ButtonStyle.PRIMARY, onClick = close)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.tabs.forEach { tab ->
                ChoiceChip(tab.kind?.title ?: "Установленные", selected = model.tab == tab, onClick = { model.open(tab) })
            }
            Spacer(Modifier.weight(1f))
            val updates = model.updates
            if (model.tab == CatalogTab.INSTALLED && updates.isNotEmpty()) {
                JuxButton(
                    "Обновить все · ${updates.size}",
                    icon = Icons.Default.Refresh,
                    enabled = model.working.isEmpty(),
                    onClick = model::updateAll,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val kind = model.tab.kind
        when {
            kind == null -> ListBox(Modifier.weight(1f, fill = false).heightIn(max = 430.dp)) { InstalledList(model, it) }
            kind == ContentKind.SHADER && !shadersPossible -> ListBox(Modifier.heightIn(max = 430.dp)) {
                ListHint(
                    if (entry.loader == LoaderKind.FORGE) "Шейдеры работают через Iris, а для Forge его нет. Выбери эту версию с Fabric или NeoForge."
                    else "Шейдерам нужен Fabric, а он пока не поддерживает ${entry.id}."
                )
            }
            else -> {
                SearchField(
                    value = model.search.query,
                    onValueChange = { model.search.query = it },
                    placeholder = kind.searchHint,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                ListBox(Modifier.weight(1f, fill = false).heightIn(max = 380.dp)) { listState ->
                    SearchResults(model.search, listState) { hit -> CatalogRow(model, kind, hit) }
                }
            }
        }
        StatusStrip(model.progress, model.error, model.message)
    }
}

@Composable
private fun CatalogRow(model: CatalogModel, kind: ContentKind, hit: Modrinth.SearchHit) {
    val installed = hit.projectId in model.installedProjects
    val busy = hit.projectId in model.working
    ContentRow(
        icon = hit.iconUrl,
        title = hit.title,
        byline = hit.author.takeIf { it.isNotBlank() }?.let { "от $it · ${formatCount(hit.downloads)} скачиваний" }
            ?: "${formatCount(hit.downloads)} скачиваний",
        details = hit.description,
        onOpen = { Shell.browse(kind.page(hit.slug.ifBlank { hit.projectId })) },
    ) {
        when {
            installed -> Tag("Установлен", JuxColors.Success)
            busy -> Text("Ставлю…", style = MaterialTheme.typography.labelLarge, color = JuxColors.TextMuted)
            else -> JuxButton(
                "Установить",
                icon = JuxIcons.Download,
                enabled = CatalogModel.ALL !in model.working,
                onClick = { model.install(hit) },
            )
        }
    }
}

@Composable
private fun InstalledList(model: CatalogModel, listState: LazyListState) {
    val sections = model.shownKinds.map { it to model.installed[it].orEmpty() }.filter { it.second.isNotEmpty() }
    when {
        !model.scanned -> ListHint("Смотрю, что стоит в сборке…")
        sections.isEmpty() -> ListHint("Пока ничего не установлено. Найди моды, шейдеры и ресурспаки в каталоге.", "Открыть каталог") {
            model.open(model.tabs.first())
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
private fun InstalledRow(model: CatalogModel, item: InstalledItem) {
    val busy = item.fileName in model.working || CatalogModel.ALL in model.working
    val update = item.update
    val locked = item.fromBoost
    ContentRow(
        icon = item.iconUrl,
        title = item.title,
        byline = listOfNotNull(item.versionNumber.takeIf { it.isNotBlank() }, item.fileName).joinToString(" · "),
        details = update?.let { "Доступно обновление: ${it.versionNumber}" },
        detailsAccent = update != null,
        dimmed = !item.enabled,
        badge = if (locked) "FPS-буст" else null,
    ) {
        if (update != null && !locked) {
            JuxButton("Обновить", icon = Icons.Default.Refresh, enabled = !busy, onClick = { model.update(item) })
            Spacer(Modifier.width(8.dp))
        }
        WithTooltip(if (locked) "Этим модом управляет FPS-буст" else if (item.enabled) "Выключить" else "Включить") {
            JuxSwitch(item.enabled, enabled = !busy && !locked) { model.toggle(item, it) }
        }
        Spacer(Modifier.width(4.dp))
        WithTooltip(if (locked) "Выключи FPS-буст, чтобы убрать его моды" else "Удалить") {
            IconButton(onClick = { model.remove(item) }, enabled = !busy && !locked, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    "Удалить",
                    tint = if (!busy && !locked) JuxColors.Danger else JuxColors.TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
