package com.hotattic.gamedesigner.shell

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash (Android 12+ API, back-ported) hands off to the full-screen Hot Attic Games splash in the layer.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // The app is dark regardless of the phone's theme, so system bar icons must always be light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        val app = application as GameDesignerApp
        val layer = app.layer()
        setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF130F0C))) { layer.Content(app) }
        }
        app.otaManager.autoCheckIfDue()
    }
}
