package ru.jux.launcher.packs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Storage
import ru.jux.launcher.core.plural
import ru.jux.launcher.core.tr
import ru.jux.launcher.instance.InstanceStore
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.link.LinkContent
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.mods.ContentKind
import ru.jux.launcher.mods.GameOptions
import ru.jux.launcher.mods.InstalledItem
import ru.jux.launcher.mods.ModManager
import ru.jux.launcher.mods.Modrinth
import ru.jux.launcher.net.DownloadProgress
import java.io.IOException
import java.nio.file.Path

/** Ссылка, которую игрок отправит другу, и что в неё не попало. */
data class PackShare(val uri: String, val summary: String, val skipped: List<String> = emptyList())

/** Что поставится по ссылке друга: точные версии с Modrinth по видам. */
data class PackImport(
    val mods: List<Modrinth.Version>,
    val shaders: List<Modrinth.Version>,
    val resourcePacks: List<Modrinth.Version>,
    val titles: Map<String, String>,
    val missing: Int,
) {
    val isEmpty: Boolean get() = mods.isEmpty() && shaders.isEmpty() && resourcePacks.isEmpty()

    fun titleOf(version: Modrinth.Version): String = titles[version.projectId] ?: version.name.ifBlank { version.projectId }
}

object PackLinks {

    /**
     * Ссылка на сборку из папки [gameDir]. Сборка с Modrinth отдаётся ссылкой на сам проект,
     * остальное — версией игры, загрузчиком и включёнными модами, шейдером и ресурспаками.
     */
    suspend fun share(
        gameDir: Path,
        gameVersion: String,
        loader: LoaderKind,
        pack: Modpack?,
        title: String,
    ): PackShare = withContext(Dispatchers.IO) {
        if (pack != null && !pack.custom) {
            return@withContext PackShare(
                LaunchLink.installUri(pack.projectId, LinkContent.MODPACK, pack.title),
                tr("Сборка ${pack.title} с Modrinth — у друга поставится её свежая версия", "${pack.title} from Modrinth — your friend gets its latest version"),
            )
        }
        // У ванили с FPS-бустом моды — это Fabric-моды буста и Iris, другу они достанутся сборкой на Fabric
        val boosted = loader == LoaderKind.VANILLA && InstanceStore.get(gameDir).fpsBoost
        val mods = if (loader.hasMods || boosted) enabled(gameDir, loader, gameVersion, ContentKind.MOD) else emptyList()
        val target = if (boosted && mods.isNotEmpty()) LoaderKind.FABRIC else loader
        // Шейдеру нужен Iris или OptiFine: в чистой ванили и на Forge он у друга не заработает
        val shaders = if (target != LoaderKind.VANILLA && ModManager.shaderLoader(target) != null) {
            enabled(gameDir, loader, gameVersion, ContentKind.SHADER)
        } else {
            emptyList()
        }
        val order = GameOptions.resourcePacks(gameDir)
        val resourcePacks = enabled(gameDir, loader, gameVersion, ContentKind.RESOURCE_PACK)
            .sortedBy { order.indexOf("file/${it.fileName}") }
        val all = mods + shaders + resourcePacks
        if (all.isNotEmpty() && all.all { it.versionId == null }) {
            throw IOException(tr("Ни один файл не нашёлся на Modrinth — проверь интернет", "None of the files were found on Modrinth — check your connection"))
        }
        val link = LaunchLink.SharedPack(
            name = title,
            version = gameVersion,
            loader = target,
            loaderVersion = pack?.loaderVersion,
            mods = mods.mapNotNull { it.versionId },
            shaders = shaders.mapNotNull { it.versionId },
            resourcePacks = resourcePacks.mapNotNull { it.versionId },
        )
        val uri = link.toUri()
        if (uri.length > LaunchLink.MAX_LENGTH) {
            throw IOException(tr("Слишком много файлов для одной ссылки: ${all.size}", "Too many files for one link: ${all.size}"))
        }
        PackShare(uri, summary(link), all.filter { it.versionId == null }.map { it.fileName })
    }

    private suspend fun enabled(gameDir: Path, loader: LoaderKind, gameVersion: String, kind: ContentKind): List<InstalledItem> =
        ModManager.scan(gameDir, loader, gameVersion, kind).filter { it.enabled }

    fun summary(link: LaunchLink.SharedPack): String = listOfNotNull(
        "${link.version} ${link.loader.label}",
        link.mods.size.takeIf { it > 0 }?.let { "$it ${plural(it, "мод", "мода", "модов", "mod", "mods")}" },
        link.shaders.size.takeIf { it > 0 }?.let { "$it ${plural(it, "шейдер", "шейдера", "шейдеров", "shader", "shaders")}" },
        link.resourcePacks.size.takeIf { it > 0 }?.let { "$it ${plural(it, "ресурспак", "ресурспака", "ресурспаков", "resource pack", "resource packs")}" },
    ).joinToString(" · ")

    /** Находит на Modrinth всё, что перечислено в ссылке. */
    suspend fun resolve(link: LaunchLink.SharedPack): PackImport = withContext(Dispatchers.IO) {
        val ids = link.versionIds
        val found = Modrinth.versionsByIds(ids).associateBy { it.id }
        val titles = try {
            Modrinth.titles(found.values.map { it.projectId }.distinct())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.warn("titles for a shared pack: ${e.message}")
            emptyMap()
        }
        PackImport(
            mods = link.mods.mapNotNull(found::get),
            shaders = link.shaders.mapNotNull(found::get),
            resourcePacks = link.resourcePacks.mapNotNull(found::get),
            titles = titles,
            missing = ids.count { it !in found },
        )
    }

    /** Создаёт свою сборку по ссылке и качает в неё файлы; если не вышло — сборка удаляется целиком. */
    suspend fun install(
        link: LaunchLink.SharedPack,
        plan: PackImport,
        onStage: (String) -> Unit,
        onProgress: (DownloadProgress) -> Unit,
    ): Modpack {
        val pack = withContext(Dispatchers.IO) { Modpacks.create(link.name, link.version, link.loader, link.loaderVersion) }
        val dir = Modpacks.dirOf(pack)
        try {
            if (!plan.isEmpty) onStage(tr("Скачиваю файлы сборки ${pack.title}", "Downloading files for ${pack.title}"))
            ModManager.download(dir, ContentKind.MOD, plan.mods, onProgress)
            val shaders = ModManager.download(dir, ContentKind.SHADER, plan.shaders, onProgress)
            val resourcePacks = ModManager.download(dir, ContentKind.RESOURCE_PACK, plan.resourcePacks, onProgress)
            withContext(Dispatchers.IO) {
                shaders.firstOrNull()?.let { GameOptions.setShader(dir, it) }
                resourcePacks.forEach { GameOptions.setResourcePack(dir, it, enabled = true) }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                InstanceStore.forget(dir)
                Storage.deleteTree(dir)
            }
            throw e
        }
        Log.info("shared pack ${pack.title} created in $dir: ${plan.mods.size} mods, ${plan.shaders.size} shaders, ${plan.resourcePacks.size} resource packs")
        return pack
    }
}
