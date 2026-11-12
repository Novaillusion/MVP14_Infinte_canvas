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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A finished ink stroke, or a filled lasso shape when [filled] is true.
 * Points are flattened [x0, y0, x1, y1, ...] in WORLD coordinates.
 * [color] is ARGB; fills carry their opacity in the alpha channel.
 */
class InkStroke(val points: FloatArray, val color: Int, val width: Float, val filled: Boolean = false) {
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
        val pad = if (filled) 0f else width / 2f
        bounds = Rect(minX - pad, minY - pad, maxX + pad, maxY + pad)
        path = buildSmoothPath(points)
        if (filled) path.close()
    }

    /** True if world point [p] lies within [radius] of the stroke's centre line. */
    fun hits(p: Offset, radius: Float): Boolean {
        val r = radius + if (filled) 0f else width / 2f
        if (!bounds.inflate(radius).contains(p)) return false
        val n = points.size / 2
        if (filled) {
            if (n >= 3 && insidePolygon(p)) return true
            // also the closing edge back to the start
            if (distToSegment(p, points[2 * n - 2], points[2 * n - 1], points[0], points[1]) <= r) return true
        }
        if (n == 1) return hypot(points[0] - p.x, points[1] - p.y) <= r
        for (i in 0 until n - 1) {
            if (distToSegment(p, points[2 * i], points[2 * i + 1], points[2 * i + 2], points[2 * i + 3]) <= r) return true
        }
        return false
    }

    /** Even-odd ray casting against the raw polygon. */
    private fun insidePolygon(p: Offset): Boolean {
        val n = points.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = points[2 * i]; val yi = points[2 * i + 1]
            val xj = points[2 * j]; val yj = points[2 * j + 1]
            if ((yi > p.y) != (yj > p.y) && p.x < (xj - xi) * (p.y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
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

enum class Tool { Pen, Fill, Eraser }

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

    // ---- camera (see rotation below) ----
    var offset by mutableStateOf(Offset.Zero)
    var scale by mutableFloatStateOf(1f)

    // ---- tool settings ----
    var tool by mutableStateOf(Tool.Pen)
    var color by mutableIntStateOf(0xFF1E1E1E.toInt())
    var penSizePx by mutableFloatStateOf(4f)
    var fillOpacity by mutableFloatStateOf(1f)

    private fun fillColor(): Int {
        val a = (fillOpacity.coerceIn(0f, 1f) * 255f).toInt()
        return (a shl 24) or (color and 0xFFFFFF)
    }

    /** Short on-screen message ("Undo", "Redo", …). [flashId] changes on every call. */
    var flashText by mutableStateOf<String?>(null)
    var flashId by mutableIntStateOf(0)
        private set

    fun flash(text: String) {
        flashText = text
        flashId++
    }

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

    // Rotation of the view in degrees, (-180, 180]. Full camera:
    //   screen = rotate(world * scale, rotation) + offset
    var rotation by mutableFloatStateOf(0f)
    var snapRotation by mutableStateOf(true)
    var lockRotation by mutableStateOf(false)
    private var rawRotation = 0f // unsnapped angle accumulated during a gesture

    fun screenToWorld(p: Offset): Offset {
        val v = rotate(p - offset, -rotation)
        return Offset(v.x / scale, v.y / scale)
    }

    fun worldToScreen(w: Offset): Offset = rotate(w * scale, rotation) + offset

    /** World-space bounding box of everything on screen (for culling). */
    fun visibleWorldRect(size: Size): Rect {
        val cs = listOf(
            screenToWorld(Offset.Zero), screenToWorld(Offset(size.width, 0f)),
            screenToWorld(Offset(0f, size.height)), screenToWorld(Offset(size.width, size.height))
        )
        return Rect(cs.minOf { it.x }, cs.minOf { it.y }, cs.maxOf { it.x }, cs.maxOf { it.y })
    }

    // ---------------- camera ----------------

    /** Call when a finger navigation gesture starts. */
    fun beginNavigation() {
        rawRotation = rotation
    }

    /** Zoom by [zoom] and rotate by [rotationDelta] degrees about screen point [focus], then pan by [pan]. */
    fun transform(focus: Offset, pan: Offset, zoom: Float, rotationDelta: Float = 0f) {
        val anchor = screenToWorld(focus)
        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        if (!lockRotation && rotationDelta != 0f) {
            rawRotation = normalizeDeg(rawRotation + rotationDelta)
            rotation = if (snapRotation) snapDeg(rawRotation) else rawRotation
        }
        offset = focus + pan - rotate(anchor * scale, rotation)
    }

    /** Back to 100% and 0°, keeping the centre of the screen where it is. */
    fun resetView(viewSize: Size) {
        val c = Offset(viewSize.width / 2f, viewSize.height / 2f)
        val anchor = screenToWorld(c)
        scale = 1f
        rotation = 0f
        rawRotation = 0f
        offset = c - anchor
    }

    /** Fits the whole drawing on screen, keeping the current rotation. */
    fun fitToContent(viewSize: Size) {
        val r = contentBounds()
        val c = Offset(viewSize.width / 2f, viewSize.height / 2f)
        if (r == null || viewSize.width <= 0f) {
            scale = 1f
            offset = c
            return
        }
        val corners = listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).map { rotate(it, rotation) }
        val w = max(corners.maxOf { it.x } - corners.minOf { it.x }, 1f)
        val h = max(corners.maxOf { it.y } - corners.minOf { it.y }, 1f)
        val s = (min(viewSize.width / w, viewSize.height / h) * 0.8f).coerceIn(MIN_SCALE, MAX_SCALE)
        scale = s
        offset = c - rotate(r.center * s, rotation)
    }

    // ---------------- pen ----------------

    private var buf = FloatArray(512)
    private var bufCount = 0 // number of points
    private var lastScreen = Offset.Zero
    private var liveFill = false

    /** Starts a pen stroke, or a lasso fill shape when [fill] is true. */
    fun beginStroke(screen: Offset, fill: Boolean = false) {
        bufCount = 0
        liveFill = fill
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
        liveStroke =
            if (liveFill) InkStroke(buf.copyOf(bufCount * 2), fillColor(), 0f, filled = true)
            else InkStroke(buf.copyOf(bufCount * 2), color, penSizePx / scale)
    }

    fun endStroke() {
        val s = liveStroke ?: return
        if (s.filled && s.points.size < 6) { cancelStroke(); return } // need 3+ points for an area
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

    /** Returns false (and shows a hint) when there's nothing to undo. */
    fun undo(): Boolean {
        val a = undoStack.removeLastOrNull() ?: run { flash("Nothing to undo"); return false }
        when (a) {
            is Action.Add -> strokes.remove(a.stroke)
            is Action.Remove -> strokes.addAll(a.strokes)
        }
        redoStack.addLast(a)
        changed()
        return true
    }

    /** Returns false (and shows a hint) when there's nothing to redo. */
    fun redo(): Boolean {
        val a = redoStack.removeLastOrNull() ?: run { flash("Nothing to redo"); return false }
        when (a) {
            is Action.Add -> strokes.add(a.stroke)
            is Action.Remove -> strokes.removeAll(a.strokes)
        }
        undoStack.addLast(a)
        changed()
        return true
    }

    private fun changed() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        version++
    }

    /** Replaces the whole document (New / Open / restore). Clears undo history. */
    fun replaceDocument(loaded: List<InkStroke>, camOffset: Offset, camScale: Float, camRotation: Float = 0f) {
        cancelStroke()
        endErase()
        strokes.clear()
        strokes.addAll(loaded)
        offset = camOffset
        scale = camScale.coerceIn(MIN_SCALE, MAX_SCALE)
        rotation = normalizeDeg(camRotation)
        rawRotation = rotation
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

/** Rotates [v] by [deg] degrees (clockwise on screen, since y points down). */
fun rotate(v: Offset, deg: Float): Offset {
    if (deg == 0f) return v
    val r = Math.toRadians(deg.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}

fun normalizeDeg(d: Float): Float {
    var a = d % 360f
    if (a <= -180f) a += 360f
    if (a > 180f) a -= 360f
    return a
}

private const val SNAP_WITHIN_DEG = 7f

/** Snaps to 0/90/180/270 when within a few degrees of it. */
private fun snapDeg(d: Float): Float {
    val nearest = (d / 90f).roundToInt() * 90f
    return if (abs(d - nearest) <= SNAP_WITHIN_DEG) normalizeDeg(nearest) else d
}
