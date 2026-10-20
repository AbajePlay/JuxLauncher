package ru.jux.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import ru.jux.launcher.core.Backdrop
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.tr
import ru.jux.launcher.core.writeAtomically
import ru.jux.launcher.ui.theme.JuxColors
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.nio.file.Files
import kotlin.math.ceil
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes

object Backdrops {

    private const val MAX_IMAGE_BYTES = 25L shl 20

    var revision by mutableStateOf(0)
        private set

    fun importImage(source: Path) {
        if (source.fileSize() > MAX_IMAGE_BYTES) throw IOException(tr("Картинка больше 25 МБ", "Image is larger than 25 MB"))
        val bytes = source.readBytes()
        if (decode(bytes) == null) throw IOException(tr("Формат картинки не поддерживается", "Image format not supported"))
        Paths.backdropImage.writeAtomically(bytes)
        revision++
    }

    internal fun load(file: Path): ImageBitmap? =
        runCatching { if (file.exists()) decode(Files.readAllBytes(file)) else null }
            .onFailure { Log.warn("backdrop image unreadable: ${it.message}") }
            .getOrNull()

    private fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        SkiaImage.makeFromEncoded(bytes).use { source -> fitToScreens(source) }
    }.getOrNull()

    /**
     * Фон рисуется с обрезкой по окну, а окно не больше экрана: фото 6000×4000 занимало бы ~100 МБ вне кучи,
     * хотя на экране видно не больше пикселей, чем у самого большого монитора. Уменьшаем до размера, который
     * этот монитор целиком покрывает с теми же пропорциями — кадр и обрезка остаются прежними.
     */
    private fun fitToScreens(source: SkiaImage): ImageBitmap {
        val screen = largestScreen() ?: return source.toComposeImageBitmap()
        val scale = maxOf(screen.width / source.width.toFloat(), screen.height / source.height.toFloat())
        if (scale >= 1f) return source.toComposeImageBitmap()
        val width = ceil(source.width * scale).toInt().coerceAtLeast(1)
        val height = ceil(source.height * scale).toInt().coerceAtLeast(1)
        return Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.drawImageRect(
                source,
                Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                Rect.makeWH(width.toFloat(), height.toFloat()),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                null,
                true,
            )
            surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
        }
    }

    private fun largestScreen(): Dimension? = runCatching {
        // Физические пиксели: режим экрана или логические границы × масштаб Windows — что больше
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { device ->
                val mode = device.displayMode
                val config = device.defaultConfiguration
                val scale = config.defaultTransform
                Dimension(
                    maxOf(mode.width, ceil(config.bounds.width * scale.scaleX).toInt()),
                    maxOf(mode.height, ceil(config.bounds.height * scale.scaleY).toInt()),
                )
            }
            .filter { it.width > 0 && it.height > 0 }
            .takeIf { it.isNotEmpty() }
            ?.let { screens -> Dimension(screens.maxOf { it.width }, screens.maxOf { it.height }) }
    }.getOrNull()
}

@Composable
fun BackdropLayer(backdrop: Backdrop, dim: Float) {
    Box(Modifier.fillMaxSize().background(JuxColors.Background)) {
        if (backdrop != Backdrop.IMAGE) return@Box
        val image by produceState<ImageBitmap?>(null, Backdrops.revision) {
            value = withContext(Dispatchers.IO) { Backdrops.load(Paths.backdropImage) }
        }
        val picture = image ?: return@Box
        Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(JuxColors.Background.copy(alpha = dim.coerceIn(0f, 0.9f))))
    }
}
