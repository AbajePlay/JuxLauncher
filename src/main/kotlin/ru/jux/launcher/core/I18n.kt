package ru.jux.launcher.core

import java.util.Locale

enum class Language(val title: String) {
    RU("Русский"),
    EN("English"),
}

private val RUSSIAN_SPEAKING = setOf("ru", "uk", "be", "kk")

fun systemLanguage(): Language = if (Locale.getDefault().language in RUSSIAN_SPEAKING) Language.RU else Language.EN

// Каждый текст хранит оба перевода рядом с местом, где он показывается: так ни один не потеряется
fun tr(ru: String, en: String): String = if (Settings.current.language == Language.EN) en else ru

// Русские формы для 1, 2–4 и 5+; в английском только «один» и «много»
fun plural(count: Int, one: String, few: String, many: String, enOne: String, enMany: String): String {
    if (Settings.current.language == Language.EN) return if (count == 1) enOne else enMany
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}
