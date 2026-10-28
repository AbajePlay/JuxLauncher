package ru.jux.launcher

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.jux.launcher.core.ClassArchives
import ru.jux.launcher.core.GameWindowMode
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.MemoryRelease
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.PlayArguments
import ru.jux.launcher.core.PreloadResult
import ru.jux.launcher.core.Preloader
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.SingleInstance
import ru.jux.launcher.core.UrlProtocol
import ru.jux.launcher.core.VerifyCache
import ru.jux.launcher.core.tr
import ru.jux.launcher.discord.DiscordPresence
import ru.jux.launcher.discord.Presence
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.net.Http
import ru.jux.launcher.online.OnlineCounter
import ru.jux.launcher.ui.App
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.SplashContent
import ru.jux.launcher.ui.WindowChrome
import ru.jux.launcher.ui.theme.JuxTheme

fun main(args: Array<String>) {
    val instance = bootstrap(args)
    val playRequest = PlayArguments.parse(args)?.also { Log.info("asked to start ${it.versionId} (${it.loader})") }
    val link = LaunchLink.find(args.toList())?.also { Log.info("opened by a link: $it") }
    instance.listenToSystem(onQuit = ::shutdownAndExit)

    application {
        LaunchedEffect(Unit) { awaitCancellation() }

        var preloaded by remember { mutableStateOf<PreloadResult?>(null) }
        var fraction by remember { mutableStateOf(0f) }
        var status by remember { mutableStateOf(tr("Запускаюсь", "Starting")) }

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
                LauncherState(scope, ready, playRequest, link).also { it.onQuit = ::shutdownAndExit }
            }
            val windowState = rememberWindowState(
                width = 1040.dp,
                height = 660.dp,
                position = WindowPosition(Alignment.Center),
            )
            var reportedReady by remember { mutableStateOf(false) }
            var hiddenForGame by remember { mutableStateOf(false) }
            var minimizedForGame by remember { mutableStateOf(false) }
            var raised by remember { mutableStateOf(0) }

            LaunchedEffect(Unit) {
                for (handed in instance.requests) {
                    Log.info("another start handed over: ${handed.joinToString(" ").ifEmpty { "no arguments" }}")
                    hiddenForGame = false
                    windowState.isMinimized = false
                    raised++
                    PlayArguments.parse(handed.toTypedArray())
                        ?.let(state::playFromShortcut)
                    LaunchLink.find(handed)?.let(state::openLink)
                }
            }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { runCatching { ClassArchives.removeStale() } }
            }
            LaunchedEffect(Unit) { state.checkForUpdates() }
            LaunchedEffect(Unit) { state.checkModUpdates() }
            LaunchedEffect(Unit) { withContext(Dispatchers.IO) { UrlProtocol.register() } }
            LaunchedEffect(Unit) { state.refreshActivity() }
            LaunchedEffect(Unit) { OnlineCounter.start() }
            LaunchedEffect(Unit) {
                DiscordPresence.show(Presence.Launcher)
                DiscordPresence.start()
            }

            // Каждый клиент ждём отдельно: лаунчер возвращается, когда клиент упал или закрылся последний
            val awaitGame: (Process) -> Unit = { process ->
                // Лаунчер уходит с глаз сразу по нажатию «Играть», как раньше
                when (Settings.current.gameWindow) {
                    GameWindowMode.HIDE -> hiddenForGame = true
                    GameWindowMode.MINIMIZE -> {
                        minimizedForGame = true
                        windowState.isMinimized = true
                    }
                    GameWindowMode.KEEP -> Unit
                }
                if (Settings.current.gameWindow != GameWindowMode.KEEP) scope.launch { MemoryRelease.afterWindowClosed() }
                scope.launch {
                    Log.info("waiting for the game to exit, pid=${runCatching { process.pid() }.getOrDefault(-1)}")
                    withContext(Dispatchers.IO) { runCatching { process.waitFor() } }
                    Log.info("game exited with ${process.exitValue()}")
                    val show = state.gameExited(process, process.exitValue())
                    state.refreshActivity()
                    if (show) {
                        hiddenForGame = false
                        if (minimizedForGame) {
                            minimizedForGame = false
                            windowState.isMinimized = false
                        }
                    }
                }
            }

            if (!hiddenForGame) {
                Window(
                    onCloseRequest = {
                        if (state.gameRunning) {
                            Log.info("window closed while the game runs, staying in the background")
                            hiddenForGame = true
                            scope.launch { MemoryRelease.afterWindowClosed() }
                        } else {
                            shutdownAndExit()
                        }
                    },
                    title = "JuxLauncher",
                    state = windowState,
                    onPreviewKeyEvent = { state.onKey(it) },
                ) {
                    window.minimumSize = java.awt.Dimension(900, 560)
                    LaunchedEffect(Unit) {
                        WindowChrome.applyIcon(window)
                        if (!reportedReady) {
                            reportedReady = true
                            Log.info("startup: launcher ready at ${sinceProcessStart()} ms")
                        }
                        if (state.consumePendingPlay()) state.play()
                    }
                    LaunchedEffect(Unit) {
                        snapshotFlow { windowState.isMinimized }.collect { state.windowShown = !it }
                    }
                    val theme = Settings.state.collectAsState().value.theme
                    LaunchedEffect(theme) { WindowChrome.applyTheme(window) }
                    LaunchedEffect(raised) {
                        if (raised > 0) {
                            window.toFront()
                            window.requestFocus()
                        }
                    }
                    JuxTheme {
                        App(state, onGameStarted = awaitGame)
                    }
                }
            }
        }
    }
}

private fun bootstrap(args: Array<String>): SingleInstance {
    val migration = Paths.migrateLegacyLocation()
    Paths.ensureBaseDirs()

    val instance = SingleInstance(Paths.root)
    when (instance.start(args.toList())) {
        SingleInstance.Role.PRIMARY -> Unit
        SingleInstance.Role.HANDED_OVER -> Shell.exitWithoutClassArchive(0)
        SingleInstance.Role.UNREACHABLE -> {
            JOptionPane.showMessageDialog(
                null,
                tr("JuxLauncher завис.\nЗакрой его в ${Shell.taskManager} и запусти снова.", "JuxLauncher is not responding.\nClose it in ${Shell.taskManager} and start it again."),
                "JuxLauncher",
                JOptionPane.WARNING_MESSAGE,
            )
            Shell.exitWithoutClassArchive(0)
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
