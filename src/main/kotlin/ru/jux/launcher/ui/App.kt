package ru.jux.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDropdownMenu
import ru.jux.launcher.ui.components.JuxMenuItem
import ru.jux.launcher.ui.components.MenuDivider
import ru.jux.launcher.ui.components.ReportOpen
import ru.jux.launcher.ui.components.ThinProgress
import ru.jux.launcher.ui.components.Wordmark
import ru.jux.launcher.ui.dialogs.ModalHost
import ru.jux.launcher.ui.screens.AccountsScreen
import ru.jux.launcher.ui.screens.HomeScreen
import ru.jux.launcher.ui.screens.SettingsScreen
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
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
            Screen.SETTINGS -> SettingsScreen(state)
            Screen.ACCOUNTS -> AccountsScreen(state)
        }
    }
}

@Composable
private fun NavRail(state: LauncherState) {
    Column(
        Modifier
            .width(196.dp)
            .fillMaxHeight()
            .background(JuxColors.Surface)
            .padding(12.dp),
    ) {
        Wordmark(156.dp, Modifier.padding(horizontal = 8.dp, vertical = 15.dp))

        Spacer(Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NavItem("Играть", Icons.Default.Home, state.screen == Screen.HOME) { state.screen = Screen.HOME }
            NavItem("Настройки", Icons.Default.Settings, state.screen == Screen.SETTINGS) { state.screen = Screen.SETTINGS }
        }

        Spacer(Modifier.weight(1f))

        UpdateCard(state)
        AccountSwitcher(state)
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background by animateColorAsState(
        targetValue = when {
            selected -> JuxColors.Accent.copy(alpha = 0.14f)
            hovered -> JuxColors.SurfaceHigh
            else -> Color.Transparent
        },
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "navBackground",
    )
    val content by animateColorAsState(
        targetValue = when {
            selected -> JuxColors.Accent
            hovered -> JuxColors.Text
            else -> JuxColors.TextMuted
        },
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "navContent",
    )
    val markerHeight by animateDpAsState(
        targetValue = if (selected) 18.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "navMarker",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(JuxDimens.CornerMedium))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(12.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(markerHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(JuxColors.Accent)
            )
        }
        Icon(
            icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
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
            onAccounts -> JuxColors.Accent.copy(alpha = 0.14f)
            hovered || open -> JuxColors.SurfaceHigh
            else -> Color.Transparent
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
                .clip(RoundedCornerShape(JuxDimens.CornerMedium))
                .background(background)
                .clickable(interactionSource = interaction, indication = null) {
                    if (accounts.isEmpty()) state.screen = Screen.ACCOUNTS else open = true
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkinHead(account, 32.dp)
            Spacer(Modifier.width(10.dp))
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
    if (current !is UpdateState.Available && current !is UpdateState.Downloading &&
        !(current is UpdateState.Failed && current.update != null)
    ) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(JuxDimens.CornerMedium))
            .background(JuxColors.SurfaceHigh)
            .padding(12.dp),
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
