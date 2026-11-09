package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineWindowTest {
    private fun line(start: Long? = 10_000, end: Long? = 15_000) = SavedLine("Night sky", translation = "Cielo", title = "Demo", artist = "Band", startMs = start, endMs = end)

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
}
