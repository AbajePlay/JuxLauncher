package ru.jux.launcher.dev

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import ru.jux.launcher.core.Preloader
import ru.jux.launcher.logs.LogSource
import ru.jux.launcher.ui.App
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.Screen
import ru.jux.launcher.ui.SplashContent
import ru.jux.launcher.ui.theme.JuxTheme
import java.io.File
import kotlin.system.exitProcess

private const val FRAME_NANOS = 16_000_000L
private const val SETTLE_NANOS = 600_000_000L

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/preview").apply { mkdirs() }
    val scale = args.getOrNull(1)?.toFloatOrNull() ?: 1.25f

    val preloaded = runBlocking { Preloader.run { _, _ -> } }
        .copy(manifestStale = false, loaderSupportStale = false)
    val state = LauncherState(CoroutineScope(Dispatchers.Unconfined), preloaded)

    fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(
            width = (width * scale).toInt(),
            height = (height * scale).toInt(),
            density = Density(scale),
            content = content,
        )
        try {
            var time = 0L
            while (time < SETTLE_NANOS) {
                scene.render(time)
                time += FRAME_NANOS
            }
            val image = scene.render(time)
            val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed for $name")
            File(out, "$name.png").writeBytes(png.bytes)
        } finally {
            scene.close()
        }
        println("rendered ${File(out, "$name.png")}")
    }

    render("splash", 520, 180) { JuxTheme { SplashContent(0.7f, "Подтягиваю загрузчики модов") } }
    for (screen in Screen.entries) {
        state.screen = screen
        render(screen.name.lowercase(), 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    }

    state.screen = Screen.HOME
    val entry = state.currentEntry()
    val dialogs = listOfNotNull(
        entry?.let { "dialog-instance" to Modal.InstanceSettings(it) },
        entry?.let { "dialog-delete" to Modal.Delete(it) },
        entry?.let { "dialog-logs" to Modal.Logs(state.gameDirOf(it), it.label, LogSource.GAME) },
        entry?.takeIf { it.loader.isModded }?.let { "dialog-mods" to Modal.Mods(it) },
    )
    for ((name, modal) in dialogs) {
        state.modal = modal
        render(name, 1040, 660) { JuxTheme { App(state, onGameStarted = {}) } }
    }
    state.modal = null
    exitProcess(0)
}
