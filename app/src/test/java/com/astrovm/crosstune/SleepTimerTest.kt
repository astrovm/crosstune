package com.astrovm.crosstune

import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun theSongIsPausedAsItEndsInAnAppThatSendsNoArtist() {
        // The app has the song by its title alone, as heard by "Band"; asked of the app or of none.
        val player = Unnamed("com.example.player", "Fake Player", "Song")
        listOf(player.app, null).forEach { app ->
            phone.plays.clear()
            phone.appPlaying[player.app] = MutableStateFlow(Following(PlaybackClock(190_000, SystemClock.elapsedRealtime(), 1f, true), "Fake Player", canSeek = true, appPackage = player.app, durationMs = 200_000))
            phone.playing.value = phone.appPlaying[player.app]?.value
            phone.unnamed.value = player
            timer.afterSong(song, app)
            passes(9_499)
            assertTrue("$app", phone.plays.isEmpty())
            passes(1)
            assertEquals(listOf(false), phone.plays)
            assertNull(timer.set.value)
        }
    }

    /** A radio station playing in its own app, with no song named, and nothing else playing. */
    private fun stationPlays(): Unnamed {
        val station = Unnamed("com.example.radio", "Fake Radio", "Fake FM")
        phone.appPlaying[station.app] = MutableStateFlow(Following(PlaybackClock(0, SystemClock.elapsedRealtime(), 1f, true), "Fake Radio", canSeek = false))
        phone.unnamed.value = station
        // The song's app lets go of it as the station takes over.
        phone.onThePhone = null
        phone.playing.value = null
        passes(0)
        return station
    }

    @Test
    fun afterSoManyMinutesAStationIsPausedInItsApp() {
        val station = stationPlays()
        timer.after(15)
        passes(15 * 60_000)
        assertEquals(listOf(false), phone.plays)
        assertEquals(listOf<String?>(station.app), phone.playedApps)
        assertNull(timer.set.value)
    }

    @Test
    fun aStationCastToASpeakerIsPausedThere() {
        val station = stationPlays()
        phone.unnamed.value = station.copy(where = Place.ELSEWHERE)
        timer.after(15)
        passes(15 * 60_000)
        assertEquals(listOf(false), phone.plays)
        assertEquals(listOf<String?>(station.app), phone.playedApps)
    }

    @Test
    fun aStationOnThePhoneIsPausedNotASongOnAComputer() {
        val station = stationPlays()
        phone.onThePhone = Heard.Song(MusicMetadata("Fake Song", "Fake Band"), null, app = "org.kde.kdeconnect_tp")
        phone.songWhere = Place.ELSEWHERE
        timer.after(15)
        passes(15 * 60_000)
        assertEquals(listOf(false), phone.plays)
        assertEquals(listOf<String?>(station.app), phone.playedApps)
    }

    @Test
    fun aSongLeftForAStationPausesTheStation() {
        // Set on a song, then the user puts a station on in another app: it's what's paused, as the song is gone.
        plays(10_000)
        timer.after(15)
        var station = stationPlays()
        passes(15 * 60_000)
        assertEquals(listOf(false), phone.plays)
        assertEquals(listOf<String?>(station.app), phone.playedApps)
        // Set to the song's end, as well.
        plays(10_000)
        timer.afterSong(song)
        station = stationPlays()
        assertEquals(listOf(false, false), phone.plays)
        assertEquals(listOf<String?>(station.app, station.app), phone.playedApps)
        assertNull(timer.set.value)
    }

    @Test
    fun aFileWithNoSongNamedIsFollowedInItsAppAndPausedAsItEnds() {
        val file = Unnamed("com.example.player", "Fake Player", "Fake Song", artist = "<unknown>")
        phone.onThePhone = null
        phone.unnamed.value = file
        phone.appPlaying[file.app] = MutableStateFlow(Following(PlaybackClock(190_000, SystemClock.elapsedRealtime(), 1f, true), "Fake Player", canSeek = true, durationMs = 200_000))
        timer.afterSong(file.played, file.app)
        passes(9_499)
        assertTrue(phone.plays.isEmpty())
        passes(1)
        assertEquals(listOf(false), phone.plays)
        assertEquals(listOf<String?>(file.app), phone.playedApps)
    }

    @Test
    fun aFileWithNoSongNamedIsStillPausedAsItEndsOnceItsAppNamesWhoSingsIt() {
        val file = Unnamed("com.example.player", "Fake Player", "Fake Song")
        phone.onThePhone = null
        phone.unnamed.value = file
        phone.appPlaying[file.app] = MutableStateFlow(Following(PlaybackClock(10_000, SystemClock.elapsedRealtime(), 1f, true), "Fake Player", canSeek = true, appPackage = file.app, durationMs = 200_000))
        timer.afterSong(file.played, file.app)
        passes(0)
        // Mid-song, the app names who sings it: the same song plays on, and the timer still waits for its end.
        phone.onThePhone = Heard.Song(MusicMetadata("Fake Song", "Fake Band"), null, app = file.app)
        phone.unnamed.value = null
        passes(180_000)
        assertTrue(phone.plays.isEmpty())
        assertEquals(Sleep(), timer.set.value)
        passes(9_500)
        assertEquals(listOf(false), phone.plays)
        assertEquals(listOf<String?>(file.app), phone.playedApps)
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
