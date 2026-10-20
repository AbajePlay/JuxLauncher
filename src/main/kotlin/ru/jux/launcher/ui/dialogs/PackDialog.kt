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
import ru.jux.launcher.core.tr
import ru.jux.launcher.link.LaunchLink
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
        title = tr("Новая сборка", "New pack"),
        subtitle = tr("Отдельная папка для модов и миров", "A separate folder for mods and worlds"),
        onDismiss = { state.modal = null },
        actions = {
            JuxButton(tr("Отмена", "Cancel"), onClick = { state.modal = null })
            JuxButton(tr("Создать", "Create"), style = ButtonStyle.PRIMARY, enabled = title.isNotBlank() && version.isNotBlank(), onClick = submit)
        },
    ) {
        Label(tr("Название", "Name"))
        JuxTextField(title, { title = it.take(TITLE_LIMIT) }, tr("Например, «Выживание с друзьями»", "E.g. “Survival with friends”"), Modifier.fillMaxWidth(), onSubmit = submit)
        Spacer(Modifier.height(14.dp))
        Label(tr("Версия игры", "Game version"))
        VersionPicker(state, version, releases) { version = it }
        Spacer(Modifier.height(14.dp))
        Label(tr("Загрузчик", "Loader"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            loaders.forEach { kind ->
                ChoiceChip(kind.label, selected = loader == kind, onClick = { loader = kind })
            }
        }
        Spacer(Modifier.height(18.dp))
        LinkField(state)
    }
}

/** Ссылка на сборку от друга: в Discord и Telegram juxlauncher:// не нажимается, поэтому её вставляют сюда. */
@Composable
private fun LinkField(state: LauncherState) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val open = {
        val link = LaunchLink.parse(text.trim())
        if (link == null || link == LaunchLink.Open) {
            error = tr("Это не ссылка JuxLauncher — скопируй её целиком", "This isn't a JuxLauncher link — copy all of it")
        } else {
            state.openLink(link)
        }
    }
    Label(tr("Или сборка друга по ссылке", "Or a friend's pack from a link"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        JuxTextField(
            text,
            {
                text = it
                error = null
            },
            "juxlauncher://pack?…",
            Modifier.weight(1f),
            leadingIcon = JuxIcons.Link,
            isError = error != null,
            onSubmit = { if (text.isNotBlank()) open() },
        )
        JuxButton(tr("Открыть", "Open"), enabled = text.isNotBlank(), onClick = open)
    }
    error?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = JuxColors.Danger)
    }
}

@Composable
private fun CopyPack(state: LauncherState, mode: PackMode.Copy) {
    val from = mode.from
    var title by remember { mutableStateOf(tr("${from.title} (копия)", "${from.title} (copy)").take(TITLE_LIMIT)) }
    val submit = { if (title.isNotBlank()) state.createPack(title, from.id, from.loader, copyFrom = from) }

    JuxDialog(
        title = tr("Копия сборки", "Duplicate pack"),
        subtitle = from.label,
        onDismiss = { state.modal = null },
        actions = {
            JuxButton(tr("Отмена", "Cancel"), onClick = { state.modal = null })
            JuxButton(tr("Скопировать", "Duplicate"), style = ButtonStyle.PRIMARY, enabled = title.isNotBlank(), onClick = submit)
        },
    ) {
        Label(tr("Название копии", "Copy name"))
        JuxTextField(title, { title = it.take(TITLE_LIMIT) }, tr("Название", "Name"), Modifier.fillMaxWidth(), onSubmit = submit)
        Spacer(Modifier.height(10.dp))
        Text(
            tr("Моды, настройки и миры скопируются, оригинал не изменится", "Mods, settings and worlds are copied; the original stays as is"),
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
        title = tr("Сборка", "Pack"),
        subtitle = "${pack.gameVersion} ${pack.loader.label}",
        onDismiss = { state.modal = null },
        actions = {
            JuxButton(tr("Отмена", "Cancel"), onClick = { state.modal = null })
            JuxButton(tr("Сохранить", "Save"), style = ButtonStyle.PRIMARY, enabled = title.isNotBlank(), onClick = submit)
        },
    ) {
        Label(tr("Название", "Name"))
        JuxTextField(title, { title = it.take(TITLE_LIMIT) }, tr("Название", "Name"), Modifier.fillMaxWidth(), onSubmit = submit)
        Spacer(Modifier.height(14.dp))
        Label(tr("Иконка", "Icon"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModIcon(state.packIconUrl(pack), 48.dp, fallback = JuxIcons.Layers)
            JuxButton(tr("Выбрать картинку…", "Choose image…"), icon = JuxIcons.Image, onClick = { state.choosePackIcon(pack) })
        }
    }
}

@Composable
private fun VersionPicker(state: LauncherState, version: String, releases: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ReportOpen(open, state::trackMenu)
    Box {
        JuxButton(version.ifBlank { tr("Выбрать", "Choose") }, icon = Icons.Default.KeyboardArrowDown, onClick = { open = true })
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
