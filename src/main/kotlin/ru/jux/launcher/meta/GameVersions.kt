package ru.jux.launcher.meta

object GameVersions {

    private val FAMILY = Regex("""^(\d+)\.(\d+)""")

    /** major.minor версии игры числами; у снапшотов вроде 25w14a — null. */
    fun family(id: String): Pair<Int, Int>? =
        FAMILY.find(id)?.destructured?.let { (major, minor) -> major.toInt() to minor.toInt() }

    fun compareFamilies(a: Pair<Int, Int>, b: Pair<Int, Int>): Int =
        compareValuesBy(a, b, { it.first }, { it.second })

    /** Версия [id] не старше [major].[minor]; снапшоты считаются свежими. */
    fun atLeast(id: String, major: Int, minor: Int): Boolean =
        family(id)?.let { compareFamilies(it, major to minor) >= 0 } ?: true
}
