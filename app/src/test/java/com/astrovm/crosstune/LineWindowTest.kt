package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineWindowTest {
    private fun line(start: Long? = 10_000, end: Long? = 15_000, next: Long? = null) =
        SavedLine("Night sky", translation = "Cielo", title = "Demo", artist = "Band", startMs = start, endMs = end, nextMs = next)

    @Test
    fun aLineEndsAMomentAfterTheWordsDo() {
        assertEquals(LineWindow(10_000, 15_000 + MainViewModel.LINE_TAIL_MS), LineWindow.of(line(), lead = 0))
        assertEquals(10_000L, LineWindow.of(line(), lead = 0).start)
        assertEquals(15_000L + MainViewModel.LINE_TAIL_MS, LineWindow.of(line(), lead = 0).end)
    }

    @Test
    fun aVideosLeadMovesBothEndsOfTheLine() {
        assertEquals(LineWindow(12_000, 17_000 + MainViewModel.LINE_TAIL_MS), LineWindow.of(line(), lead = 2_000))
    }

    @Test
    fun aLineWithNoTimesIsTakenFromTheStart() {
        assertEquals(LineWindow(0, MainViewModel.LINE_TAIL_MS), LineWindow.of(line(start = null, end = null), lead = 0))
    }

    @Test
    fun aLineStopsAtTheNextLineWhenItStartsInsideTheTail() {
        // The words end at 15 s and the next line starts at 15.4 s, inside the one second tail.
        assertEquals(LineWindow(10_000, 15_400), LineWindow.of(line(next = 15_400), lead = 0))
        // The stored end is the next line's start, so nothing past it plays.
        assertEquals(LineWindow(10_000, 20_000), LineWindow.of(line(end = 20_000, next = 20_000), lead = 0))
        // The next line starts before the words end. Play still stops there.
        assertEquals(LineWindow(10_000, 12_000), LineWindow.of(line(next = 12_000), lead = 0))
    }

    @Test
    fun aLineKeepsItsTailWhenTheNextLineIsFartherThanThat() {
        assertEquals(LineWindow(10_000, 15_000 + MainViewModel.LINE_TAIL_MS), LineWindow.of(line(next = 20_000), lead = 0))
    }

    @Test
    fun aVideosLeadMovesTheNextLineToo() {
        assertEquals(LineWindow(12_000, 17_400), LineWindow.of(line(next = 15_400), lead = 2_000))
    }

    @Test
    fun aNextLineThatDoesNotStartAfterThisOneLeavesTheTail() {
        assertEquals(LineWindow(10_000, 15_000 + MainViewModel.LINE_TAIL_MS), LineWindow.of(line(next = 10_000), lead = 0))
        assertEquals(LineWindow(10_000, 15_000 + MainViewModel.LINE_TAIL_MS), LineWindow.of(line(next = 9_000), lead = 0))
    }

    @Test
    fun anAppIsAtTheLineFromJustBeforeItsStartToItsEnd() {
        val window = LineWindow(10_000, 16_000)
        assertFalse(window.holds(10_000 - LineWindow.SEEK_SLACK_MS - 1))
        assertTrue(window.holds(10_000 - LineWindow.SEEK_SLACK_MS))
        assertTrue(window.holds(10_000))
        assertTrue(window.holds(15_999))
        assertFalse(window.holds(16_000))
    }

    @Test
    fun anAppIsNearTheLineWithinWhatAppsRoundOffEitherSide() {
        val window = LineWindow.of(line(), lead = 0)
        assertTrue(window.near(10_000 - LineWindow.SEEK_SLACK_MS))
        assertTrue(window.near(10_000 + LineWindow.SEEK_SLACK_MS))
        assertFalse(window.near(10_000 - LineWindow.SEEK_SLACK_MS - 1))
        assertFalse(window.near(10_000 + LineWindow.SEEK_SLACK_MS + 1))
        // Back at the song's start, as after an error, it isn't.
        assertFalse(window.near(0))
    }

    @Test
    fun aLineIsOverAtItsEndNotBefore() {
        val window = LineWindow(10_000, 16_000)
        assertFalse(window.over(15_999))
        assertTrue(window.over(16_000))
        assertTrue(window.over(20_000))
    }

    @Test
    fun anAppIsGivenTheLineAndTheTimeToSayWhereItIs() {
        assertEquals(6_000 + LineWindow.SEEK_WAIT_MS, LineWindow(10_000, 16_000).patience)
    }

    @Test
    fun somethingHasLastedOnceItHasBeenSoLongAndNeverBeforeItBegan() {
        assertFalse(lasted(null, now = 5_000, atLeast = 1_000))
        assertFalse(lasted(4_001, now = 5_000, atLeast = 1_000))
        assertTrue(lasted(4_000, now = 5_000, atLeast = 1_000))
        assertTrue(lasted(1_000, now = 5_000, atLeast = 1_000))
    }

    @Test
    fun aLineEndsBeforeWhatTheAppPlaysDoes() {
        val window = LineWindow(10_000, 16_000)
        // Longer than the line: as it is.
        assertEquals(window, window.within(200_000))
        assertEquals(window, window.within(16_000 + LineWindow.END_GUARD_MS))
        // Ending within it: the line ends a moment before, so the app is paused before going on.
        assertEquals(LineWindow(10_000, 15_000 - LineWindow.END_GUARD_MS), window.within(15_000))
        // Over by the line's start, or right at it: there's no line there to play.
        assertEquals(null, window.within(10_000 + LineWindow.END_GUARD_MS))
        assertEquals(LineWindow(10_000, 10_001), window.within(10_001 + LineWindow.END_GUARD_MS))
        assertEquals(null, window.within(8_000))
        // Not known, or said as nothing: as it is.
        assertEquals(window, window.within(null))
        assertEquals(window, window.within(0))
        assertEquals(LineWindow(0, 1), LineWindow(0, 1).within(1 + LineWindow.END_GUARD_MS))
    }
}
