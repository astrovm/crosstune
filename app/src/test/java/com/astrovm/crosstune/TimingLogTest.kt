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

    /** The phone's clock the log starts with, put back after each test so the tests of everything else notes with it. */
    private lateinit var phonesClock: () -> Long

    @Before
    fun setUp() {
        TimingLog.clear()
        phonesClock = TimingLog.clock
        TimingLog.clock = { now }
    }

    @After
    fun tearDown() {
        TimingLog.clear()
        TimingLog.clock = phonesClock
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
    fun anAppSayingTheSameAgainIsNotedOnce() {
        val clocks = SteadyClock(null) {}
        val playing = PlaybackClock(10_000, 999_000)
        clocks.of(playing)
        // Going on as it was, a few ms off, and a paused clock said over and over.
        clocks.of(PlaybackClock(10_503, 999_500 + 5))
        clocks.of(PlaybackClock(10_510, 999_510))
        assertEquals(1, TimingLog.lastMinute().lines().size)
        // Somewhere else, stopped, then the same stop said again, then going again at another speed.
        clocks.of(PlaybackClock(15_000, 999_600))
        clocks.of(PlaybackClock(15_100, 999_700, playing = false))
        clocks.of(PlaybackClock(15_100, 999_700, playing = false))
        clocks.of(PlaybackClock(15_100, 999_900, speed = 1.5f))
        assertEquals(4, TimingLog.lastMinute().lines().size)
    }

    @Test
    fun everyoneFollowingAnAppNotesItsClockOnce() {
        val noted = NotedClock()
        val one = SteadyClock(null, noted) {}
        val another = SteadyClock(null, noted) {}
        val said = PlaybackClock(10_000, 999_000, playing = false)
        one.of(said)
        another.of(said)
        assertEquals(1, TimingLog.lastMinute().lines().size)
    }

    @Test
    fun theCharacterBoundKeepsAMinuteThatFillsItExactly() {
        // 80 lines of 99 characters and their line break are exactly 8,000.
        val note = "x".repeat(99 - "1000000 ".length)
        repeat(TimingLog.MAX_CHARS / 100) { TimingLog.note(note) }
        val full = TimingLog.lastMinute()
        assertEquals(TimingLog.MAX_CHARS / 100, full.lines().size)
        assertEquals(TimingLog.MAX_CHARS - 1, full.length)
        // One more and the oldest goes.
        TimingLog.note(note)
        assertEquals(TimingLog.MAX_CHARS / 100, TimingLog.lastMinute().lines().size)
    }

    @Test
    fun aMinuteOneCharacterOverTheBoundLosesItsOldestNote() {
        // The oldest is a character longer than the rest, so together they're one over 8,000.
        TimingLog.note("x".repeat(100 - "1000000 ".length))
        repeat(TimingLog.MAX_CHARS / 100 - 1) { TimingLog.note("x".repeat(99 - "1000000 ".length)) }
        assertEquals(TimingLog.MAX_CHARS / 100 - 1, TimingLog.lastMinute().lines().size)
    }

    @Test
    fun aClockIsOnlyNewWhenItIsMoreThanAFewMsFromWhereItWasGoing() {
        fun noted(positionMs: Long): Int {
            TimingLog.clear()
            val clocks = SteadyClock(null) {}
            clocks.of(PlaybackClock(10_000, 999_000))
            clocks.of(PlaybackClock(positionMs, 999_500))
            return TimingLog.lastMinute().lines().size
        }
        // Going on, it'd be at 10,500 now.
        assertEquals(1, noted(10_520))
        assertEquals(2, noted(10_521))
        assertEquals(1, noted(10_480))
        assertEquals(2, noted(10_479))
    }

    @Test
    fun clearForgetsEverything() {
        TimingLog.note("something")
        TimingLog.clear()
        assertFalse(TimingLog.lastMinute().contains("something"))
    }
}
