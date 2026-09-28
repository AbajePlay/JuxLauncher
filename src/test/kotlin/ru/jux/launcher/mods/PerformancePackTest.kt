package ru.jux.launcher.mods

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.jux.launcher.instance.InstanceStore
import ru.jux.launcher.instance.ManagedMod
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.outputStream

class PerformancePackTest {

    private fun version(
        project: String,
        type: String = "release",
        requires: List<String> = emptyList(),
        date: String = "2026-01-01",
    ) = Modrinth.Version(
        id = "v-$project-$date",
        projectId = project,
        versionType = type,
        datePublished = date,
        files = listOf(
            Modrinth.VersionFile(
                url = "https://cdn.modrinth.com/data/$project/file.jar",
                filename = "$project.jar",
                primary = true,
                hashes = mapOf("sha1" to "0".repeat(40)),
            )
        ),
        dependencies = requires.map { Modrinth.Dependency(projectId = it, type = "required") },
    )

    private fun finder(override: Map<String, Modrinth.Version?> = emptyMap()): suspend (String) -> Modrinth.Version? =
        { slug -> if (slug in override) override[slug] else version(slug) }

    private val titles: suspend (Collection<String>) -> Map<String, String> = { ids -> ids.associateWith { "Fabric API" } }

    @Test
    fun `required dependencies come along`() {
        val find = finder(mapOf("entityculling" to version("entityculling", requires = listOf("fabric-api"))))
        val (chosen, missing) = runBlocking { PerformancePack.resolve(emptySet(), find, titles) }

        assertTrue(chosen.any { it.projectId == "fabric-api" && it.title == "Fabric API" })
        assertTrue(chosen.any { it.projectId == "entityculling" })
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `a mod whose dependency has no build is left out and reported`() {
        val find = finder(
            mapOf(
                "entityculling" to version("entityculling", requires = listOf("fabric-api")),
                "fabric-api" to null,
            )
        )
        val (chosen, missing) = runBlocking { PerformancePack.resolve(emptySet(), find, titles) }

        assertTrue(chosen.none { it.projectId == "entityculling" })
        assertEquals(listOf("EntityCulling"), missing)
        assertEquals(PerformancePack.MEMBERS.size - 1, chosen.size)
    }

    @Test
    fun `mods the player already has are neither installed again nor reported missing`() {
        val (chosen, missing, owned) = runBlocking { PerformancePack.resolve(setOf("sodium"), finder(), titles) }

        assertTrue(chosen.none { it.projectId == "sodium" })
        assertTrue(missing.isEmpty())
        assertEquals(listOf("Sodium"), owned)
    }

    @Test
    fun `a dependency the player already has counts as met`() {
        val find = finder(
            mapOf(
                "entityculling" to version("entityculling", requires = listOf("fabric-api")),
                "fabric-api" to null,
            )
        )
        val (chosen, missing) = runBlocking { PerformancePack.resolve(setOf("fabric-api"), find, titles) }

        assertTrue(chosen.any { it.projectId == "entityculling" })
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `members with no stable build are reported by name`() {
        val (_, missing) = runBlocking { PerformancePack.resolve(emptySet(), finder(mapOf("modernfix" to null)), titles) }
        assertEquals(listOf("ModernFix"), missing)
    }

    @Test
    fun `releases first, betas when there is nothing else, alphas never`() {
        val alpha = version("sodium", type = "alpha", date = "2026-03-01")
        val beta = version("sodium", type = "beta", date = "2026-02-01")
        val release = version("sodium", type = "release", date = "2026-01-01")

        assertEquals(release, Modrinth.pick(listOf(alpha, beta, release)))
        assertEquals(beta, Modrinth.pick(listOf(alpha, beta)))
        assertNull(Modrinth.pick(listOf(alpha)))
    }

    @Test
    fun `turning the boost off keeps what other mods still need`(@TempDir game: Path) {
        val mods = game.resolve("mods").createDirectories()
        fun jar(name: String, json: String) = ZipOutputStream(mods.resolve(name).outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write(json.toByteArray())
            zip.closeEntry()
        }
        jar("sodium.jar", """{"id":"sodium","version":"0.8.12"}""")
        jar("lithium.jar", """{"id":"lithium","version":"0.21.0"}""")
        jar("iris.jar", """{"id":"iris","version":"1.11.4","depends":{"sodium":">=0.8"}}""")
        InstanceStore.update(game) { options ->
            options.copy(fpsBoost = true, boostMods = listOf("sodium.jar", "lithium.jar").map { ManagedMod(it, it, it, "0") })
        }

        PerformancePack.remove(game)

        assertTrue(mods.resolve("sodium.jar").exists())
        assertFalse(mods.resolve("lithium.jar").exists())
        assertTrue(mods.resolve("iris.jar").exists())
        assertFalse(InstanceStore.get(game).fpsBoost)
    }

    @Test
    fun `an upstream file name can never leave its folder`() {
        assertEquals("sodium-fabric-0.6.13+mc1.21.4.jar", ContentKind.MOD.safeName("sodium-fabric-0.6.13+mc1.21.4.jar"))
        assertNull(ContentKind.MOD.safeName("../evil.jar"))
        assertNull(ContentKind.MOD.safeName("sub/dir.jar"))
        assertNull(ContentKind.MOD.safeName("sub\\dir.jar"))
        assertNull(ContentKind.MOD.safeName("C:evil.jar"))
        assertNull(ContentKind.MOD.safeName("readme.txt"))
        assertEquals("BSL_v10.zip", ContentKind.SHADER.safeName("BSL_v10.zip"))
        assertNull(ContentKind.RESOURCE_PACK.safeName("pack.jar"))
    }
}
