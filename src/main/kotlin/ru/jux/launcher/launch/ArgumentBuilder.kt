package ru.jux.launcher.launch

import ru.jux.launcher.auth.Account
import ru.jux.launcher.auth.AccountType
import ru.jux.launcher.core.LauncherSettings
import ru.jux.launcher.core.Paths
import ru.jux.launcher.install.InstalledVersion
import ru.jux.launcher.meta.Argument
import ru.jux.launcher.meta.RuleEnvironment
import ru.jux.launcher.servers.ServerAddress
import ru.jux.launcher.servers.SrvResolver
import java.io.File
import java.nio.file.Path

class ArgumentBuilder(
    private val installed: InstalledVersion,
    private val account: Account,
    private val settings: LauncherSettings,
    private val javaExecutable: Path,
    private val gameDir: Path,
    private val serverAddress: String? = null,
) {

    private val version = installed.json

    private val quickPlayJoin: Boolean = !serverAddress.isNullOrBlank() &&
        version.arguments?.game.orEmpty().any { argument ->
            argument is Argument.Conditional && argument.rules.any { "is_quick_play_multiplayer" in it.features }
        }

    private val env: RuleEnvironment = RuleEnvironment.current.withFeatures(
        "is_demo_user" to false,
        "has_custom_resolution" to false,
        "has_quick_plays_support" to false,
        "is_quick_play_singleplayer" to false,
        "is_quick_play_multiplayer" to quickPlayJoin,
        "is_quick_play_realms" to false,
    )

    private val classpath: String =
        installed.classpath.distinct().joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }

    fun build(): List<String> = buildList {
        add(javaExecutable.toAbsolutePath().toString())
        addAll(jvmArguments())
        add(version.mainClass)
        addAll(gameArguments())
    }

    private fun jvmArguments(): List<String> = buildList {
        add("-Xmx${settings.memoryMb}M")
        add("-Xms${(settings.memoryMb / 2).coerceAtLeast(512)}M")

        add("-Dlog4j2.formatMsgNoLookups=true")

        add("-XX:-HeapDumpOnOutOfMemoryError")

        installed.logConfig?.let { config ->
            val argument = installed.logConfigArgument ?: "-Dlog4j.configurationFile=\${path}"
            add(argument.replace("\${path}", config.toAbsolutePath().toString()))
        }

        settings.jvmArgs.split(' ').filter { it.isNotBlank() }.forEach { add(it) }

        val fromManifest = version.arguments?.jvm.orEmpty()
            .flatMap { it.resolve(env) }
            .map { template(it) }

        if (fromManifest.isEmpty()) {
            add("-Djava.library.path=${installed.nativesDir.toAbsolutePath()}")
            add("-cp")
            add(classpath)
        } else {
            addAll(fromManifest)
        }
    }

    private fun gameArguments(): List<String> = buildList {
        val structured = version.arguments?.game.orEmpty()
        if (structured.isNotEmpty()) {
            structured.forEach { argument ->
                argument.resolve(env).forEach { add(template(it)) }
            }
        } else {
            version.minecraftArguments.orEmpty()
                .split(' ')
                .filter { it.isNotBlank() }
                .forEach { add(template(it)) }
        }

        if (!quickPlayJoin) {
            serverAddress?.let { ServerAddress.parse(it) }?.let { address ->
                val target = if (address.port == null) SrvResolver.resolve(address.host) ?: address else address
                add("--server"); add(target.host)
                add("--port"); add(target.effectivePort.toString())
            }
        }
    }

    private fun template(raw: String): String {
        if (!raw.contains("\${")) return raw
        var result = raw
        for ((key, value) in replacements) {
            result = result.replace("\${$key}", value)
        }
        return result
    }

    private val replacements: Map<String, String> by lazy {
        mapOf(
            "natives_directory" to installed.nativesDir.toAbsolutePath().toString(),
            "launcher_name" to LAUNCHER_NAME,
            "launcher_version" to LAUNCHER_VERSION,
            "classpath" to classpath,
            "classpath_separator" to File.pathSeparator,
            "library_directory" to Paths.libraries.toAbsolutePath().toString(),
            "version_name" to version.id,
            "auth_player_name" to account.name,
            "game_directory" to gameDir.toAbsolutePath().toString(),
            "assets_root" to installed.assetsDir.toAbsolutePath().toString(),
            "game_assets" to installed.assetsDir.toAbsolutePath().toString(),
            "assets_index_name" to installed.assetIndexId,
            "auth_uuid" to account.uuid,
            "auth_access_token" to account.accessToken,
            "auth_session" to "token:${account.accessToken}:${account.uuid}",
            "auth_xuid" to "",
            "clientid" to "",
            "user_type" to if (account.type == AccountType.MICROSOFT) "msa" else "legacy",
            "version_type" to version.type,
            "user_properties" to "{}",
            "resolution_width" to "854",
            "resolution_height" to "480",
            "quickPlayPath" to "",
            "quickPlaySingleplayer" to "",
            "quickPlayMultiplayer" to serverAddress?.trim().orEmpty(),
            "quickPlayRealms" to "",
        )
    }

    companion object {
        const val LAUNCHER_NAME = "JuxLauncher"
        const val LAUNCHER_VERSION = "1.6.1"
    }
}
