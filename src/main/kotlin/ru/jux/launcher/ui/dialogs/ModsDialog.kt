package ru.jux.launcher.ui.dialogs

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.jux.launcher.core.Shell
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.mods.InstalledMod
import ru.jux.launcher.mods.ModManager
import ru.jux.launcher.mods.Modrinth
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.ModIcon
import ru.jux.launcher.ui.VersionEntry
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.ChoiceChip
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDialog
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.JuxSwitch
import ru.jux.launcher.ui.components.SearchField
import ru.jux.launcher.ui.components.Tag
import ru.jux.launcher.ui.components.ThinProgress
import ru.jux.launcher.ui.components.WithTooltip
import ru.jux.launcher.ui.components.formatCount
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import ru.jux.launcher.ui.theme.PillShape

private enum class ModsTab { CATALOG, INSTALLED }

private class ModsModel(
    private val dir: Path,
    private val loader: LoaderKind,
    private val gameVersion: String,
    private val scope: CoroutineScope,
) {
    var tab by mutableStateOf(ModsTab.CATALOG)
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<Modrinth.SearchHit>>(emptyList())
    var total by mutableStateOf(0)
    var searching by mutableStateOf(false)
    var searchFailed by mutableStateOf(false)
    var installed by mutableStateOf<List<InstalledMod>>(emptyList())
    var scanned by mutableStateOf(false)
    var working by mutableStateOf<Set<String>>(emptySet())
    var progress by mutableStateOf<DownloadProgress?>(null)
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    private var searchJob: Job? = null

    val installedProjects: Set<String> get() = installed.mapNotNull { it.projectId }.toSet()
    val updates: List<InstalledMod> get() = installed.filter { it.update != null }

    fun search(more: Boolean = false) {
        searchJob?.cancel()
        val offset = if (more) hits.size else 0
        searching = true
        searchFailed = false
        searchJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    Modrinth.search(query, ModManager.loadersFor(loader), gameVersion, offset)
                }
                hits = if (more) hits + page.hits.filter { hit -> hits.none { it.projectId == hit.projectId } } else page.hits
                total = page.totalHits
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                searchFailed = true
            } finally {
                searching = false
            }
        }
    }

    fun rescan() {
        scope.launch {
            installed = ModManager.scan(dir, loader, gameVersion, withUpdates = true)
            scanned = true
        }
    }

    fun install(hit: Modrinth.SearchHit) = work(hit.projectId) {
        val titles = ModManager.install(dir, loader, gameVersion, hit.projectId, hit.title, installedProjects) { progress = it }
        message = if (titles.size <= 1) "${hit.title} установлен" else "Установлено: ${titles.joinToString()}"
    }

    fun update(mod: InstalledMod) = work(mod.fileName) {
        ModManager.update(mod) { progress = it }
        message = "${mod.title} обновлён до ${mod.update?.versionNumber.orEmpty()}".trimEnd()
    }

    fun updateAll() = work(ALL) {
        val pending = updates
        pending.forEach { mod -> ModManager.update(mod) { progress = it } }
        message = "Обновлено модов: ${pending.size}"
    }

    fun toggle(mod: InstalledMod, enabled: Boolean) = work(mod.fileName) {
        withContext(Dispatchers.IO) { ModManager.setEnabled(mod, enabled) }
    }

    fun remove(mod: InstalledMod) = work(mod.fileName) {
        withContext(Dispatchers.IO) { ModManager.remove(mod) }
        message = "${mod.title} удалён"
    }

    fun openFolder(state: LauncherState) {
        state.openFolder(ModManager.modsDir(dir).also { runCatching { it.createDirectories() } })
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
            installed = ModManager.scan(dir, loader, gameVersion, withUpdates = true)
            working = working - key
        }
    }

    companion object {
        const val ALL = "*"
    }
}

@Composable
fun ModsDialog(state: LauncherState, entry: VersionEntry) {
    val scope = rememberCoroutineScope()
    val model = remember(entry.key) { ModsModel(state.gameDirOf(entry), entry.loader, entry.id, scope) }
    LaunchedEffect(model) { model.rescan() }
    LaunchedEffect(model, model.query) {
        if (model.query.isNotBlank()) delay(350)
        model.search()
    }
    val close = {
        state.modal = null
        state.modsChanged(entry)
    }

    JuxDialog(
        title = "Моды",
        subtitle = "${entry.label} · каталог Modrinth",
        onDismiss = close,
        width = 860.dp,
        actions = {
            JuxButton("Папка модов", icon = JuxIcons.Folder, onClick = { model.openFolder(state) })
            JuxButton("Готово", style = ButtonStyle.PRIMARY, onClick = close)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Каталог", selected = model.tab == ModsTab.CATALOG, onClick = { model.tab = ModsTab.CATALOG })
            ChoiceChip(
                if (model.scanned) "Установленные · ${model.installed.size}" else "Установленные",
                selected = model.tab == ModsTab.INSTALLED,
                onClick = { model.tab = ModsTab.INSTALLED },
            )
            Spacer(Modifier.weight(1f))
            val updates = model.updates
            if (model.tab == ModsTab.INSTALLED && updates.isNotEmpty()) {
                JuxButton(
                    "Обновить все · ${updates.size}",
                    icon = Icons.Default.Refresh,
                    enabled = model.working.isEmpty(),
                    onClick = model::updateAll,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when (model.tab) {
            ModsTab.CATALOG -> {
                SearchField(
                    value = model.query,
                    onValueChange = { model.query = it },
                    placeholder = "Найти мод: Sodium, JEI, Xaero's Minimap…",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                ListBox(maxHeight = 380) { listState -> CatalogList(model, listState) }
            }
            ModsTab.INSTALLED -> ListBox(maxHeight = 430) { listState -> InstalledList(model, listState) }
        }
        StatusArea(model)
    }
}

@Composable
private fun ColumnScope.ListBox(maxHeight: Int, content: @Composable (LazyListState) -> Unit) {
    val listState = rememberLazyListState()
    Box(
        Modifier
            .weight(1f, fill = false)
            .heightIn(max = maxHeight.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(JuxDimens.CornerCard))
            .background(JuxColors.Background),
    ) {
        content(listState)
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
            style = ScrollbarStyle(
                minimalHeight = 24.dp,
                thickness = 6.dp,
                shape = PillShape,
                hoverDurationMillis = 250,
                unhoverColor = JuxColors.Text.copy(alpha = 0.18f),
                hoverColor = JuxColors.Text.copy(alpha = 0.40f),
            ),
        )
    }
}

@Composable
private fun CatalogList(model: ModsModel, listState: LazyListState) {
    val hits = model.hits
    when {
        hits.isEmpty() && model.searching -> Hint("Ищу на Modrinth…")
        hits.isEmpty() && model.searchFailed -> Hint("Нет связи с Modrinth. Проверь интернет.", "Повторить") { model.search() }
        hits.isEmpty() -> Hint("Ничего не нашлось. Попробуй другое название.")
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(hits, key = { it.projectId }) { hit -> CatalogRow(model, hit) }
            item {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    when {
                        model.searching -> Text("Загружаю…", style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
                        hits.size < model.total -> JuxButton("Показать ещё", onClick = { model.search(more = true) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(model: ModsModel, hit: Modrinth.SearchHit) {
    val installed = hit.projectId in model.installedProjects
    val busy = hit.projectId in model.working
    ModRow(
        icon = hit.iconUrl,
        title = hit.title,
        byline = hit.author.takeIf { it.isNotBlank() }?.let { "от $it · ${formatCount(hit.downloads)} скачиваний" }
            ?: "${formatCount(hit.downloads)} скачиваний",
        details = hit.description,
        onOpen = { Shell.browse("https://modrinth.com/mod/${hit.slug.ifBlank { hit.projectId }}") },
    ) {
        when {
            installed -> Tag("Установлен", JuxColors.Success)
            busy -> Text("Ставлю…", style = MaterialTheme.typography.labelLarge, color = JuxColors.TextMuted)
            else -> JuxButton(
                "Установить",
                icon = JuxIcons.Download,
                enabled = model.working.isEmpty() || ModsModel.ALL !in model.working,
                onClick = { model.install(hit) },
            )
        }
    }
}

@Composable
private fun InstalledList(model: ModsModel, listState: LazyListState) {
    val mods = model.installed
    when {
        !model.scanned -> Hint("Смотрю, что стоит в сборке…")
        mods.isEmpty() -> Hint("Модов пока нет. Найди их в каталоге.", "Открыть каталог") { model.tab = ModsTab.CATALOG }
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(mods, key = { it.file.toString() }) { mod -> InstalledRow(model, mod) }
        }
    }
}

@Composable
private fun InstalledRow(model: ModsModel, mod: InstalledMod) {
    val busy = mod.fileName in model.working || ModsModel.ALL in model.working
    val update = mod.update
    ModRow(
        icon = mod.iconUrl,
        title = mod.title,
        byline = listOfNotNull(mod.versionNumber.takeIf { it.isNotBlank() }, mod.fileName).joinToString(" · "),
        details = update?.let { "Доступно обновление: ${it.versionNumber}" },
        detailsAccent = update != null,
        dimmed = !mod.enabled,
        badge = if (mod.fromBoost) "FPS-буст" else null,
    ) {
        if (update != null && !mod.fromBoost) {
            JuxButton("Обновить", icon = Icons.Default.Refresh, enabled = !busy, onClick = { model.update(mod) })
            Spacer(Modifier.width(8.dp))
        }
        WithTooltip(if (mod.fromBoost) "Этим модом управляет FPS-буст" else if (mod.enabled) "Выключить мод" else "Включить мод") {
            JuxSwitch(mod.enabled, enabled = !busy && !mod.fromBoost) { model.toggle(mod, it) }
        }
        Spacer(Modifier.width(4.dp))
        WithTooltip(if (mod.fromBoost) "Выключи FPS-буст, чтобы убрать его моды" else "Удалить мод") {
            IconButton(onClick = { model.remove(mod) }, enabled = !busy && !mod.fromBoost, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    "Удалить",
                    tint = if (!busy && !mod.fromBoost) JuxColors.Danger else JuxColors.TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ModRow(
    icon: String?,
    title: String,
    byline: String,
    details: String?,
    detailsAccent: Boolean = false,
    dimmed: Boolean = false,
    badge: String? = null,
    onOpen: (() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.alpha(if (dimmed) 0.45f else 1f)) { ModIcon(icon) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(if (dimmed) 0.55f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = JuxColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) Tag(badge, JuxColors.Accent)
                if (onOpen != null) {
                    IconButton(onClick = onOpen, modifier = Modifier.size(24.dp)) {
                        Icon(JuxIcons.OpenInNew, "Открыть на Modrinth", tint = JuxColors.TextMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Text(byline, style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!details.isNullOrBlank()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detailsAccent) JuxColors.Accent else JuxColors.Text.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
private fun Hint(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = JuxColors.TextMuted)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            JuxButton(action, onClick = onAction)
        }
    }
}

@Composable
private fun StatusArea(model: ModsModel) {
    val progress = model.progress
    val error = model.error
    val message = model.message
    if (progress == null && error == null && message == null) return
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JuxDimens.CornerMedium))
            .background(JuxColors.SurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        when {
            progress != null -> {
                Text(
                    "Скачиваю ${progress.currentFile}".trimEnd() +
                        if (progress.totalFiles > 1) " · ${progress.completedFiles}/${progress.totalFiles}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = JuxColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                ThinProgress(
                    if (progress.totalBytes > 0) progress.completedBytes.toFloat() / progress.totalBytes else 0f,
                    Modifier.fillMaxWidth(),
                )
            }
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = JuxColors.Danger)
            message != null -> Text(message, style = MaterialTheme.typography.bodySmall, color = JuxColors.Success)
        }
    }
}
