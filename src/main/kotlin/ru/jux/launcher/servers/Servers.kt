package ru.jux.launcher.servers

data class ServerEntry(
    val name: String,
    val address: String,
    val entryKey: String? = null,
    // Прежние адреса: закреплённая под ними запись переезжает на новый, а не двоится.
    val formerly: List<String> = emptyList(),
)

object Servers {

    val all: List<ServerEntry> = listOf(
        ServerEntry(
            name = "VirtusMine",
            address = "jux.virtusmine.fun",
            entryKey = "26.2#VANILLA",
            formerly = listOf("mc.virtusmine.fun"),
        ),
    )
}
