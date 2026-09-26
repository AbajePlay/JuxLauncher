package ru.jux.launcher.ui.components

import java.time.LocalDate
import java.util.Locale

private val RU = Locale.forLanguageTag("ru")

private val MONTHS = arrayOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

fun formatReleaseDate(iso: String, today: LocalDate = LocalDate.now()): String {
    val date = runCatching { LocalDate.parse(iso.take(10)) }.getOrElse { return iso.take(10) }
    val dayMonth = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"
    return if (date.year == today.year) dayMonth else "$dayMonth ${date.year}"
}

fun formatMemory(mb: Int): String = when {
    mb < 1024 -> "$mb МБ"
    mb % 1024 == 0 -> "${mb / 1024} ГБ"
    else -> String.format(RU, "%.1f ГБ", mb / 1024.0)
}

fun formatTotalMemory(mb: Int): String = "${(mb + 512) / 1024} ГБ"

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(RU, "%.1f ГБ", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(RU, "%.0f МБ", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format(RU, "%.0f КБ", bytes / 1024.0)
    else -> "$bytes Б"
}

fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "" else "${formatBytes(bytesPerSecond)}/с"
