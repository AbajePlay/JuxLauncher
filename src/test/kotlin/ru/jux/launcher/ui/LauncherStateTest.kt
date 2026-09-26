package ru.jux.launcher.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.jux.launcher.core.PreloadResult
import ru.jux.launcher.core.Settings
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.meta.LoaderSupport
import ru.jux.launcher.meta.ManifestVersion
import ru.jux.launcher.meta.VersionManifest

class LauncherStateTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun preload(profiles: Set<String>) = PreloadResult(
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
    fun `a server's pinned version survives a round trip through its key`() {
        val state = LauncherState(scope, preload(setOf("26.3")))
        val entry = state.entryFor("26.3", LoaderKind.FABRIC)

        assertEquals(entry, state.entryByKey(entry.key))
        assertEquals(null, state.entryByKey("26.3#NOT_A_LOADER"))
    }
}
