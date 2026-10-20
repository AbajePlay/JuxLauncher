package ru.jux.launcher.link

import ru.jux.launcher.meta.GameVersions
import ru.jux.launcher.meta.LoaderKind
import ru.jux.launcher.mods.ContentKind
import java.net.URLDecoder
import java.net.URLEncoder

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
            val low = GameVersions.family(minVersion ?: return false) ?: return false
            val high = GameVersions.family(version ?: return false) ?: return false
            val own = GameVersions.family(id) ?: return false
            return GameVersions.compareFamilies(low, own) <= 0 && GameVersions.compareFamilies(own, high) <= 0
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

    /** Сборка, которой поделился игрок: версия игры, загрузчик и точные версии файлов с Modrinth. */
    data class SharedPack(
        val name: String,
        val version: String,
        val loader: LoaderKind,
        val loaderVersion: String? = null,
        val mods: List<String> = emptyList(),
        val shaders: List<String> = emptyList(),
        val resourcePacks: List<String> = emptyList(),
    ) : LaunchLink {
        val versionIds: List<String> get() = (mods + shaders + resourcePacks).distinct()

        fun toUri(): String = uri(
            "pack",
            "name" to name,
            "version" to version,
            "loader" to loader.name.lowercase(),
            "lv" to loaderVersion,
            "mods" to mods.joinToString(",").ifEmpty { null },
            "shaders" to shaders.joinToString(",").ifEmpty { null },
            "resourcepacks" to resourcePacks.joinToString(",").ifEmpty { null },
        )
    }

    companion object {
        const val SCHEME = "juxlauncher"

        // Ссылка на сборку несёт по 9 символов на каждый файл, поэтому лимит с запасом
        const val MAX_LENGTH = 8192
        private const val MAX_FILES = 600
        private const val MAX_NAME = 64
        private val HOST_PORT = Regex("""^[A-Za-z0-9][A-Za-z0-9.-]{0,252}(?::(\d{1,5}))?$""")
        private val SLUG = Regex("""^[A-Za-z0-9_-]{1,64}$""")
        private val VERSION = Regex("""^[A-Za-z0-9][A-Za-z0-9._+-]{0,47}$""")
        private val LOADER = Regex("""^[a-z0-9_-]{1,32}$""")
        private val VERSION_ID = Regex("""^[A-Za-z0-9]{8}$""")
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
                "pack" -> pack(params)
                else -> Open
            }
        }

        fun installUri(project: String, content: LinkContent, name: String?): String =
            uri("install", "project" to project, "kind" to content.key, "name" to name)

        private fun uri(action: String, vararg params: Pair<String, String?>): String =
            params.mapNotNull { (key, value) -> value?.let { "$key=${encode(it)}" } }
                .joinToString("&", prefix = "$SCHEME://$action?")

        // Запятые в списках оставляем как есть: так ссылка короче и читается глазами
        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("%2C", ",")

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

        private fun pack(params: Map<String, String>): SharedPack? {
            val version = version(params) ?: return null
            val loader = LoaderKind.entries.firstOrNull { it.name.equals(params["loader"], ignoreCase = true) } ?: return null
            val mods = versionIds(params["mods"]) ?: return null
            val shaders = versionIds(params["shaders"]) ?: return null
            val resourcePacks = versionIds(params["resourcepacks"]) ?: return null
            if (mods.size + shaders.size + resourcePacks.size > MAX_FILES) return null
            if (mods.isNotEmpty() && !loader.hasMods) return null
            return SharedPack(
                name = params["name"]?.let(::cleanName) ?: "$version ${loader.label}",
                version = version,
                loader = loader,
                loaderVersion = params["lv"]?.takeIf { loader.isModded && VERSION.matches(it) },
                mods = mods,
                shaders = shaders,
                resourcePacks = resourcePacks,
            )
        }

        /** Id версий Modrinth через запятую; хоть один кривой — ссылка битая. */
        private fun versionIds(raw: String?): List<String>? {
            if (raw.isNullOrEmpty()) return emptyList()
            val ids = raw.split(',').filter { it.isNotEmpty() }
            return ids.takeIf { list -> list.all { VERSION_ID.matches(it) } }?.distinct()
        }

        private fun version(params: Map<String, String>): String? = params["version"]?.takeIf { VERSION.matches(it) }

        private fun isBelow(low: String, high: String?): Boolean {
            val l = GameVersions.family(low) ?: return false
            val h = GameVersions.family(high ?: return false) ?: return false
            return GameVersions.compareFamilies(l, h) < 0
        }

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
