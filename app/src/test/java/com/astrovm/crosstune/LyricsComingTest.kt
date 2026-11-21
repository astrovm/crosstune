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

    /** The screen as it's drawn now, its pixels read once, with where each line is. */
    private inner class Frame {
        private val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        private val width = image.width
        private val pixels = IntArray(width * image.height).also { image.getPixels(it, 0, width, 0, 0, width, image.height) }
        private val lines = composeRule.onAllNodesWithTag(LYRIC_LINE_TAG).fetchSemanticsNodes().map { it.boundsInRoot }

        /**
         * How strongly the line [index] is drawn against what's behind it: its most different pixel in each of [parts]
         * equal shares of where its words are drawn, from the first to the last.
         */
        fun ink(index: Int, parts: Int = 1): List<Int> {
            val bounds = lines[index]
            val top = bounds.top.toInt()
            val left = bounds.left.toInt()
            // Each column's most different pixel, against what's behind it on the same row, as the ground shades
            // from top to bottom.
            val columns = IntArray(bounds.right.toInt() - left)
            for (y in top until bounds.bottom.toInt()) {
                val ground = pixels[y * width]
                for (x in columns.indices) columns[x] = maxOf(columns[x], difference(pixels[y * width + left + x], ground))
            }
            val start = columns.indexOfFirst { it > inked }
            val span = columns.indexOfLast { it > inked } - start
            return (0 until parts).map { part ->
                (start + span * part / parts.toFloat()).toInt()..(start + span * (part + 1) / parts.toFloat()).toInt()
            }.map { share -> share.maxOf { columns[it] } }
        }

        private fun difference(pixel: Int, ground: Int) = shifts.sumOf { shift -> abs(((pixel shr shift) and 0xFF) - ((ground shr shift) and 0xFF)) }
    }

    /** How strongly each line is drawn now, across where its words are. */
    private fun ink() = Frame().let { frame -> List(lines.size) { frame.ink(it).single() } }

    private val shifts = listOf(0, 8, 16)

    /** How different from what's behind it a pixel has to be to be a letter's, not the ground's shading. */
    private val inked = 60

    @Test
    fun setToTheLineComingNextIsBrighterThanTheOnesAfterItAMomentBeforeItIsSung() {
        show(4_500, aheadMs = 1_000)
        val (first, second, third) = ink()
        assertTrue("$first $second", first > second)
        assertTrue("$second $third", second > third + 20)
    }

    @Test
    fun notSetToItIsDimLikeTheRestUntilItIsSung() {
        show(4_500, aheadMs = 0)
        val (_, second, third) = ink()
        assertEquals(third.toFloat(), second.toFloat(), 15f)
    }

    @Test
    fun setToItWaitsTillTheNextLineIsNearBeforeBrightening() {
        show(3_500, aheadMs = 1_000)
        val (_, second, third) = ink()
        assertEquals(third.toFloat(), second.toFloat(), 15f)
    }

    @Test
    fun setFurtherAheadItBrightensSooner() {
        show(3_500, aheadMs = 2_000)
        val (_, second, third) = ink()
        assertTrue("$second $third", second > third + 20)
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
            at to Frame().let { frame -> watch.map { line -> frame.ink(line, parts = 4) } }
        }
    }

    /** Two lines timed word by word, each starting a moment before its first word, the second close behind the first. */
    private val sungByWord = listOf(
        LyricLine(0, "First"),
        LyricLine(5_000, "Wide open roads", listOf(TimedWord(5_300, "Wide "), TimedWord(5_700, "open "), TimedWord(6_100, "roads"))),
        LyricLine(6_600, "Home again now", listOf(TimedWord(6_900, "Home "), TimedWord(7_300, "again "), TimedWord(7_700, "now")))
    )

    /**
     * How the words of the two lines are drawn, a tenth of a second at a time, while the song plays through them, from
     * [from] to [to]: each frame takes a moment to draw, so a test draws only the ones it looks at.
     */
    private fun played(from: Long = 3_900, to: Long = 6_900, chords: ChordsState = ChordsState()) =
        sampled(sungByWord, 1_000, from, to, 100, listOf(1, 2), chords)

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
        val next = drawn(played(from = 5_300, to = 6_500), 1)
        next.zipWithNext().forEach { (before, after) -> assertEquals("at ${after.first}", before.second, after.second) }
    }

    @Test
    fun withChordsTimedWordByWordTheLineComingNextStaysDimToo() {
        val sheet = ChordSheet(listOf(SheetLine("Home again now", listOf(Chord(5, "C")))), site = ChordSite.ULTIMATE_GUITAR, url = "https://tabs.ultimate-guitar.com/tab/x-1")
        assertFirstWordNotBrightBeforeItIsSung(played(chords = ChordsState(on = true, sheet = sheet)).filter { (at, _) -> at < 7_000 }, 1, 6_900)
    }
}
