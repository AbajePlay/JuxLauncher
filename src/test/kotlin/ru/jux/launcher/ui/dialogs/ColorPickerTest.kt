package ru.jux.launcher.ui.dialogs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ColorPickerTest {

    @Test
    fun `colors are shown as hex without alpha`() {
        assertEquals("#5EC8FF", hexOf(0x5EC8FF))
        assertEquals("#00000A", hexOf(0x0A))
        assertEquals("#FFFFFF", hexOf(0xFFFFFFFF.toInt()))
    }
}
