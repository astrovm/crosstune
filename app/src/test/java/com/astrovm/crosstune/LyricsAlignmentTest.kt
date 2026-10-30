package com.astrovm.crosstune

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The words at the start of the line or in its middle, whichever way the lyrics show them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LyricsAlignmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val song = MusicMetadata("River Road", "The Walkers")

    private val lines = listOf(LyricLine(1_000, "Walking down"), LyricLine(5_000, "River road"))

    private val padding get() = 24 * context.resources.displayMetrics.density

    /** How many rows of pixels from the top of a node are looked at: its first line, with what's over it. */
    private val ROWS = 150

    /** The song's words, timed unless [timed] is off, which leaves them a page. */
    private fun state(
        learning: Learning = Learning(),
        phone: Phone = Phone.OTHER,
        timed: Boolean = true,
        following: Following? = null,
        chords: ChordsState = ChordsState()
    ) = UiState(
        lyricsFor = song,
        lyrics = lines.joinToString("\n") { it.text },
        lyricLines = if (timed) lines else emptyList(),
        following = following,
        canFollowApps = following != null,
        learning = learning,
        phone = phone,
        chords = chords
    )

    private fun show(state: UiState) = composeRule.setContent { CrosstuneTheme { LyricsScreen(state, ScreenActions()) } }

    private fun nodeOf(text: String): SemanticsNode =
        composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().first()

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    /** How far what's drawn for [node], its first rows, is from the screen's left edge, and from its right. */
    private fun gaps(node: SemanticsNode): Pair<Float, Float> {
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val bounds = node.boundsInRoot
        var left = image.width
        var right = -1
        for (y in bounds.top.toInt() until minOf(bounds.bottom.toInt(), bounds.top.toInt() + ROWS)) {
            val background = image.getPixel(0, y)
            for (x in 1 until image.width) {
                if (inked(image.getPixel(x, y), background)) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                }
            }
        }
        return left.toFloat() to (image.width - 1 - right).toFloat()
    }

    private fun gaps(text: String) = gaps(nodeOf(text))

    /** Whether [pixel] is something drawn over [background], which doesn't change along a row. */
    private fun inked(pixel: Int, background: Int) =
        listOf(16, 8, 0).sumOf { Math.abs(((pixel shr it) and 0xff) - ((background shr it) and 0xff)) } > 60

    private fun assertStart(text: String) {
        val (left, right) = gaps(text)
        assertEquals(padding, left, 8f)
        assertTrue("$left $right", right > left * 2)
    }

    private fun assertCentered(text: String) {
        val (left, right) = gaps(text)
        assertEquals("$left $right", left, right, 8f)
        assertTrue("$left", left > padding * 2)
    }

    @Test
    fun timedWordsStartAtTheStartOnAPhone() {
        show(state())
        assertStart("Walking down")
        assertStart("River road")
    }

    @Test
    fun timedWordsOnATvAreCenteredUntilPicked() {
        show(state(phone = Phone.TV))
        assertCentered("Walking down")
        assertCentered("River road")
    }

    @Test
    fun aPickBeatsWhatThePhoneWouldDo() {
        show(state(Learning(centered = true)))
        assertCentered("Walking down")
    }

    @Test
    fun aPickToStartKeepsWordsAtTheStartOnATv() {
        show(state(Learning(centered = false), phone = Phone.TV))
        assertStart("Walking down")
    }

    @Test
    fun wordsWithNoTimingsAreAPageAlignedTheSame() {
        show(state(Learning(centered = true), timed = false))
        assertCentered("Walking down\nRiver road")
    }

    @Test
    fun wordsOnAPageStartAtTheStartOtherwise() {
        show(state(timed = false))
        assertStart("Walking down\nRiver road")
    }

    @Test
    fun aLineWithTheTranslationAndLatinLettersUnderItIsCenteredWithThem() {
        val reading = LineReading(listOf(Ruby("Walking down")), "Walking down", "wokingu daun")
        val learning = Learning(centered = true, translation = true, romanized = true, script = Script.JAPANESE, lines = listOf(reading), translations = listOf("Caminando", null))
        show(state(learning, timed = false))
        assertCentered("Walking down")
        assertCentered("wokingu daun")
        assertCentered("Caminando")
    }

    @Test
    fun theSameLinesStartAtTheStartWithTheirTranslation() {
        val reading = LineReading(listOf(Ruby("Walking down")), "Walking down", "wokingu daun")
        val learning = Learning(translation = true, romanized = true, script = Script.JAPANESE, lines = listOf(reading), translations = listOf("Caminando", null))
        show(state(learning, timed = false))
        assertStart("wokingu daun")
        assertStart("Caminando")
    }

    @Test
    fun readingsStayOverTheirKanjiWhenCentered() {
        val text = "夜空の星"
        val parts = listOf(Ruby("夜空", "よぞら"), Ruby("の"), Ruby("星", "ほし"))
        val learning = Learning(centered = true, readings = true, script = Script.JAPANESE, lines = listOf(LineReading(parts, "よぞらのほし", "yozora no hoshi")))
        val state = UiState(lyricsFor = song, lyrics = text, lyricLines = listOf(LyricLine(1_000, text)), learning = learning)
        show(state)
        assertCentered(text)
        val node = composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().first { it.config.contains(ReadingsShown) }
        assertEquals(listOf("よぞら", "ほし"), node.config[ReadingsShown])
        val layout = layoutOf(node)
        // Over where the pieces are laid out, which is away from the edge now.
        val places = readingPlaces(parts, layout)
        assertEquals((layout.getBoundingBox(0).left + layout.getBoundingBox(1).right) / 2, places[0].center, 0.5f)
        assertEquals((layout.getBoundingBox(3).left + layout.getBoundingBox(3).right) / 2, places[1].center, 0.5f)
        assertTrue(places[0].center > padding * 2)
        shot("READINGS_SCREENSHOT")
    }

    @Test
    fun aLineWithNoReadingsToPutOverItIsPlainWords() {
        // Nothing in it has a reading, so there's no room to leave for any.
        val learning = Learning(readings = true, script = Script.JAPANESE, lines = listOf(LineReading(listOf(Ruby("Walking down")), "Walking down", "wokingu daun")))
        show(state(learning))
        composeRule.onAllNodesWithText("Walking down", useUnmergedTree = true).fetchSemanticsNodes().single().let { assertFalse(it.config.contains(ReadingsShown)) }
    }

    @Test
    fun chordsStayOverTheirLettersWhenCentered() {
        val sheet = ChordSheet(listOf(SheetLine(lines[0].text, listOf(Chord(0, "G"), Chord(8, "C")))), capo = 0, site = ChordSite.ULTIMATE_GUITAR, url = "https://tabs.ultimate-guitar.com/tab/x-1")
        show(state(Learning(centered = true), chords = ChordsState(on = true, sheet = sheet)))
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Walking down", useUnmergedTree = true).fetchSemanticsNodes().any { it.config.contains(ChordsShown) } }
        val node = composeRule.onAllNodesWithText("Walking down", useUnmergedTree = true).fetchSemanticsNodes().first { it.config.contains(ChordsShown) }
        assertEquals(listOf("G", "C"), node.config[ChordsShown])
        assertCentered("Walking down")
        shot("CHORDS_SCREENSHOT")
    }

    /** How far the ink of the line after the one being sung, once scaled down, is from the left of the one being sung's, and from the right of it. */
    private fun heldIn(centered: Boolean): Pair<Float, Float> {
        val following = Following(PlaybackClock(1_500, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true)
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state(Learning(centered = centered), following = following).copy(lyricLines = listOf(LyricLine(1_000, "Walking down"), LyricLine(5_000, "Walking down"))), ScreenActions()) } }
        composeRule.waitForIdle()
        val (lit, dim) = composeRule.onAllNodesWithText("Walking down", useUnmergedTree = true).fetchSemanticsNodes().map(::gaps)
        // The one being sung is whole, the next a little smaller: it's held in from the edge it scales toward.
        return (dim.first - lit.first) to (dim.second - lit.second)
    }

    @Test
    fun linesScaleDownTowardTheirStart() {
        val (fromLeft, fromRight) = heldIn(centered = false)
        assertEquals(0f, fromLeft, 2f)
        assertTrue(fromRight > 8f)
    }

    @Test
    fun centeredLinesScaleDownTowardTheirMiddle() {
        val (fromLeft, fromRight) = heldIn(centered = true)
        assertTrue(fromLeft > 4f)
        assertEquals(fromLeft, fromRight, 2f)
    }

    private fun shot(variable: String) {
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        System.getenv(variable)?.let { path -> File(path).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        assertTrue(image.width > 0)
    }
}
