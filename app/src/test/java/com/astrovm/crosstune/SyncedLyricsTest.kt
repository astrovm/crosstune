package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncedLyricsTest {

    @Test
    fun eachTimedLineIsReadWithWhenItsSung() {
        val lrc = """
            [ar: Band]
            [ti: Song]
            [00:01.50] First line
            [00:03.05]Second line
            [01:02] Third line
            Not timed, so not a line
        """.trimIndent()
        assertEquals(
            listOf(LyricLine(1_500, "First line"), LyricLine(3_050, "Second line"), LyricLine(62_000, "Third line")),
            SyncedLyrics.parse(lrc)
        )
    }

    @Test
    fun aLineSungTwiceIsInPlaceBothTimes() {
        // A chorus written once with both its times, the later one first.
        val lines = SyncedLyrics.parse("[00:20.00][00:05.00] Chorus\n[00:10.00] Verse")
        assertEquals(listOf(LyricLine(5_000, "Chorus"), LyricLine(10_000, "Verse"), LyricLine(20_000, "Chorus")), lines)
        // Three-digit fractions, and a colon before them as some players write it.
        assertEquals(listOf(LyricLine(1_234, "Exact")), SyncedLyrics.parse("[00:01:234] Exact"))
    }

    @Test
    fun aBlankLineStaysOnlyWhereNothingIsSungForAWhile() {
        val lines = listOf(
            LyricLine(0, ""),
            LyricLine(6_000, "After an intro"),
            LyricLine(8_000, ""),
            LyricLine(9_000, "Right after"),
            LyricLine(10_000, ""),
            LyricLine(11_000, ""),
            LyricLine(30_000, "After a solo"),
            LyricLine(32_000, "")
        )
        assertEquals(
            listOf(LyricLine(0, ""), LyricLine(6_000, "After an intro"), LyricLine(9_000, "Right after"), LyricLine(11_000, ""), LyricLine(30_000, "After a solo")),
            SyncedLyrics.withPauses(lines)
        )
    }

    @Test
    fun theLineSungIsTheLastOneStarted() {
        val lines = listOf(LyricLine(1_000, "One"), LyricLine(2_000, "Two"))
        assertEquals(-1, SyncedLyrics.indexAt(lines, 999))
        assertEquals(0, SyncedLyrics.indexAt(lines, 1_000))
        assertEquals(1, SyncedLyrics.indexAt(lines, 60_000))
    }

    @Test
    fun aClockMovesOnOnlyWhilePlaying() {
        val playing = PlaybackClock(positionMs = 10_000, atMs = 1_000, speed = 1.5f)
        assertEquals(13_000, playing.positionAt(3_000))
        assertEquals(10_000, playing.copy(playing = false).positionAt(3_000))
    }
}
