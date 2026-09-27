package ru.jux.launcher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.jux.launcher.core.Notice
import ru.jux.launcher.core.NoticeAction
import ru.jux.launcher.core.NoticeLevel
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import ru.jux.launcher.ui.theme.softShadow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MONTHS = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

fun noticeTone(level: NoticeLevel): Color = when (level) {
    NoticeLevel.ERROR -> JuxColors.Danger
    NoticeLevel.WARNING -> JuxColors.Warning
    NoticeLevel.INFO -> JuxColors.Info
    NoticeLevel.SUCCESS -> JuxColors.Success
}

private fun noticeIcon(level: NoticeLevel): ImageVector = when (level) {
    NoticeLevel.ERROR, NoticeLevel.WARNING -> Icons.Default.Warning
    NoticeLevel.INFO -> Icons.Default.Info
    NoticeLevel.SUCCESS -> Icons.Default.CheckCircle
}

fun noticeActionLabel(action: NoticeAction): String = when (action) {
    NoticeAction.LOGS -> "Логи"
    NoticeAction.MODS -> "Моды"
    NoticeAction.FIX_MODS -> "Исправить"
    NoticeAction.PLAY_ANYWAY -> "Запустить всё равно"
}

fun noticeActionIcon(action: NoticeAction): ImageVector = when (action) {
    NoticeAction.LOGS -> JuxIcons.Log
    NoticeAction.MODS -> JuxIcons.Extension
    NoticeAction.FIX_MODS -> Icons.Default.Build
    NoticeAction.PLAY_ANYWAY -> Icons.Default.PlayArrow
}

fun formatNoticeDay(at: Long, today: LocalDate = LocalDate.now()): String {
    val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (day) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}" + if (day.year != today.year) " ${day.year}" else ""
    }
}

fun formatNoticeTime(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(TIME)

@Composable
fun NoticeBadge(level: NoticeLevel, size: Dp) {
    val tone = noticeTone(level)
    Box(
        Modifier.size(size).clip(CircleShape).background(tone.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(noticeIcon(level), null, tint = tone, modifier = Modifier.size(size * 0.45f))
    }
}

@Composable
fun NoticeToast(state: LauncherState, modifier: Modifier = Modifier) {
    val toast = state.toast
    var shown by remember { mutableStateOf<Notice?>(null) }
    if (toast != null) shown = toast

    LaunchedEffect(toast?.id) {
        val current = toast ?: return@LaunchedEffect
        delay(if (current.level == NoticeLevel.ERROR) 12_000L else 6_000L)
        state.dismissToast(current.id)
    }

    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        shown?.let { ToastCard(state, it) }
    }
}

@Composable
private fun ToastCard(state: LauncherState, notice: Notice) {
    val shape = RoundedCornerShape(JuxDimens.CornerCard)
    Row(
        Modifier
            .width(420.dp)
            .softShadow(shape, 28.dp)
            .clip(shape)
            .background(JuxColors.SurfaceHigh)
            .clickable { state.openNotices() }
            .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NoticeBadge(notice.level, 38.dp)
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(
                notice.title ?: notice.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = JuxColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (notice.title != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    notice.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = JuxColors.TextSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (notice.actions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.offset(x = (-8).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    notice.actions.take(2).forEach { action ->
                        Text(
                            noticeActionLabel(action),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (action == NoticeAction.FIX_MODS) JuxColors.Accent else noticeTone(notice.level),
                            modifier = Modifier
                                .clip(RoundedCornerShape(JuxDimens.CornerSmall))
                                .clickable { state.runAction(notice, action) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        IconButton(onClick = { state.dismissToast(notice.id) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, "Закрыть", tint = JuxColors.TextMuted, modifier = Modifier.size(16.dp))
        }
    }
}
