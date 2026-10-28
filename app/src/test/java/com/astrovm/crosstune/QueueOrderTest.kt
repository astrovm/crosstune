package com.astrovm.crosstune

import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Time passes on the phone's clock, as Robolectric moves it on. */
@RunWith(RobolectricTestRunner::class)
class QueueOrderTest {
    private val music = MusicService.YOUTUBE_MUSIC.packageName
    private val phone = FakePlayback()
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val order = QueueOrder(phone, scope)
    private val ordered = order.ordering(phone)
    private val song = MusicMetadata("Song", "Band")
    private val a = Queued("A", "Band", 1)
    private val b = Queued("B", "Band", 2)
    private val c = Queued("C", "Band", 3)
    private val later = Queued("Later", "Band")

    @After
    fun tearDown() = scope.cancel()

    private fun passes(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** YouTube Music on the song it numbers [id], [at] so far into its 200 seconds, with [next] after it. */
    private fun plays(
        id: Long,
        next: List<Queued>,
        at: Long = 0,
        playing: Boolean = true,
        speed: Float = 1f,
        repeat: Repeat = Repeat.OFF,
        durationMs: Long? = 200_000
    ) {
        phone.playing.value = Following(
            PlaybackClock(at, SystemClock.elapsedRealtime(), speed, playing), "YouTube Music", canSeek = true,
            appPackage = music, durationMs = durationMs, repeat = repeat, upNext = next
        )
        phone.inApp(music).value = AppPlays(MusicMetadata("Song $id", "Band"), id)
        passes(0)
    }

    /** What's up next as the player shows it, in the order, which is what a song's moved in. */
    private fun following() = runBlocking { ordered.follow(song).first() }!!

    private fun shown() = following().upNext

    @Test
    fun movedTheAppsSongsShowAndPlayInThatOrderEachAMomentBeforeTheOneBeforeEnds() {
        plays(10, listOf(a, b, c, later))
        order.move(following(), 2, 0)
        assertEquals(listOf(c, a, b, later), shown())
        passes(200_000 - SleepTimer.SONG_END_EARLY_MS - 1)
        assertTrue(phone.queuedPlayed.isEmpty())
        passes(1)
        assertEquals(listOf(3L), phone.queuedPlayed)
        // On there, the app has what's behind it no more, and the rest of the order follows.
        plays(3, listOf(Queued("D", "Band", 4), later))
        assertEquals(listOf(a, b, Queued("D", "Band", 4), later), shown())
        passes(200_000)
        assertEquals(listOf(3L, 1L), phone.queuedPlayed)
        plays(1, emptyList())
        passes(200_000)
        assertEquals(listOf(3L, 1L, 2L), phone.queuedPlayed)
        // Played through, the app's own order is back.
        plays(2, listOf(Queued("D", "Band", 4)))
        passes(200_000)
        assertEquals(listOf(3L, 1L, 2L), phone.queuedPlayed)
        assertEquals(listOf(Queued("D", "Band", 4)), shown())
    }

    @Test
    fun theNextButtonGoesOnInTheOrder() {
        plays(10, listOf(a, b, c))
        ordered.skip(song, next = true)
        assertEquals(listOf(true), phone.skips)
        order.move(following(), 1, 0)
        ordered.skip(song, next = true)
        assertEquals(listOf(2L), phone.queuedPlayed)
        // Back is the app's own.
        ordered.skip(song, next = false)
        assertEquals(listOf(true, false), phone.skips)
        // Off, so is next.
        order.off()
        ordered.skip(song, next = true)
        assertEquals(listOf(true, false, true), phone.skips)
        assertEquals(listOf(a, b, c), shown())
    }

    @Test
    fun whereTheAppGoesOnToItByItselfItIsntAsked() {
        plays(10, listOf(a, b, c))
        order.move(following(), 2, 1)
        assertEquals(listOf(a, c, b), shown())
        passes(200_000)
        assertTrue(phone.queuedPlayed.isEmpty())
        plays(1, listOf(b, c))
        passes(200_000)
        assertEquals(listOf(3L), phone.queuedPlayed)
    }

    @Test
    fun repeatingOnePausedOrOfNoKnownLengthItWaits() {
        plays(10, listOf(a, b), repeat = Repeat.ONE)
        order.move(following(), 1, 0)
        passes(200_000)
        plays(10, listOf(a, b), playing = false)
        passes(200_000)
        plays(10, listOf(a, b), durationMs = null)
        passes(200_000)
        assertTrue(phone.queuedPlayed.isEmpty())
        // Played on, it's asked once the rest of the song has played, at the speed it plays at.
        plays(10, listOf(a, b), at = 100_000, speed = 2f)
        passes(50_000 - SleepTimer.SONG_END_EARLY_MS - 1)
        assertTrue(phone.queuedPlayed.isEmpty())
        passes(1)
        assertEquals(listOf(2L), phone.queuedPlayed)
    }

    @Test
    fun somethingElsePlayedInTheAppEndsTheOrder() {
        plays(10, listOf(a, b))
        order.move(following(), 1, 0)
        plays(99, listOf(a, b))
        assertEquals(listOf(a, b), shown())
        passes(200_000)
        assertTrue(phone.queuedPlayed.isEmpty())
    }

    @Test
    fun onlyTheAppsSongsMoveAndOnlyInAnAppItKnows() {
        plays(10, listOf(a, b, later))
        order.move(following(), 0, 2)
        order.move(following(), -1, 0)
        order.move(following(), 2, 0)
        order.move(following().copy(appPackage = null), 1, 0)
        assertEquals(listOf(a, b, later), shown())
        passes(200_000)
        assertTrue(phone.queuedPlayed.isEmpty())
        // Moved again, the last move is the one kept.
        order.move(following(), 1, 0)
        order.move(following(), 0, 1)
        assertEquals(listOf(a, b, later), shown())
        passes(200_000)
        assertTrue(phone.queuedPlayed.isEmpty())
    }
}
