package ru.jux.launcher.auth

import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

object AuthConfig {

    const val PLACEHOLDER = "SET_YOUR_AZURE_CLIENT_ID"

    private const val BUILT_IN = PLACEHOLDER

    val clientId: String by lazy {
        sequenceOf(
            System.getenv("JUX_MS_CLIENT_ID"),
            readFromFile(),
            BUILT_IN,
        ).firstOrNull { !it.isNullOrBlank() && it != PLACEHOLDER } ?: PLACEHOLDER
    }

    val isConfigured: Boolean get() = clientId != PLACEHOLDER

    private fun readFromFile(): String? = runCatching {
        val file = Paths.root.resolve("client_id.txt")
        if (!file.exists()) return@runCatching null
        file.readText().trim().takeIf { it.isNotBlank() }
    }.onFailure { Log.debug("client_id.txt unreadable: ${it.message}") }.getOrNull()

    val SETUP_HINT: String
        get() = "Лицензионный вход не настроен: нужен client_id приложения Azure.\n" +
            "portal.azure.com -> Microsoft Entra ID -> App registrations -> New registration.\n" +
            "Тип аккаунтов: Personal Microsoft accounts. Платформа: Mobile and desktop applications, " +
            "redirect URI http://localhost.\n" +
            "Полученный Application (client) ID положи в ${Paths.root.resolve("client_id.txt")}"
}
