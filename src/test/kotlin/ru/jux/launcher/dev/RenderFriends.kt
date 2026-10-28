package ru.jux.launcher.dev

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.jux.launcher.core.Paths
import ru.jux.launcher.friends.Friend
import ru.jux.launcher.friends.FriendMe
import ru.jux.launcher.friends.FriendRequest
import ru.jux.launcher.friends.FriendSettings
import ru.jux.launcher.friends.FriendState
import ru.jux.launcher.friends.Friends
import ru.jux.launcher.friends.FriendsAccount
import ru.jux.launcher.friends.FriendsSnapshot
import ru.jux.launcher.friends.LinkStart
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.net.DownloadProgress
import ru.jux.launcher.packs.Modpack
import ru.jux.launcher.packs.PackImport
import ru.jux.launcher.ui.App
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.ModIcons
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.PingState
import ru.jux.launcher.ui.Screen
import ru.jux.launcher.ui.SkinHeads
import ru.jux.launcher.ui.components.JuxDropdownMenu
import ru.jux.launcher.ui.screens.FriendMenuItems
import ru.jux.launcher.ui.screens.JoinVersionItems
import ru.jux.launcher.ui.theme.JuxTheme
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

// Вкладка «Друзья» на образце без сети к juxmc.ru: головы — скины по умолчанию из клиента игры, сервер — настоящий пинг
internal fun renderFriends(
    state: LauncherState,
    packs: List<Modpack>,
    render: (name: String, width: Int, height: Int, realMillis: Long, content: @Composable () -> Unit) -> Unit,
) {
    SkinHeads.extraJars = listOfNotNull(skinJar())
    val app = @Composable { JuxTheme { App(state, onGameStarted = {}) } }

    Friends.show(null, null)
    state.modal = null
    state.screen = Screen.FRIENDS
    render("friends-signin", 1040, 660, 1_000, app)
    state.modal = Modal.FriendLink(LinkStart("JX47KQ", "", "https://juxmc.ru/launcher/link/?code=JX4-7KQ", 600, 3))
    render("friends-link-code", 1040, 660, 0, app)
    state.modal = null

    val heads = Paths.cache.resolve("preview-heads").also { it.createDirectories() }
    fun head(skin: String): String? = faceFile(skin, heads)?.let(ModIcons::localUrl)
    val shared = LaunchLink.SharedPack(
        name = "Выживание с друзьями",
        version = "1.21.11",
        loader = LoaderKind.FABRIC,
        mods = List(34) { "mod${it.toString().padStart(5, '0')}" },
        shaders = listOf("shader01"),
        resourcePacks = listOf("rpack001", "rpack002"),
    )
    val now = System.currentTimeMillis() / 1000
    val kirill = Friend(
        "Kirill_Play", head("wide/kai"), FriendState.SERVER, "mc.virtusmine.fun", "VirtusMine", "1.21.4", "26.2",
        "26.2", "vanilla", "Kirill_Play",
    )
    val nastya = Friend(
        "Nastya", head("slim/alex"), FriendState.SERVER, "mc.virtusmine.fun", "VirtusMine", "1.21.4", "26.2",
        "1.21.11", "fabric", "nastya_mc", shared.toUri(), shared.name,
    )
    val snapshot = FriendsSnapshot(
        me = FriendMe("Abaje", head("wide/steve")),
        settings = FriendSettings(),
        friends = listOf(
            kirill,
            nastya,
            Friend("MrDenis", head("wide/efe"), FriendState.SINGLE, version = "26.3", loader = "fabric", gameNick = "MrDenis"),
            Friend("Sanya_228", head("wide/noor"), FriendState.LAUNCHER),
            Friend("Lexa", head("wide/zuri"), FriendState.OFFLINE, seen = now - 2 * 3600 - 300),
            Friend("Vika_PvP", head("slim/sunny"), FriendState.OFFLINE, seen = yesterdayAt(22, 40)),
        ),
        incoming = listOf(FriendRequest("Polina", head("slim/makena")), FriendRequest("Dimon4ik", head("wide/ari"))),
    )
    Friends.show(FriendsAccount("Abaje", "", System.currentTimeMillis()), snapshot)
    state.installed = state.installed + mapOf("1.21.11" to 1L, "26.3" to 1L)
    state.pingFriendServers()
    val pingUntil = System.currentTimeMillis() + 8_000
    while (state.serverStatus[kirill.address] !is PingState.Online && System.currentTimeMillis() < pingUntil) Thread.sleep(100)
    check(packs.any { it.title == shared.name }) { "the preview state needs the pack ${shared.name}" }

    render("friends", 1040, 1060, 0, app)
    render("friends-first-screen", 1040, 660, 0, app)
    render("friends-menu", 1040, 660, 0) {
        JuxTheme {
            Box(Modifier.fillMaxSize()) {
                App(state, onGameStarted = {})
                Box(Modifier.offset(x = 985.dp, y = 432.dp).size(1.dp)) {
                    JuxDropdownMenu(expanded = true, onDismissRequest = {}) { FriendMenuItems(state, kirill, close = {}, confirm = { _, _ -> }) }
                }
            }
        }
    }
    render("friends-join-versions", 1040, 660, 0) {
        JuxTheme {
            Box(Modifier.fillMaxSize()) {
                App(state, onGameStarted = {})
                Box(Modifier.offset(x = 558.dp, y = 132.dp).size(1.dp)) {
                    JuxDropdownMenu(expanded = true, onDismissRequest = {}) { JoinVersionItems(state, kirill, close = {}) }
                }
            }
        }
    }

    // Обычный сервер: версия друга качается прямо на кнопке
    state.busy = true
    state.joiningFriend = kirill.nick
    state.progress = DownloadProgress(120, 260, 45, 100, 0, "")
    render("friends-join-progress", 1040, 660, 0, app)
    state.busy = false
    state.joiningFriend = null
    state.progress = null

    state.modal = Modal.FriendVersions(kirill)
    render("friends-join-other-version", 1040, 660, 0, app)
    state.modal = Modal.FriendPack(nastya, shared, PackImport(emptyList(), emptyList(), emptyList(), emptyMap(), missing = 0))
    render("friends-join-pack", 1040, 660, 0, app)
    state.modal = null

    Friends.show(
        FriendsAccount("Abaje", "", System.currentTimeMillis()),
        snapshot.copy(friends = emptyList(), incoming = emptyList(), outgoing = listOf(FriendRequest("Kirill_Play", head("wide/kai")))),
    )
    render("friends-empty", 1040, 660, 0, app)

    Friends.show(FriendsAccount("Abaje", "", System.currentTimeMillis()), snapshot)
    state.screen = Screen.SETTINGS
    render("settings-friends", 1040, 1900, 0, app)

    Friends.show(null, null)
    state.screen = Screen.HOME
}

/** Клиент игры со скинами по умолчанию: -Dskins=путь или самый свежий из настоящей ~/.jux (только чтение). */
private fun skinJar(): Path? {
    System.getProperty("skins")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
    val versions = Path.of(System.getenv("USERPROFILE") ?: return null).resolve(".jux").resolve("versions")
    if (!versions.isDirectory()) return null
    return versions.listDirectoryEntries()
        .map { it.resolve("${it.name}.jar") }
        .filter { it.exists() }
        .sortedByDescending { it.getLastModifiedTime().toMillis() }
        .firstOrNull { jar -> runCatching { ZipFile(jar.toFile()).use { it.getEntry("assets/minecraft/textures/entity/player/wide/steve.png") != null } }.getOrDefault(false) }
}

/** Лицо скина 64×64 без сглаживания — так выглядит аватар с juxmc.ru. */
private fun faceFile(skin: String, dir: Path): Path? {
    val jar = SkinHeads.extraJars.firstOrNull() ?: return null
    val target = dir.resolve(skin.replace('/', '-') + ".png")
    if (target.exists()) return target
    val image = ZipFile(jar.toFile()).use { zip ->
        val entry = zip.getEntry("assets/minecraft/textures/entity/player/$skin.png") ?: return null
        zip.getInputStream(entry).use { ImageIO.read(it) }
    }
    val face = SkinHeads.face(image) ?: return null
    val big = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
    big.createGraphics().apply {
        drawImage(face.getScaledInstance(64, 64, Image.SCALE_REPLICATE), 0, 0, null)
        dispose()
    }
    ImageIO.write(big, "png", File(target.toString()))
    return target
}

private fun yesterdayAt(hour: Int, minute: Int): Long =
    java.time.LocalDate.now().minusDays(1).atTime(hour, minute).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
