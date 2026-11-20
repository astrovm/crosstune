package com.astrovm.crosstune

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** The line coming next brightening a moment before it's sung, when set to, in the words of a song. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LyricsComingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val lines = listOf(LyricLine(0, "First"), LyricLine(5_000, "Second"), LyricLine(9_000, "Third"))

    private fun show(positionMs: Long, aheadMs: Long, lines: List<LyricLine> = this.lines, chords: ChordsState = ChordsState()) {
        val state = UiState(
            lyricsFor = MusicMetadata("River Road", "The Walkers"),
            lyrics = lines.joinToString("\n") { it.text },
            lyricLines = lines,
            following = Following(PlaybackClock(positionMs, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true),
            canFollowApps = true,
            linesEarlyMs = 0,
            nextLineAheadMs = aheadMs,
            chords = chords
        )
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state, ScreenActions()) } }
        composeRule.waitForIdle()
    }

    /**
     * How strongly the line [index] is drawn against what's behind it: its most different pixel, across
     * the line or, as a share of where its words are drawn, from [from] to [to].
     */
    private fun ink(index: Int, from: Float = 0f, to: Float = 1f, image: android.graphics.Bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()): Int {
        val node = composeRule.onAllNodesWithTag(LYRIC_LINE_TAG).fetchSemanticsNodes()[index]
        val bounds = node.boundsInRoot
        val rows = bounds.top.toInt() until bounds.bottom.toInt()
        // Against what's behind it on the same row, as the ground shades from top to bottom.
        fun difference(x: Int, y: Int) = shifts.sumOf { shift -> abs(((image.getPixel(x, y) shr shift) and 0xFF) - ((image.getPixel(0, y) shr shift) and 0xFF)) }
        val drawn = (bounds.left.toInt() until bounds.right.toInt()).filter { x -> rows.any { y -> difference(x, y) > inked } }
        val start = drawn.first()
        val width = drawn.last() - start
        return rows.maxOf { y -> ((start + width * from).toInt()..(start + width * to).toInt()).maxOf { x -> difference(x, y) } }
    }

    private val shifts = listOf(0, 8, 16)

    /** How different from what's behind it a pixel has to be to be a letter's, not the ground's shading. */
    private val inked = 60


    @Test
    fun setToTheLineComingNextIsBrighterThanTheOnesAfterItAMomentBeforeItIsSung() {
        show(4_500, aheadMs = 1_000)
        assertTrue(ink(0) > ink(1))
        assertTrue("${ink(1)} ${ink(2)}", ink(1) > ink(2) + 20)
    }

    @Test
    fun notSetToItIsDimLikeTheRestUntilItIsSung() {
        show(4_500, aheadMs = 0)
        assertEquals(ink(2).toFloat(), ink(1).toFloat(), 15f)
    }

    @Test
    fun setToItWaitsTillTheNextLineIsNearBeforeBrightening() {
        show(3_500, aheadMs = 1_000)
        assertEquals(ink(2).toFloat(), ink(1).toFloat(), 15f)
    }

    @Test
    fun setFurtherAheadItBrightensSooner() {
        show(3_500, aheadMs = 2_000)
        assertTrue("${ink(1)} ${ink(2)}", ink(1) > ink(2) + 20)
    }

    /**
     * The song played through from [from] to [to] in [step]s, as it plays: each sample is where the song is, then
     * how strongly each line in [watch] is drawn there, as ink per fourth of the line.
     */
    private fun sampled(lines: List<LyricLine>, aheadMs: Long, from: Long, to: Long, step: Long, watch: List<Int>, chords: ChordsState = ChordsState()): List<Pair<Long, List<List<Int>>>> {
        var position by mutableLongStateOf(from)
        val clock = SystemClock.elapsedRealtime()
        composeRule.setContent {
            CrosstuneTheme {
                LyricsScreen(
                    UiState(
                        lyricsFor = MusicMetadata("River Road", "The Walkers"),
                        lyrics = lines.joinToString("\n") { it.text },
                        lyricLines = lines,
                        following = Following(PlaybackClock(position, clock, playing = false), "Spotify", canSeek = true),
                        canFollowApps = true,
                        linesEarlyMs = 0,
                        nextLineAheadMs = aheadMs,
                        chords = chords
                    ),
                    ScreenActions()
                )
            }
        }
        return (from..to step step).map { at ->
            position = at
            composeRule.waitForIdle()
            val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
            at to watch.map { line -> (0 until 4).map { quarter -> ink(line, quarter / 4f, (quarter + 1) / 4f, image) } }
        }
    }

    /** Two lines timed word by word, each starting a moment before its first word, the second close behind the first. */
    private val sungByWord = listOf(
        LyricLine(0, "First"),
        LyricLine(5_000, "Wide open roads", listOf(TimedWord(5_300, "Wide "), TimedWord(5_700, "open "), TimedWord(6_100, "roads"))),
        LyricLine(6_600, "Home again now", listOf(TimedWord(6_900, "Home "), TimedWord(7_300, "again "), TimedWord(7_700, "now")))
    )

    /** How the words of the two lines are drawn, a tenth of a second at a time, while the song plays through them. */
    private fun played(chords: ChordsState = ChordsState()) = sampled(sungByWord, 1_000, 3_900, 8_500, 100, listOf(1, 2), chords)

    /** Every quarter of [line], by where the song is, as it's drawn. */
    private fun drawn(samples: List<Pair<Long, List<List<Int>>>>, line: Int) = samples.map { (at, inks) -> at to inks[line] }

    /** Before its first word is sung, a line's first word is never brighter than the lines still to come are. */
    private fun assertFirstWordNotBrightBeforeItIsSung(samples: List<Pair<Long, List<List<Int>>>>, line: Int, firstWordMs: Long) {
        val resting = drawn(samples, line).first().second[0]
        drawn(samples, line).forEach { (at, inks) ->
            if (at < firstWordMs) assertTrue("line ${line + 1}'s first word at $at: ${inks[0]} against $resting", inks[0] <= resting + 30)
            else if (at > firstWordMs) assertTrue("line ${line + 1}'s first word at $at: ${inks[0]} against $resting", inks[0] > resting + 100)
        }
    }

    @Test
    fun timedWordByWordTheLineComingNextStaysDimUntilItsFirstWordIsSung() {
        // Its first word brightening ahead, only to go dim as the line is lit before its word, and bright again as it's sung, would flicker.
        val samples = played()
        // Until the next line is lit, which dims it.
        assertFirstWordNotBrightBeforeItIsSung(samples.filter { (at, _) -> at < 6_600 }, 0, 5_300)
        assertFirstWordNotBrightBeforeItIsSung(samples.filter { (at, _) -> at < 7_000 }, 1, 6_900)
    }

    @Test
    fun timedWordByWordOnlyTheLineSungChangesWhileItsWordsLight() {
        // The line coming next waits, rather than brightening among the words lit one by one.
        val next = drawn(played(), 1).filter { (at, _) -> at in 5_300..6_500 }
        next.zipWithNext().forEach { (before, after) -> assertEquals("at ${after.first}", before.second, after.second) }
    }

    @Test
    fun withChordsTimedWordByWordTheLineComingNextStaysDimToo() {
        val sheet = ChordSheet(listOf(SheetLine("Home again now", listOf(Chord(5, "C")))), site = ChordSite.ULTIMATE_GUITAR, url = "https://tabs.ultimate-guitar.com/tab/x-1")
        assertFirstWordNotBrightBeforeItIsSung(played(ChordsState(on = true, sheet = sheet)).filter { (at, _) -> at < 7_000 }, 1, 6_900)
    }
}
