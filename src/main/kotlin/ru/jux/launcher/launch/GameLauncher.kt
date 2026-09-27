package ru.jux.launcher.launch

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.jux.launcher.auth.Account
import ru.jux.launcher.auth.AccountManager
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.VerifyCache
import ru.jux.launcher.install.InstalledVersion
import ru.jux.launcher.install.LoaderInstaller
import ru.jux.launcher.install.VersionInstaller
import ru.jux.launcher.instance.InstanceStore
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.mods.PerformancePack
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.net.Downloader
import ru.jux.launcher.runtime.JavaComponents
import ru.jux.launcher.runtime.JavaManager
import ru.jux.launcher.servers.ServerList

data class LaunchResult(val process: Process, val gameDir: Path, val logFile: Path)

class PreparedLaunch(
    val installed: InstalledVersion,
    val gameDir: Path,
    val javaExecutable: Path,
    val memoryMb: Int,
)

object GameLauncher {

    suspend fun launch(
        versionId: String,
        account: Account,
        loader: LoaderKind = LoaderKind.VANILLA,
        serverAddress: String? = null,
        onStage: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
        onNotice: (String) -> Unit = {},
    ): LaunchResult {
        val prepared = prepare(versionId, loader, onStage, onProgress, onNotice)
        withContext(Dispatchers.IO) { runCatching { ServerList.seedDefaults(prepared.gameDir) } }

        onStage("Проверка аккаунта")
        val readyAccount = AccountManager.prepareForLaunch(account, onStage)

        onStage("Запуск игры")
        val command = withContext(Dispatchers.IO) {
            ArgumentBuilder(
                installed = prepared.installed,
                account = readyAccount,
                settings = Settings.current.copy(memoryMb = prepared.memoryMb),
                javaExecutable = prepared.javaExecutable,
                gameDir = prepared.gameDir,
                serverAddress = serverAddress,
            ).build()
        }

        logCommand(command, readyAccount)

        val logFile = prepared.gameDir.resolve(GAME_LOG)
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(command)
                .directory(prepared.gameDir.toFile())
                .redirectOutput(logFile.toFile())
                .redirectErrorStream(true)
                .start()
        }

        Log.info("game started, pid=${runCatching { process.pid() }.getOrDefault(-1)}")
        return LaunchResult(process, prepared.gameDir, logFile)
    }

    suspend fun prepare(
        versionId: String,
        loader: LoaderKind = LoaderKind.VANILLA,
        onStage: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
        onNotice: (String) -> Unit = {},
    ): PreparedLaunch {
        val settings = Settings.current
        if (settings.forceVerify) {
            Log.info("forced verification requested, dropping hash cache")
            VerifyCache.clear()
            Settings.update { it.copy(forceVerify = false) }
        }

        onStage("Чтение версии $versionId")
        val installer = VersionInstaller(
            downloader = Downloader(
                concurrency = settings.downloadConcurrency.takeIf { it > 0 } ?: Downloader.DEFAULT_CONCURRENCY
            )
        )

        val baseVersion = withContext(Dispatchers.IO) { installer.resolve(versionId) }

        onStage("Подготовка Java")
        val javaExecutable = resolveJava(
            baseVersion.javaVersion?.component,
            baseVersion.javaVersion?.majorVersion,
            onProgress,
        )

        val gameDir = Settings.gameDir(versionId, loader).also { it.createDirectories() }
        val options = InstanceStore.get(gameDir)

        val runLoader = if (options.fpsBoost && loader.supportsBoost) {
            applyBoost(gameDir, versionId, loader, onStage, onProgress, onNotice)
        } else {
            loader
        }

        val profileId = if (runLoader.isModded) {
            try {
                LoaderInstaller.ensureProfile(
                    kind = runLoader,
                    gameVersion = versionId,
                    javaExecutable = javaExecutable,
                    onStage = onStage,
                    onProgress = onProgress,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loader.isModded) throw e
                Log.warn("fabric for the boost unavailable, starting vanilla: ${e.message}")
                onNotice("FPS-буст в этот раз не включился: ${e.message}")
                versionId
            }
        } else {
            versionId
        }

        val version = if (profileId == versionId) baseVersion
        else withContext(Dispatchers.IO) { installer.resolve(profileId) }

        val installed = installer.install(version, gameDir, onStage, onProgress)
        return PreparedLaunch(installed, gameDir, javaExecutable, settings.memoryMb)
    }

    private suspend fun applyBoost(
        gameDir: Path,
        versionId: String,
        loader: LoaderKind,
        onStage: (String) -> Unit,
        onProgress: (DownloadProgress) -> Unit,
        onNotice: (String) -> Unit,
    ): LoaderKind {
        val boost = PerformancePack.sync(gameDir, versionId, onStage, onProgress)
        when {
            !boost.active && boost.offline ->
                onNotice("FPS-буст не установился: нет связи с Modrinth. Игра запущена без него.")
            !boost.active ->
                onNotice("Для $versionId модов FPS-буста пока нет. Игра запущена без него.")
            boost.missing.isNotEmpty() ->
                onNotice("FPS-буст без ${boost.missing.joinToString()}: для $versionId они ещё не вышли.")
        }
        return if (boost.active) LoaderKind.FABRIC else loader
    }

    private suspend fun resolveJava(
        component: String?,
        majorVersion: Int?,
        onProgress: (DownloadProgress) -> Unit,
    ): Path {
        val wanted = component ?: JavaComponents.forMajor(majorVersion ?: 8)
        val major = majorVersion ?: 8

        runCatching { return JavaManager.ensure(wanted, onProgress = onProgress) }
            .onFailure { Log.warn("could not install runtime $wanted: ${it.message}") }

        JavaManager.findLocal(major)?.let { return it }

        throw IOException(
            "Не удалось получить Java $major для этой версии. " +
                "Проверьте интернет или установите JDK $major вручную."
        )
    }

    private fun logCommand(command: List<String>, account: Account) {
        val token = account.accessToken
        val safe = command.joinToString(" ") { part ->
            if (token.length > 8 && part.contains(token)) part.replace(token, "<redacted>") else part
        }
        Log.info("command: $safe")
    }

    const val GAME_LOG = "latest-game.log"
}
