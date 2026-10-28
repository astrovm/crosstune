package com.astrovm.crosstune

import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Time passes on the phone's clock, as Robolectric moves it on. */
@RunWith(RobolectricTestRunner::class)
class SleepTimerTest {
    private val phone = FakePlayback().apply { onThePhone = Heard.Song(MusicMetadata("Playing", "Band"), null) }
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val timer = SleepTimer(phone, scope)
    private val song = MusicMetadata("Song", "Band")

    @After
    fun tearDown() = scope.cancel()

    private fun passes(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** [song], [at] so far into its 200 seconds, playing or not. */
    private fun plays(at: Long, playing: Boolean = true, speed: Float = 1f, durationMs: Long? = 200_000) {
        phone.playing.value = Following(PlaybackClock(at, SystemClock.elapsedRealtime(), speed, playing), "YouTube Music", canSeek = true, durationMs = durationMs)
        passes(0)
    }

    @Test
    fun afterSoManyMinutesWhatPlaysIsPaused() {
        timer.after(15)
        assertEquals(Sleep(SystemClock.elapsedRealtime() + 15 * 60_000), timer.set.value)
        passes(15 * 60_000 - 1)
        assertTrue(phone.plays.isEmpty())
        passes(1)
        assertEquals(listOf(false), phone.plays)
        assertNull(timer.set.value)
        // With nothing playing by then, there's nothing to pause.
        phone.onThePhone = null
        timer.after(1)
        passes(60_000)
        assertEquals(listOf(false), phone.plays)
        assertNull(timer.set.value)
    }

    @Test
    fun theSongIsPausedAMomentBeforeItEnds() {
        plays(190_000)
        timer.afterSong(song)
        assertEquals(Sleep(), timer.set.value)
        passes(9_499)
        assertTrue(phone.plays.isEmpty())
        passes(1)
        assertEquals(listOf(false), phone.plays)
        assertNull(timer.set.value)
    }

    @Test
    fun itWaitsWhileTheSongIsPausedMovedOrOfNoKnownLength() {
        plays(190_000, playing = false)
        timer.afterSong(song)
        passes(60_000)
        assertTrue(phone.plays.isEmpty())
        plays(190_000, durationMs = null)
        passes(60_000)
        assertTrue(phone.plays.isEmpty())
        // Moved back, it waits for the new end, at the speed it plays.
        plays(190_000)
        passes(5_000)
        plays(100_000, speed = 2f)
        passes(49_000)
        assertTrue(phone.plays.isEmpty())
        passes(500)
        assertEquals(listOf(false), phone.plays)
    }

    @Test
    fun theSongSkippedOrStoppedPausesWhatPlaysInstead() {
        plays(10_000)
        timer.afterSong(song)
        phone.playing.value = null
        passes(0)
        assertEquals(listOf(false), phone.plays)
        assertNull(timer.set.value)
        // With nothing playing then, there's nothing to pause.
        phone.onThePhone = null
        plays(10_000)
        timer.afterSong(song)
        phone.playing.value = null
        passes(0)
        assertEquals(listOf(false), phone.plays)
        assertNull(timer.set.value)
    }

    @Test
    fun turnedOffOrSetAgainTheOldOneNeverPauses() {
        timer.after(15)
        timer.off()
        assertNull(timer.set.value)
        passes(15 * 60_000)
        assertTrue(phone.plays.isEmpty())
        timer.after(15)
        timer.after(30)
        assertEquals(Sleep(SystemClock.elapsedRealtime() + 30 * 60_000), timer.set.value)
        passes(15 * 60_000)
        assertTrue(phone.plays.isEmpty())
        passes(15 * 60_000)
        assertEquals(listOf(false), phone.plays)
    }
}
