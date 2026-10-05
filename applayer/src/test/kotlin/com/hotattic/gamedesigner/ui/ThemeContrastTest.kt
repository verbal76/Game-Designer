package com.hotattic.gamedesigner.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for the Pixel onboarding defect (dark text on a dark window). Every foreground/background pair the UI
 * relies on must meet WCAG contrast, and the app must use a single dark scheme regardless of the phone's theme setting.
 */
class ThemeContrastTest {
    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun ratio(a: Color, b: Color): Double { val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending(); return (hi + 0.05) / (lo + 0.05) }

    private val s = AppColorScheme

    private fun assertRatio(name: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        assertTrue(r >= min, "$name contrast ${"%.2f".format(r)} is below $min")
    }

    @Test fun bodyTextOnWindowBackgroundIsHighContrast() {
        assertRatio("onBackground/background", s.onBackground, s.background, 7.0)
        assertRatio("onSurface/surface", s.onSurface, s.surface, 7.0)
        // The window background the activity paints before Compose draws must equal the theme background.
        assertRatio("onBackground/window(#130F0C)", s.onBackground, Color(0xFF130F0C), 7.0)
    }

    @Test fun secondaryAndContainerPairsMeetAA() {
        assertRatio("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant, 4.5)
        assertRatio("onSurfaceVariant/background", s.onSurfaceVariant, s.background, 4.5)
        assertRatio("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer, 4.5)
        assertRatio("onPrimary/primary", s.onPrimary, s.primary, 4.5)
        assertRatio("onSecondary/secondary", s.onSecondary, s.secondary, 4.5)
        assertRatio("onError/error", s.onError, s.error, 4.5)
    }

    @Test fun accentsAreReadableAsTextOnTheDarkBackground() {
        assertRatio("primary/background", s.primary, s.background, 4.5)
        assertRatio("secondary/background", s.secondary, s.background, 4.5)
        assertRatio("secondary/surface", s.secondary, s.surface, 4.5)
        assertRatio("error/background", s.error, s.background, 4.5)
    }

    @Test fun theSchemeIsDarkSoItMatchesTheDarkWindowAndSplash() {
        assertTrue(lum(s.background) < 0.05, "background must stay dark to match the window and splash")
        assertTrue(lum(s.onBackground) > 0.5, "foreground must be light on the dark background")
    }
}
