package ru.jux.launcher.dev

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import ru.jux.launcher.core.DEFAULT_ACCENT
import ru.jux.launcher.core.Backdrop
import ru.jux.launcher.core.NoticeAction
import ru.jux.launcher.core.PreloadResult
import ru.jux.launcher.core.Settings
import ru.jux.launcher.core.Storage
import ru.jux.launcher.core.ThemeMode
import ru.jux.launcher.instance.Worlds
import ru.jux.launcher.instance.writeWorld
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.packs.Modpacks
import ru.jux.launcher.servers.Nbt
import ru.jux.launcher.ui.App
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.PackMode
import ru.jux.launcher.ui.Screen
import ru.jux.launcher.ui.dialogs.ColorPickerDialog
import ru.jux.launcher.ui.theme.JuxTheme

// Экраны новых фич: темы, OptiFine, миры, свои сборки и разбор вылетов
internal fun renderNewFeatures(preloaded: PreloadResult, render: (String, Int, Int, @Composable () -> Unit) -> Unit) {
    val app = { state: LauncherState -> @Composable { JuxTheme { App(state, onGameStarted = {}) } } }

    val state = LauncherState(CoroutineScope(Dispatchers.Unconfined), preloaded)
    state.screen = Screen.SETTINGS
    render("settings-tall", 1040, 1500, app(state))

    val optiFine = preloaded.loaderSupport.optifine.firstOrNull { it == "1.21.11" } ?: preloaded.loaderSupport.optifine.firstOrNull()
    if (optiFine != null) {
        state.screen = Screen.HOME
        val entry = state.entryFor(optiFine, LoaderKind.OPTIFINE)
        state.selectEntry(entry)
        state.searchQuery = optiFine
        render("home-optifine", 1040, 660, app(state))
        state.searchQuery = ""
    }

    // Тема из четырёх, акцент и фон — свои цвета из палитры
    val looks = listOf(
        Triple(ThemeMode.LIGHT, 0x2F6FD0, null),
        Triple(ThemeMode.MIDNIGHT, 0xB79CFF, 0x2A1B47),
        Triple(ThemeMode.OLED, 0x6BE38E, null),
        Triple(ThemeMode.DARK, 0xFF4FD8, 0x1E2A3A),
    )
    state.screen = Screen.HOME
    for ((mode, accent, backdrop) in looks) {
        Settings.update {
            it.copy(
                theme = mode,
                accentColor = accent,
                backdrop = if (backdrop != null) Backdrop.COLOR else Backdrop.NONE,
                backdropColor = backdrop ?: it.backdropColor,
            )
        }
        render("theme-${mode.name.lowercase()}", 1040, 660, app(state))
    }
    Settings.update { it.copy(theme = ThemeMode.LIGHT, accentColor = 0x2F6FD0, backdrop = Backdrop.NONE) }
    state.screen = Screen.SETTINGS
    render("theme-light-settings", 1040, 900, app(state))
    Settings.update { it.copy(theme = ThemeMode.DARK, accentColor = 0xFF4FD8, backdrop = Backdrop.NONE) }
    render("accent-custom-picker", 1040, 760) {
        JuxTheme {
            App(state, onGameStarted = {})
            ColorPickerDialog("Акцент", 0xFF4FD8, onDismiss = {}, onPick = {})
        }
    }
    Settings.update { it.copy(theme = ThemeMode.DARK, accentColor = DEFAULT_ACCENT, backdrop = Backdrop.NONE) }

    val custom = Modpacks.create("Выживание с друзьями", "1.21.11", LoaderKind.FABRIC)
    val packDir = Modpacks.dirOf(custom)
    writeWorld(packDir, "Survival", "Выживание с друзьями", "1.21.11", mapOf("GameType" to Nbt.IntTag(0), "LastPlayed" to Nbt.LongTag(System.currentTimeMillis() - 3_600_000)))
    writeWorld(packDir, "Creative", "Город из кварца", "1.21.11", mapOf("GameType" to Nbt.IntTag(1), "LastPlayed" to Nbt.LongTag(System.currentTimeMillis() - 86_400_000)))
    writeWorld(packDir, "Hard", "Хардкор №3", "1.21.4", mapOf("GameType" to Nbt.IntTag(0), "hardcore" to Nbt.ByteTag(1)))
    Worlds.backup(packDir, "Survival")
    Worlds.autoBackup(packDir)
    writeWorld(packDir, "Old Island", "Старый остров", "1.21.4")
    Worlds.backup(packDir, "Old Island")
    Storage.deleteTree(Worlds.savesDir(packDir).resolve("Old Island"))

    val withCustom = LauncherState(CoroutineScope(Dispatchers.Unconfined), preloaded.copy(packs = Modpacks.list()))
    val customEntry = withCustom.entryFor(withCustom.packs.first { it.id == custom.id })
    withCustom.selectEntry(customEntry)
    render("home-custom-pack", 1040, 660, app(withCustom))

    withCustom.modal = Modal.WorldList(customEntry)
    render("dialog-worlds", 1040, 760, app(withCustom))
    withCustom.modal = Modal.PackEditor(PackMode.Create)
    render("dialog-pack-create", 1040, 660, app(withCustom))
    withCustom.modal = Modal.PackEditor(PackMode.Copy(customEntry))
    render("dialog-pack-copy", 1040, 660, app(withCustom))
    withCustom.modal = Modal.PackEditor(PackMode.Edit(customEntry.pack!!))
    render("dialog-pack-edit", 1040, 660, app(withCustom))
    withCustom.modal = null

    withCustom.fail(
        "Мод «Sodium Extra» не работает без «sodium». Его можно поставить с Modrinth одной кнопкой.",
        customEntry,
        "Игра закрылась с ошибкой",
        listOf(NoticeAction.INSTALL_MOD, NoticeAction.LOGS),
        actionTarget = "sodium",
    )
    render("home-crash-fix", 1040, 660, app(withCustom))
    withCustom.fail("Игре не хватило памяти.", customEntry, "Игра закрылась с ошибкой", listOf(NoticeAction.MORE_MEMORY, NoticeAction.LOGS))
    withCustom.openNotices()
    render("notices-crash-fix", 1040, 660, app(withCustom))
}
