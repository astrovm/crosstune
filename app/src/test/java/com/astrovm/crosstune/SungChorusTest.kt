package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Recorded words heard over one play, including repeated choruses. */
class SungChorusTest {
    private fun replay(name: String): Map<Int, Long> {
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
        return SungTiming.linesMs(lines, stretches, lengthMs)
    }

    @Test
    fun aRepeatedChorusDoesNotTakeAnUnplacedCopiesHearing() {
        val lines = replay("wrong-chorus")
        // The hearing also fits an unplaced copy of the chorus; neither copy is established.
        assertFalse(lines.containsKey(27))
        assertFalse(lines.containsKey(33))
    }

    @Test
    fun aSeparatePlacedCopyDoesNotHideTheHeardChorus() {
        val lines = replay("repeated-lines")
        assertEquals(50_020L, lines[14])
        assertEquals(52_470L, lines[15])
    }
}
