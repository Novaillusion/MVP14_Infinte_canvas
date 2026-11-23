package com.example.infinitecanvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp

/**
 * The whole app UI, shared by Android and Windows.
 * [inputModifier] is the platform's pointer handling for the canvas;
 * [safeArea] keeps controls clear of system bars (Android) and is empty on Windows.
 */
@Composable
fun AppScreen(c: AppController, inputModifier: Modifier, safeArea: Modifier = Modifier) {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { c.onViewSize(Size(it.width.toFloat(), it.height.toFloat())) }
        ) {
            InfiniteCanvas(c.state, inputModifier)
            FileMenu(c, Modifier.align(Alignment.TopStart).then(safeArea).padding(12.dp))
            FlashLabel(c.state, Modifier.align(Alignment.TopCenter).then(safeArea).padding(top = 20.dp))
            ZoomControls(c.state, c.viewSize, Modifier.align(Alignment.TopEnd).then(safeArea).padding(12.dp))
            BottomToolbar(c.state, Modifier.align(Alignment.BottomCenter).then(safeArea).padding(bottom = 12.dp))
        }
        if (c.showDrawings) DrawingsDialog(c)
        if (c.showNameDialog) NameDialog(c)
        DiscardDialog(c)
    }
}
