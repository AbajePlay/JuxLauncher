package ru.jux.launcher.mods

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.time.Instant
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
    private const val FIX_ROUNDS = 4
    private const val FIX_CANDIDATES = 6
    private const val BLOCKED_LIMIT = 50

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
            val loaders = loadersFor(kind)
            val updates = if (withUpdates && versions.isNotEmpty()) {
                val latest = remote("mod updates") { Modrinth.latestVersions(versions.keys, loaders, gameVersion) }.orEmpty()
                stableUpdates(versions, latest, loaders, gameVersion)
            } else {
                emptyMap()
            }
            val options = InstanceStore.get(gameDir)
            val boost = options.boostMods.map { it.fileName }.toSet()
            val blocked = options.blockedUpdates.toSet()

            files.map { file ->
                val sha1 = sums.getValue(file)
                val version = versions[sha1]
                val project = version?.let { projects[it.projectId] }
                val newer = updates[sha1]?.takeIf { it.id !in blocked }
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
        val before = dir.listDirectoryEntries().toSet()
        val tasks = chosen.values.map { version ->
            val file = version.primaryFile ?: throw IOException("У ${version.name} нет файла для скачивания")
            val name = PerformancePack.safeFileName(file.filename)
                ?: throw IOException("Недопустимое имя файла: ${file.filename}")
            DownloadTask(file.url, dir.resolve(name), file.sha1, file.size, label = name)
        }
        Downloader().run(tasks, onProgress)
        val added = tasks.map { it.dest }.filter { it !in before }.toSet()
        val existing = ModCompat.enabledIn(dir, except = added)
        val conflicts = added.mapNotNull(ModCompat::read).flatMap { ModCompat.conflicts(it, existing) }
        if (conflicts.isNotEmpty()) {
            added.forEach { runCatching { it.deleteIfExists() } }
            throw IncompatibleModException(conflicts, "не стал ставить, чтобы игра не упала")
        }
        clearBlocked(gameDir)
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
        if (mod.enabled) {
            val conflicts = ModCompat.read(download)
                ?.let { ModCompat.conflicts(it, ModCompat.enabledIn(mod.file.parent, except = setOf(mod.file))) }
                .orEmpty()
            if (conflicts.isNotEmpty()) {
                download.deleteIfExists()
                gameDirOf(mod)?.let { dir ->
                    InstanceStore.update(dir) { it.copy(blockedUpdates = (it.blockedUpdates + version.id).distinct().takeLast(BLOCKED_LIMIT)) }
                }
                throw IncompatibleModException(conflicts, "оставил прежнюю версию")
            }
        }
        locked(mod) {
            Files.move(download, target, StandardCopyOption.REPLACE_EXISTING)
            if (target != mod.file) mod.file.deleteIfExists()
        }
        gameDirOf(mod)?.let(::clearBlocked)
        Log.info("mod updated: ${mod.fileName} -> ${target.name}")
    }

    suspend fun fixConflicts(
        gameDir: Path,
        kind: LoaderKind,
        gameVersion: String,
        onProgress: (DownloadProgress) -> Unit = {},
    ): List<String> = withContext(Dispatchers.IO) {
        val dir = modsDir(gameDir)
        val changes = ArrayList<String>()
        val rejected = ArrayList<String>()
        try {
            repeat(FIX_ROUNDS) {
                val jars = dir.listDirectoryEntries("*.jar").mapNotNull { jar -> ModCompat.read(jar)?.let { jar to it } }
                val conflict = ModCompat.conflictsAmong(jars.map { it.second }).firstOrNull() ?: return@withContext changes
                changes += replaceOneSide(conflict, jars, kind, gameVersion, rejected, onProgress)
                    ?: throw IOException("${conflict.text}. Подходящей версии на Modrinth нет — выключи один из них в «Моды»")
            }
            if (ModCompat.conflictsIn(dir).isNotEmpty()) throw IOException("Не получилось развести все моды — выключи лишние в «Моды»")
            changes
        } finally {
            if (rejected.isNotEmpty()) {
                InstanceStore.update(gameDir) { options ->
                    options.copy(blockedUpdates = (options.blockedUpdates + rejected).distinct().takeLast(BLOCKED_LIMIT))
                }
            }
        }
    }

    private suspend fun replaceOneSide(
        conflict: Conflict,
        jars: List<Pair<Path, ModMeta>>,
        kind: LoaderKind,
        gameVersion: String,
        rejected: MutableList<String>,
        onProgress: (DownloadProgress) -> Unit,
    ): String? {
        val sides = listOf(conflict.mod, conflict.other).mapNotNull { meta -> jars.firstOrNull { it.second == meta } }
        val known = Modrinth.versionsByHash(sides.map { hashOf(it.first) })
        val ordered = sides.mapNotNull { side -> known[hashOf(side.first)]?.let { side to it } }
            .sortedBy { (_, version) -> if (version.versionType == "release") 1 else 0 }
        val loaders = loadersFor(kind)
        for ((side, current) in ordered) {
            val (jar, meta) = side
            val others = jars.filter { it.first != jar }.map { it.second }
            val candidates = Modrinth.versions(current.projectId, loaders, gameVersion)
                .filter { it.versionType == "release" && it.id != current.id }
                .take(FIX_CANDIDATES)
            for (candidate in candidates) {
                val file = candidate.primaryFile ?: continue
                val name = PerformancePack.safeFileName(file.filename) ?: continue
                val download = jar.resolveSibling("$name.download")
                Downloader().run(listOf(DownloadTask(file.url, download, file.sha1, file.size, label = name)), onProgress)
                val fresh = ModCompat.read(download)
                if (fresh == null || ModCompat.conflicts(fresh, others).isNotEmpty()) {
                    download.deleteIfExists()
                    rejected += candidate.id
                    continue
                }
                val target = jar.resolveSibling(name)
                Files.move(download, target, StandardCopyOption.REPLACE_EXISTING)
                if (target != jar) jar.deleteIfExists()
                rejected += current.id
                Log.info("mod conflict fixed: ${jar.name} -> ${target.name}")
                return "${meta.name} ${meta.shortVersion} → ${fresh.shortVersion}"
            }
        }
        return null
    }

    private suspend fun stableUpdates(
        installed: Map<String, Modrinth.Version>,
        latest: Map<String, Modrinth.Version>,
        loaders: List<String>,
        gameVersion: String,
    ): Map<String, Modrinth.Version> = coroutineScope {
        latest.mapNotNull { (sha1, candidate) ->
            val current = installed[sha1] ?: return@mapNotNull null
            if (candidate.id == current.id) return@mapNotNull null
            sha1 to async {
                val release = if (candidate.versionType == "release") {
                    candidate
                } else {
                    remote("releases of ${current.projectId}") {
                        Modrinth.versions(current.projectId, loaders, gameVersion)
                    }?.let(::newestRelease)
                }
                release?.takeIf { isUpgrade(it, current) }
            }
        }.mapNotNull { (sha1, lookup) -> lookup.await()?.let { sha1 to it } }.toMap()
    }

    internal fun newestRelease(versions: List<Modrinth.Version>): Modrinth.Version? =
        versions.filter { it.versionType == "release" }.maxByOrNull(::publishedAt)

    internal fun isUpgrade(candidate: Modrinth.Version, current: Modrinth.Version): Boolean =
        candidate.versionType == "release" && candidate.id != current.id && publishedAt(candidate) > publishedAt(current)

    private fun publishedAt(version: Modrinth.Version): Instant =
        runCatching { Instant.parse(version.datePublished) }.getOrDefault(Instant.EPOCH)

    fun setEnabled(mod: InstalledMod, enabled: Boolean) {
        if (mod.enabled == enabled) return
        val base = mod.fileName.removeSuffix(DISABLED)
        val target = mod.file.resolveSibling(if (enabled) base else base + DISABLED)
        locked(mod) { Files.move(mod.file, target, StandardCopyOption.REPLACE_EXISTING) }
        gameDirOf(mod)?.let(::clearBlocked)
    }

    fun remove(mod: InstalledMod) {
        locked(mod) { mod.file.deleteIfExists() }
        gameDirOf(mod)?.let(::clearBlocked)
    }

    private fun gameDirOf(mod: InstalledMod): Path? = mod.file.parent?.parent

    private fun clearBlocked(gameDir: Path) {
        if (InstanceStore.get(gameDir).blockedUpdates.isEmpty()) return
        InstanceStore.update(gameDir) { it.copy(blockedUpdates = emptyList()) }
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
