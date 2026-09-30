package ru.jux.launcher.dev

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import ru.jux.launcher.activity.PlayHistory
import ru.jux.launcher.core.Notices
import ru.jux.launcher.core.Paths
import ru.jux.launcher.core.Preloader
import ru.jux.launcher.core.Storage
import ru.jux.launcher.instance.CarryPlan
import ru.jux.launcher.launch.GameLauncher
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.servers.Nbt
import ru.jux.launcher.ui.App
import ru.jux.launcher.ui.LauncherState
import ru.jux.launcher.ui.Modal
import ru.jux.launcher.ui.theme.JuxTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.system.exitProcess

// Runs the carry-over flow end to end against a sandboxed user.home (set by the Gradle task):
// Play on a never-launched version -> dialog -> click "Перенести и играть" -> files copied -> launch goes on.
// Pass "x,y" (scene pixels of the primary button) as the second argument to click; without it the dialog is
// only rendered to <out>/carry-smoke-dialog.png so the coordinates can be read off it.

private const val WIDTH = 1040
private const val HEIGHT = 660
private const val SCALE = 1.25f
private const val FRAME_NANOS = 16_000_000L

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val out = File(args.first()).apply { mkdirs() }
    val click = args.getOrNull(1)?.split(',')?.map { it.trim().toFloat() }?.let { Offset(it[0], it[1]) }
    val realInstances = Path.of(System.getenv("USERPROFILE") ?: error("USERPROFILE not set")).resolve(".jux/instances")
    check(Paths.root.startsWith(Path.of(System.getProperty("user.home")))) { "not sandboxed: ${Paths.root}" }
    check(!Paths.root.startsWith(realInstances.parent)) { "refusing to run against the real ${Paths.root}" }
    Storage.deleteTree(Paths.root)
    Paths.ensureBaseDirs()
    PlayHistory.cacheFile = null
    PlayHistory.historyFile = null
    Notices.file = null

    var failed = false
    fun ok(what: String, condition: Boolean) {
        println((if (condition) "  ok   " else "  FAIL ") + what)
        if (!condition) failed = true
    }

    val source = Paths.instanceDir("26.2").createDirectories()
    val realOptions = realInstances.resolve("26.3-fabric/options.txt")
    val realServers = realInstances.resolve("1.21.11-fabric/servers.dat")
    Files.copy(realOptions, source.resolve("options.txt"))
    Files.copy(realServers, source.resolve("servers.dat"))
    source.resolve("resourcepacks").createDirectories().resolve("Test Pack.zip").writeText("pack")
    world(source, "Survival", "Выживание")
    world(source, "Creative", "Креатив")
    source.resolve("saves/Survival/session.lock").writeText("lock")
    source.resolve(GameLauncher.GAME_LOG).writeText("played")

    val preloaded = runBlocking { Preloader.run { _, _ -> } }
    val state = LauncherState(CoroutineScope(SupervisorJob() + Dispatchers.Default), preloaded)
    state.addOffline("Tester")

    println("1. First launch of 26.3: dialog, click, copy, launch continues")
    val entry = state.entryFor("26.3", LoaderKind.VANILLA)
    val target = state.gameDirOf(entry)
    state.selectEntry(entry)
    state.play()
    waitFor("the carry-over dialog") { state.modal is Modal.Carry }
    val modal = state.modal as Modal.Carry
    ok("dialog is the first-launch one", modal.firstLaunch)
    ok("26.2 is offered first", modal.sources.first().label == "26.2")
    val offered = modal.sources.first()
    ok("everything is offered: options, servers, packs, 2 worlds",
        offered.options && offered.servers > 0 && offered.packs.size == 1 && offered.worlds.size == 2)

    val scene = ImageComposeScene((WIDTH * SCALE).toInt(), (HEIGHT * SCALE).toInt(), Density(SCALE)) {
        JuxTheme { App(state, onGameStarted = {}) }
    }
    var time = 0L
    fun frames(count: Int) = repeat(count) { scene.render(time); time += FRAME_NANOS }
    frames(40)
    File(out, "carry-smoke-dialog.png").writeBytes(scene.render(time).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    if (click == null) {
        println("no click position given; dialog saved to ${File(out, "carry-smoke-dialog.png")}")
        scene.close()
        exitProcess(2)
    }
    scene.sendPointerEvent(PointerEventType.Move, click)
    frames(5)
    scene.sendPointerEvent(PointerEventType.Press, click, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
    frames(3)
    scene.sendPointerEvent(PointerEventType.Release, click, buttons = PointerButtons(), button = PointerButton.Primary)
    frames(10)
    ok("the click closed the dialog", state.modal == null)

    waitFor("the carry-over notice") { Notices.items.value.any { it.title == "Перенесено в 26.3" } }
    val notice = Notices.items.value.first { it.title == "Перенесено в 26.3" }
    println("     notice: ${notice.text}")
    ok("no error notices", Notices.items.value.none { it.level.name == "ERROR" })
    ok("options.txt copied line for line", target.resolve("options.txt").readLines().filter { ':' in it } ==
        realOptions.readLines().filter { ':' in it }.distinctBy { it.substringBefore(':') })
    ok("servers added", addresses(target).containsAll(addresses(source).filter { !it.contains("virtusmine") }))
    ok("resource pack copied", target.resolve("resourcepacks/Test Pack.zip").exists())
    ok("both worlds copied", listOf("Survival", "Creative").all { target.resolve("saves/$it/level.dat").exists() })
    ok("region file intact", target.resolve("saves/Survival/region/r.0.0.mca").fileSize() == 8192L)
    ok("session.lock left behind", !target.resolve("saves/Survival/session.lock").exists())
    ok("no temporary copies left", target.resolve("saves").listDirectoryEntries().none { it.name.startsWith(".") })
    ok("source untouched", source.resolve("saves/Survival/session.lock").exists())
    waitFor("the launch to go on after copying") { state.stage.startsWith("Чтение версии") || state.progress != null && !state.stage.startsWith("Переношу") }
    println("     stage after copying: ${state.stage}")
    state.cancel()
    frames(5)
    scene.close()

    println("2. Closing the dialog cancels the launch")
    val entry21 = state.entryFor("26.1", LoaderKind.VANILLA)
    state.selectEntry(entry21)
    state.play()
    waitFor("the dialog for 26.1") { state.modal is Modal.Carry }
    state.dismissCarry()
    waitFor("the job to end") { !state.busy }
    ok("nothing copied into 26.1", !state.gameDirOf(entry21).resolve("options.txt").exists())

    println("3. \"Не переносить\" launches without copying")
    state.play()
    waitFor("the dialog for 26.1 again") { state.modal is Modal.Carry }
    state.answerCarry((state.modal as Modal.Carry).sources.first(), CarryPlan.NONE)
    waitFor("the launch to start") { state.stage.startsWith("Чтение версии") || state.progress != null }
    ok("nothing copied", !state.gameDirOf(entry21).resolve("options.txt").exists() && !state.gameDirOf(entry21).resolve("saves").exists())
    state.cancel()

    println("4. A version that has run before is not asked about")
    target.resolve(GameLauncher.GAME_LOG).writeText("played")
    state.selectEntry(entry)
    state.play()
    waitFor("the launch to start") { state.modal != null || state.stage.startsWith("Чтение версии") || state.progress != null }
    ok("no dialog", state.modal == null)
    state.cancel()

    println("5. Manual carry-over from the menu skips worlds that are already there")
    state.offerCarryInto(entry)
    waitFor("the manual dialog") { state.modal is Modal.Carry }
    val manual = state.modal as Modal.Carry
    ok("manual dialog", !manual.firstLaunch)
    val again = manual.sources.first { it.label == "26.2" }
    ok("worlds already copied are marked present", again.worlds.all { it.present })
    ok("pack already copied is not offered", again.packs.isEmpty())
    state.carryInto(entry, again, CarryPlan(options = true))
    waitFor("the manual copy") { !state.busy }
    ok("still no error notices", Notices.items.value.none { it.level.name == "ERROR" })

    println("6. After a manual carry-over into a never-launched version, its first launch does not ask again")
    val fresh = state.entryFor("1.21.4", LoaderKind.VANILLA)
    state.offerCarryInto(fresh)
    waitFor("the manual dialog for 1.21.4") { state.modal is Modal.Carry }
    val everything = (state.modal as Modal.Carry).sources.first { it.label == "26.2" }
    state.carryInto(fresh, everything, CarryPlan(true, true, true, everything.worlds.map { it.folder }.toSet()))
    waitFor("the manual copy into 1.21.4") { !state.busy }
    state.selectEntry(fresh)
    state.play()
    waitFor("the launch of 1.21.4") { state.modal != null || state.stage.startsWith("Чтение версии") || state.progress != null }
    ok("no dialog on the first launch", state.modal == null)
    state.cancel()

    println(if (failed) "CARRY SMOKE FAILED" else "CARRY SMOKE PASSED")
    exitProcess(if (failed) 1 else 0)
}

private fun waitFor(what: String, timeoutMillis: Long = 60_000, condition: () -> Boolean) {
    val until = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
        Thread.sleep(50)
    }
}

private fun world(gameDir: Path, folder: String, name: String) {
    val dir = gameDir.resolve("saves").resolve(folder).createDirectories()
    val data = Nbt.CompoundTag(
        mapOf(
            "LevelName" to Nbt.StringTag(name),
            "Version" to Nbt.CompoundTag(mapOf("Name" to Nbt.StringTag("26.2"))),
        ),
    )
    val bytes = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(Nbt.writeRoot(Nbt.CompoundTag(mapOf("Data" to data)))) }
    }.toByteArray()
    dir.resolve("level.dat").writeBytes(bytes)
    dir.resolve("region").createDirectories().resolve("r.0.0.mca").writeBytes(ByteArray(8192))
}

private fun addresses(gameDir: Path): List<String> {
    val file = gameDir.resolve("servers.dat")
    if (!file.exists()) return emptyList()
    return ((Nbt.readRoot(file.readBytes()).entries["servers"] as? Nbt.ListTag)?.items.orEmpty())
        .mapNotNull { ((it as Nbt.CompoundTag).entries["ip"] as? Nbt.StringTag)?.value?.lowercase() }
}
