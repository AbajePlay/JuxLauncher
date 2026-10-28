package ru.jux.launcher.online

object JuxSite {

    const val ORIGIN = "https://juxmc.ru"

    /** Друзья ходят на этот адрес; JUX_SITE_ORIGIN подменяет его для проверки на своём сайте, например http://127.0.0.1:8000. */
    val origin: String = originOf(System.getenv("JUX_SITE_ORIGIN"))

    fun api(path: String): String = "$ORIGIN/api/v1/$path"

    fun page(path: String): String = "$origin/$path"

    internal fun originOf(value: String?): String =
        value?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: ORIGIN
}
