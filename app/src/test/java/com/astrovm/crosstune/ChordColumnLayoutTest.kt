package com.astrovm.crosstune

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Where a chord sits over the words, measured the way the lyrics screen places it:
 * [androidx.compose.ui.text.TextLayoutResult.getHorizontalPosition] of the chord's letter.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChordColumnLayoutTest {

    private val measurer = TextMeasurer(
        createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
        Density(1f),
        LayoutDirection.Ltr
    )

    private fun jTotal(name: String) = """<a href="JavaScript:jump_1('../../../cd/$name.html');">$name</a>"""

    @Test
    fun aChordAfterATrimmedBracketSitsOnTheLetterComposeMeasuresThere() {
        // ） before an ideographic space is one column on the page, so four Latin columns are い.
        sitsOn("あ）　い", "abcd", 3)
    }

    @Test
    fun aChordAfterABracketTrimmedByAFullwidthColonSitsOnTheLetterComposeMeasuresThere() {
        sitsOn("あ）：い", "abcd", 3)
    }

    @Test
    fun aChordAfterAnEmptyLinkSitsOnTheLetterComposeMeasuresThere() {
        val empty = """<a href="JavaScript:jump_1('../../../cd/C.html');"></a>"""
        val words = "abcdefghijklmnopqrstuvwxyz"
        val at = ChordSheets.fromJTotal("${jTotal("C")} / $empty${jTotal("C")}<br>$words").single().chords[1].at
        val layout = measurer.measure(AnnotatedString(words), TextStyle(fontSize = 20.sp), constraints = Constraints(maxWidth = 4000))
        assertEquals(4, at)
        assertEquals(layout.getHorizontalPosition(4, true), layout.getHorizontalPosition(at, true), 0.01f)
        assertTrue(layout.getHorizontalPosition(3, true) < layout.getHorizontalPosition(at, true))
    }

    @Test
    fun chordsAreDrawnOverTheirLettersOnTheRowTheyAreOn() {
        val words = "abcdefghij klmnopqrst"
        val layout = measurer.measure(AnnotatedString(words), TextStyle(fontSize = 20.sp), constraints = Constraints(maxWidth = 160))
        assertEquals(2, layout.lineCount)
        // The last one is played at the end of the words, past their last letter.
        val chords = listOf(Chord(0, "C"), Chord(14, "G"), Chord(words.length, "D"))
        val places = chordPlaces(chords, listOf(10f, 10f, 10f), layout, 160f, 2f)
        val expected = listOf(
            Offset(layout.getHorizontalPosition(0, true), layout.getLineTop(0)),
            Offset(layout.getHorizontalPosition(14, true), layout.getLineTop(1)),
            Offset(layout.getHorizontalPosition(words.length, true), layout.getLineTop(1))
        )
        assertEquals(expected.size, places.size)
        expected.forEachIndexed { index, place ->
            assertEquals(place.x, places[index].x, 0.01f)
            assertEquals(place.y, places[index].y, 0.01f)
        }
        assertTrue(places[1].y > places[0].y)
    }

    private fun sitsOn(words: String, latin: String, letter: Int) {
        val at = ChordSheets.fromJTotal("$latin${jTotal("C")}<br>$words").single().chords.single().at
        val layout = measurer.measure(AnnotatedString(words), TextStyle(fontSize = 20.sp), constraints = Constraints(maxWidth = 4000))
        val widths = words.indices.joinToString { index -> layout.getBoundingBox(index).width.toString() }
        assertEquals("widths $widths", letter, at)
        assertEquals(layout.getHorizontalPosition(letter, true), layout.getHorizontalPosition(at, true), 0.01f)
        assertTrue(layout.getHorizontalPosition(letter - 1, true) < layout.getHorizontalPosition(at, true))
    }
}
