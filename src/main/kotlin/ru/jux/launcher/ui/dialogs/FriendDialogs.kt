package ru.jux.launcher.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.tr
import ru.jux.launcher.friends.Friend
import ru.jux.launcher.packs.PackLinks
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.friendJoinKey
import ru.jux.launcher.ui.friendJoinOptions
import ru.jux.launcher.ui.friendServer
import ru.jux.launcher.ui.components.ButtonStyle
import ru.jux.launcher.ui.components.JuxButton
import ru.jux.launcher.ui.components.JuxDialog
import ru.jux.launcher.ui.components.JuxIcons
import ru.jux.launcher.ui.components.Tag
import ru.jux.launcher.ui.components.loaderColor
import ru.jux.launcher.ui.theme.JuxColors
import ru.jux.launcher.ui.theme.JuxDimens

@Composable
internal fun FriendLinkDialog(state: LauncherState, modal: Modal.FriendLink) {
    val start = modal.start
    val failed = modal.failure != null
    JuxDialog(
        title = tr("Вход через juxmc.ru", "Sign in with juxmc.ru"),
        subtitle = tr("Браузер открыл ", "Your browser opened ") + start.url.substringAfter("://").substringBefore('?').trimEnd('/'),
        onDismiss = state::cancelFriendLink,
        width = 480.dp,
        actions = {
            JuxButton(tr("Отмена", "Cancel"), onClick = state::cancelFriendLink)
            if (failed) {
                JuxButton(tr("Начать заново", "Start over"), style = ButtonStyle.PRIMARY, onClick = {
                    state.cancelFriendLink()
                    state.signInFriends()
                })
            } else {
                JuxButton(tr("Открыть сайт снова", "Open the site again"), icon = JuxIcons.OpenInNew, onClick = { Shell.browse(start.url) })
            }
        },
    ) {
        Text(
            tr("Проверь, что на сайте тот же код, и нажми «Привязать»", "Check that the site shows the same code and press “Link”"),
            style = MaterialTheme.typography.bodyMedium,
            color = JuxColors.Text,
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(JuxDimens.CornerCard)).background(JuxColors.SurfaceHigh).padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                displayCode(start.code),
                color = if (failed) JuxColors.TextMuted else JuxColors.Accent,
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        if (failed) {
            Hint(modal.failure.orEmpty(), JuxColors.Danger)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = JuxColors.Accent, strokeWidth = 2.dp, trackColor = JuxColors.SurfaceHigh)
                Spacer(Modifier.width(10.dp))
                Text(
                    tr("Жду подтверждения на сайте · код живёт ${start.expiresIn / 60} минут", "Waiting for you to confirm on the site · the code lives ${start.expiresIn / 60} minutes"),
                    style = MaterialTheme.typography.bodySmall,
                    color = JuxColors.TextSoft,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Hint(
            tr(
                "Лаунчер получит список друзей и будет показывать им, где ты играешь. Почта и пароль в лаунчер не попадают.",
                "The launcher gets your friend list and shows your friends where you play. Your email and password never reach the launcher.",
            ),
        )
    }
}

/** Код с сайта показываем как XXX-XXX, даже если он пришёл без дефиса. */
internal fun displayCode(code: String): String {
    val clean = code.filter { it.isLetterOrDigit() }.uppercase()
    return if (clean.length == 6) "${clean.take(3)}-${clean.drop(3)}" else code
}

@Composable
internal fun FriendVersionsDialog(state: LauncherState, friend: Friend) {
    val server = state.friendServer(friend)
    val options = remember(friend, state.versions, state.packs, state.installed, state.profiles) { state.friendJoinOptions(friend) }
    val initial = state.friendJoinKey(friend)
    var choice by remember(friend) { mutableStateOf(options.firstOrNull { it.entry?.key == initial }?.entry ?: options.firstOrNull()?.entry) }
    val close = { state.modal = null }
    val title = friend.address.orEmpty()

    JuxDialog(
        title = tr("Версия для $title", "Version for $title"),
        subtitle = listOfNotNull(
            server.version?.let { max -> server.minVersion?.let { tr("Сервер пускает $it – $max", "The server accepts $it – $max") } ?: tr("Сервер на $max", "The server runs $max") },
            friend.version?.let { tr("${friend.nick} на $it", "${friend.nick} is on $it") },
        ).joinToString(" · ").ifEmpty { null },
        onDismiss = close,
        width = 520.dp,
        actions = {
            JuxButton(tr("Отмена", "Cancel"), onClick = close)
            JuxButton(
                tr("Играть", "Play"),
                icon = Icons.Default.PlayArrow,
                style = ButtonStyle.PRIMARY,
                enabled = choice != null && !state.busy,
                onClick = { choice?.let { state.joinFriendWith(friend, it) } },
            )
        },
    ) {
        Hint(
            if (server.version != null) tr("Только версии, которые пустит сервер", "Only versions the server will accept")
            else tr("Сервер не сообщил, какие версии пускает", "The server didn't say which versions it accepts"),
        )
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                val entry = option.entry ?: return@forEach
                EntryChoice(state, entry, entry.key == choice?.key, note = option.note) { choice = entry }
            }
        }
        Spacer(Modifier.height(14.dp))
        Hint(
            if (state.busy) busyText(state)
            else tr(
                "Запомню выбор для $title: дальше «Присоединиться» будет сразу заходить с этой версией",
                "I'll remember this for $title: from now on “Join” goes straight in with this version",
            ),
            if (state.busy) JuxColors.Warning else JuxColors.TextMuted,
        )
    }
}

@Composable
internal fun FriendPackDialog(state: LauncherState, modal: Modal.FriendPack) {
    val friend = modal.friend
    val link = modal.link
    val plan = modal.plan
    val close = { state.modal = null }
    val supported = link.loader in state.loaderSupport.loadersFor(link.version)

    JuxDialog(
        title = tr("Играть с ${friend.nick}", "Play with ${friend.nick}"),
        subtitle = friend.address.orEmpty(),
        onDismiss = close,
        width = 520.dp,
        actions = {
            JuxButton(tr("Отмена", "Cancel"), onClick = close)
            JuxButton(
                tr("Скачать и играть", "Download and play"),
                icon = JuxIcons.Download,
                style = ButtonStyle.PRIMARY,
                enabled = plan != null && supported && !state.busy,
                onClick = { plan?.let { state.joinFriendPack(friend, link, it) } },
            )
        },
    ) {
        Text(tr("${friend.nick} играет на своей сборке", "${friend.nick} plays on their own pack"), style = MaterialTheme.typography.bodyMedium, color = JuxColors.Text)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(JuxDimens.CornerCard)).background(JuxColors.SurfaceHigh).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(JuxDimens.CornerMedium)).background(JuxColors.Surface), contentAlignment = Alignment.Center) {
                Icon(JuxIcons.Layers, null, tint = JuxColors.Accent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(link.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = JuxColors.Text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    Tag("${link.version} ${link.loader.label}", loaderColor(link.loader))
                }
                Spacer(Modifier.height(3.dp))
                // Версия и загрузчик уже на значке рядом с названием
                val contents = PackLinks.summary(link).split(" · ").drop(1)
                val fromModrinth = tr("всё с Modrinth", "all from Modrinth").takeIf { plan != null && plan.missing == 0 }
                Text((contents + listOfNotNull(fromModrinth)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = JuxColors.TextMuted)
            }
        }
        Spacer(Modifier.height(14.dp))
        when {
            modal.error != null -> Hint(modal.error, JuxColors.Danger)
            plan == null -> Hint(tr("Ищу файлы на Modrinth…", "Looking for the files on Modrinth…"))
            !supported -> Hint(tr("${link.loader.label} не поддерживает Minecraft ${link.version}", "${link.loader.label} doesn't support Minecraft ${link.version}"), JuxColors.Danger)
            state.busy -> Hint(busyText(state), JuxColors.Warning)
            else -> {
                if (plan.missing > 0) {
                    Hint(
                        tr("Не нашлось на Modrinth: ${plan.missing} — их в сборке не будет", "Not found on Modrinth: ${plan.missing} — they won't be in the pack"),
                        JuxColors.Warning,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Hint(
                    tr(
                        "Такой сборки у тебя нет: скачаем такую же, как у ${friend.nick}, и сразу зайдём на сервер. Спрашиваю один раз, дальше без этого окна. Миры и настройки друга не копируются",
                        "You don't have this pack: we'll download the same one ${friend.nick} has and join the server right away. I only ask once. Your friend's worlds and settings are not copied",
                    ),
                )
            }
        }
    }
}
