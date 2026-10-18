package ru.jux.launcher.core

enum class ThemeMode(private val ru: String, private val en: String) {
    DARK("Тёмная", "Dark"),
    MIDNIGHT("Полночь", "Midnight"),
    OLED("Чёрная", "Black"),
    LIGHT("Светлая", "Light"),
    ;

    val title: String get() = tr(ru, en)
}

enum class Backdrop(private val ru: String, private val en: String) {
    NONE("Как в теме", "Theme default"),
    COLOR("Свой цвет", "Custom color"),
    IMAGE("Своя картинка", "Custom image"),
    ;

    val title: String get() = tr(ru, en)
}

const val DEFAULT_ACCENT = 0xFFD43B
const val DEFAULT_BACKDROP_COLOR = 0x1E2A3A
