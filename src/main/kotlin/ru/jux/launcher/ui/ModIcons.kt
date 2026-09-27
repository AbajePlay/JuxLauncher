package ru.jux.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.toHex
import ru.jux.launcher.core.writeAtomically
import ru.jux.launcher.net.Http
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readBytes

object ModIcons {

    private const val SIZE = 64
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val KEEP = 160

    private val dir = Paths.cache.resolve("mod-icons")

    private val memory = object : LinkedHashMap<String, ImageBitmap>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > KEEP
    }

    fun cached(url: String): ImageBitmap? = synchronized(memory) { memory[url] }

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cached(url)?.let { return@withContext it }
        val file = dir.resolve(MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).toHex())
        val bytes = if (file.exists()) {
            file.readBytes()
        } else {
            val fetched = runCatching { fetch(url) }.getOrNull() ?: return@withContext null
            runCatching { file.writeAtomically(fetched) }
            fetched
        }
        val bitmap = runCatching { shrink(bytes) }.getOrNull() ?: return@withContext null
        synchronized(memory) { memory[url] = bitmap }
        bitmap
    }

    private fun fetch(url: String): ByteArray? =
        Http.client.newCall(Http.request(url)).execute().use { response ->
            val body = response.body ?: return null
            if (!response.isSuccessful || body.contentLength() > MAX_BYTES) return null
            body.bytes().takeIf { it.size <= MAX_BYTES }
        }

    private fun shrink(bytes: ByteArray): ImageBitmap {
        val source = SkiaImage.makeFromEncoded(bytes)
        val surface = Surface.makeRasterN32Premul(SIZE, SIZE)
        surface.canvas.drawImageRect(
            source,
            Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
            Rect.makeWH(SIZE.toFloat(), SIZE.toFloat()),
            SamplingMode.LINEAR,
            null,
            true,
        )
        return surface.makeImageSnapshot().toComposeImageBitmap()
    }
}

@Composable
fun ModIcon(url: String?, size: Dp = 44.dp) {
    val bitmap by produceState(url?.let(ModIcons::cached), url) {
        if (value == null && url != null) value = ModIcons.load(url)
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(JuxDimens.CornerSmall)).background(JuxColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, null, modifier = Modifier.fillMaxSize())
        } else {
            Icon(JuxIcons.Extension, null, tint = JuxColors.TextMuted, modifier = Modifier.size(size / 2))
        }
    }
}
