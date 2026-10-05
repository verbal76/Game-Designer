package com.hotattic.gamedesigner.applayer

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hotattic.gamedesigner.AppContainer
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.shellapi.AppLayer
import com.hotattic.gamedesigner.shellapi.ShellServices
import com.hotattic.gamedesigner.ui.AppRoot
import com.hotattic.gamedesigner.ui.GameDesignerTheme
import kotlinx.coroutines.delay

/**
 * Entry point of the application layer. This exact class exists twice: compiled into the APK (the known-good fallback)
 * and inside OTA bundles. The shell never references anything else in this layer.
 */
class AppLayerEntry : AppLayer {
    @Composable
    override fun Content(shell: ShellServices) {
        val container = remember(shell) { AppContainer(shell) }
        val vm: AppViewModel = viewModel(factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                AppViewModel(shell.application as Application, container) as T
        })
        // First frame composed and the layer stayed alive for a moment: it is healthy, so a trial update can be committed.
        LaunchedEffect(Unit) { delay(1500); shell.ota.markHealthy() }
        GameDesignerTheme { AppRoot(vm, shell) }
    }

    companion object {
        const val LAYER_VERSION = BuildConfig.LAYER_VERSION
        const val LAYER_LABEL = BuildConfig.LAYER_LABEL
    }
}
