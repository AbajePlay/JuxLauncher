package ru.jux.launcher.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.io.path.exists
import kotlinx.coroutines.delay
import ru.jux.launcher.core.Shortcuts
import ru.jux.launcher.instance.InstanceOptions
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.mods.PerformancePack
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.servers.ServerEntry
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.PingState
import ru.jux.launcher.ui.VersionEntry
import ru.jux.launcher.ui.VersionGroup
import ru.jux.launcher.ui.components.ChoiceChip
import ru.jux.launcher.ui.components.ContextMenuBox
import ru.jux.launcher.ui.components.JuxDropdownMenu
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.JuxMenuItem
import ru.jux.launcher.ui.components.MenuDivider
import ru.jux.launcher.ui.components.Panel
import ru.jux.launcher.ui.components.ReportOpen
import ru.jux.launcher.ui.components.SearchField
import ru.jux.launcher.ui.components.Tag
import ru.jux.launcher.ui.components.WithTooltip
import ru.jux.launcher.ui.components.formatBytes
import ru.jux.launcher.ui.components.formatReleaseDate
import ru.jux.launcher.ui.components.formatSpeed
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import ru.jux.launcher.ui.theme.PillShape
import ru.jux.launcher.ui.theme.glow

@Composable
fun HomeScreen(state: LauncherState) {
    Column(Modifier.fillMaxSize().padding(JuxDimens.Gutter)) {
        QuickPlayCard(state)
        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                Column {
                    ListHeader(state)
                    Spacer(Modifier.height(12.dp))
                    val groups = state.groups()
                    if (groups.isEmpty()) {
                        CenteredHint(
                            when {
                                state.onlyInstalled && state.searchQuery.isBlank() -> "Скачанных версий пока нет"
                                state.onlyInstalled -> "Среди скачанных такой версии нет"
                                else -> "Ничего не найдено. Включите снапшоты или старые версии в настройках."
                            }
                        )
                    } else {
                        GroupedVersionList(state, groups)
                    }
                }
            }

            ServersPanel(state, Modifier.width(280.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun QuickPlayCard(state: LauncherState) {
    val entry = state.busyEntry ?: state.currentEntry()
    val installed = entry != null && state.isEntryInstalled(entry)

    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("К ЗАПУСКУ", style = MaterialTheme.typography.labelSmall, color = JuxColors.TextMuted)
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        entry?.title ?: "версия не выбрана",
                        style = if (entry == null || entry.pack != null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
                        color = if (entry == null) JuxColors.TextMuted else JuxColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    when {
                        entry?.pack != null -> Tag("${entry.id} ${entry.loader.label}", loaderColor(entry.loader))
                        entry != null && entry.loader.isModded -> Tag(entry.loader.label, loaderColor(entry.loader))
                    }
                }
                if (entry != null) {
                    Spacer(Modifier.height(16.dp))
                    InstanceChips(state, entry)
                }
            }

            Spacer(Modifier.width(20.dp))

            Box(Modifier.width(320.dp), contentAlignment = Alignment.CenterEnd) {
                if (state.busy) {
                    ProgressArea(state)
                } else {
                    PlayButton(installed = installed, enabled = entry != null, onClick = { state.play() })
                }
            }
        }
    }
}

@Composable
private fun PlayButton(installed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        when {
            pressed && enabled -> 0.96f
            hovered && enabled -> 1.02f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "playScale",
    )
    val glow by animateDpAsState(if (hovered && enabled) 30.dp else 20.dp, label = "playGlow")
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = JuxColors.Accent,
            contentColor = JuxColors.OnAccent,
            disabledContainerColor = JuxColors.SurfaceHigh,
            disabledContentColor = JuxColors.TextMuted,
        ),
        contentPadding = PaddingValues(horizontal = 32.dp),
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (enabled) Modifier.glow(PillShape, JuxColors.Accent.copy(alpha = 0.55f), glow) else Modifier)
            .defaultMinSize(minWidth = 260.dp)
            .height(72.dp),
    ) {
        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (installed) "Играть" else "Установить и играть",
            fontWeight = FontWeight.Black,
            fontSize = if (installed) 20.sp else 17.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun InstanceChips(state: LauncherState, entry: VersionEntry) {
    val options = state.selectedOptions

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (entry.pack == null && entry.loader.supportsBoost) {
            val fabricReady = state.loaderSupport.supports(LoaderKind.FABRIC, entry.id)
            WithTooltip(boostHint(entry, options, fabricReady)) {
                ChoiceChip(
                    label = "FPS-буст",
                    selected = options.fpsBoost,
                    onClick = { state.setBoost(entry, !options.fpsBoost) },
                    enabled = fabricReady && !state.busy,
                    icon = JuxIcons.Bolt,
                )
            }
        }

        WithTooltip("Открыть папку: миры, скриншоты, моды и настройки") {
            ChoiceChip(
                label = "Папка",
                selected = false,
                onClick = { state.openFolder(state.gameDirOf(entry)) },
                icon = JuxIcons.Folder,
            )
        }

        val pack = entry.pack
        if (pack != null && pack.id in state.packUpdates) {
            WithTooltip("Вышла ${state.packUpdates[pack.id]?.version.orEmpty()}. Миры, настройки и твои моды останутся".trim()) {
                ChoiceChip(
                    label = "Обновить сборку",
                    selected = true,
                    onClick = { state.updatePack(pack) },
                    enabled = !state.busy,
                    icon = Icons.Default.Refresh,
                )
            }
        }

        EntryMenuButton(state, entry)
    }
}

private fun boostHint(entry: VersionEntry, options: InstanceOptions, fabricReady: Boolean): String = when {
    !fabricReady ->
        "Моды FPS-буста работают через Fabric, а он пока не поддерживает ${entry.id}."
    options.fpsBoost && (options.boostMods.isNotEmpty() || options.boostOwned.isNotEmpty()) ->
        "Включён: ${(options.boostMods.map { it.title } + options.boostOwned).distinct().joinToString()}." +
            (if (options.boostMissing.isNotEmpty()) " Ещё не вышли для ${entry.id}: ${options.boostMissing.joinToString()}." else "") +
            " Нажми, чтобы выключить — моды удалятся."
    options.fpsBoost ->
        "Включён. Моды с Modrinth поставятся при запуске: ${PerformancePack.summary}."
    entry.loader == LoaderKind.VANILLA ->
        "${PerformancePack.summary} с Modrinth. Игра запустится через Fabric в этой же папке — " +
            "миры и настройки останутся на месте."
    else ->
        "${PerformancePack.summary} с Modrinth добавятся к твоим модам. Те, что уже стоят, не продублируются."
}

@Composable
private fun EntryMenuButton(state: LauncherState, entry: VersionEntry) {
    var open by remember { mutableStateOf(false) }
    val close = { open = false }
    ReportOpen(open, state::trackMenu)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.MoreVert, "Действия со сборкой", tint = JuxColors.TextMuted, modifier = Modifier.size(20.dp))
        }
        JuxDropdownMenu(expanded = open, onDismissRequest = close) {
            EntryMenuItems(state, entry, close, fromList = false)
        }
    }
}

@Composable
fun EntryMenuItems(state: LauncherState, entry: VersionEntry, close: () -> Unit, fromList: Boolean = true) {
    val installed = state.isEntryInstalled(entry)
    val hasFolder = remember(entry, state.instanceRevision) { state.gameDirOf(entry).exists() }

    if (fromList) {
        JuxMenuItem("Играть", icon = Icons.Default.PlayArrow, enabled = !state.busy, onClick = {
            close()
            state.selectEntry(entry)
            state.play()
        })
        JuxMenuItem("Открыть папку", icon = JuxIcons.Folder, onClick = {
            close()
            state.openFolder(state.gameDirOf(entry))
        })
    }
    JuxMenuItem("Логи", icon = JuxIcons.Log, onClick = {
        close()
        state.showLogs(entry)
    })
    JuxMenuItem("Ярлык на рабочем столе", icon = JuxIcons.OpenInNew, enabled = Shortcuts.isAvailable, onClick = {
        close()
        state.createShortcut(entry)
    })
    MenuDivider()
    JuxMenuItem("Переустановить", icon = Icons.Default.Refresh, enabled = (installed || entry.pack != null) && !state.busy, onClick = {
        close()
        state.reinstall(entry)
    })
    JuxMenuItem(
        "Удалить…",
        icon = Icons.Default.Delete,
        danger = true,
        enabled = (installed || hasFolder) && !(state.busy && state.busyEntry == entry),
        onClick = {
            close()
            state.modal = Modal.Delete(entry)
        },
    )
}

@Composable
private fun ProgressArea(state: LauncherState) {
    val progress = state.progress
    Column(Modifier.width(320.dp)) {
        Text(
            state.stage.ifBlank { "Готовлюсь" },
            style = MaterialTheme.typography.bodySmall,
            color = JuxColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        ProgressBar(progress?.fraction ?: -1f)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progressDetails(progress),
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.TextMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Отмена",
                style = MaterialTheme.typography.labelLarge,
                color = JuxColors.TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(JuxDimens.CornerSmall))
                    .clickable { state.cancel() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private fun progressDetails(progress: DownloadProgress?): String = when {
    progress == null -> ""
    progress.totalBytes > 0 -> buildString {
        append(formatBytes(progress.completedBytes)).append(" из ").append(formatBytes(progress.totalBytes))
        formatSpeed(progress.bytesPerSecond).takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
    }
    progress.totalFiles > 0 -> "${progress.completedFiles} из ${progress.totalFiles} файлов"
    else -> ""
}

@Composable
private fun ProgressBar(fraction: Float) {
    val animated by animateFloatAsState(if (fraction < 0f) 0f else fraction, label = "progress")
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(8.dp)
            .clip(PillShape)
            .background(JuxColors.SurfaceHigh)
    ) {
        if (fraction < 0f) {
            val sweep by rememberInfiniteTransition(label = "indeterminate").animateFloat(
                initialValue = -0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)),
                label = "sweep",
            )
            Box(
                Modifier.fillMaxHeight()
                    .width(maxWidth * 0.35f)
                    .offset(x = maxWidth * sweep)
                    .clip(PillShape)
                    .background(JuxColors.Accent)
            )
        } else {
            Box(
                Modifier.fillMaxHeight()
                    .fillMaxWidth(animated)
                    .clip(PillShape)
                    .background(JuxColors.Accent)
            )
        }
    }
}

@Composable
private fun ListHeader(state: LauncherState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchField(
            value = state.searchQuery,
            onValueChange = { state.searchQuery = it },
            placeholder = "Поиск версии",
            modifier = Modifier.weight(1f),
            focusRequester = state.searchFocus,
            onFocusChange = { state.searchFocused = it },
        )
        WithTooltip(if (state.onlyInstalled) "Показать все версии" else "Показать только скачанные версии и сборки") {
            ChoiceChip(
                label = "Скачанные",
                selected = state.onlyInstalled,
                onClick = state::toggleOnlyInstalled,
                icon = JuxIcons.Download,
            )
        }
    }
}

private class DoubleClick {
    private var lastKey: String? = null
    private var lastAt = 0L

    fun isSecondClick(key: String): Boolean {
        val now = System.currentTimeMillis()
        val second = key == lastKey && now - lastAt < DOUBLE_CLICK_MILLIS
        lastKey = if (second) null else key
        lastAt = now
        return second
    }

    private companion object {
        const val DOUBLE_CLICK_MILLIS = 400L
    }
}

@Composable
private fun GroupedVersionList(state: LauncherState, groups: List<VersionGroup>) {
    val clicks = remember { DoubleClick() }
    LazyColumn(state = state.listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        groups.forEach { group ->
            item(key = "header-${group.key}") {
                GroupHeader(
                    group = group,
                    expanded = state.isExpanded(group),
                    onToggle = { state.toggleGroup(group.key) },
                )
            }
            item(key = "body-${group.key}") {
                AnimatedVisibility(
                    visible = state.isExpanded(group),
                    enter = expandVertically(
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                    ) + fadeIn(tween(200, delayMillis = 60)),
                    exit = shrinkVertically(
                        animationSpec = tween(240, easing = FastOutSlowInEasing),
                    ) + fadeOut(tween(150)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        group.entries.forEach { entry -> EntryRow(state, entry, clicks) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: VersionGroup, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "groupArrow")
    val versions = remember(group) { group.entries.distinctBy { it.pack?.id ?: it.id }.size }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JuxDimens.CornerSmall))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = JuxColors.TextMuted,
            modifier = Modifier.size(18.dp).rotate(rotation),
        )
        Text(
            group.key,
            style = MaterialTheme.typography.titleMedium,
            color = JuxColors.Text,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            versions.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = JuxColors.TextMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(state: LauncherState, entry: VersionEntry, clicks: DoubleClick) {
    val selected = state.isSelected(entry)
    val installed = state.isEntryInstalled(entry)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> JuxColors.AccentSoft
            hovered -> JuxColors.SurfaceHigh
            else -> Color.Transparent
        },
        animationSpec = tween(160),
        label = "rowBackground",
    )
    val dotSize by animateDpAsState(if (installed) 7.dp else 0.dp, label = "installedDot")

    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected, state.keyboardMoves) {
        if (selected && state.keyboardMoves > 0) requester.bringIntoView()
    }

    ContextMenuBox(
        modifier = Modifier.padding(start = 18.dp),
        onOpenChange = { open ->
            state.trackMenu(open)
            if (open) state.selectEntry(entry)
        },
        menu = { close -> EntryMenuItems(state, entry, close) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(requester)
                .clip(RoundedCornerShape(JuxDimens.CornerMedium))
                .background(background)
                .clickable(interactionSource = interaction, indication = null) {
                    state.selectEntry(entry)
                    if (clicks.isSecondClick(entry.key)) state.play()
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(7.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(dotSize).clip(CircleShape).background(JuxColors.Accent))
            }
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) JuxColors.Accent else JuxColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Tag(entry.loader.label, loaderColor(entry.loader))
            }
            Text(
                if (entry.pack != null) entry.id else formatReleaseDate(entry.version.releaseTime),
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.TextMuted,
            )
        }
    }
}

private fun loaderColor(kind: LoaderKind) = when (kind) {
    LoaderKind.FABRIC -> JuxColors.LoaderFabric
    LoaderKind.QUILT -> JuxColors.LoaderQuilt
    LoaderKind.FORGE -> JuxColors.LoaderForge
    LoaderKind.NEOFORGE -> JuxColors.LoaderNeoForge
    LoaderKind.VANILLA -> JuxColors.LoaderVanilla
}

@Composable
private fun ServersPanel(state: LauncherState, modifier: Modifier) {
    LaunchedEffect(Unit) {
        while (true) {
            state.refreshServers()
            delay(60_000)
        }
    }

    Panel(modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "СЕРВЕРЫ",
                    style = MaterialTheme.typography.labelSmall,
                    color = JuxColors.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { state.refreshServers() },
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(JuxColors.SurfaceHigh),
                ) {
                    Icon(Icons.Default.Refresh, "Обновить", tint = JuxColors.TextMuted, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(14.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.servers, key = { it.address }) { server -> ServerRow(state, server) }
            }
        }
    }
}

@Composable
private fun ServerRow(state: LauncherState, server: ServerEntry) {
    val ping = state.serverStatus[server.address]
    val clipboard = LocalClipboardManager.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val online = (ping as? PingState.Online)?.status

    ContextMenuBox(
        onOpenChange = state::trackMenu,
        menu = { close ->
            JuxMenuItem("Играть", icon = Icons.Default.PlayArrow, enabled = !state.busy, onClick = {
                close()
                state.playOnServer(server)
            })
            JuxMenuItem("Копировать адрес", icon = JuxIcons.Copy, onClick = {
                close()
                runCatching { clipboard.setText(AnnotatedString(server.address)) }
                    .onFailure { state.fail("Не удалось скопировать адрес: буфер обмена занят другой программой") }
            })
        },
    ) {
        WithTooltip(online?.let { listOf(it.motd, "Сервер: ${it.versionName}").filter(String::isNotBlank).joinToString("\n") }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(JuxDimens.CornerCard))
                    .background(if (hovered) JuxColors.Outline else JuxColors.SurfaceHigh)
                    .hoverable(interaction)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ServerIcon(online?.favicon)
                Column(Modifier.weight(1f)) {
                    Text(
                        server.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = JuxColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusLine(ping)
                }
                IconButton(
                    onClick = { state.playOnServer(server) },
                    enabled = !state.busy,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(if (state.busy) JuxColors.Surface else JuxColors.AccentSoft),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        "Играть на сервере",
                        tint = if (state.busy) JuxColors.TextMuted else JuxColors.Accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(ping: PingState?) {
    val (dot, text) = when (ping) {
        is PingState.Online -> JuxColors.Success to "${ping.status.playersOnline}/${ping.status.playersMax}"
        PingState.Offline -> JuxColors.Danger to "не отвечает"
        else -> JuxColors.TextMuted to "проверяю…"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted, maxLines = 1)
    }
}

@Composable
private fun ServerIcon(favicon: ByteArray?) {
    val bitmap = remember(favicon) {
        favicon?.let { bytes ->
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        }
    }
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(JuxDimens.CornerMedium)).background(JuxColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, null, modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
        } else {
            Icon(JuxIcons.Server, null, tint = JuxColors.TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
    }
}
