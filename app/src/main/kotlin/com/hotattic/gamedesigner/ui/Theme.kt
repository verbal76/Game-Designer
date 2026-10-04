package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ember = Color(0xFFFF6A1A)
val Gold = Color(0xFFFFC24B)
val Char = Color(0xFF130F0C)
val CharRaised = Color(0xFF1E1713)
val Cream = Color(0xFFF4E6D8)

private val Dark = darkColorScheme(
    primary = Ember, onPrimary = Color.Black, secondary = Gold, onSecondary = Color.Black,
    background = Char, onBackground = Cream, surface = CharRaised, onSurface = Cream,
    surfaceVariant = Color(0xFF2B211B), onSurfaceVariant = Color(0xFFD9C6B4),
    primaryContainer = Color(0xFF5A2A10), onPrimaryContainer = Color(0xFFFFDCC6),
    error = Color(0xFFFF6B6B),
)
private val Light = lightColorScheme(
    primary = Color(0xFFC4480A), onPrimary = Color.White, secondary = Color(0xFF8A5A00),
    background = Color(0xFFFFF8F2), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFF3E3D5),
    primaryContainer = Color(0xFFFFDCC6), onPrimaryContainer = Color(0xFF3B1500),
)

@Composable
fun GameDesignerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
