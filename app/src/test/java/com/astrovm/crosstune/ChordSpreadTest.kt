package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the chords over one line of words are moved apart, with plain numbers for the widths. */
class ChordSpreadTest {

    /** Whether any two of [xs] on the same row run into each other. */
    private fun overlaps(xs: List<Float>, rows: List<Int>, widths: List<Float>) =
        xs.indices.any { index -> index > 0 && rows[index] == rows[index - 1] && xs[index] < xs[index - 1] + widths[index - 1] - 0.01f }

    @Test
    fun chordsOverTheSameLetterAreMovedApart() {
        val xs = spreadChords(listOf(0f, 0f, 10f), listOf(0, 0, 0), listOf(20f, 20f, 20f), 300f, 4f)
        assertEquals(listOf(0f, 24f, 48f), xs)
    }

    @Test
    fun chordsThatRunPastTheEdgeAreMovedBackLeftTogether() {
        val xs = spreadChords(listOf(250f, 260f), listOf(0, 0), listOf(30f, 30f), 300f, 4f)
        assertEquals(listOf(236f, 270f), xs)
    }

    @Test
    fun aFullRowKeepsEveryChordApartByTighteningTheGapsNotByStackingTheFirstOnes() {
        // Sixteen chords 20 wide are 320 of a 330 wide row: the 4 wide gaps need 60 more than the row has.
        val widths = List(16) { 20f }
        val rows = List(16) { 0 }
        val xs = spreadChords(List(16) { 0f }, rows, widths, 330f, 4f)
        assertTrue(xs.toString(), xs.all { it >= 0f })
        assertTrue(xs.toString(), xs.last() + 20f <= 330.01f)
        assertTrue(xs.toString(), !overlaps(xs, rows, widths))
    }

    @Test
    fun aRowOfChordsWiderThanTheLineKeepsTheirOrder() {
        val widths = List(4) { 30f }
        val xs = spreadChords(List(4) { 0f }, List(4) { 0 }, widths, 100f, 4f)
        assertEquals(xs.sorted(), xs)
    }

    @Test
    fun chordsOnOtherRowsDoNotPushEachOther() {
        val xs = spreadChords(listOf(0f, 0f), listOf(0, 1), listOf(30f, 30f), 100f, 4f)
        assertEquals(listOf(0f, 0f), xs)
    }
}
