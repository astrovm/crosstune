package com.astrovm.crosstune

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Readings over a line laid out as a whole, with real text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RubyLineTest {

    @get:Rule
    val compose = createComposeRule()

    private val measurer = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), Density(1f), LayoutDirection.Ltr)

    private fun laidOut(parts: List<Ruby>, width: Int) =
        measurer.measure(rubyText(parts), TextStyle(fontSize = 20.sp), constraints = Constraints(maxWidth = width))

    @Test
    fun aPieceIsSpacedOutOnlyWhenItsReadingIsWider() {
        val parts = listOf(Ruby("夜空", "よぞら"), Ruby("に"), Ruby("星", "ほし"), Ruby("承", "うけたまわ"))
        val text = rubyText(parts)
        assertEquals("夜空に星承", text.text)
        // Three kana at under half size fit over two kanji, and two over one; five don't fit over one.
        val spaced = text.spanStyles.single()
        assertEquals(4 to 5, spaced.start to spaced.end)
        assertEquals((5 * READING_SCALE - 1).toDouble(), spaced.item.letterSpacing.value.toDouble(), 1e-4)
        assertTrue(spaced.item.letterSpacing.isEm)
        assertEquals(0, rubyText(listOf(Ruby("夜空", "よぞら"), Ruby("に"))).spanStyles.size)
        // Spread over each letter of a piece of two.
        val two = rubyText(listOf(Ruby("今日", "きょうのひる"))).spanStyles.single().item.letterSpacing
        assertEquals(((6 * READING_SCALE - 2) / 2).toDouble(), two.value.toDouble(), 1e-4)
    }

    @Test
    fun biggerReadingsSpaceTheirPiecesOutMore() {
        assertEquals(0, rubyText(listOf(Ruby("夜空", "よぞら"))).spanStyles.size)
        // Three kana at 0.8 are wider than two kanji.
        val spaced = rubyText(listOf(Ruby("夜空", "よぞら")), scale = 0.8f).spanStyles.single().item.letterSpacing
        assertEquals(((3 * 0.8 - 2) / 2), spaced.value.toDouble(), 1e-4)
    }

    @Test
    fun eachReadingSitsOverItsPieceOnItsRow() {
        val parts = listOf(Ruby("夜空", "よぞら"), Ruby("にきらめく"), Ruby("星", "ほし"))
        // Seven kanji and kana a row, at most: the last piece goes to the next row.
        val words = laidOut(parts, 140)
        val places = readingPlaces(parts, words)
        assertEquals(listOf("よぞら", "ほし"), places.map { it.reading })
        val sky = words.getBoundingBox(0).left to words.getBoundingBox(1).right
        assertEquals((sky.first + sky.second) / 2, places[0].center, 0.5f)
        assertEquals(words.getLineTop(0), places[0].top)
        val star = words.getBoundingBox(7)
        assertEquals(1, words.getLineForOffset(7))
        assertEquals((star.left + star.right) / 2, places[1].center, 0.5f)
        assertEquals(words.getLineTop(1), places[1].top)
        assertTrue(places[1].top > places[0].top)
    }

    @Test
    fun readingsAreDrawnOverTheirKanjiOnly() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.width(400.dp)) {
                    RubyLine(listOf(Ruby("あいう"), Ruby("夜空", "よぞら")), TextStyle(fontSize = 40.sp, lineHeight = 48.sp))
                }
            }
        }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        // The readings' row, over the words: drawn over 夜空, from 120 to 200, and not over あいう.
        val ground = image.getPixel(image.width - 1, 0)
        fun inked(from: Int, to: Int) = (from until to).any { x -> (0 until 16).any { y -> image.getPixel(x, y) != ground } }
        assertTrue(inked(125, 195))
        assertFalse(inked(0, 110))
    }

    @Test
    fun aPieceBrokenOverTwoRowsHasItsReadingOverItsFirst() {
        val parts = listOf(Ruby("あいうえお"), Ruby("夜空", "よぞら"))
        // Six a row: 夜 ends the first, 空 starts the second.
        val words = laidOut(parts, 120)
        assertEquals(0, words.getLineForOffset(5))
        assertEquals(1, words.getLineForOffset(6))
        val place = readingPlaces(parts, words).single()
        assertEquals((words.getBoundingBox(5).left + words.getLineRight(0)) / 2, place.center, 0.5f)
        assertEquals(words.getLineTop(0), place.top)
    }

    @Test
    fun piecesThatArentInTheLaidOutWordsHaveNoPlace() {
        // Words laid out for parts since replaced, a moment before they're laid out again.
        val words = laidOut(listOf(Ruby("夜")), 400)
        assertEquals(emptyList<ReadingPlace>(), readingPlaces(listOf(Ruby("夜空", "よぞら")), words))
        assertEquals(emptyList<ReadingPlace>(), readingPlaces(listOf(Ruby("", "よ")), words))
        assertEquals(listOf("よる"), readingPlaces(listOf(Ruby("夜", "よる")), words).map { it.reading })
    }

    @Test
    fun japaneseIsLaidOutAsJapaneseBrokenBetweenItsPhrases() {
        val style = TextStyle(fontSize = 20.sp)
        val japanese = style.writing("終わらない歌を歌おう クソッタレの世界のため")
        assertEquals(LocaleList("ja"), japanese.localeList)
        assertEquals(LineBreak.WordBreak.Phrase, japanese.lineBreak.wordBreak)
        // Anything else is laid out as it was.
        assertEquals(style, style.writing("Plastic Love"))
    }
}
