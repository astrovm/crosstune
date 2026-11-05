package com.astrovm.crosstune

import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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
    fun tearDown() {
        scope.cancel()
        MainActivity.playbackFactory = ::MediaSessionPlayback
    }

    private companion object {
        const val STORE_ANSWERS_MS = 2_000L
    }

    private fun passes(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun aSongThatDoesntPlayIsOpenedOnTheNumberOfItsOtherStore() {
        numbers.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS - 1)
        assertTrue(phone.opened.isEmpty())
        passes(1)
        assertEquals(listOf(song to link), looked)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
        // Once only: there's no third, and asked by name Qobuz stays on the loading page.
        passes(QobuzNumbers.WAIT_MS * 3)
        assertEquals(1, phone.opened.size)
        assertTrue(phone.songsAsked.isEmpty())
    }

    @Test
    fun theAppKeepsOneWatchSoEveryScreenSharesIt() {
        MainActivity.playbackFactory = { phone }
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val matcher = ExactMatcher(FakeSpotify().client(), "AR")
        val kept = MainActivity.qobuzNumbers(app, matcher)
        assertSame(kept, MainActivity.qobuzNumbers(app, matcher))
        kept.off()
    }

    @Test
    fun aWatchMadeForAnotherAppIsDoneWith() {
        MainActivity.playbackFactory = { phone }
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = qobuz })
        val matcher = ExactMatcher(FakeSpotify().client(), "AR")
        val first = MainActivity.qobuzNumbers(app, matcher)
        first.opened(song, qobuz, link)
        // Left playing something else, which would send it the same number again once the wait is over.
        phone.onThePhone = Heard.Song(MusicMetadata("Another", "Someone"), null, app = qobuz)
        val another = object : ContextWrapper(app) {
            override fun getApplicationContext() = this
        }
        val next = MainActivity.qobuzNumbers(another, matcher)
        assertNotSame(first, next)
        passes(QobuzNumbers.WAIT_MS * 2)
        assertTrue(phone.opened.isEmpty())
        next.off()
    }

    @Test
    fun aSongThatPlaysIsLeftAlone() {
        numbers.opened(song, qobuz, link)
        passes(1_000)
        // Whatever Qobuz calls it.
        phone.onThePhone = Heard.Song(MusicMetadata("Song (Remastered)", "Band"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS * 2)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun theStoreIsAskedAsTheSongOpensSoItsAnswerTakesNothingFromWhatAndroidAllows() {
        // Measured: an answer that took 2 s after the wait put the open 10.0 s after Crosstune left, and Android blocked it.
        val slow = QobuzNumbers(phone, { _, _ -> delay(STORE_ANSWERS_MS); "https://play.qobuz.com/track/2" }, { true }, scope)
        slow.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS - 1)
        assertTrue(phone.opened.isEmpty())
        passes(1)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun aStoreSlowerThanTheWaitIsStillWaitedFor() {
        val slow = QobuzNumbers(phone, { _, _ -> delay(QobuzNumbers.WAIT_MS + 1_000); "https://play.qobuz.com/track/2" }, { true }, scope)
        slow.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
        passes(1_000)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun onceTheSongPlaysTheStoresAnswerIsNoLongerWaitedFor() {
        var waited = true
        val slow = QobuzNumbers(phone, { _, _ -> try { awaitCancellation() } finally { waited = false } }, { true }, scope)
        slow.opened(song, qobuz, link)
        phone.onThePhone = Heard.Song(song, null, app = qobuz)
        passes(1)
        assertEquals(false, waited)
    }

    @Test
    fun sendingTheSameNumberAgainNoLongerWaitsForTheStore() {
        var waited = true
        val slow = QobuzNumbers(phone, { _, _ -> try { awaitCancellation() } finally { waited = false } }, { true }, scope)
        slow.opened(song, qobuz, link)
        phone.onThePhone = Heard.Song(MusicMetadata("Left On", "Someone Else"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to link), phone.opened.toList())
        assertEquals(false, waited)
    }

    @Test
    fun stillPlayingTheSongFromBeforeItWasOpenedDoesntCount() {
        phone.onThePhone = Heard.Song(MusicMetadata("Before", "Band"), null, app = qobuz)
        numbers.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(1, phone.opened.size)
    }

    @Test
    fun wakingOnWhatItWasLeftOnLosesTheLinkSoTheSameNumberIsSentAgain() {
        numbers.opened(song, qobuz, link)
        phone.onThePhone = Heard.Song(MusicMetadata("Left On", "Someone Else"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to link), phone.opened.toList())
    }

    @Test
    fun theSameTitleUnderAnotherNameForTheArtistCounts() {
        numbers.opened(song, qobuz, link)
        phone.onThePhone = Heard.Song(MusicMetadata("Song", "Another Name"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun anotherAppPlayingCancelsTheRetry() {
        numbers.opened(song, qobuz, link)
        phone.onThePhone = Heard.Song(MusicMetadata("Other", "Band"), null, app = "other.app")
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun anotherTitleByTheSameArtistDoesntStopTheRetry() {
        phone.onThePhone = Heard.Song(MusicMetadata("Old song", "Band"), null, app = qobuz)
        numbers.opened(song, qobuz, link)
        passes(1_000)
        phone.onThePhone = Heard.Song(MusicMetadata("Next old queue song", "Band"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun aTitleWrittenBothWaysStillCounts() {
        numbers.opened(MusicMetadata("ミステリー・ガール - Mystery Girl", "Band"), qobuz, link)
        phone.onThePhone = Heard.Song(MusicMetadata("Mystery Girl (Remastered)", "Band"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun anExistingOtherPlayerDoesntCancelUntilItChanges() {
        phone.onThePhone = Heard.Song(MusicMetadata("Before", "Someone"), null, app = "other.app")
        numbers.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun switchingAppsWhileTheStoreIsStillAnsweringCancelsItToo() {
        val slow = QobuzNumbers(phone, { _, _ -> delay(QobuzNumbers.WAIT_MS * 2); other }, { true }, scope)
        slow.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS + 1_000)
        phone.onThePhone = Heard.Song(MusicMetadata("Other", "Someone"), null, app = "other.app")
        passes(QobuzNumbers.WAIT_MS * 2)
        assertTrue(phone.opened.isEmpty())
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
    fun withNoOtherNumberItStays() {
        other = null
        numbers.opened(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(1, looked.size)
        assertTrue(phone.opened.isEmpty())
        assertTrue(phone.songsAsked.isEmpty())
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
        assertEquals(next to "https://play.qobuz.com/track/3", looked.last())
        assertEquals(1, phone.opened.size)
    }
}
