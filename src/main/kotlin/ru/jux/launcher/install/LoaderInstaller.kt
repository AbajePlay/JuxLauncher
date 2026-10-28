package ru.jux.launcher.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.Storage
import ru.jux.launcher.core.tr
import ru.jux.launcher.core.writeAtomically
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.meta.LoaderRepository
import ru.jux.launcher.meta.OptiFineRepository
import ru.jux.launcher.meta.VersionJson
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.net.DownloadTask
import ru.jux.launcher.net.Downloader
import ru.jux.launcher.net.Http
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

object LoaderInstaller {

    private const val INSTALLER_TIMEOUT_MINUTES = 15L
    private const val INSTALLER_LOG_LINES = 200

    suspend fun ensureProfile(
        kind: LoaderKind,
        gameVersion: String,
        javaExecutable: suspend () -> Path,
        pinned: String? = null,
        onStage: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        if (kind == LoaderKind.VANILLA) return@withContext gameVersion

        if (pinned == null) onStage(tr("Ищу последнюю сборку ${kind.label}", "Looking for the latest ${kind.label} build"))
        val loaderVersion = pinned ?: LoaderRepository.latestBuild(kind, gameVersion)
        if (loaderVersion == null) {
            installedProfile(kind, gameVersion)?.let {
                Log.warn("${kind.label} builds for $gameVersion unavailable, launching installed $it")
                return@withContext it
            }
            throw IOException(
                tr(
                    "${kind.label} для $gameVersion не найден — " +
                        "ещё не вышел или нет связи",
                    "${kind.label} for $gameVersion not found — " +
                        "not released yet or no connection",
                )
            )
        }
        Log.info("${kind.label} build chosen for $gameVersion: $loaderVersion")

        when (kind) {
            LoaderKind.FABRIC, LoaderKind.QUILT ->
                installFabricLike(kind, gameVersion, loaderVersion, onStage)
            LoaderKind.FORGE, LoaderKind.NEOFORGE ->
                installViaInstaller(kind, gameVersion, loaderVersion, javaExecutable, onStage, onProgress)
            LoaderKind.OPTIFINE ->
                installOptiFine(gameVersion, loaderVersion, javaExecutable, onStage, onProgress)
            LoaderKind.VANILLA -> gameVersion
        }
    }

    // Установщик OptiFine ставит только в папку официального лаунчера (%APPDATA%\.minecraft, на macOS и Linux —
    // внутри домашней папки): подсовываем ему временную папку с чистой версией, а готовый профиль и библиотеки переносим к себе
    private suspend fun installOptiFine(
        gameVersion: String,
        edition: String,
        javaExecutable: suspend () -> Path,
        onStage: (String) -> Unit,
        onProgress: (DownloadProgress) -> Unit,
    ): String {
        installedProfile(LoaderKind.OPTIFINE, gameVersion)
            ?.takeIf { it.endsWith(edition) && isInstalled(it) }
            ?.let {
                Log.debug("OptiFine profile $it already present")
                return it
            }

        onStage(tr("Скачиваю OptiFine $edition", "Downloading OptiFine $edition"))
        val build = OptiFineRepository.build(gameVersion, edition)
        val installerJar = Paths.cache.resolve("installers").resolve(build.file)
        val base = Json.decodeFromString<VersionJson>(Paths.versionJson(gameVersion).readText())
        val client = base.downloads?.client ?: throw IOException(tr("У $gameVersion нет файла игры для OptiFine", "$gameVersion has no game file for OptiFine"))
        Downloader().run(
            listOf(
                DownloadTask(OptiFineRepository.downloadUrl(build), installerJar, label = build.file),
                DownloadTask(client.url, Paths.versionJar(gameVersion), client.sha1, client.size, label = "$gameVersion.jar"),
            ),
            onProgress,
        )

        val staging = Paths.cache.resolve("optifine-install")
        Storage.deleteTree(staging)
        val minecraft = Paths.officialMinecraft(home = staging, appData = staging.toString())
        val stagedVersion = minecraft.resolve("versions").resolve(gameVersion).also { it.createDirectories() }
        Files.copy(Paths.versionJson(gameVersion), stagedVersion.resolve("$gameVersion.json"))
        Files.copy(Paths.versionJar(gameVersion), stagedVersion.resolve("$gameVersion.jar"))
        minecraft.resolve("launcher_profiles.json").writeText("""{"profiles":{}}""")

        onStage(tr("Устанавливаю OptiFine $edition", "Installing OptiFine $edition"))
        runJava(
            javaExecutable(),
            listOf(
                "-Djava.awt.headless=true",
                "-Duser.home=${staging.toAbsolutePath()}",
                "-cp", installerJar.toAbsolutePath().toString(),
                "optifine.Installer",
            ),
            workDir = staging,
            log = installerJar.resolveSibling("optifine-output.log"),
            env = mapOf("APPDATA" to staging.toAbsolutePath().toString()),
        )

        val profile = minecraft.resolve("versions").listDirectoryEntries()
            .firstOrNull { LoaderKind.OPTIFINE.ownsProfile(it.name, gameVersion) && it.resolve("${it.name}.json").exists() }
            ?: throw IOException(tr("OptiFine не установился — подробности в логе лаунчера", "OptiFine did not install — see the launcher log"))
        val id = profile.name
        Paths.versionDir(id).createDirectories()
        Files.copy(profile.resolve("$id.json"), Paths.versionJson(id), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(Paths.versionJar(gameVersion), Paths.versionJar(id), StandardCopyOption.REPLACE_EXISTING)
        minecraft.resolve("libraries").toFile().copyRecursively(Paths.libraries.toFile(), overwrite = true)
        Storage.deleteTree(staging)

        Paths.versionDir(id).resolve(INSTALLED_MARKER).writeText("")
        Log.info("OptiFine profile installed: $id")
        return id
    }

    private fun installFabricLike(
        kind: LoaderKind,
        gameVersion: String,
        loaderVersion: String,
        onStage: (String) -> Unit,
    ): String {
        predictedProfileId(kind, gameVersion, loaderVersion)
            .takeIf { Paths.versionJson(it).exists() }
            ?.let {
                Log.debug("${kind.label} profile $it already present")
                return it
            }

        onStage(tr("Получаю профиль ${kind.label} $loaderVersion", "Getting the ${kind.label} $loaderVersion profile"))
        val url = LoaderRepository.profileUrl(kind, gameVersion, loaderVersion)
        val text = Http.getString(url)

        val parsed = Json.decodeFromString<VersionJson>(text)
        val id = parsed.id.ifBlank {
            throw IOException(tr("${kind.label} вернул профиль без id", "${kind.label} returned a profile without an id"))
        }

        Paths.versionJson(id).writeAtomically(text)
        Log.info("${kind.label} profile installed: $id")
        return id
    }

    private suspend fun installViaInstaller(
        kind: LoaderKind,
        gameVersion: String,
        loaderVersion: String,
        javaExecutable: suspend () -> Path,
        onStage: (String) -> Unit,
        onProgress: (DownloadProgress) -> Unit,
    ): String {
        locateInstalledProfile(kind, gameVersion, loaderVersion)
            ?.takeIf(::isInstalled)
            ?.let {
                Log.debug("${kind.label} profile $it already present")
                return it
            }

        onStage(tr("Скачиваю установщик ${kind.label} $loaderVersion", "Downloading the ${kind.label} $loaderVersion installer"))
        val url = LoaderRepository.installerUrl(kind, gameVersion, loaderVersion)
        val installerDir = Paths.cache.resolve("installers").also { it.createDirectories() }
        val installerJar = installerDir.resolve(url.substringAfterLast('/'))

        Downloader().run(
            listOf(DownloadTask(url = url, dest = installerJar, label = installerJar.fileName.toString())),
            onProgress,
        )
        if (!installerJar.exists()) throw IOException(tr("Установщик ${kind.label} не скачался", "The ${kind.label} installer did not download"))

        val legacy = LegacyForge.read(installerJar)
        val id = if (legacy != null) {
            onStage(tr("Устанавливаю ${kind.label}", "Installing ${kind.label}"))
            LegacyForge.install(legacy, installerJar)
        } else {
            ensureLauncherProfilesStub()

            val java = javaExecutable()
            onStage(tr("Устанавливаю ${kind.label}", "Installing ${kind.label}"))
            runJava(
                java,
                listOf("-jar", installerJar.toAbsolutePath().toString(), "--installClient", Paths.root.toAbsolutePath().toString()),
                workDir = Paths.root,
                log = installerJar.resolveSibling("installer-output.log"),
            )

            locateInstalledProfile(kind, gameVersion, loaderVersion)
                ?: throw IOException(
                    tr(
                        "${kind.label} отработал, но профиль не найден в ${Paths.versions}. " +
                            "Подробности в логе лаунчера.",
                        "${kind.label} finished, but no profile was found in ${Paths.versions}. " +
                            "See the launcher log.",
                    )
                )
        }
        Paths.versionDir(id).resolve(INSTALLED_MARKER).writeText("")
        Log.info("${kind.label} profile installed: $id")
        return id
    }

    private const val INSTALLED_MARKER = "jux-installed"

    private fun isInstalled(id: String): Boolean =
        Paths.versionJson(id).exists() && Paths.versionDir(id).resolve(INSTALLED_MARKER).exists()

    private suspend fun runJava(
        javaExecutable: Path,
        arguments: List<String>,
        workDir: Path,
        log: Path,
        env: Map<String, String> = emptyMap(),
    ) {
        val command = listOf(javaExecutable.toAbsolutePath().toString()) + arguments
        Log.info("running installer: ${command.joinToString(" ")}")

        val process = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .apply { environment().putAll(env) }
            .start()
        try {
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(INSTALLER_TIMEOUT_MINUTES)
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() > deadline) {
                    throw IOException(tr("Установщик не завершился за $INSTALLER_TIMEOUT_MINUTES минут", "The installer did not finish in $INSTALLER_TIMEOUT_MINUTES minutes"))
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }

        val lines = runCatching { String(Files.readAllBytes(log), nativeCharset) }.getOrDefault("")
            .lineSequence().filter { it.isNotBlank() }.toList()
        lines.takeLast(INSTALLER_LOG_LINES).forEach { Log.debug("installer: $it") }

        val exit = process.exitValue()
        if (exit != 0) {
            throw IOException(tr("Установщик завершился с кодом $exit\n", "The installer exited with code $exit\n") + lines.takeLast(6).joinToString("\n"))
        }
    }

    private val nativeCharset: Charset =
        runCatching { Charset.forName(System.getProperty("native.encoding")) }.getOrDefault(Charset.defaultCharset())

    private fun ensureLauncherProfilesStub() {
        val file = Paths.root.resolve("launcher_profiles.json")
        if (file.exists()) return
        runCatching {
            file.writeAtomically("""{"profiles":{},"selectedProfile":"","clientToken":"","version":3}""")
        }.onFailure { Log.warn("could not create launcher_profiles.json stub", it) }
    }

    private fun predictedProfileId(kind: LoaderKind, gameVersion: String, loaderVersion: String): String =
        when (kind) {
            LoaderKind.FABRIC -> "fabric-loader-$loaderVersion-$gameVersion"
            LoaderKind.QUILT -> "quilt-loader-$loaderVersion-$gameVersion"
            LoaderKind.FORGE -> "$gameVersion-forge-$loaderVersion"
            LoaderKind.NEOFORGE -> "neoforge-$loaderVersion"
            LoaderKind.OPTIFINE -> "$gameVersion-OptiFine_$loaderVersion"
            LoaderKind.VANILLA -> gameVersion
        }

    private fun installedProfile(kind: LoaderKind, gameVersion: String): String? = runCatching {
        Paths.versions.listDirectoryEntries()
            .filter { it.isDirectory() }
            .filter { dir ->
                val id = dir.fileName.toString()
                kind.ownsProfile(id, gameVersion) && dir.resolve("$id.json").exists() &&
                    (kind == LoaderKind.FABRIC || kind == LoaderKind.QUILT || isInstalled(id))
            }
            .maxByOrNull { it.getLastModifiedTime().toMillis() }
            ?.fileName?.toString()
    }.getOrNull()

    private fun locateInstalledProfile(
        kind: LoaderKind,
        gameVersion: String,
        loaderVersion: String,
    ): String? {
        predictedProfileId(kind, gameVersion, loaderVersion)
            .takeIf { Paths.versionJson(it).exists() }
            ?.let { return it }

        val marker = if (kind == LoaderKind.NEOFORGE) "neoforge" else "forge"
        return runCatching {
            Paths.versions.listDirectoryEntries()
                .filter { it.isDirectory() }
                .filter { dir ->
                    val name = dir.fileName.toString()
                    name.contains(marker, ignoreCase = true) &&
                        name.contains(loaderVersion) &&
                        dir.resolve("$name.json").exists()
                }
                .maxByOrNull { it.getLastModifiedTime().toMillis() }
                ?.fileName?.toString()
        }.getOrNull()
    }
}
