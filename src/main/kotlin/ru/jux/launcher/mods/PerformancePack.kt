package ru.jux.launcher.mods

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.sha1Of
import ru.jux.launcher.instance.InstanceStore
import ru.jux.launcher.instance.ManagedMod
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.net.DownloadTask
import ru.jux.launcher.net.Downloader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

object PerformancePack {

    data class Member(val slug: String, val title: String)

    val MEMBERS = listOf(
        Member("sodium", "Sodium"),
        Member("lithium", "Lithium"),
        Member("ferrite-core", "FerriteCore"),
        Member("entityculling", "EntityCulling"),
        Member("immediatelyfast", "ImmediatelyFast"),
        Member("modernfix", "ModernFix"),
    )

    const val LOADER = "fabric"

    private const val RECHECK_MILLIS = 24 * 60 * 60 * 1000L

    private const val DEPENDENCY_DEPTH = 3

    val summary: String = MEMBERS.joinToString { it.title }

    class Outcome(
        val mods: List<ManagedMod>,
        val missing: List<String>,
        val offline: Boolean,
    )

    internal class Candidate(val projectId: String, val title: String, val version: Modrinth.Version)

    suspend fun sync(
        gameDir: Path,
        gameVersion: String,
        onStage: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val modsDir = gameDir.resolve("mods")
        val options = InstanceStore.get(gameDir)
        val present = options.boostMods.filter { modsDir.resolve(it.fileName).exists() }
        val checkedRecently = System.currentTimeMillis() - options.boostCheckedAt in 0..RECHECK_MILLIS
        if (checkedRecently && present.size == options.boostMods.size) {
            return@withContext Outcome(present, options.boostMissing, offline = false)
        }

        onStage("FPS-буст: подбираю моды под $gameVersion")
        val (candidates, missing) = try {
            val owned = playersOwnProjects(modsDir, options.boostMods)
            resolve(
                owned = owned,
                find = { project -> Modrinth.pick(Modrinth.versions(project, LOADER, gameVersion)) },
                titles = { ids -> Modrinth.titles(ids) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.warn("performance pack: Modrinth unavailable: ${e.message}")
            return@withContext Outcome(present, options.boostMissing, offline = true)
        }

        val wanted = candidates.mapNotNull { candidate ->
            val file = candidate.version.primaryFile ?: return@mapNotNull null
            val name = safeFileName(file.filename) ?: return@mapNotNull null
            val sha1 = file.sha1 ?: return@mapNotNull null
            ManagedMod(candidate.projectId, candidate.version.id, name, sha1, candidate.title) to file
        }

        if (wanted.isNotEmpty()) {
            modsDir.createDirectories()
            onStage("FPS-буст: скачиваю ${wanted.joinToString { it.first.title }}")
            Downloader().run(
                wanted.map { (mod, file) ->
                    DownloadTask(file.url, modsDir.resolve(mod.fileName), mod.sha1, file.size, label = mod.title)
                },
                onProgress,
            )
        }

        val keep = wanted.map { it.first.fileName }.toSet()
        options.boostMods
            .filter { it.fileName !in keep }
            .forEach { stale -> runCatching { modsDir.resolve(stale.fileName).deleteIfExists() } }

        val mods = wanted.map { it.first }
        InstanceStore.update(gameDir) {
            it.copy(boostMods = mods, boostMissing = missing, boostCheckedAt = System.currentTimeMillis())
        }
        Log.info("performance pack for $gameVersion: ${mods.joinToString { it.fileName }}; missing: $missing")
        Outcome(mods, missing, offline = false)
    }

    fun remove(gameDir: Path) {
        val options = InstanceStore.get(gameDir)
        val modsDir = gameDir.resolve("mods")
        options.boostMods.forEach { mod -> runCatching { modsDir.resolve(mod.fileName).deleteIfExists() } }
        InstanceStore.update(gameDir) {
            it.copy(fpsBoost = false, boostMods = emptyList(), boostMissing = emptyList(), boostCheckedAt = 0)
        }
    }

    internal suspend fun resolve(
        owned: Set<String>,
        find: suspend (project: String) -> Modrinth.Version?,
        titles: suspend (ids: Collection<String>) -> Map<String, String>,
    ): Pair<List<Candidate>, List<String>> = coroutineScope {
        val chosen = LinkedHashMap<String, Candidate>()
        val missing = ArrayList<String>()
        val memberProjects = HashMap<String, String>()

        MEMBERS.map { member -> async { member to find(member.slug) } }.awaitAll().forEach { (member, version) ->
            when {
                version == null -> missing += member.title
                version.projectId in owned -> Unit
                else -> {
                    chosen[version.projectId] = Candidate(version.projectId, member.title, version)
                    memberProjects[version.projectId] = member.title
                }
            }
        }

        var frontier = chosen.values.toList()
        for (depth in 1..DEPENDENCY_DEPTH) {
            val needed = frontier.asSequence()
                .flatMap { it.version.dependencies }
                .filter { it.type == "required" }
                .mapNotNull { it.projectId }
                .filter { it !in chosen && it !in owned }
                .distinct()
                .toList()
            if (needed.isEmpty()) break
            val names = runCatching { titles(needed) }.getOrDefault(emptyMap())
            frontier = needed.map { id -> async { id to find(id) } }.awaitAll().mapNotNull { (id, version) ->
                version?.let { Candidate(id, names[id] ?: id, it).also { candidate -> chosen[id] = candidate } }
            }
        }

        do {
            val broken = chosen.values.filter { candidate ->
                candidate.version.dependencies.any { dep ->
                    dep.type == "required" && dep.projectId != null &&
                        dep.projectId !in chosen && dep.projectId !in owned
                }
            }
            broken.forEach { candidate ->
                chosen.remove(candidate.projectId)
                memberProjects[candidate.projectId]?.let { missing += it }
            }
        } while (broken.isNotEmpty())

        chosen.values.toList() to missing
    }

    private fun playersOwnProjects(modsDir: Path, managed: List<ManagedMod>): Set<String> {
        if (!modsDir.isDirectory()) return emptySet()
        val managedNames = managed.map { it.fileName }.toSet()
        val jars = modsDir.listDirectoryEntries("*.jar").filter { it.fileName.toString() !in managedNames }
        if (jars.isEmpty()) return emptySet()
        val hashes = jars.mapNotNull { runCatching { sha1Of(it) }.getOrNull() }
        return Modrinth.versionsByHash(hashes).values.map { it.projectId }.toSet()
    }

    internal fun safeFileName(name: String): String? =
        name.takeIf { it.endsWith(".jar") && '/' !in it && '\\' !in it && ".." !in it && ':' !in it }
}
