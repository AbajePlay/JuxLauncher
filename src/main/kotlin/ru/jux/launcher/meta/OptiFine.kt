package ru.jux.launcher.meta

import ru.jux.launcher.core.tr
import ru.jux.launcher.net.Http
import java.io.IOException

data class OptiFineBuild(val file: String, val gameVersion: String, val edition: String, val preview: Boolean)

object OptiFineRepository {

    private const val SITE = "https://optifine.net"
    private val FILE = Regex("""adloadx\?f=((preview_)?OptiFine_(\d+(?:\.\d+)+)_(HD_U_[A-Za-z0-9_]+?)\.jar)""")
    private val LINK = Regex("""downloadx\?f=[^'"\s<>]+""")
    private val TRAILING_ZERO = Regex("""\d+\.\d+\.0""")

    @Volatile
    private var cached: List<OptiFineBuild>? = null

    fun builds(): List<OptiFineBuild> =
        cached ?: parse(Http.getString("$SITE/downloads")).also { if (it.isNotEmpty()) cached = it }

    fun buildsFor(gameVersion: String): List<OptiFineBuild> = builds().filter { it.gameVersion == gameVersion }

    fun build(gameVersion: String, edition: String): OptiFineBuild =
        buildsFor(gameVersion).firstOrNull { it.edition == edition }
            ?: throw IOException(tr("На optifine.net нет OptiFine $edition для $gameVersion", "optifine.net has no OptiFine $edition for $gameVersion"))

    fun downloadUrl(build: OptiFineBuild): String {
        val page = Http.getString("$SITE/adloadx?f=${build.file}")
        val link = LINK.find(page)?.value?.replace("&amp;", "&")
            ?: throw IOException(tr("optifine.net не отдал файл — попробуй позже", "optifine.net did not return the file — try later"))
        return "$SITE/$link"
    }

    internal fun parse(html: String): List<OptiFineBuild> =
        FILE.findAll(html)
            .map { match ->
                val (file, preview, version, edition) = match.destructured
                OptiFineBuild(file, normalize(version), edition, preview.isNotEmpty())
            }
            .distinctBy { it.file }
            .toList()

    // OptiFine называет 1.8 и 1.9 «1.8.0» и «1.9.0», а Mojang — без нуля
    internal fun normalize(version: String): String =
        if (TRAILING_ZERO.matches(version)) version.removeSuffix(".0") else version
}
