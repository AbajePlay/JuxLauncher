package ru.jux.launcher.mods

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.sha1Of
import ru.jux.launcher.instance.InstanceStore
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.net.DownloadTask
import ru.jux.launcher.net.Downloader
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

data class InstalledMod(
    val file: Path,
    val enabled: Boolean,
    val sha1: String,
    val projectId: String?,
    val title: String,
    val versionNumber: String,
    val iconUrl: String?,
    val fromBoost: Boolean,
    val update: Modrinth.Version?,
) {
    val fileName: String get() = file.name
}

object ModManager {

    private const val DISABLED = ".disabled"
    private const val DEPENDENCY_DEPTH = 4

    private class Hashed(val size: Long, val modified: Long, val sha1: String)

    private val hashes = ConcurrentHashMap<Path, Hashed>()

    private fun hashOf(file: Path): String {
        val size = file.fileSize()
        val modified = file.getLastModifiedTime().toMillis()
        hashes[file]?.takeIf { it.size == size && it.modified == modified }?.let { return it.sha1 }
        return sha1Of(file).also { hashes[file] = Hashed(size, modified, it) }
    }

    fun loadersFor(kind: LoaderKind): List<String> = when (kind) {
        LoaderKind.FABRIC -> listOf("fabric")
        LoaderKind.QUILT -> listOf("quilt", "fabric")
        LoaderKind.FORGE -> listOf("forge")
        LoaderKind.NEOFORGE -> listOf("neoforge")
        LoaderKind.VANILLA -> emptyList()
    }

    fun modsDir(gameDir: Path): Path = gameDir.resolve("mods")

    suspend fun scan(gameDir: Path, kind: LoaderKind, gameVersion: String, withUpdates: Boolean): List<InstalledMod> =
        withContext(Dispatchers.IO) {
            val dir = modsDir(gameDir)
            if (!dir.isDirectory()) return@withContext emptyList()
            val files = dir.listDirectoryEntries().filter { it.name.endsWith(".jar") || it.name.endsWith(".jar$DISABLED") }
            if (files.isEmpty()) return@withContext emptyList()

            val sums = files.associateWith { runCatching { hashOf(it) }.getOrDefault("") }
            val known = sums.values.filter { it.isNotEmpty() }
            val versions = remote("identify mods") { Modrinth.versionsByHash(known) }.orEmpty()
            val projects = remote("mod titles") { Modrinth.projects(versions.values.map { it.projectId }.distinct()) }
                .orEmpty().associateBy { it.id }
            val updates = if (withUpdates && versions.isNotEmpty()) {
                remote("mod updates") { Modrinth.latestVersions(versions.keys, loadersFor(kind), gameVersion) }.orEmpty()
            } else {
                emptyMap()
            }
            val boost = InstanceStore.get(gameDir).boostMods.map { it.fileName }.toSet()

            files.map { file ->
                val sha1 = sums.getValue(file)
                val version = versions[sha1]
                val project = version?.let { projects[it.projectId] }
                val newer = updates[sha1]?.takeIf { it.id != version?.id && it.versionType != "alpha" }
                val enabled = file.name.endsWith(".jar")
                InstalledMod(
                    file = file,
                    enabled = enabled,
                    sha1 = sha1,
                    projectId = version?.projectId,
                    title = project?.title?.takeIf { it.isNotBlank() } ?: file.name.removeSuffix(DISABLED).removeSuffix(".jar"),
                    versionNumber = version?.versionNumber.orEmpty(),
                    iconUrl = project?.iconUrl,
                    fromBoost = file.name.removeSuffix(DISABLED) in boost,
                    update = newer,
                )
            }.sortedBy { it.title.lowercase() }
        }

    suspend fun install(
        gameDir: Path,
        kind: LoaderKind,
        gameVersion: String,
        projectId: String,
        title: String,
        present: Set<String>,
        onProgress: (DownloadProgress) -> Unit = {},
    ): List<String> = withContext(Dispatchers.IO) {
        val loaders = loadersFor(kind)
        val find = { id: String -> Modrinth.pick(Modrinth.versions(id, loaders, gameVersion)) }
        val root = find(projectId)
            ?: throw IOException("$title пока нет для ${kind.label} $gameVersion")

        val chosen = LinkedHashMap<String, Modrinth.Version>()
        chosen[projectId] = root
        val skipped = ArrayList<String>()
        var frontier = listOf(root)
        for (depth in 1..DEPENDENCY_DEPTH) {
            val needed = frontier.asSequence()
                .flatMap { it.dependencies }
                .filter { it.type == "required" }
                .mapNotNull { it.projectId }
                .filter { it !in chosen && it !in present }
                .distinct()
                .toList()
            if (needed.isEmpty()) break
            val names = remote("dependency titles") { Modrinth.titles(needed) }.orEmpty()
            frontier = needed.mapNotNull { id ->
                val version = find(id)
                if (version == null) skipped += names[id] ?: id
                version?.also { chosen[id] = it }
            }
        }
        if (skipped.isNotEmpty()) {
            throw IOException("$title требует ${skipped.joinToString()}, а для ${kind.label} $gameVersion их нет")
        }

        val dir = modsDir(gameDir).also { it.createDirectories() }
        val tasks = chosen.values.map { version ->
            val file = version.primaryFile ?: throw IOException("У ${version.name} нет файла для скачивания")
            val name = PerformancePack.safeFileName(file.filename)
                ?: throw IOException("Недопустимое имя файла: ${file.filename}")
            DownloadTask(file.url, dir.resolve(name), file.sha1, file.size, label = name)
        }
        Downloader().run(tasks, onProgress)
        val titles = remote("installed titles") { Modrinth.titles(chosen.keys) }.orEmpty()
        Log.info("mods installed into $dir: ${tasks.joinToString { it.dest.name }}")
        chosen.keys.map { titles[it] ?: it }
    }

    suspend fun update(mod: InstalledMod, onProgress: (DownloadProgress) -> Unit = {}) = withContext(Dispatchers.IO) {
        val version = mod.update ?: return@withContext
        val file = version.primaryFile ?: throw IOException("У ${version.name} нет файла для скачивания")
        val name = PerformancePack.safeFileName(file.filename)
            ?: throw IOException("Недопустимое имя файла: ${file.filename}")
        val target = mod.file.resolveSibling(if (mod.enabled) name else name + DISABLED)
        val download = mod.file.resolveSibling("$name.download")
        Downloader().run(listOf(DownloadTask(file.url, download, file.sha1, file.size, label = name)), onProgress)
        locked(mod) {
            Files.move(download, target, StandardCopyOption.REPLACE_EXISTING)
            if (target != mod.file) mod.file.deleteIfExists()
        }
        Log.info("mod updated: ${mod.fileName} -> ${target.name}")
    }

    fun setEnabled(mod: InstalledMod, enabled: Boolean) {
        if (mod.enabled == enabled) return
        val base = mod.fileName.removeSuffix(DISABLED)
        val target = mod.file.resolveSibling(if (enabled) base else base + DISABLED)
        locked(mod) { Files.move(mod.file, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    fun remove(mod: InstalledMod) {
        locked(mod) { mod.file.deleteIfExists() }
    }

    fun count(gameDir: Path): Int = runCatching {
        val dir = modsDir(gameDir)
        if (!dir.exists()) 0 else dir.listDirectoryEntries("*.jar").size
    }.getOrDefault(0)

    private inline fun locked(mod: InstalledMod, action: () -> Unit) {
        try {
            action()
        } catch (e: FileSystemException) {
            throw IOException("${mod.title}: файл занят. Закрой игру и попробуй снова", e)
        }
    }

    private inline fun <T> remote(what: String, call: () -> T): T? = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.warn("modrinth $what failed: ${e.message}")
        null
    }
}
