package ru.jux.launcher.ui

import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

object FilePicker {

    val IMAGES = listOf(".png", ".jpg", ".jpeg", ".webp", ".bmp")

    fun open(title: String, extensions: List<String>): Path? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.file = extensions.joinToString(";") { "*$it" }
        dialog.setFilenameFilter { _, name -> extensions.any { name.lowercase().endsWith(it) } }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val directory = dialog.directory ?: return null
        return Path.of(directory, name)
    }
}
