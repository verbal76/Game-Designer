package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

val Ember = Color(0xFFFF6A1A)
val Gold = Color(0xFFFFC24B)
val Char = Color(0xFF130F0C)
val CharRaised = Color(0xFF1E1713)
val Cream = Color(0xFFF4E6D8)

/**
 * The one and only color scheme. Game Designer is a dark, Hot Attic-branded app and does NOT follow the phone's
 * light/dark setting: the window background, splash and system bars are dark, so a light scheme would put dark text on a
 * dark window (this was the Pixel onboarding defect). Every foreground color is explicit and checked by ThemeContrastTest.
 */
val AppColorScheme = darkColorScheme(
    primary = Ember, onPrimary = Color.Black, secondary = Gold, onSecondary = Color.Black,
    background = Char, onBackground = Cream, surface = CharRaised, onSurface = Cream,
    surfaceVariant = Color(0xFF2B211B), onSurfaceVariant = Color(0xFFD9C6B4),
    primaryContainer = Color(0xFF5A2A10), onPrimaryContainer = Color(0xFFFFDCC6),
    error = Color(0xFFFF6B6B), onError = Color.Black,
)

@Composable
fun GameDesignerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColorScheme) {
        // An explicit Surface supplies LocalContentColor, so bare Text() can never inherit an unsafe default.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}
