package com.hotattic.gamedesigner.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.shellapi.ShellServices
import com.hotattic.gamedesigner.UiEvent
import kotlinx.coroutines.delay

@Composable
fun AppRoot(vm: AppViewModel, shell: ShellServices) {
    var splashDone by rememberSaveable { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()
    when {
        !splashDone -> HotAtticSplash(shell.splashLogoRes) { splashDone = true }
        !settings.onboardingComplete -> OnboardingScreen(vm)
        else -> AppNav(vm, shell)
    }
}

/** Full-screen Hot Attic Games studio splash. Uses the authoritative logo asset (derived, never redrawn). */
@Composable
fun HotAtticSplash(logoRes: Int, onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, androidx.compose.animation.core.tween(700))
        delay(1500)
        alpha.animateTo(0f, androidx.compose.animation.core.tween(400))
        onFinished()
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF130F0C)).clickable { onFinished() }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.systemBarsPadding().padding(24.dp)) {
            Image(
                painter = painterResource(logoRes),
                contentDescription = "Hot Attic Games",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().graphicsAlpha(alpha.value),
            )
            Text("GAME DESIGNER", color = Color(0xFFFFC24B), fontSize = 18.sp, letterSpacing = 6.sp, modifier = Modifier.padding(top = 20.dp).graphicsAlpha(alpha.value))
        }
    }
}

private fun Modifier.graphicsAlpha(a: Float) = this.alpha(a)

@Composable
fun AppNav(vm: AppViewModel, shell: ShellServices) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Message -> snackbar.showSnackbar(e.text)
                is UiEvent.OpenSpec -> nav.navigate("spec/${e.projectId}")
                is UiEvent.OpenChat -> nav.navigate("chat/${e.projectId}")
                is UiEvent.PickImage -> Unit // handled by the chat screen's picker
            }
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)) { pad ->
        Box(Modifier.padding(pad)) {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen(vm, nav) }
                composable("chat/{id}") { ChatScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
                composable("spec/{id}") { SpecScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
                composable("branding/{id}") { BrandingScreen(vm, nav, it.arguments?.getString("id").orEmpty()) }
                composable("settings") { SettingsScreen(vm, nav, shell) }
                composable("repos") { ReposScreen(vm, nav) }
                composable("models") { ModelsScreen(vm, nav) }
            }
        }
    }
}
