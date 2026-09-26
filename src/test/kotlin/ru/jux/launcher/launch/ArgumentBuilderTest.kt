package ru.jux.launcher.launch

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.jux.launcher.auth.Account
import ru.jux.launcher.core.LauncherSettings
import ru.jux.launcher.install.InstalledVersion
import ru.jux.launcher.meta.Argument
import ru.jux.launcher.meta.Arguments
import ru.jux.launcher.meta.Rule
import ru.jux.launcher.meta.VersionJson
import java.nio.file.Path

class ArgumentBuilderTest {

    private val modern = VersionJson(
        id = "26.3",
        arguments = Arguments(
            game = listOf(
                Argument.Literal("--username"),
                Argument.Literal("\${auth_player_name}"),
                Argument.Conditional(
                    rules = listOf(Rule(features = mapOf("is_quick_play_multiplayer" to true))),
                    values = listOf("--quickPlayMultiplayer", "\${quickPlayMultiplayer}"),
                ),
            ),
            jvm = listOf(Argument.Literal("-cp"), Argument.Literal("\${classpath}")),
        ),
    )

    private val legacy = VersionJson(
        id = "1.12.2",
        minecraftArguments = "--username \${auth_player_name} --version \${version_name}",
    )

    private fun command(version: VersionJson, server: String?): List<String> =
        ArgumentBuilder(
            installed = InstalledVersion(
                json = version,
                clientJar = Path.of("client.jar"),
                classpath = listOf(Path.of("lib.jar")),
                nativesDir = Path.of("natives"),
                assetsDir = Path.of("assets"),
                assetIndexId = "1",
                logConfig = null,
                logConfigArgument = null,
            ),
            account = Account.offline("Tester"),
            settings = LauncherSettings(),
            javaExecutable = Path.of("java"),
            gameDir = Path.of("game"),
            serverAddress = server,
        ).build()

    @Test
    fun `a version with quick play joins through it`() {
        val args = command(modern, "play.example.net")
        val at = args.indexOf("--quickPlayMultiplayer")
        assertTrue(at >= 0)
        assertTrue(args[at + 1] == "play.example.net")
        assertFalse("--server" in args)
    }

    @Test
    fun `an older version gets server and port`() {
        val args = command(legacy, "127.0.0.1:25566")
        val at = args.indexOf("--server")
        assertTrue(at >= 0)
        assertTrue(args.subList(at, at + 4) == listOf("--server", "127.0.0.1", "--port", "25566"))
    }

    @Test
    fun `without a server nothing is added`() {
        assertFalse("--quickPlayMultiplayer" in command(modern, null))
        assertFalse("--server" in command(legacy, null))
    }
}
