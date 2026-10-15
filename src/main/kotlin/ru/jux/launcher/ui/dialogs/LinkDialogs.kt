package ru.jux.launcher.ui.dialogs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.jux.launcher.core.Shell
import ru.jux.launcher.link.LinkContent
import ru.jux.launcher.mods.ContentKind
import ru.jux.launcher.mods.Modrinth
import ru.jux.launcher.ui.InstallPlan
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.ModIcon
import ru.jux.launcher.ui.VersionEntry
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDialog
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.Tag
import ru.jux.launcher.ui.components.WithTooltip
import ru.jux.launcher.ui.components.loaderColor
import ru.jux.launcher.ui.screens.plural
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens

@Composable
internal fun JoinDialog(state: LauncherState, modal: Modal.Join) {
    val link = modal.link
    var choice by remember(modal) { mutableStateOf(modal.choices.firstOrNull()) }
    val close = { state.modal = null }

    JuxDialog(
        title = "Играть на ${link.title}",
        subtitle = listOfNotNull(link.address.takeIf { link.name != null }, link.version?.let { "Minecraft $it" })
            .joinToString(" · ")
            .ifEmpty { null },
        onDismiss = close,
        width = 520.dp,
        actions = {
            JuxButton("Отмена", onClick = close)
            JuxButton(
                "Играть",
                icon = Icons.Default.PlayArrow,
                style = ButtonStyle.PRIMARY,
                enabled = choice != null && !state.busy && !state.gameRunning,
                onClick = { choice?.let { state.joinServer(link, it) } },
            )
        },
    ) {
        if (modal.choices.isEmpty()) {
            Hint(modal.note ?: "Нет ни одной версии игры. Проверь интернет и открой ссылку ещё раз", JuxColors.Danger)
            return@JuxDialog
        }
        EntryChoices(state, "Сборка", modal.choices, choice) { choice = it }
        Spacer(Modifier.height(14.dp))
        when {
            state.gameRunning -> Hint("Игра уже запущена. Закрой её, и отсюда сразу зайдёшь на сервер", JuxColors.Warning)
            state.busy -> Hint(busyText(state), JuxColors.Warning)
            modal.note != null -> Hint(modal.note, JuxColors.Warning)
            else -> Hint("Игра сразу зайдёт на сервер, а адрес останется в списке серверов этой сборки")
        }
    }
}

@Composable
internal fun InstallDialog(state: LauncherState, modal: Modal.Install) {
    val link = modal.link
    val plan = modal.plan
    val project = (plan as? InstallPlan.Content)?.project ?: (plan as? InstallPlan.Pack)?.project
    val title = link.name ?: project?.title ?: link.project
    val targets = (plan as? InstallPlan.Content)?.targets.orEmpty()
    var choice by remember(plan) { mutableStateOf(targets.firstOrNull()) }
    val close = { state.modal = null }
    val modrinth: () -> Unit = { Shell.browse(link.content.page(project?.slug?.ifBlank { null } ?: link.project)) }

    JuxDialog(
        title = when (link.content) {
            LinkContent.DATAPACK -> "Датапак $title"
            LinkContent.MODPACK -> "Установить сборку $title"
            else -> "Установить $title"
        },
        subtitle = listOfNotNull("${kindLabel(link.content)} с Modrinth", project?.let(::loadersOf)).joinToString(" · "),
        onDismiss = close,
        width = 560.dp,
        actions = {
            when {
                link.content == LinkContent.DATAPACK || plan is InstallPlan.Failed -> {
                    JuxButton("Закрыть", onClick = close)
                    JuxButton("Открыть на Modrinth", icon = JuxIcons.OpenInNew, style = ButtonStyle.PRIMARY, onClick = {
                        modrinth()
                        close()
                    })
                }
                plan is InstallPlan.Pack && plan.installed != null -> {
                    JuxButton("Отмена", onClick = close)
                    JuxButton("Играть", icon = Icons.Default.PlayArrow, style = ButtonStyle.PRIMARY, enabled = !state.busy, onClick = {
                        close()
                        state.selectEntry(state.entryFor(plan.installed))
                        state.play()
                    })
                }
                else -> {
                    JuxButton("Отмена", onClick = close)
                    JuxButton(
                        "Установить",
                        icon = JuxIcons.Download,
                        style = ButtonStyle.PRIMARY,
                        enabled = !state.busy && (plan is InstallPlan.Pack || (plan is InstallPlan.Content && choice != null)),
                        onClick = {
                            when (plan) {
                                is InstallPlan.Pack -> state.installPackFromLink(plan.project)
                                is InstallPlan.Content -> choice?.let { state.installFromLink(it, plan.project, plan.kind) }
                                else -> Unit
                            }
                        },
                    )
                }
            }
        },
    ) {
        if (link.content == LinkContent.DATAPACK) {
            Hint(
                "Датапак ставится в мир, а не в сборку, и лаунчер пока так не умеет. " +
                    "Скачай его на Modrinth и положи в папку datapacks мира или добавь при создании мира.",
                JuxColors.Text,
            )
            return@JuxDialog
        }
        when (plan) {
            null -> Hint("Ищу $title на Modrinth…")
            is InstallPlan.Failed -> Hint(plan.message, JuxColors.Danger)
            is InstallPlan.Pack -> {
                ProjectCard(plan.project, modrinth)
                Spacer(Modifier.height(14.dp))
                Hint(
                    if (plan.installed != null) "Сборка уже установлена: ${plan.installed.title} ${plan.installed.version}"
                    else "Скачаю сборку со всеми модами и настройками в отдельную папку, миры других версий её не заденут",
                )
            }
            is InstallPlan.Content -> {
                ProjectCard(plan.project, modrinth)
                Spacer(Modifier.height(16.dp))
                EntryChoices(state, "Куда поставить", plan.targets, choice) { choice = it }
                Spacer(Modifier.height(14.dp))
                when {
                    state.busy -> Hint(busyText(state), JuxColors.Warning)
                    plan.unfit > 0 -> Hint(
                        "Ещё ${plan.unfit} ${plural(plan.unfit, "сборка не подходит", "сборки не подходят", "сборок не подходят")} " +
                            "по версии игры или загрузчику",
                    )
                    else -> Hint(
                        when (plan.kind) {
                            ContentKind.MOD -> "Нужные ему моды поставятся сами"
                            ContentKind.SHADER -> "Шейдеры работают через Iris, он поставится сам"
                            ContentKind.RESOURCE_PACK -> "Ресурспак сразу включится в настройках игры"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: Modrinth.Project, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JuxDimens.CornerMedium))
            .background(JuxColors.Background)
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModIcon(project.iconUrl)
        Text(
            project.description.ifBlank { project.title },
            style = MaterialTheme.typography.bodySmall,
            color = JuxColors.Text.copy(alpha = 0.8f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        WithTooltip("Открыть на Modrinth") {
            IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                Icon(JuxIcons.OpenInNew, "Открыть на Modrinth", tint = JuxColors.TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private val LOADER_NAMES = mapOf(
    "fabric" to "Fabric",
    "quilt" to "Quilt",
    "forge" to "Forge",
    "neoforge" to "NeoForge",
    "iris" to "Iris",
    "optifine" to "OptiFine",
)

private fun loadersOf(project: Modrinth.Project): String? =
    project.loaders.mapNotNull(LOADER_NAMES::get).joinToString(", ").ifEmpty { null }

@Composable
private fun EntryChoices(
    state: LauncherState,
    label: String,
    entries: List<VersionEntry>,
    selected: VersionEntry?,
    onSelect: (VersionEntry) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier.fillMaxWidth().heightIn(max = 252.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entries.forEach { entry -> EntryChoice(state, entry, entry.key == selected?.key) { onSelect(entry) } }
    }
}

@Composable
private fun EntryChoice(state: LauncherState, entry: VersionEntry, selected: Boolean, onClick: () -> Unit) {
    val installed = entry.pack != null || state.isEntryInstalled(entry)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> JuxColors.AccentSoft
            hovered -> JuxColors.SurfaceHigh
            else -> Color.Transparent
        },
        animationSpec = tween(160),
        label = "choiceBackground",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JuxDimens.CornerMedium))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (installed) JuxColors.Accent else Color.Transparent))
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
            Tag(if (entry.pack != null) "${entry.id} ${entry.loader.label}" else entry.loader.label, loaderColor(entry.loader))
        }
        Text(
            when {
                entry.pack != null -> "сборка"
                installed -> "установлена"
                else -> "новая"
            },
            style = MaterialTheme.typography.bodySmall,
            color = JuxColors.TextMuted,
        )
    }
}

@Composable
private fun Hint(text: String, color: Color = JuxColors.TextMuted) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

private fun busyText(state: LauncherState): String =
    "Дождись окончания загрузки ${state.busyEntry?.label.orEmpty()}".trimEnd()

private fun kindLabel(content: LinkContent): String = when (content) {
    LinkContent.MOD -> "Мод"
    LinkContent.SHADER -> "Шейдер"
    LinkContent.RESOURCE_PACK -> "Ресурспак"
    LinkContent.DATAPACK -> "Датапак"
    LinkContent.MODPACK -> "Сборка"
}
