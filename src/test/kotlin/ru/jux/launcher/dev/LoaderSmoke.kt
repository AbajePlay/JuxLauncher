package ru.jux.launcher.dev

import kotlinx.coroutines.runBlocking
import ru.jux.launcher.auth.Account
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.Settings
import ru.jux.launcher.launch.GameLauncher
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.meta.RuleEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.system.exitProcess

// Без звуковой карты (CI) звук не стартует, поэтому годится и собранный атлас текстур: меню уже рисуется
private val READY = listOf("Sound engine started", "OpenAL initialized", "blocks.png-atlas", "textures-atlas")
private val CRASHED = listOf("Crash report saved", "Game crashed", "Exception in thread \"main\"")
private val TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(8)

private const val QUIET_OPTIONS = """soundCategory_master:0.0
pauseOnLostFocus:false
narrator:0
onboardAccessibility:false
tutorialStep:none
"""

private class Outcome(val target: String, val ok: Boolean, val detail: String)

fun main(args: Array<String>) {
    Paths.ensureBaseDirs()
    val targets = args.map { arg ->
        val (version, loader) = arg.split(':', limit = 2)
        version to LoaderKind.valueOf(loader.uppercase())
    }
    if (targets.isEmpty()) {
        println("usage: smokeLoaders -Ptargets=1.20.1:forge,1.21.1:neoforge")
        exitProcess(2)
    }
    println("data: ${Paths.root}")

    val outcomes = targets.map { (version, loader) -> smoke(version, loader) }

    println()
    outcomes.forEach { println("${if (it.ok) "OK  " else "FAIL"}  ${it.target.padEnd(20)} ${it.detail}") }
    exitProcess(if (outcomes.all { it.ok }) 0 else 1)
}

private fun smoke(version: String, loader: LoaderKind): Outcome {
    val target = "$version ${loader.label}"
    println("\n=== $target")
    val started = System.currentTimeMillis()
    val gameDir = Settings.gameDir(version, loader).also { it.createDirectories() }
    gameDir.resolve("options.txt").takeUnless { it.exists() }?.writeText(QUIET_OPTIONS)

    // Без окна игры: ставит всё нужное и показывает, с чем игра запустилась бы
    if (System.getProperty("smoke.prepareOnly") == "true") {
        return try {
            val prepared = runBlocking { GameLauncher.prepare(version, loader, gameDir, onStage = { println("  $it") }) }
            val json = prepared.installed.json
            val tweaks = json.arguments?.game.orEmpty().flatMap { it.resolve(RuleEnvironment.current) } +
                json.minecraftArguments.orEmpty().split(' ')
            val jars = prepared.installed.classpath.map { it.fileName.toString() }
            val missing = prepared.installed.classpath.filterNot { it.exists() }.map { it.fileName.toString() }
            println("  id: ${json.id}\n  main: ${json.mainClass}\n  tweaks: ${tweaks.filter { "tweak" in it.lowercase() || "OptiFine" in it }}")
            println("  classpath: ${jars.filter { "optifine" in it.lowercase() || "launchwrapper" in it }}")
            Outcome(target, missing.isEmpty(), "prepared ${json.id} in ${seconds(started)} s" + if (missing.isEmpty()) "" else ", missing $missing")
        } catch (e: Throwable) {
            Outcome(target, false, "prepare failed: ${e.message?.lineSequence()?.joinToString(" | ")}")
        }
    }

    val launch = try {
        runBlocking {
            GameLauncher.launch(
                versionId = version,
                account = Account.offline("JuxSmoke"),
                loader = loader,
                onStage = { println("  $it") },
                onNotice = { println("  notice: $it") },
            )
        }
    } catch (e: Throwable) {
        return Outcome(target, false, "install/launch failed: ${e.message?.lineSequence()?.joinToString(" | ")}")
    }

    val installed = seconds(started)
    val process = launch.process
    try {
        while (System.currentTimeMillis() - started < TIMEOUT_MILLIS) {
            val log = read(launch.logFile)
            READY.firstOrNull { it in log }?.let {
                return Outcome(target, true, "menu after ${seconds(started)} s (install ${installed} s), \"$it\"")
            }
            CRASHED.firstOrNull { it in log }?.let {
                return Outcome(target, false, "crashed: \"$it\"\n${tail(log)}")
            }
            if (!process.isAlive) {
                return Outcome(target, false, "exited with ${process.exitValue()}\n${tail(log)}")
            }
            Thread.sleep(1000)
        }
        return Outcome(target, false, "no main menu in ${TIMEOUT_MILLIS / 60000} min\n${tail(read(launch.logFile))}")
    } finally {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(20, TimeUnit.SECONDS)
    }
}

private fun read(file: Path): String = runCatching { String(Files.readAllBytes(file)) }.getOrDefault("")

private fun tail(log: String): String = log.lineSequence().filter { it.isNotBlank() }.toList().takeLast(15)
    .joinToString("\n") { "      $it" }

private fun seconds(since: Long): Long = (System.currentTimeMillis() - since) / 1000
