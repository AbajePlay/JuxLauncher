package ru.jux.launcher.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.jux.launcher.core.tr
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.link.LinkContent
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.meta.VersionKind
import ru.jux.launcher.mods.ContentKind
import ru.jux.launcher.mods.ModManager
import ru.jux.launcher.mods.Modrinth
import ru.jux.launcher.net.HttpStatusException

private val MOD_LOADERS = listOf(LoaderKind.FABRIC, LoaderKind.NEOFORGE, LoaderKind.FORGE, LoaderKind.QUILT)

internal fun LauncherState.joinModal(link: LaunchLink.Join): Modal.Join {
    val latest = versions.firstOrNull { it.kind == VersionKind.RELEASE }?.id
    val known = link.version?.takeIf { id -> versions.any { it.id == id } }
    val gameVersion = known ?: link.version?.let { latest }
    val targets = (listOfNotNull(currentEntry()) + catalogTargets()).distinctBy { it.key }
    // Сначала сборки, с которыми сервер пускает (его версия или диапазон «1.8–26.3»), потом чистая игра
    // его версии, потом все остальные установленные: сервер с ViaVersion пускает и с других, выбирать игроку
    val choices = when (gameVersion) {
        null -> targets.ifEmpty { listOfNotNull(latest?.let { entryFor(it, LoaderKind.VANILLA) }) }
        else -> {
            val (fit, other) = targets.partition { it.id == gameVersion || link.accepts(it.id) }
            (fit + entryFor(gameVersion, LoaderKind.VANILLA) + other).distinctBy { it.key }
        }
    }
    val note = when {
        link.version == null || known != null -> null
        latest == null -> tr("Нет списка версий — проверь интернет", "No version list — check your connection")
        else -> tr("Версии ${link.version} нет — предлагаю $latest", "No version ${link.version} — suggesting $latest")
    }
    return Modal.Join(link, choices, note)
}

internal suspend fun LauncherState.installPlan(link: LaunchLink.Install): InstallPlan {
    val project = try {
        withContext(Dispatchers.IO) { Modrinth.project(link.project) }
    } catch (e: HttpStatusException) {
        if (e.code != 404) throw e
        return InstallPlan.Failed(tr("«${link.title}» нет на Modrinth", "“${link.title}” isn't on Modrinth"))
    }
    if (link.content == LinkContent.MODPACK) return InstallPlan.Pack(project, packs.firstOrNull { it.projectId == project.id })
    val kind = link.content.content ?: return InstallPlan.Failed(tr("Датапаки ставятся в мир, а не в сборку", "Data packs go into a world, not a pack"))

    val candidates = catalogTargets().filter { canHold(it, kind) }
    val fresh = listOfNotNull(
        link.version?.takeIf { id -> versions.any { it.id == id } },
        versions.firstOrNull { it.kind == VersionKind.RELEASE && it.id in project.gameVersions }?.id,
    ).distinct()
    val gameVersions = (candidates.map { it.id } + fresh).distinct()
    val files = if (gameVersions.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
        Modrinth.versionsFor(project.id, emptyList(), gameVersions)
    }
    val fits = { entry: VersionEntry ->
        val loaders = ModManager.catalogLoaders(kind, entry.loader)
        Modrinth.pick(files.filter { file -> entry.id in file.gameVersions && file.loaders.any { it in loaders } }) != null
    }

    val compatible = candidates.filter(fits)
    val created = fresh.asSequence()
        .flatMap { id -> freshLoaders(kind, link.loader).map { entryFor(id, it) } }
        .firstOrNull { loaderSupport.supports(it.loader, it.id) && canHold(it, kind) && fits(it) }
        ?.takeIf { entry -> compatible.none { it.key == entry.key } }
    val targets = (compatible + listOfNotNull(created)).sortedBy { entry ->
        (if (entry.id == link.version) 0 else 2) + (if (entry.loader.name.equals(link.loader, ignoreCase = true)) 0 else 1)
    }
    if (targets.isEmpty()) {
        val wanted = listOfNotNull(link.version?.let { "Minecraft $it" }, link.loader?.replaceFirstChar { it.uppercase() })
        return InstallPlan.Failed(
            tr(
                "У ${project.title} нет версии под ${wanted.joinToString(" ").ifEmpty { "твои сборки" }}",
                "${project.title} has no version for ${wanted.joinToString(" ").ifEmpty { "your packs" }}",
            ),
        )
    }
    return InstallPlan.Content(project, kind, targets, unfit = candidates.size - compatible.size)
}

private fun LauncherState.canHold(entry: VersionEntry, kind: ContentKind): Boolean = when (kind) {
    ContentKind.MOD -> entry.loader.hasMods
    ContentKind.SHADER -> supportsShaders(entry)
    ContentKind.RESOURCE_PACK -> true
}

private fun freshLoaders(kind: ContentKind, wanted: String?): List<LoaderKind> = when (kind) {
    ContentKind.MOD -> (MOD_LOADERS.filter { it.name.equals(wanted, ignoreCase = true) } + MOD_LOADERS).distinct()
    ContentKind.SHADER, ContentKind.RESOURCE_PACK -> listOf(LoaderKind.VANILLA)
}
