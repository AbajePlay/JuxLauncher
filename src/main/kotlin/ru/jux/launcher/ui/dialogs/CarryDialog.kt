package ru.jux.launcher.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.jux.launcher.instance.CarryPlan
import ru.jux.launcher.instance.CarrySource
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.ChoiceChip
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDialog
import ru.jux.launcher.ui.components.WithTooltip
import ru.jux.launcher.ui.components.formatBytes
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CarryDialog(state: LauncherState, modal: Modal.Carry) {
    val entry = modal.entry
    var source by remember(modal) { mutableStateOf(modal.sources.first()) }
    var plan by remember(modal, source) { mutableStateOf(defaultPlan(modal, source)) }
    DisposableEffect(modal) {
        onDispose { if (modal.firstLaunch) state.dismissCarry() }
    }
    val close: () -> Unit = if (modal.firstLaunch) state::dismissCarry else ({ state.modal = null })
    val worlds = worldsOf(source)
    val copyBytes = (if (plan.packs) source.packsBytes else 0L) +
        source.worlds.filter { it.folder in plan.worlds }.sumOf { it.bytes }

    JuxDialog(
        title = if (modal.firstLaunch) "Перенести в ${entry.label}?" else "Перенести в ${entry.label}",
        onDismiss = close,
        width = 560.dp,
        actions = {
            Text(
                if (copyBytes > 0) "Скопируется ${formatBytes(copyBytes)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.TextMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (modal.firstLaunch) {
                JuxButton("Не переносить", onClick = { state.answerCarry(source, CarryPlan.NONE) })
                JuxButton(
                    "Перенести и играть",
                    style = ButtonStyle.PRIMARY,
                    enabled = !plan.isEmpty,
                    onClick = { state.answerCarry(source, plan) },
                )
            } else {
                JuxButton("Отмена", onClick = close)
                JuxButton(
                    "Перенести",
                    style = ButtonStyle.PRIMARY,
                    enabled = !plan.isEmpty,
                    onClick = { state.carryInto(entry, source, plan) },
                )
            }
        },
    ) {
        if (modal.sources.size > 1) {
            Text("Откуда", style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                modal.sources.forEach { option ->
                    WithTooltip(OFFICIAL_HINT.takeIf { option.official }) {
                        ChoiceChip(option.label, selected = option == source, onClick = { source = option })
                    }
                }
            }
        } else {
            WithTooltip(OFFICIAL_HINT.takeIf { source.official }) {
                Text("Из ${source.label}", style = MaterialTheme.typography.bodyMedium, color = JuxColors.TextSoft)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (source.options) {
            CheckRow("Настройки и управление", plan.options) { plan = plan.copy(options = !plan.options) }
        }
        if (source.servers > 0) {
            CheckRow("Серверы", plan.servers) { plan = plan.copy(servers = !plan.servers) }
        }
        if (source.packs.isNotEmpty()) {
            val shaders = source.packs.any { it.path.startsWith("shaderpacks/") }
            CheckRow(if (shaders) "Ресурспаки и шейдеры" else "Ресурспаки", plan.packs) { plan = plan.copy(packs = !plan.packs) }
        }
        if (worlds.isNotEmpty()) {
            val on = plan.worlds.isNotEmpty()
            CheckRow("Миры", on) { plan = plan.copy(worlds = if (on) emptySet() else worlds) }
        }
    }
}

private const val OFFICIAL_HINT = "Папка официального лаунчера и TLauncher"

private fun worldsOf(source: CarrySource): Set<String> = source.worlds.filter { !it.present }.map { it.folder }.toSet()

private fun defaultPlan(modal: Modal.Carry, source: CarrySource) = CarryPlan(
    options = source.options && !(modal.firstLaunch && modal.targetHasOptions),
    servers = source.servers > 0,
    packs = source.packs.isNotEmpty(),
    worlds = worldsOf(source),
)

@Composable
private fun CheckRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JuxDimens.CornerSmall))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = JuxColors.Accent,
                    uncheckedColor = JuxColors.TextMuted,
                    checkmarkColor = JuxColors.Background,
                ),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = JuxColors.Text)
    }
}
