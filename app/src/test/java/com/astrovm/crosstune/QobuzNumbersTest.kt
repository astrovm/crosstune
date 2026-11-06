package com.astrovm.crosstune

import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
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
        scope.launch { val opening = first.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
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
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        passes(1_000)
        // Whatever Qobuz calls it.
        phone.onThePhone = Heard.Song(MusicMetadata("Song (Remastered)", "Band"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS * 2)
        assertTrue(phone.opened.isEmpty())
        assertEquals(0, scope.coroutineContext[Job]?.children?.count())
    }

    @Test
    fun theFirstOpenWaitsForTheStoreThenGetsItsWholePlaybackWait() {
        var firstOpened = false
        val slow = QobuzNumbers(phone, { _, _ -> delay(STORE_ANSWERS_MS); other }, { true }, scope)
        scope.launch {
            val pending = slow.prepare(song, qobuz, link)
            if (pending.ready()) { firstOpened = true; pending.started(true) }
        }
        passes(STORE_ANSWERS_MS - 1)
        assertEquals(false, firstOpened)
        passes(1)
        assertEquals(true, firstOpened)
        passes(QobuzNumbers.WAIT_MS - 1)
        assertTrue(phone.opened.isEmpty())
        passes(1)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun aStoreSlowerThanTheWaitFinishesBeforeTheFirstOpen() {
        val slow = QobuzNumbers(phone, { _, _ -> delay(QobuzNumbers.WAIT_MS + 1_000); other }, { true }, scope)
        scope.launch { val pending = slow.prepare(song, qobuz, link); if (pending.ready()) pending.started(true) }
        passes(QobuzNumbers.WAIT_MS + 1_000)
        assertTrue(phone.opened.isEmpty())
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun onceTheSongPlaysTheStoresAnswerIsNoLongerWaitedFor() {
        var waited = true
        val slow = QobuzNumbers(phone, { _, _ -> try { awaitCancellation() } finally { waited = false } }, { true }, scope)
        scope.launch { val opening = slow.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        phone.onThePhone = Heard.Song(song, null, app = qobuz)
        passes(1)
        assertEquals(false, waited)
        assertEquals(0, scope.coroutineContext[Job]?.children?.count())
    }

    @Test
    fun cancellingAnUnansweredPreparationReleasesTheStore() {
        var waited = true
        val slow = QobuzNumbers(phone, { _, _ -> try { awaitCancellation() } finally { waited = false } }, { true }, scope)
        val pending = slow.prepare(song, qobuz, link)
        slow.off()
        scope.launch { assertEquals(false, pending.ready()) }
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
        assertEquals(false, waited)
    }

    @Test
    fun stillPlayingTheSongFromBeforeItWasOpenedDoesntCount() {
        phone.onThePhone = Heard.Song(MusicMetadata("Before", "Band"), null, app = qobuz)
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(1, phone.opened.size)
    }

    @Test
    fun wakingOnWhatItWasLeftOnLosesTheLinkSoTheSameNumberIsSentAgain() {
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        phone.onThePhone = Heard.Song(MusicMetadata("Left On", "Someone Else"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to link), phone.opened.toList())
    }

    @Test
    fun theSameTitleUnderAnotherNameForTheArtistCounts() {
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        phone.onThePhone = Heard.Song(MusicMetadata("Song", "Another Name"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun anotherAppPlayingCancelsTheRetry() {
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        phone.onThePhone = Heard.Song(MusicMetadata("Other", "Band"), null, app = "other.app")
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun anotherTitleByTheSameArtistDoesntStopTheRetry() {
        phone.onThePhone = Heard.Song(MusicMetadata("Old song", "Band"), null, app = qobuz)
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        passes(1_000)
        phone.onThePhone = Heard.Song(MusicMetadata("Next old queue song", "Band"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun aTitleWrittenBothWaysStillCounts() {
        scope.launch { val opening = numbers.prepare(MusicMetadata("ミステリー・ガール - Mystery Girl", "Band"), qobuz, link); if (opening.ready()) opening.started(true) }
        phone.onThePhone = Heard.Song(MusicMetadata("Mystery Girl (Remastered)", "Band"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun anExistingOtherPlayerDoesntCancelUntilItChanges() {
        phone.onThePhone = Heard.Song(MusicMetadata("Before", "Someone"), null, app = "other.app")
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        passes(1_000)
        phone.onThePhone = phone.onThePhone?.copy(offsetMs = 1_000)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun theExistingOtherPlayerStartingAgainCancelsTheRetry() {
        val before = Heard.Song(MusicMetadata("Before", "Someone"), null, app = "other.app")
        phone.onThePhone = before
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        passes(1_000)
        phone.onThePhone = null
        passes(1_000)
        phone.onThePhone = before
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun theExistingOtherPlayerChangingSongsCancelsTheRetry() {
        phone.onThePhone = Heard.Song(MusicMetadata("Before", "Someone"), null, app = "other.app")
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        passes(1_000)
        phone.onThePhone = Heard.Song(MusicMetadata("Next", "Someone"), null, app = "other.app")
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun switchingAppsWhileTheStoreIsStillAnsweringCancelsItToo() {
        val slow = QobuzNumbers(phone, { _, _ -> delay(QobuzNumbers.WAIT_MS * 2); other }, { true }, scope)
        scope.launch { val opening = slow.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        passes(QobuzNumbers.WAIT_MS + 1_000)
        phone.onThePhone = Heard.Song(MusicMetadata("Other", "Someone"), null, app = "other.app")
        passes(QobuzNumbers.WAIT_MS * 2)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun theSongAlreadyPlayingIsntWatched() {
        phone.onThePhone = Heard.Song(song.copy(), null, app = qobuz)
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
        assertTrue(looked.isEmpty())
    }

    @Test
    fun withoutSeeingWhatPlaysNothingIsDoneOnAGuess() {
        phone.access = false
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
        assertTrue(looked.isEmpty())
    }

    @Test
    fun aSlowStoreDoesntSpendAndroidsWindowBeforeTheRetry() {
        var firstOpenAt = android.os.SystemClock.elapsedRealtime()
        var blocked = 0
        val guarded = object : PlaybackSource by phone {
            override fun openSong(app: String, link: String): Boolean {
                if (android.os.SystemClock.elapsedRealtime() - firstOpenAt >= 10_000) {
                    blocked++
                    return false
                }
                return phone.openSong(app, link)
            }
        }
        val slow = QobuzNumbers(guarded, { _, _ -> delay(11_000); other }, { true }, scope)
        scope.launch { val opening = slow.prepare(song, qobuz, link); if (opening.ready()) { firstOpenAt = android.os.SystemClock.elapsedRealtime(); opening.started(true) } }
        passes(20_000)
        assertEquals(0, blocked)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun aPreparedOpenThatWasSupersededCantLaunchLater() {
        val pending = numbers.prepare(song, qobuz, link)
        numbers.off()
        var ready: Boolean? = null
        scope.launch { ready = pending.ready() }
        passes(1)
        assertEquals(false, ready)
    }

    @Test
    fun leavingDuringPreparationCancelsItsLookup() {
        var waiting = true
        val slow = QobuzNumbers(phone, { _, _ -> try { awaitCancellation() } finally { waiting = false } }, { true }, scope)
        val pending = slow.prepare(song, qobuz, link)
        var continued = false
        val caller = scope.launch { pending.ready(); continued = true }
        caller.cancel()
        passes(1)
        assertEquals(false, waiting)
        assertEquals(false, continued)
        assertEquals(0, scope.coroutineContext[Job]?.children?.count())
    }

    @Test
    fun aFailedFirstLaunchStopsTheWatch() {
        val pending = numbers.prepare(song, qobuz, link)
        scope.launch { assertTrue(pending.ready()); pending.started(false) }
        passes(QobuzNumbers.WAIT_MS * 2)
        assertTrue(phone.opened.isEmpty())
        assertEquals(0, scope.coroutineContext[Job]?.children?.count())
    }

    @Test
    fun withNoOtherNumberItStays() {
        other = null
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(1, looked.size)
        assertTrue(phone.opened.isEmpty())
        assertTrue(phone.songsAsked.isEmpty())
    }

    @Test
    fun onlyTheLastSongOpenedIsWatched() {
        scope.launch { val opening = numbers.prepare(song, qobuz, link); if (opening.ready()) opening.started(true) }
        passes(1_000)
        val next = MusicMetadata("Next", "Band")
        scope.launch { val opening = numbers.prepare(next, qobuz, "https://play.qobuz.com/track/3"); if (opening.ready()) opening.started(true) }
        passes(QobuzNumbers.WAIT_MS - 1_000)
        assertTrue(phone.opened.isEmpty())
        passes(1_000)
        assertEquals(next to "https://play.qobuz.com/track/3", looked.last())
        assertEquals(1, phone.opened.size)
    }
}
