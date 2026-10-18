package ru.jux.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import org.jetbrains.skia.Image as SkiaImage
import ru.jux.launcher.launch.ArgumentBuilder
import ru.jux.launcher.ui.theme.JuxColors
import java.awt.image.BufferedImage

object Brand {

    val wordmark: ImageBitmap by lazy { decode("brand/wordmark.png") }

    val windowIcons: List<BufferedImage> by lazy {
        listOf(16, 20, 24, 32, 40, 48, 64).map { decode("brand/icon-$it.png").toAwtImage() }
    }

    private fun decode(path: String): ImageBitmap {
        val bytes = Brand::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("$path is missing from the launcher's resources")
        return SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }
}

@Composable
fun Wordmark(width: Dp, modifier: Modifier = Modifier) {
    val image = Brand.wordmark
    Image(
        bitmap = image,
        contentDescription = ArgumentBuilder.LAUNCHER_NAME,
        modifier = modifier.width(width).aspectRatio(image.width.toFloat() / image.height),
        filterQuality = FilterQuality.Medium,
        colorFilter = if (JuxColors.isDark) null else ON_LIGHT,
    )
}

// На светлом фоне белое «Launcher» пропадает: вычитаем синий канал, белое темнеет, а жёлтое «Jux» почти не меняется
private val ON_LIGHT = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            1f, 0f, -0.85f, 0f, 0f,
            0f, 1f, -0.85f, 0f, 0f,
            0f, 0f, 0.15f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)
