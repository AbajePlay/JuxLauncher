package ru.jux.launcher.packs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.PrettyJson
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.writeAtomically
import ru.jux.launcher.instance.InstanceStore
import ru.jux.launcher.launch.GameLauncher
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.mods.ModManager
import ru.jux.launcher.mods.Modrinth
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.net.DownloadTask
import ru.jux.launcher.net.Downloader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

@Serializable
data class PackIndex(
    val formatVersion: Int = 0,
    val game: String = "",
    val versionId: String = "",
    val name: String = "",
    val files: List<PackFile> = emptyList(),
    val dependencies: Map<String, String> = emptyMap(),
)

@Serializable
data class PackFile(
    val path: String = "",
    val hashes: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
    val downloads: List<String> = emptyList(),
    val fileSize: Long = 0,
)

@Serializable
data class Modpack(
    val id: String,
    val title: String,
    val version: String,
    val gameVersion: String,
    val loader: LoaderKind,
    val loaderVersion: String? = null,
    val projectId: String,
    val versionId: String,
    val iconUrl: String? = null,
    val files: List<String> = emptyList(),
) {
    // Сборку собрал сам игрок: у неё нет проекта на Modrinth, обновлять и переустанавливать её оттуда нечего
    val custom: Boolean get() = projectId.isBlank()
}

data class PackSource(
    val id: String,
    val title: String,
    val projectId: String,
    val versionId: String,
    val version: String,
    val url: String,
    val sha1: String? = null,
    val size: Long = 0,
    val iconUrl: String? = null,
)

internal data class PackDownload(val path: String, val urls: List<String>, val sha1: String, val size: Long)

object Modpacks {

    const val MANIFEST = "jux-pack.json"
    private const val INDEX = "modrinth.index.json"
    private const val ARCHIVE = ".mrpack"
    private val OVERRIDES = listOf("overrides/", "client-overrides/")
    private val KEPT_ON_UPDATE = setOf("options.txt", "servers.dat")
    private val HOSTS = setOf("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com")
    private val LOADERS = listOf(
        "fabric-loader" to LoaderKind.FABRIC,
        "quilt-loader" to LoaderKind.QUILT,
        "neoforge" to LoaderKind.NEOFORGE,
        "forge" to LoaderKind.FORGE,
    )
    private val UNSAFE_ID = Regex("[^a-z0-9._-]+")
    private const val ICON = "icon.png"
    private const val MAX_ICON_BYTES = 2L shl 20
    private val TRANSLIT = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя".toList().zip(
        listOf("a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"),
    ).toMap()
    // Логи, бэкапы и настройки буста не переносим: у копии своя история и свой буст
    private val NOT_COPIED = setOf(MANIFEST, ICON, "logs", "crash-reports", "backups", GameLauncher.GAME_LOG, InstanceStore.FILE_NAME)

    fun dirOf(pack: Modpack): Path = Settings.packsDir().resolve(pack.id)

    fun iconOf(pack: Modpack): Path = dirOf(pack).resolve(ICON)

    fun create(title: String, gameVersion: String, loader: LoaderKind, loaderVersion: String? = null, copyFrom: Path? = null): Modpack {
        val name = title.trim().ifEmpty { throw IOException("Дай сборке название") }
        val id = freeFolder(name)
        val dir = Settings.packsDir().resolve(id)
        if (copyFrom != null && copyFrom.isDirectory()) copyContent(copyFrom, dir)
        val pack = Modpack(id, name, "", gameVersion, loader, loaderVersion, projectId = "", versionId = "")
        save(pack)
        Log.info("custom pack $name created in $dir from ${copyFrom ?: "scratch"}: $gameVersion ${loader.label}")
        return pack
    }

    fun rename(pack: Modpack, title: String): Modpack {
        val name = title.trim().ifEmpty { throw IOException("Название не может быть пустым") }
        return pack.copy(title = name).also(::save)
    }

    fun setIcon(pack: Modpack, image: Path) {
        if (Files.size(image) > MAX_ICON_BYTES) throw IOException("Картинка больше 2 МБ — выбери поменьше")
        Files.copy(image, iconOf(pack), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun save(pack: Modpack) = dirOf(pack).resolve(MANIFEST).writeAtomically(PrettyJson.encodeToString(pack))

    private fun copyContent(from: Path, to: Path) {
        to.createDirectories()
        from.listDirectoryEntries().filter { it.name !in NOT_COPIED }.forEach { item ->
            item.toFile().copyRecursively(to.resolve(item.name).toFile(), overwrite = true)
        }
    }

    internal fun freeFolder(title: String): String {
        val latin = title.lowercase().map { TRANSLIT[it] ?: it.toString() }.joinToString("")
        val base = latin.replace(UNSAFE_ID, "-").trim('-', '.').ifBlank { "pack" }
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }
            .first { !Settings.packsDir().resolve(it).exists() }
    }

    fun list(): List<Modpack> = runCatching {
        val root = Settings.packsDir()
        if (!root.isDirectory()) return emptyList()
        root.listDirectoryEntries().filter { it.isDirectory() }.mapNotNull(::read).sortedBy { it.title.lowercase() }
    }.getOrDefault(emptyList())

    fun read(dir: Path): Modpack? {
        val file = dir.resolve(MANIFEST)
        if (!file.exists()) return null
        return runCatching { Json.decodeFromString<Modpack>(file.readText()).copy(id = dir.name) }
            .onFailure { Log.warn("$file unreadable: ${it.message}") }
            .getOrNull()
    }

    suspend fun source(projectId: String, slug: String, title: String, iconUrl: String?): PackSource = withContext(Dispatchers.IO) {
        val versions = Modrinth.versions(projectId).filter { version -> version.files.any { it.filename.endsWith(ARCHIVE) } }
        val version = Modrinth.pick(versions) ?: versions.firstOrNull()
            ?: throw IOException("У сборки $title нет файлов для скачивания")
        sourceOf(version, folderFor(slug, projectId), projectId, title, iconUrl)
    }

    suspend fun reinstallSource(pack: Modpack): PackSource = withContext(Dispatchers.IO) {
        sourceOf(Modrinth.version(pack.versionId), pack.id, pack.projectId, pack.title, pack.iconUrl)
    }

    suspend fun update(pack: Modpack): PackSource? = withContext(Dispatchers.IO) {
        val versions = Modrinth.versions(pack.projectId, ModManager.loadersFor(pack.loader).take(1), pack.gameVersion)
            .filter { version -> version.files.any { it.filename.endsWith(ARCHIVE) } }
        val newest = Modrinth.pick(versions) ?: return@withContext null
        val installed = versions.indexOfFirst { it.id == pack.versionId }
        if (newest.id == pack.versionId || (installed >= 0 && versions.indexOf(newest) > installed)) return@withContext null
        sourceOf(newest, pack.id, pack.projectId, pack.title, pack.iconUrl)
    }

    suspend fun install(
        source: PackSource,
        onStage: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
        allowed: (String) -> Boolean = ::isAllowedUrl,
    ): Modpack = withContext(Dispatchers.IO) {
        val dir = Settings.packsDir().resolve(source.id)
        val previous = read(dir)
        onStage("Скачиваю сборку ${source.title}")
        val archive = fetch(source, onProgress)
        ZipFile(archive.toFile()).use { zip ->
            val index = readIndex(zip)
            val gameVersion = index.dependencies["minecraft"]?.takeIf { it.isNotBlank() }
                ?: throw IOException("В сборке ${source.title} не указана версия Minecraft")
            val (loader, loaderVersion) = loaderOf(index.dependencies)
            val downloads = downloadsOf(index, allowed)
            if (downloads.isNotEmpty()) {
                onStage("Скачиваю моды сборки ${source.title}")
                Downloader().run(
                    downloads.map { DownloadTask(it.urls.first(), dir.resolve(it.path), it.sha1, it.size, it.urls.drop(1)) },
                    onProgress,
                )
            }
            onStage("Распаковываю настройки сборки")
            extractOverrides(zip, dir, keep = if (previous != null) KEPT_ON_UPDATE else emptySet())
            val paths = downloads.map { it.path }
            previous?.files.orEmpty().filter { it !in paths }.mapNotNull(::safePath)
                .forEach { stale -> runCatching { dir.resolve(stale).deleteIfExists() } }

            val pack = Modpack(
                id = source.id,
                title = source.title,
                version = source.version,
                gameVersion = gameVersion,
                loader = loader,
                loaderVersion = loaderVersion,
                projectId = source.projectId,
                versionId = source.versionId,
                iconUrl = source.iconUrl,
                files = paths,
            )
            dir.resolve(MANIFEST).writeAtomically(PrettyJson.encodeToString(pack))
            Log.info("modpack ${pack.title} ${pack.version} installed into $dir: ${paths.size} files, $gameVersion ${loader.label} $loaderVersion")
            pack
        }
    }

    internal fun readIndex(zip: ZipFile): PackIndex {
        val entry = zip.getEntry(INDEX) ?: throw IOException("Это не сборка Modrinth: нет $INDEX")
        val index = Json.decodeFromString<PackIndex>(zip.getInputStream(entry).readBytes().decodeToString())
        if (index.formatVersion != 1 || index.game != "minecraft") {
            throw IOException("Сборка в незнакомом формате (${index.game} ${index.formatVersion})")
        }
        return index
    }

    internal fun downloadsOf(index: PackIndex, allowed: (String) -> Boolean): List<PackDownload> =
        index.files.filter { it.env["client"] != "unsupported" }.map { file ->
            val path = safePath(file.path) ?: throw IOException("В сборке недопустимый путь: ${file.path}")
            val sha1 = file.hashes["sha1"] ?: throw IOException("У файла $path нет контрольной суммы")
            val urls = file.downloads.filter(allowed)
            if (urls.isEmpty()) throw IOException("Файл $path скачивается с непроверенного сайта")
            PackDownload(path, urls, sha1, file.fileSize)
        }

    internal fun loaderOf(dependencies: Map<String, String>): Pair<LoaderKind, String?> {
        for ((key, kind) in LOADERS) {
            dependencies[key]?.takeIf { it.isNotBlank() }?.let { return kind to it }
        }
        return LoaderKind.VANILLA to null
    }

    internal fun safePath(raw: String): String? {
        val path = raw.replace('\\', '/')
        if (path.isBlank() || path.startsWith("/") || ':' in path || path == MANIFEST) return null
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return path
    }

    internal fun isAllowedUrl(url: String): Boolean = runCatching {
        val parsed = url.toHttpUrl()
        parsed.isHttps && parsed.host in HOSTS
    }.getOrDefault(false)

    internal fun folderFor(slug: String, projectId: String): String {
        val base = slug.lowercase().replace(UNSAFE_ID, "-").trim('-', '.').ifBlank { "pack" }
        val taken = read(Settings.packsDir().resolve(base))
        return if (taken == null || taken.projectId == projectId) base else "$base-${projectId.take(6).lowercase()}"
    }

    private fun extractOverrides(zip: ZipFile, dir: Path, keep: Set<String>) {
        for (prefix in OVERRIDES) {
            zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) }.forEach { entry ->
                val relative = safePath(entry.name.removePrefix(prefix)) ?: return@forEach
                val target = dir.resolve(relative)
                if (relative in keep && target.exists()) return@forEach
                target.parent?.createDirectories()
                zip.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }

    private suspend fun fetch(source: PackSource, onProgress: (DownloadProgress) -> Unit): Path {
        val archive = Paths.cache.resolve("packs").resolve(source.id + ARCHIVE)
        if (source.sha1 == null) archive.deleteIfExists()
        Downloader().run(listOf(DownloadTask(source.url, archive, source.sha1, source.size, label = source.title + ARCHIVE)), onProgress)
        if (!archive.exists()) throw IOException("Сборка ${source.title} не скачалась")
        return archive
    }

    private fun sourceOf(version: Modrinth.Version, id: String, projectId: String, title: String, iconUrl: String?): PackSource {
        val file = version.files.firstOrNull { it.primary && it.filename.endsWith(ARCHIVE) }
            ?: version.files.firstOrNull { it.filename.endsWith(ARCHIVE) }
            ?: throw IOException("У сборки $title нет файла $ARCHIVE")
        return PackSource(id, title, projectId, version.id, version.versionNumber, file.url, file.sha1, file.size, iconUrl)
    }
}
