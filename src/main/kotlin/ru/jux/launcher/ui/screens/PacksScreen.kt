package ru.jux.launcher.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.jux.launcher.core.Shell
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.mods.Modrinth
import ru.jux.launcher.packs.Modpacks
import ru.jux.launcher.ui.CatalogSearch
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.ContentRow
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.ListBox
import ru.jux.launcher.ui.components.Panel
import ru.jux.launcher.ui.components.SearchField
import ru.jux.launcher.ui.components.SearchResults
import ru.jux.launcher.ui.components.StatusStrip
import ru.jux.launcher.ui.components.formatCount
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens

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
                error = e.message ?: "Не получилось"
            } finally {
                resolving = null
            }
        }
    }
}

@Composable
fun PacksScreen(state: LauncherState) {
    val scope = rememberCoroutineScope()
    val model = remember { PacksModel(scope) }
    LaunchedEffect(model.search.query) {
        if (model.search.query.isNotBlank()) delay(350)
        model.search.run()
    }

    Column(Modifier.fillMaxSize().padding(JuxDimens.Gutter)) {
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            Column {
                SearchField(
                    value = model.search.query,
                    onValueChange = { model.search.query = it },
                    placeholder = "Найти сборку на Modrinth: Fabulously Optimized, Cobblemon…",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                ListBox(Modifier.weight(1f)) { listState ->
                    SearchResults(model.search, listState) { hit -> PackRow(state, model, hit) }
                }
                StatusStrip(state.progress.takeIf { state.installingPack != null }, model.error, null)
            }
        }
    }
}

@Composable
private fun PackRow(state: LauncherState, model: PacksModel, hit: Modrinth.SearchHit) {
    val pack = state.packs.firstOrNull { it.projectId == hit.projectId }
    val busy = model.resolving == hit.projectId || state.installingPack == hit.projectId
    ContentRow(
        icon = hit.iconUrl,
        title = hit.title,
        byline = listOfNotNull(
            hit.author.takeIf { it.isNotBlank() }?.let { "от $it" },
            "${formatCount(hit.downloads)} скачиваний",
            listOfNotNull(loaderOf(hit)?.label, hit.versions.lastOrNull()).joinToString(" ").ifBlank { null },
        ).joinToString(" · "),
        details = hit.description,
        onOpen = { Shell.browse("https://modrinth.com/modpack/${hit.slug.ifBlank { hit.projectId }}") },
    ) {
        when {
            busy -> Text("Ставлю…", style = MaterialTheme.typography.labelLarge, color = JuxColors.TextMuted)
            pack != null && pack.id in state.packUpdates ->
                JuxButton("Обновить", icon = Icons.Default.Refresh, enabled = !state.busy, onClick = { state.updatePack(pack) })
            pack != null -> JuxButton("Играть", icon = Icons.Default.PlayArrow, style = ButtonStyle.PRIMARY, enabled = !state.busy, onClick = {
                state.selectEntry(state.entryFor(pack))
                state.play()
            })
            else -> JuxButton("Установить", icon = JuxIcons.Download, enabled = !state.busy && model.resolving == null, onClick = {
                model.install(state, hit)
            })
        }
    }
}

private fun loaderOf(hit: Modrinth.SearchHit): LoaderKind? =
    LoaderKind.entries.firstOrNull { it.isModded && it.name.lowercase() in hit.categories }
