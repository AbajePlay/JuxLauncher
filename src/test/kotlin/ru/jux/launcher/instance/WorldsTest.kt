package ru.jux.launcher.instance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.jux.launcher.servers.Nbt
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class WorldsTest {

    @Test
    fun `worlds show their name, mode and version`(@TempDir game: Path) {
        writeWorld(game, "Survival", "Выживание", "1.21.4", mapOf("GameType" to Nbt.IntTag(0), "LastPlayed" to Nbt.LongTag(1_700_000_000_000)))
        writeWorld(game, "Hard", "Хардкор", "1.21.4", mapOf("GameType" to Nbt.IntTag(0), "hardcore" to Nbt.ByteTag(1)))

        val worlds = Worlds.list(game).associateBy { it.folder }
        assertEquals("Выживание", worlds.getValue("Survival").name)
        assertEquals("Выживание", worlds.getValue("Survival").mode)
        assertEquals(1_700_000_000_000, worlds.getValue("Survival").level.lastPlayed)
        assertEquals("Хардкор", worlds.getValue("Hard").mode)
        assertTrue(worlds.getValue("Hard").bytes > 4096)
    }

    @Test
    fun `a backup restores the world as it was and keeps the newer state too`(@TempDir game: Path) {
        val world = writeWorld(game, "Survival", "Выживание", "1.21.4")
        world.resolve("session.lock").writeText("lock")
        world.resolve("notes.txt").writeText("before")

        val backup = Worlds.backup(game, "Survival")
        ZipFile(backup.file.toFile()).use { zip ->
            assertTrue(zip.getEntry("Survival/notes.txt") != null)
            assertTrue(zip.getEntry("Survival/session.lock") == null, "the lock of a running game is not copied")
        }
        assertEquals(listOf("Survival"), Worlds.backups(game).map { it.world })

        world.resolve("notes.txt").writeText("after")
        Worlds.restore(game, backup)

        assertEquals("before", world.resolve("notes.txt").readText())
        assertEquals(2, Worlds.backups(game).size, "the state before restoring was saved first")
        assertTrue(Worlds.backups(game).any { it.auto })
    }

    @Test
    fun `an archived world is imported under a free name`(@TempDir game: Path, @TempDir downloads: Path) {
        writeWorld(game, "Skyblock", "Уже есть", "1.21.4")
        val archive = downloads.resolve("Skyblock.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            listOf("Skyblock/level.dat" to "x", "Skyblock/region/r.0.0.mca" to "region").forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }

        assertEquals("Skyblock (2)", Worlds.import(game, archive))
        assertEquals("region", game.resolve("saves/Skyblock (2)/region/r.0.0.mca").readText())
    }

    @Test
    fun `archives without a world or with escaping paths are refused`(@TempDir game: Path, @TempDir downloads: Path) {
        val empty = downloads.resolve("empty.zip")
        ZipOutputStream(Files.newOutputStream(empty)).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.closeEntry()
        }
        assertThrows<IOException> { Worlds.import(game, empty) }

        val evil = downloads.resolve("evil.zip")
        ZipOutputStream(Files.newOutputStream(evil)).use { zip ->
            listOf("w/level.dat", "w/../../escape.txt").forEach {
                zip.putNextEntry(ZipEntry(it))
                zip.closeEntry()
            }
        }
        assertThrows<IOException> { Worlds.import(game, evil) }
        assertFalse(game.resolve("escape.txt").exists())
        assertFalse(game.resolve("saves").toFile().list().orEmpty().any { it.startsWith(".import") }, "nothing half-extracted is left")
    }

    @Test
    fun `auto backups only follow worlds played since the last one`(@TempDir game: Path) {
        writeWorld(game, "Survival", "Выживание", "1.21.4")
        assertEquals(1, Worlds.autoBackup(game))
        assertEquals(0, Worlds.autoBackup(game), "nothing changed since")
    }
}
