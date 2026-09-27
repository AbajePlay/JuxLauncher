package ru.jux.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object JuxColors {
    val Background = Color(0xFF111014)
    val Sidebar = Color(0xFF1A191F)
    val Surface = Color(0xFF1C1B21)
    val SurfaceHigh = Color(0xFF25242B)
    val Outline = Color(0xFF2E2D35)

    val Accent = Color(0xFFFFD43B)
    val AccentPressed = Color(0xFFE8BE2C)
    val AccentSoft = Accent.copy(alpha = 0.13f)

    val OnAccent = Color(0xFF2A2100)

    val Text = Color(0xFFEEECF3)
    val TextSoft = Color(0xFFA7A4B3)
    val TextMuted = Color(0xFF8F8B9C)
    val Danger = Color(0xFFF58E8E)

    val Warning = Color(0xFFF2A36B)
    val Info = Color(0xFF9CC3FF)

    val Success = Color(0xFF6BD68A)

    val LoaderVanilla = Color(0xFFB7BFCC)
    val LoaderFabric = Color(0xFF9CC3FF)
    val LoaderQuilt = Color(0xFFE6C07B)
    val LoaderForge = Color(0xFFF2A36B)
    val LoaderNeoForge = Color(0xFFF28B8B)

    val Shadow = Color(0xFF000000)
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
    shadow(
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
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = JuxTypography,
        content = content,
    )
}
