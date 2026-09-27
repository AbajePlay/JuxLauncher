package ru.jux.launcher.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import ru.jux.launcher.core.Log
import ru.jux.launcher.ui.components.Brand
import ru.jux.launcher.ui.theme.JuxColors
import java.awt.Window

private interface Dwmapi : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
}

object WindowChrome {

    private const val USE_IMMERSIVE_DARK_MODE = 20
    private const val USE_IMMERSIVE_DARK_MODE_LEGACY = 19
    private const val BORDER_COLOR = 34
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    fun applyDark(window: Window) {
        if (!isWindows) return
        runCatching {
            val hwnd = Native.getComponentPointer(window) ?: return
            val dwm = Native.load("dwmapi", Dwmapi::class.java)

            if (dwm.set(hwnd, USE_IMMERSIVE_DARK_MODE, 1) != 0) {
                dwm.set(hwnd, USE_IMMERSIVE_DARK_MODE_LEGACY, 1)
            }

            dwm.set(hwnd, CAPTION_COLOR, colorRef(JuxColors.Background.toArgbInt()))
            dwm.set(hwnd, TEXT_COLOR, colorRef(JuxColors.Text.toArgbInt()))
            dwm.set(hwnd, BORDER_COLOR, colorRef(JuxColors.Background.toArgbInt()))
            Log.debug("dark window chrome applied")
        }.onFailure { Log.debug("window chrome unavailable: ${it.message}") }
    }

    fun applyIcon(window: Window) {
        runCatching { window.iconImages = Brand.windowIcons }
            .onFailure { Log.debug("window icon unavailable: ${it.message}") }
    }

    private fun Dwmapi.set(hwnd: Pointer, attribute: Int, value: Int): Int =
        DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4)

    private fun colorRef(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int {
        val r = (red * 255f + 0.5f).toInt()
        val g = (green * 255f + 0.5f).toInt()
        val b = (blue * 255f + 0.5f).toInt()
        return (r shl 16) or (g shl 8) or b
    }
}
