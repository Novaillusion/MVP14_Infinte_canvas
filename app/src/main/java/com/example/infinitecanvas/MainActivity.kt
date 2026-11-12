package com.example.infinitecanvas

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val state = CanvasState()
    private var viewSize = Size.Zero
    private var needsCentering = false

    // ---- current document ----
    private var docUri by mutableStateOf<Uri?>(null)
    private var docName by mutableStateOf(UNTITLED)

    /** Non-null while the "discard unsaved drawing?" dialog is showing. */
    private var pendingAction by mutableStateOf<(() -> Unit)?>(null)
    /** Runs after a successful "Save as" started from that dialog. */
    private var afterSaveAs: (() -> Unit)? = null

    private val prefs by lazy { getSharedPreferences("document", MODE_PRIVATE) }

    // ---- system file pickers ----
    private val saveAsPicker = registerForActivityResult(ActivityResultContracts.CreateDocument(Storage.MIME)) { uri ->
        if (uri != null) saveAsTo(uri) else afterSaveAs = null
    }
    private val openPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openFrom(uri)
    }
    private val svgPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        if (uri != null) export(uri, pdf = false)
    }
    private val pdfPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) export(uri, pdf = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val restored = Storage.loadInternal(this)
        if (restored != null) state.replaceDocument(restored.strokes, restored.offset, restored.scale)
        else needsCentering = true
        restoreDocumentRef()

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                var size by remember { mutableStateOf(Size.Zero) }

                LaunchedEffect(size) {
                    viewSize = size
                    if (needsCentering && size.width > 0f) {
                        state.offset = Offset(size.width / 2f, size.height / 2f)
                        needsCentering = false
                    }
                }

                // Autosave ~1 s after any change: always to the hidden slot,
                // and also into the open .canvas file if there is one.
                LaunchedEffect(Unit) {
                    snapshotFlow { Triple(state.version, state.offset, state.scale) }
                        .collectLatest {
                            delay(1000)
                            autosave()
                        }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
                ) {
                    InfiniteCanvas(state)
                    FileMenu(
                        title = docName,
                        unsaved = docUri == null && state.strokes.isNotEmpty(),
                        onNew = { guardUnsaved(::newDocument) },
                        onOpen = { guardUnsaved { openPicker.launch(arrayOf("*/*")) } },
                        onSave = ::save,
                        onSaveAs = { saveAsPicker.launch(suggestedName(Storage.EXTENSION)) },
                        onExportSvg = { if (checkNotEmpty()) svgPicker.launch(suggestedName("svg")) },
                        onExportPdf = { if (checkNotEmpty()) pdfPicker.launch(suggestedName("pdf")) },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(12.dp)
                    )
                    ZoomControls(
                        state, size,
                        Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(12.dp)
                    )
                    BottomToolbar(
                        state,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(bottom = 12.dp)
                    )
                }

                pendingAction?.let { action ->
                    AlertDialog(
                        onDismissRequest = { pendingAction = null },
                        title = { Text("Discard current drawing?") },
                        text = { Text("It hasn't been saved to a file yet.") },
                        confirmButton = {
                            TextButton(onClick = {
                                pendingAction = null
                                afterSaveAs = action
                                saveAsPicker.launch(suggestedName(Storage.EXTENSION))
                            }) { Text("Save as…") }
                        },
                        dismissButton = {
                            Row {
                                TextButton(onClick = { pendingAction = null }) { Text("Cancel") }
                                TextButton(onClick = { pendingAction = null; action() }) { Text("Discard") }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Make sure nothing is lost if the app is killed in the background.
        val snap = Storage.snapshot(state)
        try { Storage.saveInternal(applicationContext, snap) } catch (_: Exception) {}
        docUri?.let { uri ->
            lifecycleScope.launch(Dispatchers.IO) {
                try { Storage.writeText(applicationContext, uri, Storage.encode(snap)) } catch (_: Exception) {}
            }
        }
    }

    // ---------------- actions ----------------

    private fun guardUnsaved(action: () -> Unit) {
        if (docUri == null && state.strokes.isNotEmpty()) pendingAction = action else action()
    }

    private fun newDocument() {
        state.replaceDocument(emptyList(), Offset(viewSize.width / 2f, viewSize.height / 2f), 1f)
        setDocument(null, UNTITLED)
    }

    private fun save() {
        val uri = docUri
        if (uri == null) {
            saveAsPicker.launch(suggestedName(Storage.EXTENSION))
            return
        }
        val snap = Storage.snapshot(state)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { Storage.writeText(applicationContext, uri, Storage.encode(snap)) }.isSuccess
            }
            toast(if (ok) "Saved" else "Couldn't save. Try “Save as”.")
            if (!ok) setDocument(null, docName)
        }
    }

    private fun saveAsTo(uri: Uri) {
        takePermission(uri)
        val snap = Storage.snapshot(state)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { Storage.writeText(applicationContext, uri, Storage.encode(snap)) }.isSuccess
            }
            if (ok) {
                setDocument(uri, displayName(uri))
                toast("Saved")
                afterSaveAs?.invoke()
            } else {
                toast("Couldn't save the file")
            }
            afterSaveAs = null
        }
    }

    private fun openFrom(uri: Uri) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { Storage.decode(Storage.readText(applicationContext, uri)) }.getOrNull()
            }
            if (loaded == null) {
                toast("That isn't a canvas file")
                return@launch
            }
            takePermission(uri)
            state.replaceDocument(loaded.strokes, loaded.offset, loaded.scale)
            setDocument(uri, displayName(uri))
        }
    }

    private fun export(uri: Uri, pdf: Boolean) {
        val strokes = state.strokes.toList()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (pdf) Storage.writeBytes(applicationContext, uri, VectorExport.toPdf(strokes))
                    else Storage.writeText(applicationContext, uri, VectorExport.toSvg(strokes))
                }.isSuccess
            }
            toast(if (ok) "Exported ${displayName(uri)}" else "Export failed")
        }
    }

    private suspend fun autosave() {
        val snap = Storage.snapshot(state)
        val uri = docUri
        val fileOk = withContext(Dispatchers.IO) {
            try { Storage.saveInternal(applicationContext, snap) } catch (_: Exception) {}
            if (uri == null) true
            else runCatching { Storage.writeText(applicationContext, uri, Storage.encode(snap)) }.isSuccess
        }
        if (!fileOk && uri == docUri) {
            // File was moved/deleted or access was revoked: keep working, mark unsaved.
            toast("Couldn't autosave to $docName. Use “Save as”.")
            setDocument(null, docName)
        }
    }

    // ---------------- helpers ----------------

    private fun checkNotEmpty(): Boolean {
        if (state.strokes.isEmpty()) toast("Nothing to export yet")
        return state.strokes.isNotEmpty()
    }

    private fun suggestedName(ext: String): String {
        val base = docName.removeSuffix(".${Storage.EXTENSION}").takeIf { it != UNTITLED } ?: "Drawing"
        return "$base.$ext"
    }

    private fun setDocument(uri: Uri?, name: String) {
        docUri = uri
        docName = name
        prefs.edit().putString("uri", uri?.toString()).putString("name", name).apply()
    }

    /** Re-attach to the file that was open last time, if we still have access to it. */
    private fun restoreDocumentRef() {
        val uri = prefs.getString("uri", null)?.let(Uri::parse)
        val name = prefs.getString("name", null) ?: UNTITLED
        val stillAllowed = uri != null &&
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        docUri = if (stillAllowed) uri else null
        docName = name
    }

    private fun takePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some providers don't offer persistable access; the file still works this session.
        }
    }

    private fun displayName(uri: Uri): String =
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment ?: UNTITLED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val UNTITLED = "Untitled"
    }
}
