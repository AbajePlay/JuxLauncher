package ru.jux.launcher.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.jux.launcher.core.Backdrop
import ru.jux.launcher.core.LauncherSettings
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.ThemeMode

data class Palette(
    val isDark: Boolean,
    val background: Color,
    val sidebar: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val text: Color,
    val textSoft: Color,
    val textMuted: Color,
    val danger: Color,
    val warning: Color,
    val info: Color,
    val success: Color,
    val loaderVanilla: Color,
    val loaderOptiFine: Color,
    val shadows: Boolean = true,
)

private val DarkStatus = Palette(
    isDark = true,
    background = Color(0xFF111014),
    sidebar = Color(0xFF1A191F),
    surface = Color(0xFF1C1B21),
    surfaceHigh = Color(0xFF25242B),
    outline = Color(0xFF2E2D35),
    text = Color(0xFFEEECF3),
    textSoft = Color(0xFFA7A4B3),
    textMuted = Color(0xFF8F8B9C),
    danger = Color(0xFFF58E8E),
    warning = Color(0xFFF2A36B),
    info = Color(0xFF9CC3FF),
    success = Color(0xFF6BD68A),
    loaderVanilla = Color(0xFFB7BFCC),
    loaderOptiFine = Color(0xFFB79CFF),
)

fun paletteOf(mode: ThemeMode): Palette = when (mode) {
    ThemeMode.DARK -> DarkStatus
    ThemeMode.MIDNIGHT -> DarkStatus.copy(
        background = Color(0xFF0D1220),
        sidebar = Color(0xFF141B2D),
        surface = Color(0xFF162033),
        surfaceHigh = Color(0xFF1F2A41),
        outline = Color(0xFF2A3650),
        text = Color(0xFFE8EDF7),
        textSoft = Color(0xFFA3AEC6),
        textMuted = Color(0xFF8590AA),
    )
    ThemeMode.OLED -> DarkStatus.copy(
        background = Color(0xFF000000),
        sidebar = Color(0xFF0B0B0D),
        surface = Color(0xFF0E0E11),
        surfaceHigh = Color(0xFF1A1A1F),
        outline = Color(0xFF26262D),
        text = Color(0xFFF2F2F5),
        textSoft = Color(0xFFA9A9B3),
        textMuted = Color(0xFF8C8C96),
    )
    ThemeMode.LIGHT -> Palette(
        isDark = false,
        background = Color(0xFFEEF0F4),
        sidebar = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        surfaceHigh = Color(0xFFF0F1F5),
        outline = Color(0xFFDDE0E8),
        text = Color(0xFF1B1A22),
        textSoft = Color(0xFF4A4858),
        textMuted = Color(0xFF6E6B7B),
        danger = Color(0xFFD64545),
        warning = Color(0xFFC76B1E),
        info = Color(0xFF2F6FD0),
        success = Color(0xFF1E9E4A),
        loaderVanilla = Color(0xFF5E6675),
        loaderOptiFine = Color(0xFF6B4FD8),
        shadows = false,
    )
}

fun colorOf(rgb: Int): Color = Color(0xFF000000 or (rgb.toLong() and 0xFFFFFF))

// Тёмный текст контрастнее белого уже с яркости ~0.18 — так кнопка читается на любом акценте
private fun inkOn(color: Color): Color = if (color.luminance() > 0.18f) Color(0xFF1B1A22) else Color.White

object JuxColors {

    private data class Look(val palette: Palette, val accent: Color, val onAccent: Color, val translucent: Boolean)

    private var look by mutableStateOf(lookOf(LauncherSettings()))

    private fun lookOf(settings: LauncherSettings): Look {
        val theme = paletteOf(settings.theme)
        val palette = if (settings.backdrop == Backdrop.COLOR) theme.copy(background = colorOf(settings.backdropColor)) else theme
        val picked = colorOf(settings.accentColor)
        // На светлой теме светлый акцент как цвет текста не прочитать — затемняем его
        val accent = if (!palette.isDark && picked.luminance() > 0.5f) Color(picked.red * 0.7f, picked.green * 0.7f, picked.blue * 0.7f) else picked
        return Look(palette, accent, inkOn(accent), translucent = settings.backdrop == Backdrop.IMAGE)
    }

    fun apply(settings: LauncherSettings) {
        val next = lookOf(settings)
        if (next != look) look = next
    }

    val isDark: Boolean get() = look.palette.isDark

    private fun Color.layer(): Color = if (look.translucent) copy(alpha = if (isDark) 0.84f else 0.88f) else this

    val Background: Color get() = look.palette.background
    val Sidebar: Color get() = look.palette.sidebar.layer()
    val Surface: Color get() = look.palette.surface.layer()
    val SurfaceHigh: Color get() = look.palette.surfaceHigh
    val Outline: Color get() = look.palette.outline

    val Accent: Color get() = look.accent
    val AccentPressed: Color get() = look.accent.copy(red = look.accent.red * 0.9f, green = look.accent.green * 0.88f, blue = look.accent.blue * 0.85f)
    val AccentSoft: Color get() = look.accent.copy(alpha = if (isDark) 0.13f else 0.16f)
    val OnAccent: Color get() = look.onAccent

    val Text: Color get() = look.palette.text
    val TextSoft: Color get() = look.palette.textSoft
    val TextMuted: Color get() = look.palette.textMuted
    val Danger: Color get() = look.palette.danger
    val Warning: Color get() = look.palette.warning
    val Info: Color get() = look.palette.info
    val Success: Color get() = look.palette.success

    val LoaderVanilla: Color get() = look.palette.loaderVanilla
    val LoaderFabric: Color get() = Info
    val LoaderQuilt: Color get() = if (isDark) Color(0xFFE6C07B) else Color(0xFFA87A16)
    val LoaderForge: Color get() = Warning
    val LoaderNeoForge: Color get() = if (isDark) Color(0xFFF28B8B) else Color(0xFFC94444)
    val LoaderOptiFine: Color get() = look.palette.loaderOptiFine

    val Shadow: Color get() = Color.Black
    // Под полупрозрачной панелью тень просвечивает прямоугольником, а на светлой теме панели и так отделены от фона
    val CastsShadow: Boolean get() = look.palette.shadows && !look.translucent
}

val Montserrat = FontFamily(
    Font("fonts/Montserrat-Regular.ttf", FontWeight.Normal),
    Font("fonts/Montserrat-Medium.ttf", FontWeight.Medium),
    Font("fonts/Montserrat-SemiBold.ttf", FontWeight.SemiBold),
    Font("fonts/Montserrat-Bold.ttf", FontWeight.Bold),
    Font("fonts/Montserrat-Black.ttf", FontWeight.Black),
)

private val JuxTypography = Typography(
    displayMedium = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.8).sp, lineHeight = 46.sp),
    displaySmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp),
).withFamily(Montserrat)

private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

object JuxDimens {
    val CornerLarge = 28.dp
    val CornerCard = 22.dp
    val CornerMedium = 16.dp
    val CornerSmall = 12.dp
    val Gutter = 20.dp
}

val PillShape = RoundedCornerShape(percent = 50)

fun Modifier.softShadow(shape: Shape, elevation: Dp = 20.dp): Modifier =
    if (!JuxColors.CastsShadow) this
    else shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = JuxColors.Shadow.copy(alpha = 0.45f),
        spotColor = JuxColors.Shadow.copy(alpha = 0.7f),
    )

fun Modifier.glow(shape: Shape, color: Color, elevation: Dp = 22.dp): Modifier =
    shadow(elevation = elevation, shape = shape, clip = false, ambientColor = color, spotColor = color)

@Composable
fun JuxTheme(content: @Composable () -> Unit) {
    val settings by Settings.state.collectAsState()
    JuxColors.apply(settings)
    val base = if (JuxColors.isDark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = base.copy(
            primary = JuxColors.Accent,
            onPrimary = JuxColors.OnAccent,
            secondary = JuxColors.Info,
            onSecondary = JuxColors.Background,
            background = JuxColors.Background,
            onBackground = JuxColors.Text,
            surface = JuxColors.Surface,
            onSurface = JuxColors.Text,
            surfaceVariant = JuxColors.SurfaceHigh,
            onSurfaceVariant = JuxColors.TextMuted,
            outline = JuxColors.Outline,
            outlineVariant = JuxColors.Outline,
            error = JuxColors.Danger,
            onError = JuxColors.Background,
        ),
        typography = JuxTypography,
        content = content,
    )
}
