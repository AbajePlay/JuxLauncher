package ru.jux.launcher.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.meta.VersionKind
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.ModIcon
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.PackMode
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.ChoiceChip
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDialog
import ru.jux.launcher.ui.components.JuxDropdownMenu
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.JuxMenuItem
import ru.jux.launcher.ui.components.JuxTextField
import ru.jux.launcher.ui.components.ReportOpen
import ru.jux.launcher.ui.theme.JuxColors

@Composable
fun PackDialog(state: LauncherState, modal: Modal.PackEditor) {
    when (val mode = modal.mode) {
        PackMode.Create -> CreatePack(state)
        is PackMode.Copy -> CopyPack(state, mode)
        is PackMode.Edit -> EditPack(state, mode)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreatePack(state: LauncherState) {
    val releases = remember(state.versions) { state.versions.filter { it.kind == VersionKind.RELEASE }.map { it.id } }
    val start = state.currentEntry()?.takeIf { it.pack == null && it.id in releases }
    var title by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(start?.id ?: releases.firstOrNull().orEmpty()) }
    var loader by remember { mutableStateOf(start?.loader ?: LoaderKind.VANILLA) }
    val loaders = state.loaderSupport.loadersFor(version)
    if (loader !in loaders) loader = LoaderKind.VANILLA
    val submit = { if (title.isNotBlank() && version.isNotBlank()) state.createPack(title, version, loader) }

    JuxDialog(
        title = "Новая сборка",
        subtitle = "Отдельная папка для модов и миров",
        onDismiss = { state.modal = null },
        actions = {
            JuxButton("Отмена", onClick = { state.modal = null })
            JuxButton("Создать", style = ButtonStyle.PRIMARY, enabled = title.isNotBlank() && version.isNotBlank(), onClick = submit)
        },
    ) {
        Label("Название")
        JuxTextField(title, { title = it.take(TITLE_LIMIT) }, "Например, «Выживание с друзьями»", Modifier.fillMaxWidth(), onSubmit = submit)
        Spacer(Modifier.height(14.dp))
        Label("Версия игры")
        VersionPicker(state, version, releases) { version = it }
        Spacer(Modifier.height(14.dp))
        Label("Загрузчик")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            loaders.forEach { kind ->
                ChoiceChip(kind.label, selected = loader == kind, onClick = { loader = kind })
            }
        }
    }
}

@Composable
private fun CopyPack(state: LauncherState, mode: PackMode.Copy) {
    val from = mode.from
    var title by remember { mutableStateOf("${from.title} (копия)".take(TITLE_LIMIT)) }
    val submit = { if (title.isNotBlank()) state.createPack(title, from.id, from.loader, copyFrom = from) }

    JuxDialog(
        title = "Копия сборки",
        subtitle = from.label,
        onDismiss = { state.modal = null },
        actions = {
            JuxButton("Отмена", onClick = { state.modal = null })
            JuxButton("Скопировать", style = ButtonStyle.PRIMARY, enabled = title.isNotBlank(), onClick = submit)
        },
    ) {
        Label("Название копии")
        JuxTextField(title, { title = it.take(TITLE_LIMIT) }, "Название", Modifier.fillMaxWidth(), onSubmit = submit)
        Spacer(Modifier.height(10.dp))
        Text(
            "Моды, настройки и миры скопируются, оригинал не изменится",
            style = MaterialTheme.typography.bodySmall,
            color = JuxColors.TextMuted,
        )
    }
}

@Composable
private fun EditPack(state: LauncherState, mode: PackMode.Edit) {
    val pack = state.packs.firstOrNull { it.id == mode.pack.id } ?: mode.pack
    var title by remember { mutableStateOf(pack.title) }
    val submit = { if (title.isNotBlank()) state.editPack(pack, title) }

    JuxDialog(
        title = "Сборка",
        subtitle = "${pack.gameVersion} ${pack.loader.label}",
        onDismiss = { state.modal = null },
        actions = {
            JuxButton("Отмена", onClick = { state.modal = null })
            JuxButton("Сохранить", style = ButtonStyle.PRIMARY, enabled = title.isNotBlank(), onClick = submit)
        },
    ) {
        Label("Название")
        JuxTextField(title, { title = it.take(TITLE_LIMIT) }, "Название", Modifier.fillMaxWidth(), onSubmit = submit)
        Spacer(Modifier.height(14.dp))
        Label("Иконка")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModIcon(state.packIconUrl(pack), 48.dp, fallback = JuxIcons.Layers)
            JuxButton("Выбрать картинку…", icon = JuxIcons.Image, onClick = { state.choosePackIcon(pack) })
        }
    }
}

@Composable
private fun VersionPicker(state: LauncherState, version: String, releases: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ReportOpen(open, state::trackMenu)
    Box {
        JuxButton(version.ifBlank { "Выбрать" }, icon = Icons.Default.KeyboardArrowDown, onClick = { open = true })
        JuxDropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            releases.forEach { id ->
                JuxMenuItem(id, onClick = {
                    onPick(id)
                    open = false
                })
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = JuxColors.Text)
    Spacer(Modifier.height(6.dp))
}

private const val TITLE_LIMIT = 48
