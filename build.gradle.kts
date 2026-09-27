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
version = "1.2.1"

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

tasks.test {
    useJUnitPlatform()
    systemProperty("user.home", temporaryDir.absolutePath)
}

compose.desktop {
    application {
        mainClass = "ru.jux.launcher.MainKt"

        jvmArgs += listOf(
            "-Xms24m",
            "-Xmx384m",
            "-XX:+UseSerialGC",
            "-XX:MaxMetaspaceSize=192m",
            "-Dfile.encoding=UTF-8",
            "-Dsun.java2d.dpiaware=true",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "JuxLauncher"
            packageVersion = project.version.toString()
            vendor = "Jux"
            description = "Minecraft launcher"

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
}

tasks.register<JavaExec>("smokeLoaders") {
    group = "verification"
    description = "Installs each version:loader from -Ptargets and starts the game up to the main menu"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.jux.launcher.dev.LoaderSmokeKt")
    (findProperty("smokeHome") as String?)?.let { systemProperty("user.home", it) }
    args((findProperty("targets") as String? ?: "").split(',').filter { it.isNotBlank() })
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
    if (!rewrite && desktops.any { it.resolve("JuxLauncher.lnk").exists() }) return

    fun literal(value: String) = "'" + value.replace("'", "''") + "'"
    val script = listOf(
        "\$path = Join-Path ([Environment]::GetFolderPath('Desktop')) 'JuxLauncher.lnk'",
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

    tasks.named("createDistributable") { finalizedBy(deployDesktop) }
}
