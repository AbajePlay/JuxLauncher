package ru.jux.launcher.servers

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class ServerListTest {

    private fun servers(dir: Path): List<Map<String, Nbt>> {
        val root = Nbt.readRoot(dir.resolve(ServerList.FILE_NAME).readBytes())
        return (root.entries["servers"] as Nbt.ListTag).items.map { (it as Nbt.CompoundTag).entries }
    }

    private fun Map<String, Nbt>.text(key: String) = (this[key] as Nbt.StringTag).value

    @Test
    fun `creates the list when the game has none yet`(@TempDir dir: Path) {
        assertTrue(ServerList.ensure(dir, "VirtusMine", "mc.virtusmine.fun"))
        val list = servers(dir)
        assertEquals(1, list.size)
        assertEquals("VirtusMine", list[0].text("name"))
        assertEquals("mc.virtusmine.fun", list[0].text("ip"))
    }

    @Test
    fun `goes first and keeps the player's servers with all their fields`(@TempDir dir: Path) {
        val own = Nbt.CompoundTag(
            linkedMapOf(
                "name" to Nbt.StringTag("Мой сервер"),
                "ip" to Nbt.StringTag("play.example.org"),
                "icon" to Nbt.StringTag("iVBORw0KGgo="),
                "acceptTextures" to Nbt.ByteTag(1),
                "hidden" to Nbt.ByteTag(0),
            ),
        )
        val original = Nbt.writeRoot(Nbt.CompoundTag(mapOf("servers" to Nbt.ListTag(10, listOf(own)))))
        dir.resolve(ServerList.FILE_NAME).writeBytes(original)

        assertTrue(ServerList.ensure(dir, "VirtusMine", "mc.virtusmine.fun"))
        val list = servers(dir)
        assertEquals(listOf("mc.virtusmine.fun", "play.example.org"), list.map { it.text("ip") })
        assertEquals(own.entries, list[1])
    }

    @Test
    fun `a hidden quick play entry is turned into a visible one on top`(@TempDir dir: Path) {
        val own = Nbt.CompoundTag(linkedMapOf("name" to Nbt.StringTag("Мой"), "ip" to Nbt.StringTag("play.example.org")))
        val quickPlay = Nbt.CompoundTag(
            linkedMapOf(
                "icon" to Nbt.StringTag("iVBORw0KGgo="),
                "name" to Nbt.StringTag("Minecraft Server"),
                "ip" to Nbt.StringTag("mc.virtusmine.fun"),
                "acceptTextures" to Nbt.ByteTag(1),
                "hidden" to Nbt.ByteTag(1),
            ),
        )
        dir.resolve(ServerList.FILE_NAME).writeBytes(Nbt.writeRoot(Nbt.CompoundTag(mapOf("servers" to Nbt.ListTag(10, listOf(own, quickPlay))))))

        assertTrue(ServerList.ensure(dir, "VirtusMine", "mc.virtusmine.fun"))
        val list = servers(dir)
        assertEquals(listOf("mc.virtusmine.fun", "play.example.org"), list.map { it.text("ip") })
        assertEquals("VirtusMine", list[0].text("name"))
        assertEquals(Nbt.ByteTag(0), list[0]["hidden"])
        assertEquals(Nbt.ByteTag(1), list[0]["acceptTextures"])
        assertEquals(Nbt.StringTag("iVBORw0KGgo="), list[0]["icon"])
        assertFalse(ServerList.ensure(dir, "VirtusMine", "mc.virtusmine.fun"))
    }

    @Test
    fun `does not add the same server twice, whatever the spelling`(@TempDir dir: Path) {
        assertTrue(ServerList.ensure(dir, "VirtusMine", "mc.virtusmine.fun"))
        assertFalse(ServerList.ensure(dir, "Virtus", "MC.VirtusMine.fun:25565"))
        assertEquals(1, servers(dir).size)
    }

    @Test
    fun `a broken file is left alone`(@TempDir dir: Path) {
        val junk = byteArrayOf(1, 2, 3, 4)
        dir.resolve(ServerList.FILE_NAME).writeBytes(junk)
        assertThrows(IOException::class.java) { ServerList.ensure(dir, "VirtusMine", "mc.virtusmine.fun") }
        assertArrayEquals(junk, dir.resolve(ServerList.FILE_NAME).readBytes())
    }

    @Test
    fun `the server is always there, even after the player removes it`(@TempDir dir: Path) {
        val virtus = listOf(ServerEntry("VirtusMine", "mc.virtusmine.fun"))
        ServerList.seedDefaults(dir, virtus)
        assertEquals(1, servers(dir).size)

        dir.resolve(ServerList.FILE_NAME).writeBytes(Nbt.writeRoot(Nbt.CompoundTag(mapOf("servers" to Nbt.ListTag(10, emptyList())))))
        ServerList.seedDefaults(dir, virtus)
        assertEquals(listOf("mc.virtusmine.fun"), servers(dir).map { it.text("ip") })

        ServerList.seedDefaults(dir, virtus)
        assertEquals(1, servers(dir).size)
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
