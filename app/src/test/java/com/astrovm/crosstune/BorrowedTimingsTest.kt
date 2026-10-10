package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BorrowedTimingsTest {

    private val words = listOf("one two three", "four five six", "seven eight nine", "ten eleven twelve")
    private val sounds = words.map(BorrowedTimings::sound)

    @Test
    fun linesTakeTheirTimesFromTheSameLinesElsewhere() {
        val other = listOf(LyricLine(10_000, "One two three"), LyricLine(20_000, "Four five six"), LyricLine(30_000, "Seven eight nine"), LyricLine(40_000, "Ten eleven twelve"))
        val timed = BorrowedTimings.retime(words, sounds, other, other.map { BorrowedTimings.sound(it.text) })
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 40_000L), timed?.map { it.timeMs })
        assertEquals(words, timed?.map { it.text })
    }

    @Test
    fun twoLinesSungAsOneThereSplitItByWhereTheSecondStarts() {
        // Written otherwise there: "one two three four five six" is one line, with a slip.
        val other = listOf(LyricLine(10_000, "One-two three, four five sicks"), LyricLine(30_000, "Seven eight nine ten eleven twelve"))
        val timed = BorrowedTimings.retime(words, sounds, other, other.map { BorrowedTimings.sound(it.text) })!!.map { it.timeMs }
        assertEquals(10_000L, timed[0])
        // "four" starts about halfway through "onetwothreefourfivesix".
        assertTrue(timed.toString(), timed[1] in 18_000L..22_000L)
        assertEquals(30_000L, timed[2])
        // Past the last line there, as far on as the letters before it took.
        assertTrue(timed.toString(), timed[3] in 40_000L..44_000L)
    }

    @Test
    fun aLineBeforeTheFirstThereIsAsFarBeforeIt() {
        val other = listOf(LyricLine(20_000, "Four five six"), LyricLine(30_000, "Seven eight nine"))
        val timed = BorrowedTimings.retime(words, sounds, other, other.map { BorrowedTimings.sound(it.text) })!!.map { it.timeMs }
        assertTrue(timed.toString(), timed[0] in 8_000L..12_000L)
        // Never before the song starts.
        val early = listOf(LyricLine(1_000, "Four five six"), LyricLine(30_000, "Seven eight nine"))
        assertEquals(0L, BorrowedTimings.retime(words, sounds, early, early.map { BorrowedTimings.sound(it.text) })!![0].timeMs)
    }

    @Test
    fun anotherSongsWordsLendNoTimes() {
        val other = listOf(LyricLine(10_000, "Yesterday all my troubles"), LyricLine(20_000, "Seemed so far away"), LyricLine(30_000, "Now it looks as though"))
        assertNull(BorrowedTimings.retime(words, sounds, other, other.map { BorrowedTimings.sound(it.text) }))
        // Nothing to line up, or a single line to time from.
        assertNull(BorrowedTimings.retime(words, sounds, emptyList(), emptyList()))
        assertNull(BorrowedTimings.retime(listOf("…"), listOf(""), listOf(LyricLine(1, "One")), listOf("one")))
        val one = listOf(LyricLine(10_000, "One two three four five six seven eight nine ten eleven twelve"))
        assertNull(BorrowedTimings.retime(words, sounds, one, one.map { BorrowedTimings.sound(it.text) }))
        // Lines there out of order in time count once.
        val backwards = listOf(LyricLine(30_000, "One two three"), LyricLine(20_000, "Four five six"), LyricLine(10_000, "Seven eight nine"))
        assertNull(BorrowedTimings.retime(words, sounds, backwards, backwards.map { BorrowedTimings.sound(it.text) }))
    }

    @Test
    fun wordsTooLongToLineUpAreLeftAsTheyAre() {
        val long = "la".repeat(2_500)
        val lines = listOf(long, long)
        val other = listOf(LyricLine(1_000, long), LyricLine(2_000, long))
        assertNull(BorrowedTimings.retime(lines, lines, other, other.map { it.text }))
    }

    @Test
    fun soundsAreTheLettersSaid() {
        assertEquals("kiniroburesuoshokku", BorrowedTimings.sound("Kin'iro búresu, ō-Shokku!"))
    }

    @Test
    fun borrowedTimesAgreeWithRoughOnesWhenMostAreClose() {
        val rough = listOf(LyricLine(10_000, "a"), LyricLine(20_000, "b"), LyricLine(30_000, "c"))
        assertTrue(BorrowedTimings.agrees(rough, rough.map { it.copy(timeMs = it.timeMs + 4_000) }))
        // One far off is a slip in the rough ones.
        assertTrue(BorrowedTimings.agrees(rough, listOf(LyricLine(14_000, "a"), LyricLine(80_000, "b"), LyricLine(31_000, "c"))))
        // All far off is another take of the song.
        assertFalse(BorrowedTimings.agrees(rough, rough.map { it.copy(timeMs = it.timeMs + 60_000) }))
        assertFalse(BorrowedTimings.agrees(rough, rough.take(2)))
        assertFalse(BorrowedTimings.agrees(emptyList(), emptyList()))
    }
}
