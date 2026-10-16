package ru.jux.launcher.link

import ru.jux.launcher.mods.ContentKind
import java.net.URLDecoder

enum class LinkContent(val key: String, val content: ContentKind?) {
    MOD("mod", ContentKind.MOD),
    SHADER("shader", ContentKind.SHADER),
    RESOURCE_PACK("resourcepack", ContentKind.RESOURCE_PACK),
    DATAPACK("datapack", null),
    MODPACK("modpack", null);

    fun page(project: String): String = "https://modrinth.com/$key/$project"
}

sealed interface LaunchLink {

    data object Open : LaunchLink

    /** [minVersion] — нижний край диапазона сервера «1.8–26.3», [version] — верхний. */
    data class Join(
        val address: String,
        val version: String?,
        val name: String?,
        val minVersion: String? = null,
    ) : LaunchLink {
        val title: String get() = name ?: address

        /** «1.8–26.3» или просто версия сервера. */
        val range: String? get() = version?.let { top -> minVersion?.let { "$it–$top" } ?: top }

        /** Пускает ли сервер с версии [id]: она основная или внутри диапазона (по major.minor). */
        fun accepts(id: String): Boolean {
            if (id == version) return true
            val low = family(minVersion ?: return false) ?: return false
            val high = family(version ?: return false) ?: return false
            val own = family(id) ?: return false
            return compareFamilies(low, own) <= 0 && compareFamilies(own, high) <= 0
        }
    }

    data class Install(
        val project: String,
        val content: LinkContent,
        val version: String?,
        val loader: String?,
        val name: String?,
    ) : LaunchLink {
        val title: String get() = name ?: project
    }

    companion object {
        const val SCHEME = "juxlauncher"

        private const val MAX_LENGTH = 2048
        private const val MAX_NAME = 64
        private val HOST_PORT = Regex("""^[A-Za-z0-9][A-Za-z0-9.-]{0,252}(?::(\d{1,5}))?$""")
        private val SLUG = Regex("""^[A-Za-z0-9_-]{1,64}$""")
        private val VERSION = Regex("""^[A-Za-z0-9][A-Za-z0-9._+-]{0,47}$""")
        private val LOADER = Regex("""^[a-z0-9_-]{1,32}$""")
        private val SPACES = Regex("""\s+""")

        fun find(args: List<String>): LaunchLink? = args.firstOrNull { it.startsWith("$SCHEME:") }?.let(::parse)

        fun parse(raw: String): LaunchLink? {
            val prefix = "$SCHEME://"
            if (!raw.startsWith(prefix) || raw.length > MAX_LENGTH) return null
            val rest = raw.substring(prefix.length).substringBefore('#')
            val params = parameters(rest.substringAfter('?', "")) ?: return null
            return when (rest.substringBefore('?').removeSuffix("/")) {
                "join" -> join(params)
                "install" -> install(params)
                else -> Open
            }
        }

        private fun join(params: Map<String, String>): Join? {
            val address = params["address"]?.takeIf(::isAddress) ?: return null
            val version = version(params)
            // Минимум без верхней версии смысла не имеет и не бывает выше неё
            val min = params["min"]?.takeIf { VERSION.matches(it) && isBelow(it, version) }
            return Join(address, version, params["name"]?.let(::cleanName), min)
        }

        private fun install(params: Map<String, String>): Install? {
            val project = params["project"]?.takeIf { SLUG.matches(it) } ?: return null
            val content = LinkContent.entries.firstOrNull { it.key == params["kind"] } ?: return null
            return Install(
                project = project,
                content = content,
                version = version(params),
                loader = params["loader"]?.lowercase()?.takeIf { LOADER.matches(it) },
                name = params["name"]?.let(::cleanName),
            )
        }

        private fun version(params: Map<String, String>): String? = params["version"]?.takeIf { VERSION.matches(it) }

        private val FAMILY = Regex("""^(\d+)\.(\d+)""")

        /** major.minor версии игры числами; у снапшотов вроде 25w14a — null. */
        internal fun family(id: String): Pair<Int, Int>? =
            FAMILY.find(id)?.destructured?.let { (major, minor) -> major.toInt() to minor.toInt() }

        private fun isBelow(low: String, high: String?): Boolean {
            val l = family(low) ?: return false
            val h = family(high ?: return false) ?: return false
            return compareFamilies(l, h) < 0
        }

        internal fun compareFamilies(a: Pair<Int, Int>, b: Pair<Int, Int>): Int =
            compareValuesBy(a, b, { it.first }, { it.second })

        internal fun isAddress(text: String): Boolean {
            val match = HOST_PORT.matchEntire(text) ?: return false
            val port = match.groupValues[1]
            return port.isEmpty() || port.toInt() in 1..65535
        }

        internal fun cleanName(text: String): String? =
            text.replace(SPACES, " ")
                .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
                .trim()
                .take(MAX_NAME)
                .trimEnd()
                .ifEmpty { null }

        private fun parameters(query: String): Map<String, String>? {
            val params = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val name = decode(pair.substringBefore('=')) ?: return null
                val value = decode(pair.substringAfter('=', "")) ?: return null
                params.putIfAbsent(name, value)
            }
            return params
        }

        private fun decode(text: String): String? =
            runCatching { URLDecoder.decode(text, Charsets.UTF_8) }.getOrNull()
    }
}
