package ru.jux.launcher.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.tr
import ru.jux.launcher.friends.Friend
import ru.jux.launcher.friends.FriendAction
import ru.jux.launcher.friends.FriendRequest
import ru.jux.launcher.friends.FriendState
import ru.jux.launcher.friends.Friends
import ru.jux.launcher.friends.FriendsSnapshot
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.online.JuxSite
import ru.jux.launcher.ui.JoinOption
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.ModIcons
import ru.jux.launcher.ui.PingState
import ru.jux.launcher.ui.SkinHeads
import ru.jux.launcher.ui.friendJoinKey
import ru.jux.launcher.ui.friendJoinMenu
import ru.jux.launcher.ui.friendServer
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.ConfirmDialog
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDropdownMenu
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.JuxMenuItem
import ru.jux.launcher.ui.components.JuxTextField
import ru.jux.launcher.ui.components.MenuDivider
import ru.jux.launcher.ui.components.Panel
import ru.jux.launcher.ui.components.ReportOpen
import ru.jux.launcher.ui.components.SectionTitle
import ru.jux.launcher.ui.components.Tag
import ru.jux.launcher.ui.components.formatSeen
import ru.jux.launcher.ui.components.loaderColor
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens
import ru.jux.launcher.ui.theme.PillShape
import ru.jux.launcher.link.LaunchLink

private val NICKNAME = Regex("^[A-Za-z0-9_]{3,16}$")

@Composable
fun FriendsScreen(state: LauncherState) {
    val account by state.friendsAccount.collectAsState()
    val snapshot by state.friendsSnapshot.collectAsState()
    val unreachable by Friends.unreachable.collectAsState()
    if (account == null) {
        SignIn(state)
        return
    }
    LaunchedEffect(snapshot) { state.pingFriendServers() }
    var confirm by remember { mutableStateOf<Pair<FriendAction, String>?>(null) }

    Column(Modifier.fillMaxSize().padding(JuxDimens.Gutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Header(state, snapshot)
        val current = snapshot
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            when {
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (unreachable) tr("juxmc.ru не отвечает — список появится, когда сайт ответит", "juxmc.ru isn't responding — the list will show up once it does")
                        else tr("Загружаю друзей…", "Loading friends…"),
                        style = MaterialTheme.typography.bodySmall,
                        color = JuxColors.TextMuted,
                    )
                }
                current.friends.isEmpty() && current.incoming.isEmpty() -> EmptyFriends(state, current)
                else -> FriendList(state, current) { action, nick -> confirm = action to nick }
            }
        }
    }

    confirm?.let { (action, nick) ->
        val remove = action == FriendAction.REMOVE
        ConfirmDialog(
            title = if (remove) tr("Удалить $nick из друзей?", "Remove $nick from friends?") else tr("Заблокировать $nick?", "Block $nick?"),
            text = if (remove) tr("Добавить снова можно будет заявкой", "You can add them again with a friend request")
            else tr("Дружба и заявки пропадут, $nick не увидит тебя и не сможет прислать заявку. Разблокировать — на сайте", "Your friendship and requests are removed, $nick won't see you or be able to send a request. Unblock on the site"),
            action = if (remove) tr("Удалить", "Remove") else tr("Заблокировать", "Block"),
            onDismiss = { confirm = null },
            onConfirm = { state.friendAction(action, nick) },
        )
    }
}

// --- До входа ---

@Composable
private fun SignIn(state: LauncherState) {
    Panel(Modifier.fillMaxSize().padding(JuxDimens.Gutter)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row {
                listOf("wide/kai", "slim/alex", "wide/efe").forEachIndexed { index, skin ->
                    Box(Modifier.offset(x = (-14 * index).dp)) {
                        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(JuxColors.Surface).padding(4.dp)) { BuiltInHead(skin, 60.dp) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(tr("Играй с друзьями", "Play with friends"), style = MaterialTheme.typography.displaySmall, color = JuxColors.Text)
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Point(JuxIcons.Server, tr("Видно, кто в сети и на каком сервере играет", "See who's online and which server they're on"))
                Point(Icons.Default.PlayArrow, tr("Заходишь к другу одной кнопкой: версия и сборка поставятся сами", "Join a friend with one button: the version and pack install themselves"))
                Point(JuxIcons.Friends, tr("Друзья те же, что на juxmc.ru: добавляй здесь или в профиле на сайте", "Same friends as on juxmc.ru: add them here or on their profile on the site"))
            }
            Spacer(Modifier.height(26.dp))
            JuxButton(
                tr("Войти через juxmc.ru", "Sign in with juxmc.ru"),
                onClick = state::signInFriends,
                style = ButtonStyle.PRIMARY,
                icon = JuxIcons.OpenInNew,
                enabled = !state.friendLinkStarting,
            )
        }
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(JuxColors.AccentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = JuxColors.Accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = JuxColors.TextSoft)
    }
}

// --- Шапка и список ---

@Composable
private fun Header(state: LauncherState, snapshot: FriendsSnapshot?) {
    var nick by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val submit = submit@{
        val wanted = nick.trim()
        if (sending || wanted.isEmpty()) return@submit
        if (!NICKNAME.matches(wanted)) {
            error = tr("Ник на juxmc.ru: 3–16 символов, латиница, цифры и подчёркивание", "juxmc.ru nickname: 3–16 characters, Latin letters, digits and underscores")
            return@submit
        }
        sending = true
        error = null
        state.friendAction(
            FriendAction.REQUEST,
            wanted,
            onError = {
                sending = false
                error = it
            },
            onDone = {
                sending = false
                nick = ""
            },
        )
    }
    val total = snapshot?.friends?.size ?: 0
    val online = snapshot?.onlineCount ?: 0

    Panel(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(150.dp)) {
                    Text(tr("Друзья", "Friends"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = JuxColors.Text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (online > 0) JuxColors.Success else JuxColors.TextMuted)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (total == 0) tr("пока никого", "nobody yet") else tr("$online в сети из $total", "$online online of $total"),
                            style = MaterialTheme.typography.bodySmall,
                            color = JuxColors.TextMuted,
                            maxLines = 1,
                        )
                    }
                }
                JuxTextField(
                    nick,
                    {
                        nick = it
                        error = null
                    },
                    tr("Ник на juxmc.ru", "juxmc.ru nickname"),
                    Modifier.weight(1f),
                    leadingIcon = Icons.Default.Person,
                    isError = error != null,
                    onSubmit = submit,
                )
                JuxButton(
                    tr("Добавить", "Add"),
                    onClick = submit,
                    icon = Icons.Default.Add,
                    style = if (nick.isBlank()) ButtonStyle.SECONDARY else ButtonStyle.PRIMARY,
                    enabled = !sending,
                )
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = JuxColors.Danger, modifier = Modifier.padding(start = 162.dp, top = 8.dp))
            }
        }
    }
}

@Composable
private fun FriendList(state: LauncherState, snapshot: FriendsSnapshot, confirm: (FriendAction, String) -> Unit) {
    val playing = snapshot.friends.filter { it.state.playing }
    val idle = snapshot.friends.filter { it.state == FriendState.LAUNCHER || it.state == FriendState.ONLINE }
    val offline = snapshot.friends.filter { it.state == FriendState.OFFLINE }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section(tr("Заявки", "Requests"), snapshot.incoming) { RequestRow(state, it) }
        Section(tr("В игре", "In game"), playing) { FriendRow(state, it, confirm) }
        Section(tr("В лаунчере", "In the launcher"), idle) { FriendRow(state, it, confirm) }
        Section(tr("Не в сети", "Offline"), offline) { FriendRow(state, it, confirm) }
        Section(tr("Отправленные заявки", "Sent requests"), snapshot.outgoing) { OutgoingRow(state, it) }
    }
}

@Composable
private fun <T> Section(title: String, items: List<T>, row: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    SectionTitle("$title · ${items.size}")
    items.forEach { row(it) }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun EmptyFriends(state: LauncherState, snapshot: FriendsSnapshot) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (snapshot.outgoing.isNotEmpty()) {
            SectionTitle(tr("Отправленные заявки", "Sent requests") + " · ${snapshot.outgoing.size}")
            snapshot.outgoing.forEach { OutgoingRow(state, it) }
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(JuxIcons.Friends, null, tint = JuxColors.TextMuted, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(10.dp))
            Text(tr("Друзей пока нет", "No friends yet"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = JuxColors.Text)
            Spacer(Modifier.height(4.dp))
            Text(
                tr("Добавь по нику с juxmc.ru или кнопкой «В друзья» в профиле на сайте", "Add someone by their juxmc.ru nickname or with “Add friend” on their profile on the site"),
                style = MaterialTheme.typography.bodySmall,
                color = JuxColors.TextMuted,
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun RequestRow(state: LauncherState, request: FriendRequest) {
    Card {
        FriendHead(request.head, request.nick, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(request.nick, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = JuxColors.Text)
            Text(tr("хочет добавить тебя в друзья", "wants to be your friend"), style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
        }
        SmallButton(tr("Принять", "Accept"), primary = true, icon = Icons.Default.Check) { state.friendAction(FriendAction.ACCEPT, request.nick) }
        SmallButton(tr("Отклонить", "Decline")) { state.friendAction(FriendAction.DECLINE, request.nick) }
    }
}

@Composable
private fun OutgoingRow(state: LauncherState, request: FriendRequest) {
    Card {
        FriendHead(request.head, request.nick, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(request.nick, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = JuxColors.Text)
            Text(tr("заявка отправлена, ждёт ответа", "request sent, waiting for an answer"), style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
        }
        SmallButton(tr("Отменить", "Cancel")) { state.friendAction(FriendAction.CANCEL, request.nick) }
    }
}

@Composable
private fun FriendRow(state: LauncherState, friend: Friend, confirm: (FriendAction, String) -> Unit) {
    val offline = friend.state == FriendState.OFFLINE
    var menu by remember { mutableStateOf(false) }
    ReportOpen(menu, state::trackMenu)
    Card {
        Box {
            FriendHead(friend.head, friend.nick, 48.dp, dim = offline)
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(14.dp).clip(CircleShape).background(JuxColors.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) { Dot(statusColor(friend.state), 8.dp) }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    friend.nick,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (offline) JuxColors.TextSoft else JuxColors.Text,
                    maxLines = 1,
                )
                if (friend.gameNick != null && friend.state.playing) {
                    Spacer(Modifier.width(8.dp))
                    Text(tr("в игре ${friend.gameNick}", "in game ${friend.gameNick}"), style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted, maxLines = 1)
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Whereabouts(state, friend)
            }
        }
        if (friend.joinable) JoinButton(state, friend)
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.MoreVert, tr("Ещё", "More"), tint = JuxColors.TextMuted, modifier = Modifier.size(20.dp))
            }
            JuxDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                FriendMenuItems(state, friend, close = { menu = false }, confirm = confirm)
            }
        }
    }
}

@Composable
private fun RowScope.Whereabouts(state: LauncherState, friend: Friend) {
    val small = MaterialTheme.typography.bodySmall
    when (friend.state) {
        FriendState.SERVER -> {
            val favicon = (state.serverStatus[friend.address.orEmpty()] as? PingState.Online)?.status?.favicon
            ServerIcon(favicon, 16.dp, 4.dp)
            Text(friend.serverTitle ?: tr("Сервер", "Server"), style = small, fontWeight = FontWeight.Bold, color = JuxColors.Text, maxLines = 1)
            if (friend.serverName != null && friend.address != null) {
                Text(friend.address, style = small, color = JuxColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            VersionTag(friend)
        }
        FriendState.SINGLE -> {
            Text(tr("Одиночная игра", "Singleplayer"), style = small, fontWeight = FontWeight.Bold, color = JuxColors.Text)
            VersionTag(friend)
        }
        FriendState.LAUNCHER -> Text(tr("В лаунчере, выбирает, во что играть", "In the launcher, picking what to play"), style = small, color = JuxColors.TextMuted)
        FriendState.ONLINE -> Text(tr("В сети", "Online"), style = small, color = JuxColors.TextMuted)
        FriendState.OFFLINE -> Text(friend.seen?.let { formatSeen(it * 1000) } ?: tr("не в сети", "offline"), style = small, color = JuxColors.TextMuted)
    }
}

@Composable
private fun VersionTag(friend: Friend) {
    val version = friend.version ?: return
    val loader = friend.loaderKind
    Tag(listOfNotNull(version, loader.label.takeIf { loader.isModded }).joinToString(" "), loaderColor(loader))
}

/** Пункты меню друга; вынесены отдельно, чтобы превью показало меню открытым. */
@Composable
internal fun FriendMenuItems(state: LauncherState, friend: Friend, close: () -> Unit, confirm: (FriendAction, String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    if (friend.joinable) {
        JuxMenuItem(tr("Присоединиться", "Join"), icon = Icons.Default.PlayArrow, enabled = !state.busy, onClick = {
            close()
            state.joinFriend(friend)
        })
    }
    friend.address?.let { address ->
        JuxMenuItem(tr("Копировать адрес сервера", "Copy server address"), icon = JuxIcons.Copy, onClick = {
            close()
            runCatching { clipboard.setText(AnnotatedString(address)) }
                .onFailure { state.fail(tr("Буфер обмена занят — адрес не скопирован", "Clipboard is busy — address not copied")) }
        })
    }
    JuxMenuItem(tr("Профиль на juxmc.ru", "Profile on juxmc.ru"), icon = JuxIcons.OpenInNew, onClick = {
        close()
        Shell.browse(friend.profile ?: profileUrl(friend.nick))
    })
    MenuDivider()
    JuxMenuItem(tr("Удалить из друзей", "Remove from friends"), icon = Icons.Default.Close, danger = true, onClick = {
        close()
        confirm(FriendAction.REMOVE, friend.nick)
    })
    JuxMenuItem(tr("Заблокировать", "Block"), icon = Icons.Default.Lock, danger = true, onClick = {
        close()
        confirm(FriendAction.BLOCK, friend.nick)
    })
}

// Запасной адрес, пока сайт не прислал свой: слаг профиля обычно — ник в нижнем регистре
internal fun profileUrl(nick: String): String = JuxSite.page("profile/${nick.lowercase()}/")

// --- «Присоединиться» ---

@Composable
private fun JoinButton(state: LauncherState, friend: Friend) {
    if (state.busy && state.joiningFriend == friend.nick) {
        JoinProgress(state, friend)
        return
    }
    var versions by remember { mutableStateOf(false) }
    ReportOpen(versions, state::trackMenu)
    val enabled = !state.busy
    val content = if (enabled) JuxColors.OnAccent else JuxColors.TextMuted
    Box {
        Row(
            Modifier.height(36.dp).clip(PillShape).background(if (enabled) JuxColors.Accent else JuxColors.Surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.fillMaxHeight().clickable(enabled = enabled) { state.joinFriend(friend) }.padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = content, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Присоединиться", "Join"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = content, maxLines = 1)
            }
            Box(Modifier.width(1.dp).height(20.dp).background(content.copy(alpha = 0.25f)))
            Box(
                Modifier.fillMaxHeight()
                    .background(if (versions) JuxColors.AccentPressed else Color.Transparent)
                    .clickable(enabled = enabled) { versions = true }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, tr("Другая версия", "Another version"), tint = content, modifier = Modifier.size(18.dp))
            }
        }
        JuxDropdownMenu(expanded = versions, onDismissRequest = { versions = false }) {
            JoinVersionItems(state, friend, close = { versions = false })
        }
    }
}

// Та же полоска прогресса, что у обновления в левой панели: кнопка сама показывает загрузку
@Composable
private fun JoinProgress(state: LauncherState, friend: Friend) {
    val fraction = state.progress?.fraction
    val label = state.busyEntry?.title ?: friend.version.orEmpty()
    Row(
        Modifier.width(206.dp).height(36.dp).clip(PillShape).background(JuxColors.Surface)
            .drawBehind { if (fraction != null) drawRect(JuxColors.Accent.copy(alpha = 0.35f), size = Size(size.width * fraction, size.height)) }
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (fraction != null) tr("Скачиваю $label · ${(fraction * 100).toInt()}%", "Downloading $label · ${(fraction * 100).toInt()}%")
            else tr("Запускаю $label", "Starting $label"),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = JuxColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = state::cancel, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, tr("Отменить", "Cancel"), tint = JuxColors.TextMuted, modifier = Modifier.size(16.dp))
        }
    }
}

/** Меню у стрелки «Присоединиться»: версии, которые пускает сервер друга. */
@Composable
internal fun JoinVersionItems(state: LauncherState, friend: Friend, close: () -> Unit) {
    val server = state.friendServer(friend)
    val current = state.friendJoinKey(friend)
    rangeText(server)?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = JuxColors.TextMuted,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
    }
    state.friendJoinMenu(friend).forEachIndexed { index, option ->
        VersionItem(option, selected = option.entry?.key == current) {
            close()
            val entry = option.entry
            val link = friend.sharedPack
            when {
                entry != null -> state.joinFriendWith(friend, entry)
                index == 0 && link != null -> state.showFriendPack(friend, link)
            }
        }
    }
    MenuDivider()
    JuxMenuItem(tr("Другая версия…", "Another version…"), icon = JuxIcons.Layers, onClick = {
        close()
        state.showFriendVersions(friend)
    })
}

internal fun rangeText(server: LaunchLink.Join): String? {
    val max = server.version ?: return null
    val name = server.title
    return server.minVersion?.let { tr("$name пускает $it – $max", "$name accepts $it – $max") } ?: tr("$name на $max", "$name runs $max")
}

@Composable
private fun VersionItem(option: JoinOption, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val alpha = if (option.fits) 1f else 0.45f
    Row(
        Modifier.width(390.dp).height(42.dp)
            .background(if (hovered && option.fits) JuxColors.Outline.copy(alpha = 0.5f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(enabled = option.fits, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (option.installed && option.fits) JuxColors.Accent else Color.Transparent))
        Text(
            option.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = (if (selected) JuxColors.Accent else JuxColors.Text).copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (option.loader != LoaderKind.VANILLA) Tag(option.loader.label, loaderColor(option.loader))
        Text(option.note, style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted.copy(alpha = alpha), maxLines = 1)
        if (selected) Icon(Icons.Default.Check, null, tint = JuxColors.Accent, modifier = Modifier.size(16.dp))
    }
}

// --- Мелочи ---

@Composable
private fun Card(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(JuxDimens.CornerCard)).background(JuxColors.SurfaceHigh).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** Невысокая кнопка в строке друга и в панели настроек. */
@Composable
internal fun SmallButton(text: String, primary: Boolean = false, icon: ImageVector? = null, onPanel: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(PillShape)
            .background(
                when {
                    primary -> JuxColors.Accent
                    onPanel -> JuxColors.SurfaceHigh
                    else -> JuxColors.Surface
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (primary) JuxColors.OnAccent else JuxColors.Text
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

/** Голова друга по адресу из ответа сайта; картинка кэшируется на диске вместе с иконками модов. */
@Composable
internal fun FriendHead(url: String?, nick: String, size: Dp, dim: Boolean = false) {
    val bitmap by produceState(url?.let(ModIcons::cached), url) {
        if (value == null && url != null) value = ModIcons.load(url)
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(JuxColors.SurfaceHigh), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, nick, filterQuality = FilterQuality.None, alpha = if (dim) 0.45f else 1f, modifier = Modifier.fillMaxSize())
        } else {
            Text(nick.take(1).uppercase(), color = JuxColors.Accent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        }
    }
}

@Composable
private fun BuiltInHead(skin: String, size: Dp) {
    val bitmap by produceState(SkinHeads.cachedBuiltIn(skin), skin) {
        if (value == null) value = SkinHeads.builtIn(skin)
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(JuxColors.SurfaceHigh)) {
        bitmap?.let { Image(it, null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Dot(color: Color, size: Dp = 12.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size / 2).clip(CircleShape).background(color))
    }
}

private fun statusColor(state: FriendState): Color = when (state) {
    FriendState.SERVER -> JuxColors.Success
    FriendState.SINGLE -> JuxColors.Info
    FriendState.LAUNCHER, FriendState.ONLINE -> JuxColors.Accent
    FriendState.OFFLINE -> JuxColors.TextMuted
}
