package ru.jux.launcher

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.util.concurrent.TimeUnit
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import ru.jux.launcher.core.ClassArchives
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.MemoryRelease
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.PlayArguments
import ru.jux.launcher.core.PreloadResult
import ru.jux.launcher.core.Preloader
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.SingleInstance
import ru.jux.launcher.core.VerifyCache
import ru.jux.launcher.discord.DiscordPresence
import ru.jux.launcher.discord.Presence
import ru.jux.launcher.net.Http
import ru.jux.launcher.ui.App
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.SplashContent
import ru.jux.launcher.ui.WindowChrome
import ru.jux.launcher.ui.theme.JuxTheme

fun main(args: Array<String>) {
    val instance = bootstrap(args)
    val playRequest = PlayArguments.parse(args)?.also { Log.info("asked to start ${it.versionId} (${it.loader})") }

    application {
        LaunchedEffect(Unit) { awaitCancellation() }

        var preloaded by remember { mutableStateOf<PreloadResult?>(null) }
        var fraction by remember { mutableStateOf(0f) }
        var status by remember { mutableStateOf("Запускаюсь") }

        LaunchedEffect(Unit) {
            preloaded = Preloader.run { value, text ->
                fraction = value
                status = text
            }
        }

        val ready = preloaded
        if (ready == null) {
            Window(
                onCloseRequest = { shutdownAndExit() },
                title = "JuxLauncher",
                undecorated = true,
                transparent = true,
                resizable = false,
                alwaysOnTop = true,
                state = rememberWindowState(
                    width = 520.dp,
                    height = 180.dp,
                    position = WindowPosition(Alignment.Center),
                ),
            ) {
                LaunchedEffect(Unit) {
                    Log.info("startup: splash at ${sinceProcessStart()} ms")
                    WindowChrome.applyIcon(window)
                }
                JuxTheme { SplashContent(fraction, status) }
            }
        } else {
            val scope = rememberCoroutineScope()
            val state = remember {
                LauncherState(scope, ready, playRequest).also { it.onQuit = ::shutdownAndExit }
            }
            val windowState = rememberWindowState(
                width = 1040.dp,
                height = 660.dp,
                position = WindowPosition(Alignment.Center),
            )
            var gameProcess by remember { mutableStateOf<Process?>(null) }
            var reportedReady by remember { mutableStateOf(false) }
            var shownDuringGame by remember { mutableStateOf(false) }
            var raised by remember { mutableStateOf(0) }

            LaunchedEffect(Unit) {
                for (handed in instance.requests) {
                    Log.info("another start handed over: ${handed.joinToString(" ").ifEmpty { "no arguments" }}")
                    if (gameProcess != null) shownDuringGame = true
                    windowState.isMinimized = false
                    raised++
                    PlayArguments.parse(handed.toTypedArray())
                        ?.let { state.playFromShortcut(it, gameRunning = gameProcess != null) }
                }
            }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { runCatching { ClassArchives.removeStale() } }
            }
            LaunchedEffect(Unit) { state.checkForUpdates() }
            LaunchedEffect(Unit) {
                DiscordPresence.show(Presence.Launcher)
                DiscordPresence.start()
            }

            LaunchedEffect(gameProcess) {
                val process = gameProcess ?: return@LaunchedEffect
                if (Settings.current.closeOnLaunch) {
                    val exitedEarly = withContext(Dispatchers.IO) {
                        runCatching { process.waitFor(EARLY_EXIT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
                    }
                    if (!exitedEarly || process.exitValue() == 0) {
                        if (!exitedEarly && Settings.current.discordPresence) {
                            Log.info("closing the window, staying in the background for the Discord status")
                            MemoryRelease.afterWindowClosed()
                            withContext(Dispatchers.IO) { runCatching { process.waitFor() } }
                        }
                        shutdownAndExit()
                        return@LaunchedEffect
                    }
                } else {
                    Log.info("waiting for the game to exit, pid=${runCatching { process.pid() }.getOrDefault(-1)}")
                    MemoryRelease.afterWindowClosed()
                    withContext(Dispatchers.IO) { runCatching { process.waitFor() } }
                }
                Log.info("game exited with ${process.exitValue()}, showing the launcher again")
                DiscordPresence.show(Presence.Launcher)
                state.gameExited(process.exitValue())
                shownDuringGame = false
                gameProcess = null
            }

            if (gameProcess == null || shownDuringGame) {
                Window(
                    onCloseRequest = { shutdownAndExit() },
                    title = "JuxLauncher",
                    state = windowState,
                    onPreviewKeyEvent = { state.onKey(it) },
                ) {
                    window.minimumSize = java.awt.Dimension(900, 560)
                    LaunchedEffect(Unit) {
                        WindowChrome.applyIcon(window)
                        WindowChrome.applyDark(window)
                        if (!reportedReady) {
                            reportedReady = true
                            Log.info("startup: launcher ready at ${sinceProcessStart()} ms")
                        }
                        if (state.consumePendingPlay()) state.play()
                    }
                    LaunchedEffect(raised) {
                        if (raised > 0) {
                            window.toFront()
                            window.requestFocus()
                        }
                    }
                    JuxTheme {
                        App(state, onGameStarted = {
                            shownDuringGame = false
                            gameProcess = it
                        })
                    }
                }
            }
        }
    }
}

private const val EARLY_EXIT_SECONDS = 20L

private fun bootstrap(args: Array<String>): SingleInstance {
    val migration = Paths.migrateLegacyLocation()
    Paths.ensureBaseDirs()

    val instance = SingleInstance(Paths.root)
    when (instance.start(args.toList())) {
        SingleInstance.Role.PRIMARY -> Unit
        SingleInstance.Role.HANDED_OVER -> exitProcess(0)
        SingleInstance.Role.UNREACHABLE -> {
            JOptionPane.showMessageDialog(
                null,
                "JuxLauncher уже запущен, но не отвечает.\nЗакройте его в диспетчере задач и запустите снова.",
                "JuxLauncher",
                JOptionPane.WARNING_MESSAGE,
            )
            exitProcess(0)
        }
    }

    Log.init()
    Log.info("JuxLauncher starting, data dir: ${Paths.root}, jvm up in ${sinceProcessStart()} ms")
    migration?.let { Log.info(it) }

    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        Log.error("uncaught exception on ${thread.name}", error)
    }
    return instance
}

private fun sinceProcessStart(): Long =
    ProcessHandle.current().info().startInstant()
        .map { System.currentTimeMillis() - it.toEpochMilli() }
        .orElse(-1L)

private fun shutdownAndExit() {
    runCatching { DiscordPresence.stop() }
    runCatching { VerifyCache.save() }
    runCatching { Settings.save() }
    runCatching { Http.shutdown() }
    Log.info("bye")
    Log.close()
    exitProcess(0)
}
