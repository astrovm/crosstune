package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Recorded words heard over one play, including repeated choruses. */
class SungChorusTest {
    private fun replay(name: String, knownLength: Boolean = true, startAgain: Boolean = false, startLate: Boolean = false, skipAt: Int = -1, adjust: (MutableList<HeardStretch>, Long) -> Unit = { _, _ -> }): Map<Int, Long> {
        val lines = mutableListOf<String>()
        val stretches = mutableListOf<HeardStretch>()
        var lengthMs = 0L
        checkNotNull(javaClass.getResourceAsStream("/syncreplay/$name.tsv")).bufferedReader().useLines { rows ->
            rows.forEach { row ->
                val cells = row.split('\t')
                when (cells[0]) {
                    "N" -> lengthMs = cells[1].toLong()
                    "L" -> lines += cells[1]
                    "S" -> stretches += HeardStretch(cells[1].toLong(), cells[2].toLong(), emptyList())
                    "W" -> stretches[stretches.lastIndex] = stretches.last().let { it.copy(words = it.words + (cells[2] to cells[1].toLong())) }
                }
            }
        }
        adjust(stretches, lengthMs)
        val heard = when {
            startAgain -> stretches + stretches.first()
            startLate -> stretches.drop(1)
            skipAt >= 0 -> stretches.filterIndexed { index, _ -> index != skipAt }
            else -> stretches
        }
        return SungTiming.linesMs(lines, heard, lengthMs, knownLength)
    }

    @Test
    fun aRepeatedChorusDoesNotTakeAnUnplacedCopiesHearing() {
        val lines = replay("wrong-chorus")
        // The hearing also fits an unplaced copy of the chorus; neither copy is established.
        assertFalse(lines.containsKey(27))
        assertFalse(lines.containsKey(33))
    }

    @Test
    fun noHeardCandidatesLeaveEveryLineUntimed() {
        assertEquals(emptyMap<Int, Long>(), SungTiming.linesMs(listOf("Words to sing"), emptyList(), 60_000))
        assertEquals(emptyMap<Int, Long>(), SungTiming.linesMs(listOf("Words to sing"), listOf(HeardStretch(0, 20_000, emptyList())), 60_000))
    }

    @Test
    fun aSeparatePlacedCopyDoesNotHideTheHeardChorus() {
        val lines = replay("repeated-lines")
        assertEquals(50_020L, lines[14])
        assertEquals(52_470L, lines[15])
    }

    @Test
    fun aGuessedLengthDoesNotSayTheFirstPlayEnded() {
        val lines = replay("wrong-chorus", knownLength = false)
        assertEquals(176_490L, lines[27])
        assertEquals(186_310L, lines[33])
    }

    @Test
    fun aSecondPlayKeepsTheExistingPlacementWhileItIsHeard() {
        assertEquals(replay("wrong-chorus", knownLength = false, startAgain = true), replay("wrong-chorus", startAgain = true))
    }

    @Test
    fun reachingTheEndAfterOpeningLateIsNotACompleteFirstPlay() {
        assertEquals(replay("wrong-chorus", knownLength = false, startLate = true), replay("wrong-chorus", startLate = true))
    }

    @Test
    fun skippingPartOfTheSongIsNotACompleteFirstPlay() {
        for (at in 1..10) {
            assertEquals("skipped hearing $at", replay("wrong-chorus", knownLength = false, skipAt = at), replay("wrong-chorus", skipAt = at))
        }
    }

    @Test
    fun aCompletePlayIncludesTheExactStartAndEndMargins() {
        fun adjusted(start: Long, endBefore: Long, known: Boolean) = replay("wrong-chorus", knownLength = known) { stretches, length ->
            stretches[0] = stretches.first().copy(fromMs = start)
            stretches[stretches.lastIndex] = stretches.last().copy(toMs = length - endBefore)
        }
        assertFalse(adjusted(1_000, 1_000, true).containsKey(27))
        assertEquals(adjusted(1_001, 1_000, false), adjusted(1_001, 1_000, true))
        assertEquals(adjusted(1_000, 1_001, false), adjusted(1_000, 1_001, true))
    }

    @Test
    fun aCompletePlayAllowsTheExactGapAndEqualStartTimes() {
        fun adjusted(gap: Long, sameStart: Boolean, known: Boolean) = replay("wrong-chorus", knownLength = known) { stretches, _ ->
            stretches[1] = stretches[1].copy(fromMs = if (sameStart) stretches[0].fromMs else stretches[0].toMs + gap)
        }
        assertFalse(adjusted(10_000, false, true).containsKey(27))
        assertEquals(adjusted(10_001, false, false), adjusted(10_001, false, true))
        assertFalse(adjusted(0, true, true).containsKey(27))
    }

    private fun candidates(vararg points: Triple<Int, Long, Double>): Map<Int, Long> {
        val placed = points.map { (line, ms, support) ->
            SungTiming.Placed(line, ms, support, 3, SungTiming.HeardAt(ms, 0)..SungTiming.HeardAt(ms + 500, 0), 0)
        }
        return SungTiming.sure(placed, 7, (0..5).associateWith { 1_000L }, List(6) { "same words" }).associate { it.line to it.ms }
    }

    @Test
    fun aRepeatedAlternativeAtTheSupportBoundaryStillCounts() {
        val found = candidates(Triple(4, 2_500, 8.0), Triple(3, 8_000, 5.0), Triple(1, 2_000, 2.0), Triple(2, 5_000, 4.0))
        assertEquals(emptyMap<Int, Long>(), found)
    }

    @Test
    fun aRepeatedAlternativeExactlyTwoSecondsAwayIsNotTheSameHearing() {
        val found = candidates(Triple(1, 0, 5.0), Triple(4, 3_000, 2.0), Triple(1, 3_000, 2.0),
            Triple(5, 2_000, 4.0), Triple(4, 1_000, 3.0), Triple(4, 4_000, 8.0))
        assertEquals(mapOf(1 to 0L), found)
    }

    @Test
    fun anAlternativeAtTheChainWeightBoundaryIsAmbiguous() {
        val found = candidates(Triple(0, 2_000, 2.5), Triple(3, 2_500, 2.0), Triple(1, 2_500, 2.5))
        assertEquals(emptyMap<Int, Long>(), found)
    }

    @Test
    fun anAlternativeCountsItsSharedCandidateOnlyOnce() {
        val found = candidates(Triple(4, 4_000, 4.0), Triple(0, 1_000, 10.0), Triple(1, 5_000, 10.0),
            Triple(3, 10_000, 8.0), Triple(1, 3_000, 6.0), Triple(4, 3_000, 5.0), Triple(1, 1_000, 4.0),
            Triple(0, 5_000, 10.0), Triple(5, 2_000, 6.0))
        assertEquals(mapOf(0 to 1_000L, 1 to 5_000L, 3 to 10_000L), found)
    }

    @Test
    fun aLaterAlternativeAlsoCountsItsSharedCandidateOnlyOnce() {
        val found = candidates(Triple(5, 2_000, 2.0), Triple(1, 500, 4.0), Triple(2, 3_000, 8.0),
            Triple(4, 0, 1.0), Triple(1, 8_000, 3.0), Triple(4, 1_000, 4.0), Triple(2, 4_000, 8.0),
            Triple(2, 4_000, 6.0), Triple(5, 10_000, 8.0))
        assertEquals(mapOf(1 to 500L, 5 to 10_000L), found)
    }
}
