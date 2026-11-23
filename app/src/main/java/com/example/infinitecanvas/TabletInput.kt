package com.example.infinitecanvas

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.max

/** Tablet input: the pen draws / fills / erases; fingers navigate and tap for undo/redo. */
fun Modifier.tabletInput(state: CanvasState): Modifier = this
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
                                    state.beginNavigation()
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
                                    // 1 finger = pan, 2+ fingers = pan + pinch-zoom + twist-rotate.
                                    state.transform(
                                        focus = event.calculateCentroid(useCurrent = true),
                                        pan = event.calculatePan(),
                                        zoom = event.calculateZoom(),
                                        rotationDelta = if (down.size >= 2) event.calculateRotation() else 0f,
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

private fun PointerType.isPen() = this == PointerType.Stylus || this == PointerType.Eraser

private const val TAP_MAX_MS = 300L        // fingers down→up within this = a tap
private const val HOLD_START_MS = 500L     // 2-finger hold starts repeating undo after this
private const val HOLD_REPEAT_MS = 180L    // …then undoes again every this many ms
