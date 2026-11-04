package com.astrovm.crosstune

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.astrovm.crosstune.ui.theme.DarkColorScheme
import com.astrovm.crosstune.ui.theme.LightColorScheme
import com.astrovm.crosstune.ui.theme.Palette
import com.astrovm.crosstune.ui.theme.pureBlack
import com.astrovm.crosstune.ui.theme.withAccent
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every line and chord against the brightest that can be behind it, in every palette: WCAG's 4.5 to 1. */
class WordsContrastTest {
    private val aim = 4.5f

    /** [ink] drawn [alpha] bright over [ground], against [ground], as WCAG counts it. */
    private fun contrast(ink: Color, alpha: Float, ground: Color): Float {
        val drawn = ink.copy(alpha = alpha).compositeOver(ground).luminance()
        val behind = ground.luminance()
        return (maxOf(drawn, behind) + 0.05f) / (minOf(drawn, behind) + 0.05f)
    }

    private val brightestVisual = Color(VISUALS_CEILING, VISUALS_CEILING, VISUALS_CEILING)

    private fun assertReads(what: String, ink: Color, alpha: Float, ground: Color) {
        val got = contrast(ink, alpha, ground)
        assertTrue("$what at $alpha over $ground: $got", got >= aim)
    }

    private val dimmest = minOf(lineAlpha(-1, waiting = false, coming = false), lineAlpha(1, waiting = false, coming = false), UNSUNG_ALPHA)

    private fun assertLinesRead(onSurface: Color, onVariant: Color, primary: Color, ground: Color) {
        assertReads("Words", onSurface, dimmest, ground)
        assertReads("Chords and translations", wordsHelp(primary, onSurface), dimmest, ground)
        assertReads("Readings", wordsHelp(onVariant, onSurface), dimmest, ground)
        assertReads("A chord not being played", onVariant, lineAlpha(0, waiting = false, coming = false), ground)
    }

    @Test
    fun everyLineAndChordReadsOverTheBrightestVisualsInEveryPalette() {
        Palette.entries.forEach { palette ->
            val scheme = DarkColorScheme.withAccent(palette.dark).pureBlack()
            assertLinesRead(scheme.onSurface, scheme.onSurfaceVariant, scheme.primary, brightestVisual)
        }
    }

    @Test
    fun everyLineAndChordReadsWithNoVisualsInLightDarkAndPureBlack() {
        Palette.entries.forEach { palette ->
            val dark = DarkColorScheme.withAccent(palette.dark)
            val light = LightColorScheme.withAccent(palette.light)
            assertLinesRead(dark.onSurface, dark.onSurfaceVariant, dark.primary, dark.surface)
            assertLinesRead(dark.onSurface, dark.onSurfaceVariant, dark.primary, dark.pureBlack().surface)
            assertLinesRead(light.onSurface, light.onSurfaceVariant, light.primary, light.surface)
        }
    }
}
