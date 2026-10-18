package ru.jux.launcher.instance

import ru.jux.launcher.servers.Nbt
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

internal fun writeWorld(gameDir: Path, folder: String, name: String, version: String, extra: Map<String, Nbt> = emptyMap()): Path {
    val dir = gameDir.resolve("saves").resolve(folder).createDirectories()
    val data = Nbt.CompoundTag(
        mapOf(
            "LevelName" to Nbt.StringTag(name),
            "Version" to Nbt.CompoundTag(mapOf("Name" to Nbt.StringTag(version))),
        ) + extra,
    )
    val bytes = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(Nbt.writeRoot(Nbt.CompoundTag(mapOf("Data" to data)))) }
    }.toByteArray()
    dir.resolve("level.dat").writeBytes(bytes)
    dir.resolve("region").createDirectories().resolve("r.0.0.mca").writeBytes(ByteArray(4096))
    return dir
}
