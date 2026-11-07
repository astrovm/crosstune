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

    private fun passes(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun aSongThatDoesntPlayIsOpenedOnTheNumberOfItsOtherStore() {
        numbers.prepare(song, qobuz, link).started(true)
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
        first.prepare(song, qobuz, link).started(true)
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
        numbers.prepare(song, qobuz, link).started(true)
        passes(1_000)
        // Whatever Qobuz calls it.
        phone.onThePhone = Heard.Song(MusicMetadata("Song (Remastered)", "Band"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS * 2)
        assertTrue(phone.opened.isEmpty())
        assertEquals(0, scope.coroutineContext[Job]?.children?.count())
    }

    @Test
    fun aLateLaunchStillGetsItsWholePlaybackWait() {
        val pending = numbers.prepare(song, qobuz, link)
        passes(QobuzNumbers.WAIT_MS * 2)
        assertTrue(phone.opened.isEmpty())
        pending.started(true)
        passes(QobuzNumbers.WAIT_MS - 1)
        assertTrue(phone.opened.isEmpty())
        passes(1)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun onceTheSongPlaysTheStoresAnswerIsNoLongerWaitedFor() {
        var waited = true
        val slow = QobuzNumbers(phone, { _, _ -> try { awaitCancellation() } finally { waited = false } }, { true }, scope)
        slow.prepare(song, qobuz, link).started(true)
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
        assertEquals(false, pending.superseded)
        slow.off()
        assertEquals(true, pending.superseded)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
        assertEquals(false, waited)
    }

    @Test
    fun stillPlayingTheSongFromBeforeItWasOpenedDoesntCount() {
        phone.onThePhone = Heard.Song(MusicMetadata("Before", "Band"), null, app = qobuz)
        numbers.prepare(song, qobuz, link).started(true)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(1, phone.opened.size)
    }

    @Test
    fun wakingOnWhatItWasLeftOnLosesTheLinkSoTheSameNumberIsSentAgain() {
        numbers.prepare(song, qobuz, link).started(true)
        phone.onThePhone = Heard.Song(MusicMetadata("Left On", "Someone Else"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to link), phone.opened.toList())
    }

    @Test
    fun wakingPausedOnTheOldQueueWithNoOtherNumberRetriesTheSameLink() {
        other = null
        numbers.prepare(song, qobuz, link).started(true)
        phone.inApp(qobuz).value = AppPlays(MusicMetadata("Left On", "Someone Else"), null)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to link), phone.opened.toList())
    }

    @Test
    fun wakingPausedOnTheOldQueueUsesTheOtherNumberWhenKnown() {
        numbers.prepare(song, qobuz, link).started(true)
        phone.inApp(qobuz).value = AppPlays(MusicMetadata("Left On", "Someone Else"), null)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun theSameTitleUnderAnotherNameForTheArtistCounts() {
        numbers.prepare(song, qobuz, link).started(true)
        phone.onThePhone = Heard.Song(MusicMetadata("Song", "Another Name"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun anotherAppPlayingCancelsTheRetry() {
        numbers.prepare(song, qobuz, link).started(true)
        phone.onThePhone = Heard.Song(MusicMetadata("Other", "Band"), null, app = "other.app")
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun anotherTitleByTheSameArtistDoesntStopTheRetry() {
        phone.onThePhone = Heard.Song(MusicMetadata("Old song", "Band"), null, app = qobuz)
        numbers.prepare(song, qobuz, link).started(true)
        passes(1_000)
        phone.onThePhone = Heard.Song(MusicMetadata("Next old queue song", "Band"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun aTitleWrittenBothWaysStillCounts() {
        numbers.prepare(MusicMetadata("ミステリー・ガール - Mystery Girl", "Band"), qobuz, link).started(true)
        phone.onThePhone = Heard.Song(MusicMetadata("Mystery Girl (Remastered)", "Band"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun anExistingOtherPlayerDoesntCancelUntilItChanges() {
        phone.onThePhone = Heard.Song(MusicMetadata("Before", "Someone"), null, app = "other.app")
        numbers.prepare(song, qobuz, link).started(true)
        passes(1_000)
        phone.onThePhone = phone.onThePhone?.copy(offsetMs = 1_000)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun theExistingOtherPlayerStartingAgainCancelsTheRetry() {
        val before = Heard.Song(MusicMetadata("Before", "Someone"), null, app = "other.app")
        phone.onThePhone = before
        numbers.prepare(song, qobuz, link).started(true)
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
        numbers.prepare(song, qobuz, link).started(true)
        passes(1_000)
        phone.onThePhone = Heard.Song(MusicMetadata("Next", "Someone"), null, app = "other.app")
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun switchingAppsWhileTheStoreIsStillAnsweringCancelsItToo() {
        val slow = QobuzNumbers(phone, { _, _ -> delay(QobuzNumbers.WAIT_MS * 2); other }, { true }, scope)
        slow.prepare(song, qobuz, link).started(true)
        passes(QobuzNumbers.WAIT_MS + 1_000)
        phone.onThePhone = Heard.Song(MusicMetadata("Other", "Someone"), null, app = "other.app")
        passes(QobuzNumbers.WAIT_MS * 2)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun theSongAlreadyPlayingIsntWatched() {
        phone.onThePhone = Heard.Song(song.copy(), null, app = qobuz)
        val pending = numbers.prepare(song, qobuz, link)
        pending.started(false)
        assertEquals(false, pending.superseded)
        pending.started(true)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
        assertTrue(looked.isEmpty())
    }

    @Test
    fun withoutSeeingWhatPlaysNothingIsDoneOnAGuess() {
        phone.access = false
        numbers.prepare(song, qobuz, link).started(true)
        passes(QobuzNumbers.WAIT_MS)
        assertTrue(phone.opened.isEmpty())
        assertTrue(looked.isEmpty())
    }

    @Test
    fun aPreparedOpenThatWasSupersededCantLaunchLater() {
        val pending = numbers.prepare(song, qobuz, link)
        numbers.off()
        assertEquals(true, pending.superseded)
        pending.started(true)
        passes(QobuzNumbers.WAIT_MS * 2)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun aFailedFirstLaunchStopsTheWatchAndItsLookup() {
        var waiting = true
        val slow = QobuzNumbers(phone, { _, _ -> try { awaitCancellation() } finally { waiting = false } }, { true }, scope)
        slow.prepare(song, qobuz, link).started(false)
        assertEquals(false, waiting)
        passes(QobuzNumbers.WAIT_MS * 2)
        assertTrue(phone.opened.isEmpty())
        assertEquals(0, scope.coroutineContext[Job]?.children?.count())
    }

    @Test
    fun withNoOtherNumberItStays() {
        other = null
        numbers.prepare(song, qobuz, link).started(true)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(1, looked.size)
        assertTrue(phone.opened.isEmpty())
        assertTrue(phone.songsAsked.isEmpty())
    }

    /** Watching [song], opened straight away, on a store that takes [ms] to answer. */
    private fun openedOnAStoreTaking(ms: Long) = QobuzNumbers(phone, { _, _ -> delay(ms); other }, { true }, scope).also { it.prepare(song, qobuz, link).started(true) }

    @Test
    fun aStoreAnsweringInsideAndroidsWindowStillGetsItsRetry() {
        openedOnAStoreTaking(9_000)
        passes(8_999)
        assertTrue(phone.opened.isEmpty())
        passes(1)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
        passes(QobuzNumbers.WAIT_MS * 3)
        assertEquals(1, phone.opened.size)
    }

    @Test
    fun aStoreSlowerThanAndroidsWindowGetsNoLaunch() {
        openedOnAStoreTaking(9_501)
        passes(QobuzNumbers.WAIT_MS * 4)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun noStoreSpeedGetsALaunchThatAndroidBlocks() {
        for (storeMs in 0L..20_000L step 250) {
            phone.opened.clear()
            val openAt = android.os.SystemClock.elapsedRealtime()
            val launches = mutableListOf<Long>()
            val guarded = object : PlaybackSource by phone {
                override fun openSong(app: String, link: String): Boolean {
                    launches += android.os.SystemClock.elapsedRealtime() - openAt
                    return phone.openSong(app, link)
                }
            }
            val watch = QobuzNumbers(guarded, { _, _ -> delay(storeMs); other }, { true }, scope)
            watch.prepare(song, qobuz, link).started(true)
            passes(30_000)
            watch.off()
            if (storeMs <= 9_000) assertEquals("store took $storeMs", 1, launches.size)
            if (storeMs >= 9_750) assertEquals("store took $storeMs", 0, launches.size)
            assertTrue("store took $storeMs, launched at $launches", launches.all { it in QobuzNumbers.WAIT_MS..9_500L })
        }
    }

    @Test
    fun aTimedOutFirstAttemptLeavesTheSecondTimeInsideTheWindow() {
        val store = FakeSpotify()
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val release = java.util.concurrent.CountDownLatch(1)
        store.handler = { request ->
            val attempt = attempts.incrementAndGet()
            release.await()
            if (attempt == 1) throw java.io.IOException("hung up")
            FakeSpotify.html(request, """
                <li><div class="ListItem">
                    <a href="/x/album/slug/a1" class="ListItem__title" title="Song">Song</a>
                    <p class="ListItem__artists">Band • Album</p>
                    <button data-url="&#x2F;v4&#x2F;ajax&#x2F;popin-add-cart&#x2F;track&#x2F;2"></button>
                </div></li>""")
        }
        fun until(done: () -> Boolean) {
            val end = System.currentTimeMillis() + 10_000
            while (!done()) {
                assertTrue(System.currentTimeMillis() < end)
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
        }
        try {
            QobuzNumbers(phone, ExactMatcher(store.client(), "AR")::otherQobuzLink, { true }, scope).prepare(song, qobuz, link).started(true)
            until { attempts.get() == 1 }
            // An attempt has 5 s, which leaves the second one 4.5 s of the 9.5.
            passes(5_000)
            until { attempts.get() == 2 }
            passes(4_000)
            assertTrue(phone.opened.isEmpty())
            release.countDown()
            until { phone.opened.isNotEmpty() }
            assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun aStillUnansweredStoreLeavesThePausedOldQueueItsOwnNumberAtTheLastMoment() {
        openedOnAStoreTaking(60_000)
        phone.inApp(qobuz).value = AppPlays(MusicMetadata("Left On", "Someone Else"), null)
        passes(9_499)
        assertTrue(phone.opened.isEmpty())
        passes(1)
        assertEquals(listOf(qobuz to link), phone.opened.toList())
        passes(60_000)
        assertEquals(1, phone.opened.size)
    }

    @Test
    fun aStoreSayingNoLeavesThePausedOldQueueItsOwnNumberAtOnce() {
        other = null
        openedOnAStoreTaking(8_500)
        phone.inApp(qobuz).value = AppPlays(MusicMetadata("Left On", "Someone Else"), null)
        passes(8_499)
        assertTrue(phone.opened.isEmpty())
        passes(1)
        assertEquals(listOf(qobuz to link), phone.opened.toList())
    }

    @Test
    fun aStillUnansweredStoreWithAnotherSongBeforeItLaunchesNothing() {
        phone.onThePhone = Heard.Song(MusicMetadata("Before", "Band"), null, app = qobuz)
        openedOnAStoreTaking(60_000)
        passes(60_000)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun aWakingOldQueueGetsItsOwnNumberWithoutWaitingForTheStore() {
        openedOnAStoreTaking(60_000)
        phone.onThePhone = Heard.Song(MusicMetadata("Left On", "Someone Else"), null, app = qobuz)
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(listOf(qobuz to link), phone.opened.toList())
    }

    @Test
    fun anOldQueueWakingWhileTheStoreStillAnswersGetsItsOwnNumber() {
        openedOnAStoreTaking(9_000)
        passes(8_500)
        phone.onThePhone = Heard.Song(MusicMetadata("Left On", "Someone Else"), null, app = qobuz)
        passes(500)
        assertEquals(listOf(qobuz to link), phone.opened.toList())
    }

    @Test
    fun theSongStartingWhileTheStoreStillAnswersEndsIt() {
        openedOnAStoreTaking(9_000)
        passes(8_500)
        phone.onThePhone = Heard.Song(song, null, app = qobuz)
        passes(60_000)
        assertTrue(phone.opened.isEmpty())
        assertEquals(0, scope.coroutineContext[Job]?.children?.count())
    }

    @Test
    fun anotherAppStartingWhileTheStoreStillAnswersEndsIt() {
        openedOnAStoreTaking(9_000)
        passes(8_500)
        phone.onThePhone = Heard.Song(MusicMetadata("Other", "Someone"), null, app = "other.app")
        passes(60_000)
        assertTrue(phone.opened.isEmpty())
    }

    @Test
    fun aNewerSongWhileTheStoreStillAnswersEndsTheOldRetry() {
        val watch = openedOnAStoreTaking(9_000)
        passes(8_500)
        watch.off()
        passes(60_000)
        assertTrue(phone.opened.isEmpty())
        assertEquals(0, scope.coroutineContext[Job]?.children?.count())
    }

    @Test
    fun theWaitCountsFromTheLaunchNotFromTheLookup() {
        var answers = 0
        val store = QobuzNumbers(phone, { _, _ -> delay(12_000); answers++; other }, { true }, scope)
        val opening = store.prepare(song, qobuz, link)
        passes(3_000)
        assertTrue(phone.opened.isEmpty())
        opening.started(true)
        // 8 s after the launch, 11 s after asking, and still unanswered.
        passes(QobuzNumbers.WAIT_MS)
        assertEquals(0, answers)
        assertTrue(phone.opened.isEmpty())
        // 9 s after the launch, 12 s after asking.
        passes(1_000)
        assertEquals(1, answers)
        assertEquals(listOf(qobuz to "https://play.qobuz.com/track/2"), phone.opened.toList())
    }

    @Test
    fun aStoreStillUnansweredAtTheEndOfTheWindowIsCutOff() {
        var lookups = 0
        var cut = false
        val store = QobuzNumbers(phone, { _, _ -> lookups++; try { awaitCancellation() } finally { cut = true } }, { true }, scope)
        store.prepare(song, qobuz, link).started(true)
        passes(9_499)
        assertEquals(1, lookups)
        assertEquals(false, cut)
        passes(1)
        assertEquals(true, cut)
        assertEquals(0, scope.coroutineContext[Job]?.children?.count())
    }

    @Test
    fun onlyTheLastSongOpenedIsWatched() {
        numbers.prepare(song, qobuz, link).started(true)
        passes(1_000)
        val next = MusicMetadata("Next", "Band")
        numbers.prepare(next, qobuz, "https://play.qobuz.com/track/3").started(true)
        passes(QobuzNumbers.WAIT_MS - 1_000)
        assertTrue(phone.opened.isEmpty())
        passes(1_000)
        assertEquals(next to "https://play.qobuz.com/track/3", looked.last())
        assertEquals(1, phone.opened.size)
    }
}
