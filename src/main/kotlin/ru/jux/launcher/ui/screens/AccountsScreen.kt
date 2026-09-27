package ru.jux.launcher.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.jux.launcher.auth.Account
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.SkinHead
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxTextField
import ru.jux.launcher.ui.components.Panel
import ru.jux.launcher.ui.components.SectionTitle
import ru.jux.launcher.ui.components.Tag
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens

@Composable
fun AccountsScreen(state: LauncherState) {
    val accounts by state.accounts.collectAsState()
    val selected by state.selectedAccount.collectAsState()
    var nickname by remember { mutableStateOf("") }
    var nicknameError by remember { mutableStateOf<String?>(null) }

    val addOffline = {
        val problem = state.addOffline(nickname)
        nicknameError = problem
        if (problem == null) nickname = ""
    }

    Column(
        Modifier.fillMaxSize().padding(JuxDimens.Gutter).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("Лицензионный вход")
                Text(
                    "Откроется браузер с формой Microsoft. Пароль вводится только там — " +
                        "лаунчер его не видит и не хранит.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JuxColors.TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                JuxButton(
                    if (state.signingIn) state.signInStage.ifBlank { "Вход…" } else "Войти через Microsoft",
                    style = ButtonStyle.PRIMARY,
                    enabled = !state.signingIn,
                    onClick = { state.signInMicrosoft() },
                )
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("Офлайн-режим")
                Text(
                    "Работает только на серверах с online-mode=false. Скины и Realms недоступны.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JuxColors.TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    JuxTextField(
                        value = nickname,
                        onValueChange = {
                            nickname = it
                            nicknameError = null
                        },
                        placeholder = "Никнейм",
                        isError = nicknameError != null,
                        onSubmit = addOffline,
                        modifier = Modifier.width(240.dp),
                    )
                    JuxButton("Добавить", onClick = addOffline, enabled = nickname.isNotBlank())
                }
                nicknameError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = JuxColors.Danger,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("Аккаунты (${accounts.size})")
                if (accounts.isEmpty()) {
                    Text(
                        "Пока пусто.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JuxColors.TextMuted,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        accounts.forEach { account ->
                            AccountRow(
                                account = account,
                                isSelected = account.uuid == selected?.uuid,
                                onSelect = { state.selectAccount(account.uuid) },
                                onRemove = { state.removeAccount(account.uuid) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            isSelected -> JuxColors.Accent.copy(alpha = 0.10f)
            hovered -> JuxColors.SurfaceHigh
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "accountBackground",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JuxDimens.CornerMedium))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkinHead(account, 32.dp)
        Column(Modifier.weight(1f)) {
            Text(
                account.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (isSelected) JuxColors.Accent else JuxColors.Text,
                fontWeight = FontWeight.Medium,
            )
            Text(
                when {
                    account.isOffline -> "Вход не нужен"
                    account.isExpired -> "Сессия обновится при запуске"
                    else -> "Сессия активна"
                },
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.TextMuted,
            )
        }
        if (account.isOffline) Tag("Офлайн", JuxColors.Warning) else Tag("Лицензия", JuxColors.Accent)
        if (isSelected) {
            Icon(Icons.Default.Check, null, tint = JuxColors.Accent, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Delete, "Удалить", tint = JuxColors.TextMuted, modifier = Modifier.size(16.dp))
        }
    }
}
