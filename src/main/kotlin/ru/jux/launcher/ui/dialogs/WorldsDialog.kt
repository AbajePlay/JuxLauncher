package ru.jux.launcher.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.jux.launcher.core.Storage
import ru.jux.launcher.instance.WorldBackup
import ru.jux.launcher.instance.WorldInfo
import ru.jux.launcher.instance.Worlds
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.ModIcon
import ru.jux.launcher.ui.ModIcons
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.ConfirmStrip
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDialog
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.Tag
import ru.jux.launcher.ui.components.ThinProgress
import ru.jux.launcher.ui.components.WithTooltip
import ru.jux.launcher.ui.components.formatBytes
import ru.jux.launcher.ui.components.formatNoticeDay
import ru.jux.launcher.ui.components.formatNoticeTime
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens

private class WorldsData(val worlds: List<WorldInfo>, val backups: Map<String, List<WorldBackup>>)

private sealed interface Confirm {
    data class DeleteWorld(val world: WorldInfo) : Confirm
    data class Restore(val backup: WorldBackup) : Confirm
}

@Composable
fun WorldsDialog(state: LauncherState, modal: Modal.WorldList) {
    val entry = modal.entry
    val dir = state.gameDirOf(entry)
    val data by produceState<WorldsData?>(null, dir, state.instanceRevision) {
        value = withContext(Dispatchers.IO) { WorldsData(Worlds.list(dir), Worlds.backups(dir).groupBy { it.world }) }
    }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val working = state.busy && state.busyEntry == entry
    val locked = state.busy || state.gameRunning
    val close = { state.modal = null }

    JuxDialog(
        title = "Миры и бэкапы",
        subtitle = entry.label,
        onDismiss = close,
        width = 720.dp,
        actions = {
            JuxButton("Импорт мира…", icon = JuxIcons.Upload, enabled = !locked, onClick = { state.importWorld(entry) })
            JuxButton("Папка бэкапов", icon = JuxIcons.Folder, onClick = { state.openFolder(Worlds.backupsDir(dir)) })
            JuxButton("Готово", style = ButtonStyle.PRIMARY, onClick = close)
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 380.dp)
                .clip(RoundedCornerShape(JuxDimens.CornerCard))
                .background(JuxColors.Background),
        ) {
            val loaded = data
            val deleted = loaded?.backups.orEmpty().filterKeys { folder -> loaded?.worlds.orEmpty().none { it.folder == folder } }
            val onRestore = { backup: WorldBackup -> confirm = Confirm.Restore(backup) }
            val onDeleteBackup = { backup: WorldBackup -> state.deleteBackup(entry, backup) }
            when {
                loaded == null -> Hint("Ищу миры…")
                loaded.worlds.isEmpty() && deleted.isEmpty() -> Hint("Миров пока нет")
                else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                    loaded.worlds.forEach { world ->
                        WorldCard(
                            name = world.name,
                            details = detailsOf(world),
                            icon = ModIcons.localUrl(world.icon),
                            backups = loaded.backups[world.folder].orEmpty(),
                            locked = locked,
                            onRestore = onRestore,
                            onDeleteBackup = onDeleteBackup,
                        ) {
                            JuxButton("Бэкап", icon = JuxIcons.Backup, enabled = !locked, onClick = { state.backupWorld(entry, world) })
                            IconAction("Открыть папку мира", JuxIcons.Folder) { state.openFolder(Worlds.savesDir(dir).resolve(world.folder)) }
                            IconAction("Удалить мир", Icons.Default.Delete, enabled = !locked) { confirm = Confirm.DeleteWorld(world) }
                        }
                    }
                    deleted.forEach { (folder, backups) ->
                        WorldCard(
                            name = folder,
                            details = "Удалён — можно вернуть из бэкапа",
                            icon = null,
                            backups = backups,
                            locked = locked,
                            onRestore = onRestore,
                            onDeleteBackup = onDeleteBackup,
                        )
                    }
                }
            }
        }
        if (working) {
            Spacer(Modifier.height(12.dp))
            Text(state.stage.ifBlank { "Работаю…" }, style = MaterialTheme.typography.bodySmall, color = JuxColors.Text)
            Spacer(Modifier.height(6.dp))
            ThinProgress(state.progress?.fraction ?: 0f)
        } else if (state.gameRunning) {
            Spacer(Modifier.height(10.dp))
            Text("Закрой игру, чтобы работать с мирами", style = MaterialTheme.typography.bodySmall, color = JuxColors.Warning)
        }
        confirm?.let { pending ->
            ConfirmStrip(
                text = when (pending) {
                    is Confirm.DeleteWorld -> "Удалить мир «${pending.world.name}»? " +
                        if (Storage.trashAvailable) "Уйдёт в корзину." else "Вернуть можно только из бэкапа."
                    is Confirm.Restore -> "Вернуть «${pending.backup.world}» к бэкапу от ${dateOf(pending.backup.createdAt)}? " +
                        "Текущий мир сохранится в бэкап."
                },
                action = if (pending is Confirm.DeleteWorld) "Удалить" else "Вернуть",
                danger = pending is Confirm.DeleteWorld,
                onCancel = { confirm = null },
                onConfirm = {
                    when (pending) {
                        is Confirm.DeleteWorld -> state.deleteWorld(entry, pending.world)
                        is Confirm.Restore -> state.restoreWorld(entry, pending.backup)
                    }
                    confirm = null
                },
            )
        }
    }
}

@Composable
private fun WorldCard(
    name: String,
    details: String,
    icon: String?,
    backups: List<WorldBackup>,
    locked: Boolean,
    onRestore: (WorldBackup) -> Unit,
    onDeleteBackup: (WorldBackup) -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .clip(RoundedCornerShape(JuxDimens.CornerMedium))
            .background(JuxColors.Surface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModIcon(icon, fallback = JuxIcons.World)
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    color = JuxColors.Text,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(details, style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
        }
        backups.forEach { backup ->
            Row(
                Modifier.fillMaxWidth().padding(start = 56.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(JuxIcons.History, null, tint = JuxColors.TextMuted, modifier = Modifier.size(16.dp))
                Text(
                    "${dateOf(backup.createdAt)} · ${formatBytes(backup.bytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = JuxColors.Text,
                    modifier = Modifier.weight(1f),
                )
                if (backup.auto) Tag("авто", JuxColors.Info)
                Text(
                    "Вернуть",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (locked) JuxColors.TextMuted else JuxColors.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(JuxDimens.CornerSmall))
                        .clickable(enabled = !locked) { onRestore(backup) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                IconButton(onClick = { onDeleteBackup(backup) }, enabled = !locked, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, "Удалить бэкап", tint = JuxColors.TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun IconAction(hint: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    WithTooltip(hint) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(icon, hint, tint = JuxColors.TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

private fun detailsOf(world: WorldInfo): String = listOfNotNull(
    world.mode,
    world.level.gameVersion,
    formatBytes(world.bytes),
    world.level.lastPlayed.takeIf { it > 0 }?.let { "играли ${formatNoticeDay(it).lowercase()}" },
).joinToString(" · ")

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().height(120.dp).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = JuxColors.TextMuted)
    }
}

private fun dateOf(at: Long): String = "${formatNoticeDay(at)}, ${formatNoticeTime(at)}"
