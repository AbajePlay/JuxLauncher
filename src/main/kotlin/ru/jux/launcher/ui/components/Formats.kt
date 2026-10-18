package ru.jux.launcher.ui.components

import ru.jux.launcher.core.Language
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.tr
import java.time.LocalDate
import java.util.Locale

private val MONTHS_SHORT_RU = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
private val MONTHS_LONG_RU = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)
private val MONTHS_SHORT_EN = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MONTHS_LONG_EN = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private val english: Boolean get() = Settings.current.language == Language.EN

// «15 сен» в списках версий, «15 сентября» в уведомлениях; год — только если он не текущий
fun formatDayMonth(date: LocalDate, long: Boolean = false, today: LocalDate = LocalDate.now()): String {
    val months = when {
        english -> if (long) MONTHS_LONG_EN else MONTHS_SHORT_EN
        else -> if (long) MONTHS_LONG_RU else MONTHS_SHORT_RU
    }
    val dayMonth = "${date.dayOfMonth} ${months[date.monthValue - 1]}"
    return if (date.year == today.year) dayMonth else "$dayMonth ${date.year}"
}

fun formatReleaseDate(iso: String, today: LocalDate = LocalDate.now()): String {
    val date = runCatching { LocalDate.parse(iso.take(10)) }.getOrElse { return iso.take(10) }
    return formatDayMonth(date, today = today)
}

private fun decimal(value: Double, digits: Int): String {
    val text = String.format(Locale.ROOT, "%.${digits}f", value)
    return if (english) text else text.replace('.', ',')
}

private val GB: String get() = tr("ГБ", "GB")
private val MB: String get() = tr("МБ", "MB")

fun formatMemory(mb: Int): String = when {
    mb < 1024 -> "$mb $MB"
    mb % 1024 == 0 -> "${mb / 1024} $GB"
    else -> "${decimal(mb / 1024.0, 1)} $GB"
}

fun formatTotalMemory(mb: Int): String = "${(mb + 512) / 1024} $GB"

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${decimal(bytes / 1024.0 / 1024 / 1024, 1)} $GB"
    bytes >= 1024L * 1024 -> "${decimal(bytes / 1024.0 / 1024, 0)} $MB"
    bytes >= 1024 -> "${decimal(bytes / 1024.0, 0)} ${tr("КБ", "KB")}"
    else -> "$bytes ${tr("Б", "B")}"
}

fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "" else "${formatBytes(bytesPerSecond)}${tr("/с", "/s")}"

fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "${decimal(count / 1_000_000.0, 1).removeSuffix(",0").removeSuffix(".0")} ${tr("млн", "M")}"
    count >= 1_000 -> "${count / 1_000} ${tr("тыс.", "K")}"
    else -> count.toString()
}

// Подпись месяца над столбцом тепловой карты: «май», а не «мая»
fun formatMonthLabel(month: Int): String =
    if (english) MONTHS_SHORT_EN[month - 1] else if (month == 5) "май" else MONTHS_SHORT_RU[month - 1]
