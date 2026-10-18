package ru.jux.launcher.instance

import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Storage
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.servers.Nbt
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.relativeTo

data class LevelInfo(
    val name: String? = null,
    val gameVersion: String? = null,
    val lastPlayed: Long = 0,
    val gameType: Int? = null,
    val hardcore: Boolean = false,
)

data class WorldInfo(
    val folder: String,
    val name: String,
    val level: LevelInfo,
    val bytes: Long,
    val icon: Path?,
) {
    val mode: String?
        get() = when {
            level.hardcore -> "Хардкор"
            else -> when (level.gameType) {
                0 -> "Выживание"
                1 -> "Творчество"
                2 -> "Приключение"
                3 -> "Наблюдение"
                else -> null
            }
        }
}

data class WorldBackup(val file: Path, val world: String, val createdAt: Long, val bytes: Long, val auto: Boolean)

object Worlds {

    const val SAVES = "saves"
    const val BACKUPS = "backups"
    private const val LEVEL = "level.dat"
    private const val ICON = "icon.png"
    private const val LOCK = "session.lock"
    private const val AUTO = "-auto"
    private const val AUTO_KEEP = 3
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val FORMATTING = Regex("§.")
    private val ILLEGAL = Regex("""[\\/:*?"<>|]""")

    fun savesDir(gameDir: Path): Path = gameDir.resolve(SAVES)

    fun backupsDir(gameDir: Path): Path = gameDir.resolve(BACKUPS)

    fun list(gameDir: Path): List<WorldInfo> =
        dirs(savesDir(gameDir))
            .filter { it.resolve(LEVEL).exists() }
            .map { world ->
                val level = readLevel(world)
                WorldInfo(
                    folder = world.name,
                    name = level.name ?: world.name,
                    level = level,
                    bytes = Storage.sizeOf(world),
                    icon = world.resolve(ICON).takeIf { it.exists() },
                )
            }
            .sortedByDescending { it.level.lastPlayed.takeIf { played -> played > 0 } ?: modified(it.folder, gameDir) }

    fun backups(gameDir: Path): List<WorldBackup> =
        runCatching { backupsDir(gameDir).listDirectoryEntries("*.zip") }.getOrDefault(emptyList())
            .mapNotNull { file ->
                val world = runCatching { ZipFile(file.toFile()).use { rootOf(it) } }.getOrNull() ?: return@mapNotNull null
                WorldBackup(file, world, modifiedAt(file), file.fileSize(), file.nameWithoutExtension.endsWith(AUTO))
            }
            .sortedByDescending { it.createdAt }

    fun backup(
        gameDir: Path,
        folder: String,
        auto: Boolean = false,
        onProgress: (DownloadProgress) -> Unit = {},
    ): WorldBackup {
        val world = worldDir(gameDir, folder)
        val files = Files.walk(world).use { stream -> stream.filter { it.isRegularFile() && it.name != LOCK }.toList() }
        val total = files.sumOf { runCatching { it.fileSize() }.getOrDefault(0L) }
        val name = "${safeName(folder)}_${LocalDateTime.now().format(STAMP)}${if (auto) AUTO else ""}.zip"
        val target = backupsDir(gameDir).also { it.createDirectories() }.resolve(name)
        val partial = target.resolveSibling("$name.part")
        var done = 0L
        try {
            ZipOutputStream(Files.newOutputStream(partial).buffered()).use { zip ->
                files.forEachIndexed { index, file ->
                    zip.putNextEntry(ZipEntry("$folder/" + file.relativeTo(world).joinToString("/")))
                    Files.newInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                    done += runCatching { file.fileSize() }.getOrDefault(0L)
                    onProgress(DownloadProgress(index + 1, files.size, done, total, 0, file.name))
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            Files.deleteIfExists(partial)
            throw IOException("Бэкап мира не сохранился: ${e.message}", e)
        }
        Log.info("world $folder backed up to $target (${files.size} files)")
        return WorldBackup(target, folder, modifiedAt(target), target.fileSize(), auto)
    }

    // Перед рискованными действиями: сохраняет миры, в которые играли после их последнего бэкапа
    fun autoBackup(gameDir: Path, onProgress: (DownloadProgress) -> Unit = {}): Int {
        val existing = backups(gameDir).groupBy { it.world }
        var made = 0
        list(gameDir).forEach { world ->
            val latest = existing[world.folder]?.maxOfOrNull { it.createdAt } ?: 0L
            if (modified(world.folder, gameDir) <= latest) return@forEach
            runCatching { backup(gameDir, world.folder, auto = true, onProgress = onProgress) }
                .onSuccess {
                    made++
                    existing[world.folder].orEmpty().filter { it.auto }.drop(AUTO_KEEP - 1)
                        .forEach { old -> runCatching { Files.deleteIfExists(old.file) } }
                }
                .onFailure { Log.warn("auto backup of ${world.folder} failed: ${it.message}") }
        }
        return made
    }

    fun restore(gameDir: Path, backup: WorldBackup, onProgress: (DownloadProgress) -> Unit = {}): String {
        val target = savesDir(gameDir).resolve(backup.world)
        if (target.exists()) backup(gameDir, backup.world, auto = true, onProgress = onProgress)
        ZipFile(backup.file.toFile()).use { zip ->
            val staged = extract(zip, "${backup.world}/", savesDir(gameDir).resolve(".restore-${System.nanoTime()}"), onProgress)
            if (target.exists()) Storage.deleteTree(target)
            Files.move(staged, target)
        }
        Log.info("world ${backup.world} restored from ${backup.file}")
        return backup.world
    }

    fun import(gameDir: Path, archive: Path, onProgress: (DownloadProgress) -> Unit = {}): String =
        ZipFile(archive.toFile()).use { zip ->
            val level = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.substringAfterLast('/') == LEVEL }
                .minByOrNull { it.name.count { char -> char == '/' } }
                ?: throw IOException("В архиве нет мира: не нашёл level.dat")
            val prefix = level.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val base = safeName(prefix.trimEnd('/').substringAfterLast('/').ifEmpty { archive.nameWithoutExtension })
            val folder = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base ($it)" }
                .first { !savesDir(gameDir).resolve(it).exists() }
            val staged = extract(zip, prefix, savesDir(gameDir).resolve(".import-${System.nanoTime()}"), onProgress)
            Files.move(staged, savesDir(gameDir).resolve(folder))
            Log.info("world imported from $archive into $folder")
            folder
        }

    fun delete(gameDir: Path, folder: String) = Storage.discard(worldDir(gameDir, folder))

    fun readLevel(world: Path): LevelInfo = runCatching {
        val bytes = GZIPInputStream(Files.newInputStream(world.resolve(LEVEL))).use { it.readBytes() }
        val data = Nbt.readRoot(bytes).entries["Data"] as? Nbt.CompoundTag ?: return LevelInfo()
        val entries = data.entries
        LevelInfo(
            name = (entries["LevelName"] as? Nbt.StringTag)?.value?.replace(FORMATTING, "")?.trim()?.takeIf { it.isNotEmpty() },
            gameVersion = ((entries["Version"] as? Nbt.CompoundTag)?.entries?.get("Name") as? Nbt.StringTag)?.value,
            lastPlayed = (entries["LastPlayed"] as? Nbt.LongTag)?.value ?: 0,
            gameType = (entries["GameType"] as? Nbt.IntTag)?.value,
            hardcore = ((entries["hardcore"] as? Nbt.ByteTag)?.value ?: 0).toInt() != 0,
        )
    }.getOrDefault(LevelInfo())

    private fun extract(zip: ZipFile, prefix: String, into: Path, onProgress: (DownloadProgress) -> Unit): Path {
        val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) }.toList()
        val total = entries.sumOf { it.size.coerceAtLeast(0) }
        var done = 0L
        val root = into.toAbsolutePath().normalize()
        try {
            entries.forEachIndexed { index, entry ->
                val target = root.resolve(entry.name.removePrefix(prefix)).normalize()
                if (!target.startsWith(root) || target == root) throw IOException("В архиве недопустимый путь: ${entry.name}")
                target.parent.createDirectories()
                zip.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                done += entry.size.coerceAtLeast(0)
                onProgress(DownloadProgress(index + 1, entries.size, done, total, 0, target.name))
            }
        } catch (e: IOException) {
            Storage.deleteTree(root)
            throw e
        }
        return root
    }

    private fun rootOf(zip: ZipFile): String? =
        zip.entries().asSequence().firstOrNull()?.name?.substringBefore('/')?.takeIf { it.isNotEmpty() }

    private fun worldDir(gameDir: Path, folder: String): Path {
        val saves = savesDir(gameDir).toAbsolutePath().normalize()
        val world = saves.resolve(folder).normalize()
        if (world.parent != saves || !world.isDirectory()) throw IOException("Мира $folder нет в папке сборки")
        return world
    }

    private fun modified(folder: String, gameDir: Path): Long = modifiedAt(savesDir(gameDir).resolve(folder).resolve(LEVEL))

    private fun modifiedAt(file: Path): Long = runCatching { file.getLastModifiedTime().toMillis() }.getOrDefault(0L)

    private fun dirs(dir: Path): List<Path> =
        runCatching { dir.listDirectoryEntries().filter { it.isDirectory() && !it.name.startsWith(".") } }.getOrDefault(emptyList())

    private fun safeName(name: String): String = name.replace(ILLEGAL, "_").trim().trim('.').ifBlank { "world" }
}
