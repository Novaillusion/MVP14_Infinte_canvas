package com.example.infinitecanvas

import androidx.compose.ui.geometry.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The .canvas file format (JSON) and SVG export. Platform-independent.
 *
 * v2 adds a stable "id" per stroke and a "deleted" list of erased stroke IDs, so two
 * versions of the same drawing (e.g. edited on tablet and PC before Syncthing synced)
 * can be merged. The view (zoom/position/angle) is no longer stored in the file; each
 * device remembers its own. v1 files still open.
 */
object DocFormat {
    const val EXTENSION = "canvas"

    class Doc(
        val strokes: List<InkStroke>,
        val deleted: Set<String>,
        /** Only present in old v1 files. */
        val legacyView: View? = null,
    )

    data class View(val x: Float, val y: Float, val scale: Float, val rotation: Float)

    fun snapshot(state: CanvasState) = Doc(state.strokes.toList(), HashSet(state.deletedIds))

    fun encode(doc: Doc): String {
        val arr = JSONArray()
        for (s in doc.strokes) {
            val pts = JSONArray()
            // Round to 0.01 world units: plenty of precision, much smaller files.
            for (v in s.points) pts.put(Math.round(v * 100.0) / 100.0)
            val o = JSONObject()
                .put("id", s.id)
                .put("c", s.color)
                .put("w", s.width.toDouble())
                .put("p", pts)
            if (s.filled) o.put("f", 1)
            arr.put(o)
        }
        val del = JSONArray()
        for (id in doc.deleted.sorted()) del.put(id)
        return JSONObject()
            .put("format", "infinite-canvas")
            .put("v", 2)
            .put("strokes", arr)
            .put("deleted", del)
            .toString()
    }

    /** Throws if the text isn't a valid canvas file. */
    fun decode(text: String): Doc {
        val root = JSONObject(text)
        val arr = root.getJSONArray("strokes")
        val list = ArrayList<InkStroke>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = o.getJSONArray("p")
            if (p.length() < 2) continue
            val pts = FloatArray(p.length() - p.length() % 2) { p.getDouble(it).toFloat() }
            val color = o.getInt("c")
            val width = o.getDouble("w").toFloat()
            val filled = o.optInt("f", 0) == 1
            val id = o.optString("id", "").ifEmpty { contentStrokeId(pts, color, width, filled) }
            list.add(InkStroke(pts, color, width, filled, id))
        }
        val deleted = HashSet<String>()
        root.optJSONArray("deleted")?.let { d -> for (i in 0 until d.length()) deleted.add(d.getString(i)) }
        val legacy = if (root.has("s")) View(
            root.optDouble("ox", 0.0).toFloat(), root.optDouble("oy", 0.0).toFloat(),
            root.optDouble("s", 1.0).toFloat(), root.optDouble("r", 0.0).toFloat()
        ) else null
        return Doc(list, deleted, legacy)
    }

    // ---------------- per-device view memory ----------------

    fun encodeViews(map: Map<String, View>): String {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.scale.toDouble()).put(v.rotation.toDouble()))
        return o.toString()
    }

    fun decodeViews(text: String?): LinkedHashMap<String, View> {
        val out = LinkedHashMap<String, View>()
        if (text.isNullOrEmpty()) return out
        try {
            val o = JSONObject(text)
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                out[k] = View(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
            }
        } catch (_: Exception) {
        }
        return out
    }
}

/** One drawing command of a stroke outline, matching [buildSmoothPath]. */
sealed interface PathCmd {
    data class Move(val x: Float, val y: Float) : PathCmd
    data class Line(val x: Float, val y: Float) : PathCmd
    data class Quad(val cx: Float, val cy: Float, val x: Float, val y: Float) : PathCmd
}

fun pathCommands(pts: FloatArray, dx: Float = 0f, dy: Float = 0f): List<PathCmd> {
    val n = pts.size / 2
    if (n == 0) return emptyList()
    fun x(i: Int) = pts[2 * i] - dx
    fun y(i: Int) = pts[2 * i + 1] - dy
    val out = ArrayList<PathCmd>(n + 1)
    out.add(PathCmd.Move(x(0), y(0)))
    if (n == 1) {
        out.add(PathCmd.Line(x(0) + 0.01f, y(0)))
        return out
    }
    for (i in 1 until n - 1) {
        out.add(PathCmd.Quad(x(i), y(i), (x(i) + x(i + 1)) / 2f, (y(i) + y(i + 1)) / 2f))
    }
    out.add(PathCmd.Line(x(n - 1), y(n - 1)))
    return out
}

/** Export helpers shared by SVG (here) and the platform PDF writers. */
object VectorExport {
    private const val MARGIN = 24f

    /** Area to export: all strokes plus a margin, in world units. */
    fun area(strokes: List<InkStroke>): Rect {
        var r = strokes[0].bounds
        for (s in strokes) r = Rect(
            min(r.left, s.bounds.left), min(r.top, s.bounds.top),
            max(r.right, s.bounds.right), max(r.bottom, s.bounds.bottom)
        )
        return r.inflate(MARGIN)
    }

    /** Fills first so the ink sits on top, matching the app. */
    fun paintOrder(strokes: List<InkStroke>) = strokes.sortedBy { !it.filled }

    fun alphaOf(argb: Int) = ((argb ushr 24) and 0xFF) / 255f

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun hex(argb: Int) = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    fun toSvg(strokes: List<InkStroke>): String {
        val a = area(strokes)
        val w = f(a.width)
        val h = f(a.height)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h">""").append('\n')
        sb.append("""<g fill="none" stroke-linecap="round" stroke-linejoin="round">""").append('\n')
        for (s in paintOrder(strokes)) {
            val d = StringBuilder()
            for (c in pathCommands(s.points, a.left, a.top)) when (c) {
                is PathCmd.Move -> d.append('M').append(f(c.x)).append(' ').append(f(c.y))
                is PathCmd.Line -> d.append('L').append(f(c.x)).append(' ').append(f(c.y))
                is PathCmd.Quad -> d.append('Q').append(f(c.cx)).append(' ').append(f(c.cy))
                    .append(' ').append(f(c.x)).append(' ').append(f(c.y))
            }
            val alpha = alphaOf(s.color)
            if (s.filled) {
                val op = if (alpha < 1f) """ fill-opacity="${f(alpha)}"""" else ""
                sb.append("""<path fill="${hex(s.color)}"$op stroke="none" d="${d}Z"/>""")
            } else {
                val op = if (alpha < 1f) """ stroke-opacity="${f(alpha)}"""" else ""
                sb.append("""<path stroke="${hex(s.color)}"$op stroke-width="${f(s.width)}" d="$d"/>""")
            }
            sb.append('\n')
        }
        sb.append("</g>\n</svg>\n")
        return sb.toString()
    }
}
