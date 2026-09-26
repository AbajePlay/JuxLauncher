package ru.jux.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.jux.launcher.core.LauncherSettings
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.SettingsDefaults
import ru.jux.launcher.core.VerifyCache
import ru.jux.launcher.logs.LogSource
import ru.jux.launcher.net.Downloader
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.components.Banner
import ru.jux.launcher.ui.components.ChoiceChip
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.JuxSwitch
import ru.jux.launcher.ui.components.LabeledRow
import ru.jux.launcher.ui.components.Panel
import ru.jux.launcher.ui.components.SectionTitle
import ru.jux.launcher.ui.components.formatMemory
import ru.jux.launcher.ui.components.formatTotalMemory
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens

@Composable
fun SettingsScreen(state: LauncherState) {
    val settings by Settings.state.collectAsState()
    val scroll = rememberScrollState()

    LaunchedEffect(state.notice, state.error) {
        if (state.notice != null || state.error != null) scroll.animateScrollTo(0)
    }

    Column(
        Modifier.fillMaxSize().padding(JuxDimens.Gutter).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Banner(state.error, isError = true, onDismiss = { state.error = null })
        Banner(state.notice, isError = false, onDismiss = { state.notice = null })

        MemoryPanel(settings)

        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("Запуск")
                LabeledRow(
                    "Полностью закрывать лаунчер при запуске игры",
                    "Выключено — лаунчер скроется и вернётся, когда игра закроется",
                ) {
                    JuxSwitch(settings.closeOnLaunch) { checked ->
                        Settings.update { it.copy(closeOnLaunch = checked) }
                    }
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("Список версий")
                LabeledRow("Показывать снапшоты") {
                    JuxSwitch(settings.showSnapshots) { checked ->
                        Settings.update { it.copy(showSnapshots = checked) }
                    }
                }
                LabeledRow("Показывать alpha и beta") {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryPanel(settings: LauncherSettings) {
    val total = remember { SettingsDefaults.totalSystemMemoryMb() }
    val presets = remember { SettingsDefaults.memoryPresets(total) }
    val recommended = remember { SettingsDefaults.memoryMb() }

    Panel(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle("Память")
            Text(
                "${formatMemory(settings.memoryMb)} для игры · в компьютере ${formatTotalMemory(total)}",
                style = MaterialTheme.typography.bodyMedium,
                color = JuxColors.Text,
            )
            Text(
                "Больше — не всегда лучше: сверх 8 ГБ паузы сборщика мусора становятся заметнее, " +
                    "чем выигрыш от объёма. Для этого компьютера советуем ${formatMemory(recommended)}.",
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { mb ->
                    ChoiceChip(formatMemory(mb), selected = settings.memoryMb == mb, onClick = {
                        Settings.update { it.copy(memoryMb = mb) }
                    })
                }
                if (settings.memoryMb !in presets) {
                    ChoiceChip("${formatMemory(settings.memoryMb)} (своё)", selected = true, onClick = {})
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Отдельной сборке можно задать свою память: ⋮ на карточке «К запуску» → «Настройки сборки».",
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.TextMuted,
            )
        }
    }
}

@Composable
private fun JvmArgumentsPanel(settings: LauncherSettings) {
    Panel(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle("Аргументы JVM")
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
            JuxButton("Сбросить к рекомендуемым", onClick = {
                Settings.update { it.copy(jvmArgs = SettingsDefaults.JVM_ARGS) }
            })
        }
    }
}

@Composable
private fun DownloadsPanel(state: LauncherState, settings: LauncherSettings) {
    Panel(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle("Загрузка и целостность")
            val threads = settings.downloadConcurrency.takeIf { it > 0 } ?: Downloader.DEFAULT_CONCURRENCY
            Text("Потоков загрузки: $threads", style = MaterialTheme.typography.bodyMedium, color = JuxColors.Text)
            Slider(
                value = threads.toFloat(),
                onValueChange = { value -> Settings.update { it.copy(downloadConcurrency = value.toInt()) } },
                valueRange = 4f..32f,
                colors = SliderDefaults.colors(
                    thumbColor = JuxColors.Accent,
                    activeTrackColor = JuxColors.Accent,
                    inactiveTrackColor = JuxColors.SurfaceHigh,
                ),
            )

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                JuxButton("Сбросить кэш проверки", onClick = {
                    VerifyCache.clear()
                    state.notice = "Кэш проверки сброшен — следующий запуск заново пересчитает хеши всех файлов"
                })
                JuxButton("Открыть папку лаунчера", icon = JuxIcons.Folder, onClick = { state.openFolder(Paths.root) })
                JuxButton("Лог лаунчера", icon = JuxIcons.Log, onClick = { state.showLogs(null, LogSource.LAUNCHER) })
            }
            Text(
                "Сброс кэша проверки помогает, когда игра вылетает из-за повреждённых файлов: " +
                    "лаунчер перепроверит и докачает всё, что не сходится.",
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
