package ru.jux.launcher.online

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Settings
import ru.jux.launcher.net.Http
import java.util.UUID

data class OnlineCount(val online: Int, val playing: Int)

object OnlineCounter {

    private const val DEFAULT_URL = "https://juxmc.ru/api/v1/launcher/online/"
    private const val INTERVAL_MILLIS = 60_000L
    private const val STALE_MILLIS = 5 * 60_000L

    private val url: String = System.getenv("JUX_ONLINE_URL")?.takeIf { it.startsWith("http") } ?: DEFAULT_URL

    private val _count = MutableStateFlow<OnlineCount?>(null)
    val count: StateFlow<OnlineCount?> = _count.asStateFlow()

    private val wake = MutableStateFlow(0)
    @Volatile
    private var playing = false
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job != null) return
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var lastSuccess = 0L
            var warned = false
            while (isActive) {
                val seen = wake.value
                runCatching { send() }
                    .onSuccess {
                        _count.value = it
                        lastSuccess = System.currentTimeMillis()
                        warned = false
                    }
                    .onFailure {
                        if (!warned) Log.info("online counter unavailable: ${it.message}")
                        warned = true
                        if (System.currentTimeMillis() - lastSuccess > STALE_MILLIS) _count.value = null
                    }
                withTimeoutOrNull(INTERVAL_MILLIS) { wake.first { it != seen } }
            }
        }
    }

    internal fun show(value: OnlineCount?) {
        _count.value = value
    }

    fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        wake.value++
    }

    private fun send(): OnlineCount {
        val body = buildJsonObject {
            put("id", installId())
            put("playing", playing)
        }
        return parse(Json.parseToJsonElement(Http.postJson(url, body.toString())).jsonObject)
    }

    internal fun parse(json: JsonObject): OnlineCount {
        val online = (json["online"] as? JsonPrimitive)?.intOrNull ?: error("no online count")
        val playing = (json["playing"] as? JsonPrimitive)?.intOrNull ?: 0
        return OnlineCount(online.coerceAtLeast(1), playing.coerceIn(0, online))
    }

    internal fun installId(): String {
        Settings.current.installId?.takeIf(::isValidId)?.let { return it }
        val fresh = newId()
        Settings.update { it.copy(installId = fresh) }
        return fresh
    }

    internal fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    internal fun isValidId(id: String): Boolean = id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' }
}
