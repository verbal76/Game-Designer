package com.hotattic.gamedesigner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.hotattic.gamedesigner.ui.AppRoot
import com.hotattic.gamedesigner.ui.GameDesignerTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash (Android 12+ API, back-ported) hands off to the full-screen Hot Attic Games splash in Compose.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GameDesignerTheme { AppRoot(vm) }
        }
    }
}
