package com.example.infinitecanvas

import android.content.Context
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Document serialization (.canvas = JSON) plus the hidden internal autosave slot,
 * which always holds the latest state so nothing is lost if the app is killed.
 */
object Storage {
    private const val INTERNAL_FILE = "autosave.canvas"
    const val EXTENSION = "canvas"
    const val MIME = "application/octet-stream"

    class Snapshot(val strokes: List<InkStroke>, val offset: Offset, val scale: Float)

    class Loaded(val strokes: List<InkStroke>, val offset: Offset, val scale: Float)

    fun snapshot(state: CanvasState) = Snapshot(state.strokes.toList(), state.offset, state.scale)

    // ---------------- format ----------------

    fun encode(snap: Snapshot): String {
        val arr = JSONArray()
        for (s in snap.strokes) {
            val pts = JSONArray()
            // Round to 0.01 world units: plenty of precision, much smaller files.
            for (v in s.points) pts.put(Math.round(v * 100.0) / 100.0)
            val o = JSONObject().put("c", s.color).put("w", s.width.toDouble()).put("p", pts)
            if (s.filled) o.put("f", 1)
            arr.put(o)
        }
        return JSONObject()
            .put("format", "infinite-canvas")
            .put("v", 1)
            .put("ox", snap.offset.x.toDouble())
            .put("oy", snap.offset.y.toDouble())
            .put("s", snap.scale.toDouble())
            .put("strokes", arr)
            .toString()
    }

    /** Throws if the text isn't a valid canvas file. */
    fun decode(text: String): Loaded {
        val root = JSONObject(text)
        val arr = root.getJSONArray("strokes")
        val list = ArrayList<InkStroke>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = o.getJSONArray("p")
            if (p.length() < 2) continue
            val pts = FloatArray(p.length() - p.length() % 2) { p.getDouble(it).toFloat() }
            list.add(InkStroke(pts, o.getInt("c"), o.getDouble("w").toFloat(), filled = o.optInt("f", 0) == 1))
        }
        return Loaded(
            list,
            Offset(root.optDouble("ox", 0.0).toFloat(), root.optDouble("oy", 0.0).toFloat()),
            root.optDouble("s", 1.0).toFloat()
        )
    }

    // ---------------- internal autosave ----------------

    fun saveInternal(context: Context, snap: Snapshot) {
        val dir = context.filesDir
        val tmp = File(dir, "$INTERNAL_FILE.tmp")
        tmp.writeText(encode(snap))
        tmp.renameTo(File(dir, INTERNAL_FILE)) // atomic replace: a crash can't corrupt it
    }

    fun loadInternal(context: Context): Loaded? {
        val f = File(context.filesDir, INTERNAL_FILE)
        if (!f.exists()) return null
        return try { decode(f.readText()) } catch (e: Exception) { null }
    }

    // ---------------- user files (Storage Access Framework) ----------------

    fun writeText(context: Context, uri: Uri, text: String) = writeBytes(context, uri, text.toByteArray())

    fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
        val cr = context.contentResolver
        // "wt" truncates; a few providers (e.g. some cloud drives) only accept "w".
        val out = try { cr.openOutputStream(uri, "wt") } catch (e: Exception) { null }
            ?: cr.openOutputStream(uri, "w")
            ?: error("Can't open file for writing")
        out.use { it.write(bytes) }
    }

    fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Can't open file")
}
