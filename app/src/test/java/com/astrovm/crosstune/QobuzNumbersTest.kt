package com.astrovm.crosstune

import android.os.Looper
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Qobuz stuck on a loading page for a number its account's catalog doesn't have (Issue #673). */
@RunWith(RobolectricTestRunner::class)
class QobuzNumbersTest {
    private val qobuz = MusicService.QOBUZ.packageName
    private val phone = FakePlayback().apply { access = true }
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var other: String? = "https://play.qobuz.com/track/2"
    private val looked = mutableListOf<Pair<MusicMetadata, String>>()
    private val numbers = QobuzNumbers(phone, { song, link -> looked += song to link; other }, { true }, scope)
    private val song = MusicMetadata("Song", "Band")
    private val link = "https://play.qobuz.com/track/1"

    @After
    fun tearDown() = scope.cancel()

    private fun passes(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun aSongThatDoesntPlayIsOpenedOnTheNumberOfItsOtherStore() {
        numbers.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS - 1)
        assertTrue(phone.opened.isEmpty())
        passes(1)
        assertEquals(listOf(song to link), looked)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
        assertTrue(phone.songsAsked.isEmpty())
        // That one plays: nothing more's asked.
        phone.onThePhone = Heard.Song(song, null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS * 3)
        assertEquals(1, phone.opened.size)
        assertTrue(phone.songsAsked.isEmpty())
    }

    @Test
    fun aSongNeitherNumberPlaysIsAskedForByName() {
        numbers.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS * 2)
        assertEquals(listOf(song to qobuz), phone.songsAsked.toList())
        phone.onThePhone = Heard.Song(MusicMetadata("Song (Remastered)", "Band"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.plays.isEmpty())
    }

    @Test
    fun whatItPlaysByNameIsStoppedWhenItsSomeoneElses() {
        numbers.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS * 2)
        phone.onThePhone = Heard.Song(MusicMetadata("Song (Tribute)", "Someone Else"), null, app = qobuz)
        passes(1)
        assertEquals(listOf(false), phone.plays.toList())
    }

    @Test
    fun askedByNameAndNotStartingIsLeftAtThat() {
        numbers.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS * 4)
        assertEquals(1, phone.songsAsked.size)
        assertTrue(phone.plays.isEmpty())
    }

    @Test
    fun aSongThatPlaysIsLeftAloneAndNothingMoreIsAskedOfTheStore() {
        numbers.opened(song, qobuz, link)
        passes(1_000)
        // Whatever Qobuz calls it.
        phone.onThePhone = Heard.Song(MusicMetadata("Song (Remastered)", "Band"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS * 2)
        assertTrue(phone.opened.isEmpty())
        assertTrue(looked.isEmpty())
    }

    @Test
    fun stillPlayingTheSongFromBeforeItWasOpenedDoesntCount() {
        phone.onThePhone = Heard.Song(MusicMetadata("Before", "Band"), null, app = qobuz)
        numbers.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(1, phone.opened.size)
    }

    @Test
    fun someoneElsesSongStartingDoesntCount() {
        // As Qobuz does, waking on what it was left on.
        numbers.opened(song, qobuz, link)
        phone.onThePhone = Heard.Song(MusicMetadata("Left On", "Someone Else"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(1, phone.opened.size)
    }

    @Test
    fun theSameTitleUnderAnotherNameForTheArtistCounts() {
        numbers.opened(song, qobuz, link)
        phone.onThePhone = Heard.Song(MusicMetadata("Song", "Another Name"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun anotherAppPlayingDoesntCount() {
        numbers.opened(song, qobuz, link)
        phone.onThePhone = Heard.Song(MusicMetadata("Other", "Band"), null, app = "other.app")
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(1, phone.opened.size)
    }

    @Test
    fun theSongAlreadyPlayingIsntWatched() {
        phone.onThePhone = Heard.Song(song.copy(), null, app = qobuz)
        numbers.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
        assertTrue(looked.isEmpty())
    }

    @Test
    fun withoutSeeingWhatPlaysNothingIsDoneOnAGuess() {
        phone.access = false
        numbers.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
        assertTrue(looked.isEmpty())
    }

    @Test
    fun withNoOtherNumberItGoesStraightToItsName() {
        other = null
        numbers.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(1, looked.size)
        assertTrue(phone.opened.isEmpty())
        assertEquals(listOf(song to qobuz), phone.songsAsked.toList())
    }

    @Test
    fun onlyTheLastSongOpenedIsWatched() {
        numbers.opened(song, qobuz, link)
        passes(1_000)
        val next = MusicMetadata("Next", "Band")
        numbers.opened(next, qobuz, "https://play.qobuz.com/track/3")
        passes(QobuzNumbers.WAIT_MS - 1_000)
        assertTrue(phone.opened.isEmpty())
        passes(1_000)
        assertEquals(listOf(next to "https://play.qobuz.com/track/3"), looked)
        assertEquals(1, phone.opened.size)
    }
}
