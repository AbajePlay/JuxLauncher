package ru.jux.launcher.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.jux.launcher.activity.ActivityStats
import ru.jux.launcher.activity.PlaySession
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.SectionTitle
import ru.jux.launcher.ui.components.Tag
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import ru.jux.launcher.ui.theme.PillShape
import ru.jux.launcher.ui.theme.softShadow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val MONTHS_SHORT = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
private val MONTHS_OF = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val CELL = 13.dp
private val GAP = 4.dp
private val DAY_LABELS = 26.dp
private const val REFRESH_MILLIS = 60_000L

@Composable
fun ActivityScreen(state: LauncherState) {
    LaunchedEffect(Unit) {
        while (true) {
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
            "Активность",
            style = MaterialTheme.typography.headlineSmall,
            color = JuxColors.Text,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        StatRow(stats)
        HeatmapCard(stats)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VersionsCard(stats, Modifier.weight(1f).fillMaxHeight())
            RecentCard(stats, Modifier.weight(1.25f).fillMaxHeight())
        }
    }
}

@Composable
private fun StatRow(stats: ActivityStats?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatCard(
            "Всего в игре",
            stats?.let { formatPlayTime(it.totalMillis) } ?: "…",
            stats?.let { "${it.sessions.size} ${plural(it.sessions.size, "сессия", "сессии", "сессий")} · ${it.activeDays} ${daysWord(it.activeDays)}" }.orEmpty(),
            JuxIcons.Clock,
            JuxColors.Accent,
        )
        StatCard(
            "Эта неделя",
            stats?.let { formatPlayTime(it.weekMillis) } ?: "…",
            stats?.let { if (it.lastWeekMillis > 0) "прошлая — ${formatPlayTime(it.lastWeekMillis)}" else "на прошлой без игры" }.orEmpty(),
            JuxIcons.Calendar,
            JuxColors.Info,
        )
        StatCard(
            "Серия",
            stats?.let { "${it.streak} ${daysWord(it.streak)}" } ?: "…",
            stats?.let { "рекорд — ${it.bestStreak} ${daysWord(it.bestStreak)}" }.orEmpty(),
            JuxIcons.Flame,
            JuxColors.Warning,
        )
        StatCard(
            "Рекорд",
            stats?.longest?.let { formatPlayTime(it.millis) } ?: if (stats == null) "…" else "—",
            stats?.longest?.let { "${dayOf(it.start)} · ${it.label}" }.orEmpty(),
            JuxIcons.Trophy,
            JuxColors.Success,
        )
    }
}

@Composable
private fun RowScope.StatCard(label: String, value: String, hint: String, icon: ImageVector, tone: Color) {
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
        Spacer(Modifier.height(4.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = JuxColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Каждый день", Modifier.weight(1f))
                if (stats != null && stats.sessions.isEmpty()) {
                    Text("Сыграй разок — и тут начнут загораться кубики", style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
                }
            }
            Spacer(Modifier.height(14.dp))
            Heatmap(stats)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text("Меньше", style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
                Spacer(Modifier.width(8.dp))
                (0..4).forEach { level ->
                    Box(Modifier.padding(horizontal = 2.dp).size(CELL).clip(RoundedCornerShape(3.dp)).background(levelColor(level)))
                }
                Spacer(Modifier.width(8.dp))
                Text("Больше", style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
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
                                MONTHS_SHORT[month - 1],
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
                    listOf("пн", "", "ср", "", "пт", "", "").forEach { label ->
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
                                            "${day.dayOfMonth} ${MONTHS_OF[day.monthValue - 1]} · " +
                                                if (millis > 0) formatPlayTime(millis) else "без игры",
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
            SectionTitle("Любимые версии")
            Spacer(Modifier.height(14.dp))
            val versions = stats?.versions.orEmpty()
            if (versions.isEmpty()) {
                Text(if (stats == null) "Считаю…" else "Пока пусто", style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
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
                                    .background(loaderTone(version.loader)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCard(stats: ActivityStats?, modifier: Modifier) {
    Card(modifier) {
        Column {
            SectionTitle("Последние сессии")
            Spacer(Modifier.height(10.dp))
            val recent = stats?.recent.orEmpty()
            if (recent.isEmpty()) {
                Text(if (stats == null) "Считаю…" else "Пока пусто", style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
            }
            recent.forEach { session -> SessionRow(session, stats?.today ?: LocalDate.now()) }
        }
    }
}

@Composable
private fun SessionRow(session: PlaySession, today: LocalDate) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                whenOf(session.start, today),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = JuxColors.Text,
                maxLines = 1,
            )
            session.server?.let {
                Text("на $it", style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Tag(session.label, loaderTone(session.loader))
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

private fun loaderTone(kind: LoaderKind): Color = when (kind) {
    LoaderKind.FABRIC -> JuxColors.LoaderFabric
    LoaderKind.QUILT -> JuxColors.LoaderQuilt
    LoaderKind.FORGE -> JuxColors.LoaderForge
    LoaderKind.NEOFORGE -> JuxColors.LoaderNeoForge
    LoaderKind.VANILLA -> JuxColors.LoaderVanilla
}

internal fun formatPlayTime(millis: Long): String {
    val minutes = millis / 60_000
    val hours = minutes / 60
    return when {
        minutes < 1 -> "< 1 мин"
        hours == 0L -> "$minutes мин"
        minutes % 60 == 0L -> "$hours ч"
        else -> "$hours ч ${minutes % 60} мин"
    }
}

internal fun plural(count: Int, one: String, few: String, many: String): String {
    val tens = count % 100
    val ones = count % 10
    return when {
        tens in 11..14 -> many
        ones == 1 -> one
        ones in 2..4 -> few
        else -> many
    }
}

private fun daysWord(count: Int) = plural(count, "день", "дня", "дней")

private fun dateOf(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()

private fun dayOf(at: Long): String = dateOf(at).let { "${it.dayOfMonth} ${MONTHS_OF[it.monthValue - 1]}" }

private fun whenOf(at: Long, today: LocalDate): String {
    val date = dateOf(at)
    val clock = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(CLOCK)
    val day = when (date) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> "${date.dayOfMonth} ${MONTHS_SHORT[date.monthValue - 1]}"
    }
    return "$day, $clock"
}
