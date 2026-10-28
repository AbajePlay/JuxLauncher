package ru.jux.launcher.discord

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.Shell
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class DiscordIpc private constructor(private val pipe: Pipe) : Closeable {

    fun setActivity(activity: JsonObject?): JsonObject = command("SET_ACTIVITY", buildJsonObject {
        put("pid", ProcessHandle.current().pid())
        if (activity != null) put("activity", activity)
    })

    private fun command(name: String, args: JsonObject): JsonObject {
        write(OP_FRAME, buildJsonObject {
            put("cmd", name)
            put("args", args)
            put("nonce", UUID.randomUUID().toString())
        })
        val reply = read()
        if (reply.text("evt") == "ERROR") {
            val data = reply["data"] as? JsonObject
            throw IOException("Discord отклонил $name: ${data?.text("message") ?: reply}")
        }
        return reply
    }

    private fun handshake(appId: String) {
        write(OP_HANDSHAKE, buildJsonObject {
            put("v", 1)
            put("client_id", appId)
        })
        val ready = read()
        if (ready.text("evt") != "READY") throw IOException("Discord не принял приложение: ${ready.text("message") ?: ready}")
    }

    private fun write(op: Int, payload: JsonObject) {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val frame = ByteBuffer.allocate(HEADER + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(op)
            .putInt(body.size)
            .put(body)
        pipe.write(frame.array())
    }

    private fun read(): JsonObject {
        val header = ByteArray(HEADER)
        pipe.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val op = buffer.int
        val length = buffer.int
        if (length !in 0..MAX_FRAME) throw IOException("Discord прислал кадр в $length байт")
        val body = ByteArray(length)
        pipe.readFully(body)
        val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
        if (op == OP_CLOSE) throw IOException("Discord закрыл соединение: ${json.text("message") ?: json}")
        return json
    }

    override fun close() {
        runCatching { pipe.close() }
    }

    companion object {
        private const val OP_HANDSHAKE = 0
        private const val OP_FRAME = 1
        private const val OP_CLOSE = 2
        private const val HEADER = 8
        private const val MAX_FRAME = 1 shl 20
        private const val PIPES = 10

        fun connect(appId: String): DiscordIpc {
            var refused: IOException? = null
            for (address in addresses()) {
                val pipe = try {
                    open(address)
                } catch (e: IOException) {
                    continue
                }
                val ipc = DiscordIpc(pipe)
                try {
                    ipc.handshake(appId)
                    return ipc
                } catch (e: IOException) {
                    ipc.close()
                    if (refused == null) refused = e
                }
            }
            throw refused ?: IOException("Discord не запущен")
        }

        /** На Windows Discord слушает именованный канал, на macOS и Linux — Unix-сокет во временной папке. */
        internal fun addresses(env: (String) -> String? = System::getenv, windows: Boolean = Shell.isWindows): List<String> {
            if (windows) return (0 until PIPES).map { "\\\\.\\pipe\\discord-ipc-$it" }
            val roots = listOf("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP").mapNotNull { env(it)?.takeIf(String::isNotBlank) } + "/tmp"
            // Discord из Flatpak и Snap кладёт сокет в свою подпапку
            val flavours = listOf("", "app/com.discordapp.Discord", "snap.discord", ".flatpak/dev.vencord.Vesktop/xdg-run")
            return roots.distinct()
                .flatMap { root -> flavours.map { Path.of(root, it) } }
                .flatMap { dir -> (0 until PIPES).map { dir.resolve("discord-ipc-$it").toString() } }
                .distinct()
        }

        private fun open(address: String): Pipe =
            if (Shell.isWindows) WindowsPipe(RandomAccessFile(address, "rw")) else UnixPipe.open(Path.of(address))

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

private interface Pipe : Closeable {
    fun write(bytes: ByteArray)
    fun readFully(bytes: ByteArray)
}

private class WindowsPipe(private val file: RandomAccessFile) : Pipe {
    override fun write(bytes: ByteArray) = file.write(bytes)
    override fun readFully(bytes: ByteArray) = file.readFully(bytes)
    override fun close() = file.close()
}

private class UnixPipe(private val channel: SocketChannel) : Pipe {
    override fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun readFully(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw EOFException("Discord закрыл сокет")
        }
    }

    override fun close() = channel.close()

    companion object {
        fun open(path: Path): UnixPipe {
            if (!Files.exists(path)) throw IOException("нет сокета $path")
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                channel.connect(UnixDomainSocketAddress.of(path))
            } catch (e: IOException) {
                channel.close()
                throw e
            }
            return UnixPipe(channel)
        }
    }
}
