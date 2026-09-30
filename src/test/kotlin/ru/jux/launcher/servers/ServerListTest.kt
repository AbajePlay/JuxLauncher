package ru.jux.launcher.servers

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class ServerListTest {

    private val virtus = listOf(ServerEntry("VirtusMine", "mc.virtusmine.fun"))

    private fun servers(dir: Path): List<Map<String, Nbt>> {
        val root = Nbt.readRoot(dir.resolve(ServerList.FILE_NAME).readBytes())
        return (root.entries["servers"] as Nbt.ListTag).items.map { (it as Nbt.CompoundTag).entries }
    }

    private fun Map<String, Nbt>.text(key: String) = (this[key] as Nbt.StringTag).value

    private fun server(name: String, ip: String, vararg extra: Pair<String, Nbt>) =
        Nbt.CompoundTag(linkedMapOf("name" to Nbt.StringTag(name), "ip" to Nbt.StringTag(ip), *extra))

    private fun list(vararg servers: Nbt.CompoundTag) = Nbt.writeRoot(Nbt.CompoundTag(mapOf("servers" to Nbt.ListTag(10, servers.toList()))))

    private fun save(dir: Path, vararg servers: Nbt.CompoundTag) = dir.resolve(ServerList.FILE_NAME).writeBytes(list(*servers))

    private fun pin(dir: Path, repair: Boolean = true) = ServerList.pin(dir, virtus, repair)

    @Test
    fun `creates the list when the game has none yet`(@TempDir dir: Path) {
        assertTrue(pin(dir))
        val list = servers(dir)
        assertEquals(1, list.size)
        assertEquals("VirtusMine", list[0].text("name"))
        assertEquals("mc.virtusmine.fun", list[0].text("ip"))
    }

    @Test
    fun `goes first and keeps the player's servers with all their fields`(@TempDir dir: Path) {
        val own = server(
            "Мой сервер", "play.example.org",
            "icon" to Nbt.StringTag("iVBORw0KGgo="), "acceptTextures" to Nbt.ByteTag(1), "hidden" to Nbt.ByteTag(0),
        )
        save(dir, own)

        assertTrue(pin(dir))
        val list = servers(dir)
        assertEquals(listOf("mc.virtusmine.fun", "play.example.org"), list.map { it.text("ip") })
        assertEquals(own.entries, list[1])
    }

    @Test
    fun `a hidden quick play entry is turned into a visible one on top`(@TempDir dir: Path) {
        val quickPlay = server(
            "Minecraft Server", "mc.virtusmine.fun",
            "icon" to Nbt.StringTag("iVBORw0KGgo="), "acceptTextures" to Nbt.ByteTag(1), "hidden" to Nbt.ByteTag(1),
        )
        save(dir, server("Мой", "play.example.org"), quickPlay)

        assertTrue(pin(dir))
        val list = servers(dir)
        assertEquals(listOf("mc.virtusmine.fun", "play.example.org"), list.map { it.text("ip") })
        assertEquals("VirtusMine", list[0].text("name"))
        assertEquals(Nbt.ByteTag(0), list[0]["hidden"])
        assertEquals(Nbt.ByteTag(1), list[0]["acceptTextures"])
        assertEquals(Nbt.StringTag("iVBORw0KGgo="), list[0]["icon"])
        assertFalse(pin(dir))
    }

    @Test
    fun `a pinned list is not rewritten`(@TempDir dir: Path) {
        assertTrue(pin(dir))
        val bytes = dir.resolve(ServerList.FILE_NAME).readBytes()
        assertFalse(pin(dir))
        assertArrayEquals(bytes, dir.resolve(ServerList.FILE_NAME).readBytes())
    }

    @Test
    fun `renamed, moved down or doubled, it is one entry on top under its own name`(@TempDir dir: Path) {
        save(
            dir,
            server("A", "a.example.org"),
            server("Какой-то сервер", "MC.VirtusMine.fun:25565", "acceptTextures" to Nbt.ByteTag(1)),
            server("B", "b.example.org"),
            server("Ещё раз", "mc.virtusmine.fun."),
        )

        assertTrue(pin(dir))
        val list = servers(dir)
        assertEquals(listOf("MC.VirtusMine.fun:25565", "a.example.org", "b.example.org"), list.map { it.text("ip") })
        assertEquals("VirtusMine", list[0].text("name"))
        assertEquals(Nbt.ByteTag(1), list[0]["acceptTextures"])
    }

    @Test
    fun `the server is always there, even after the player removes it`(@TempDir dir: Path) {
        ServerList.seedDefaults(dir, virtus)
        assertEquals(1, servers(dir).size)

        save(dir)
        ServerList.seedDefaults(dir, virtus)
        assertEquals(listOf("mc.virtusmine.fun"), servers(dir).map { it.text("ip") })

        ServerList.seedDefaults(dir, virtus)
        assertEquals(1, servers(dir).size)
    }

    @Test
    fun `a read-only list is made writable and pinned`(@TempDir dir: Path) {
        save(dir, server("Мой", "play.example.org"))
        val file = dir.resolve(ServerList.FILE_NAME)
        assertTrue(file.toFile().setWritable(false))

        assertTrue(pin(dir))
        assertEquals(listOf("mc.virtusmine.fun", "play.example.org"), servers(dir).map { it.text("ip") })
        assertTrue(file.toFile().canWrite())
    }

    @Test
    fun `a planted temp file does not block the write`(@TempDir dir: Path) {
        dir.resolve("${ServerList.FILE_NAME}.tmp").createDirectories()
        save(dir, server("Мой", "play.example.org"))

        assertTrue(pin(dir))
        assertEquals(listOf("mc.virtusmine.fun", "play.example.org"), servers(dir).map { it.text("ip") })
        assertEquals(setOf(ServerList.FILE_NAME, "${ServerList.FILE_NAME}.tmp"), dir.listDirectoryEntries().map { it.fileName.toString() }.toSet())
    }

    @Test
    fun `a broken list is kept aside and rebuilt from the game's backup`(@TempDir dir: Path) {
        val junk = byteArrayOf(1, 2, 3, 4)
        dir.resolve(ServerList.FILE_NAME).writeBytes(junk)
        dir.resolve("servers.dat_old").writeBytes(list(server("Мой", "play.example.org")))

        assertTrue(pin(dir))
        assertEquals(listOf("mc.virtusmine.fun", "play.example.org"), servers(dir).map { it.text("ip") })
        val aside = dir.listDirectoryEntries("${ServerList.FILE_NAME}.broken-*").single()
        assertArrayEquals(junk, aside.readBytes())
    }

    @Test
    fun `a broken list without a backup starts over`(@TempDir dir: Path) {
        dir.resolve(ServerList.FILE_NAME).writeBytes(byteArrayOf(1, 2, 3, 4))

        assertTrue(pin(dir))
        assertEquals(listOf("mc.virtusmine.fun"), servers(dir).map { it.text("ip") })
    }

    @Test
    fun `a folder in place of the list is moved aside`(@TempDir dir: Path) {
        dir.resolve(ServerList.FILE_NAME).createDirectories()

        assertTrue(pin(dir))
        assertEquals(listOf("mc.virtusmine.fun"), servers(dir).map { it.text("ip") })
        assertTrue(dir.listDirectoryEntries("${ServerList.FILE_NAME}.broken-*").single().isDirectory())
    }

    @Test
    fun `while the game runs a missing or broken list is left for the next launch`(@TempDir dir: Path) {
        assertFalse(pin(dir, repair = false))
        assertFalse(dir.resolve(ServerList.FILE_NAME).exists())

        val junk = byteArrayOf(1, 2, 3, 4)
        dir.resolve(ServerList.FILE_NAME).writeBytes(junk)
        assertFalse(pin(dir, repair = false))
        assertArrayEquals(junk, dir.resolve(ServerList.FILE_NAME).readBytes())
    }

    @Test
    fun `the guard puts the server back when the game saves the list without it`(@TempDir dir: Path) {
        val file = dir.resolve(ServerList.FILE_NAME)
        ServerList.seedDefaults(dir, virtus)
        val guard = ServerList.Guard(dir, virtus)

        guard.check()
        assertEquals(1, servers(dir).size)

        save(dir, server("Мой", "play.example.org"))
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000))
        guard.check()
        assertEquals(listOf("mc.virtusmine.fun", "play.example.org"), servers(dir).map { it.text("ip") })

        val pinned = file.readBytes()
        guard.check()
        assertArrayEquals(pinned, file.readBytes())
    }

    @Test
    fun `every tag type survives a round trip`() {
        val root = Nbt.CompoundTag(
            linkedMapOf(
                "b" to Nbt.ByteTag(-3), "s" to Nbt.ShortTag(300), "i" to Nbt.IntTag(70000), "l" to Nbt.LongTag(1L shl 40),
                "f" to Nbt.FloatTag(1.5f), "d" to Nbt.DoubleTag(2.25), "str" to Nbt.StringTag("Привет ✓"),
                "list" to Nbt.ListTag(3, listOf(Nbt.IntTag(1), Nbt.IntTag(2))),
                "nested" to Nbt.CompoundTag(mapOf("x" to Nbt.StringTag("y"))),
            ),
        )
        val bytes = Nbt.writeRoot(root)
        assertArrayEquals(bytes, Nbt.writeRoot(Nbt.readRoot(bytes)))
        assertEquals(root, Nbt.readRoot(bytes))
    }
}
