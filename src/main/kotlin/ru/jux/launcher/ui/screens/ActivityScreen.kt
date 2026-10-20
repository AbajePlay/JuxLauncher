package ru.jux.launcher.ui.screens

import ru.jux.launcher.core.plural
import ru.jux.launcher.core.tr
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.delay
import ru.jux.launcher.activity.ActivityStats
import ru.jux.launcher.activity.PlaySession
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.formatDayMonth
import ru.jux.launcher.ui.components.formatMoment
import ru.jux.launcher.ui.components.formatMonthLabel
import ru.jux.launcher.ui.components.Tag
import ru.jux.launcher.ui.components.loaderColor
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import ru.jux.launcher.ui.theme.PillShape
import ru.jux.launcher.ui.theme.softShadow

private val CELL = 13.dp
private val GAP = 4.dp
private val DAY_LABELS = 26.dp
private const val REFRESH_MILLIS = 60_000L
private val SESSION_ROW = 38.dp
private val SESSION_ROW_MAX = 46.dp
private val RECENT_OVERHEAD = 44.dp + 26.dp + 10.dp
private const val RECENT_MIN = 3

@Composable
fun ActivityScreen(state: LauncherState) {
    LaunchedEffect(Unit) {
        while (true) {
            state.awaitWindowShown()
            state.loadActivity()
            delay(REFRESH_MILLIS)
        }
    }
    val stats = state.activity

    Column(
        Modifier.fillMaxSize().padding(JuxDimens.Gutter).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            tr("Активность", "Activity"),
            style = MaterialTheme.typography.headlineSmall,
            color = JuxColors.Text,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        StatRow(stats)
        HeatmapCard(stats)
        val density = LocalDensity.current
        var leftHeight by remember { mutableStateOf(0.dp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Column(
                Modifier.weight(1f).onSizeChanged { leftHeight = with(density) { it.height.toDp() } },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                VersionsCard(stats, Modifier.fillMaxWidth())
                ServersCard(stats, Modifier.fillMaxWidth())
            }
            val fits = ((leftHeight - RECENT_OVERHEAD) / SESSION_ROW).toInt().coerceIn(RECENT_MIN, ActivityStats.RECENT_MAX)
            val shown = minOf(fits, stats?.recent?.size ?: 0)
            val rowHeight = if (shown == fits && leftHeight > 0.dp) {
                ((leftHeight - RECENT_OVERHEAD) / fits).coerceIn(SESSION_ROW, SESSION_ROW_MAX)
            } else {
                SESSION_ROW
            }
            RecentCard(stats, fits, rowHeight, Modifier.weight(1.25f))
        }
    }
}

@Composable
private fun StatRow(stats: ActivityStats?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatCard(
            tr("Всего в игре", "Total played"),
            stats?.let { formatPlayTime(it.totalMillis) } ?: "…",
            JuxIcons.Clock,
            JuxColors.Accent,
        )
        StatCard(
            tr("Эта неделя", "This week"),
            stats?.let { formatPlayTime(it.weekMillis) } ?: "…",
            JuxIcons.Calendar,
            JuxColors.Info,
        )
        StatCard(
            tr("Серия", "Streak"),
            stats?.let { "${it.streak} ${daysWord(it.streak)}" } ?: "…",
            JuxIcons.Flame,
            JuxColors.Warning,
        )
        StatCard(
            tr("Рекорд", "Longest"),
            stats?.longest?.let { formatPlayTime(it.millis) } ?: if (stats == null) "…" else "—",
            JuxIcons.Trophy,
            JuxColors.Success,
        )
    }
}

@Composable
private fun RowScope.StatCard(label: String, value: String, icon: ImageVector, tone: Color) {
    val shape = RoundedCornerShape(JuxDimens.CornerCard)
    Column(
        Modifier
            .weight(1f)
            .softShadow(shape, 12.dp)
            .clip(shape)
            .background(JuxColors.Surface)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(tone.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tone, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = JuxColors.TextMuted, maxLines = 1)
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.height(34.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                value,
                fontSize = when {
                    value.length <= 7 -> 26.sp
                    value.length <= 10 -> 22.sp
                    else -> 19.sp
                },
                fontWeight = FontWeight.Black,
                color = JuxColors.Text,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(JuxDimens.CornerLarge)
    Box(
        modifier
            .softShadow(shape, 12.dp)
            .clip(shape)
            .background(JuxColors.Surface)
            .padding(22.dp),
    ) { content() }
}

@Composable
private fun HeatmapCard(stats: ActivityStats?) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Heatmap(stats)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(tr("Меньше", "Less"), style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
                Spacer(Modifier.width(8.dp))
                (0..4).forEach { level ->
                    Box(Modifier.padding(horizontal = 2.dp).size(CELL).clip(RoundedCornerShape(3.dp)).background(levelColor(level)))
                }
                Spacer(Modifier.width(8.dp))
                Text(tr("Больше", "More"), style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Heatmap(stats: ActivityStats?) {
    val today = stats?.today ?: LocalDate.now()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val weeks = ((maxWidth - DAY_LABELS) / (CELL + GAP)).toInt().coerceIn(8, 53)
        val firstMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
        val columns = (0 until weeks).map { week -> (0..6).map { firstMonday.plusDays(week * 7L + it) } }

        Column {
            Row(Modifier.padding(start = DAY_LABELS).height(16.dp)) {
                var previousMonth = -1
                columns.forEach { days ->
                    val month = days.first().monthValue
                    Box(Modifier.width(CELL + GAP)) {
                        if (month != previousMonth && days.first().dayOfMonth <= 7) {
                            Text(
                                formatMonthLabel(month),
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = JuxColors.TextMuted,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                            )
                        }
                    }
                    previousMonth = month
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Column(Modifier.width(DAY_LABELS), verticalArrangement = Arrangement.spacedBy(GAP)) {
                    listOf(tr("пн", "Mon"), "", tr("ср", "Wed"), "", tr("пт", "Fri"), "", "").forEach { label ->
                        Box(Modifier.height(CELL), contentAlignment = Alignment.CenterStart) {
                            if (label.isNotEmpty()) {
                                Text(
                                    label,
                                    style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                                    color = JuxColors.TextMuted,
                                    modifier = Modifier.wrapContentHeight(unbounded = true),
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    columns.forEach { days ->
                        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                            days.forEach { day ->
                                val millis = stats?.days?.get(day) ?: 0L
                                val future = day.isAfter(today)
                                TooltipArea(
                                    tooltip = {
                                        Text(
                                            "${formatDayMonth(day, long = true)} · " +
                                                if (millis > 0) formatPlayTime(millis) else tr("без игры", "no play"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = JuxColors.Text,
                                            modifier = Modifier
                                                .softShadow(RoundedCornerShape(JuxDimens.CornerSmall), 12.dp)
                                                .background(JuxColors.SurfaceHigh, RoundedCornerShape(JuxDimens.CornerSmall))
                                                .padding(horizontal = 10.dp, vertical = 7.dp),
                                        )
                                    },
                                    delayMillis = 120,
                                    tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
                                ) {
                                    Box(
                                        Modifier
                                            .size(CELL)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(if (future) Color.Transparent else levelColor(levelOf(millis)))
                                            .then(
                                                if (day == today) Modifier.border(1.5.dp, JuxColors.Accent, RoundedCornerShape(3.dp))
                                                else Modifier
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionsCard(stats: ActivityStats?, modifier: Modifier) {
    Card(modifier) {
        Column {
            CardTitle(tr("Любимые версии", "Top versions"), JuxIcons.Layers, JuxColors.Info)
            Spacer(Modifier.height(14.dp))
            val versions = stats?.versions.orEmpty()
            if (versions.isEmpty()) {
                Text(if (stats == null) tr("Считаю…", "Counting…") else tr("Пока пусто", "Nothing yet"), style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
            }
            val max = versions.maxOfOrNull { it.millis }?.coerceAtLeast(1) ?: 1
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                versions.forEach { version ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                version.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = JuxColors.Text,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(formatPlayTime(version.millis), style = MaterialTheme.typography.bodySmall, color = JuxColors.TextSoft)
                        }
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(PillShape).background(JuxColors.SurfaceHigh)) {
                            Box(
                                Modifier
                                    .fillMaxWidth((version.millis.toFloat() / max).coerceIn(0.04f, 1f))
                                    .fillMaxHeight()
                                    .clip(PillShape)
                                    .background(loaderColor(version.loader)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServersCard(stats: ActivityStats?, modifier: Modifier) {
    Card(modifier) {
        Column {
            CardTitle(tr("Любимые серверы", "Top servers"), JuxIcons.Server, JuxColors.Success)
            Spacer(Modifier.height(14.dp))
            val servers = stats?.servers.orEmpty()
            if (servers.isEmpty()) {
                Text(
                    if (stats == null) tr("Считаю…", "Counting…") else tr("Пока не заходил на серверы", "No servers joined yet"),
                    style = MaterialTheme.typography.bodySmall,
                    color = JuxColors.TextMuted,
                )
            }
            val max = servers.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                servers.forEach { (address, millis) ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                address,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = JuxColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(formatPlayTime(millis), style = MaterialTheme.typography.bodySmall, color = JuxColors.TextSoft)
                        }
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(PillShape).background(JuxColors.SurfaceHigh)) {
                            Box(
                                Modifier
                                    .fillMaxWidth((millis.toFloat() / max).coerceIn(0.04f, 1f))
                                    .fillMaxHeight()
                                    .clip(PillShape)
                                    .background(JuxColors.Success),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCard(stats: ActivityStats?, rows: Int, rowHeight: Dp, modifier: Modifier) {
    Card(modifier) {
        Column {
            CardTitle(tr("Последние сессии", "Recent sessions"), JuxIcons.History, JuxColors.Accent)
            Spacer(Modifier.height(10.dp))
            val recent = stats?.recent.orEmpty().take(rows)
            if (recent.isEmpty()) {
                Text(if (stats == null) tr("Считаю…", "Counting…") else tr("Пока пусто", "Nothing yet"), style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
            }
            recent.forEach { session -> SessionRow(session, stats?.today ?: LocalDate.now(), rowHeight) }
        }
    }
}

@Composable
private fun CardTitle(text: String, icon: ImageVector, tone: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(tone.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tone, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = JuxColors.TextMuted)
    }
}

@Composable
private fun SessionRow(session: PlaySession, today: LocalDate, height: Dp) {
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                formatMoment(session.start, today),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = JuxColors.Text,
                maxLines = 1,
            )
        }
        Tag(session.label, loaderColor(session.loader))
        Text(
            formatPlayTime(session.millis),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = JuxColors.Text,
            modifier = Modifier.width(92.dp).padding(start = 12.dp),
            maxLines = 1,
        )
    }
}

private fun levelOf(millis: Long): Int {
    val minutes = millis / 60_000
    return when {
        millis <= 0 -> 0
        minutes < 30 -> 1
        minutes < 60 -> 2
        minutes < 120 -> 3
        else -> 4
    }
}

private fun levelColor(level: Int): Color = when (level) {
    0 -> JuxColors.SurfaceHigh
    1 -> JuxColors.Accent.copy(alpha = 0.25f).compositeOver(JuxColors.Surface)
    2 -> JuxColors.Accent.copy(alpha = 0.48f).compositeOver(JuxColors.Surface)
    3 -> JuxColors.Accent.copy(alpha = 0.74f).compositeOver(JuxColors.Surface)
    else -> JuxColors.Accent
}

internal fun formatPlayTime(millis: Long): String {
    val minutes = millis / 60_000
    val hours = minutes / 60
    return when {
        minutes < 1 -> tr("< 1 мин", "< 1 min")
        hours == 0L -> tr("$minutes мин", "$minutes min")
        minutes % 60 == 0L -> tr("$hours ч", "$hours h")
        else -> tr("$hours ч ${minutes % 60} мин", "$hours h ${minutes % 60} min")
    }
}

private fun daysWord(count: Int) = plural(count, "день", "дня", "дней", "day", "days")



