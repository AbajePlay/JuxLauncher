package ru.jux.launcher.ui.dialogs

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.io.path.exists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.Storage
import ru.jux.launcher.core.tr
import ru.jux.launcher.logs.GameLogs
import ru.jux.launcher.logs.LogSource
import ru.jux.launcher.mods.GraphicsPreset
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.VersionEntry
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.ChoiceChip
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDialog
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.NoteStrip
import ru.jux.launcher.ui.components.formatBytes
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import ru.jux.launcher.ui.theme.PillShape

@Composable
fun ModalHost(state: LauncherState) {
    when (val modal = state.modal) {
        null -> Unit
        is Modal.Delete -> DeleteDialog(state, modal.entry)
        is Modal.Logs -> LogDialog(state, modal)
        is Modal.Carry -> CarryDialog(state, modal)
        is Modal.Join -> JoinDialog(state, modal)
        is Modal.Install -> InstallDialog(state, modal)
        is Modal.WorldList -> WorldsDialog(state, modal)
        is Modal.PackEditor -> PackDialog(state, modal)
        is Modal.Share -> ShareDialog(state, modal.entry)
        is Modal.ImportPack -> ImportPackDialog(state, modal)
        is Modal.Graphics -> GraphicsDialog(state, modal.entry)
    }
}

@Composable
private fun GraphicsDialog(state: LauncherState, entry: VersionEntry) {
    var preset by remember { mutableStateOf(GraphicsPreset.LOW) }
    val running = state.isRunning(entry)
    val close = { state.modal = null }

    JuxDialog(
        title = tr("Графика в игре", "In-game graphics"),
        subtitle = entry.label,
        onDismiss = close,
        width = 480.dp,
        actions = {
            JuxButton(tr("Отмена", "Cancel"), onClick = close)
            JuxButton(tr("Применить", "Apply"), style = ButtonStyle.PRIMARY, enabled = !running, onClick = {
                close()
                state.applyGraphics(entry, preset)
            })
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GraphicsPreset.entries.forEach { option ->
                val selected = option == preset
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(JuxDimens.CornerMedium))
                        .background(if (selected) JuxColors.AccentSoft else Color.Transparent)
                        .clickable { preset = option }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    Text(option.title, style = MaterialTheme.typography.titleMedium, color = if (selected) JuxColors.Accent else JuxColors.Text)
                    Text(option.summary.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (running) tr("Закрой игру: при выходе она перезапишет настройки", "Close the game: it overwrites the settings when it exits")
            else tr("Меняются только дальность, графика, частицы, облака и тени — остальное в игре останется как было", "Only render distance, graphics, particles, clouds and shadows change; everything else stays as it was"),
            style = MaterialTheme.typography.bodySmall,
            color = if (running) JuxColors.Warning else JuxColors.TextMuted,
        )
    }
}

private class DeleteInfo(val versionBytes: Long, val folderExists: Boolean, val folderBytes: Long, val hasWorlds: Boolean)

@Composable
private fun DeleteDialog(state: LauncherState, entry: VersionEntry) {
    val dir = state.gameDirOf(entry)
    var info by remember { mutableStateOf<DeleteInfo?>(null) }
    var withFolder by remember { mutableStateOf(false) }
    LaunchedEffect(entry) {
        info = withContext(Dispatchers.IO) {
            val ids = if (entry.pack != null) emptyList() else Storage.versionIdsOf(entry.id, entry.loader, state.versions.map { it.id })
            DeleteInfo(
                versionBytes = ids.sumOf { Storage.sizeOf(Paths.versionDir(it)) },
                folderExists = dir.exists(),
                folderBytes = Storage.sizeOf(dir),
                hasWorlds = Storage.hasWorlds(dir),
            )
        }
    }
    val close = { state.modal = null }

    JuxDialog(
        title = tr("Удалить ${entry.label}?", "Delete ${entry.label}?"),
        onDismiss = close,
        width = 500.dp,
        actions = {
            JuxButton(tr("Отмена", "Cancel"), onClick = close)
            JuxButton(
                if (withFolder) tr("Удалить всё", "Delete all") else tr("Удалить", "Delete"),
                style = ButtonStyle.DANGER,
                enabled = info != null,
                onClick = {
                    state.delete(entry, withFolder)
                    close()
                },
            )
        },
    ) {
        val known = info
        if (known == null) {
            Text(tr("Считаю место…", "Measuring size…"), style = MaterialTheme.typography.bodyMedium, color = JuxColors.TextMuted)
            return@JuxDialog
        }
        if (entry.pack != null) {
            Text(
                tr("Папка сборки (${formatBytes(known.folderBytes)}) ", "Pack folder (${formatBytes(known.folderBytes)}) ") +
                    if (Storage.trashAvailable) tr("уйдёт в корзину", "goes to the Recycle Bin") else tr("удалится насовсем", "is deleted permanently"),
                style = MaterialTheme.typography.bodyMedium,
                color = JuxColors.Text,
            )
            return@JuxDialog
        }
        Text(
            if (known.versionBytes > 0) {
                tr("Файлы версии: ${formatBytes(known.versionBytes)}. Скачаются заново при запуске", "Version files: ${formatBytes(known.versionBytes)}. Re-downloaded on launch")
            } else {
                tr("Файлов версии уже нет", "Version files already gone")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = JuxColors.Text,
        )
        if (known.folderExists) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(JuxDimens.CornerMedium))
                    .clickable { withFolder = !withFolder }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = withFolder,
                    onCheckedChange = { withFolder = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = JuxColors.Danger,
                        uncheckedColor = JuxColors.TextMuted,
                        checkmarkColor = JuxColors.Background,
                    ),
                )
                Column(Modifier.padding(top = 12.dp)) {
                    Text(
                        tr("И папку сборки ", "And the pack folder ") + (if (known.hasWorlds) tr("с мирами ", "with worlds ") else "") +
                            "(${formatBytes(known.folderBytes)})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = JuxColors.Text,
                    )
                    Text(
                        if (Storage.trashAvailable) tr("Уйдёт в корзину", "Goes to the Recycle Bin")
                        else tr("Насовсем, без корзины", "Permanently, no Recycle Bin"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (Storage.trashAvailable) JuxColors.TextMuted else JuxColors.Danger,
                    )
                }
            }
        }
    }
}

private sealed interface ShareState {
    data object Idle : ShareState
    data object Confirm : ShareState
    data object Uploading : ShareState
    data object Copied : ShareState
    data class CopyFailed(val message: String) : ShareState
    data class Done(val url: String) : ShareState
    data class Failed(val message: String) : ShareState
}

@Composable
private fun LogDialog(state: LauncherState, modal: Modal.Logs) {
    var source by remember { mutableStateOf(modal.source) }
    val file = remember(source) { GameLogs.locate(source, modal.gameDir) }
    var lines by remember(file) { mutableStateOf<List<String>?>(null) }
    var readError by remember(file) { mutableStateOf<String?>(null) }
    var share by remember(file) { mutableStateOf<ShareState>(ShareState.Idle) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(file) {
        if (file == null) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { GameLogs.readForView(file) } }
            .onSuccess { lines = it }
            .onFailure { readError = it.message }
    }
    LaunchedEffect(lines) {
        lines?.takeIf { it.isNotEmpty() }?.let { listState.scrollToItem(it.lastIndex) }
    }

    val close = { state.modal = null }
    JuxDialog(
        title = tr("Логи", "Logs"),
        subtitle = listOfNotNull(modal.title, file?.fileName?.toString()).joinToString(" · "),
        onDismiss = close,
        width = 860.dp,
        actions = {
            JuxButton(tr("Копировать", "Copy"), icon = JuxIcons.Copy, enabled = file != null, onClick = {
                val target = file ?: return@JuxButton
                scope.launch {
                    share = runCatching {
                        val text = withContext(Dispatchers.IO) { GameLogs.readForShare(target) }
                        clipboard.setText(AnnotatedString(text))
                        ShareState.Copied
                    }.getOrElse { ShareState.CopyFailed(it.message ?: tr("неизвестная ошибка", "unknown error")) }
                }
            })
            JuxButton(tr("Открыть папку", "Open folder"), icon = JuxIcons.Folder, enabled = file != null, onClick = {
                file?.parent?.let(state::openFolder)
            })
            JuxButton(
                tr("Поделиться…", "Share…"),
                icon = Icons.Default.Share,
                style = ButtonStyle.PRIMARY,
                enabled = file != null && share != ShareState.Uploading,
                onClick = { share = ShareState.Confirm },
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogSource.entries.forEach { option ->
                ChoiceChip(
                    option.label,
                    selected = source == option,
                    enabled = option == LogSource.LAUNCHER || modal.gameDir != null,
                    onClick = { source = option },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(340.dp)
                .clip(RoundedCornerShape(JuxDimens.CornerCard))
                .background(JuxColors.Background),
        ) {
            val shown = lines
            when {
                file == null -> LogHint(emptyLogText(source))
                readError != null -> LogHint(tr("Лог не читается: $readError", "Can't read log: $readError"))
                shown == null -> LogHint(tr("Читаю…", "Reading…"))
                shown.isEmpty() -> LogHint(tr("Лог пуст", "Log is empty"))
                else -> {
                    SelectionContainer {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                            items(shown) { line ->
                                Text(
                                    line.replace("\t", "    "),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = lineColor(line),
                                )
                            }
                        }
                    }
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
        }
        ShareStatus(
            share = share,
            onConfirm = {
                val target = file ?: return@ShareStatus
                scope.launch {
                    share = ShareState.Uploading
                    share = runCatching {
                        val text = withContext(Dispatchers.IO) { GameLogs.readForShare(target) }
                        val url = GameLogs.share(text)
                        clipboard.setText(AnnotatedString(url))
                        ShareState.Done(url)
                    }.getOrElse { ShareState.Failed(it.message ?: tr("неизвестная ошибка", "unknown error")) }
                }
            },
            onCancel = { share = ShareState.Idle },
        )
    }
}

private fun emptyLogText(source: LogSource): String = when (source) {
    LogSource.GAME -> tr("Сборку ещё не запускали", "Pack hasn't been launched yet")
    LogSource.CRASH -> tr("Вылетов не было", "No crashes")
    LogSource.LAUNCHER -> tr("Лог лаунчера не найден.", "Launcher log not found.")
}

private fun lineColor(line: String): Color = when {
    line.contains("ERROR") || line.contains("FATAL") || line.contains("Exception") ||
        line.startsWith("\tat ") || line.startsWith("Caused by") -> JuxColors.Danger
    line.contains("WARN") -> JuxColors.Warning
    else -> JuxColors.Text
}

@Composable
private fun LogHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = JuxColors.TextMuted)
    }
}

@Composable
private fun ShareStatus(share: ShareState, onConfirm: () -> Unit, onCancel: () -> Unit) {
    if (share == ShareState.Idle) return
    NoteStrip(Modifier.padding(top = 12.dp)) {
        when (share) {
            ShareState.Confirm -> {
                Text(
                    tr("Лог будет доступен по ссылке на mclo.gs. ", "The log will be shared via a link on mclo.gs. ") +
                        tr("Токены скрыты.", "Tokens are hidden."),
                    style = MaterialTheme.typography.bodySmall,
                    color = JuxColors.Text,
                    modifier = Modifier.weight(1f),
                )
                JuxButton(tr("Отмена", "Cancel"), onClick = onCancel)
                JuxButton(tr("Загрузить", "Upload"), style = ButtonStyle.PRIMARY, onClick = onConfirm)
            }
            ShareState.Uploading -> Text(tr("Загружаю на mclo.gs…", "Uploading to mclo.gs…"), style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
            ShareState.Copied -> Text(
                tr("Лог скопирован, токены скрыты", "Log copied, tokens hidden"),
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.TextMuted,
            )
            is ShareState.CopyFailed -> Text(
                tr("Не скопировалось: ${share.message}", "Copy failed: ${share.message}"),
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.Danger,
            )
            is ShareState.Done -> {
                Text(tr("Ссылка скопирована:", "Link copied:"), style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
                Text(
                    share.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JuxColors.Accent,
                    modifier = Modifier.clip(RoundedCornerShape(JuxDimens.CornerSmall)).clickable { Shell.browse(share.url) },
                )
            }
            is ShareState.Failed -> Text(
                tr("Не загрузилось: ${share.message}", "Upload failed: ${share.message}"),
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.Danger,
            )
            ShareState.Idle -> Unit
        }
    }
}
