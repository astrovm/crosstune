package com.astrovm.crosstune

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class TimingEvidenceTest {
    private val lines = listOf(LyricLine(1000, "First"), LyricLine(5000, "Second"))

    @Test fun impossibleTimesAreRejected() {
        assertTrue(TimingEvidence.usable(lines))
        assertTrue(TimingEvidence.usable(lines, 5001))
        assertFalse(TimingEvidence.usable(lines, 5000))
        assertFalse(TimingEvidence.usable(emptyList()))
        assertFalse(TimingEvidence.usable(listOf(LyricLine(0, ""))))
        assertFalse(TimingEvidence.usable(listOf(LyricLine(-1, "First"))))
        assertFalse(TimingEvidence.usable(lines.reversed()))
        assertTrue(TimingEvidence.usable(lines + LyricLine(5001, ""), 5001))
        assertFalse(TimingEvidence.usable(listOf(LyricLine(1000, "First", endMs = 999))))
        assertTrue(TimingEvidence.usable(listOf(LyricLine(1000, "First", endMs = 1000))))
        assertFalse(TimingEvidence.usable(listOf(LyricLine(1000, "First", listOf(TimedWord(999, "First"))))))
        assertFalse(TimingEvidence.usable(listOf(LyricLine(1000, "First", listOf(TimedWord(2001, "First")), 2000))))
        assertFalse(TimingEvidence.usable(listOf(LyricLine(1000, "First", listOf(TimedWord(1500, "Fir"), TimedWord(1499, "st"))))))
        assertTrue(TimingEvidence.usable(listOf(LyricLine(1000, "First", listOf(TimedWord(1000, "First")), 2000))))
        assertTrue(TimingEvidence.usable(listOf(LyricLine(1000, "First", listOf(TimedWord(1000, "Fir"), TimedWord(2000, "st")), 2000))))
    }

    @Test fun copiedTimestampsAreOneVoteEvenWithDifferentWordsOrBlanks() {
        val translated = lines.map { it.copy(text = "Translated") }
        val blank = listOf(LyricLine(0, "")) + lines
        val different = lines.map { it.copy(timeMs = it.timeMs + 600) }
        assertEquals(listOf(lines, different), TimingEvidence.independent(listOf(lines, translated, blank, different)))
        assertEquals(emptyList<List<LyricLine>>(), TimingEvidence.independent(emptyList()))
    }

    @Test fun generatedOrderedTimesFitOnlyInsideTheirDurationAndCopiesHaveOneVote() {
        val random = Random(713)
        repeat(300) {
            val generated = (1..random.nextInt(2, 60)).map { random.nextLong(1, 300_000) }.distinct().sorted().map { LyricLine(it, "Word") }
            val last = generated.last().timeMs
            assertTrue(TimingEvidence.usable(generated, last + 1))
            assertFalse(TimingEvidence.usable(generated, last))
            assertEquals(listOf(generated), TimingEvidence.independent(List(random.nextInt(1, 20)) { generated }))
        }
    }
}
