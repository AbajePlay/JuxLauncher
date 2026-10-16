package ru.jux.launcher.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.jux.launcher.core.PreloadResult
import ru.jux.launcher.core.Settings
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.link.LinkContent
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.meta.LoaderSupport
import ru.jux.launcher.meta.ManifestVersion
import ru.jux.launcher.meta.VersionManifest
import ru.jux.launcher.packs.Modpack

class LauncherStateTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val pack = Modpack(
        id = "fabulously-optimized",
        title = "Fabulously Optimized",
        version = "1",
        gameVersion = "26.2",
        loader = LoaderKind.FABRIC,
        loaderVersion = "0.19.5",
        projectId = "1KVo5zza",
        versionId = "ssWn7YI0",
    )

    private fun preload(profiles: Set<String>, packs: List<Modpack> = emptyList()) = PreloadResult(
        manifest = VersionManifest(
            latest = VersionManifest.Latest(release = "26.3"),
            versions = listOf(
                ManifestVersion(id = "26.3", url = "https://example.invalid/26.3.json"),
                ManifestVersion(id = "26.2", url = "https://example.invalid/26.2.json"),
            ),
        ),
        installed = mapOf("26.3" to 1L),
        profiles = profiles,
        loaderSupport = LoaderSupport(fabric = setOf("26.3")),
        packs = packs,
    )

    private fun rememberLaunch(version: String, loader: String) =
        Settings.update { it.copy(lastVersionId = version, lastLoader = loader) }

    @Test
    fun `the loader of the last launch is selected again`() {
        rememberLaunch("26.3", LoaderKind.FABRIC.name)
        val state = LauncherState(scope, preload(setOf("26.3", "fabric-loader-0.16.14-26.3")))

        assertEquals("26.3", state.selectedVersionId)
        assertEquals(LoaderKind.FABRIC, state.selectedLoader)
    }

    @Test
    fun `a removed loader profile falls back to the same version without it`() {
        rememberLaunch("26.3", LoaderKind.FABRIC.name)
        val state = LauncherState(scope, preload(setOf("26.3")))

        assertEquals("26.3", state.selectedVersionId)
        assertEquals(LoaderKind.VANILLA, state.selectedLoader)
    }

    @Test
    fun `an unknown loader name is treated as vanilla`() {
        rememberLaunch("26.3", "SOME_LOADER_FROM_THE_FUTURE")
        val state = LauncherState(scope, preload(setOf("26.3", "fabric-loader-0.16.14-26.3")))

        assertEquals(LoaderKind.VANILLA, state.selectedLoader)
    }

    @Test
    fun `the arrow keys walk the rows that can be seen`() {
        val state = LauncherState(scope, preload(setOf("26.3")))
        state.selectEntry(state.entryFor("26.3", LoaderKind.VANILLA))

        state.moveSelection(+1)
        assertEquals(LoaderKind.FABRIC, state.selectedLoader)
        state.moveSelection(+1)
        assertEquals("26.3" to LoaderKind.FABRIC, state.selectedVersionId to state.selectedLoader)
        state.moveSelection(-1)
        assertEquals(LoaderKind.VANILLA, state.selectedLoader)
    }

    @Test
    fun `a search opens every group that matches`() {
        val state = LauncherState(scope, preload(setOf("26.3")))
        assertEquals(listOf(true, false), state.groups().map(state::isExpanded))

        state.searchQuery = "26"
        assertEquals(listOf(true, true), state.groups().map(state::isExpanded))

        state.moveSelection(+1)
        state.moveSelection(+1)
        assertEquals("26.2", state.selectedVersionId)
    }

    @Test
    fun `installed packs come first and play from their own folder`() {
        val state = LauncherState(scope, preload(setOf("26.3"), listOf(pack)))
        val group = state.groups().first()
        assertEquals(PACKS_GROUP, group.key)
        assertTrue(state.isExpanded(group))

        val entry = group.entries.single()
        assertEquals("Fabulously Optimized", entry.label)
        assertEquals(entry, state.entryByKey(entry.key))
        state.selectEntry(entry)
        assertEquals(pack, state.currentEntry()?.pack)
        assertTrue(state.isSelected(entry))
        assertEquals(Settings.packsDir().resolve("fabulously-optimized"), state.gameDirOf(entry))

        state.selectEntry(state.entryFor("26.2", LoaderKind.FABRIC))
        assertNull(state.currentEntry()?.pack)
        assertFalse(state.isSelected(entry))
    }

    @Test
    fun `the last played pack is selected again, a removed one is forgotten`() {
        Settings.update { it.copy(lastPack = pack.id) }
        try {
            assertEquals(pack, LauncherState(scope, preload(setOf("26.3"), listOf(pack))).currentEntry()?.pack)
            assertNull(LauncherState(scope, preload(setOf("26.3"))).currentEntry()?.pack)
        } finally {
            Settings.update { it.copy(lastPack = null) }
        }
    }

    @Test
    fun `the downloaded filter keeps only installed entries and opens their groups`() {
        val state = LauncherState(scope, preload(setOf("26.3", "fabric-loader-0.16.14-26.3")))
        if (state.onlyInstalled) state.toggleOnlyInstalled()
        try {
            state.toggleOnlyInstalled()
            val groups = state.groups()
            assertEquals(listOf("26.3"), groups.map { it.key })
            assertEquals(listOf(LoaderKind.VANILLA, LoaderKind.FABRIC), groups.single().entries.map { it.loader })
            assertTrue(state.isExpanded(groups.single()))
            assertTrue(Settings.current.onlyInstalled)
        } finally {
            if (state.onlyInstalled) state.toggleOnlyInstalled()
        }
        assertEquals(listOf("26.3", "26.2"), state.groups().map { it.key })
    }

    @Test
    fun `a join link offers builds of that version first, a fresh one, then every other build`() {
        val state = LauncherState(scope, preload(setOf("26.3", "fabric-loader-0.16.14-26.3")))
        state.selectEntry(state.entryFor("26.3", LoaderKind.FABRIC))

        val modal = state.joinModal(LaunchLink.Join("mc.example.org", "26.3", "Test"))
        assertEquals(listOf("26.3#FABRIC", "26.3#VANILLA"), modal.choices.map { it.key })
        assertNull(modal.note)

        val older = state.joinModal(LaunchLink.Join("mc.example.org", "26.2", null))
        assertEquals(listOf("26.2#VANILLA", "26.3#FABRIC", "26.3#VANILLA"), older.choices.map { it.key })
        assertFalse(state.isEntryInstalled(older.choices.first()))
    }

    @Test
    fun `builds inside the server's version range come first`() {
        val state = LauncherState(scope, preload(setOf("26.3", "fabric-loader-0.16.14-26.3")))
        state.selectEntry(state.entryFor("26.2", LoaderKind.VANILLA))

        val exact = state.joinModal(LaunchLink.Join("mc.example.org", "26.3", null))
        assertEquals(listOf("26.3#VANILLA", "26.3#FABRIC", "26.2#VANILLA"), exact.choices.map { it.key })

        val ranged = state.joinModal(LaunchLink.Join("mc.example.org", "26.3", null, minVersion = "26.2"))
        assertEquals(listOf("26.2#VANILLA", "26.3#VANILLA", "26.3#FABRIC"), ranged.choices.map { it.key })
    }

    @Test
    fun `an unknown version falls back to the latest release, no version offers every build`() {
        val state = LauncherState(scope, preload(setOf("26.3", "fabric-loader-0.16.14-26.3"), listOf(pack)))
        state.selectEntry(state.entryFor("26.3", LoaderKind.VANILLA))

        val future = state.joinModal(LaunchLink.Join("mc.example.org", "99.1", null))
        assertEquals(listOf("26.3#VANILLA", "26.3#FABRIC", "pack:fabulously-optimized"), future.choices.map { it.key })
        assertEquals("Версии 99.1 нет в списке Mojang — предлагаю последнюю, 26.3", future.note)

        val any = state.joinModal(LaunchLink.Join("mc.example.org", null, null))
        assertEquals(listOf("26.3#VANILLA", "pack:fabulously-optimized", "26.3#FABRIC"), any.choices.map { it.key })
    }

    @Test
    fun `opening a link shows its dialog on the home screen`() {
        val state = LauncherState(scope, preload(setOf("26.3")), link = LaunchLink.Join("mc.example.org", "26.3", null))
        assertTrue(state.modal is Modal.Join)

        state.screen = Screen.SETTINGS
        state.modal = null
        state.openLink(LaunchLink.Open)
        assertEquals(Screen.HOME, state.screen)
        assertNull(state.modal)

        state.openLink(LaunchLink.Install("terralith", LinkContent.DATAPACK, null, null, "Terralith"))
        assertEquals(Modal.Install(LaunchLink.Install("terralith", LinkContent.DATAPACK, null, null, "Terralith")), state.modal)
    }

    @Test
    fun `a server's pinned version survives a round trip through its key`() {
        val state = LauncherState(scope, preload(setOf("26.3")))
        val entry = state.entryFor("26.3", LoaderKind.FABRIC)

        assertEquals(entry, state.entryByKey(entry.key))
        assertEquals(null, state.entryByKey("26.3#NOT_A_LOADER"))
    }
}
