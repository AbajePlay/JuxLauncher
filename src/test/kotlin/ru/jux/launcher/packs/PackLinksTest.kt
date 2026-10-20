package ru.jux.launcher.packs

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.jux.launcher.link.LaunchLink
import ru.jux.launcher.link.LinkContent
import ru.jux.launcher.meta.LoaderKind
import java.nio.file.Path

class PackLinksTest {

    @Test
    fun `a pack from Modrinth is shared as a link to the project itself`(@TempDir dir: Path) {
        val pack = Modpack(
            id = "fabulously-optimized",
            title = "Fabulously Optimized",
            version = "14.1.0",
            gameVersion = "26.2",
            loader = LoaderKind.FABRIC,
            projectId = "1KVo5zza",
            versionId = "ssWn7YI0",
        )
        val share = runBlocking { PackLinks.share(dir, pack.gameVersion, pack.loader, pack, pack.title) }

        assertEquals(LaunchLink.Install("1KVo5zza", LinkContent.MODPACK, null, null, "Fabulously Optimized"), LaunchLink.parse(share.uri))
        assertTrue(share.skipped.isEmpty())
    }

    @Test
    fun `an empty pack of your own is shared as its version and loader`(@TempDir dir: Path) {
        val pack = Modpack(
            id = "druzya",
            title = "Друзья",
            version = "",
            gameVersion = "1.21.4",
            loader = LoaderKind.FABRIC,
            loaderVersion = "0.16.14",
            projectId = "",
            versionId = "",
        )
        val share = runBlocking { PackLinks.share(dir, pack.gameVersion, pack.loader, pack, pack.title) }

        assertEquals(LaunchLink.SharedPack("Друзья", "1.21.4", LoaderKind.FABRIC, "0.16.14"), LaunchLink.parse(share.uri))
        assertEquals("1.21.4 Fabric", share.summary)
    }
}
