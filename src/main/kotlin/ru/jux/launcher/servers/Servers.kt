package ru.jux.launcher.servers

data class ServerEntry(
    val name: String,
    val address: String,
    val entryKey: String? = null,
)

object Servers {

    val all: List<ServerEntry> = listOf(
        ServerEntry(
            name = "VirtusMine",
            address = "mc.virtusmine.fun",
            entryKey = "26.2#VANILLA",
        ),
    )
}
