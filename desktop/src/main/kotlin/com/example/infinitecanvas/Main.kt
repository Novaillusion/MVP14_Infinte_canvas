package com.example.infinitecanvas

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import javax.swing.UIManager

fun main() {
    // Native-looking folder chooser.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    application {
        val scope = rememberCoroutineScope()
        val platform = remember { DesktopPlatform() }
        val controller = remember { AppController(platform, scope).also { it.start() } }
        val keys = remember { DesktopKeys() }
        val icon = remember {
            Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
                ?.use { BitmapPainter(loadImageBitmap(it)) }
        }
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized)

        Window(
            onCloseRequest = {
                controller.flushBlocking()
                exitApplication()
            },
            title = controller.docName.removeSuffix(".${DocFormat.EXTENSION}") +
                (if (controller.unsaved) " •" else "") + " — Infinite Canvas",
            icon = icon,
            state = windowState,
            onPreviewKeyEvent = { handleShortcut(controller, keys, it) },
        ) {
            platform.window = window
            LaunchedEffect(windowState.isMinimized) { controller.foreground = !windowState.isMinimized }
            AppScreen(controller, inputModifier = Modifier.desktopInput(controller.state, keys))
        }
    }
}
