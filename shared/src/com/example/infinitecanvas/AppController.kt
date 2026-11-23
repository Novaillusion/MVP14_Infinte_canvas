package com.example.infinitecanvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A drawing file in the Drawings folder. */
data class FileEntry(val id: String, val name: String, val modified: Long)

enum class ExportKind(val extension: String) { Svg("svg"), Pdf("pdf") }

/**
 * Everything the shared code needs from Android / Windows. File IDs are opaque strings
 * (a content:// URI on Android, an absolute path on Windows). Blocking functions are
 * only called on a background thread.
 */
interface Platform {
    fun getSetting(key: String): String?
    fun setSetting(key: String, value: String?)

    /** Hidden copy of the current drawing, so nothing is lost if the app is closed. */
    fun readBackup(): String?
    fun writeBackup(text: String)

    /** Display name of the chosen Drawings folder, or null if none is chosen yet. */
    fun folderName(): String?
    fun chooseFolder(onChosen: () -> Unit)
    fun listFolder(): List<FileEntry>
    /** Creates an empty file named [fileName] in the folder; returns its ID. */
    fun createInFolder(fileName: String): String?
    fun isInFolder(id: String): Boolean

    fun read(id: String): String
    fun write(id: String, text: String)
    /** 0 if unknown / missing. */
    fun lastModified(id: String): Long
    fun delete(id: String): Boolean
    fun displayName(id: String): String
    fun canWrite(id: String): Boolean

    /** Lets the user pick any .canvas file (outside the folder too). */
    fun pickFile(onPicked: (String) -> Unit)
    /** Lets the user choose where to export; then [write] is called with the bytes. */
    fun pickExportTarget(suggestedName: String, kind: ExportKind, onPicked: (target: String) -> Unit)
    fun writeBytes(target: String, bytes: ByteArray)
    fun exportPdf(strokes: List<InkStroke>): ByteArray
}

class AppController(val platform: Platform, private val scope: CoroutineScope) {
    val state = CanvasState()

    // ---- current document ----
    var docId by mutableStateOf<String?>(null)
        private set
    var docName by mutableStateOf(UNTITLED)
        private set
    /** True when the drawing exists only in the hidden backup, not in a file. */
    val unsaved get() = docId == null && state.strokes.isNotEmpty()

    var folderName by mutableStateOf<String?>(null)
        private set

    // ---- dialogs ----
    var showDrawings by mutableStateOf(false)
    val drawings = mutableStateListOf<FileEntry>()
    var showNameDialog by mutableStateOf(false)
    var pendingAction by mutableStateOf<(() -> Unit)?>(null)

    /** Set by the platform: polling for changes only runs while the app is in front. */
    var foreground = true

    var viewSize by mutableStateOf(Size.Zero)
        private set
    private var needsView = true // place the view once the window size is known
    private var pendingView: DocFormat.View? = null

    private var lastMtime = 0L
    /** What the file holds as far as we know; equal text means no need to write again. */
    private var lastSyncedText: String? = null

    private fun currentText() = DocFormat.encode(DocFormat.snapshot(state))
    private val fileLock = Mutex()
    private val views = DocFormat.decodeViews(platform.getSetting(KEY_VIEWS))

    // ---------------- startup ----------------

    fun start() {
        state.snapRotation = platform.getSetting(KEY_SNAP) != "false"
        state.lockRotation = platform.getSetting(KEY_LOCK) == "true"
        folderName = platform.folderName()

        val backup = runCatching { platform.readBackup()?.let(DocFormat::decode) }.getOrNull()
        val backupFor = platform.getSetting(KEY_BACKUP_FOR)
        val savedId = platform.getSetting(KEY_DOC)
        val fromFile = savedId?.let { id -> runCatching { DocFormat.decode(platform.read(id)) }.getOrNull() }

        if (savedId != null && fromFile != null) {
            state.replaceDocument(fromFile.strokes, fromFile.deleted)
            lastSyncedText = currentText()
            attach(savedId)
            lastMtime = runCatching { platform.lastModified(savedId) }.getOrDefault(0L)
            // Edits made just before the app was closed may not have reached the file.
            if (backup != null && backupFor == savedId && state.mergeFrom(backup.strokes, backup.deleted)) {
                saveSoon()
            }
            pendingView = views[savedId] ?: fromFile.legacyView
        } else if (backup != null) {
            state.replaceDocument(backup.strokes, backup.deleted)
            docName = if (backupFor != null) platform.getSetting(KEY_NAME) ?: UNTITLED else UNTITLED
            pendingView = views[UNTITLED_KEY] ?: backup.legacyView
        }

        // Autosave ~1 s after any change to the drawing.
        scope.launch {
            snapshotFlow { state.version }.collectLatest {
                delay(1000)
                saveNow(quiet = true)
            }
        }
        // Remember this device's view of the drawing.
        scope.launch {
            snapshotFlow { listOf(state.offset, state.scale, state.rotation) }.collectLatest {
                delay(1500)
                rememberView()
            }
        }
        // Watch for changes made by the other device (via Syncthing).
        scope.launch {
            var tick = 0
            while (true) {
                delay(2000)
                if (foreground) {
                    checkForChanges(conflicts = tick % 3 == 0)
                    tick++
                }
            }
        }
    }

    /** Call whenever the canvas size changes. */
    fun onViewSize(size: Size) {
        viewSize = size
        if (needsView && size.width > 0f) {
            needsView = false
            applyView(pendingView)
        }
    }

    private fun applyView(v: DocFormat.View?) {
        if (v != null) state.setView(Offset(v.x, v.y), v.scale, v.rotation)
        else if (state.strokes.isNotEmpty() && viewSize.width > 0f) {
            state.setView(Offset.Zero, 1f, 0f)
            state.fitToContent(viewSize)
        } else state.setView(Offset(viewSize.width / 2f, viewSize.height / 2f), 1f, 0f)
    }

    private fun rememberView() {
        val key = docId ?: UNTITLED_KEY
        views.remove(key)
        views[key] = DocFormat.View(state.offset.x, state.offset.y, state.scale, state.rotation)
        while (views.size > 200) views.remove(views.keys.first())
        platform.setSetting(KEY_VIEWS, DocFormat.encodeViews(views))
    }

    // ---------------- saving ----------------

    private fun saveSoon() {
        scope.launch { saveNow(quiet = true) }
    }

    /** Writes the backup, and the file if there is one. Merges in outside changes first. */
    suspend fun saveNow(quiet: Boolean) {
        val id = docId
        val ok = fileLock.withLock {
            // Someone else (Syncthing) changed the file since we last saw it: merge first.
            if (id != null) mergeIfChanged(id)
            val text = currentText()
            val writeFile = id != null && text != lastSyncedText
            withContext(Dispatchers.IO) {
                runCatching {
                    platform.writeBackup(text)
                    platform.setSetting(KEY_BACKUP_FOR, id)
                }
                if (!writeFile || id == null) true
                else runCatching {
                    platform.write(id, text)
                    lastMtime = platform.lastModified(id)
                }.isSuccess
            }.also { if (it && writeFile) lastSyncedText = text }
        }
        if (!ok && id == docId) {
            state.flash("Couldn't save to $docName. Use “Save as”.")
            detach()
        } else if (!quiet) {
            state.flash(if (id == null) "Not saved to a file yet" else "Saved")
        }
    }

    /** Blocking save used when the app goes to the background or the window closes. */
    fun flushBlocking() {
        val id = docId
        val text = currentText()
        runCatching {
            platform.writeBackup(text)
            platform.setSetting(KEY_BACKUP_FOR, id)
        }
        rememberView()
        if (id != null && text != lastSyncedText) runCatching {
            platform.write(id, text)
            lastMtime = platform.lastModified(id)
            lastSyncedText = text
        }
    }

    // ---------------- sync ----------------

    /** Reads the file if it changed on disk and merges it in. Must hold [fileLock]. */
    private suspend fun mergeIfChanged(id: String): Boolean {
        val m = withContext(Dispatchers.IO) { runCatching { platform.lastModified(id) }.getOrDefault(0L) }
        if (m == 0L || m == lastMtime) return false
        val remote = withContext(Dispatchers.IO) {
            runCatching { DocFormat.decode(platform.read(id)) }.getOrNull()
        } ?: return false
        lastMtime = m
        val before = state.strokes.toList()
        val localHasMore = state.mergeFrom(remote.strokes, remote.deleted)
        if (!localHasMore) lastSyncedText = currentText() // file already matches us
        if (state.strokes.toList() != before) state.flash("Updated from other device")
        return localHasMore
    }

    private suspend fun checkForChanges(conflicts: Boolean) {
        val id = docId ?: return
        var needWrite = false
        fileLock.withLock {
            if (id != docId) return
            needWrite = mergeIfChanged(id)

            // Syncthing keeps "<name>.sync-conflict-<date>-<time>-<device>.canvas" when
            // both devices changed the drawing before syncing. Merge those in and remove them.
            if (conflicts && platform.isInFolder(id)) {
                val base = docName.removeSuffix(".${DocFormat.EXTENSION}")
                val copies = withContext(Dispatchers.IO) {
                    runCatching { platform.listFolder() }.getOrDefault(emptyList())
                }.filter { it.name.startsWith("$base.sync-conflict-") && it.name.endsWith(".${DocFormat.EXTENSION}") }
                for (copy in copies) {
                    val other = withContext(Dispatchers.IO) {
                        runCatching { DocFormat.decode(platform.read(copy.id)) }.getOrNull()
                    } ?: continue
                    state.mergeFrom(other.strokes, other.deleted)
                    withContext(Dispatchers.IO) { runCatching { platform.delete(copy.id) } }
                    needWrite = true
                    state.flash("Merged changes from other device")
                }
            }
        }
        if (needWrite) saveNow(quiet = true)
    }

    // ---------------- file actions ----------------

    /** Runs [action] now, or after asking if it would throw away an unsaved drawing. */
    fun guardUnsaved(action: () -> Unit) {
        if (unsaved) pendingAction = action else action()
    }

    /** "Cancel" in the unsaved-drawing dialog. */
    fun cancelPending() {
        pendingAction = null
        state.flash("Cancelled \u2014 your unsaved drawing is still here")
    }

    fun newDrawing() = guardUnsaved {
        scope.launch {
            if (docId != null) saveNow(quiet = true)
            state.replaceDocument(emptyList(), emptySet())
            detach()
            applyView(null)
        }
    }

    fun openDrawingsDialog() {
        showDrawings = true
        refreshDrawings()
    }

    fun refreshDrawings() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { platform.listFolder() }.getOrDefault(emptyList())
            }.filter { it.name.endsWith(".${DocFormat.EXTENSION}") && !it.name.contains(".sync-conflict-") }
                .sortedByDescending { it.modified }
            drawings.clear()
            drawings.addAll(list)
        }
    }

    fun open(id: String) = guardUnsaved {
        scope.launch {
            if (docId != null) saveNow(quiet = true)
            val text = withContext(Dispatchers.IO) { runCatching { platform.read(id) }.getOrNull() }
            if (text == null) {
                state.flash("Couldn't read ${platform.displayName(id)}")
                return@launch
            }
            val doc = runCatching { DocFormat.decode(text) }.getOrNull()
            if (doc == null) {
                state.flash("${platform.displayName(id)} isn't a canvas file")
                return@launch
            }
            state.replaceDocument(doc.strokes, doc.deleted)
            lastSyncedText = currentText()
            val writable = withContext(Dispatchers.IO) { runCatching { platform.canWrite(id) }.getOrDefault(false) }
            if (writable) {
                attach(id)
                lastMtime = withContext(Dispatchers.IO) { runCatching { platform.lastModified(id) }.getOrDefault(0L) }
            } else {
                // Read-only access (some file managers): open as an unsaved copy.
                detach(name = platform.displayName(id))
            }
            showDrawings = false
            applyView(views[id] ?: doc.legacyView)
        }
    }

    fun openOtherFile() = platform.pickFile { id -> open(id) }

    fun save() {
        if (docId == null) startSaveAs() else scope.launch { saveNow(quiet = false) }
    }

    fun startSaveAs() {
        if (folderName == null) {
            chooseFolder { showNameDialog = true }
        } else showNameDialog = true
    }

    /** Saves the drawing into the Drawings folder under [rawName] and switches to that file. */
    fun saveAs(rawName: String) {
        showNameDialog = false
        val clean = rawName.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
            .removeSuffix(".${DocFormat.EXTENSION}").ifEmpty { "Drawing" }
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching {
                    val taken = platform.listFolder().map { it.name.lowercase() }.toSet()
                    var name = "$clean.${DocFormat.EXTENSION}"
                    var n = 2
                    while (name.lowercase() in taken) name = "$clean ($n).${DocFormat.EXTENSION}".also { n++ }
                    platform.createInFolder(name)
                }.getOrNull()
            }
            if (id == null) {
                state.flash("Couldn't create the file")
                return@launch
            }
            attach(id)
            lastMtime = 0L
            lastSyncedText = null
            saveNow(quiet = false)
            afterSaveAs?.invoke()
            afterSaveAs = null
        }
    }

    private var afterSaveAs: (() -> Unit)? = null

    /** From the "discard?" dialog: save first, then carry on with [action]. */
    fun saveThen(action: () -> Unit) {
        pendingAction = null
        afterSaveAs = action
        startSaveAs()
    }

    fun cancelSaveAs() {
        showNameDialog = false
        afterSaveAs = null
    }

    fun chooseFolder(then: (() -> Unit)? = null) {
        platform.chooseFolder {
            folderName = platform.folderName()
            if (showDrawings) refreshDrawings()
            if (folderName != null) then?.invoke()
        }
    }

    fun export(kind: ExportKind) {
        val strokes = state.strokes.toList()
        if (strokes.isEmpty()) {
            state.flash("Nothing to export yet")
            return
        }
        val base = docName.removeSuffix(".${DocFormat.EXTENSION}").takeIf { it != UNTITLED } ?: "Drawing"
        platform.pickExportTarget("$base.${kind.extension}", kind) { target ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = when (kind) {
                            ExportKind.Svg -> VectorExport.toSvg(strokes).toByteArray()
                            ExportKind.Pdf -> platform.exportPdf(strokes)
                        }
                        platform.writeBytes(target, bytes)
                    }.isSuccess
                }
                state.flash(if (ok) "Exported" else "Export failed")
            }
        }
    }

    fun toggleSnap() {
        state.snapRotation = !state.snapRotation
        platform.setSetting(KEY_SNAP, state.snapRotation.toString())
    }

    fun toggleLock() {
        state.lockRotation = !state.lockRotation
        platform.setSetting(KEY_LOCK, state.lockRotation.toString())
    }

    // ---------------- helpers ----------------

    private fun attach(id: String) {
        docId = id
        docName = platform.displayName(id)
        platform.setSetting(KEY_DOC, id)
        platform.setSetting(KEY_NAME, docName)
    }

    private fun detach(name: String = UNTITLED) {
        docId = null
        lastSyncedText = null
        docName = name
        lastMtime = 0L
        platform.setSetting(KEY_DOC, null)
        platform.setSetting(KEY_NAME, name)
    }

    companion object {
        const val UNTITLED = "Untitled"
        private const val UNTITLED_KEY = "\u0000untitled"
        private const val KEY_DOC = "docId"
        private const val KEY_NAME = "docName"
        private const val KEY_BACKUP_FOR = "backupFor"
        private const val KEY_VIEWS = "views"
        private const val KEY_SNAP = "snapRotation"
        private const val KEY_LOCK = "lockRotation"
    }
}
