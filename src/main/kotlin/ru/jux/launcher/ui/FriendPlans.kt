package ru.jux.launcher.ui

import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.tr
import ru.jux.launcher.friends.Friend
import ru.jux.launcher.friends.FriendPresence
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.meta.GameVersions
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.meta.VersionKind
import ru.jux.launcher.packs.Modpack

/** Строка в меню версий у «Присоединиться». [entry] нет у сборки друга, которую ещё надо скачать. */
data class JoinOption(
    val entry: VersionEntry?,
    val title: String,
    val loader: LoaderKind,
    val note: String,
    val fits: Boolean,
    val installed: Boolean,
)

private const val MENU_INSTALLED = 5
private val GAME_VERSION = Regex("""(?<![\d.])(\d+\.\d+(?:\.\d+)?)(?![\d.])""")

/** Что уходит друзьям: клиент на сервере в интернете важнее, из равных — запущенный последним. */
internal fun friendPresenceOf(games: List<RunningGame>): FriendPresence {
    val game = games.lastOrNull { it.presence.server?.let(FriendPresence::publicAddress) != null } ?: games.lastOrNull()
        ?: return FriendPresence.LAUNCHER
    return FriendPresence.playing(
        server = game.presence.server,
        version = game.entry.id,
        loader = game.entry.loader,
        gameNick = game.nick,
        pack = game.packLink,
        packName = game.entry.pack?.title ?: game.entry.label,
    )
}

/**
 * Какие версии пускает сервер друга: диапазон из каталога juxmc.ru, а если адреса там нет — из ответа сервера
 * на пинг («Velocity 1.21.4-26.2»). Версия null — диапазон неизвестен, подходит любая.
 */
internal fun LauncherState.friendServer(friend: Friend): LaunchLink.Join {
    val address = friend.address.orEmpty()
    val pinged = (serverStatus[address] as? PingState.Online)?.status
    val (min, max) = when {
        friend.serverMax != null -> friend.serverMin to friend.serverMax
        pinged != null -> rangeOf(pinged.versionName) ?: rangeOf(pinged.motd) ?: (null to null)
        else -> null to null
    }
    return LaunchLink.Join(address, max, friend.serverName, min?.takeIf { it != max })
}

/** Диапазон версий из текста сервера: первая и последняя версия игры в нём. */
internal fun rangeOf(text: String): Pair<String?, String>? {
    val found = GAME_VERSION.findAll(text).map { it.groupValues[1] }
        .filter { id -> GameVersions.family(id)?.let { it.first == 1 || it.first >= 24 } == true }
        .toList()
    if (found.isEmpty()) return null
    val sorted = found.sortedWith(::compareReleases)
    return (if (found.size > 1) sorted.first() else null) to sorted.last()
}

/**
 * Пустит ли сервер с версии [id]. Диапазон друга точный: «1.21.4 – 26.2» не пускает 1.21.3, хотя у ссылок
 * на сервер сравнение идёт по major.minor. Снапшоты сравниваются как у ссылок.
 */
internal fun LaunchLink.Join.fits(id: String): Boolean {
    val top = version ?: return true
    if (id == top) return true
    val low = minVersion ?: return false
    if (listOf(id, low, top).any { numbers(it) == null }) return accepts(id)
    return compareReleases(low, id) <= 0 && compareReleases(id, top) <= 0
}

private fun numbers(id: String): List<Int>? = id.split('.').map { it.toIntOrNull() ?: return null }

private fun compareReleases(a: String, b: String): Int {
    val x = numbers(a).orEmpty()
    val y = numbers(b).orEmpty()
    for (i in 0 until maxOf(x.size, y.size)) {
        val diff = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return 0
}

/** Своя сборка друга, если она уже скачана по его ссылке: та же сборка — то же название, версия и загрузчик. */
internal fun LauncherState.ownedPackOf(link: LaunchLink.SharedPack): Modpack? =
    packs.firstOrNull { it.custom && it.title == link.name && it.gameVersion == link.version && it.loader == link.loader }

/** С чем «Присоединиться» зайдёт сразу: запомненный для сервера выбор, пока сервер его пускает, иначе как у друга. */
internal fun LauncherState.friendJoinEntry(friend: Friend): VersionEntry {
    val server = friendServer(friend)
    Settings.current.serverVersions[server.address]?.let(::entryByKey)?.takeIf { server.fits(it.id) }?.let { return it }
    val version = friend.version ?: server.version ?: versions.firstOrNull { it.kind == VersionKind.RELEASE }?.id ?: "26.2"
    return entryFor(version, friend.loaderKind)
}

/** То, чем зайдёт кнопка: сборка друга или [friendJoinEntry]. */
internal fun LauncherState.friendJoinKey(friend: Friend): String? {
    val link = friend.sharedPack ?: return friendJoinEntry(friend).key
    return ownedPackOf(link)?.let { entryFor(it).key }
}

/** Меню у стрелки: версия друга, потом свои установленные — подходящие сначала, неподходящие серым. */
internal fun LauncherState.friendJoinMenu(friend: Friend): List<JoinOption> {
    val server = friendServer(friend)
    val own = friendOption(friend, server)
    val installed = catalogTargets()
        .filter { it.key != own.entry?.key && (it.pack != null || isEntryInstalled(it)) }
        .map { entry ->
            val fits = server.fits(entry.id)
            JoinOption(
                entry = entry,
                title = entry.title,
                loader = entry.loader,
                note = when {
                    !fits -> tr("сервер не пустит", "server won't accept")
                    entry.pack == null && !entry.loader.isModded -> tr("есть, без загрузки", "installed, no download")
                    else -> tr("есть", "installed")
                },
                fits = fits,
                installed = true,
            )
        }
        .sortedWith(compareBy({ !it.fits }, { it.entry?.pack != null || it.loader.isModded }))
        .take(MENU_INSTALLED)
    return listOf(own) + installed
}

/** Окно «Другая версия…»: все версии игры, которые пустит сервер, и свои сборки на них. */
internal fun LauncherState.friendJoinOptions(friend: Friend): List<JoinOption> {
    val server = friendServer(friend)
    val friendVersion = friend.version
    val modded = catalogTargets().filter { (it.pack != null || it.loader.isModded) && server.fits(it.id) }
    val releases = versions.filter { it.kind == VersionKind.RELEASE && server.fits(it.id) }.map { it.id }
    val ids = (listOfNotNull(friendVersion?.takeIf { it !in releases && server.fits(it) }) + releases).distinct()
    return ids.flatMap { id ->
        val vanilla = entryFor(id, LoaderKind.VANILLA)
        val installed = isEntryInstalled(vanilla)
        val state = if (installed) tr("есть", "installed") else tr("новая", "new")
        val note = if (id == friendVersion) tr("как у ${friend.nick} · $state", "same as ${friend.nick} · $state") else state
        listOf(JoinOption(vanilla, vanilla.title, LoaderKind.VANILLA, note, fits = true, installed = installed)) +
            modded.filter { it.id == id }.map { entry ->
                JoinOption(entry, entry.title, entry.loader, if (entry.pack != null) tr("сборка", "pack") else tr("есть", "installed"), fits = true, installed = true)
            }
    }
}

private fun LauncherState.friendOption(friend: Friend, server: LaunchLink.Join): JoinOption {
    val same = tr("как у ${friend.nick}", "same as ${friend.nick}")
    val link = friend.sharedPack
    if (link != null) {
        val owned = ownedPackOf(link)?.let(::entryFor)
        return JoinOption(owned, link.name, link.loader, same, fits = server.fits(link.version), installed = owned != null)
    }
    val entry = entryFor(friend.version ?: friendJoinEntry(friend).id, friend.loaderKind)
    return JoinOption(entry, entry.title, entry.loader, same, fits = server.fits(entry.id), installed = isEntryInstalled(entry))
}
