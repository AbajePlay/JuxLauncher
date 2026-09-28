package ru.jux.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.jux.launcher.core.NoticeLevel
import ru.jux.launcher.launch.ArgumentBuilder
import ru.jux.launcher.online.OnlineCounter
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDropdownMenu
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.JuxMenuItem
import ru.jux.launcher.ui.components.MenuDivider
import ru.jux.launcher.ui.components.NoticeToast
import ru.jux.launcher.ui.components.ReportOpen
import ru.jux.launcher.ui.components.ThinProgress
import ru.jux.launcher.ui.components.Wordmark
import ru.jux.launcher.ui.dialogs.ModalHost
import ru.jux.launcher.ui.screens.AccountsScreen
import ru.jux.launcher.ui.screens.CatalogScreen
import ru.jux.launcher.ui.screens.ActivityScreen
import ru.jux.launcher.ui.screens.HomeScreen
import ru.jux.launcher.ui.screens.NoticesScreen
import ru.jux.launcher.ui.screens.SettingsScreen
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import ru.jux.launcher.ui.theme.PillShape
import ru.jux.launcher.ui.theme.softShadow
import ru.jux.launcher.update.UpdateState

@Composable
fun App(state: LauncherState, onGameStarted: (Process) -> Unit) {
    SideEffect { state.onGameStarted = onGameStarted }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(JuxColors.Background)) {
            NavRail(state)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                ScreenHost(state)
            }
        }
        NoticeToast(
            state,
            Modifier.align(Alignment.BottomEnd).padding(end = JuxDimens.Gutter + 8.dp, bottom = JuxDimens.Gutter + 8.dp),
        )
        ModalHost(state)
    }
}

@Composable
private fun ScreenHost(state: LauncherState) {
    AnimatedContent(
        targetState = state.screen,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            val enterOffset = if (forward) 1 else -1
            val slide = tween<IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)

            (
                fadeIn(tween(220, delayMillis = 90)) +
                    slideInVertically(slide) { height -> enterOffset * height / 14 }
                )
                .togetherWith(
                    fadeOut(tween(130)) +
                        slideOutVertically(slide) { height -> -enterOffset * height / 22 }
                )
                .using(SizeTransform(clip = false))
        },
        label = "screen",
        modifier = Modifier.fillMaxSize(),
    ) { screen ->
        when (screen) {
            Screen.HOME -> HomeScreen(state)
            Screen.CATALOG -> CatalogScreen(state)
            Screen.ACTIVITY -> ActivityScreen(state)
            Screen.NOTICES -> NoticesScreen(state)
            Screen.SETTINGS -> SettingsScreen(state)
            Screen.ACCOUNTS -> AccountsScreen(state)
        }
    }
}

@Composable
private fun NavRail(state: LauncherState) {
    val shape = RoundedCornerShape(JuxDimens.CornerLarge)
    Column(
        Modifier
            .padding(start = JuxDimens.Gutter, top = JuxDimens.Gutter, bottom = JuxDimens.Gutter)
            .width(224.dp)
            .fillMaxHeight()
            .softShadow(shape)
            .background(JuxColors.Sidebar, shape)
            .padding(14.dp),
    ) {
        Wordmark(164.dp, Modifier.padding(start = 12.dp, top = 12.dp))
        Box(
            Modifier
                .padding(start = 12.dp, top = 8.dp, bottom = 22.dp)
                .height(20.dp)
                .background(JuxColors.SurfaceHigh, PillShape)
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "v${ArgumentBuilder.LAUNCHER_VERSION}",
                color = JuxColors.TextMuted,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 11.sp,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
                modifier = Modifier.offset(y = (-1).dp),
            )
        }

        val notices by state.notices.collectAsState()
        val seen by state.noticesSeen.collectAsState()
        val unread = notices.filter { it.id > seen }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NavItem("Играть", Icons.Default.Home, state.screen == Screen.HOME) { state.screen = Screen.HOME }
            NavItem("Каталог", JuxIcons.Extension, state.screen == Screen.CATALOG) { state.openCatalog() }
            NavItem("Активность", JuxIcons.Activity, state.screen == Screen.ACTIVITY) { state.screen = Screen.ACTIVITY }
            NavItem(
                "Уведомления",
                Icons.Default.Notifications,
                state.screen == Screen.NOTICES,
                badge = unread.size,
                badgeAlert = unread.any { it.level == NoticeLevel.ERROR },
            ) { state.openNotices() }
            NavItem("Настройки", Icons.Default.Settings, state.screen == Screen.SETTINGS) { state.screen = Screen.SETTINGS }
        }

        Spacer(Modifier.weight(1f))

        UpdateCard(state)
        OnlineLine()
        AccountSwitcher(state)
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    badge: Int = 0,
    badgeAlert: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background by animateColorAsState(
        targetValue = when {
            selected -> JuxColors.AccentSoft
            hovered -> JuxColors.SurfaceHigh
            else -> Color.Transparent
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "navBackground",
    )
    val content by animateColorAsState(
        targetValue = when {
            selected -> JuxColors.Accent
            hovered -> JuxColors.Text
            else -> JuxColors.TextSoft
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "navContent",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(PillShape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(
                icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp),
            )
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-7).dp)
                        .defaultMinSize(minWidth = 17.dp)
                        .height(17.dp)
                        .clip(PillShape)
                        .background(if (badgeAlert) JuxColors.Danger else JuxColors.Accent)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (badge > 99) "99+" else badge.toString(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = if (badgeAlert) JuxColors.Background else JuxColors.OnAccent,
                        style = TextStyle(
                            lineHeight = 10.sp,
                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = content,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
    }
}

@Composable
private fun OnlineLine() {
    val count by OnlineCounter.count.collectAsState()
    val current = count ?: return
    Row(
        Modifier
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .height(40.dp)
            .clip(PillShape)
            .background(JuxColors.Success.copy(alpha = 0.10f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(12.dp).clip(CircleShape).background(JuxColors.Success.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(JuxColors.Success))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            current.online.toString(),
            color = JuxColors.Text,
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 15.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "онлайн",
            color = JuxColors.TextSoft,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 13.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun AccountSwitcher(state: LauncherState) {
    val account by state.selectedAccount.collectAsState()
    val accounts by state.accounts.collectAsState()
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val onAccounts = state.screen == Screen.ACCOUNTS
    val background by animateColorAsState(
        when {
            onAccounts -> JuxColors.AccentSoft
            hovered || open -> JuxColors.Outline
            else -> JuxColors.SurfaceHigh
        },
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "accountBackground",
    )
    val close = { open = false }
    ReportOpen(open, state::trackMenu)

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(JuxDimens.CornerCard))
                .background(background)
                .clickable(interactionSource = interaction, indication = null) {
                    if (accounts.isEmpty()) state.screen = Screen.ACCOUNTS else open = true
                }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkinHead(account, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account?.name ?: "Добавить аккаунт",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (onAccounts) JuxColors.Accent else JuxColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        account == null -> "нужен, чтобы играть"
                        account!!.isOffline -> "Офлайн"
                        else -> "Лицензия"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = JuxColors.TextMuted,
                )
            }
            if (accounts.isNotEmpty()) {
                Icon(Icons.Default.KeyboardArrowUp, null, tint = JuxColors.TextMuted, modifier = Modifier.size(18.dp))
            }
        }

        JuxDropdownMenu(expanded = open, onDismissRequest = close) {
            accounts.forEach { item ->
                val current = item.uuid == account?.uuid
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium, color = JuxColors.Text)
                            Text(
                                if (item.isOffline) "Офлайн" else "Лицензия",
                                style = MaterialTheme.typography.bodySmall,
                                color = JuxColors.TextMuted,
                            )
                        }
                    },
                    leadingIcon = { SkinHead(item, 28.dp) },
                    trailingIcon = {
                        if (current) Icon(Icons.Default.Check, null, tint = JuxColors.Accent, modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        state.selectAccount(item.uuid)
                        close()
                    },
                    colors = MenuDefaults.itemColors(textColor = JuxColors.Text),
                    modifier = Modifier.height(52.dp),
                )
            }
            MenuDivider()
            JuxMenuItem("Управление аккаунтами", icon = Icons.Default.Person, onClick = {
                close()
                state.screen = Screen.ACCOUNTS
            })
        }
    }
}

@Composable
private fun UpdateCard(state: LauncherState) {
    val update by state.updates.collectAsState()
    val current = update
    if (current !is UpdateState.Available && current !is UpdateState.Downloading && current !is UpdateState.Installing &&
        !(current is UpdateState.Failed && current.update != null)
    ) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(JuxDimens.CornerCard))
            .background(JuxColors.SurfaceHigh)
            .padding(14.dp),
    ) {
        when (current) {
            is UpdateState.Available -> {
                Text("Вышла версия ${current.update.version}", style = MaterialTheme.typography.labelLarge, color = JuxColors.Text)
                Spacer(Modifier.height(8.dp))
                JuxButton(
                    "Обновить",
                    style = ButtonStyle.PRIMARY,
                    onClick = { state.installUpdate(current.update) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is UpdateState.Downloading -> {
                Text("Скачиваю ${current.update.version}", style = MaterialTheme.typography.labelLarge, color = JuxColors.Text)
                Spacer(Modifier.height(10.dp))
                ThinProgress(current.fraction)
            }
            is UpdateState.Installing -> {
                Text("Устанавливаю ${current.update.version}", style = MaterialTheme.typography.labelLarge, color = JuxColors.Text)
                Spacer(Modifier.height(10.dp))
                ThinProgress(1f)
            }
            is UpdateState.Failed -> {
                Text("Обновление не удалось", style = MaterialTheme.typography.labelLarge, color = JuxColors.Danger)
                Text(
                    current.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = JuxColors.TextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                current.update?.let { retry ->
                    JuxButton("Ещё раз", onClick = { state.installUpdate(retry) }, modifier = Modifier.fillMaxWidth())
                }
            }
            else -> Unit
        }
    }
}
