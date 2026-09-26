package ru.jux.launcher.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.jux.launcher.core.Json
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.PrettyJson
import ru.jux.launcher.core.writeAtomically
import kotlin.io.path.exists
import kotlin.io.path.readText

object AccountManager {

    private val NICKNAME = Regex("^[A-Za-z0-9_]{3,16}$")

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _selected = MutableStateFlow<Account?>(null)
    val selected: StateFlow<Account?> = _selected.asStateFlow()

    fun load() {
        val file = Paths.accountsFile
        if (!file.exists()) return
        runCatching {
            val store = Json.decodeFromString<AccountStore>(file.readText())
            _accounts.value = store.accounts
            _selected.value = store.accounts.firstOrNull { it.uuid == store.selectedUuid }
                ?: store.accounts.firstOrNull()
            Log.info("loaded ${store.accounts.size} account(s)")
        }.onFailure { Log.warn("accounts.json unreadable, starting empty", it) }
    }

    private fun save() {
        runCatching {
            val store = AccountStore(_accounts.value, _selected.value?.uuid)
            Paths.accountsFile.writeAtomically(PrettyJson.encodeToString(store))
        }.onFailure { Log.error("could not save accounts", it) }
    }

    fun select(uuid: String) {
        _selected.value = _accounts.value.firstOrNull { it.uuid == uuid } ?: return
        save()
    }

    fun remove(uuid: String) {
        _accounts.value = _accounts.value.filterNot { it.uuid == uuid }
        if (_selected.value?.uuid == uuid) _selected.value = _accounts.value.firstOrNull()
        save()
    }

    fun upsert(account: Account) {
        _accounts.value = _accounts.value.filterNot { it.uuid == account.uuid } + account
        _selected.value = account
        save()
    }

    suspend fun signInMicrosoft(onStage: (String) -> Unit = {}): Account {
        val account = MicrosoftAuth().signIn(onStage)
        upsert(account)
        return account
    }

    fun addOffline(name: String): Account {
        val trimmed = name.trim()
        if (!NICKNAME.matches(trimmed)) {
            throw AuthException("Ник: 3-16 символов, только латиница, цифры и подчёркивание")
        }
        val account = Account.offline(trimmed)
        upsert(account)
        return account
    }

    suspend fun prepareForLaunch(account: Account, onStage: (String) -> Unit = {}): Account {
        if (account.isOffline || !account.isExpired) return account

        val refreshToken = TokenStore.reveal(account.refreshToken)
        if (refreshToken.isBlank()) {
            throw AuthException("Сессия ${account.name} истекла — войдите в аккаунт заново")
        }

        val renewed = MicrosoftAuth().refresh(refreshToken, onStage)
        upsert(renewed)
        return renewed
    }
}
