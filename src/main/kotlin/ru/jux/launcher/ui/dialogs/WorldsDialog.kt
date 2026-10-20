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
import ru.jux.launcher.core.tr
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
import ru.jux.launcher.ui.components.formatMoment
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens

// Адреса иконок (exists + время изменения) считаются вместе со списком в IO, а не в UI-потоке при каждой перерисовке
private class WorldsData(val worlds: List<WorldInfo>, val backups: Map<String, List<WorldBackup>>, val icons: Map<String, String?>)

private sealed interface Confirm {
    data class DeleteWorld(val world: WorldInfo) : Confirm
    data class Restore(val backup: WorldBackup) : Confirm
    data class DeleteBackup(val backup: WorldBackup) : Confirm
}

@Composable
fun WorldsDialog(state: LauncherState, modal: Modal.WorldList) {
    val entry = modal.entry
    val dir = state.gameDirOf(entry)
    val data by produceState<WorldsData?>(null, dir, state.instanceRevision) {
        value = withContext(Dispatchers.IO) {
            val worlds = Worlds.list(dir)
            WorldsData(worlds, Worlds.backups(dir).groupBy { it.world }, worlds.associate { it.folder to ModIcons.localUrl(it.icon) })
        }
    }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val working = state.busy && state.busyEntry == entry
    val running = state.isRunning(entry)
    val locked = state.busy || running
    val close = { state.modal = null }

    JuxDialog(
        title = tr("Миры и бэкапы", "Worlds & backups"),
        subtitle = entry.label,
        onDismiss = close,
        width = 720.dp,
        actions = {
            JuxButton(tr("Импорт мира…", "Import world…"), icon = JuxIcons.Upload, enabled = !locked, onClick = { state.importWorld(entry) })
            JuxButton(tr("Папка бэкапов", "Backups folder"), icon = JuxIcons.Folder, onClick = { state.openFolder(Worlds.backupsDir(dir)) })
            JuxButton(tr("Готово", "Done"), style = ButtonStyle.PRIMARY, onClick = close)
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
            val onDeleteBackup = { backup: WorldBackup -> confirm = Confirm.DeleteBackup(backup) }
            when {
                loaded == null -> Hint(tr("Ищу миры…", "Looking for worlds…"))
                loaded.worlds.isEmpty() && deleted.isEmpty() -> Hint(tr("Миров пока нет", "No worlds yet"))
                else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                    loaded.worlds.forEach { world ->
                        WorldCard(
                            name = world.name,
                            details = detailsOf(world),
                            icon = loaded.icons[world.folder],
                            backups = loaded.backups[world.folder].orEmpty(),
                            locked = locked,
                            onRestore = onRestore,
                            onDeleteBackup = onDeleteBackup,
                        ) {
                            JuxButton(tr("Бэкап", "Backup"), icon = JuxIcons.Backup, enabled = !locked, onClick = { state.backupWorld(entry, world) })
                            IconAction(tr("Открыть папку мира", "Open world folder"), JuxIcons.Folder) { state.openFolder(Worlds.savesDir(dir).resolve(world.folder)) }
                            IconAction(tr("Удалить мир", "Delete world"), Icons.Default.Delete, enabled = !locked) { confirm = Confirm.DeleteWorld(world) }
                        }
                    }
                    deleted.forEach { (folder, backups) ->
                        WorldCard(
                            name = folder,
                            details = tr("Удалён — можно вернуть из бэкапа", "Deleted, can be restored from a backup"),
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
            WorkingStrip(state)
        } else if (running) {
            Spacer(Modifier.height(10.dp))
            Text(tr("Закрой игру, чтобы работать с мирами", "Close the game to manage worlds"), style = MaterialTheme.typography.bodySmall, color = JuxColors.Warning)
        }
        confirm?.let { pending ->
            ConfirmStrip(
                text = when (pending) {
                    is Confirm.DeleteWorld -> tr("Удалить мир «${pending.world.name}»? ", "Delete world “${pending.world.name}”? ") +
                        if (Storage.trashAvailable) tr("Уйдёт в корзину.", "It goes to the Recycle Bin.") else tr("Вернуть можно только из бэкапа.", "Only a backup can bring it back.")
                    is Confirm.Restore -> tr("Вернуть «${pending.backup.world}» к бэкапу от ${formatMoment(pending.backup.createdAt)}? ", "Restore “${pending.backup.world}” to the backup from ${formatMoment(pending.backup.createdAt)}? ") +
                        tr("Текущий мир сохранится в бэкап.", "The current world is backed up first.")
                    is Confirm.DeleteBackup -> tr("Удалить бэкап «${pending.backup.world}» от ${formatMoment(pending.backup.createdAt)}? Насовсем, без корзины.", "Delete the backup of “${pending.backup.world}” from ${formatMoment(pending.backup.createdAt)}? Permanently, no Recycle Bin.")
                },
                action = if (pending is Confirm.Restore) tr("Вернуть", "Restore") else tr("Удалить", "Delete"),
                danger = pending !is Confirm.Restore,
                onCancel = { confirm = null },
                onConfirm = {
                    when (pending) {
                        is Confirm.DeleteWorld -> state.deleteWorld(entry, pending.world)
                        is Confirm.Restore -> state.restoreWorld(entry, pending.backup)
                        is Confirm.DeleteBackup -> state.deleteBackup(entry, pending.backup)
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
                    "${formatMoment(backup.createdAt)} · ${formatBytes(backup.bytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = JuxColors.Text,
                    modifier = Modifier.weight(1f),
                )
                if (backup.auto) Tag(tr("авто", "auto"), JuxColors.Info)
                Text(
                    tr("Вернуть", "Restore"),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (locked) JuxColors.TextMuted else JuxColors.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(JuxDimens.CornerSmall))
                        .clickable(enabled = !locked) { onRestore(backup) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                IconButton(onClick = { onDeleteBackup(backup) }, enabled = !locked, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, tr("Удалить бэкап", "Delete backup"), tint = JuxColors.TextMuted, modifier = Modifier.size(16.dp))
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
    world.level.lastPlayed.takeIf { it > 0 }?.let { tr("играли ${formatNoticeDay(it).lowercase()}", "played ${formatNoticeDay(it).lowercase()}") },
).joinToString(" · ")

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().height(120.dp).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = JuxColors.TextMuted)
    }
}


// Прогресс бэкапа меняется на каждый файл мира: читаем его здесь, чтобы перерисовывалась только эта полоска, а не весь диалог
@Composable
private fun WorkingStrip(state: LauncherState) {
    Spacer(Modifier.height(12.dp))
    Text(state.stage.ifBlank { tr("Работаю…", "Working…") }, style = MaterialTheme.typography.bodySmall, color = JuxColors.Text)
    Spacer(Modifier.height(6.dp))
    ThinProgress(state.progress?.fraction ?: 0f)
}
