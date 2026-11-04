package com.astrovm.crosstune

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.astrovm.crosstune.ui.theme.DarkColorScheme
import com.astrovm.crosstune.ui.theme.LightColorScheme
import com.astrovm.crosstune.ui.theme.Palette
import com.astrovm.crosstune.ui.theme.pureBlack
import com.astrovm.crosstune.ui.theme.withAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every line and chord against the brightest that can be behind it, in every palette: WCAG's 4.5 to 1. */
class WordsContrastTest {
    private val aim = 4.5f

    /** [ink] drawn [alpha] bright over [ground], against [ground], as WCAG counts it. */
    private fun contrast(ink: Color, alpha: Float, ground: Color) = contrast(ink.copy(alpha = alpha).compositeOver(ground), ground)

    private val brightestVisual = Color(VISUALS_CEILING, VISUALS_CEILING, VISUALS_CEILING)

    private fun assertReads(what: String, ink: Color, alpha: Float, ground: Color) {
        val got = contrast(ink, alpha, ground)
        assertTrue("$what at $alpha over $ground: $got", got >= aim)
    }

    private val dimmest = minOf(lineAlpha(-1, waiting = false, coming = false), lineAlpha(1, waiting = false, coming = false), UNSUNG_ALPHA)

    private fun assertLinesRead(onSurface: Color, onVariant: Color, primary: Color, ground: Color) {
        assertReads("Words and readings", onSurface, dimmest, ground)
        assertReads("Chords and translations", wordsAccent(primary, onSurface), dimmest, ground)
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

    /** The corners of what [coverColor] can come to, around the wheel. */
    private val covers = (0 until 360 step 30).flatMap { hue ->
        listOf(0.35f, 0.9f).flatMap { saturation -> listOf(0.55f, 0.95f).map { value -> Color.hsv(hue.toFloat(), saturation, value) } }
    }

    @Test
    fun everyLineAndChordReadsOnTheCoverWashWhereItsStrongestWhateverTheCover() {
        Palette.entries.forEach { palette ->
            listOf(
                DarkColorScheme.withAccent(palette.dark),
                DarkColorScheme.withAccent(palette.dark).pureBlack(),
                LightColorScheme.withAccent(palette.light)
            ).forEach { scheme ->
                covers.forEach { cover ->
                    val strength = washStrength(cover, scheme.surface, scheme.onSurface, scheme.onSurfaceVariant, wordsAccent(scheme.primary, scheme.onSurface))
                    assertLinesRead(scheme.onSurface, scheme.onSurfaceVariant, scheme.primary, cover.copy(alpha = strength).compositeOver(scheme.surface))
                }
            }
        }
    }

    @Test
    fun aCoverStillColorsTheScreenWhereWordsAllowIt() {
        val scheme = DarkColorScheme.withAccent(Palette.VIOLET.dark)
        val accent = wordsAccent(scheme.primary, scheme.onSurface)
        assertEquals(0.5f, washStrength(Color(0xFF8C1C13), scheme.surface, scheme.onSurface, scheme.onSurfaceVariant, accent), 0.001f)
        val light = washStrength(Color.hsv(50f, 0.35f, 0.95f), scheme.surface, scheme.onSurface, scheme.onSurfaceVariant, accent)
        assertTrue("A light cover has to give way for light words: $light", light < 0.5f)
    }

    @Test
    fun aGroundTheWordsCanNotReadOnGetsNoWashAtAll() {
        assertEquals(0f, washStrength(Color.Gray, Color.Gray, Color.Gray, Color.Gray, Color.Gray), 0.001f)
    }
}
