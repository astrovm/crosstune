package com.astrovm.crosstune

import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class TimingLogTest {
    private var now = 1_000_000L

    @Before
    fun setUp() {
        TimingLog.clear()
        TimingLog.clock = { now }
    }

    @After
    fun tearDown() {
        TimingLog.clear()
        TimingLog.clock = android.os.SystemClock::elapsedRealtime
    }

    @Test
    fun eachNoteIsKeptAfterTheTimeItWasMadeAt() {
        TimingLog.note("said 5000 at 999000")
        now += 1_500
        TimingLog.note("lit 2 at 1000500")
        assertEquals("1000000 said 5000 at 999000\n1001500 lit 2 at 1000500", TimingLog.lastMinute())
    }

    @Test
    fun onlyTheLastMinuteIsKept() {
        TimingLog.note("old")
        now += TimingLog.KEEP_MS
        TimingLog.note("edge")
        // A minute ago to the ms is still in it, and a moment more isn't.
        assertEquals("1000000 old\n1060000 edge", TimingLog.lastMinute())
        now += 1
        assertEquals("1060000 edge", TimingLog.lastMinute())
        TimingLog.note("new")
        assertEquals("1060000 edge\n1060001 new", TimingLog.lastMinute())
        now += TimingLog.KEEP_MS + 1
        assertEquals("", TimingLog.lastMinute())
    }

    @Test
    fun aBusyMinuteKeepsTheLatestNotesAndNoMoreThanItsBound() {
        repeat(TimingLog.MAX_NOTES + 30) { TimingLog.note("note $it") }
        val kept = TimingLog.lastMinute().lines()
        assertEquals(TimingLog.MAX_NOTES, kept.size)
        assertEquals("1000000 note 30", kept.first())
        assertEquals("1000000 note ${TimingLog.MAX_NOTES + 29}", kept.last())
    }

    @Test
    fun aLongNoteIsCutAndTheMinuteIsBoundedInCharacters() {
        repeat(TimingLog.MAX_NOTES) { TimingLog.note("$it ".padEnd(1_000, 'x')) }
        val minute = TimingLog.lastMinute()
        assertTrue(minute.length.toString(), minute.length <= TimingLog.MAX_CHARS)
        // Whole notes, the latest ones, each cut to its own bound.
        val lines = minute.lines()
        assertTrue(lines.all { it.length <= "1000000 ".length + 300 })
        assertTrue(lines.size > 20)
        assertTrue(lines.last().startsWith("1000000 ${TimingLog.MAX_NOTES - 1} "))
    }

    @Test
    fun nothingGoesToLogcatUnlessTimingIsTurnedOn() {
        TimingLog.note("quiet")
        assertEquals(emptyList<String>(), ShadowLog.getLogsForTag(TIMING_LOG).map { it.msg })
        ShadowLog.setLoggable(TIMING_LOG, Log.DEBUG)
        TimingLog.note("loud")
        // As noted, without the time in front, as the phone check reads it.
        assertEquals(listOf("loud"), ShadowLog.getLogsForTag(TIMING_LOG).map { it.msg })
        assertTrue("quiet" in TimingLog.lastMinute())
    }

    @Test
    fun clearForgetsEverything() {
        TimingLog.note("something")
        TimingLog.clear()
        assertFalse(TimingLog.lastMinute().contains("something"))
    }
}
