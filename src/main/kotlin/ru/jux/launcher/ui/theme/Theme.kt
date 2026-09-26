package ru.jux.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object JuxColors {
    val Background = Color(0xFF0F1115)
    val Surface = Color(0xFF171A21)
    val SurfaceHigh = Color(0xFF1E222B)
    val Outline = Color(0xFF262B36)

    val Accent = Color(0xFFF5D105)
    val AccentPressed = Color(0xFFD4B404)

    val OnAccent = Color(0xFF241C00)

    val Text = Color(0xFFE6E8EE)
    val TextMuted = Color(0xFF9AA3B2)
    val Danger = Color(0xFFF87171)

    val Warning = Color(0xFFFB923C)
    val Info = Color(0xFF60A5FA)

    val Success = Color(0xFF4ADE80)
}

private val DarkScheme = darkColorScheme(
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
)

private val LightScheme = DarkScheme

val Montserrat = FontFamily(
    Font("fonts/Montserrat-Regular.ttf", FontWeight.Normal),
    Font("fonts/Montserrat-Medium.ttf", FontWeight.Medium),
    Font("fonts/Montserrat-SemiBold.ttf", FontWeight.SemiBold),
    Font("fonts/Montserrat-Bold.ttf", FontWeight.Bold),
    Font("fonts/Montserrat-Black.ttf", FontWeight.Black),
)

private val JuxTypography = Typography(
    displaySmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
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
    val CornerLarge = 16.dp
    val CornerMedium = 12.dp
    val CornerSmall = 8.dp
    val Gutter = 20.dp
}

@Composable
fun JuxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = JuxTypography,
        content = content,
    )
}
