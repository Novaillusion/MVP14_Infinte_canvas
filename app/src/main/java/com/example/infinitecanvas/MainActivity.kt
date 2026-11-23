package com.example.infinitecanvas

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private lateinit var controller: AppController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        controller = AppController(AndroidPlatform(this), lifecycleScope)
        controller.start()
        if (savedInstanceState == null) handleIncoming(intent)

        setContent {
            AppScreen(
                controller,
                inputModifier = Modifier.tabletInput(controller.state),
                safeArea = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }

    override fun onStart() {
        super.onStart()
        controller.foreground = true
    }

    override fun onStop() {
        super.onStop()
        controller.foreground = false
        // Make sure nothing is lost if the app is killed in the background.
        controller.flushBlocking()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** A .canvas file opened from a file manager ("Open with") or shared to the app. */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> streamUri(intent)
            else -> null
        }
        if (uri != null) controller.open(uri.toString())
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
}
