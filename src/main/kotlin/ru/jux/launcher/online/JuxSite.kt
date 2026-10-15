package ru.jux.launcher.online

object JuxSite {

    const val ORIGIN = "https://juxmc.ru"

    fun api(path: String): String = "$ORIGIN/api/v1/$path"
}
