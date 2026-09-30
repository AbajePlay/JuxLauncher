package ru.jux.launcher.instance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.jux.launcher.launch.GameLauncher
import ru.jux.launcher.servers.Nbt
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class CarryOverTest {

    private val gameOptions = (1..12).joinToString("\n") { "option$it:$it" }

    @Test
    fun `settings come from the source, keeping the target's own packs and keys`() {
        val source = listOf("version:3955", "lang:ru_ru", "fov:0.5", "resourcePacks:[\"vanilla\",\"file/Faithful.zip\"]")
        val target = listOf("version:4000", "fov:0.0", "key_key.sodium:key.keyboard.p", "resourcePacks:[\"vanilla\",\"file/Fresh.zip\"]")

        val merged = CarryOver.mergeOptions(source, target)

        assertEquals(
            listOf(
                "version:3955",
                "lang:ru_ru",
                "fov:0.5",
                "resourcePacks:[\"vanilla\",\"file/Faithful.zip\",\"file/Fresh.zip\"]",
                "key_key.sodium:key.keyboard.p",
            ),
            merged,
        )
    }

    @Test
    fun `settings without a version do not inherit the target's, so the game upgrades them`() {
        val merged = CarryOver.mergeOptions(listOf("key_key.forward:17"), listOf("version:4000", "lang:en_us"))
        assertEquals(listOf("key_key.forward:17", "lang:en_us"), merged)
    }

    @Test
    fun `a fresh folder is one the game never ran in`(@TempDir root: Path) {
        val dir = root.resolve("26.3").createDirectories()
        dir.resolve("mods").createDirectories()
        assertTrue(CarryOver.isFresh(dir))
        dir.resolve(GameLauncher.GAME_LOG).writeText("")
        assertFalse(CarryOver.isFresh(dir))
    }

    @Test
    fun `sources are other folders with something to carry, latest played first`(@TempDir root: Path) {
        val instances = root.resolve("instances")
        val old = instances.resolve("1.20.1").createDirectories()
        old.resolve("options.txt").writeText(gameOptions)
        old.resolve("options.txt").toFile().setLastModified(1_000_000)
        val fabric = instances.resolve("26.2-fabric").createDirectories()
        world(fabric, "Survival", "Мой мир", "26.2")
        fabric.resolve("options.txt").writeText("resourcePacks:[\"vanilla\"]")
        fabric.resolve("options.txt").toFile().setLastModified(2_000_000)
        instances.resolve("empty").createDirectories()
        val target = instances.resolve("26.3").createDirectories()
        target.resolve("options.txt").writeText(gameOptions)
        val official = root.resolve(".minecraft").createDirectories()
        official.resolve("options.txt").writeText(gameOptions)
        official.resolve("options.txt").toFile().setLastModified(500_000)

        val sources = CarryOver.sources(target, withShaders = false, roots = listOf(instances), official = official)

        assertEquals(listOf("26.2 Fabric", "1.20.1", ".minecraft"), sources.map { it.label })
        val first = sources.first()
        assertFalse(first.options, "a one-line options file written by the launcher is not the player's settings")
        assertEquals(listOf(CarryWorld("Survival", "Мой мир", "26.2", first.worlds.single().bytes, false)), first.worlds)
        assertTrue(sources.last().official)
    }

    @Test
    fun `everything picked is copied, nothing else is touched`(@TempDir root: Path) {
        val from = root.resolve("26.2").createDirectories()
        from.resolve("options.txt").writeText("version:4000\nlang:ru_ru\n$gameOptions")
        servers(from, "VirtusMine" to "mc.virtusmine.fun", "Друг" to "friend.example:25565", "Ещё" to "other.example")
        from.resolve("resourcepacks").createDirectories().resolve("Faithful.zip").writeText("pack")
        world(from, "Survival", "Выживание", "26.2")
        world(from, "Creative", "Креатив", "26.2")
        world(from, "Old", "Старый", "1.20.1")
        from.resolve("saves/Survival/session.lock").writeText("☃")

        val into = root.resolve("26.3").createDirectories()
        servers(into, "Друг" to "FRIEND.example")
        world(into, "Old", "Старый уже тут", "26.3")

        val source = CarryOver.sources(into, withShaders = false, roots = listOf(root), official = null).single()
        assertEquals(1, source.servers, "VirtusMine is seeded anyway and the friend's server is already there")
        assertTrue(source.worlds.single { it.folder == "Old" }.present)

        val result = CarryOver.apply(source, into, CarryPlan(options = true, servers = true, packs = true, worlds = setOf("Survival", "Old")))

        assertEquals(CarryResult(options = true, servers = 1, packs = 1, worlds = 1, failed = emptyList()), result)
        assertTrue(into.resolve("options.txt").readLines().contains("lang:ru_ru"))
        assertEquals(listOf("FRIEND.example", "other.example"), addresses(into))
        assertEquals("pack", into.resolve("resourcepacks/Faithful.zip").readText())
        assertTrue(into.resolve("saves/Survival/level.dat").exists())
        assertTrue(into.resolve("saves/Survival/region/r.0.0.mca").exists())
        assertFalse(into.resolve("saves/Survival/session.lock").exists())
        assertFalse(into.resolve("saves/Creative").exists())
        assertEquals("Старый уже тут", CarryOver.readLevel(into.resolve("saves/Old")).first)
        assertEquals(listOf("Old", "Survival"), into.resolve("saves").listDirectoryEntries().map { it.name }.sorted())
        assertTrue(from.resolve("saves/Survival/session.lock").exists(), "the source is left as it was")
    }

    @Test
    fun `world names and versions are read from level dat`(@TempDir root: Path) {
        world(root, "w", "§6Золотой мир", "1.21.4")
        assertEquals("Золотой мир" to "1.21.4", CarryOver.readLevel(root.resolve("saves/w")))
        root.resolve("saves/broken").createDirectories().resolve("level.dat").writeText("not nbt")
        assertEquals(null to null, CarryOver.readLevel(root.resolve("saves/broken")))
    }

    private fun world(gameDir: Path, folder: String, name: String, version: String) {
        val dir = gameDir.resolve("saves").resolve(folder).createDirectories()
        val data = Nbt.CompoundTag(
            mapOf(
                "LevelName" to Nbt.StringTag(name),
                "Version" to Nbt.CompoundTag(mapOf("Name" to Nbt.StringTag(version))),
            ),
        )
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(Nbt.writeRoot(Nbt.CompoundTag(mapOf("Data" to data)))) }
        }.toByteArray()
        dir.resolve("level.dat").writeBytes(bytes)
        dir.resolve("region").createDirectories().resolve("r.0.0.mca").writeBytes(ByteArray(4096))
    }

    private fun servers(gameDir: Path, vararg entries: Pair<String, String>) {
        val list = entries.map { (name, ip) -> Nbt.CompoundTag(mapOf("name" to Nbt.StringTag(name), "ip" to Nbt.StringTag(ip))) }
        gameDir.resolve("servers.dat").writeBytes(Nbt.writeRoot(Nbt.CompoundTag(mapOf("servers" to Nbt.ListTag(10, list)))))
    }

    private fun addresses(gameDir: Path): List<String> =
        (Nbt.readRoot(gameDir.resolve("servers.dat").readBytes()).entries["servers"] as Nbt.ListTag).items
            .map { ((it as Nbt.CompoundTag).entries["ip"] as Nbt.StringTag).value }
}
