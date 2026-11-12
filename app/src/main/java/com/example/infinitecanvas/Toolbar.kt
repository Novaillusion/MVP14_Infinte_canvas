package com.example.infinitecanvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val InkColors = listOf(
    Color(0xFF1E1E1E), Color(0xFFE5484D), Color(0xFFF76B15), Color(0xFFFFC53D),
    Color(0xFF30A46C), Color(0xFF0090FF), Color(0xFF8E4EC6),
)
val PenSizes = listOf(2f, 4f, 8f, 16f)

private val BarShape = RoundedCornerShape(24.dp)

@Composable
fun BottomToolbar(state: CanvasState, modifier: Modifier = Modifier) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(visible = state.tool == Tool.Pen) {
            Surface(shape = BarShape, shadowElevation = 6.dp, tonalElevation = 2.dp) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (c in InkColors) {
                        val selected = state.color == c.toArgb()
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    if (selected) 3.dp else 0.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape
                                )
                                .clickable { state.color = c.toArgb() }
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    for (sz in PenSizes) {
                        val selected = state.penSizePx == sz
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .clickable { state.penSizePx = sz },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .size((sz.coerceAtMost(16f) * 0.9f + 3f).dp)
                                    .clip(CircleShape)
                                    .background(Color(state.color))
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(shape = BarShape, shadowElevation = 8.dp, tonalElevation = 2.dp) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.Filled.Brush, "Pen", state.tool == Tool.Pen) { state.tool = Tool.Pen }
                ToolButton(Icons.Filled.AutoFixNormal, "Eraser", state.tool == Tool.Eraser) { state.tool = Tool.Eraser }
                Box(
                    Modifier
                        .padding(horizontal = 6.dp)
                        .width(1.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                IconButton(onClick = state::undo, enabled = state.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                }
                IconButton(onClick = state::redo, enabled = state.canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                }
                IconButton(onClick = { confirmClear = true }, enabled = state.strokes.isNotEmpty()) {
                    Icon(Icons.Filled.DeleteOutline, "Clear canvas")
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear canvas?") },
            text = { Text("Everything will be removed. You can still undo this.") },
            confirmButton = {
                TextButton(onClick = { state.clearAll(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    IconButton(onClick = onClick, modifier = Modifier.clip(CircleShape).background(bg)) {
        Icon(icon, label, tint = fg)
    }
}

/** Zoom % (tap to reset to 100%) and "fit to content". */
@Composable
fun ZoomControls(state: CanvasState, viewSize: Size, modifier: Modifier = Modifier) {
    Surface(modifier, shape = BarShape, shadowElevation = 6.dp, tonalElevation = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                zoomLabel(state.scale),
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(BarShape)
                    .clickable { state.zoomTo(1f, viewSize) }
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
            )
            IconButton(onClick = { state.fitToContent(viewSize) }) {
                Icon(Icons.Filled.CenterFocusStrong, "Fit to drawing")
            }
        }
    }
}

/** Top-left: file name (• = unsaved) and the File menu. */
@Composable
fun FileMenu(
    title: String,
    unsaved: Boolean,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onExportSvg: () -> Unit,
    onExportPdf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(shape = BarShape, shadowElevation = 6.dp, tonalElevation = 2.dp) {
            Row(
                Modifier
                    .clip(BarShape)
                    .clickable { open = true }
                    .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Menu, "File menu", Modifier.padding(8.dp))
                Text(title, fontSize = 15.sp, maxLines = 1, modifier = Modifier.widthIn(max = 280.dp))
                if (unsaved) Text("  \u2022", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem("New", Icons.Filled.Add) { open = false; onNew() }
            MenuItem("Open\u2026", Icons.Filled.FolderOpen) { open = false; onOpen() }
            MenuItem("Save", Icons.Filled.Save) { open = false; onSave() }
            MenuItem("Save as\u2026", Icons.Filled.SaveAs) { open = false; onSaveAs() }
            HorizontalDivider()
            MenuItem("Export SVG\u2026", Icons.Filled.Image) { open = false; onExportSvg() }
            MenuItem("Export PDF\u2026", Icons.Filled.PictureAsPdf) { open = false; onExportPdf() }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}
