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

    @Test
    fun wordsTimedFasterThanAnyoneSingsAreRushed() {
        // Timed by guesswork: two lines of eight syllables each gone in about half a second.
        val guessed = listOf(
            LyricLine(43_480, "金色のブレス"),
            LyricLine(44_640, "きらめいたピアス"),
            LyricLine(45_200, "Ah　ブローした髪を"),
            LyricLine(134_350, "たそがれのワイン"),
            LyricLine(134_990, "Ah　振りまわす恋を"),
            LyricLine(142_200, "楽しんで　罪さ")
        )
        assertEquals(true, SyncedLyrics.rushed(guessed))
        // One such line can be a slip.
        assertEquals(false, SyncedLyrics.rushed(guessed.take(3)))
        // Fast rap, eleven words a second, is still sung.
        val rap = listOf(
            LyricLine(179_400, "I don't wanna hurt 'em, but I did, I'm in a fit of rage"),
            LyricLine(180_620, "I got a trailer full of money and I'm paid in full"),
            LyricLine(181_670, "Murder, murder, murder")
        )
        assertEquals(false, SyncedLyrics.rushed(rap))
        // The same lines sung faster than twelve words a second, twice, are rushed.
        val tooFast = listOf(LyricLine(0, rap[0].text), LyricLine(1_000, rap[1].text), LyricLine(1_900, "End"))
        assertEquals(true, SyncedLyrics.rushed(tooFast))
        // Short calls, "Oh, yeah", can go by in a moment.
        val calls = listOf(LyricLine(0, "Oh, yeah"), LyricLine(100, "Hey hey"), LyricLine(200, "Oh, yeah"), LyricLine(300, "Ho"))
        assertEquals(false, SyncedLyrics.rushed(calls))
        // Korean and Chinese count by syllable too.
        assertEquals(true, SyncedLyrics.rushed(listOf(LyricLine(0, "사랑해요 정말로"), LyricLine(300, "我爱你中国人"), LyricLine(600, "끝"))))
        assertEquals(false, SyncedLyrics.rushed(emptyList()))
    }
}
