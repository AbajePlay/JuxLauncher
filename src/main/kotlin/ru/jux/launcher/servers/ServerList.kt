package ru.jux.launcher.servers

import ru.jux.launcher.core.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

internal sealed interface Nbt {
    data class ByteTag(val value: Byte) : Nbt
    data class ShortTag(val value: Short) : Nbt
    data class IntTag(val value: Int) : Nbt
    data class LongTag(val value: Long) : Nbt
    data class FloatTag(val value: Float) : Nbt
    data class DoubleTag(val value: Double) : Nbt
    class ByteArrayTag(val value: ByteArray) : Nbt
    data class StringTag(val value: String) : Nbt
    data class ListTag(val type: Int, val items: List<Nbt>) : Nbt
    data class CompoundTag(val entries: Map<String, Nbt>) : Nbt
    class IntArrayTag(val value: IntArray) : Nbt
    class LongArrayTag(val value: LongArray) : Nbt

    companion object {
        private const val END = 0
        private const val COMPOUND = 10
        private const val MAX_DEPTH = 64

        fun readRoot(bytes: ByteArray): CompoundTag {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readUnsignedByte() != COMPOUND) throw IOException("не NBT: корень не compound")
            input.readUTF()
            return read(COMPOUND, input, 0) as CompoundTag
        }

        fun writeRoot(root: CompoundTag): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.writeByte(COMPOUND)
                output.writeUTF("")
                write(root, output)
            }
            return buffer.toByteArray()
        }

        private fun read(type: Int, input: DataInput, depth: Int): Nbt {
            if (depth > MAX_DEPTH) throw IOException("NBT слишком глубокий")
            return when (type) {
                1 -> ByteTag(input.readByte())
                2 -> ShortTag(input.readShort())
                3 -> IntTag(input.readInt())
                4 -> LongTag(input.readLong())
                5 -> FloatTag(input.readFloat())
                6 -> DoubleTag(input.readDouble())
                7 -> ByteArrayTag(ByteArray(length(input)).also(input::readFully))
                8 -> StringTag(input.readUTF())
                9 -> {
                    val itemType = input.readUnsignedByte()
                    val count = length(input)
                    ListTag(itemType, List(count) { read(itemType, input, depth + 1) })
                }
                COMPOUND -> {
                    val entries = LinkedHashMap<String, Nbt>()
                    while (true) {
                        val tagType = input.readUnsignedByte()
                        if (tagType == END) break
                        entries[input.readUTF()] = read(tagType, input, depth + 1)
                    }
                    CompoundTag(entries)
                }
                11 -> IntArrayTag(IntArray(length(input)) { input.readInt() })
                12 -> LongArrayTag(LongArray(length(input)) { input.readLong() })
                else -> throw IOException("неизвестный тег NBT $type")
            }
        }

        private fun length(input: DataInput): Int =
            input.readInt().also { if (it !in 0..(1 shl 24)) throw IOException("NBT: длина $it") }

        private fun typeOf(tag: Nbt): Int = when (tag) {
            is ByteTag -> 1
            is ShortTag -> 2
            is IntTag -> 3
            is LongTag -> 4
            is FloatTag -> 5
            is DoubleTag -> 6
            is ByteArrayTag -> 7
            is StringTag -> 8
            is ListTag -> 9
            is CompoundTag -> COMPOUND
            is IntArrayTag -> 11
            is LongArrayTag -> 12
        }

        private fun write(tag: Nbt, output: DataOutput) {
            when (tag) {
                is ByteTag -> output.writeByte(tag.value.toInt())
                is ShortTag -> output.writeShort(tag.value.toInt())
                is IntTag -> output.writeInt(tag.value)
                is LongTag -> output.writeLong(tag.value)
                is FloatTag -> output.writeFloat(tag.value)
                is DoubleTag -> output.writeDouble(tag.value)
                is ByteArrayTag -> {
                    output.writeInt(tag.value.size)
                    output.write(tag.value)
                }
                is StringTag -> output.writeUTF(tag.value)
                is ListTag -> {
                    output.writeByte(if (tag.items.isEmpty()) tag.type else typeOf(tag.items.first()))
                    output.writeInt(tag.items.size)
                    tag.items.forEach { write(it, output) }
                }
                is CompoundTag -> {
                    tag.entries.forEach { (name, value) ->
                        output.writeByte(typeOf(value))
                        output.writeUTF(name)
                        write(value, output)
                    }
                    output.writeByte(END)
                }
                is IntArrayTag -> {
                    output.writeInt(tag.value.size)
                    tag.value.forEach(output::writeInt)
                }
                is LongArrayTag -> {
                    output.writeInt(tag.value.size)
                    tag.value.forEach(output::writeLong)
                }
            }
        }
    }
}

object ServerList {

    const val FILE_NAME = "servers.dat"
    private const val GAME_BACKUP = "servers.dat_old"

    fun seedDefaults(gameDir: Path, defaults: List<ServerEntry> = Servers.all) {
        runCatching { pin(gameDir, defaults, repair = true) }
            .onSuccess { changed -> if (changed) Log.info("pinned ${defaults.joinToString { it.address }} on top of the multiplayer list of $gameDir") }
            .onFailure { Log.warn("could not pin the servers in $FILE_NAME of $gameDir: ${it.message}") }
    }

    internal fun pin(gameDir: Path, defaults: List<ServerEntry>, repair: Boolean): Boolean {
        val file = gameDir.resolve(FILE_NAME)
        val stamp = stampOf(file)
        val missing = Files.notExists(file)
        val read = if (missing) null else parse(file)
        if (read == null && !repair) return false
        val root = read ?: if (missing) Nbt.CompoundTag(emptyMap()) else recover(gameDir)
        val servers = (root.entries["servers"] as? Nbt.ListTag)?.items.orEmpty().filterIsInstance<Nbt.CompoundTag>()
        val ours = defaults.map { normalize(it.address) }.toSet()
        val pinned = defaults.map { server ->
            val matching = servers.filter { addressOf(it)?.let(::normalize) == normalize(server.address) }
            val kept = matching.firstOrNull { !isHidden(it) } ?: matching.firstOrNull()
                ?: return@map Nbt.CompoundTag(linkedMapOf("name" to Nbt.StringTag(server.name), "ip" to Nbt.StringTag(server.address)))
            val shown = if (isHidden(kept)) mapOf("hidden" to Nbt.ByteTag(0)) else emptyMap()
            Nbt.CompoundTag(kept.entries + ("name" to Nbt.StringTag(server.name)) + shown)
        }
        val updated = pinned + servers.filter { addressOf(it)?.let(::normalize) !in ours }
        if (read != null && (updated == servers || stampOf(file) != stamp)) return false
        write(file, Nbt.CompoundTag(root.entries + ("servers" to Nbt.ListTag(10, updated))))
        return true
    }

    class Guard(private val gameDir: Path, private val defaults: List<ServerEntry> = Servers.all) {
        private val file = gameDir.resolve(FILE_NAME)
        private var seen = stampOf(file)
        private var failed: Stamp? = null

        fun check() {
            val now = stampOf(file)
            if (now == null || now == seen) return
            runCatching { pin(gameDir, defaults, repair = false) }
                .onSuccess { changed ->
                    seen = now
                    if (changed) Log.info("the multiplayer list of $gameDir changed, pinned ${defaults.joinToString { it.address }} again")
                }
                .onFailure {
                    if (failed != now) Log.warn("could not pin the servers in $file again: ${it.message}")
                    failed = now
                }
        }
    }

    fun missingIn(from: Path, into: Path): Int = missing(from, into).size

    fun merge(from: Path, into: Path): Int {
        val added = missing(from, into)
        if (added.isEmpty()) return 0
        val file = into.resolve(FILE_NAME)
        val root = if (file.exists()) Nbt.readRoot(file.readBytes()) else Nbt.CompoundTag(emptyMap())
        val servers = (root.entries["servers"] as? Nbt.ListTag)?.items.orEmpty()
        val updated = Nbt.CompoundTag(root.entries + ("servers" to Nbt.ListTag(10, servers + added)))
        write(file, updated)
        return added.size
    }

    private fun parse(file: Path): Nbt.CompoundTag? {
        if (!file.isRegularFile()) return null
        val bytes = file.readBytes()
        return try {
            Nbt.readRoot(bytes)
        } catch (_: IOException) {
            null
        }
    }

    private fun recover(gameDir: Path): Nbt.CompoundTag {
        val broken = gameDir.resolve(FILE_NAME)
        val aside = gameDir.resolve("$FILE_NAME.broken-${System.currentTimeMillis()}")
        Files.move(broken, aside)
        val backup = gameDir.resolve(GAME_BACKUP)
        val restored = runCatching { parse(backup) }.getOrNull()
        Log.warn("$broken could not be read, kept it as ${aside.fileName} and rebuilt the list${if (restored != null) " from $GAME_BACKUP" else ""}")
        return restored ?: Nbt.CompoundTag(emptyMap())
    }

    private fun write(file: Path, root: Nbt.CompoundTag) {
        file.parent.createDirectories()
        file.toFile().takeIf { it.isFile && !it.canWrite() }?.setWritable(true)
        val tmp = Files.createTempFile(file.parent, "servers", ".tmp")
        try {
            Files.write(tmp, Nbt.writeRoot(root))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private data class Stamp(val modified: FileTime, val size: Long)

    private fun stampOf(file: Path): Stamp? =
        runCatching { Files.readAttributes(file, BasicFileAttributes::class.java) }.getOrNull()?.let { Stamp(it.lastModifiedTime(), it.size()) }

    private fun missing(from: Path, into: Path): List<Nbt.CompoundTag> {
        val known = (entries(into).mapNotNull(::addressOf) + Servers.all.map { it.address }).map(::normalize).toSet()
        return entries(from)
            .filter { !isHidden(it) }
            .filter { server -> addressOf(server)?.let(::normalize)?.let { it.isNotEmpty() && it !in known } == true }
            .distinctBy { addressOf(it)?.let(::normalize) }
    }

    private fun entries(gameDir: Path): List<Nbt.CompoundTag> {
        val file = gameDir.resolve(FILE_NAME)
        if (!file.exists()) return emptyList()
        return (Nbt.readRoot(file.readBytes()).entries["servers"] as? Nbt.ListTag)?.items.orEmpty().filterIsInstance<Nbt.CompoundTag>()
    }

    private fun addressOf(server: Nbt.CompoundTag): String? = (server.entries["ip"] as? Nbt.StringTag)?.value

    private fun isHidden(server: Nbt.CompoundTag): Boolean =
        ((server.entries["hidden"] as? Nbt.ByteTag)?.value ?: 0).toInt() != 0

    internal fun normalize(address: String): String =
        address.trim().lowercase().removeSuffix(".").removeSuffix(":25565")
}
