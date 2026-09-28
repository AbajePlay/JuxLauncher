package ru.jux.launcher.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.Shortcuts
import ru.jux.launcher.core.toHex
import ru.jux.launcher.launch.ArgumentBuilder
import ru.jux.launcher.net.Http
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
data class UpdateManifest(
    val version: String,
    val url: String,
    val sha256: String,
    val size: Long = 0,
)

sealed interface UpdateState {
    data object NotConfigured : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: UpdateManifest) : UpdateState
    data class Downloading(val update: UpdateManifest, val fraction: Float) : UpdateState
    data class Failed(val message: String, val update: UpdateManifest?) : UpdateState
}

object UpdateConfig {

    private const val BUILT_IN = "https://github.com/AbajePlay/JuxLauncher/releases/latest/download/update.json"

    val feedUrl: String? by lazy {
        sequenceOf(System.getenv("JUX_UPDATE_URL"), readFromFile(), BUILT_IN)
            .mapNotNull { it?.trim() }
            .firstOrNull { it.startsWith("https://") }
    }

    private fun readFromFile(): String? = runCatching {
        val file = Paths.root.resolve("update_url.txt")
        if (file.exists()) file.readText() else null
    }.getOrNull()
}

object Updater {

    private val _state = MutableStateFlow<UpdateState>(
        if (UpdateConfig.feedUrl == null) UpdateState.NotConfigured else UpdateState.Idle
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String get() = ArgumentBuilder.LAUNCHER_VERSION

    private val VERSION = Regex("""^[0-9A-Za-z.+-]{1,32}$""")
    private val SHA256 = Regex("""^[0-9a-fA-F]{64}$""")

    suspend fun check() {
        val url = UpdateConfig.feedUrl ?: return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Checking) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val manifest = withContext(Dispatchers.IO) { Json.decodeFromString<UpdateManifest>(Http.getString(url)) }
            validate(manifest)
            if (isNewer(manifest.version, currentVersion)) UpdateState.Available(manifest) else UpdateState.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.warn("update check failed: ${e.message}")
            UpdateState.Idle
        }
    }

    suspend fun install(update: UpdateManifest) {
        try {
            val app = Shell.appExecutable
                ?: throw IOException("Обновление ставится только в установленный лаунчер, не при запуске из IDE")
            val file = download(update) { fraction -> _state.value = UpdateState.Downloading(update, fraction) }
            startInstaller(file, app)
        } catch (e: CancellationException) {
            _state.value = UpdateState.Available(update)
            throw e
        } catch (e: Exception) {
            Log.warn("update to ${update.version} failed", e)
            _state.value = UpdateState.Failed(e.message ?: "Обновление не удалось", update)
            throw e
        }
    }

    internal fun isNewer(remote: String, local: String): Boolean {
        fun split(version: String): Pair<List<Int>, String?> {
            val (numbers, suffix) = version.trim().removePrefix("v").split('-', limit = 2)
                .let { it[0] to it.getOrNull(1) }
            return numbers.split('.').map { it.toIntOrNull() ?: 0 } to suffix
        }
        val (remoteParts, remoteSuffix) = split(remote)
        val (localParts, localSuffix) = split(local)
        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val a = remoteParts.getOrElse(i) { 0 }
            val b = localParts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return localSuffix != null && remoteSuffix == null
    }

    internal fun validate(manifest: UpdateManifest) {
        if (!VERSION.matches(manifest.version)) throw IOException("в фиде некорректная версия")
        if (!manifest.url.startsWith("https://")) throw IOException("установщик должен раздаваться по https")
        if (!SHA256.matches(manifest.sha256)) throw IOException("в фиде нет корректного sha256 установщика")
    }

    internal suspend fun download(update: UpdateManifest, onFraction: (Float) -> Unit = {}): Path =
        withContext(Dispatchers.IO) {
            val extension = if (update.url.substringBefore('?').endsWith(".exe", ignoreCase = true)) "exe" else "msi"
            val dir = Paths.cache.resolve("updates").also { it.createDirectories() }
            val target = dir.resolve("JuxLauncher-${update.version}.$extension")
            val part = dir.resolve("${target.fileName}.part")

            try {
                Http.client.newCall(Http.request(update.url)).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} при загрузке обновления")
                    val body = response.body ?: throw IOException("пустой ответ сервера обновлений")
                    val total = update.size.takeIf { it > 0 } ?: body.contentLength()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    body.byteStream().use { input ->
                        Files.newOutputStream(part).use { output ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                done += n
                                if (total > 0) onFraction((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                    val actual = digest.digest().toHex()
                    if (!actual.equals(update.sha256, ignoreCase = true)) {
                        throw IOException("контрольная сумма обновления не сошлась — файл повреждён или подменён")
                    }
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
                target
            } catch (e: Throwable) {
                part.deleteIfExists()
                throw e
            }
        }

    internal fun msiArguments(installer: Path, desktopShortcut: Boolean): String {
        val base = "/i \"$installer\" /passive /norestart"
        return if (desktopShortcut) base else "$base JP_INSTALL_DESKTOP_SHORTCUT=\"\""
    }

    private fun startInstaller(installer: Path, app: Path) {
        val pid = ProcessHandle.current().pid()
        val install = if (installer.toString().endsWith(".msi")) {
            val keepDesktop = Shortcuts.appOnDesktop(app).exists()
            "Start-Process -FilePath 'msiexec.exe' -ArgumentList " +
                Shell.psLiteral(msiArguments(installer, keepDesktop)) + " -Wait"
        } else {
            "Start-Process -FilePath ${Shell.psLiteral(installer.toString())} -Wait"
        }
        val script = listOf(
            "Wait-Process -Id $pid -Timeout 60 -ErrorAction SilentlyContinue",
            install,
            "Start-Process -FilePath ${Shell.psLiteral(app.toString())}",
        ).joinToString("\n")
        Shell.runHidden(Shell.powershell(script), waitMs = 0)
            ?: throw IOException("Не удалось запустить установщик обновления")
        Log.info("update installer handed over: $installer")
    }
}
