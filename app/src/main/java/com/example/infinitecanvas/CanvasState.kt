package com.example.infinitecanvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A finished ink stroke. Points are flattened [x0, y0, x1, y1, ...] in WORLD coordinates. */
class InkStroke(val points: FloatArray, val color: Int, val width: Float) {
    val bounds: Rect
    val path: Path

    init {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < points.size) {
            minX = min(minX, points[i]); maxX = max(maxX, points[i])
            minY = min(minY, points[i + 1]); maxY = max(maxY, points[i + 1])
            i += 2
        }
        val pad = width / 2f
        bounds = Rect(minX - pad, minY - pad, maxX + pad, maxY + pad)
        path = buildSmoothPath(points)
    }

    /** True if world point [p] lies within [radius] of the stroke's centre line. */
    fun hits(p: Offset, radius: Float): Boolean {
        val r = radius + width / 2f
        if (!bounds.inflate(radius).contains(p)) return false
        val n = points.size / 2
        if (n == 1) return hypot(points[0] - p.x, points[1] - p.y) <= r
        for (i in 0 until n - 1) {
            if (distToSegment(p, points[2 * i], points[2 * i + 1], points[2 * i + 2], points[2 * i + 3]) <= r) return true
        }
        return false
    }
}

private fun distToSegment(p: Offset, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((p.x - ax) * dx + (p.y - ay) * dy) / len2).coerceIn(0f, 1f)
    return hypot(p.x - (ax + t * dx), p.y - (ay + t * dy))
}

/** Smooths the polyline with quadratic curves through segment midpoints. */
fun buildSmoothPath(pts: FloatArray, count: Int = pts.size / 2): Path {
    val path = Path()
    if (count == 0) return path
    path.moveTo(pts[0], pts[1])
    if (count == 1) {
        path.lineTo(pts[0] + 0.01f, pts[1]) // so a single tap draws a round dot
        return path
    }
    for (i in 1 until count - 1) {
        val x = pts[2 * i]
        val y = pts[2 * i + 1]
        val mx = (x + pts[2 * i + 2]) / 2f
        val my = (y + pts[2 * i + 3]) / 2f
        path.quadraticTo(x, y, mx, my)
    }
    path.lineTo(pts[2 * count - 2], pts[2 * count - 1])
    return path
}

enum class Tool { Pen, Eraser }

private sealed interface Action {
    class Add(val stroke: InkStroke) : Action
    class Remove(val strokes: List<InkStroke>) : Action
}

class CanvasState {
    companion object {
        const val MIN_SCALE = 0.01f
        const val MAX_SCALE = 100f
        const val ERASER_RADIUS_PX = 14f
    }

    // ---- document ----
    val strokes = mutableStateListOf<InkStroke>()
    var liveStroke by mutableStateOf<InkStroke?>(null)
        private set

    /** Bumped on every document change; used for autosave. */
    var version by mutableIntStateOf(0)
        private set

    // ---- camera: screen = world * scale + offset ----
    var offset by mutableStateOf(Offset.Zero)
    var scale by mutableFloatStateOf(1f)

    // ---- tool settings ----
    var tool by mutableStateOf(Tool.Pen)
    var color by mutableIntStateOf(0xFF1E1E1E.toInt())
    var penSizePx by mutableFloatStateOf(4f)

    /** Screen-space position of the eraser while erasing (for drawing its outline). */
    var eraserCursor by mutableStateOf<Offset?>(null)
        private set

    // ---- history ----
    private val undoStack = ArrayDeque<Action>()
    private val redoStack = ArrayDeque<Action>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    fun screenToWorld(p: Offset) = Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale)

    fun visibleWorldRect(size: Size) =
        Rect(screenToWorld(Offset.Zero), screenToWorld(Offset(size.width, size.height)))

    // ---------------- camera ----------------

    /** Zoom by [zoom] about screen point [focus], then pan by [pan]. */
    fun transform(focus: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val z = newScale / scale
        offset = (offset - focus) * z + focus + pan
        scale = newScale
    }

    fun zoomTo(newScale: Float, viewSize: Size) {
        val c = Offset(viewSize.width / 2f, viewSize.height / 2f)
        transform(c, Offset.Zero, newScale / scale)
    }

    fun fitToContent(viewSize: Size) {
        val r = contentBounds()
        if (r == null || viewSize.width <= 0f) {
            scale = 1f
            offset = Offset(viewSize.width / 2f, viewSize.height / 2f)
            return
        }
        val w = max(r.width, 1f)
        val h = max(r.height, 1f)
        val s = (min(viewSize.width / w, viewSize.height / h) * 0.8f).coerceIn(MIN_SCALE, MAX_SCALE)
        scale = s
        offset = Offset(viewSize.width / 2f - r.center.x * s, viewSize.height / 2f - r.center.y * s)
    }

    // ---------------- pen ----------------

    private var buf = FloatArray(512)
    private var bufCount = 0 // number of points
    private var lastScreen = Offset.Zero

    fun beginStroke(screen: Offset) {
        bufCount = 0
        appendPoint(screen)
    }

    fun extendStroke(screen: Offset) {
        if (bufCount == 0) return
        // Drop points that are too close on screen; keeps strokes light.
        if ((screen - lastScreen).getDistance() < 1.5f) return
        appendPoint(screen)
    }

    private fun appendPoint(screen: Offset) {
        if (bufCount * 2 + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
        val w = screenToWorld(screen)
        buf[bufCount * 2] = w.x
        buf[bufCount * 2 + 1] = w.y
        bufCount++
        lastScreen = screen
        // Width is stored in world units so it looks like the chosen size at the current zoom.
        liveStroke = InkStroke(buf.copyOf(bufCount * 2), color, penSizePx / scale)
    }

    fun endStroke() {
        val s = liveStroke ?: return
        liveStroke = null
        bufCount = 0
        strokes.add(s)
        push(Action.Add(s))
    }

    fun cancelStroke() {
        liveStroke = null
        bufCount = 0
    }

    // ---------------- eraser ----------------

    private var erasedThisGesture: MutableList<InkStroke>? = null
    private var lastErase: Offset? = null

    fun beginErase(screen: Offset) {
        erasedThisGesture = mutableListOf()
        lastErase = null
        eraseTo(screen)
    }

    fun eraseTo(screen: Offset) {
        val erased = erasedThisGesture ?: return
        eraserCursor = screen
        val from = lastErase ?: screen
        lastErase = screen
        // Sample along the finger's path so fast swipes don't skip strokes.
        val dist = (screen - from).getDistance()
        val steps = max(1, (dist / (ERASER_RADIUS_PX / 2f)).toInt())
        val radiusWorld = ERASER_RADIUS_PX / scale
        for (i in 1..steps) {
            val sp = from + (screen - from) * (i.toFloat() / steps)
            val wp = screenToWorld(sp)
            val hit = strokes.filter { it.hits(wp, radiusWorld) }
            if (hit.isNotEmpty()) {
                strokes.removeAll(hit)
                erased.addAll(hit)
            }
        }
    }

    fun endErase() {
        val erased = erasedThisGesture
        erasedThisGesture = null
        eraserCursor = null
        lastErase = null
        if (!erased.isNullOrEmpty()) push(Action.Remove(erased))
    }

    // ---------------- history ----------------

    fun clearAll() {
        if (strokes.isEmpty()) return
        val all = strokes.toList()
        strokes.clear()
        push(Action.Remove(all))
    }

    private fun push(a: Action) {
        undoStack.addLast(a)
        if (undoStack.size > 500) undoStack.removeFirstOrNull()
        redoStack.clear()
        changed()
    }

    fun undo() {
        val a = undoStack.removeLastOrNull() ?: return
        when (a) {
            is Action.Add -> strokes.remove(a.stroke)
            is Action.Remove -> strokes.addAll(a.strokes)
        }
        redoStack.addLast(a)
        changed()
    }

    fun redo() {
        val a = redoStack.removeLastOrNull() ?: return
        when (a) {
            is Action.Add -> strokes.add(a.stroke)
            is Action.Remove -> strokes.removeAll(a.strokes)
        }
        undoStack.addLast(a)
        changed()
    }

    private fun changed() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        version++
    }

    /** Replaces the whole document (New / Open / restore). Clears undo history. */
    fun replaceDocument(loaded: List<InkStroke>, camOffset: Offset, camScale: Float) {
        cancelStroke()
        endErase()
        strokes.clear()
        strokes.addAll(loaded)
        offset = camOffset
        scale = camScale.coerceIn(MIN_SCALE, MAX_SCALE)
        undoStack.clear()
        redoStack.clear()
        changed()
    }

    /** Union of all stroke bounds, or null when the canvas is empty. */
    fun contentBounds(): Rect? {
        if (strokes.isEmpty()) return null
        var r = strokes[0].bounds
        for (s in strokes) r = Rect(
            min(r.left, s.bounds.left), min(r.top, s.bounds.top),
            max(r.right, s.bounds.right), max(r.bottom, s.bounds.bottom)
        )
        return r
    }
}
