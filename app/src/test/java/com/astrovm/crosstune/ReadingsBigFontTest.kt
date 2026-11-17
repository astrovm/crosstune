package com.astrovm.crosstune

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Readings and chords drawn over the words at each font size, measured on the pixels drawn: each
 * reading right over its kanji, under its chord, with nothing run into anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingsBigFontTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val text = "沈むように"
    private val parts = listOf(Ruby("沈", "しず"), Ruby("むように"))

    // The lyrics' own words: headline size, rows 1.2 times as tall.
    private val style = TextStyle(fontSize = 26.sp, lineHeight = 26.sp * 1.2f, fontWeight = FontWeight.Bold).writing(text)

    /** Words white, readings red, chords green, on black, so each is told apart by its color. */
    private val colors = darkColorScheme(onSurface = Color.White, onSurfaceVariant = Color.Red, primary = Color.Green, surface = Color.Black, background = Color.Black)

    /** The rows each color is drawn on, top to bottom, and the density they were drawn at. */
    private class Drawn(val words: IntRange, val readings: IntRange, val chords: IntRange?, val density: Density) {
        val gap get() = words.first - readings.last - 1
        override fun toString() = "words $words, readings $readings, chords $chords, ${density.density}x at font ${density.fontScale}"
    }

    private fun drawn(chords: Boolean): Drawn {
        lateinit var density: Density
        composeRule.setContent {
            density = LocalDensity.current
            MaterialTheme(colorScheme = colors) {
                Box(Modifier.background(Color.Black)) {
                    Column(Modifier.width(400.dp)) {
                        if (chords) ChordedLine(LyricLine(0, text), null, LineChords(over = listOf(Chord(0, "Am"), Chord(2, "F"))), null, false, style, parts, READING_SCALE)
                        else RubyLine(parts, style)
                    }
                }
            }
        }
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        return Drawn(rowsOf(image) { r, g, b -> r > 160 && g > 160 && b > 160 }, rowsOf(image) { r, g, b -> r > 160 && g < 90 && b < 90 }, if (chords) rowsOf(image) { r, g, b -> g > 160 && r < 90 && b < 90 } else null, density)
    }

    private fun rowsOf(image: Bitmap, color: (Int, Int, Int) -> Boolean): IntRange {
        val rows = (0 until image.height).filter { y ->
            (0 until image.width).any { x -> image.getPixel(x, y).let { color((it shr 16) and 0xff, (it shr 8) and 0xff, it and 0xff) } }
        }
        assertTrue("nothing drawn in that color", rows.isNotEmpty())
        return rows.first()..rows.last()
    }

    /**
     * Readings sit right over the words: what's between a reading's ink and its kanji's is less than
     * the reading is tall, and they meet by no more than 2 dp or a 25th of the words' size, as kana
     * with marks under them already meet the kanji at the usual font. Chords sit over the readings, not on them.
     */
    private fun assertSnug(drawn: Drawn) {
        val reading = drawn.readings.last - drawn.readings.first + 1
        assertTrue("readings run into the words: $drawn", drawn.gap >= -maxOf(2 * drawn.density.density, with(drawn.density) { style.fontSize.toPx() } / 25))
        assertTrue("readings float ${drawn.gap} px over their kanji, more than their own $reading px: $drawn", drawn.gap < reading)
        drawn.chords?.let { assertTrue("chords run into the readings: $drawn", it.last < drawn.readings.first) }
    }

    @Test
    fun readingsSitOnTheirKanjiAtTheUsualFont() = assertSnug(drawn(chords = false))

    @Test
    fun readingsAndChordsSitOnTheirKanjiAtTheUsualFont() = assertSnug(drawn(chords = true))

    @Test
    @Config(fontScale = 2f)
    fun readingsSitOnTheirKanjiAtFontTwo() = assertSnug(drawn(chords = false))

    @Test
    @Config(fontScale = 2f)
    fun readingsAndChordsSitOnTheirKanjiAtFontTwo() = assertSnug(drawn(chords = true))

    @Test
    @Config(fontScale = 3.2f)
    fun readingsAndChordsSitOnTheirKanjiAtTheBiggestFont() = assertSnug(drawn(chords = true))

    @Test
    @Config(fontScale = 3.2f)
    fun readingsSitOnTheirKanjiAtTheBiggestFont() = assertSnug(drawn(chords = false))
}
