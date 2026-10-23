package ru.jux.launcher.update

import ru.jux.launcher.core.tr
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeLines

/**
 * Заставка обновления — JuxUpdate.exe из папки установки (собирается из src/main/csharp). Перед запуском
 * копируется в кэш: msiexec заменит файлы в папке установки, а запущенный exe заменить нельзя.
 */
internal object UpdateSplash {

    const val EXE = "JuxUpdate.exe"

    private val ASSETS = listOf("brand/wordmark.png", "fonts/Montserrat-Regular.ttf", "fonts/Montserrat-Bold.ttf")

    private val installed: Path?
        get() = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).resolve(EXE) }
            ?.takeIf { it.exists() }

    fun prepare(dir: Path): Path {
        dir.createDirectories()
        ASSETS.forEach { resource ->
            val stream = UpdateSplash::class.java.getResourceAsStream("/$resource") ?: throw IOException(tr("В лаунчере нет $resource", "The launcher is missing $resource"))
            stream.use { Files.copy(it, dir.resolve(resource.substringAfterLast('/')), StandardCopyOption.REPLACE_EXISTING) }
        }
        val exe = installed ?: throw IOException(tr("В лаунчере нет $EXE — переустановите его", "The launcher is missing $EXE — reinstall it"))
        return Files.copy(exe, dir.resolve(EXE), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Строки job.txt в том порядке, в каком их читает Program.Main. */
    fun job(launcher: Long, installer: String, arguments: String, app: Path, version: String, ready: Path): List<String> {
        val lines = listOf(
            launcher.toString(),
            installer,
            arguments,
            app.toString(),
            version,
            tr("Обновляю JuxLauncher до $version", "Updating JuxLauncher to $version"),
            tr("Обновление не встало (код {0}), открываю прежнюю версию", "Update failed (code {0}), opening the previous version"),
            ready.toString(),
        )
        require(lines.none { '\n' in it || '\r' in it }) { "job.txt lines must be single-line" }
        return lines
    }

    fun start(exe: Path, job: List<String>) {
        val file = exe.resolveSibling("job.txt")
        file.writeLines(job, Charsets.UTF_8)
        ProcessBuilder(exe.toString(), file.toString())
            .directory(exe.parent.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}
