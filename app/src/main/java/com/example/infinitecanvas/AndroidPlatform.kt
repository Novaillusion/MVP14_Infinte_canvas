package com.example.infinitecanvas

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Android side of [Platform]. File IDs are content:// URIs. The Drawings folder is a
 * folder the user picks once (Android's folder picker), e.g. the one Syncthing shares.
 * Must be created during the activity's onCreate (it registers result launchers).
 */
class AndroidPlatform(private val activity: ComponentActivity) : Platform {
    private val prefs = activity.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
    private val cr get() = activity.contentResolver

    // ---- pickers (registered up front, callbacks stored per launch) ----
    private var onFolder: (() -> Unit)? = null
    private var onFile: ((String) -> Unit)? = null
    private var onExport: ((String) -> Unit)? = null

    private val folderPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            takePermission(uri)
            setSetting(KEY_FOLDER, uri.toString())
        }
        onFolder?.invoke()
        onFolder = null
    }
    private val filePicker = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            takePermission(uri)
            onFile?.invoke(uri.toString())
        }
        onFile = null
    }
    private val svgPicker = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        if (uri != null) onExport?.invoke(uri.toString())
        onExport = null
    }
    private val pdfPicker = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) onExport?.invoke(uri.toString())
        onExport = null
    }

    // ---- settings / backup ----
    override fun getSetting(key: String): String? = prefs.getString(key, null)
    override fun setSetting(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    private val backupFile get() = File(activity.filesDir, "autosave.canvas")
    override fun readBackup(): String? = backupFile.takeIf { it.exists() }?.readText()
    override fun writeBackup(text: String) {
        val tmp = File(activity.filesDir, "autosave.canvas.tmp")
        tmp.writeText(text)
        tmp.renameTo(backupFile) // atomic replace
    }

    // ---- Drawings folder ----
    private fun folder(): DocumentFile? {
        val uri = getSetting(KEY_FOLDER)?.let(Uri::parse) ?: return null
        val allowed = cr.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!allowed) return null
        return DocumentFile.fromTreeUri(activity, uri)?.takeIf { it.exists() }
    }

    override fun folderName(): String? = runCatching { folder()?.name }.getOrNull()

    override fun chooseFolder(onChosen: () -> Unit) {
        onFolder = onChosen
        folderPicker.launch(getSetting(KEY_FOLDER)?.let(Uri::parse))
    }

    override fun listFolder(): List<FileEntry> =
        folder()?.listFiles()?.filter { it.isFile && it.name != null }
            ?.map { FileEntry(it.uri.toString(), it.name!!, it.lastModified()) } ?: emptyList()

    override fun createInFolder(fileName: String): String? =
        folder()?.createFile("application/octet-stream", fileName)?.uri?.toString()

    override fun isInFolder(id: String): Boolean {
        val tree = getSetting(KEY_FOLDER) ?: return false
        val treeDocId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(tree)) }.getOrNull()
            ?: return false
        return Uri.parse(id).toString().contains(Uri.encode(treeDocId))
    }

    // ---- files ----
    override fun read(id: String): String =
        cr.openInputStream(Uri.parse(id))?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Can't open file")

    override fun write(id: String, text: String) = writeBytes(id, text.toByteArray())

    override fun writeBytes(target: String, bytes: ByteArray) {
        val uri = Uri.parse(target)
        // "wt" truncates; a few providers (e.g. some cloud drives) only accept "w".
        val out = try { cr.openOutputStream(uri, "wt") } catch (e: Exception) { null }
            ?: cr.openOutputStream(uri, "w")
            ?: error("Can't open file for writing")
        out.use { it.write(bytes) }
    }

    override fun lastModified(id: String): Long =
        DocumentFile.fromSingleUri(activity, Uri.parse(id))?.lastModified() ?: 0L

    override fun delete(id: String): Boolean =
        DocumentFile.fromSingleUri(activity, Uri.parse(id))?.delete() ?: false

    override fun displayName(id: String): String {
        val uri = Uri.parse(id)
        return try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: AppController.UNTITLED
    }

    override fun canWrite(id: String): Boolean =
        activity.checkCallingOrSelfUriPermission(Uri.parse(id), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    // ---- pickers ----

    /** A chooser with Android's file picker plus any file-manager apps. */
    override fun pickFile(onPicked: (String) -> Unit) {
        onFile = onPicked
        val systemPicker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val getContent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION")
        val excluded = activity.packageManager.queryIntentActivities(getContent, 0)
            .filter { it.activityInfo.packageName.contains("documentsui") }
            .map { android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        val chooser = Intent.createChooser(getContent, "Open drawing with")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(systemPicker))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded.toTypedArray())
        try {
            filePicker.launch(chooser)
        } catch (_: Exception) {
            filePicker.launch(systemPicker)
        }
    }

    override fun pickExportTarget(suggestedName: String, kind: ExportKind, onPicked: (target: String) -> Unit) {
        onExport = onPicked
        when (kind) {
            ExportKind.Svg -> svgPicker.launch(suggestedName)
            ExportKind.Pdf -> pdfPicker.launch(suggestedName)
        }
    }

    override fun exportPdf(strokes: List<InkStroke>): ByteArray = AndroidPdf.export(strokes)

    private fun takePermission(uri: Uri) {
        try {
            cr.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some providers don't offer lasting access; the file still works this session.
        }
    }

    companion object {
        private const val KEY_FOLDER = "folderUri"
    }
}
