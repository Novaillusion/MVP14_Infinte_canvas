package com.example.infinitecanvas

import android.content.Intent
import android.content.pm.PackageManager
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
    /** Open…: a chooser with Android's file picker plus any file-manager apps. */
    private val openChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode == RESULT_OK && uri != null) openFrom(uri)
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
        if (restored != null) state.replaceDocument(restored.strokes, restored.offset, restored.scale, restored.rotation)
        else needsCentering = true
        restoreDocumentRef()
        state.snapRotation = prefs.getBoolean("snapRotation", true)
        state.lockRotation = prefs.getBoolean("lockRotation", false)
        if (savedInstanceState == null) handleIncoming(intent)

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
                    snapshotFlow { listOf(state.version, state.offset, state.scale, state.rotation) }
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
                        onOpen = { guardUnsaved(::launchOpenChooser) },
                        onSave = ::save,
                        onSaveAs = { saveAsPicker.launch(suggestedName(Storage.EXTENSION)) },
                        onExportSvg = { if (checkNotEmpty()) svgPicker.launch(suggestedName("svg")) },
                        onExportPdf = { if (checkNotEmpty()) pdfPicker.launch(suggestedName("pdf")) },
                        snapRotation = state.snapRotation,
                        lockRotation = state.lockRotation,
                        onToggleSnap = {
                            state.snapRotation = !state.snapRotation
                            prefs.edit().putBoolean("snapRotation", state.snapRotation).apply()
                        },
                        onToggleLock = {
                            state.lockRotation = !state.lockRotation
                            prefs.edit().putBoolean("lockRotation", state.lockRotation).apply()
                        },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(12.dp)
                    )
                    FlashLabel(
                        state,
                        Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(top = 20.dp)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** A .canvas file opened from a file manager ("Open with") or shared to the app. */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intentStreamUri(intent)
            else -> null
        }
        if (uri != null) guardUnsaved { openFrom(uri) }
    }

    @Suppress("DEPRECATION")
    private fun intentStreamUri(intent: Intent): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun launchOpenChooser() {
        // Android's picker (OPEN_DOCUMENT) gives lasting read/write access, so autosave works.
        val systemPicker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        // File-manager apps answer GET_CONTENT. Android's own picker answers it too, but
        // without lasting access, so it is left out here: it's already offered above.
        val getContent = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        @Suppress("DEPRECATION")
        val excluded = packageManager.queryIntentActivities(getContent, 0)
            .filter { it.activityInfo.packageName.contains("documentsui") }
            .map { android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        val chooser = Intent.createChooser(getContent, "Open drawing with")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(systemPicker))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded.toTypedArray())
        try {
            openChooser.launch(chooser)
        } catch (_: Exception) {
            openChooser.launch(systemPicker)
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
            state.replaceDocument(loaded.strokes, loaded.offset, loaded.scale, loaded.rotation)
            // If we may write to it, autosave goes into this file. Otherwise (e.g. some
            // file managers give read-only access) it opens as unsaved; Save asks where.
            val writable = checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
            setDocument(if (writable) uri else null, displayName(uri))
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
