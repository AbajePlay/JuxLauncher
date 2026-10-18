package ru.jux.launcher.logs

import ru.jux.launcher.core.NoticeAction
import ru.jux.launcher.meta.LoaderKind
import java.io.RandomAccessFile
import java.nio.file.Path

data class CrashFix(val action: NoticeAction, val target: String? = null)

data class Diagnosis(val reason: String, val fix: CrashFix? = null)

object CrashHints {

    private const val TAIL_BYTES = 256 * 1024

    private val MISSING_FABRIC = Regex("""requires .*? of (?:mod '[^']*' \()?([A-Za-z0-9_.\-]+)\)?,? which is missing""")
    private val MISSING_FORGE = Regex("""Mod ID: '([A-Za-z0-9_.\-]+)', Requested by: '([A-Za-z0-9_.\-]+)'""")
    private val MIXIN_MOD = Regex("""Mixin apply for mod ([A-Za-z0-9_.\-]+) failed""")
    private val NEWER_JAVA = Regex("""compiled by a more recent version of the Java Runtime \(class file version (\d+)""")
    private val CORRUPT = listOf(
        "java.util.zip.ZipException", "Invalid or corrupt jarfile", "zip END header not found",
        "ClassNotFoundException: net.minecraft.client.main.Main", "NoClassDefFoundError: net/minecraft/",
    )
    private val DRIVER = listOf(
        "Pixel format not accelerated", "GLFW error 65542", "WGL: The driver does not appear to support OpenGL",
        "Failed to create the GLFW window", "GLFW error 65543",
    )
    private val DUPLICATES = listOf("Found duplicate mods", "DuplicateModsFoundException", "Duplicate mods found")
    private val PLATFORM = setOf("minecraft", "java", "fabricloader", "quilt_loader", "forge", "neoforge")

    fun diagnose(logFile: Path?, crashReport: Path?, loader: LoaderKind, versionId: String, exitCode: Int): Diagnosis {
        val text = listOfNotNull(logFile, crashReport).mapNotNull(::tail).joinToString("\n")
        return reason(text, loader, versionId) ?: Diagnosis("Код выхода $exitCode — подробности в логах")
    }

    internal fun reason(log: String, loader: LoaderKind, versionId: String): Diagnosis? {
        MISSING_FABRIC.findAll(log).map { it.groupValues[1] }.firstOrNull { it !in PLATFORM }?.let { id ->
            return Diagnosis("Не хватает мода «$id»", CrashFix(NoticeAction.INSTALL_MOD, id))
        }
        MISSING_FORGE.findAll(log).map { it.destructured }.firstOrNull { (id, _) -> id !in PLATFORM }?.let { (id, by) ->
            return Diagnosis("Моду «$by» нужен «$id»", CrashFix(NoticeAction.INSTALL_MOD, id))
        }
        MIXIN_MOD.find(log)?.let { mixin ->
            val id = mixin.groupValues[1]
            return Diagnosis("Мод «$id» не подходит к этой версии игры", CrashFix(NoticeAction.DISABLE_MOD, id))
        }
        NEWER_JAVA.find(log)?.let { java ->
            val major = (java.groupValues[1].toIntOrNull() ?: 0) - 44
            return Diagnosis("Один из модов требует Java $major — проверь новые моды")
        }
        return when {
            "Unsupported class file major version" in log && loader.isModded -> Diagnosis(
                "${loader.label} пока не поддерживает Minecraft $versionId" +
                    if (loader == LoaderKind.QUILT) " — выбери Fabric" else "",
            )
            "Could not reserve enough space" in log || "Invalid maximum heap size" in log -> Diagnosis(
                "Java не смогла выделить столько памяти",
                CrashFix(NoticeAction.RESET_MEMORY),
            )
            "OutOfMemoryError" in log -> Diagnosis("Игре не хватило памяти", CrashFix(NoticeAction.MORE_MEMORY))
            CORRUPT.any { it in log } -> Diagnosis(
                "Файлы игры повреждены",
                CrashFix(NoticeAction.VERIFY_FILES),
            )
            DRIVER.any { it in log } -> Diagnosis(
                "Видеокарта не открыла окно игры — обнови драйвер",
            )
            DUPLICATES.any { it in log } -> Diagnosis(
                "Один мод стоит дважды — удали копию",
            )
            "Incompatible mods found" in log || "Incompatible mod set" in log || "ModResolutionException" in log -> Diagnosis(
                "Моды несовместимы — подробности в логах",
            )
            "Mixin apply failed" in log || "MixinApplyError" in log || "InvalidInjectionException" in log -> Diagnosis(
                "Какой-то мод не подходит к этой версии — выключи новые моды",
            )
            else -> null
        }
    }

    private fun tail(file: Path): String? = runCatching {
        RandomAccessFile(file.toFile(), "r").use { raf ->
            val start = (raf.length() - TAIL_BYTES).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()
}
