package com.example.infinitecanvas

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

private val PaperColor = Color(0xFFFAF9F6)
private val MinorDot = Color(0xFFD3D1CB)
private val MajorDot = Color(0xFFB4B1A9)

@Composable
fun InfiniteCanvas(state: CanvasState, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxSize()
            // Pen draws / fills / erases; fingers only navigate (plus undo/redo taps).
            // While the pen is down, every finger/palm contact is ignored.
            .pointerInput(state) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val gestureStart = first.uptimeMillis
                    var penId: PointerId? = null
                    var penTool = state.tool
                    var penUsed = false

                    // finger-gesture tracking
                    val downAt = HashMap<PointerId, Offset>()
                    var navigating = false   // fingers moved past slop: pan/zoom
                    var maxFingers = 0
                    var twoFingerSince = -1L // when exactly 2 fingers became down
                    var nextHoldUndo = Long.MAX_VALUE
                    var holdFired = false

                    fun startPen(c: PointerInputChange) {
                        penId = c.id
                        penUsed = true
                        // Flipping to the stylus's eraser end always erases.
                        penTool = if (c.type == PointerType.Eraser) Tool.Eraser else state.tool
                        when (penTool) {
                            Tool.Pen -> state.beginStroke(c.position)
                            Tool.Fill -> state.beginStroke(c.position, fill = true)
                            Tool.Eraser -> state.beginErase(c.position)
                        }
                    }

                    if (first.type.isPen()) startPen(first)
                    else if (first.type == PointerType.Touch) {
                        downAt[first.id] = first.position
                        maxFingers = 1
                    }
                    first.consume()

                    while (true) {
                        // While two still fingers are held, wake up to fire hold-to-undo.
                        val wait = nextHoldUndo - SystemClock.uptimeMillis()
                        val event = if (penId == null && !navigating && nextHoldUndo != Long.MAX_VALUE) {
                            withTimeoutOrNull(max(1L, wait)) { awaitPointerEvent() }
                        } else awaitPointerEvent()

                        if (event == null) {
                            holdFired = true
                            nextHoldUndo = if (state.undo()) {
                                state.flash("Undo")
                                SystemClock.uptimeMillis() + HOLD_REPEAT_MS
                            } else Long.MAX_VALUE // history exhausted: stop repeating
                            continue
                        }

                        val id = penId
                        if (id == null) {
                            // Finger mode. A pen landing mid-gesture takes over.
                            val pen = event.changes.firstOrNull { it.type.isPen() && it.pressed && !it.previousPressed }
                            if (pen != null) {
                                nextHoldUndo = Long.MAX_VALUE
                                startPen(pen)
                            } else {
                                val touches = event.changes.filter { it.type == PointerType.Touch }
                                for (t in touches) {
                                    if (t.pressed && !t.previousPressed) downAt[t.id] = t.position
                                }
                                val down = touches.filter { it.pressed }
                                maxFingers = max(maxFingers, down.size)

                                if (!navigating && down.any { c ->
                                        (c.position - (downAt[c.id] ?: c.position)).getDistance() > slop
                                    }) {
                                    navigating = true
                                    nextHoldUndo = Long.MAX_VALUE
                                }

                                // Arm hold-to-undo only while exactly 2 still fingers are down.
                                if (!navigating && down.size == 2) {
                                    if (twoFingerSince < 0) {
                                        twoFingerSince = event.changes.maxOf { it.uptimeMillis }
                                        nextHoldUndo = twoFingerSince + HOLD_START_MS
                                    }
                                } else {
                                    twoFingerSince = -1L
                                    nextHoldUndo = Long.MAX_VALUE
                                }

                                if (down.isEmpty()) {
                                    if (event.changes.none { it.pressed }) {
                                        val duration = event.changes.maxOf { it.uptimeMillis } - gestureStart
                                        if (!navigating && !holdFired && !penUsed && duration <= TAP_MAX_MS) {
                                            when (maxFingers) {
                                                2 -> if (state.undo()) state.flash("Undo")
                                                3 -> if (state.redo()) state.flash("Redo")
                                            }
                                        }
                                        break
                                    }
                                } else if (navigating) {
                                    // 1 finger = pan, 2+ fingers = pan + pinch-zoom.
                                    state.transform(
                                        focus = event.calculateCentroid(useCurrent = true),
                                        pan = event.calculatePan(),
                                        zoom = event.calculateZoom(),
                                    )
                                }
                            }
                        } else {
                            val c = event.changes.firstOrNull { it.id == id }
                            if (c == null || !c.pressed) {
                                when (penTool) {
                                    Tool.Pen, Tool.Fill -> state.endStroke()
                                    Tool.Eraser -> state.endErase()
                                }
                                event.changes.forEach { it.consume() }
                                break // remaining fingers are ignored until lifted
                            }
                            when (penTool) {
                                Tool.Pen, Tool.Fill -> state.extendStroke(c.position)
                                Tool.Eraser -> state.eraseTo(c.position)
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        drawRect(PaperColor)
        drawDotGrid(state)

        val visible = state.visibleWorldRect(size)
        val o = state.offset
        val s = state.scale
        withTransform({
            translate(o.x, o.y)
            scale(s, s, pivot = Offset.Zero)
        }) {
            // Fills first, so lines always sit on top of them.
            for (stroke in state.strokes) {
                if (stroke.filled && stroke.bounds.overlaps(visible)) drawInk(stroke)
            }
            for (stroke in state.strokes) {
                if (!stroke.filled && stroke.bounds.overlaps(visible)) drawInk(stroke)
            }
            state.liveStroke?.let {
                drawInk(it)
                if (it.filled) {
                    // Show the lasso outline while drawing a fill.
                    drawPath(
                        it.path, Color(it.color).copy(alpha = 1f),
                        style = Stroke(width = 1.5f / s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }

        state.eraserCursor?.let {
            drawCircle(Color(0x22000000), CanvasState.ERASER_RADIUS_PX, it)
            drawCircle(Color(0x99000000), CanvasState.ERASER_RADIUS_PX, it, style = Stroke(1.5f))
        }
    }
}

private fun PointerType.isPen() = this == PointerType.Stylus || this == PointerType.Eraser

private fun DrawScope.drawInk(s: InkStroke) {
    if (s.filled) {
        drawPath(s.path, Color(s.color), style = Fill)
    } else {
        drawPath(
            s.path,
            Color(s.color),
            style = Stroke(width = s.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

private const val TAP_MAX_MS = 300L        // fingers down→up within this = a tap
private const val HOLD_START_MS = 500L     // 2-finger hold starts repeating undo after this
private const val HOLD_REPEAT_MS = 180L    // …then undoes again every this many ms

/** Dot grid whose spacing adapts to the zoom level so it never gets too dense or sparse. */
private fun DrawScope.drawDotGrid(state: CanvasState) {
    val scale = state.scale
    var step = 40f
    while (step * scale < 22f) step *= 5f
    while (step * scale > 110f) step /= 5f

    val vis = state.visibleWorldRect(size)
    val ox = state.offset.x
    val oy = state.offset.y
    val minor = ArrayList<Offset>()
    val major = ArrayList<Offset>()

    val i0 = floor(vis.left / step).toLong()
    val i1 = floor(vis.right / step).toLong() + 1
    val j0 = floor(vis.top / step).toLong()
    val j1 = floor(vis.bottom / step).toLong() + 1
    for (j in j0..j1) {
        val sy = (j * step) * scale + oy
        for (i in i0..i1) {
            val p = Offset((i * step) * scale + ox, sy)
            if (Math.floorMod(i, 5L) == 0L && Math.floorMod(j, 5L) == 0L) major.add(p) else minor.add(p)
        }
    }
    drawPoints(minor, PointMode.Points, MinorDot, strokeWidth = 3f, cap = StrokeCap.Round)
    drawPoints(major, PointMode.Points, MajorDot, strokeWidth = 5f, cap = StrokeCap.Round)
}

/** Zoom as a friendly percentage string. */
fun zoomLabel(scale: Float): String {
    val pct = scale * 100f
    return if (pct >= 10f) "${pct.roundToLong()}%" else "%.1f%%".format(pct)
}
