package com.example.infinitecanvas

import androidx.compose.foundation.Canvas
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
import kotlin.math.floor
import kotlin.math.roundToLong

/** Thinnest a line may look on screen, in pixels (display only; files are unchanged). */
private const val MIN_SCREEN_WIDTH_PX = 1.2f

private val PaperColor = Color(0xFFFAF9F6)
private val MinorDot = Color(0xFFD3D1CB)
private val MajorDot = Color(0xFFB4B1A9)

@Composable
fun InfiniteCanvas(state: CanvasState, inputModifier: Modifier, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxSize()
            .then(inputModifier)
    ) {
        drawRect(PaperColor)
        drawDotGrid(state)

        val visible = state.visibleWorldRect(size)
        val o = state.offset
        val s = state.scale
        val minWidth = MIN_SCREEN_WIDTH_PX / s
        withTransform({
            translate(o.x, o.y)
            rotate(state.rotation, pivot = Offset.Zero)
            scale(s, s, pivot = Offset.Zero)
        }) {
            // Fills first, so lines always sit on top of them.
            for (stroke in state.strokes) {
                if (stroke.filled && stroke.bounds.overlaps(visible)) drawInk(stroke, minWidth)
            }
            for (stroke in state.strokes) {
                if (!stroke.filled && stroke.bounds.overlaps(visible)) drawInk(stroke, minWidth)
            }
            state.liveStroke?.let {
                drawInk(it, minWidth)
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

/** [minWidth] (world units) keeps lines at least ~1 screen pixel wide when zoomed far out. */
private fun DrawScope.drawInk(s: InkStroke, minWidth: Float) {
    if (s.filled) {
        drawPath(s.path, Color(s.color), style = Fill)
    } else {
        drawPath(
            s.path,
            Color(s.color),
            style = Stroke(width = maxOf(s.width, minWidth), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}


/** Dot grid whose spacing adapts to the zoom level so it never gets too dense or sparse. */
private fun DrawScope.drawDotGrid(state: CanvasState) {
    val scale = state.scale
    var step = 40f
    while (step * scale < 22f) step *= 5f
    while (step * scale > 110f) step /= 5f

    val vis = state.visibleWorldRect(size)
    val ox = state.offset.x
    val oy = state.offset.y
    val rad = Math.toRadians(state.rotation.toDouble())
    val cs = (kotlin.math.cos(rad) * scale).toFloat()
    val sn = (kotlin.math.sin(rad) * scale).toFloat()
    val minor = ArrayList<Offset>()
    val major = ArrayList<Offset>()

    val i0 = floor(vis.left / step).toLong()
    val i1 = floor(vis.right / step).toLong() + 1
    val j0 = floor(vis.top / step).toLong()
    val j1 = floor(vis.bottom / step).toLong() + 1
    for (j in j0..j1) {
        val wy = j * step
        for (i in i0..i1) {
            val wx = i * step
            val p = Offset(wx * cs - wy * sn + ox, wx * sn + wy * cs + oy)
            if (p.x < -4f || p.y < -4f || p.x > size.width + 4f || p.y > size.height + 4f) continue
            if (Math.floorMod(i, 5L) == 0L && Math.floorMod(j, 5L) == 0L) major.add(p) else minor.add(p)
        }
    }
    drawPoints(minor, PointMode.Points, MinorDot, strokeWidth = 3f, cap = StrokeCap.Round)
    drawPoints(major, PointMode.Points, MajorDot, strokeWidth = 5f, cap = StrokeCap.Round)
}

/** Zoom as a friendly percentage string, plus the angle when rotated. */
fun zoomLabel(scale: Float, rotation: Float = 0f): String {
    val pct = scale * 100f
    val z = if (pct >= 10f) "${pct.roundToLong()}%" else "%.1f%%".format(pct)
    val deg = Math.round(rotation)
    return if (deg == 0) z else "$z \u00B7 $deg\u00B0"
}
