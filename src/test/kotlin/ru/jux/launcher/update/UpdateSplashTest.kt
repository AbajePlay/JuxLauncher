package ru.jux.launcher.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readLines

class UpdateSplashTest {

    @Test
    fun `the job keeps quotes and apostrophes as they are, one value per line`() {
        val job = UpdateSplash.job(
            launcher = 4242,
            installer = "msiexec.exe",
            arguments = "/i \"C:\\Users\\O'Brien\\JuxLauncher-1.6.1.msi\" /qn /norestart",
            app = Path.of("C:\\Users\\O'Brien\\AppData\\Local\\JuxLauncher\\JuxLauncher.exe"),
            version = "1.6.1",
            ready = Path.of("C:\\Users\\O'Brien\\.jux\\cache\\update\\ready"),
        )
        assertEquals(8, job.size)
        assertEquals("4242", job[0])
        assertEquals("/i \"C:\\Users\\O'Brien\\JuxLauncher-1.6.1.msi\" /qn /norestart", job[2])
        assertEquals("C:\\Users\\O'Brien\\AppData\\Local\\JuxLauncher\\JuxLauncher.exe", job[3])
        assertEquals("Обновляю JuxLauncher до 1.6.1", job[5])
        assertTrue("{0}" in job[6])
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the splash exe ships next to its assets and draws itself`(@TempDir dir: Path) {
        val exe = UpdateSplash.prepare(dir)
        listOf("JuxUpdate.exe", "wordmark.png", "Montserrat-Regular.ttf", "Montserrat-Bold.ttf").forEach {
            assertTrue(dir.resolve(it).exists(), "$it is missing")
        }
        val png = dir.resolve("splash.png")
        val render = ProcessBuilder(exe.toString(), "--render", dir.toString(), "1.6.1", "Обновляю JuxLauncher до 1.6.1", png.toString(), "1.25").start()
        assertTrue(render.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, render.exitValue())
        assertTrue(png.exists() && png.fileSize() > 10_000)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the splash runs the installer from the job and reports its exit code`(@TempDir dir: Path) {
        val exe = UpdateSplash.prepare(dir)
        val gone = ProcessBuilder("cmd.exe", "/c", "exit").start().also { it.waitFor() }
        val ready = dir.resolve("ready")
        val job = UpdateSplash.job(
            launcher = gone.pid(),
            installer = "cmd.exe",
            arguments = "/c echo done> \"${dir.resolve("installed.txt")}\"",
            app = Path.of(""),
            version = "1.6.1",
            ready = ready,
        )
        dir.resolve("job.txt").toFile().writeText(job.joinToString("\n", postfix = "\n"), Charsets.UTF_8)

        val splash = ProcessBuilder(exe.toString(), dir.resolve("job.txt").toString()).start()
        assertTrue(splash.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, splash.exitValue())
        assertTrue(ready.exists())
        assertEquals(listOf("done"), dir.resolve("installed.txt").readLines())
    }
}
