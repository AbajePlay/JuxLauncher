package ru.jux.launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import ru.jux.launcher.auth.AccountManager
import kotlinx.serialization.encodeToString
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.meta.LoaderRepository
import ru.jux.launcher.meta.LoaderSupport
import ru.jux.launcher.meta.VersionManifest
import ru.jux.launcher.meta.VersionManifestRepository
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

data class PreloadResult(
    val manifest: VersionManifest,
    val installed: Map<String, Long>,
    val profiles: Set<String>,
    val loaderSupport: LoaderSupport = LoaderSupport(),
    val manifestStale: Boolean = false,
    val loaderSupportStale: Boolean = false,
)

object Preloader {

    private const val MIN_VISIBLE_MS = 900L

    suspend fun run(onStep: (Float, String) -> Unit): PreloadResult {
        val startedAt = System.currentTimeMillis()

        val opening = "Запускаюсь"
        onStep(0.05f, opening)
        withContext(Dispatchers.IO) { runCatching { Paths.ensureBaseDirs() } }
        onStep(0.15f, opening)
        withContext(Dispatchers.IO) { runCatching { Settings.load() } }
        onStep(0.25f, opening)
        withContext(Dispatchers.IO) { runCatching { AccountManager.load() } }
        withContext(Dispatchers.IO) { runCatching { Notices.load() } }
        onStep(0.40f, opening)
        withContext(Dispatchers.IO) { runCatching { VerifyCache.load() } }

        onStep(0.50f, "Спрашиваю Mojang, что нового")
        val cachedManifest = withContext(Dispatchers.IO) { VersionManifestRepository.loadCached() }
        val manifest = cachedManifest ?: withContext(Dispatchers.IO) {
            runCatching { VersionManifestRepository.load() }.getOrElse { failure ->
                Log.warn("preload: manifest unavailable: ${failure.message}")
                VersionManifest()
            }
        }
        val manifestStale = cachedManifest != null && !VersionManifestRepository.isFresh()

        val loaders = "Подтягиваю загрузчики модов"
        onStep(0.70f, loaders)
        val cachedSupport = withContext(Dispatchers.IO) { readSupportCache() }
        val loaderSupport = cachedSupport?.support ?: fetchLoaderSupport()

        onStep(0.90f, loaders)
        val (installed, profiles) = withContext(Dispatchers.IO) { scanInstalled() }

        onStep(1f, loaders)
        val elapsed = System.currentTimeMillis() - startedAt
        Log.info(
            "preload: ${elapsed} ms; versions from " +
                (if (cachedManifest != null) "cache" + (if (manifestStale) " (stale)" else "") else "network") +
                ", loaders from " +
                (if (cachedSupport != null) "cache" + (if (!cachedSupport.fresh) " (stale)" else "") else "network")
        )
        if (elapsed < MIN_VISIBLE_MS) delay(MIN_VISIBLE_MS - elapsed)

        return PreloadResult(
            manifest = manifest,
            installed = installed,
            profiles = profiles,
            loaderSupport = loaderSupport,
            manifestStale = manifestStale,
            loaderSupportStale = cachedSupport != null && !cachedSupport.fresh,
        )
    }

    suspend fun refreshManifest(): VersionManifest? =
        withContext(Dispatchers.IO) { VersionManifestRepository.refresh() }

    suspend fun refreshLoaderSupport(): LoaderSupport = fetchLoaderSupport()

    private const val SUPPORT_FRESH_MILLIS = 6 * 60 * 60 * 1000L

    private const val PARTIAL_FRESH_MILLIS = 15 * 60 * 1000L

    private val supportCache: Path get() = Paths.cache.resolve("loader-support-v2.json")

    private suspend fun fetchLoaderSupport(): LoaderSupport {
        val fetched = withContext(Dispatchers.IO) {
            coroutineScope {
                val fabric = async { LoaderRepository.supportedGameVersions(LoaderKind.FABRIC) }
                val quilt = async { LoaderRepository.supportedGameVersions(LoaderKind.QUILT) }
                val forge = async { LoaderRepository.supportedGameVersions(LoaderKind.FORGE) }
                val neoforge = async { LoaderRepository.supportedGameVersions(LoaderKind.NEOFORGE) }
                LoaderSupport(fabric.await(), quilt.await(), forge.await(), neoforge.await())
            }
        }

        val previous = withContext(Dispatchers.IO) { readSupportCache() }?.support ?: LoaderSupport()
        val merged = LoaderSupport(
            fabric = fetched.fabric.ifEmpty { previous.fabric },
            quilt = fetched.quilt.ifEmpty { previous.quilt },
            forge = fetched.forge.ifEmpty { previous.forge },
            neoforge = fetched.neoforge.ifEmpty { previous.neoforge },
        )

        if (!merged.isEmpty) {
            withContext(Dispatchers.IO) {
                runCatching { supportCache.writeAtomically(Json.encodeToString(merged)) }
                    .onFailure { Log.warn("could not cache loader support", it) }
            }
        }
        return merged
    }

    private class CachedSupport(val support: LoaderSupport, val fresh: Boolean)

    private fun readSupportCache(): CachedSupport? = runCatching {
        if (!supportCache.exists()) return null
        val cached = Json.decodeFromString<LoaderSupport>(supportCache.readText()).takeIf { !it.isEmpty }
            ?: return null

        val complete = cached.fabric.isNotEmpty() && cached.quilt.isNotEmpty() &&
            cached.forge.isNotEmpty() && cached.neoforge.isNotEmpty()
        val window = if (complete) SUPPORT_FRESH_MILLIS else PARTIAL_FRESH_MILLIS
        val age = System.currentTimeMillis() - supportCache.getLastModifiedTime().toMillis()
        CachedSupport(cached, fresh = age in 0..window)
    }.getOrNull()

    fun scanInstalled(): Pair<Map<String, Long>, Set<String>> = runCatching {
        val dirs = Paths.versions.listDirectoryEntries().filter { it.isDirectory() }
        val jars = dirs.mapNotNull { dir ->
            val id = dir.fileName.toString()
            val jar = dir.resolve("$id.jar")
            if (jar.exists()) id to runCatching { jar.fileSize() }.getOrDefault(0L) else null
        }.toMap()
        val profiles = dirs.mapNotNull { dir ->
            val id = dir.fileName.toString()
            id.takeIf { dir.resolve("$id.json").exists() }
        }.toSet()
        jars to profiles
    }.getOrDefault(emptyMap<String, Long>() to emptySet())
}
