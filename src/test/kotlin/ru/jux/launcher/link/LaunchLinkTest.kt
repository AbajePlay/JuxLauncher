package ru.jux.launcher.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaunchLinkTest {

    private fun parse(text: String) = LaunchLink.parse(text)

    @Test
    fun `a join link carries the address, version and name`() {
        assertEquals(
            LaunchLink.Join("mc.example.org", "1.21.4", "Test"),
            parse("juxlauncher://join?address=mc.example.org&version=1.21.4&name=Test"),
        )
        assertEquals(
            LaunchLink.Join("play.example.org:25570", null, null),
            parse("juxlauncher://join/?address=play.example.org%3A25570"),
        )
    }

    @Test
    fun `a join link may carry the lowest version the server lets in`() {
        val link = parse("juxlauncher://join?address=mc.example.org&version=26.3&min=1.8&name=Test") as LaunchLink.Join
        assertEquals(LaunchLink.Join("mc.example.org", "26.3", "Test", "1.8"), link)
        assertEquals("1.8–26.3", link.range)
        assertTrue(link.accepts("1.8.9"))
        assertTrue(link.accepts("1.21.11"))
        assertTrue(link.accepts("26.3"))
        assertFalse(link.accepts("1.7.10"))
        assertFalse(link.accepts("26.4"))
        assertFalse(link.accepts("25w14a"))

        // Минимум не выше верхней версии и не без неё
        listOf("min=26.3&version=26.3", "min=26.4&version=26.3", "min=1.8", "min=x&version=26.3").forEach { query ->
            assertNull((parse("juxlauncher://join?address=a.org&$query") as LaunchLink.Join).minVersion, query)
        }
        val single = parse("juxlauncher://join?address=a.org&version=1.21") as LaunchLink.Join
        assertEquals("1.21", single.range)
        assertTrue(single.accepts("1.21"))
        assertFalse(single.accepts("1.21.11"))
    }

    @Test
    fun `parameters come in any order, extra ones are ignored and names are decoded`() {
        assertEquals(
            LaunchLink.Join("mc.virtusmine.fun", "26.3", "Virtus Mine: выживание"),
            parse("juxlauncher://join?utm=site&name=Virtus+Mine%3A%20%D0%B2%D1%8B%D0%B6%D0%B8%D0%B2%D0%B0%D0%BD%D0%B8%D0%B5&version=26.3&address=mc.virtusmine.fun#top"),
        )
    }

    @Test
    fun `an install link names the project, its kind and what it needs`() {
        assertEquals(
            LaunchLink.Install("sodium", LinkContent.MOD, "1.21.4", "fabric", "Sodium"),
            parse("juxlauncher://install?project=sodium&kind=mod&version=1.21.4&loader=Fabric&name=Sodium"),
        )
        assertEquals(
            LaunchLink.Install("AANobbMI", LinkContent.SHADER, null, null, null),
            parse("juxlauncher://install?kind=shader&project=AANobbMI"),
        )
        listOf("resourcepack", "datapack", "modpack").forEach { kind ->
            assertEquals(kind, (parse("juxlauncher://install?project=p&kind=$kind") as LaunchLink.Install).content.key)
        }
    }

    @Test
    fun `an unknown action just opens the launcher`() {
        assertEquals(LaunchLink.Open, parse("juxlauncher://"))
        assertEquals(LaunchLink.Open, parse("juxlauncher://settings?tab=java"))
    }

    @Test
    fun `only the juxlauncher scheme is accepted`() {
        assertNull(parse("https://juxmc.ru/join?address=mc.example.org"))
        assertNull(parse("JUXLAUNCHER://join?address=mc.example.org"))
        assertNull(parse("juxlauncher:join?address=mc.example.org"))
        assertNull(parse("xjuxlauncher://join?address=mc.example.org"))
        assertNull(parse("juxlauncher://join?address=" + "a".repeat(3000)))
    }

    @Test
    fun `a join without a valid address is ignored`() {
        listOf(
            "",
            "address=",
            "address=-mc.example.org",
            "address=mc.example.org:0",
            "address=mc.example.org:65536",
            "address=mc.example.org:25565:1",
            "address=mc.example.org%2Fpath",
            "address=mc.example.org%20--demo",
            "address=..%5C..%5Cwindows",
            "address=%5B::1%5D:25565",
            "address=http://mc.example.org",
            "address=mc.example.org%00",
            "address=%E0%A4%A",
        ).forEach { query -> assertNull(parse("juxlauncher://join?$query"), query) }
        assertEquals("mc.example.org:65535", (parse("juxlauncher://join?address=mc.example.org:65535") as LaunchLink.Join).address)
    }

    @Test
    fun `an install with a bad project or kind is ignored`() {
        listOf(
            "kind=mod",
            "project=sodium",
            "project=sodium&kind=plugin",
            "project=sodium&kind=MOD",
            "project=..%2F..%2Fversion&kind=mod",
            "project=sod ium&kind=mod",
            "project=${"a".repeat(65)}&kind=mod",
            "project=https://evil.example&kind=mod",
        ).forEach { query -> assertNull(parse("juxlauncher://install?$query"), query) }
        assertEquals("a".repeat(64), (parse("juxlauncher://install?project=${"a".repeat(64)}&kind=mod") as LaunchLink.Install).project)
    }

    @Test
    fun `a broken optional field is dropped, not trusted`() {
        val link = parse("juxlauncher://install?project=iris&kind=shader&version=..%2F..%2Fx&loader=fab%20ric&name=%E2%80%AE%01") as LaunchLink.Install
        assertNull(link.version)
        assertNull(link.loader)
        assertNull(link.name)
        assertEquals("iris", link.title)
        assertNull((parse("juxlauncher://join?address=mc.example.org&version=1.21%20.4") as LaunchLink.Join).version)
    }

    @Test
    fun `names are cleaned for display`() {
        assertEquals("Сервер друзей", LaunchLink.cleanName("  Сервер\n\tдрузей ‮ "))
        assertEquals(64, LaunchLink.cleanName("x".repeat(200))?.length)
        assertNull(LaunchLink.cleanName(" ​ "))
    }

    @Test
    fun `addresses are host and port only`() {
        assertTrue(LaunchLink.isAddress("mc.example.org"))
        assertTrue(LaunchLink.isAddress("127.0.0.1:25565"))
        assertTrue(LaunchLink.isAddress("my-server.ru:1"))
        assertFalse(LaunchLink.isAddress("mc.example.org:"))
        assertFalse(LaunchLink.isAddress("mc_example.org"))
        assertFalse(LaunchLink.isAddress("user@mc.example.org"))
    }

    @Test
    fun `the link is found among the start arguments`() {
        assertEquals(
            LaunchLink.Join("mc.example.org", null, null),
            LaunchLink.find(listOf("--minimized", "juxlauncher://join?address=mc.example.org")),
        )
        assertNull(LaunchLink.find(listOf("--play", "26.3")))
        assertNull(LaunchLink.find(emptyList()))
    }

    @Test
    fun `datapack and modpack pages live under their own type on Modrinth`() {
        assertEquals("https://modrinth.com/datapack/terralith", LinkContent.DATAPACK.page("terralith"))
        assertEquals("https://modrinth.com/modpack/fabulously-optimized", LinkContent.MODPACK.page("fabulously-optimized"))
    }
}
