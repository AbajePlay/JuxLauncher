package ru.jux.launcher.discord

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ru.jux.launcher.core.Log
import java.io.IOException

sealed interface Presence {
    data object Launcher : Presence

    data class Playing(
        val versionId: String,
        val loaderLabel: String?,
        val server: String?,
        val mods: Int,
        val startedAt: Long,
        val pack: String? = null,
        val serverIcon: String? = null,
    ) : Presence
}

object DiscordPresence {

    const val APP_ID = "1553784416487346326"

    const val DOWNLOAD_LABEL = "Скачать"
    const val PAGE_URL = "https://juxmc.ru/launcher/"
    const val SITE_URL = "https://juxmc.ru/"
    private const val ART = "https://raw.githubusercontent.com/AbajePlay/JuxLauncher/main/branding/presence"
    const val LOGO = "$ART/logo.png"

    fun serverIcon(address: String): String = "https://api.mcsrvstat.us/icon/$address"

    private const val RETRY_MILLIS = 15_000L

    private val desired = MutableStateFlow<Presence?>(null)
    private val wake = MutableStateFlow(0)
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job != null || APP_ID == "0") return
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { loop() }
    }

    fun show(presence: Presence) {
        desired.value = presence
        wake.value++
    }

    private suspend fun CoroutineScope.loop() {
        var ipc: DiscordIpc? = null
        var shown: Presence? = null
        var warned = false
        while (isActive) {
            val seen = wake.value
            val target = desired.value
            try {
                when {
                    target == null && ipc != null -> {
                        ipc.close()
                        ipc = null
                        shown = null
                    }
                    target != null && target != shown -> {
                        val connection = ipc ?: DiscordIpc.connect(APP_ID)
                        ipc = connection
                        connection.setActivity(activity(target))
                        shown = target
                        warned = false
                    }
                }
            } catch (e: IOException) {
                if (!warned) Log.info("discord presence unavailable: ${e.message}")
                warned = true
                ipc?.close()
                ipc = null
                shown = null
            }
            withTimeoutOrNull(RETRY_MILLIS) { wake.first { it != seen } }
        }
        ipc?.close()
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    internal fun activity(presence: Presence): JsonObject = buildJsonObject {
        when (presence) {
            Presence.Launcher -> {
                put("details", "В лаунчере")
                put("state", "Выбирает, во что поиграть")
                putJsonObject("assets") {
                    put("large_image", LOGO)
                    put("large_text", "JuxLauncher — лаунчер Minecraft")
                    put("large_url", SITE_URL)
                }
            }
            is Presence.Playing -> {
                put("details", detailsOf(presence))
                put("state", stateOf(presence))
                putJsonObject("timestamps") { put("start", presence.startedAt / 1000) }
                putJsonObject("assets") {
                    put("large_image", LOGO)
                    put("large_text", "Minecraft ${presence.versionId} через JuxLauncher")
                    put("large_url", SITE_URL)
                    if (presence.server != null && presence.serverIcon != null) {
                        put("small_image", presence.serverIcon)
                        put("small_text", presence.server)
                    }
                }
            }
        }
        putJsonArray("buttons") {
            addJsonObject {
                put("label", DOWNLOAD_LABEL)
                put("url", PAGE_URL)
            }
        }
    }

    private fun detailsOf(playing: Presence.Playing): String =
        listOfNotNull(playing.pack, "Minecraft ${playing.versionId}", playing.loaderLabel.takeIf { playing.pack == null })
            .joinToString(" · ")
            .take(128)

    private fun stateOf(playing: Presence.Playing): String = when {
        playing.server != null -> "Сервер: ${playing.server}".take(128)
        playing.mods > 0 -> "${playing.mods} ${modsWord(playing.mods)}"
        playing.loaderLabel != null -> "Без модов"
        else -> "Ванильная игра"
    }

    internal fun modsWord(count: Int): String {
        val tens = count % 100
        val ones = count % 10
        return when {
            tens in 11..14 -> "модов"
            ones == 1 -> "мод"
            ones in 2..4 -> "мода"
            else -> "модов"
        }
    }
}
