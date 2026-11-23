package com.example.infinitecanvas

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Properties
import javax.swing.JFileChooser

/** Windows side of [Platform]. File IDs are absolute paths. */
class DesktopPlatform : Platform {
    /** Main window, used as the parent of file dialogs. */
    var window: Frame? = null

    private val appDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "InfiniteCanvas")
        .apply { mkdirs() }
    private val settingsFile = File(appDir, "settings.properties")
    private val props = Properties().apply {
        if (settingsFile.exists()) settingsFile.inputStream().use { load(it) }
    }

    override fun getSetting(key: String): String? = props.getProperty(key)

    override fun setSetting(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        runCatching { settingsFile.outputStream().use { props.store(it, "Infinite Canvas") } }
    }

    private val backupFile = File(appDir, "autosave.canvas")
    override fun readBackup(): String? = backupFile.takeIf { it.exists() }?.readText()
    override fun writeBackup(text: String) {
        val tmp = File(appDir, "autosave.canvas.tmp")
        tmp.writeText(text)
        java.nio.file.Files.move(
            tmp.toPath(), backupFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    }

    // ---- Drawings folder ----
    private fun folder(): File? = getSetting(KEY_FOLDER)?.let(::File)?.takeIf { it.isDirectory }

    override fun folderName(): String? = folder()?.name

    override fun chooseFolder(onChosen: () -> Unit) {
        val chooser = JFileChooser(folder() ?: File(System.getProperty("user.home"))).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose your Drawings folder (the one Syncthing shares)"
        }
        if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
            setSetting(KEY_FOLDER, chooser.selectedFile.absolutePath)
        }
        onChosen()
    }

    override fun listFolder(): List<FileEntry> =
        folder()?.listFiles()?.filter { it.isFile }?.map { FileEntry(it.absolutePath, it.name, it.lastModified()) }
            ?: emptyList()

    override fun createInFolder(fileName: String): String? {
        val f = File(folder() ?: return null, fileName)
        return if (f.createNewFile()) f.absolutePath else null
    }

    override fun isInFolder(id: String): Boolean {
        val dir = folder() ?: return false
        return File(id).canonicalFile.parentFile == dir.canonicalFile
    }

    // ---- files ----
    override fun read(id: String): String = File(id).readText()
    override fun write(id: String, text: String) = File(id).writeText(text)
    override fun writeBytes(target: String, bytes: ByteArray) = File(target).writeBytes(bytes)
    override fun lastModified(id: String): Long = File(id).lastModified()
    override fun delete(id: String): Boolean = File(id).delete()
    override fun displayName(id: String): String = File(id).name
    override fun canWrite(id: String): Boolean = File(id).canWrite()

    // ---- dialogs ----
    override fun pickFile(onPicked: (String) -> Unit) {
        val d = FileDialog(window, "Open drawing", FileDialog.LOAD).apply {
            folder()?.let { directory = it.absolutePath }
            file = "*.${DocFormat.EXTENSION}" // filter hint on Windows
            isVisible = true
        }
        val name = d.file ?: return
        onPicked(File(d.directory, name).absolutePath)
    }

    override fun pickExportTarget(suggestedName: String, kind: ExportKind, onPicked: (target: String) -> Unit) {
        val d = FileDialog(window, "Export ${kind.extension.uppercase()}", FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        var name = d.file ?: return
        if (!name.lowercase().endsWith(".${kind.extension}")) name += ".${kind.extension}"
        onPicked(File(d.directory, name).absolutePath)
    }

    override fun exportPdf(strokes: List<InkStroke>): ByteArray = DesktopPdf.export(strokes)

    companion object {
        private const val KEY_FOLDER = "folder"
    }
}
