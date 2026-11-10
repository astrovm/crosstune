package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Words found with no timing put in time by what's heard sung, a while at a time. Made-up words throughout. */
class SungTimingTest {

    private val lines = listOf(
        "Morning comes over the hill",
        "Coffee steaming by the window",
        "Paper boats along the gutter",
        "We sing the river home tonight",
        "Carry every lantern down",
        "Bicycles rattle past the bakery",
        "Pigeons argue on the station roof",
        "We sing the river home tonight",
        "Carry every lantern down"
    )

    /** [text] sung from [startMs], each word heard where the speech model has it: a little late, at an even pace. */
    private fun sungAt(startMs: Long, text: String): List<Pair<String, Long>> {
        var before = 0
        return text.split(' ').map { word ->
            (word to startMs + SungTiming.HEARD_LATE_MS + before * SungTiming.LETTER_MS).also { before += SpokenWords.pieces(word).sumOf { it.length } }
        }
    }

    private fun heard(fromMs: Long, vararg sung: List<Pair<String, Long>>) = HeardStretch(fromMs, fromMs + 20_000, sung.toList().flatten())

    @Test
    fun aLineHeardStartsWhereItsSung() {
        val placed = SungTiming.linesMs(lines, listOf(heard(20_000, sungAt(21_000, lines[1]), sungAt(25_000, lines[2]))), 120_000)
        assertEquals(mapOf(1 to 21_000L, 2 to 25_000L), placed)
    }

    @Test
    fun aChorusIsTimedWhereItsSungThisTime() {
        // The chorus is sung twice; heard after the verse between, it's the second time.
        val placed = SungTiming.linesMs(
            lines, listOf(heard(60_000, sungAt(61_000, lines[5]), sungAt(65_000, lines[6]), sungAt(70_000, lines[7]), sungAt(74_000, lines[8]))), 100_000
        )
        assertEquals(mapOf(5 to 61_000L, 6 to 65_000L, 7 to 70_000L, 8 to 74_000L), placed)
    }

    @Test
    fun soundsTheModelTellsOfArentWords() {
        val sounds = listOf("[Music]" to 20_500L, "(upbeat" to 20_700L, "music)" to 20_800L, "♪" to 20_900L)
        val placed = SungTiming.linesMs(lines, listOf(heard(20_000, sounds, sungAt(22_000, lines[1]))), 120_000)
        assertEquals(mapOf(1 to 22_000L), placed)
    }

    @Test
    fun aLineMisheardHereAndThereIsStillFound() {
        // "Morning" heard as "Mornin", "uh" heard that isn't sung, and "the" not heard at all.
        val at = SungTiming.HEARD_LATE_MS + 10_000
        val misheard = listOf("Mornin" to at, "comes" to at + 7 * SungTiming.LETTER_MS, "uh" to at + 10 * SungTiming.LETTER_MS, "over" to at + 12 * SungTiming.LETTER_MS, "hill" to at + 19 * SungTiming.LETTER_MS)
        assertEquals(mapOf(0 to 10_000L), SungTiming.linesMs(lines, listOf(heard(9_000, misheard)), 120_000))
    }

    @Test
    fun aShortLineNeedsALineBesideIt() {
        val short = listOf("Paper boats along the gutter", "Oh yeah", "Morning comes over the hill")
        // Two words alike can be chance.
        assertEquals(emptyMap<Int, Long>(), SungTiming.linesMs(short, listOf(heard(10_000, sungAt(13_000, short[1]))), 60_000))
        // Right after a line found, they're that line's next.
        assertEquals(mapOf(0 to 10_000L, 1 to 13_000L), SungTiming.linesMs(short, listOf(heard(9_000, sungAt(10_000, short[0]), sungAt(13_000, short[1]))), 60_000))
    }

    @Test
    fun aLineNotMadeOutBetweenTwoHeardIsPutBetweenThem() {
        val three = listOf("Paper boats along the gutter", "Shimmer shimmer", "Morning comes over the hill")
        val placed = SungTiming.linesMs(three, listOf(heard(9_000, sungAt(10_000, three[0]), listOf("la" to 13_600L, "la" to 13_900L), sungAt(16_000, three[2]))), 60_000)
        val first = SpokenWords.pieces(three[0]).sumOf { it.length }
        val second = SpokenWords.pieces(three[1]).sumOf { it.length }
        assertEquals(mapOf(0 to 10_000L, 1 to 10_000L + 6_000L * first / (first + second), 2 to 16_000L), placed)
    }

    @Test
    fun aLineSungBeforeTheWhileHeardIsntTimedByItsLastWords() {
        // Only "by the window" falls in what was heard; the line started well before.
        val late = sungAt(28_000, lines[1]).filter { it.second >= 30_000 }
        assertEquals(emptyMap<Int, Long>(), SungTiming.linesMs(lines, listOf(heard(30_000, late)), 120_000))
    }

    @Test
    fun aLineHeardInTwoPlaysIsTimedOnce() {
        val placed = SungTiming.linesMs(lines, listOf(heard(20_000, sungAt(21_000, lines[1])), heard(12_000, sungAt(21_000, lines[1]))), 120_000)
        assertEquals(mapOf(1 to 21_000L), placed)
    }

    @Test
    fun wordsInJapaneseAreMatchedALetterAtATime() {
        val japanese = listOf("君の名前を呼んだ", "夜明けの空に")
        val heardSo = listOf("君の" to 10_550L, "名前を" to 10_690L, "呼んだ" to 10_900L)
        val placed = SungTiming.linesMs(japanese, listOf(heard(9_000, heardSo)), 60_000)
        // Each letter of a word heard has the word's start, so where the line starts is told within a letter.
        assertEquals(setOf(0), placed.keys)
        assertTrue(kotlin.math.abs(placed.getValue(0) - 10_000L) <= SungTiming.LETTER_MS)
    }

    @Test
    fun linesTimedByEarAreNeverLitTillTheyreHeard() {
        val withBreak = listOf(lines[0], lines[1], "", lines[2], lines[3])
        val timed = SungTiming.timed(withBreak, listOf(heard(9_000, sungAt(10_000, lines[0]), sungAt(14_000, lines[1]))), 120_000)
        assertEquals(withBreak, timed.map { it.text })
        assertEquals(10_000L, timed[0].timeMs)
        // The next line heard, it's lit till then.
        assertNull(timed[0].endMs)
        // The next not heard, it's lit till it's sung through.
        assertEquals(14_000L + SpokenWords.pieces(lines[1]).sumOf { it.length } * 120 + 1_000, timed[1].endMs)
        assertTrue(timed.drop(2).none { it.heard })
        assertEquals(LyricLine.NOT_HEARD, timed[3].timeMs)
    }

    @Test
    fun nothingSungHeardTimesNothing() {
        assertEquals(emptyList<LyricLine>(), SungTiming.timed(lines, listOf(heard(0, listOf("[Music]" to 1_000L))), 120_000))
        assertEquals(emptyList<LyricLine>(), SungTiming.timed(lines, emptyList(), 120_000))
        // No words to time.
        assertEquals(emptyMap<Int, Long>(), SungTiming.linesMs(listOf("", "♪"), listOf(heard(9_000, sungAt(10_000, lines[0]))), 120_000))
    }
}
