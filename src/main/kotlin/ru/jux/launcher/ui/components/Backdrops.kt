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
import ru.jux.launcher.core.Backdrop
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.writeAtomically
import ru.jux.launcher.ui.theme.JuxColors
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes

object Backdrops {

    private const val MAX_IMAGE_BYTES = 25L shl 20

    var revision by mutableStateOf(0)
        private set

    fun importImage(source: Path) {
        if (source.fileSize() > MAX_IMAGE_BYTES) throw IOException("Картинка больше 25 МБ")
        val bytes = source.readBytes()
        if (decode(bytes) == null) throw IOException("Формат картинки не поддерживается")
        Paths.backdropImage.writeAtomically(bytes)
        revision++
    }

    internal fun load(file: Path): ImageBitmap? =
        runCatching { if (file.exists()) decode(Files.readAllBytes(file)) else null }
            .onFailure { Log.warn("backdrop image unreadable: ${it.message}") }
            .getOrNull()

    private fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
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
