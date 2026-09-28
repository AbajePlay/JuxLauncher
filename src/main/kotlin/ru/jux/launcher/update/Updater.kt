package ru.jux.launcher.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.Shortcuts
import ru.jux.launcher.core.toHex
import ru.jux.launcher.launch.ArgumentBuilder
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import ru.jux.launcher.net.Http
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    data class Downloading(val update: UpdateManifest, val fraction: Float, val bytesPerSecond: Long = 0) : UpdateState
    data class Installing(val update: UpdateManifest) : UpdateState
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
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing || _state.value is UpdateState.Checking) return
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
            val file = download(update) { fraction, speed -> _state.value = UpdateState.Downloading(update, fraction, speed) }
            _state.value = UpdateState.Installing(update)
            startInstaller(file, app, update.version)
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

    internal suspend fun download(
        update: UpdateManifest,
        streams: Int = STREAMS,
        pieceBytes: Long = PIECE_BYTES,
        onProgress: (fraction: Float, bytesPerSecond: Long) -> Unit = { _, _ -> },
    ): Path = withContext(Dispatchers.IO) {
        val extension = if (update.url.substringBefore('?').endsWith(".exe", ignoreCase = true)) "exe" else "msi"
        val dir = Paths.cache.resolve("updates").also { it.createDirectories() }
        val target = dir.resolve("JuxLauncher-${update.version}.$extension")
        val part = dir.resolve("${target.fileName}.part")

        try {
            val source = probe(update.url)
            val total = update.size.takeIf { it > 0 } ?: source.length
            val meter = Meter(total, onProgress)
            if (source.ranges && total > pieceBytes) {
                RandomAccessFile(part.toFile(), "rw").use { file ->
                    file.setLength(total)
                    val pieces = (0 until (total + pieceBytes - 1) / pieceBytes).map { it * pieceBytes }
                    val next = AtomicInteger()
                    coroutineScope {
                        repeat(streams) {
                            async {
                                while (true) {
                                    val start = pieces.getOrNull(next.getAndIncrement()) ?: break
                                    fetchRange(source.url, file.channel, start, minOf(total, start + pieceBytes) - 1, meter)
                                }
                            }
                        }
                    }
                }
            } else {
                fetchWhole(source.url, part, meter)
            }
            if (!sha256Of(part).equals(update.sha256, ignoreCase = true)) {
                throw IOException("контрольная сумма обновления не сошлась — файл повреждён или подменён")
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            target
        } catch (e: Throwable) {
            part.deleteIfExists()
            throw e
        }
    }

    private val separate: OkHttpClient by lazy { Http.client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build() }

    private class Source(val url: String, val length: Long, val ranges: Boolean)

    private fun probe(url: String): Source = runCatching {
        separate.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            Source(
                url = response.request.url.toString(),
                length = response.header("Content-Length")?.toLongOrNull() ?: -1,
                ranges = response.header("Accept-Ranges").equals("bytes", ignoreCase = true),
            )
        }
    }.getOrNull() ?: Source(url, -1, ranges = false)

    private fun fetchRange(url: String, channel: FileChannel, start: Long, end: Long, meter: Meter) {
        var position = start
        var attempt = 0
        while (position <= end) {
            try {
                val request = Request.Builder().url(url).header("Range", "bytes=$position-$end").header("Connection", "close").build()
                separate.newCall(request).execute().use { response ->
                    if (response.code != 206) throw IOException("HTTP ${response.code} при загрузке обновления")
                    val input = response.body?.byteStream() ?: throw IOException("пустой ответ сервера обновлений")
                    val buffer = ByteArray(1 shl 16)
                    while (position <= end) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), end - position + 1).toInt())
                        if (n <= 0) break
                        channel.write(ByteBuffer.wrap(buffer, 0, n), position)
                        position += n
                        meter.add(n)
                    }
                }
                if (position <= end) throw IOException("соединение оборвалось на загрузке обновления")
            } catch (e: IOException) {
                if (++attempt >= ATTEMPTS) throw e
                Thread.sleep(RETRY_MILLIS * attempt)
            }
        }
    }

    private fun fetchWhole(url: String, part: Path, meter: Meter) {
        separate.newCall(Http.request(url)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} при загрузке обновления")
            val body = response.body ?: throw IOException("пустой ответ сервера обновлений")
            body.byteStream().use { input ->
                Files.newOutputStream(part).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        meter.add(n)
                    }
                }
            }
        }
    }

    private fun sha256Of(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private class Meter(private val total: Long, private val report: (Float, Long) -> Unit) {
        private val done = AtomicLong()
        private var sampledAt = System.nanoTime()
        private var sampledBytes = 0L
        private var speed = 0L

        fun add(bytes: Int) {
            val now = done.addAndGet(bytes.toLong())
            val time = System.nanoTime()
            synchronized(this) {
                val elapsed = time - sampledAt
                if (elapsed < SAMPLE_NANOS && now < total) return
                val instant = (now - sampledBytes) * 1_000_000_000L / elapsed.coerceAtLeast(1)
                speed = if (speed == 0L) instant else (speed * 2 + instant) / 3
                sampledAt = time
                sampledBytes = now
            }
            if (total > 0) report((now.toDouble() / total).toFloat().coerceIn(0f, 1f), speed)
        }
    }

    internal fun msiArguments(installer: Path, desktopShortcut: Boolean, log: Path): String {
        val shortcut = if (desktopShortcut) "" else " JP_INSTALL_DESKTOP_SHORTCUT=\"\""
        return "/i \"$installer\" /qn /norestart$shortcut /l*v \"$log\""
    }

    private suspend fun startInstaller(installer: Path, app: Path, version: String) {
        val dir = Paths.cache.resolve("update")
        val ready = dir.resolve("ready")
        val script = withContext(Dispatchers.IO) {
            UpdateSplash.prepare(dir)
            ready.deleteIfExists()
            val msi = installer.toString().endsWith(".msi")
            UpdateSplash.script(
                launcher = ProcessHandle.current().pid(),
                installer = if (msi) "msiexec.exe" else installer.toString(),
                arguments = if (msi) msiArguments(installer, Shortcuts.appOnDesktop(app).exists(), dir.resolve("install.log")) else "",
                app = app,
                assets = dir,
                version = version,
                ready = ready,
            )
        }
        Shell.runHidden(Shell.powershell(script), waitMs = 0)
            ?: throw IOException("Не удалось запустить установщик обновления")
        Log.info("update installer handed over: $installer")
        withTimeoutOrNull(READY_MILLIS) { while (!ready.exists()) delay(50) }
            ?: Log.warn("update splash did not show up in $READY_MILLIS ms")
    }

    private const val READY_MILLIS = 10_000L
    private const val STREAMS = 6
    private const val PIECE_BYTES = 4L shl 20
    private const val ATTEMPTS = 4
    private const val RETRY_MILLIS = 500L
    private const val SAMPLE_NANOS = 250_000_000L
}
