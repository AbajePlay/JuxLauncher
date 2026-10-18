package ru.jux.launcher.core

enum class ThemeMode(val title: String) {
    DARK("Тёмная"),
    MIDNIGHT("Полночь"),
    OLED("Чёрная"),
    LIGHT("Светлая"),
}

enum class Backdrop(val title: String) {
    NONE("Как в теме"),
    COLOR("Свой цвет"),
    IMAGE("Своя картинка"),
}

const val DEFAULT_ACCENT = 0xFFD43B
const val DEFAULT_BACKDROP_COLOR = 0x1E2A3A
