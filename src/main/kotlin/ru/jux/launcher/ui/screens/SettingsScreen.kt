package ru.jux.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import ru.jux.launcher.core.Backdrop
import ru.jux.launcher.core.DEFAULT_ACCENT
import ru.jux.launcher.core.Language
import ru.jux.launcher.core.LauncherSettings
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.SettingsDefaults
import ru.jux.launcher.core.ThemeMode
import ru.jux.launcher.core.VerifyCache
import ru.jux.launcher.core.tr
import ru.jux.launcher.logs.LogSource
import ru.jux.launcher.net.Downloader
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.components.ChoiceChip
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.JuxSwitch
import ru.jux.launcher.ui.components.LabeledRow
import ru.jux.launcher.ui.components.Panel
import ru.jux.launcher.ui.components.SectionTitle
import ru.jux.launcher.ui.components.WithTooltip
import ru.jux.launcher.ui.dialogs.ColorPickerDialog
import ru.jux.launcher.ui.dialogs.hexOf
import ru.jux.launcher.ui.components.formatMemory
import ru.jux.launcher.ui.components.formatTotalMemory
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import ru.jux.launcher.ui.theme.PillShape
import ru.jux.launcher.ui.theme.colorOf
import ru.jux.launcher.ui.theme.paletteOf

@Composable
fun SettingsScreen(state: LauncherState) {
    val settings by Settings.state.collectAsState()
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().padding(JuxDimens.Gutter).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LanguagePanel(settings)
        AppearancePanel(state, settings)
        MemoryPanel(settings)

        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle(tr("Список версий", "Version list"))
                LabeledRow(tr("Показывать снапшоты", "Show snapshots")) {
                    JuxSwitch(settings.showSnapshots) { checked ->
                        Settings.update { it.copy(showSnapshots = checked) }
                    }
                }
                LabeledRow(tr("Показывать alpha и beta", "Show alpha and beta")) {
                    JuxSwitch(settings.showOldVersions) { checked ->
                        Settings.update { it.copy(showOldVersions = checked) }
                    }
                }
            }
        }

        JvmArgumentsPanel(settings)
        DownloadsPanel(state, settings)
    }
}

@Composable
private fun LanguagePanel(settings: LauncherSettings) {
    Panel(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle("Язык · Language")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Language.entries.forEach { language ->
                    ChoiceChip(language.title, selected = settings.language == language, onClick = {
                        Settings.update { it.copy(language = language) }
                    })
                }
            }
        }
    }
}

private enum class ColorSlot(private val ru: String, private val en: String) {
    ACCENT("Акцент", "Accent"), BACKDROP("Цвет фона", "Background color");

    val title: String get() = tr(ru, en)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearancePanel(state: LauncherState, settings: LauncherSettings) {
    var picking by remember { mutableStateOf<ColorSlot?>(null) }
    Panel(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle(tr("Оформление", "Appearance"))
            FieldLabel(tr("Тема", "Theme"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val palette = paletteOf(mode)
                    SwatchChip(mode.title, fill = palette.background, ring = palette.surfaceHigh, selected = settings.theme == mode) {
                        Settings.update { it.copy(theme = mode) }
                    }
                }
            }

            FieldLabel(tr("Акцент", "Accent"), top = 16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SwatchChip(hexOf(settings.accentColor), fill = colorOf(settings.accentColor), selected = false) { picking = ColorSlot.ACCENT }
                if (settings.accentColor != DEFAULT_ACCENT) {
                    ChoiceChip(tr("Сбросить", "Reset"), selected = false, onClick = { Settings.update { it.copy(accentColor = DEFAULT_ACCENT) } })
                }
            }

            FieldLabel(tr("Фон", "Background"), top = 16.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(Backdrop.NONE.title, selected = settings.backdrop == Backdrop.NONE, onClick = {
                    Settings.update { it.copy(backdrop = Backdrop.NONE) }
                })
                val colored = settings.backdrop == Backdrop.COLOR
                SwatchChip(if (colored) hexOf(settings.backdropColor) else tr("Свой цвет…", "Custom color…"), fill = colorOf(settings.backdropColor), selected = colored) {
                    picking = ColorSlot.BACKDROP
                }
                val pictured = settings.backdrop == Backdrop.IMAGE
                ChoiceChip(if (pictured) tr("Своя картинка", "Custom image") else tr("Своя картинка…", "Custom image…"), selected = pictured, icon = JuxIcons.Image, onClick = state::chooseBackdropImage)
            }
            if (settings.backdrop == Backdrop.IMAGE) {
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("Затемнение: ${(settings.backdropDim * 100).roundToInt()}%", "Dim: ${(settings.backdropDim * 100).roundToInt()}%"),
                    style = MaterialTheme.typography.bodySmall,
                    color = JuxColors.TextMuted,
                )
                Slider(
                    value = settings.backdropDim,
                    onValueChange = { value -> Settings.update { it.copy(backdropDim = value) } },
                    valueRange = 0.2f..0.9f,
                    colors = sliderColors(),
                )
            }
        }
    }
    picking?.let { slot ->
        ColorPickerDialog(
            title = slot.title,
            initial = if (slot == ColorSlot.ACCENT) settings.accentColor else settings.backdropColor,
            onDismiss = { picking = null },
            onPick = { rgb ->
                picking = null
                Settings.update {
                    if (slot == ColorSlot.ACCENT) it.copy(accentColor = rgb) else it.copy(backdrop = Backdrop.COLOR, backdropColor = rgb)
                }
            },
        )
    }
}

@Composable
private fun FieldLabel(text: String, top: Dp = 0.dp) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = JuxColors.Text, modifier = Modifier.padding(top = top, bottom = 8.dp))
}

@Composable
private fun SwatchChip(label: String, fill: Color, selected: Boolean, ring: Color = JuxColors.Outline, onClick: () -> Unit) {
    Row(
        Modifier
            .height(38.dp)
            .clip(PillShape)
            .background(if (selected) JuxColors.AccentSoft else JuxColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, if (selected) JuxColors.Accent else ring, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) JuxColors.Accent else JuxColors.Text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = JuxColors.Accent,
    activeTrackColor = JuxColors.Accent,
    inactiveTrackColor = JuxColors.SurfaceHigh,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryPanel(settings: LauncherSettings) {
    val total = remember { SettingsDefaults.totalSystemMemoryMb() }
    val presets = remember { SettingsDefaults.memoryPresets(total) }

    Panel(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle(tr("Память", "Memory"))
            Text(
                tr(
                    "${formatMemory(settings.memoryMb)} для игры · в компьютере ${formatTotalMemory(total)}",
                    "${formatMemory(settings.memoryMb)} for the game · ${formatTotalMemory(total)} installed",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = JuxColors.Text,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { mb ->
                    ChoiceChip(formatMemory(mb), selected = settings.memoryMb == mb, onClick = {
                        Settings.update { it.copy(memoryMb = mb) }
                    })
                }
                if (settings.memoryMb !in presets) {
                    ChoiceChip(tr("${formatMemory(settings.memoryMb)} (своё)", "${formatMemory(settings.memoryMb)} (custom)"), selected = true, onClick = {})
                }
            }
        }
    }
}

@Composable
private fun JvmArgumentsPanel(settings: LauncherSettings) {
    Panel(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle(tr("Аргументы JVM", "JVM arguments"))
            OutlinedTextField(
                value = settings.jvmArgs,
                onValueChange = { value -> Settings.update { it.copy(jvmArgs = value) } },
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(JuxDimens.CornerMedium),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = JuxColors.Accent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = JuxColors.SurfaceHigh,
                    unfocusedContainerColor = JuxColors.SurfaceHigh,
                ),
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
            Spacer(Modifier.height(8.dp))
            JuxButton(tr("Сбросить к рекомендуемым", "Reset to recommended"), onClick = {
                Settings.update { it.copy(jvmArgs = SettingsDefaults.JVM_ARGS) }
            })
        }
    }
}

@Composable
private fun DownloadsPanel(state: LauncherState, settings: LauncherSettings) {
    Panel(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle(tr("Загрузка и целостность", "Downloads & integrity"))
            val threads = settings.downloadConcurrency.takeIf { it > 0 } ?: Downloader.DEFAULT_CONCURRENCY
            Text(tr("Потоков загрузки: $threads", "Download threads: $threads"), style = MaterialTheme.typography.bodyMedium, color = JuxColors.Text)
            Slider(
                value = threads.toFloat(),
                onValueChange = { value -> Settings.update { it.copy(downloadConcurrency = value.toInt()) } },
                valueRange = 4f..32f,
                colors = sliderColors(),
            )

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WithTooltip(tr("Если игра вылетает из-за битых файлов", "If the game crashes because of broken files")) {
                    JuxButton(tr("Перепроверить файлы", "Re-verify files"), onClick = {
                        VerifyCache.clear()
                        state.inform(tr("Кэш сброшен — файлы перепроверятся при запуске", "Cache cleared — files will be re-verified on launch"))
                    })
                }
                JuxButton(tr("Папка лаунчера", "Launcher folder"), icon = JuxIcons.Folder, onClick = { state.openFolder(Paths.root) })
                JuxButton(tr("Лог лаунчера", "Launcher log"), icon = JuxIcons.Log, onClick = { state.showLogs(null, LogSource.LAUNCHER) })
            }
        }
    }
}
