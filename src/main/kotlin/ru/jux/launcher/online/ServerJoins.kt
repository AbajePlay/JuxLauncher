package ru.jux.launcher.online

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.jux.launcher.core.Log
import ru.jux.launcher.net.Http
import ru.jux.launcher.servers.ServerAddress

object ServerJoins {

    private const val TIMEOUT_SECONDS = 5L

    private val URL = JuxSite.api("launcher/join/")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun report(address: String) {
        report(address, URL)
    }

    internal fun report(address: String, url: String): Job? {
        val body = body(address) ?: return null
        return scope.launch {
            runCatching { Http.postJson(url, body, TIMEOUT_SECONDS) }
                .onFailure { Log.debug("join report for $address not sent: ${it.message}") }
        }
    }

    internal fun body(address: String): String? {
        val parsed = ServerAddress.parse(address) ?: return null
        return buildJsonObject { put("address", parsed.toString().lowercase()) }.toString()
    }
}
