import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.security.MessageDigest
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

group = "ru.jux"
version = "1.8.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

val isWindows = System.getProperty("os.name").lowercase().contains("win")
val copyrightLine = "Copyright © 2026 Jux"

// Заставка обновления собирается в обычный exe и ставится вместе с лаунчером (app/resources), чтобы
// на машине игрока не запускать PowerShell и не компилировать код на лету — так делают вирусы, и Defender это видит.
val appResources = layout.buildDirectory.dir("generated/appResources")
val compileUpdateSplash = tasks.register("compileUpdateSplash") {
    group = "build"
    description = "Compiles src/main/csharp into JuxUpdate.exe, the splash shown while an update installs"
    val sources = fileTree("src/main/csharp") { include("*.cs") }
    val icon = file("branding/JuxLauncher.ico")
    val numeric = project.version.toString().substringBefore('-')
    val exe = appResources.map { it.file("windows/JuxUpdate.exe") }
    inputs.files(sources)
    inputs.file(icon)
    inputs.property("version", project.version.toString())
    inputs.property("copyright", copyrightLine)
    outputs.file(exe)
    onlyIf { isWindows }
    doLast {
        val csc = File(System.getenv("WINDIR") ?: "C:\\Windows", "Microsoft.NET/Framework64/v4.0.30319/csc.exe")
        check(csc.exists()) { "No .NET Framework compiler at $csc" }
        val info = temporaryDir.resolve("AssemblyInfo.cs")
        info.writeText(
            """
            using System.Reflection;
            [assembly: AssemblyTitle("JuxLauncher Update")]
            [assembly: AssemblyDescription("Shows progress while JuxLauncher installs its update")]
            [assembly: AssemblyCompany("Jux")]
            [assembly: AssemblyProduct("JuxLauncher")]
            [assembly: AssemblyCopyright("$copyrightLine")]
            [assembly: AssemblyVersion("$numeric.0")]
            [assembly: AssemblyFileVersion("$numeric.0")]
            [assembly: AssemblyInformationalVersion("${project.version}")]
            """.trimIndent()
        )
        val out = exe.get().asFile.also { it.parentFile.mkdirs() }
        val command = listOf(
            csc.absolutePath, "/nologo", "/target:winexe", "/optimize+", "/codepage:65001",
            "/win32icon:${icon.absolutePath}", "/r:System.Windows.Forms.dll", "/r:System.Drawing.dll", "/out:${out.absolutePath}",
        ) + sources.files.sortedBy { it.name }.map { it.absolutePath } + info.absolutePath
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0 && out.exists()) { "csc failed:\n$output" }
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("user.home", temporaryDir.absolutePath)
    systemProperty("user.language", "ru")
    dependsOn(compileUpdateSplash)
    systemProperty("compose.application.resources.dir", appResources.get().dir("windows").asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "ru.jux.launcher.MainKt"

        jvmArgs += listOf(
            "-Xms24m",
            "-Xmx384m",
            "-XX:+UseSerialGC",
            // Освобождённую после установки или запуска игры кучу SerialGC отдаёт Windows сразу, а не по 10–40% за сборку
            "-XX:-ShrinkHeapInSteps",
            "-XX:MinHeapFreeRatio=20",
            "-XX:MaxHeapFreeRatio=50",
            "-XX:MaxMetaspaceSize=192m",
            "-Dfile.encoding=UTF-8",
            "-Dsun.java2d.dpiaware=true",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "JuxLauncher"
            packageVersion = project.version.toString()
            vendor = "Jux"
            description = "JuxLauncher Minecraft Launcher"
            copyright = copyrightLine
            appResourcesRootDir.set(appResources)

            modules(
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.prefs",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.management",
                "jdk.naming.dns",
                "jdk.unsupported",
                "jdk.zipfs",
            )

            windows {
                menuGroup = "Jux"
                menu = true
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "9F1D3B2A-7C4E-4E8B-9A21-5D6E8C0F3B71"
                console = false
                iconFile.set(project.file("branding/JuxLauncher.ico"))
            }
        }

        buildTypes.release.proguard {
            version.set("7.7.0")
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("compose-desktop.pro"))
        }
    }
}

tasks.register<JavaExec>("renderScreens") {
    group = "verification"
    description = "Renders the launcher screens to PNG files in build/preview"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.jux.launcher.dev.RenderScreensKt")
    args(layout.buildDirectory.dir("preview").get().asFile.absolutePath)
    // Рендер создаёт сборки, миры и меняет настройки — по умолчанию только в песочнице, не в настоящей ~/.jux
    systemProperty("user.home", findProperty("renderHome") as String? ?: layout.buildDirectory.dir("render-home").get().asFile.absolutePath)
}

tasks.register<JavaExec>("smokeLoaders") {
    group = "verification"
    description = "Installs each version:loader from -Ptargets and starts the game up to the main menu"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.jux.launcher.dev.LoaderSmokeKt")
    (findProperty("smokeHome") as String?)?.let { systemProperty("user.home", it) }
    if (project.hasProperty("prepareOnly")) systemProperty("smoke.prepareOnly", "true")
    args((findProperty("targets") as String? ?: "").split(',').filter { it.isNotBlank() })
}

tasks.register<JavaExec>("smokeCarryOver") {
    group = "verification"
    description = "Runs the carry-over flow end to end in a sandboxed home; -Pclick=x,y clicks the dialog's main button"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.jux.launcher.dev.CarrySmokeKt")
    systemProperty("user.home", layout.buildDirectory.dir("carry-smoke-home").get().asFile.absolutePath)
    args(listOfNotNull(layout.buildDirectory.dir("preview").get().asFile.absolutePath, findProperty("click") as String?))
}

val desktopInstallDir = File(System.getProperty("user.home"), "JuxLauncher")

val deployDesktop = tasks.register("deployDesktop") {
    group = "distribution"
    description = "Replaces the desktop copy of the launcher with the latest build"
    dependsOn("createDistributable")
    onlyIf { System.getProperty("os.name").lowercase().contains("win") && !project.hasProperty("noDeploy") }
    doLast {
        val built = layout.buildDirectory.dir("compose/binaries/main/app/JuxLauncher").get().asFile
        check(built.resolve("JuxLauncher.exe").exists()) { "No distributable in $built" }

        val running = ProcessHandle.allProcesses()
            .filter { process ->
                process.info().command()
                    .map { it.startsWith(desktopInstallDir.absolutePath, ignoreCase = true) }
                    .orElse(false)
            }
            .map { it.pid() }
            .toList()
        if (running.isNotEmpty()) {
            throw GradleException(
                "JuxLauncher from $desktopInstallDir is running (PID ${running.joinToString()}): " +
                    "close it and run `gradlew deployDesktop`"
            )
        }

        val icon = compose.desktop.application.nativeDistributions.windows.iconFile.get().asFile
        val iconHash = MessageDigest.getInstance("SHA-1").digest(icon.readBytes()).joinToString("") { "%02x".format(it) }
        val shortcutIcon = "JuxLauncher-${iconHash.take(10)}.ico"
        val iconChanged = !desktopInstallDir.resolve(shortcutIcon).exists()

        val staging = File(desktopInstallDir.parentFile, "${desktopInstallDir.name}.new")
        val previous = File(desktopInstallDir.parentFile, "${desktopInstallDir.name}.old")
        forceDelete(staging)
        forceDelete(previous)
        built.copyRecursively(staging)
        icon.copyTo(staging.resolve(shortcutIcon))
        if (desktopInstallDir.exists()) {
            check(desktopInstallDir.renameTo(previous)) { "Could not move the old $desktopInstallDir aside" }
        }
        check(staging.renameTo(desktopInstallDir)) { "Could not put the new build in $desktopInstallDir" }
        forceDelete(previous)

        ensureDesktopShortcut(
            desktopInstallDir.resolve("JuxLauncher.exe"),
            desktopInstallDir.resolve(shortcutIcon),
            rewrite = iconChanged,
        )
        if (iconChanged) logger.lifecycle("New icon: the desktop shortcut now takes it from $shortcutIcon")
        logger.lifecycle("JuxLauncher deployed to $desktopInstallDir")
    }
}

fun forceDelete(dir: File) {
    if (!dir.exists()) return
    dir.walkBottomUp().forEach { it.setWritable(true) }
    check(dir.deleteRecursively()) { "Could not delete $dir" }
}

fun ensureDesktopShortcut(exe: File, icon: File, rewrite: Boolean) {
    val desktops = listOfNotNull(System.getenv("USERPROFILE"), System.getenv("OneDrive")).map { File(it, "Desktop") }
    val existing = desktops.asSequence()
        .flatMap { it.walk().maxDepth(2) }
        .firstOrNull { it.isFile && it.name.equals("JuxLauncher.lnk", ignoreCase = true) }
    if (!rewrite && existing != null) return

    fun literal(value: String) = "'" + value.replace("'", "''") + "'"
    val script = listOf(
        "\$path = " + (existing?.let { literal(it.absolutePath) } ?: "Join-Path ([Environment]::GetFolderPath('Desktop')) 'JuxLauncher.lnk'"),
        "\$link = (New-Object -ComObject WScript.Shell).CreateShortcut(\$path)",
        "\$link.TargetPath = ${literal(exe.absolutePath)}",
        "\$link.WorkingDirectory = ${literal(exe.parent)}",
        "\$link.IconLocation = ${literal(icon.absolutePath + ",0")}",
        "\$link.Save()",
    ).joinToString("\n")
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    val process = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "Desktop shortcut not written: $output" }
}

afterEvaluate {
    tasks.named("createRuntimeImage") {
        doLast {
            val image = layout.buildDirectory.dir("compose/tmp/main/runtime").get().asFile
            val borrowed = image.resolve("bin/java.exe")
            File(compose.desktop.application.javaHome, "bin/java.exe").copyTo(borrowed, overwrite = true)
            try {
                val dump = ProcessBuilder(borrowed.absolutePath, "-Xshare:dump")
                    .redirectErrorStream(true)
                    .start()
                val output = dump.inputStream.bufferedReader().readText()
                check(dump.waitFor() == 0) { "CDS dump failed:\n$output" }
            } finally {
                borrowed.delete()
            }
            check(image.resolve("bin/server/classes.jsa").exists()) { "CDS dump produced no archive" }
        }
    }

    val classArchiveKey = provider {
        val digest = MessageDigest.getInstance("SHA-1")
        (listOf(tasks.jar.get().archiveFile.get().asFile) + configurations.runtimeClasspath.get().files.sortedBy { it.name })
            .forEach { digest.update(it.readBytes()) }
        digest.digest().joinToString("") { "%02x".format(it) }.take(10)
    }

    tasks.withType<AbstractJPackageTask>().configureEach {
        launcherJvmArgs.addAll(classArchiveKey.map { key ->
            listOf("-XX:SharedArchiveFile=\$APPDIR\\\\jux-$key.jsa", "-XX:+AutoCreateSharedArchive")
        })
    }

    tasks.named("prepareAppResources") { dependsOn(compileUpdateSplash) }
    tasks.named("createDistributable") { finalizedBy(deployDesktop) }
}

tasks.register("scanWithDefender") {
    group = "verification"
    description = "Scans the built installers and app images with Microsoft Defender and fails if it finds anything"
    onlyIf { isWindows }
    doLast {
        val platforms = File(System.getenv("ProgramData") ?: "C:\\ProgramData", "Microsoft/Windows Defender/Platform")
        val mpCmdRun = (platforms.listFiles().orEmpty().sortedByDescending { it.name }.map { it.resolve("MpCmdRun.exe") } +
            File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "Windows Defender/MpCmdRun.exe"))
            .firstOrNull { it.exists() }
            ?: throw GradleException("MpCmdRun.exe not found: is Microsoft Defender installed?")

        val targets = listOf(
            "compose/binaries/main-release/msi",
            "compose/binaries/main-release/exe",
            "compose/binaries/main-release/app",
            "compose/binaries/main/app",
            "generated/appResources",
        ).map { layout.buildDirectory.dir(it).get().asFile }.filter { it.exists() }
        if (targets.isEmpty()) throw GradleException("Nothing to scan: run packageReleaseMsi or createDistributable first")

        targets.forEach { target ->
            val process = ProcessBuilder(mpCmdRun.absolutePath, "-Scan", "-ScanType", "3", "-File", target.absolutePath, "-DisableRemediation")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            when (val code = process.waitFor()) {
                0 -> logger.lifecycle("Defender: clean — $target")
                2 -> throw GradleException("Defender found threats in $target:\n$output")
                else -> throw GradleException("Defender scan of $target failed with code $code:\n$output")
            }
        }
    }
}
