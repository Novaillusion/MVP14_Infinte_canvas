package com.example.infinitecanvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isShiftPressedPointer
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Cursor
import kotlin.math.pow

/** Keys that change how the mouse behaves. */
class DesktopKeys {
    /** Space held: left-drag pans (handy with a pen tablet). */
    var space by mutableStateOf(false)
}

/**
 * Mouse / pen tablet input:
 *  - left-drag (or pen tip) uses the current tool; the pen's eraser end erases
 *  - right- or middle-drag, or Space + left-drag, pans
 *  - wheel zooms around the cursor; Shift + wheel rotates
 */
fun Modifier.desktopInput(state: CanvasState, keys: DesktopKeys): Modifier = this
    .pointerHoverIcon(if (keys.space) PointerIcon(Cursor(Cursor.MOVE_CURSOR)) else PointerIcon.Crosshair)
    .pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val buttons = currentEvent.buttons
            val panning = keys.space || !buttons.isPrimaryPressed
            down.consume()
            if (panning) {
                state.beginNavigation()
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    state.transform(c.position, c.position - c.previousPosition, 1f)
                    c.consume()
                }
            } else {
                val tool = if (down.type == PointerType.Eraser) Tool.Eraser else state.tool
                when (tool) {
                    Tool.Pen -> state.beginStroke(down.position)
                    Tool.Fill -> state.beginStroke(down.position, fill = true)
                    Tool.Eraser -> state.beginErase(down.position)
                }
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    when (tool) {
                        Tool.Pen, Tool.Fill -> state.extendStroke(c.position)
                        Tool.Eraser -> state.eraseTo(c.position)
                    }
                    c.consume()
                }
                when (tool) {
                    Tool.Pen, Tool.Fill -> state.endStroke()
                    Tool.Eraser -> state.endErase()
                }
            }
        }
    }
    .pointerInput(state) {
        var lastRotateAt = 0L
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                if (e.type != PointerEventType.Scroll) continue
                val c = e.changes.firstOrNull() ?: continue
                val d = c.scrollDelta
                if (e.keyboardModifiers.isShiftPressedPointer) {
                    // Shift + wheel often arrives as horizontal scrolling.
                    val amount = if (d.y != 0f) d.y else d.x
                    val now = System.currentTimeMillis()
                    if (now - lastRotateAt > 600) state.beginNavigation()
                    lastRotateAt = now
                    state.transform(c.position, Offset.Zero, 1f, rotationDelta = amount * 5f)
                } else if (d.y != 0f) {
                    state.transform(c.position, Offset.Zero, 1.15f.pow(-d.y))
                }
                c.consume()
            }
        }
    }

/** Window-level keyboard shortcuts. Returns true if the key was handled. */
fun handleShortcut(c: AppController, keys: DesktopKeys, e: KeyEvent): Boolean {
    // Don't steal typing from the Save dialog's text field etc.
    if (c.showNameDialog || c.showDrawings || c.pendingAction != null) return false
    val state = c.state
    if (e.key == Key.Spacebar) {
        keys.space = e.type == KeyEventType.KeyDown
        return true
    }
    if (e.type != KeyEventType.KeyDown) return false
    val center = Offset(c.viewSize.width / 2f, c.viewSize.height / 2f)
    if (e.isCtrlPressed) {
        when (e.key) {
            Key.Z -> if (e.isShiftPressed) state.redo() else state.undo()
            Key.Y -> state.redo()
            Key.N -> c.newDrawing()
            Key.O -> c.openDrawingsDialog()
            Key.S -> if (e.isShiftPressed) c.startSaveAs() else c.save()
            else -> return false
        }
        return true
    }
    when (e.key) {
        Key.B, Key.P -> state.tool = Tool.Pen
        Key.F -> state.tool = Tool.Fill
        Key.E -> state.tool = Tool.Eraser
        Key.LeftBracket -> state.rotateBy(-15f, center)
        Key.RightBracket -> state.rotateBy(15f, center)
        Key.Zero, Key.NumPad0 -> state.resetView(c.viewSize)
        Key.Equals, Key.Plus, Key.NumPadAdd -> state.transform(center, Offset.Zero, 1.25f)
        Key.Minus, Key.NumPadSubtract -> state.transform(center, Offset.Zero, 0.8f)
        Key.Home -> state.fitToContent(c.viewSize)
        else -> return false
    }
    return true
}
