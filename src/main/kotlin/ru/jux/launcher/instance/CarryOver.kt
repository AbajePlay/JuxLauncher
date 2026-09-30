package ru.jux.launcher.instance

import kotlinx.serialization.encodeToString
import ru.jux.launcher.activity.PlayHistory
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.Storage
import ru.jux.launcher.core.writeAtomically
import ru.jux.launcher.launch.GameLauncher
import ru.jux.launcher.mods.GameOptions
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.packs.Modpacks
import ru.jux.launcher.servers.Nbt
import ru.jux.launcher.servers.ServerList
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines

data class CarryWorld(
    val folder: String,
    val name: String,
    val gameVersion: String?,
    val bytes: Long,
    val present: Boolean,
)

data class CarryPack(val path: String, val bytes: Long)

data class CarrySource(
    val dir: Path,
    val label: String,
    val official: Boolean,
    val lastPlayed: Long,
    val options: Boolean,
    val servers: Int,
    val packs: List<CarryPack>,
    val worlds: List<CarryWorld>,
) {
    val packsBytes: Long get() = packs.sumOf { it.bytes }
    val onlyOptions: Boolean get() = servers == 0 && packs.isEmpty() && worlds.all { it.present }
    val isEmpty: Boolean get() = !options && onlyOptions
}

data class CarryPlan(
    val options: Boolean = false,
    val servers: Boolean = false,
    val packs: Boolean = false,
    val worlds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = !options && !servers && !packs && worlds.isEmpty()

    companion object {
        val NONE = CarryPlan()
    }
}

data class CarryResult(
    val options: Boolean,
    val servers: Int,
    val packs: Int,
    val worlds: Int,
    val failed: List<String>,
)

object CarryOver {

    private const val OPTIONS = "options.txt"
    private const val SAVES = "saves"
    private const val LEVEL = "level.dat"
    private const val SESSION_LOCK = "session.lock"
    private const val RESOURCE_PACKS = "resourcepacks"
    private const val SHADER_PACKS = "shaderpacks"
    private const val VERSION_KEY = "version"
    private const val PACKS_KEY = "resourcePacks"
    private const val MIN_GAME_OPTIONS = 10
    private const val MAX_SOURCES = 6
    private const val PROGRESS_MILLIS = 100L

    val officialDir: Path? = System.getenv("APPDATA")
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it).resolve(".minecraft") }

    fun isFresh(gameDir: Path): Boolean =
        !gameDir.resolve(GameLauncher.GAME_LOG).exists() && !gameDir.resolve("logs").exists()

    fun hasOwnOptions(gameDir: Path): Boolean = gameOptions(gameDir) != null

    fun sources(
        target: Path,
        withShaders: Boolean,
        roots: List<Path> = Settings.gameRoots(),
        official: Path? = officialDir,
    ): List<CarrySource> {
        val self = target.normalized()
        val officialDir = official?.normalized()
        val dirs = roots.flatMap(::listDirs) + listOfNotNull(officialDir?.takeIf { it.isDirectory() })
        return dirs.map { it.normalized() }
            .distinct()
            .filter { it != self }
            .map { it to lastPlayed(it) }
            .sortedByDescending { it.second }
            .asSequence()
            .mapNotNull { (dir, played) ->
                runCatching { inspect(dir, played, target, withShaders, dir == officialDir) }
                    .onFailure { Log.warn("carry-over: could not look into $dir: ${it.message}") }
                    .getOrNull()
            }
            .filterNot { it.isEmpty }
            .take(MAX_SOURCES)
            .toList()
    }

    private fun inspect(dir: Path, played: Long, target: Path, withShaders: Boolean, official: Boolean): CarrySource {
        val packFolders = listOfNotNull(RESOURCE_PACKS, SHADER_PACKS.takeIf { withShaders })
        val packs = packFolders.flatMap { folder ->
            listEntries(dir.resolve(folder))
                .filter { !target.resolve(folder).resolve(it.name).exists() }
                .map { CarryPack("$folder/${it.name}", Storage.sizeOf(it)) }
        }
        val worlds = listDirs(dir.resolve(SAVES))
            .filter { it.resolve(LEVEL).exists() }
            .map { world ->
                val (name, version) = readLevel(world)
                CarryWorld(
                    folder = world.name,
                    name = name ?: world.name,
                    gameVersion = version,
                    bytes = Storage.sizeOf(world),
                    present = target.resolve(SAVES).resolve(world.name).exists(),
                )
            }
            .sortedBy { it.name.lowercase() }
        return CarrySource(
            dir = dir,
            label = if (official) ".minecraft" else labelOf(dir),
            official = official,
            lastPlayed = played,
            options = hasOwnOptions(dir),
            servers = runCatching { ServerList.missingIn(dir, target) }.getOrDefault(0),
            packs = packs,
            worlds = worlds,
        )
    }

    fun apply(
        source: CarrySource,
        target: Path,
        plan: CarryPlan,
        onProgress: (DownloadProgress) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): CarryResult {
        target.createDirectories()
        val failed = ArrayList<String>()

        val options = plan.options && source.options && runCatching { carryOptions(source.dir, target) }
            .onFailure {
                Log.warn("carry-over: options from ${source.dir} failed: ${it.message}")
                failed += "настройки"
            }
            .isSuccess
        val servers = if (plan.servers) {
            runCatching { ServerList.merge(source.dir, target) }
                .onFailure {
                    Log.warn("carry-over: servers from ${source.dir} failed: ${it.message}")
                    failed += "список серверов"
                }
                .getOrDefault(0)
        } else {
            0
        }

        val packs = if (plan.packs) source.packs else emptyList()
        val worlds = source.worlds.filter { it.folder in plan.worlds && !it.present }
        val copies = packs.map { Copy(source.dir.resolve(it.path), target.resolve(it.path), it.path.substringAfter('/'), it.bytes, false) } +
            worlds.map { Copy(source.dir.resolve(SAVES).resolve(it.folder), target.resolve(SAVES).resolve(it.folder), it.name, it.bytes, true) }

        val totalBytes = copies.sumOf { it.bytes }
        var doneBytes = 0L
        var shownAt = 0L
        var copiedPacks = 0
        var copiedWorlds = 0
        copies.forEachIndexed { index, copy ->
            fun report(force: Boolean) {
                val now = System.currentTimeMillis()
                if (!force && now - shownAt < PROGRESS_MILLIS) return
                shownAt = now
                onProgress(DownloadProgress(index, copies.size, doneBytes, totalBytes, 0, copy.label))
            }
            report(force = true)
            val before = doneBytes
            try {
                copyTree(copy.from, copy.to) { bytes ->
                    checkCancelled()
                    doneBytes += bytes
                    report(force = false)
                }
                if (copy.world) copiedWorlds++ else copiedPacks++
            } catch (e: IOException) {
                Log.warn("carry-over: could not copy ${copy.from}: ${e.message}")
                failed += copy.label
                doneBytes = before + copy.bytes
            }
        }
        if (copies.isNotEmpty()) onProgress(DownloadProgress(copies.size, copies.size, totalBytes, totalBytes, 0, ""))
        Log.info("carried over from ${source.dir} to $target: options=$options servers=$servers packs=$copiedPacks worlds=$copiedWorlds failed=$failed")
        return CarryResult(options, servers, copiedPacks, copiedWorlds, failed)
    }

    private class Copy(val from: Path, val to: Path, val label: String, val bytes: Long, val world: Boolean)

    private fun carryOptions(source: Path, target: Path) {
        val from = source.resolve(OPTIONS).readLines()
        val file = target.resolve(OPTIONS)
        val into = if (file.exists()) file.readLines() else emptyList()
        file.writeAtomically(mergeOptions(from, into).joinToString("\n", postfix = "\n"))
    }

    internal fun mergeOptions(source: List<String>, target: List<String>): List<String> {
        val from = parseOptions(source)
        val into = parseOptions(target)
        val merged = LinkedHashMap(from)
        into.forEach { (key, value) -> if (key !in merged && key != VERSION_KEY) merged[key] = value }
        val packsFrom = from[PACKS_KEY]
        val packsInto = into[PACKS_KEY]
        if (packsFrom != null && packsInto != null) {
            val ours = GameOptions.parsePacks("$PACKS_KEY:$packsFrom")
            val theirs = GameOptions.parsePacks("$PACKS_KEY:$packsInto")
            merged[PACKS_KEY] = Json.encodeToString(ours + theirs.filter { it !in ours })
        }
        return merged.map { (key, value) -> "$key:$value" }
    }

    private fun parseOptions(lines: List<String>): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        lines.forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) map[line.substring(0, colon)] = line.substring(colon + 1)
        }
        return map
    }

    private fun gameOptions(gameDir: Path): List<String>? {
        val file = gameDir.resolve(OPTIONS)
        if (!file.exists()) return null
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        return lines.takeIf { found -> found.count { ':' in it } >= MIN_GAME_OPTIONS }
    }

    private fun copyTree(from: Path, to: Path, onBytes: (Long) -> Unit) {
        if (to.exists()) throw IOException("$to уже есть")
        to.parent?.createDirectories()
        val tmp = to.resolveSibling(".${to.name}.jux-copy")
        Storage.deleteTree(tmp)
        try {
            if (!from.isDirectory()) {
                Files.copy(from, tmp, StandardCopyOption.COPY_ATTRIBUTES)
                onBytes(Files.size(tmp))
            } else {
                Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.createDirectories(tmp.resolve(from.relativize(dir).toString()))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (file.name == SESSION_LOCK) return FileVisitResult.CONTINUE
                        Files.copy(file, tmp.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES)
                        onBytes(attrs.size())
                        return FileVisitResult.CONTINUE
                    }
                })
            }
            Files.move(tmp, to)
        } catch (e: Throwable) {
            Storage.deleteTree(tmp)
            throw e
        }
    }

    internal fun readLevel(world: Path): Pair<String?, String?> = runCatching {
        val bytes = GZIPInputStream(Files.newInputStream(world.resolve(LEVEL))).use { it.readBytes() }
        val data = Nbt.readRoot(bytes).entries["Data"] as? Nbt.CompoundTag
        val name = (data?.entries?.get("LevelName") as? Nbt.StringTag)?.value
            ?.replace(FORMATTING, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val version = ((data?.entries?.get("Version") as? Nbt.CompoundTag)?.entries?.get("Name") as? Nbt.StringTag)?.value
        name to version
    }.getOrDefault(null to null)

    private val FORMATTING = Regex("§.")

    private fun labelOf(dir: Path): String {
        Modpacks.read(dir)?.let { return it.title }
        val (versionId, loader) = PlayHistory.identify(dir.name)
        return if (loader.isModded) "$versionId ${loader.label}" else versionId
    }

    private fun lastPlayed(dir: Path): Long =
        listOf(dir.resolve(GameLauncher.GAME_LOG), dir.resolve("logs").resolve("latest.log"), dir.resolve(OPTIONS))
            .maxOf { file -> runCatching { file.getLastModifiedTime().toMillis() }.getOrDefault(0L) }

    private fun listEntries(dir: Path): List<Path> =
        runCatching { dir.listDirectoryEntries().filterNot { it.name.startsWith(".") } }.getOrDefault(emptyList())

    private fun listDirs(dir: Path): List<Path> = listEntries(dir).filter { it.isDirectory() }

    private fun Path.normalized(): Path = toAbsolutePath().normalize()
}
