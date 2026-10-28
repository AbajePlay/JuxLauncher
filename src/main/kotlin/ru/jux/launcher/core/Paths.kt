package ru.jux.launcher.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object Paths {

    val root: Path = Path.of(System.getProperty("user.home")).resolve(".jux")

    private val legacyRoot: Path? = System.getenv("APPDATA")
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it).resolve(".jux") }

    val versions: Path = root.resolve("versions")
    val libraries: Path = root.resolve("libraries")
    val assets: Path = root.resolve("assets")
    val assetIndexes: Path = assets.resolve("indexes")
    val assetObjects: Path = assets.resolve("objects")
    val runtimes: Path = root.resolve("runtimes")
    val natives: Path = root.resolve("natives")
    val instances: Path = root.resolve("instances")
    val logs: Path = root.resolve("logs")
    val cache: Path = root.resolve("cache")

    val settingsFile: Path = root.resolve("launcher.json")
    val accountsFile: Path = root.resolve("accounts.json")
    val friendsFile: Path = root.resolve("friends.json")
    val noticesFile: Path = root.resolve("notifications.json")
    val backdropImage: Path = root.resolve("backdrop.img")
    val verifyCacheFile: Path = cache.resolve("verify.index")

    fun versionDir(id: String): Path = versions.resolve(id)
    fun versionJson(id: String): Path = versionDir(id).resolve("$id.json")
    fun versionJar(id: String): Path = versionDir(id).resolve("$id.jar")
    fun nativesDir(id: String): Path = natives.resolve(id)
    fun instanceDir(name: String): Path = instances.resolve(name.sanitized())

    fun libraryPath(relative: String): Path = libraries.resolve(relative)

    fun assetObject(hash: String): Path = assetObjects.resolve(hash.substring(0, 2)).resolve(hash)

    /** Папка официального лаунчера внутри [home]: так её ищут и сам Minecraft, и установщик OptiFine. */
    fun officialMinecraft(
        home: Path = Path.of(System.getProperty("user.home")),
        appData: String? = System.getenv("APPDATA"),
        windows: Boolean = Shell.isWindows,
        mac: Boolean = Shell.isMac,
    ): Path = when {
        windows -> (appData?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: home).resolve(".minecraft")
        mac -> home.resolve("Library").resolve("Application Support").resolve("minecraft")
        else -> home.resolve(".minecraft")
    }

    fun migrateLegacyLocation(): String? {
        val legacy = legacyRoot ?: return null
        if (legacy == root || !legacy.exists()) return null
        if (root.exists()) {
            return "both $legacy and $root exist; using $root and leaving the old one alone"
        }
        runCatching {
            root.parent?.createDirectories()
            Files.move(legacy, root, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return "moved launcher data: $legacy -> $root"
        }.onFailure { atomicFailure ->
            return runCatching {
                legacy.toFile().copyRecursively(root.toFile(), overwrite = false)
                legacy.toFile().deleteRecursively()
                "copied launcher data: $legacy -> $root"
            }.getOrElse {
                "COULD NOT migrate from $legacy (${atomicFailure.message} / ${it.message})"
            }
        }
        return null
    }

    fun ensureBaseDirs() {
        listOf(root, versions, libraries, assetIndexes, assetObjects, runtimes, natives, instances, logs, cache)
            .forEach { it.createDirectories() }
    }

    private val illegal = Regex("""[\\/:*?"<>|]""")

    private fun String.sanitized(): String =
        replace(illegal, "_").replace("..", "_").trim().ifBlank { "default" }
}

/** [ownerOnly] — файл читает только владелец (rw-------) там, где есть права POSIX: на Windows токены шифрует DPAPI. */
fun Path.writeAtomically(bytes: ByteArray, ownerOnly: Boolean = false) {
    parent?.createDirectories()
    val tmp = resolveSibling("$fileName.tmp")
    if (ownerOnly && fileSystem.supportedFileAttributeViews().contains("posix")) {
        Files.deleteIfExists(tmp)
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    }
    Files.write(tmp, bytes)
    try {
        Files.move(tmp, this, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(tmp, this, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

fun Path.writeAtomically(text: String, ownerOnly: Boolean = false) = writeAtomically(text.toByteArray(Charsets.UTF_8), ownerOnly)
