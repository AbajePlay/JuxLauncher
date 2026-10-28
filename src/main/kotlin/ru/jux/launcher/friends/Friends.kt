package ru.jux.launcher.friends

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.jux.launcher.auth.TokenStore
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.PrettyJson
import ru.jux.launcher.core.Shell
import ru.jux.launcher.core.writeAtomically
import ru.jux.launcher.launch.ArgumentBuilder
import ru.jux.launcher.online.OnlineCounter
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Привязанный аккаунт juxmc.ru. Токен лежит на диске зашифрованным так же, как токены Microsoft. */
@Serializable
data class FriendsAccount(
    val nick: String,
    val token: String,
    val linkedAt: Long = 0,
)

object Friends {

    private const val INTERVAL_MILLIS = 60_000L
    // Игра, сервер и действие могут прийти почти разом: одного запроса на всех хватит, а лимит сайта — 6 в минуту
    private const val MIN_GAP_MILLIS = 3_000L

    internal var api = FriendsApi()

    private val _account = MutableStateFlow<FriendsAccount?>(null)
    val account: StateFlow<FriendsAccount?> = _account.asStateFlow()

    private val _snapshot = MutableStateFlow<FriendsSnapshot?>(null)
    val snapshot: StateFlow<FriendsSnapshot?> = _snapshot.asStateFlow()

    /** Сайт не ответил на последний сигнал, а списка ещё нет. */
    private val _unreachable = MutableStateFlow(false)
    val unreachable: StateFlow<Boolean> = _unreachable.asStateFlow()

    private val presence = MutableStateFlow(FriendPresence.LAUNCHER)
    private val wake = MutableStateFlow(0)
    private var job: Job? = null

    @Volatile
    private var token: String? = null

    fun load() {
        val file = Paths.friendsFile
        if (!file.exists()) return
        runCatching {
            val stored = Json.decodeFromString<FriendsAccount>(file.readText())
            val secret = TokenStore.reveal(stored.token)
            if (secret.isBlank()) return
            token = secret
            _account.value = stored
        }.onFailure { Log.warn("friends.json unreadable, sign-in required", it) }
    }

    @Synchronized
    fun start() {
        if (job != null) return
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var lastSent = 0L
            var warned = false
            while (isActive) {
                val seen = wake.value
                val current = token
                if (current != null) {
                    val wait = MIN_GAP_MILLIS - (System.currentTimeMillis() - lastSent)
                    if (wait > 0) delay(wait)
                    lastSent = System.currentTimeMillis()
                    runCatching { api.heartbeat(current, presence.value) }
                        .onSuccess {
                            if (token == current) _snapshot.value = it
                            _unreachable.value = false
                            warned = false
                        }
                        .onFailure {
                            if (it is FriendsException && it.unauthorized) {
                                Log.info("juxmc.ru token was revoked, signing out of friends")
                                forget(current)
                            } else {
                                if (!warned) Log.info("friends heartbeat failed: ${it.message}")
                                warned = true
                                _unreachable.value = _snapshot.value == null
                            }
                        }
                }
                withTimeoutOrNull(INTERVAL_MILLIS) { wake.first { it != seen } }
            }
        }
    }

    /** Для превью: показать готовый список без сети. */
    internal fun show(account: FriendsAccount?, snapshot: FriendsSnapshot?) {
        _account.value = account
        _snapshot.value = snapshot
    }

    fun setPresence(value: FriendPresence) {
        if (presence.value == value) return
        presence.value = value
        refresh()
    }

    /** Список обновится сразу, а не через минуту. */
    fun refresh() {
        wake.value++
    }

    suspend fun startLink(): LinkStart = withContext(Dispatchers.IO) {
        api.start(OnlineCounter.installId(), ArgumentBuilder.LAUNCHER_VERSION, osName())
    }

    suspend fun poll(start: LinkStart): LinkPoll = withContext(Dispatchers.IO) { api.poll(start.poll) }

    suspend fun adopt(linked: LinkPoll.Linked) = withContext(Dispatchers.IO) {
        val stored = FriendsAccount(linked.nick, TokenStore.protect(linked.token), System.currentTimeMillis())
        runCatching { Paths.friendsFile.writeAtomically(PrettyJson.encodeToString(stored)) }
            .onFailure { Log.error("could not save friends.json", it) }
        token = linked.token
        _snapshot.value = null
        _unreachable.value = false
        _account.value = stored
        Log.info("linked to juxmc.ru as ${linked.nick}")
        refresh()
    }

    suspend fun act(action: FriendAction, nick: String) = authorized { api.act(it, action, nick) }.also { refresh() }

    suspend fun changeSettings(showWhere: Boolean? = null, showOnline: Boolean? = null, acceptRequests: Boolean? = null) {
        val settings = authorized { api.settings(it, showWhere, showOnline, acceptRequests) }
        _snapshot.value = _snapshot.value?.copy(settings = settings)
    }

    /** Выйти: токен отзывается на сайте, а дома забывается в любом случае. */
    suspend fun unlink() {
        val current = token ?: return
        withContext(Dispatchers.IO) {
            runCatching { api.unlink(current) }.onFailure { Log.info("unlink on juxmc.ru failed: ${it.message}") }
        }
        forget(current)
    }

    private suspend fun <T> authorized(block: (String) -> T): T = withContext(Dispatchers.IO) {
        val current = token ?: throw FriendsException(FriendsException.UNAUTHORIZED, 401)
        try {
            block(current)
        } catch (e: FriendsException) {
            if (e.unauthorized) forget(current)
            throw e
        }
    }

    private fun forget(expected: String) {
        synchronized(this) {
            if (token != expected) return
            token = null
        }
        runCatching { Files.deleteIfExists(Paths.friendsFile) }.onFailure { Log.warn("could not delete friends.json: ${it.message}") }
        _account.value = null
        _snapshot.value = null
        _unreachable.value = false
    }

    private fun osName(): String = if (Shell.isWindows) "Windows" else System.getProperty("os.name").orEmpty().take(32)
}
