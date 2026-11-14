package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Duration
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

/**
 * The view model on its own, with only the parts a test hands it: what it does when a part is
 * missing, as the ones "nullable so tests need none" are, and the corners the screens never reach.
 */
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private val made = mutableListOf<MainViewModel>()

    @Before
    fun setUp() {
        prefs().edit().clear().putBoolean("setup_complete", true).commit()
        File(app.cacheDir, "lookups.json").delete()
    }

    @After
    fun tearDown() {
        made.forEach { runCatching { it.onClearedForTest() } }
    }

    private fun MainViewModel.onClearedForTest() {
        MainViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(this)
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lets [ms] pass for what waits on the clock. */
    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun model(
        playback: PlaybackSource? = null,
        oneTap: OneTapSetup? = null,
        chatGpt: ChatGpt? = null,
        tutor: ChatGptTutor? = null,
        updates: UpdateChecker? = null,
        keptOnDisk: LookupCache? = null,
        sleepTimer: SleepTimer? = null,
        phone: Phone = Phone.OTHER,
        lyricsFinder: LyricsFinder? = null,
        songSearcher: SongSearcher? = null,
        reports: ReportSender? = null,
        listener: SongHearing = SongHearing { Heard.Nothing },
        queueOrder: QueueOrder? = null,
        offline: OfflineSongs? = null,
        qobuzNumbers: QobuzNumbers? = null,
        playQueue: PlayQueue? = null,
        lookAhead: Boolean = true,
        reminders: PracticeReminders? = null,
        sungReadings: SungReadings? = null,
        mayCalibrate: () -> Boolean = { false },
        mayCapture: () -> Boolean = { false }
    ): MainViewModel {
        val client = fake.client()
        return MainViewModel(
            LinkResolver(client, Dispatchers.Unconfined),
            ExactMatcher(client, ioDispatcher = Dispatchers.Unconfined),
            lyricsFinder = lyricsFinder,
            playback = playback,
            preferences = prefs(),
            interception = LinkInterception(app),
            artwork = ArtworkLoader(client, Dispatchers.Unconfined),
            listener = listener,
            systemDispatcher = Dispatchers.Unconfined,
            chatGpt = chatGpt,
            tutor = tutor,
            oneTap = oneTap,
            updates = updates,
            keptOnDisk = keptOnDisk,
            sleepTimer = sleepTimer,
            phone = phone,
            songSearcher = songSearcher,
            reports = reports,
            queueOrder = queueOrder,
            playQueue = playQueue,
            lookAhead = lookAhead,
            qobuzNumbers = qobuzNumbers,
            offline = offline,
            reminders = reminders,
            sungReadings = sungReadings,
            mayCalibrate = mayCalibrate,
            mayCapture = mayCapture
        ).also { made += it }
    }

    @Test
    fun aReminderKeptIsOnTheBellFromTheFirstFrameAndNoneKeptIsOff() {
        assertFalse(model().uiState.reminder.on)
        val reminders = PracticeReminders(app)
        reminders.sessionDone()
        reminders.turn(true)
        val first = model(reminders = reminders).uiState.reminder
        assertTrue(first.on)
        assertEquals(reminders.state.minute, first.minute)
    }

    @Test
    fun anotherNameHeardIsTheSameRecordingOnlyWellIntoTheSongAndCloseToWhereItIs() {
        val after = MainViewModel.SAME_RECORDING_AFTER_MS
        val near = MainViewModel.SAME_RECORDING_MS
        // From 30 s in, a hearing up to 10 s either side of where the song shown is counts as it.
        assertTrue(MainViewModel.sameRecordingPlace(after, after + near))
        assertTrue(MainViewModel.sameRecordingPlace(after + near, after))
        assertFalse(MainViewModel.sameRecordingPlace(after, after + near + 1))
        // Earlier than that, it may just as well be the next song, which starts over.
        assertFalse(MainViewModel.sameRecordingPlace(after - 1, after - 1))
    }

    @Test
    fun aLineThatSaysWhichItIsIsHeardAloneFromItsVeryMillisecondButNotOneLater() {
        // Every line too far from the next to hear three, but "Every river finds the sea" has five words no other line shares.
        val lines = listOf(
            LyricLine(0, "Sing to me tonight"), LyricLine(3_000, "Under the yellow moon"), LyricLine(40_000, "Every river finds the sea"),
            LyricLine(60_000, "Hold the morning light"), LyricLine(80_000, "Dancing on the water"), LyricLine(99_000, "Never let it go")
        )
        // Right at its start, it's heard from now.
        assertEquals(0L, MainViewModel.untilSung(lines, here = 40_000, speed = 1f, after = null))
        // A millisecond in, its first words are gone, so it isn't heard alone now.
        assertTrue(MainViewModel.untilSung(lines, here = 40_001, speed = 1f, after = null).let { it == null || it > 0 })
        // Before it, from a second and a half ahead of it, as fast as the song goes by: 1.5 s of the song at double speed.
        assertEquals(750L, MainViewModel.untilSung(lines, here = 37_000, speed = 2f, after = null))
    }

    @Test
    fun aModelWithoutTheOptionalPartsLeavesTheirFeaturesAlone() {
        val model = model()
        model.setUpInOneTap()
        assertFalse(model.uiState.settingUpInOneTap)
        assertFalse(model.fixInOneTap(OneTapFix.LINKS))
        assertFalse(model.uiState.canSetUpInOneTap || model.uiState.oneTapRoot || model.uiState.shizukuStopped)
        assertFalse(model.uiState.canFollowApps || model.uiState.followRestricted)
        var done = 0
        model.signInWithChatGpt { done++ }
        model.cancelChatGptSignIn()
        model.pickChatGptModel("gpt-6")
        model.signOutOfChatGpt()
        idle()
        assertEquals(0, done)
        assertEquals(ChatGptState(), model.uiState.chatGpt)
        assertNull(prefs().getString("chatgpt_model", null))
        // No small player was given, so a card has none to show.
        assertNull(model.linePlayerView())
    }

    @Test
    fun withNoLyricsServiceASongKeptOfflineHasNothingToFetchAndIsKept() = runBlocking {
        val kept = OfflineSongs(File(app.cacheDir, "kept-no-lyrics/songs.json"), emptyList(), null, Dispatchers.Unconfined)
        val song = MusicMetadata("Song", "Band")
        kept.keep(song)
        val model = model(offline = kept)
        model.unmeteredChanged(true)
        idle()
        assertEquals(listOf(true), model.uiState.keptOffline.map { it.done })
        File(app.cacheDir, "kept-no-lyrics").deleteRecursively()
        Unit
    }

    @Test
    fun aFixThatFailsLeavesTheStepToDoByHandAndKeepsTheListOpen() {
        val shell = FakeShell(does = { false })
        val model = model(oneTap = OneTapSetup(app, listOf(shell)))
        assertTrue(model.uiState.canSetUpInOneTap)
        // From the list of fixes, a failed one still comes back to it.
        model.openFixes()
        for ((fix, need) in listOf(OneTapFix.LINKS to Need.Links, OneTapFix.FOLLOW to Need.Follow, OneTapFix.FLOAT to Need.Float)) {
            assertTrue(model.fixInOneTap(fix))
            idle()
            pass(2_000)
            assertEquals(Fixes(list = true, steps = listOf(need), failed = true), model.uiState.fixes)
            assertNull(model.uiState.fixingInOneTap)
        }
        // Closed, it is not listed again by the next failure.
        model.closeFixes()
        assertTrue(model.fixInOneTap(OneTapFix.LINKS))
        idle()
        assertEquals(Fixes(list = false, steps = listOf(Need.Links), failed = true), model.uiState.fixes)
    }

    @Test
    fun showingOverOtherAppsIsAllowedInOneTapWhereItCanBeAndOtherwiseByItsSteps() {
        // Without Shizuku or root, the steps show how.
        val byHand = model()
        byHand.allowFloat()
        assertEquals(Fixes(steps = listOf(Need.Float)), byHand.uiState.fixes)
        // With them, it's done, and known at once, for the card that asked.
        val shell = FakeShell()
        val model = model(oneTap = OneTapSetup(app, listOf(shell)))
        assertFalse(model.uiState.canFloatOverApps)
        model.allowFloat()
        idle()
        pass(2_000)
        assertTrue(model.uiState.canFloatOverApps)
        assertNull(model.uiState.fixes)
    }

    @Test
    fun aFixAskedWhileOneRunsIsTheSameFixAndRunsOnce() {
        val shell = FakeShell()
        shell.answer = kotlinx.coroutines.CompletableDeferred()
        val model = model(oneTap = OneTapSetup(app, listOf(shell)))
        var then = 0
        assertTrue(model.fixInOneTap(OneTapFix.LINKS) { then++ })
        assertEquals(OneTapFix.LINKS, model.uiState.fixingInOneTap)
        assertTrue(model.fixInOneTap(OneTapFix.LINKS) { then++ })
        shell.answer.complete(Unit)
        idle()
        assertEquals(1, then)
        assertNull(model.uiState.fixingInOneTap)
    }

    @Test
    fun settingUpInOneTapWaitsForAndroidToLetItFollowMusicAppsButNotForever() {
        val shell = FakeShell()
        val playback = FakePlayback()
        val model = model(playback = playback, oneTap = OneTapSetup(app, listOf(shell)))
        model.setUpInOneTap()
        // A second ask while one runs does nothing more.
        model.setUpInOneTap()
        assertTrue(model.uiState.settingUpInOneTap)
        idle()
        assertTrue(model.uiState.settingUpInOneTap)
        // Android never lets it: it stops waiting after a second, and says it worked.
        pass(1_500)
        assertFalse(model.uiState.settingUpInOneTap)
        assertFalse(model.uiState.oneTapFailed)
        assertEquals(1, shell.ran.count { "allow_listener" in it })
    }

    @Test
    fun settingUpInOneTapStopsWaitingOnceAndroidLetsItFollow() {
        val shell = FakeShell()
        val playback = FakePlayback()
        val model = model(playback = playback, oneTap = OneTapSetup(app, listOf(shell)))
        model.setUpInOneTap()
        idle()
        pass(250)
        playback.access = true
        pass(300)
        assertFalse(model.uiState.settingUpInOneTap)
        assertTrue(model.uiState.canFollowApps)
    }

    @Test
    fun shizukuStartingAndStoppingChangesWhatOneTapOffers() {
        val shell = FakeShell(ready = false)
        val model = model(oneTap = OneTapSetup(app, listOf(shell)))
        assertFalse(model.uiState.canSetUpInOneTap)
        shell.ready = true
        shell.changed!!.invoke()
        assertTrue(model.uiState.canSetUpInOneTap)
        // Cleared, it stops listening.
        model.onClearedForTest()
        assertNull(shell.changed)
    }

    private val song = MusicMetadata("Hush", "Band")

    private fun following(playing: Boolean = true, app: String = "com.spotify", skipNext: Boolean = false, skipPrevious: Boolean = false, seek: Boolean = true, shuffle: Boolean? = null, repeat: Repeat? = null, like: AppAction? = null, upNext: List<Queued> = emptyList()) =
        Following(PlaybackClock(1_000, android.os.SystemClock.elapsedRealtime(), playing = playing), "Spotify", seek, app, canSkipNext = skipNext, canSkipPrevious = skipPrevious, shuffle = shuffle, repeat = repeat, like = like, upNext = upNext)

    /** A model whose music app plays [song], offered as playing now, with the player open on it when [open]. */
    private fun playingModel(playback: FakePlayback, open: Boolean = true, sleepTimer: SleepTimer? = null, phone: Phone = Phone.OTHER, lyricsFinder: LyricsFinder? = null, keptOnDisk: LookupCache? = null, reports: ReportSender? = null, listener: SongHearing = SongHearing { Heard.Nothing }, queueOrder: QueueOrder? = null, playQueue: PlayQueue? = null, lookAhead: Boolean = true, sungReadings: SungReadings? = null): MainViewModel {
        playback.access = true
        playback.onThePhone = Heard.Song(song, null, app = "com.spotify")
        val ordered = queueOrder?.ordering(playback) ?: playback
        val model = model(playback = playQueue?.skipping(ordered) ?: ordered, sleepTimer = sleepTimer, phone = phone, lyricsFinder = lyricsFinder, keptOnDisk = keptOnDisk, reports = reports, listener = listener, queueOrder = queueOrder, playQueue = playQueue, lookAhead = lookAhead, sungReadings = sungReadings)
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        if (open) {
            model.peekPlayingNow()
            idle()
        }
        return model
    }

    private val station = Unnamed("com.ilv.vradio", "VRadio", "RTP Antena 2", "https://example.com/logo.png")

    /** Hears [answers] in turn, the last one from then on, and keeps whether each time it was let use the microphone, and only while the phone plays out loud. */
    private class EarsOnThePhone(vararg val answers: Heard) : SongHearing {
        val asked = mutableListOf<Boolean>()
        val whileAloud = mutableListOf<Boolean>()
        /** How many times the microphone listened for a song nearby, rather than to what the phone plays, and what it heard. */
        var nearby = 0
        var heardNearby: Heard = Heard.Nothing
        /** Holds a listen for a song nearby till it's completed. */
        var holdNearby: CompletableDeferred<Unit>? = null
        override suspend fun listen(): Heard {
            nearby++
            holdNearby?.await()
            return heardNearby
        }
        override suspend fun listenToPlaying(byMicrophone: Boolean, whileAloud: Boolean): Heard {
            asked += byMicrophone
            this.whileAloud += whileAloud
            return answers[minOf(asked.size, answers.size) - 1]
        }
    }

    /** A model following [playback], which plays a radio station with no song named, as VRadio does (#998). */
    private fun radioModel(
        ears: SongHearing,
        playback: FakePlayback = FakePlayback(),
        phone: Phone = Phone.OTHER,
        mayCalibrate: () -> Boolean = { true },
        lyricsFinder: LyricsFinder? = null,
        mayCapture: () -> Boolean = { false }
    ): MainViewModel {
        playback.access = true
        playback.unnamed.value = station
        val model = model(playback = playback, listener = ears, phone = phone, mayCalibrate = mayCalibrate, lyricsFinder = lyricsFinder, mayCapture = mayCapture)
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        return model
    }

    private fun heardSong(lengthMs: Long? = 200_000, offsetMs: Long? = 50_000) =
        Heard.Song(MusicMetadata("Fake Song", "Fake Artist", lengthMs = lengthMs), "1234", offsetMs, android.os.SystemClock.elapsedRealtime())

    @Test
    fun anAppPlayingWithNoSongNamedIsShownAsItIsAndNamedByEarOnlyWhileItsBarIsInSight() {
        val heard = heardSong()
        val ears = EarsOnThePhone(heard)
        val model = radioModel(ears)
        assertEquals(PlayingUnnamed(station), model.uiState.playingUnnamed)
        assertNull(model.uiState.playingNow)
        // Nothing heard yet, a tap has no song to show: it opens the station's player.
        model.openPlayingUnnamed()
        assertNull(model.uiState.result)
        assertEquals(true, model.uiState.player?.songless)
        model.closePlayer()
        assertTrue(ears.asked.isEmpty())
        model.playingBarInSight(true)
        idle()
        assertEquals(listOf(true), ears.asked)
        assertEquals(PlayingUnnamed(station, heard), model.uiState.playingUnnamed)
        // Listened to again once the song should be over: 150 s of it left, and a little after.
        pass(MainViewModel.BY_EAR_AFTER_MS + 149_000)
        assertEquals(1, ears.asked.size)
        pass(1_500)
        assertEquals(2, ears.asked.size)
        // Out of sight, it stops, and back in, it waits as it would have.
        model.playingBarInSight(false)
        pass(MainViewModel.BY_EAR_LATEST_MS)
        assertEquals(2, ears.asked.size)
        model.playingBarInSight(true)
        idle()
        assertEquals(3, ears.asked.size)
    }

    @Test
    fun theMicrophoneNamesWhatPlaysOnlyWhenAllowedAlreadyOnAPhonePlayingAloud() {
        val allowed = EarsOnThePhone(Heard.Nothing)
        radioModel(allowed).playingBarInSight(true)
        idle()
        assertEquals(listOf(true), allowed.asked)
        // Headphones plugged in midway, it stops: see SongListenerTest.
        assertEquals(listOf(true), allowed.whileAloud)
        // Never asked for: only the phone's own sound, when it shares it.
        val notAllowed = EarsOnThePhone(Heard.Failed(AppError.MICROPHONE))
        val model = radioModel(notAllowed, mayCalibrate = { false })
        model.playingBarInSight(true)
        idle()
        assertEquals(listOf(false), notAllowed.asked)
        // Failing quietly, the station stays shown as it is.
        assertNull(model.uiState.error)
        assertEquals(PlayingUnnamed(station), model.uiState.playingUnnamed)
        val tv = EarsOnThePhone(Heard.Nothing)
        radioModel(tv, phone = Phone.TV).playingBarInSight(true)
        idle()
        assertEquals(listOf(false), tv.asked)
        // Through headphones or muted, the microphone would hear nothing.
        val quiet = EarsOnThePhone(Heard.Nothing)
        radioModel(quiet, FakePlayback().apply { aloud = false }).playingBarInSight(true)
        idle()
        assertEquals(listOf(false), quiet.asked)
    }

    @Test
    fun nothingHeardShowsTheStationAgainAndRestsLongerEachTime() {
        val ears = EarsOnThePhone(heardSong(), Heard.Nothing)
        val model = radioModel(ears)
        model.playingBarInSight(true)
        idle()
        assertNotNull(model.uiState.playingUnnamed?.heard)
        pass(MainViewModel.BY_EAR_AFTER_MS + 150_000)
        // Talk between songs: the song heard is over, and no other plays yet.
        assertEquals(2, ears.asked.size)
        assertEquals(PlayingUnnamed(station), model.uiState.playingUnnamed)
        pass(MainViewModel.BY_EAR_RETRY_MS)
        assertEquals(3, ears.asked.size)
        pass(MainViewModel.BY_EAR_RETRY_MS)
        assertEquals(3, ears.asked.size)
        pass(MainViewModel.BY_EAR_RETRY_MS)
        assertEquals(4, ears.asked.size)
    }

    @Test
    fun aNewWayToHearListensAgainAtOnceAfterNoSongWasHeard() {
        var micAllowed = false
        var shared = false
        // At volume 0, with no microphone allowed and the phone's own sound not shared, nothing's heard.
        val playback = FakePlayback().apply { aloud = false }
        val ears = EarsOnThePhone(Heard.Failed(AppError.MICROPHONE), Heard.Nothing)
        val model = radioModel(ears, playback, mayCalibrate = { micAllowed }, mayCapture = { shared })
        model.playingBarInSight(true)
        idle()
        pass(MainViewModel.BY_EAR_RETRY_MS)
        pass(MainViewModel.BY_EAR_RETRY_MS * 2)
        assertEquals(3, ears.asked.size)
        // Shizuku shares the phone's own sound: heard again within a second, not at the end of a rest that grew.
        shared = true
        pass(MainViewModel.EARS_CHECK_MS)
        assertEquals(4, ears.asked.size)
        // The rests start over from the shortest.
        pass(MainViewModel.BY_EAR_RETRY_MS)
        assertEquals(5, ears.asked.size)
        // The microphone's allowed, then the phone plays aloud again: each starts a listen at once.
        micAllowed = true
        pass(MainViewModel.EARS_CHECK_MS)
        assertEquals(6, ears.asked.size)
        playback.aloud = true
        pass(MainViewModel.EARS_CHECK_MS)
        assertEquals(7, ears.asked.size)
        assertEquals(true, ears.asked.last())
        // Headphones plugged in: the phone's own sound alone, at once too.
        playback.aloud = false
        pass(MainViewModel.EARS_CHECK_MS)
        assertEquals(8, ears.asked.size)
        assertEquals(false, ears.asked.last())
    }

    @Test
    fun aSongHeardIsNotListenedToAgainForANewWayToHear() {
        val playback = FakePlayback()
        val ears = EarsOnThePhone(heardSong())
        radioModel(ears, playback).playingBarInSight(true)
        idle()
        playback.aloud = false
        pass(MainViewModel.EARS_CHECK_MS * 5)
        assertEquals(1, ears.asked.size)
    }

    @Test
    fun aSongOfNoKnownLengthOrAFailureIsListenedToAgainInAWhile() {
        val ears = EarsOnThePhone(heardSong(lengthMs = null), Heard.Failed(AppError.NETWORK), Heard.Unsent { Heard.Nothing }, heardSong(offsetMs = null), heardSong(offsetMs = 199_000))
        val model = radioModel(ears)
        model.playingBarInSight(true)
        idle()
        pass(MainViewModel.BY_EAR_UNKNOWN_MS)
        assertEquals(2, ears.asked.size)
        // Its time up, the song heard is no longer shown, and a failure names no other.
        assertEquals(PlayingUnnamed(station), model.uiState.playingUnnamed)
        pass(MainViewModel.BY_EAR_RETRY_MS)
        assertEquals(3, ears.asked.size)
        pass(MainViewModel.BY_EAR_RETRY_MS)
        assertEquals(4, ears.asked.size)
        pass(MainViewModel.BY_EAR_UNKNOWN_MS)
        assertEquals(5, ears.asked.size)
        // Heard right at its end, it's still given a moment, as the end may be misheard.
        pass(MainViewModel.BY_EAR_SOONEST_MS - 1_000)
        assertEquals(5, ears.asked.size)
        pass(1_000)
        assertEquals(6, ears.asked.size)
    }

    @Test
    fun theAppsOwnNameForASongWinsAndAnotherStationStartsOver() {
        val playback = FakePlayback()
        val ears = EarsOnThePhone(heardSong())
        val model = radioModel(ears, playback)
        model.playingBarInSight(true)
        idle()
        assertEquals(1, ears.asked.size)
        // Another station is listened to at once.
        val other = station.copy(name = "Fake FM")
        playback.unnamed.value = other
        idle()
        assertEquals(2, ears.asked.size)
        assertEquals(PlayingUnnamed(other, ears.answers[0] as Heard.Song), model.uiState.playingUnnamed)
        // The app names the song: its name is shown, and no more is listened to.
        playback.unnamed.value = null
        playback.onThePhone = Heard.Song(song, null, app = "com.ilv.vradio")
        idle()
        assertNull(model.uiState.playingUnnamed)
        assertEquals(song, model.uiState.playingNow?.song)
        pass(MainViewModel.BY_EAR_LATEST_MS)
        assertEquals(2, ears.asked.size)
    }

    @Test
    fun aSongHeardIsNeitherShownNorOpenedOnceItShouldBeOver() {
        val ears = EarsOnThePhone(heardSong(), Heard.Failed(AppError.NETWORK))
        val model = radioModel(ears)
        model.playingBarInSight(true)
        idle()
        assertNotNull(model.uiState.playingUnnamed?.heard)
        // Away from Crosstune for 20 minutes, while the station played on.
        model.playingBarInSight(false)
        pass(20 * 60_000L)
        // Back, the bar shows the station alone at once, before the next listen answers, and a tap opens nothing.
        model.playingBarInSight(true)
        assertEquals(PlayingUnnamed(station), model.uiState.playingUnnamed)
        model.openPlayingUnnamed()
        assertNull(model.uiState.result)
        // That listen fails: the old song isn't shown again.
        idle()
        assertEquals(2, ears.asked.size)
        assertEquals(PlayingUnnamed(station), model.uiState.playingUnnamed)
        model.openPlayingUnnamed()
        idle()
        assertNull(model.uiState.result)
    }

    @Test
    fun aSongHeardIsGoneWhenCrosstuneComesBackAfterItShouldBeOver() {
        val ears = EarsOnThePhone(heardSong())
        val model = radioModel(ears)
        model.playingBarInSight(true)
        idle()
        assertNotNull(model.uiState.playingUnnamed?.heard)
        // Crosstune stops watching while it's out of sight, and watches again when it's back.
        model.playingBarInSight(false)
        pass(20 * 60_000L)
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        assertEquals(PlayingUnnamed(station), model.uiState.playingUnnamed)
    }

    @Test
    fun aSongHeardIsShownAndOpenedTillItShouldBeOverAndNotAMomentAfter() {
        // 150 s of it left: a second before, a tap shows it, as a Shazam link would.
        val before = radioModel(EarsOnThePhone(heardSong(), Heard.Failed(AppError.NETWORK)))
        before.playingBarInSight(true)
        idle()
        pass(149_000)
        assertNotNull(before.uiState.playingUnnamed?.heard)
        before.openPlayingUnnamed()
        idle()
        assertTrue(before.uiState.linkText, before.uiState.linkText.endsWith("/song/1234"))
        // At its end it goes from the bar, though the next listen is a while off, and a tap opens no song.
        val ears = EarsOnThePhone(heardSong(), Heard.Failed(AppError.NETWORK))
        val over = radioModel(ears)
        over.playingBarInSight(true)
        idle()
        pass(150_000)
        assertEquals(1, ears.asked.size)
        assertEquals(PlayingUnnamed(station), over.uiState.playingUnnamed)
        over.openPlayingUnnamed()
        idle()
        assertNull(over.uiState.result)
        // Nor out of the bar's sight, once it's over.
        val away = radioModel(EarsOnThePhone(heardSong()))
        away.playingBarInSight(true)
        idle()
        away.playingBarInSight(false)
        pass(151_000)
        away.openPlayingUnnamed()
        idle()
        assertNull(away.uiState.result)
    }

    @Test
    fun anotherStationIsListenedToWithTheRestsFromTheShortestAgain() {
        val playback = FakePlayback()
        val ears = EarsOnThePhone(Heard.Nothing)
        val model = radioModel(ears, playback)
        model.playingBarInSight(true)
        idle()
        pass(MainViewModel.BY_EAR_RETRY_MS)
        pass(MainViewModel.BY_EAR_RETRY_MS * 2)
        assertEquals(3, ears.asked.size)
        // Talk on one station says nothing of the next: it's listened to at once, then after the shortest rest.
        playback.unnamed.value = station.copy(name = "Fake FM")
        idle()
        assertEquals(4, ears.asked.size)
        pass(MainViewModel.BY_EAR_RETRY_MS)
        assertEquals(5, ears.asked.size)
    }

    @Test
    fun aListenByHandHasTheMicrophoneAloneThoughTheStationChangesMeanwhile() {
        val playback = FakePlayback()
        val ears = EarsOnThePhone(Heard.Nothing)
        val model = radioModel(ears, playback)
        model.playingBarInSight(true)
        idle()
        assertEquals(1, ears.asked.size)
        val hold = CompletableDeferred<Unit>()
        ears.holdNearby = hold
        model.listen()
        idle()
        assertTrue(model.uiState.listening)
        // Another station while it listens: no listen by ear opens the microphone a second time.
        playback.unnamed.value = station.copy(name = "Fake FM")
        idle()
        assertEquals(1, ears.asked.size)
        hold.complete(Unit)
        idle()
        // The bar back in sight, the new station is listened to.
        model.playingBarInSight(true)
        idle()
        assertEquals(2, ears.asked.size)
    }

    /** Takes [takesMs] to hear [heard], timed from when it began to listen, as a recording is. */
    private class SlowEars(val takesMs: Long, val heard: Heard.Song) : SongHearing {
        override suspend fun listen(): Heard = Heard.Nothing
        override suspend fun listenToPlaying(byMicrophone: Boolean, whileAloud: Boolean): Heard {
            val from = SystemClock.elapsedRealtime()
            delay(takesMs)
            return heard.copy(startedAtMs = from)
        }
    }

    @Test
    fun aSongHeardGoesAtItsEndFromWhenTheListenBeganNotWhenItEnded() {
        // 150 s of it left when the listen began, which took 10 s.
        val model = radioModel(SlowEars(10_000, heardSong()))
        model.playingBarInSight(true)
        pass(10_000)
        assertNotNull(model.uiState.playingUnnamed?.heard)
        pass(139_999)
        assertNotNull(model.uiState.playingUnnamed?.heard)
        pass(1)
        assertEquals(PlayingUnnamed(station), model.uiState.playingUnnamed)
    }

    /** 600 s of it left as it's heard now: listened to again sooner, at the longest rest. */
    private fun longSong() = heardSong(lengthMs = 650_000)

    @Test
    fun aLongSongHeardStaysTillItsEndThroughAFailedListen() {
        val long = longSong()
        val model = radioModel(EarsOnThePhone(long, Heard.Failed(AppError.NETWORK)))
        model.playingBarInSight(true)
        idle()
        pass(MainViewModel.BY_EAR_LATEST_MS)
        // That listen failed, which says nothing of the song: it stays till its end, to the moment.
        assertEquals(long, model.uiState.playingUnnamed?.heard)
        pass(600_000 - MainViewModel.BY_EAR_LATEST_MS - 1)
        assertEquals(long, model.uiState.playingUnnamed?.heard)
        pass(1)
        assertEquals(PlayingUnnamed(station), model.uiState.playingUnnamed)
    }

    @Test
    fun aLongSongHeardGoesForTalkHeardBeforeItsEnd() {
        // No song plays, so none is shown, though it had a while left.
        val model = radioModel(EarsOnThePhone(longSong(), Heard.Nothing))
        model.playingBarInSight(true)
        idle()
        pass(MainViewModel.BY_EAR_LATEST_MS)
        assertEquals(PlayingUnnamed(station), model.uiState.playingUnnamed)
    }

    @Test
    fun anotherSongHeardBeforeTheFirstsEndStaysTillItsOwn() {
        // Timed from when it was heard, 360 s in: its end is at 960 s, the first one's at 600 s.
        val next = Heard.Song(MusicMetadata("Other Fake Song", "Fake Artist", lengthMs = 650_000), "5678", 50_000, null)
        val model = radioModel(EarsOnThePhone(longSong(), next, Heard.Failed(AppError.NETWORK)))
        model.playingBarInSight(true)
        idle()
        pass(600_000)
        assertEquals(next, model.uiState.playingUnnamed?.heard)
        pass(MainViewModel.BY_EAR_LATEST_MS - 1)
        assertEquals(next, model.uiState.playingUnnamed?.heard)
        pass(1)
        assertEquals(PlayingUnnamed(station), model.uiState.playingUnnamed)
    }

    @Test
    fun backInSightMidRestTheNextListenIsOnTime() {
        val ears = EarsOnThePhone(Heard.Nothing)
        val model = radioModel(ears)
        model.playingBarInSight(true)
        idle()
        // Out of sight half a second into the rest after nothing was heard, and back at once.
        pass(500)
        model.playingBarInSight(false)
        idle()
        model.playingBarInSight(true)
        idle()
        pass(MainViewModel.BY_EAR_RETRY_MS - 501)
        assertEquals(1, ears.asked.size)
        pass(1)
        assertEquals(2, ears.asked.size)
    }

    @Test
    fun aSongHeardWithNoEndKnownIsShownButATapNeverOpensIt() {
        // Without its length, or where in it it was heard, it may be over any time.
        for (heard in listOf(heardSong(lengthMs = null), heardSong(offsetMs = null))) {
            val model = radioModel(EarsOnThePhone(heard))
            model.playingBarInSight(true)
            idle()
            assertEquals(heard, model.uiState.playingUnnamed?.heard)
            model.openPlayingUnnamed()
            idle()
            assertNull(model.uiState.result)
            // The station's player opens instead.
            assertEquals(station.played, model.uiState.player?.song)
        }
    }

    @Test
    fun aSongOnlyTheMicrophoneHeardIsShownButATapNeverOpensIt() {
        // It may be another song playing in the room, not the station's (#1002).
        val heard = heardSong().copy(inRoom = true)
        val model = radioModel(EarsOnThePhone(heard))
        model.playingBarInSight(true)
        idle()
        assertEquals(heard, model.uiState.playingUnnamed?.heard)
        model.openPlayingUnnamed()
        idle()
        assertNull(model.uiState.result)
        assertEquals(station.played, model.uiState.player?.song)
    }

    @Test
    fun theWordsOfWhatsPlayingListenAgainForASongOnTheBarATapWouldntOpen() {
        // One that may be over by now, and one that may be another song playing in the room (#1002).
        for (heard in listOf(heardSong(lengthMs = null), heardSong().copy(inRoom = true))) {
            val ears = EarsOnThePhone(heard, Heard.Nothing)
            val model = radioModel(ears)
            model.playingBarInSight(true)
            idle()
            assertEquals(heard, model.uiState.playingUnnamed?.heard)
            model.listen(lyrics = true)
            idle()
            // Listened to again, what the phone plays first, and nothing was heard: no song opens.
            assertEquals(2, ears.asked.size)
            assertNull(model.uiState.lyricsFor)
            assertNull(model.uiState.result)
            assertEquals(AppError.NO_MATCH, model.uiState.error)
        }
    }

    @Test
    fun theWordsOfWhatsPlayingStopAListenByHandForTheSongOnTheBar() {
        fake.handler = { request -> FakeSpotify.html(request, "") }
        val finder = LyricsFinder(fake.inlineClient(), "test", Dispatchers.Unconfined, busyPauseMs = 0, matching = Dispatchers.Unconfined)
        val ears = EarsOnThePhone(heardSong())
        val model = radioModel(ears, lyricsFinder = finder)
        model.playingBarInSight(true)
        idle()
        // A listen by hand for a song nearby, which hears another one once it's done.
        val hold = CompletableDeferred<Unit>()
        ears.holdNearby = hold
        ears.heardNearby = Heard.Song(MusicMetadata("Other Fake Song", "Other Fake Band"), "5678", 10_000, null)
        model.listen()
        idle()
        assertTrue(model.uiState.listening)
        // The words of what's playing meanwhile: the song on the bar's, and the listen by hand stops.
        model.listen(lyrics = true)
        idle()
        hold.complete(Unit)
        idle()
        assertEquals("Fake Song", model.uiState.lyricsFor?.title)
        assertTrue(model.uiState.linkText, model.uiState.linkText.endsWith("/song/1234"))
        assertFalse(model.uiState.listening)
    }

    @Test
    fun theWordsOfTheSongHeardOpenOverItsOwnPlayerAsItsCardWould() {
        fake.handler = { request -> FakeSpotify.html(request, "") }
        val finder = LyricsFinder(fake.inlineClient(), "test", Dispatchers.Unconfined, busyPauseMs = 0, matching = Dispatchers.Unconfined)
        val heard = heardSong()
        val playback = FakePlayback()
        val model = radioModel(EarsOnThePhone(heard), playback, lyricsFinder = finder)
        model.playingBarInSight(true)
        idle()
        assertEquals(heard, model.uiState.playingUnnamed?.heard)
        // The song heard plays in an app that names it too, and its player is open.
        playback.onThePhone = Heard.Song(heard.metadata, null, 0, SystemClock.elapsedRealtime())
        idle()
        model.openPlayingNow()
        idle()
        assertEquals(heard.metadata, model.uiState.player?.song)
        // Its words open over its player, which stays under them, as its card does.
        model.listen(lyrics = true)
        idle()
        assertEquals("Fake Song", model.uiState.lyricsFor?.title)
        assertEquals(heard.metadata, model.uiState.player?.song)
    }

    @Test
    fun aStationsButtonsFollowItsAppAsItPausesAndPlays() {
        val playback = FakePlayback()
        playback.playing.value = Following(PlaybackClock(1_000, 0, playing = true), "VRadio", canSeek = false)
        val model = radioModel(EarsOnThePhone(Heard.Nothing), playback)
        assertEquals(true, model.uiState.playingUnnamed?.following?.clock?.playing)
        playback.playing.value = Following(PlaybackClock(2_000, 0, playing = false), "VRadio", canSeek = false)
        idle()
        assertEquals(false, model.uiState.playingUnnamed?.following?.clock?.playing)
    }

    @Test
    fun anotherStationIsNotShownAsTheLastWasFollowedTillItsAppSaysHowItIs() {
        val playback = FakePlayback()
        playback.playing.value = Following(PlaybackClock(1_000, 0, playing = true), "VRadio", canSeek = false)
        val model = radioModel(EarsOnThePhone(Heard.Nothing), playback)
        assertEquals(true, model.uiState.playingUnnamed?.following?.clock?.playing)
        // Another station, whose app takes a moment to say how it plays.
        val other = Unnamed("com.example.radio", "Fake Radio", "Fake FM")
        playback.said = CompletableDeferred()
        playback.unnamed.value = other
        idle()
        assertEquals(other, model.uiState.playingUnnamed?.playing)
        assertNull(model.uiState.playingUnnamed?.following)
        playback.said?.complete(Unit)
        idle()
        assertEquals(true, model.uiState.playingUnnamed?.following?.clock?.playing)
    }

    /** A model on [station] playing, with its bar in sight, which [ears] named nothing in. */
    private fun stationPlaying(ears: EarsOnThePhone, playback: FakePlayback = FakePlayback()): MainViewModel {
        playback.playing.value = Following(PlaybackClock(1_000, 0, playing = true), "VRadio", canSeek = false)
        return radioModel(ears, playback).also { it.playingBarInSight(true); idle() }
    }

    private fun FakePlayback.pauses() {
        playing.value = Following(PlaybackClock(2_000, 0, playing = false), "VRadio", canSeek = false)
        idle()
    }

    @Test
    fun aPausedStationStaysOnTheBarWithItsButtonsAndIsntListenedToTillItPlaysAgain() {
        val ears = EarsOnThePhone(heardSong(), Heard.Nothing)
        val playback = FakePlayback()
        val model = stationPlaying(ears, playback)
        assertEquals(1, ears.asked.size)
        assertEquals(heardSong().metadata, model.uiState.playingUnnamed?.heard?.metadata)
        // Paused from its app, or the bar: it stays, with Play, as a paused song does. The song heard in it isn't known now.
        playback.pauses()
        assertEquals(PlayingUnnamed(station, following = playback.playing.value), model.uiState.playingUnnamed)
        assertNull(model.uiState.playingNow)
        // Nothing plays to hear, however long it's paused.
        pass(MainViewModel.BY_EAR_LATEST_MS * 2)
        assertEquals(1, ears.asked.size)
        // Its Play asks its app, by package.
        model.playingNowPlayPause()
        assertEquals(listOf(true), playback.plays)
        assertEquals(listOf<String?>(station.app), playback.playedApps)
        // Played again, it's listened to at once.
        playback.playing.value = Following(PlaybackClock(2_000, 0, playing = true), "VRadio", canSeek = false)
        idle()
        assertEquals(2, ears.asked.size)
        assertEquals(true, model.uiState.playingUnnamed?.following?.clock?.playing)
    }

    @Test
    fun aPausedStationItsAppNoLongerHasLeavesTheBar() {
        val playback = FakePlayback()
        val model = stationPlaying(EarsOnThePhone(Heard.Nothing), playback)
        playback.pauses()
        assertEquals(station, model.uiState.playingUnnamed?.playing)
        // Stopped, as a song's app stops it, the app has nothing to play on.
        playback.unnamed.value = null
        playback.playing.value = null
        idle()
        assertNull(model.uiState.playingUnnamed)
    }

    @Test
    fun aSongPlayedSinceTakesAPausedStationsPlaceEvenOnceItsPausedToo() {
        val playback = FakePlayback()
        val model = stationPlaying(EarsOnThePhone(Heard.Nothing), playback)
        playback.pauses()
        val song = MusicMetadata("Other Fake Song", "Fake Artist")
        playback.onThePhone = Heard.Song(song, null, 0, SystemClock.elapsedRealtime())
        idle()
        assertEquals(song, model.uiState.playingNow?.song)
        assertNull(model.uiState.playingUnnamed)
        // Paused too, the song stays, as it played last; the station doesn't come back.
        playback.onThePhone = null
        idle()
        assertEquals(song, model.uiState.playingNow?.song)
        assertNull(model.uiState.playingUnnamed)
    }

    @Test
    fun aPausedStationIsAskedOfItsAppAgainOnceCrosstuneIsBack() {
        val playback = FakePlayback()
        val model = stationPlaying(EarsOnThePhone(Heard.Nothing), playback)
        playback.pauses()
        // Left and back: its app isn't said to play it, so it's asked whether it still has it.
        val watching = model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        assertEquals(PlayingUnnamed(station, following = playback.playing.value), model.uiState.playingUnnamed)
        watching.cancel()
    }

    @Test
    fun aStationsButtonsAskItsAppByPackageOnTheBarAndInItsPlayer() {
        val playback = FakePlayback()
        val model = stationPlaying(EarsOnThePhone(Heard.Nothing), playback)
        playback.playing.value = Following(
            PlaybackClock(1_000, 0, playing = true), "VRadio", canSeek = true, canSkipNext = true, shuffle = false, repeat = Repeat.OFF, like = AppAction("like", "Like", 0)
        )
        idle()
        model.playingNowPlayPause()
        model.playingNowSkip()
        model.peekPlayingUnnamed()
        idle()
        model.playerPlayPause()
        model.playerSkip(true)
        model.playerShuffle()
        model.playerRepeat()
        model.playerLike()
        model.playerPlayQueued(3)
        model.playerSeek(1_000)
        assertEquals(listOf<String?>(station.app, station.app), playback.playedApps)
        assertEquals(List<String?>(6) { station.app }, playback.controlledApps)
        assertEquals(listOf<String?>(station.app), playback.seekedApps)
    }

    @Test
    fun theWordsOfAStationsSongStillHearWhatThePhonePlaysAfterALinkIsOnlyHandedOn() {
        // Links go straight to YouTube Music.
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false).commit()
        fake.handler = { request -> FakeSpotify.html(request, "") }
        val finder = LyricsFinder(fake.inlineClient(), "test", Dispatchers.Unconfined, busyPauseMs = 0, matching = Dispatchers.Unconfined)
        val ears = EarsOnThePhone(heardSong())
        val model = radioModel(ears, lyricsFinder = finder)
        model.playingBarInSight(true)
        idle()
        model.listen(lyrics = true)
        idle()
        val song = model.uiState.lyricsFor
        assertEquals("Fake Song", song?.title)
        // A link only handed on leaves the words floating, and how they're kept in time, as they were.
        model.resolveIncoming("see https://soundcloud.com/discover.", keepWords = true)
        idle()
        assertEquals(song, model.uiState.lyricsFor)
        // Still listening along with them, after its pause, it's what the phone plays that's heard, never the room.
        val asked = ears.asked.size
        pass(MainViewModel.LISTEN_ALONG_PAUSE_MS)
        assertTrue(ears.asked.size > asked)
        assertEquals(0, ears.nearby)
    }

    @Test
    fun theWordsOfWhatsPlayingAreTheSongHeardOnTheBarFirstThenThePhonesOwnSound() {
        fake.handler = { request -> FakeSpotify.html(request, "") }
        val finder = LyricsFinder(fake.inlineClient(), "test", Dispatchers.Unconfined, busyPauseMs = 0, matching = Dispatchers.Unconfined)
        val ears = EarsOnThePhone(heardSong())
        val model = radioModel(ears, lyricsFinder = finder)
        model.playingBarInSight(true)
        idle()
        // Still on: its words, named with no listen again. Listening along with them hears what the phone plays,
        // its own sound first, never the microphone alone, which hears the room too: see SongListenerTest.
        model.listen(lyrics = true)
        idle()
        assertEquals("Fake Song", model.uiState.lyricsFor?.title)
        assertTrue(model.uiState.linkText, model.uiState.linkText.endsWith("/song/1234"))
        assertEquals(0, ears.nearby)
        model.dismissLyrics()
        // Over, it's what the phone plays that's heard, the same way.
        model.playingBarInSight(false)
        pass(151_000)
        val asked = ears.asked.size
        model.listen(lyrics = true)
        idle()
        assertTrue(ears.asked.size > asked)
        assertEquals(0, ears.nearby)
        // A song named nearby is listened along with by the microphone, as before.
        ears.heardNearby = heardSong()
        model.listen()
        idle()
        model.showLyrics()
        model.listenAlong()
        idle()
        assertTrue(ears.nearby > 1)
        // With no app playing, the microphone hears what's nearby.
        val nothing = EarsOnThePhone(heardSong())
        model(playback = FakePlayback().apply { access = true }, listener = nothing).listen(lyrics = true)
        idle()
        assertEquals(1, nothing.nearby)
        assertTrue(nothing.asked.isEmpty())
    }

    @Test
    fun aStationKeepsTheBarsButtonsAndItsPlayerWithNothingThatTakesItForASong() {
        val playback = FakePlayback()
        val ears = EarsOnThePhone(Heard.Nothing)
        val vradio = following(app = station.app, skipNext = true)
        playback.playing.value = vradio
        val model = radioModel(ears, playback)
        // Followed in its app, as a song is.
        assertEquals(PlayingUnnamed(station, following = vradio), model.uiState.playingUnnamed)
        assertEquals(station.played, playback.followed.last())
        assertEquals(station.app, playback.followedApps.last())
        // The bar's buttons pause it and skip, as for a song.
        model.playingNowPlayPause()
        model.playingNowSkip()
        assertEquals(listOf(false), playback.plays)
        assertEquals(listOf(true), playback.skips)
        // A swipe up, or a tap with no song heard, opens its player, with its buttons and where it is.
        model.peekPlayingUnnamed()
        idle()
        val player = checkNotNull(model.uiState.player)
        assertTrue(player.songless)
        assertEquals("RTP Antena 2", player.song.title)
        assertEquals(vradio, player.following)
        assertEquals("Spotify", player.byLine)
        // No card, words or link, and nothing kept in Recent.
        model.playerCard()
        model.playerMenu()
        model.playerLyrics()
        idle()
        assertNull(model.uiState.result)
        assertNull(model.uiState.lyricsFor)
        assertTrue(model.uiState.history.isEmpty())
        assertEquals(station.played, model.uiState.player?.song)
        // Skipped to the next station, the player takes it up, as it would the next song.
        val other = station.copy(name = "Fake FM")
        playback.playing.value = null
        playback.unnamed.value = other
        idle()
        assertEquals(other.played, model.uiState.player?.song)
        assertEquals(true, model.uiState.player?.songless)
        // The app names the song: the player's on it, as a song.
        playback.unnamed.value = null
        playback.onThePhone = Heard.Song(song, null, app = station.app)
        idle()
        assertEquals(song, model.uiState.player?.song)
        assertEquals(false, model.uiState.player?.songless)
        assertEquals(song.artist, model.uiState.player?.byLine)
    }

    @Test
    fun aFileWithNoArtistKeepsItsPlayerAndIsFollowedByWhatItsAppHasForOne() {
        // A file with a title and "<unknown>" for its artist, followed in its app by both, as the app has them.
        val file = Unnamed("com.example.player", "Player", "Fake Song", artist = "<unknown>")
        val playback = FakePlayback().apply { access = true; unnamed.value = file }
        val model = model(playback = playback)
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        assertEquals(MusicMetadata("Fake Song", "<unknown>"), playback.followed.last())
        model.openPlayingUnnamed()
        idle()
        assertEquals(true, model.uiState.player?.songless)
        assertEquals(MusicMetadata("Fake Song", "<unknown>"), playback.followed.last())
        assertEquals("com.example.player", playback.followedApps.last())
        // Its words aren't looked up, as "<unknown>" names no one.
        assertTrue(model.uiState.player?.lines.orEmpty().isEmpty())
    }

    /** Listens until it's stopped, and keeps each time it was, and whether it was let use the microphone. */
    private class LongEars(val closingMs: Long) : SongHearing {
        val asked = mutableListOf<Boolean>()
        var stopped = 0
        /** Whether the last listen by ear had closed the microphone when one was asked for by hand. */
        var closedFirst: Boolean? = null
        private var open = false
        override suspend fun listen(): Heard {
            closedFirst = !open
            return Heard.Nothing
        }
        override suspend fun listenToPlaying(byMicrophone: Boolean, whileAloud: Boolean): Heard {
            asked += byMicrophone
            open = true
            try {
                awaitCancellation()
            } finally {
                stopped++
                // Letting the microphone go takes a moment.
                withContext(NonCancellable) { delay(closingMs) }
                open = false
            }
        }
    }

    @Test
    fun aListenByHandWaitsForTheMicrophoneToBeLetGo() {
        val ears = LongEars(closingMs = 500)
        val model = radioModel(ears)
        model.playingBarInSight(true)
        idle()
        assertEquals(1, ears.asked.size)
        model.listen()
        pass(1_000)
        assertEquals(1, ears.stopped)
        assertEquals(true, ears.closedFirst)
    }

    /** Ears that take [closingMs] to let go, and keep count of the most listens that ever had them at once. */
    private class SharedEars(val closingMs: Long) : SongHearing {
        var listens = 0
        var mostAtOnce = 0
        private var open = 0
        private suspend fun held(): Heard {
            listens++
            mostAtOnce = maxOf(mostAtOnce, ++open)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(closingMs) }
                open--
            }
        }
        override suspend fun listen() = held()
        override suspend fun listenToPlaying(byMicrophone: Boolean, whileAloud: Boolean) = held()
    }

    @Test
    fun aListenByHandHasTheEarsAloneThoughAnotherStationCameWhileTheLastListenLetGo() {
        val playback = FakePlayback()
        val ears = SharedEars(closingMs = 500)
        val model = radioModel(ears, playback)
        model.playingBarInSight(true)
        idle()
        assertEquals(1, ears.listens)
        // Another station: the listen on the last one starts to let go, and a listen by hand comes meanwhile.
        // The listen by hand waits only on that last one; the next station's is put off till the bar's gone
        // from sight, which the listen by hand already said, so it never starts.
        playback.unnamed.value = station.copy(name = "Fake FM")
        model.listen()
        pass(2_000)
        assertTrue("listened by hand", ears.listens >= 2)
        assertEquals(1, ears.mostAtOnce)
    }

    @Test
    fun listeningAlongWaitsForTheListenByEarToLetGoAndTheListenByEarWaitsForItToStop() {
        val ears = LongEars(closingMs = 500)
        val model = radioModel(ears)
        model.playingBarInSight(true)
        idle()
        assertEquals(1, ears.asked.size)
        // The words open while a listen by ear goes on: listening along waits till the microphone's let go.
        model.listenAlong()
        pass(1_000)
        assertEquals(1, ears.stopped)
        assertEquals(true, ears.closedFirst)
        // Listening along has it meanwhile: naming by ear waits till it's done, then goes on.
        pass(MainViewModel.BY_EAR_LATEST_MS)
        assertEquals(1, ears.asked.size)
        model.pauseListeningAlong()
        idle()
        assertEquals(2, ears.asked.size)
    }

    @Test
    fun aSongAnotherAppPausedIsNotOfferedOverAStationPlaying() {
        val playback = FakePlayback()
        val model = playingModel(playback, open = false)
        assertEquals(song, model.uiState.playingNow?.song)
        // Spotify pauses, and keeps its song; VRadio starts.
        playback.playing.value = following(playing = false)
        playback.onThePhone = null
        // VRadio's followed apart from Spotify, which has the song paused.
        playback.appPlaying[station.app] = MutableStateFlow(null)
        playback.unnamed.value = station
        idle()
        assertNull(model.uiState.playingNow)
        assertEquals(PlayingUnnamed(station), model.uiState.playingUnnamed)
        // VRadio stops: the paused song is offered again.
        playback.unnamed.value = null
        idle()
        assertEquals(song, model.uiState.playingNow?.song)
    }

    @Test
    fun lookingUpTheWordsOfASongHeardDoesNotHoldTheNextListen() {
        var asked = 0
        fake.handler = { request ->
            // The lookup takes 10 s, all on its first ask.
            if (asked++ == 0) ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
            FakeSpotify.html(request, "")
        }
        // Answered on the main thread, so the lookup is done when the looper idles, with no real clock in it.
        val finder = LyricsFinder(fake.inlineClient(), "test", Dispatchers.Unconfined, busyPauseMs = 0, matching = Dispatchers.Unconfined)
        val heardAt = SystemClock.elapsedRealtime()
        // 5 s of the song left: listened to again as soon as it ever is after a song.
        val ears = EarsOnThePhone(heardSong(offsetMs = 195_000))
        radioModel(ears, lyricsFinder = finder).playingBarInSight(true)
        idle()
        assertEquals(1, ears.asked.size)
        assertTrue("the words were looked for", asked > 0)
        assertEquals(heardAt + 10_000, SystemClock.elapsedRealtime())
        pass(heardAt + MainViewModel.BY_EAR_SOONEST_MS - SystemClock.elapsedRealtime() - 500)
        assertEquals(1, ears.asked.size)
        pass(1_000)
        assertEquals(2, ears.asked.size)
    }

    @Test
    fun theWordsOfASongHeardAreLookedForNoMoreOnceAnotherSongIsHeard() {
        val asked = mutableListOf<String>()
        // Every place is busy, so a lookup asks again after each pause, on the test's clock, till it gives up.
        fake.handler = { request ->
            asked += request.url.toString()
            FakeSpotify.html(request, "", code = 503)
        }
        val finder = LyricsFinder(fake.inlineClient(), "test", Dispatchers.Unconfined, busyPauseMs = 2_000, matching = Dispatchers.Main)
        val playback = FakePlayback()
        val other = Heard.Song(MusicMetadata("Other Fake Song", "Other Fake Band", lengthMs = 200_000), "5678", 50_000, null)
        val ears = EarsOnThePhone(heardSong(), other)
        radioModel(ears, playback, lyricsFinder = finder).playingBarInSight(true)
        idle()
        val first = { asked.count { "Other" !in it } }
        assertTrue("the first song's words were looked for", first() > 0)
        // Another station, and another song heard on it at once, before the first song's lookup is done.
        playback.unnamed.value = station.copy(name = "Fake FM")
        idle()
        assertEquals(2, ears.asked.size)
        assertTrue("the second song's words were looked for", asked.any { "Other" in it })
        val then = first()
        pass(LyricsFinder.TOTAL_TIMEOUT_MS)
        assertEquals(then, first())
    }

    @Test
    fun listeningByHandStopsListeningByEar() {
        val ears = EarsOnThePhone(heardSong())
        val model = radioModel(ears)
        model.playingBarInSight(true)
        idle()
        model.listen()
        idle()
        pass(MainViewModel.BY_EAR_LATEST_MS)
        assertEquals(1, ears.asked.size)
    }

    @Test
    fun withNoAccessNothingPlayingIsShown() {
        val playback = FakePlayback()
        val model = radioModel(EarsOnThePhone(Heard.Nothing), playback)
        assertNotNull(model.uiState.playingUnnamed)
        playback.access = false
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        assertNull(model.uiState.playingUnnamed)
    }

    @Test
    fun theControlsOfAPlayerNotOpenDoNothing() {
        val playback = FakePlayback()
        val model = model(playback = playback)
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        model.openPlayerApp()
        model.playerMenu()
        model.playerPlayPause()
        model.playerSkip(next = true)
        model.playerShuffle()
        model.playerRepeat()
        model.playerLike()
        model.playerPlayQueued(1)
        model.playerPlayLater(0)
        model.playerMoveQueued(0, 1)
        model.playerCard()
        model.playerSeek(500)
        model.playerLyrics()
        model.playerSleep(null)
        model.playerSleep(5)
        model.playerSleepOff()
        idle()
        assertNull(model.uiState.player)
        assertTrue(effects.isEmpty())
        assertTrue(playback.songsAsked.isEmpty() && playback.plays.isEmpty() && playback.skips.isEmpty() && playback.seeks.isEmpty())
        assertTrue(playback.shuffled.isEmpty() && playback.repeated.isEmpty() && playback.queuedPlayed.isEmpty() && playback.liked == 0)
        assertNull(model.uiState.sleep)
    }

    @Test
    fun theSongPlayingNowIsOfferedAndItsButtonsReachItsApp() {
        val playback = FakePlayback()
        val model = model(playback = playback)
        // Nothing playing: its buttons and the player have nothing to act on.
        model.openPlayingNow()
        model.peekPlayingNow()
        model.playingNowPlayPause()
        model.playingNowSkip()
        assertNull(model.uiState.player)
        assertTrue(playback.plays.isEmpty() && playback.skips.isEmpty())

        playback.access = true
        playback.onThePhone = Heard.Song(song, null, app = "com.spotify")
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        assertEquals(song, model.uiState.playingNow?.song)
        // The app doesn't say where it is yet, so only that something was asked.
        model.playingNowPlayPause()
        assertEquals(1, playback.plays.size)
        playback.playing.value = following(playing = true)
        idle()
        playback.plays.clear()
        model.playingNowPlayPause()
        assertEquals(listOf(false), playback.plays.toList())
        playback.playing.value = following(playing = false)
        idle()
        model.playingNowPlayPause()
        assertEquals(listOf(false, true), playback.plays.toList())
        model.playingNowSkip()
        assertEquals(listOf(true), playback.skips.toList())
    }

    @Test
    fun openingTheSongPlayingNowShowsItsCardAndItsPlayerOnce() {
        val playback = FakePlayback()
        playback.playing.value = following()
        val model = playingModel(playback, open = false)
        model.openPlayingNow()
        idle()
        assertEquals(song, model.uiState.player?.song)
        assertEquals("com.spotify", model.uiState.player?.following?.appPackage)
        // Opened again, the same player stays as it is.
        val player = model.uiState.player
        model.openPlayingNow()
        idle()
        assertEquals(player, model.uiState.player)
        // The card of the player's song is under it already; closing it leaves the card.
        model.playerMenu()
        model.playerCard()
        assertNull(model.uiState.player)
    }

    @Test
    fun aPlayerWithAnAppSendsItsButtonsThere() {
        val playback = FakePlayback()
        val sleep = SleepTimer(playback, MainScope())
        playback.playing.value = following(playing = true, skipNext = true, shuffle = false, repeat = Repeat.OFF, like = AppAction("like", "Like", 0))
        val model = playingModel(playback, sleepTimer = sleep)
        assertEquals(true, model.uiState.player?.following?.canSkipNext)
        model.playerPlayPause()
        assertEquals(listOf(false), playback.plays.toList())
        // The previous song can't be asked for.
        model.playerSkip(next = false)
        assertTrue(playback.skips.isEmpty())
        model.playerSkip(next = true)
        assertEquals(listOf(true), playback.skips.toList())
        model.playerShuffle()
        assertEquals(listOf(true), playback.shuffled.toList())
        model.playerRepeat()
        assertEquals(listOf(Repeat.ALL), playback.repeated.toList())
        model.playerLike()
        assertEquals(1, playback.liked)
        model.playerPlayQueued(7)
        assertEquals(listOf(7L), playback.queuedPlayed.toList())
        model.playerSeek(4_000)
        assertEquals(listOf(4_000L), playback.seeks.toList())
        // Nothing queued to move, and no queue of Crosstune's to play on from.
        model.playerMoveQueued(0, 1)
        model.playerPlayLater(0)
        idle()
        model.playerSleep(10)
        assertTrue(model.uiState.sleep?.atMs != null)
        model.playerSleep(null)
        assertNull(model.uiState.sleep?.atMs)
        assertTrue(model.uiState.sleep != null)
        model.playerSleepOff()
        assertNull(model.uiState.sleep)
    }

    @Test
    fun aPlayerWhoseAppSaysNothingOfTheseButtonsLeavesThemBe() {
        val playback = FakePlayback()
        playback.playing.value = following(seek = false)
        val model = playingModel(playback)
        model.playerShuffle()
        model.playerRepeat()
        model.playerLike()
        model.playerSeek(3_000)
        model.playerSkip(next = true)
        assertTrue(playback.shuffled.isEmpty() && playback.repeated.isEmpty() && playback.liked == 0 && playback.seeks.isEmpty() && playback.skips.isEmpty())
        // A sleep timer needs none of that, and without one nothing is set.
        model.playerSleep(null)
        assertNull(model.uiState.sleep)
    }

    @Test
    fun aPlayerWhoseAppLetGoIsAskedToPlayTheSongAgain() {
        val playback = FakePlayback()
        val model = playingModel(playback)
        assertNull(model.uiState.player?.following)
        model.playerPlayPause()
        idle()
        // No app followed it, so it is asked of whichever plays.
        assertEquals(listOf<Pair<MusicMetadata, String?>>(song to null), playback.songsAsked.toList())
    }

    @Test
    fun theSongAppsMoveOnToIsTheNextOneOnceThePlayerPlayed() {
        val playback = FakePlayback()
        playback.playing.value = following()
        val model = playingModel(playback)
        val next = MusicMetadata("Calm", "Band")
        playback.playing.value = null
        playback.onThePhone = Heard.Song(next, null, app = "com.spotify")
        idle()
        assertEquals(next, model.uiState.player?.song)
    }

    private fun teachingCalls(model: MainViewModel) {
        model.explainLine(0)
        model.askAboutLine(0, "why")
        model.explainSong()
        model.askAboutSong("why")
        model.findWordsToKnow()
        model.aboutArtist()
        model.findExamples(0)
        model.lookUpWord(Word("a"), 0)
        model.toggleSavedLine(0)
        model.chooseLines(listOf(0), emptyList(), practise = true)
        model.practiseSong()
        model.toggleSavedWord(KeyWord("a", null, "b"))
        model.seekLyrics(1_000)
        model.syncLine(0)
        model.playPause()
        model.nudgeSync(100)
        model.resetSync()
        model.repeatLine(0)
        model.lyricsToPlayer()
        idle()
    }

    @Test
    fun theTeachingButtonsDoNothingWithoutWordsShownOrWithoutChatGpt() {
        val before = model().uiState.learning
        val chatGpt = ChatGpt(fake.client(), File(app.cacheDir, "chatgpt-vm.json"), "com.example.crosstune", Dispatchers.Unconfined)
        val tutor = ChatGptTutor(chatGpt, LookupCache(File(app.cacheDir, "answers-vm.json"), Dispatchers.Unconfined))
        for (made in listOf(model(), model(chatGpt = chatGpt, tutor = tutor))) {
            teachingCalls(made)
            assertEquals(before, made.uiState.learning)
            assertNull(made.uiState.lyricsFor)
            assertTrue(made.uiState.savedLines.isEmpty())
            assertNull(made.uiState.practice)
        }
        assertTrue(fake.requestedUrls.isEmpty())
    }

    private val timed = """[{"trackName":"Hush","artistName":"Band","duration":60,"syncedLyrics":"[00:00.00] One\n[00:10.00] Two\n[00:20.00] Three"}]"""

    /** The words of [song] shown over its player, timed as LRCLIB has them, followed in a music app that plays them. */
    private fun lyricsModel(
        playback: FakePlayback,
        answer: String? = timed,
        app: Following? = following(),
        keptOnDisk: LookupCache? = null,
        reports: ReportSender? = null,
        words: Boolean = true,
        listener: SongHearing = SongHearing { Heard.Nothing }
    ): MainViewModel {
        fake.handler = { request ->
            // No answer at all is a service that can't be reached.
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, answer ?: throw java.io.IOException("offline")) else FakeSpotify.html(request, "")
        }
        playback.playing.value = app
        // Answered on the thread that asks, so a lookup ahead is done before the words are, and none is
        // still on its way when a test counts what was asked.
        val finder = LyricsFinder(fake.inlineClient(), "test", Dispatchers.Unconfined, busyPauseMs = 0, matching = Dispatchers.Unconfined)
        val model = playingModel(playback, lyricsFinder = finder, keptOnDisk = keptOnDisk, reports = reports, listener = listener)
        model.playerLyrics()
        if (words) until("found the words") { model.uiState.lyrics.isNotEmpty() }
        else until("looked for the words") { model.uiState.lyricsFor != null && !model.uiState.isLoadingLyrics }
        return model
    }

    private fun lrclibAsked() = fake.requestedUrls.count { "lrclib.net" in it }

    /**
     * The Japanese words of [song] shown over its player with their readings, read by the dictionary on the phone alone, with
     * no UniDic on its way; UtaTen answers as [utaTen] does, and there's none when that's null.
     */
    private fun readingsModel(utaTen: ((okhttp3.Request) -> okhttp3.Response)?): MainViewModel {
        prefs().edit().putBoolean("lyrics_readings", true).commit()
        Readings.uniDic = null
        fake.handler = { request ->
            when (request.url.host) {
                "lrclib.net" -> FakeSpotify.html(request, """[{"trackName":"Hush","artistName":"Band","duration":60,"plainLyrics":"都会の夜"}]""")
                // UtaTen answers a moment after the dictionary's readings show, as it does on a phone, so a test waits for what it says.
                "utaten.com" -> {
                    Thread.sleep(UTATEN_MS)
                    utaTen?.invoke(request) ?: FakeSpotify.html(request, "")
                }
                else -> FakeSpotify.html(request, "")
            }
        }
        val playback = FakePlayback().apply { playing.value = following() }
        val finder = LyricsFinder(fake.client(), "test", Dispatchers.Unconfined, busyPauseMs = 0, matching = Dispatchers.Unconfined)
        val model = playingModel(playback, lyricsFinder = finder, sungReadings = utaTen?.let { SungReadings(fake.client(), ioDispatcher = Dispatchers.Unconfined) })
        model.playerLyrics()
        until("read the words") { model.uiState.learning.lines.isNotEmpty() }
        idle()
        return model
    }

    @Test
    fun wordsWithNothingToPutTheirReadingsRightAreDoneOnceRead() {
        // No UtaTen, no ChatGPT, and no UniDic to wait for: nothing's left to read them again for.
        val model = readingsModel(utaTen = null)
        assertEquals(listOf(Ruby("都会", "とかい"), Ruby("の"), Ruby("夜", "よる")), model.uiState.learning.lines.single().parts)
        assertFalse(model.uiState.learning.readingsUnfinished)
    }

    @Test
    fun wordsUtaTenCantBeAskedAboutAreLeftToBeReadAgain() {
        val model = readingsModel { throw java.io.IOException("offline") }
        until("the readings left to be read again") { model.uiState.learning.readingsUnfinished }
        assertTrue(fake.requestedUrls.any { "utaten.com" in it })
        assertEquals(listOf(Ruby("都会", "とかい"), Ruby("の"), Ruby("夜", "よる")), model.uiState.learning.lines.single().parts)
    }

    @Test
    fun wordsUtaTenSingsOtherwiseReadAsTheyreSungAndAreDone() {
        val ruby = { kanji: String, kana: String -> """<span class="ruby"><span class="rb">$kanji</span><span class="rt">$kana</span></span>""" }
        val model = readingsModel { request ->
            if (request.url.encodedPath == "/search") FakeSpotify.html(request, """<p class="searchResult__title"><a href="/lyric/ab0001/">Hush</a></p></td><td class="searchResult__artist"><p><a href="/artist/1/">Band</a>""")
            else FakeSpotify.html(request, """<div class="hiragana" >${ruby("都会", "まち")}の${ruby("夜", "よる")}<br /></div>""")
        }
        until("UtaTen's readings") { model.uiState.learning.lines.single().parts.first().by == Reader.SUNG }
        assertEquals(listOf(Ruby("都会", "まち", Reader.SUNG), Ruby("の"), Ruby("夜", "よる")), model.uiState.learning.lines.single().parts)
        assertFalse(model.uiState.learning.readingsUnfinished)
    }

    @Test
    fun aLateLyricsAnswerCannotReplaceTheNextSongsWords() = lateLyrics { model, playback ->
        val next = MusicMetadata("Moon", "Luna")
        playback.playing.value = null
        playback.onThePhone = Heard.Song(next, null, app = "com.spotify")
        until("the next song's words") { model.uiState.lyricsFor == next && model.uiState.lyrics.contains("Moon words") }
    }

    @Test
    fun aLateLyricsAnswerCannotReopenClosedWords() = lateLyrics { model, _ ->
        model.dismissLyrics()
        assertNull(model.uiState.lyricsFor)
    }

    private fun lateLyrics(leave: (MainViewModel, FakePlayback) -> Unit) {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.handler = { request ->
            if (request.url.host != "lrclib.net") FakeSpotify.html(request, "")
            else if (request.url.toString().contains("Hush")) {
                arrived.countDown()
                release.await(10, TimeUnit.SECONDS)
                FakeSpotify.html(request, timed)
            } else {
                FakeSpotify.html(request, """[{"trackName":"Moon","artistName":"Luna","duration":60,"plainLyrics":"Moon words"}]""")
            }
        }
        val playback = FakePlayback().apply { playing.value = following() }
        val finder = LyricsFinder(fake.client(), "test", Dispatchers.IO, busyPauseMs = 0)
        val model = playingModel(playback, lyricsFinder = finder, lookAhead = false)
        try {
            model.playerLyrics()
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            val field = MainViewModel::class.java.getDeclaredField("lyricsJob").apply { isAccessible = true }
            val old = field.get(model) as Job
            leave(model, playback)
            val expectedSong = model.uiState.lyricsFor
            val expectedWords = model.uiState.lyrics
            release.countDown()
            until("the old lookup finished") { old.isCompleted }
            idle()
            assertEquals(expectedSong, model.uiState.lyricsFor)
            assertEquals(expectedWords, model.uiState.lyrics)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun closingWordsStopsTheirAppFollowerBeforeItCanSetTheClockAgain() {
        val playback = FakePlayback()
        val model = lyricsModel(playback)
        val withWords = playback.playing.subscriptionCount.value
        assertNotNull(model.uiState.following)
        model.dismissLyrics()
        idle()
        assertEquals(withWords - 2, playback.playing.subscriptionCount.value)
        playback.playing.value = following(app = "com.tidal")
        idle()
        assertNull(model.uiState.following)
    }

    @Test
    fun thePlayerStopsFollowingThePreviousSongWhenTheAppGoesOn() {
        val playback = FakePlayback()
        playback.playing.value = following()
        val model = playingModel(playback)
        val before = playback.playing.subscriptionCount.value
        val next = MusicMetadata("Moon", "Luna")
        playback.playing.value = null
        playback.onThePhone = Heard.Song(next, null, app = "com.spotify")
        until("the next song in the player") { model.uiState.player?.song == next }
        idle()
        assertEquals(before, playback.playing.subscriptionCount.value)
    }

    @Test
    fun timedWordsFollowTheAppAndItsButtonsMoveIt() {
        val playback = FakePlayback()
        val model = lyricsModel(playback)
        assertEquals(listOf("One", "Two", "Three"), model.uiState.lyricLines.map { it.text })
        model.seekLyrics(10_000)
        assertEquals(listOf(10_000L), playback.seeks.toList())
        model.playPause()
        assertEquals(listOf(false), playback.plays.toList())
        // Another line sung now puts the words in time, and putting them back undoes it.
        model.syncLine(1)
        assertTrue(model.uiState.lyricsSynced)
        model.nudgeSync(500)
        assertTrue(model.uiState.lyricsSynced)
        model.resetSync()
        assertFalse(model.uiState.lyricsSynced)
        model.repeatLine(1)
        assertEquals(1, model.uiState.repeating)
        assertEquals(10_000L, playback.seeks.last())
        model.stopRepeating()
        assertNull(model.uiState.repeating)
        // Back to the player: the song's words go, and its player stays.
        model.lyricsToPlayer()
        assertNull(model.uiState.lyricsFor)
        assertEquals(song, model.uiState.player?.song)
    }

    @Test
    fun linesOfTheWordsAreKeptAndLetGo() {
        val playback = FakePlayback()
        val model = lyricsModel(playback)
        model.toggleSavedLine(1)
        assertEquals(listOf("Two"), model.uiState.savedLines.map { it.text })
        assertEquals(10_000L, model.uiState.savedLines.single().startMs)
        assertEquals(20_000L, model.uiState.savedLines.single().nextMs)
        assertEquals(60_000L, model.uiState.savedLines.single().lengthMs)
        model.toggleSavedLine(1)
        assertTrue(model.uiState.savedLines.isEmpty())
        // A line that isn't there is no line to keep.
        model.toggleSavedLine(9)
        model.chooseLines(listOf(2, 0), emptyList(), practise = false)
        assertEquals(listOf("One", "Three"), model.uiState.savedLines.map { it.text }.sorted())
        val one = model.uiState.savedLines.first { it.text == "One" }
        model.chooseLines(emptyList(), listOf(one), practise = false)
        assertEquals(listOf("Three"), model.uiState.savedLines.map { it.text })
        model.keepAgain(listOf(one))
        assertEquals(2, model.uiState.savedLines.size)
        model.toggleSavedWord(KeyWord("Two", null, "dos"))
        assertTrue(model.uiState.savedLines.any { it.text == "Two" && it.translation == "dos" })
        model.toggleSavedWord(KeyWord("Absent", null, "ausente"))
        assertTrue(model.uiState.savedLines.any { it.text == "Absent" })
        model.dismissLyrics()
        assertNull(model.uiState.lyricsFor)
    }

    @Test
    fun aSavedLineKeepsWhereTheNextLineStartsAndHowLongTheRecordingIs() {
        val fromWords = lyricsModel(FakePlayback(), app = following().copy(durationMs = 0))
        fromWords.toggleSavedLine(1)
        val two = fromWords.uiState.savedLines.single { it.text == "Two" }
        // The words are a minute long. The app saying 0 does not replace that.
        assertEquals(20_000L, two.nextMs)
        assertEquals(60_000L, two.lengthMs)
        fromWords.toggleSavedLine(2)
        val three = fromWords.uiState.savedLines.single { it.text == "Three" }
        assertNull(three.nextMs)
        assertEquals(20_000L + MainViewModel.LAST_LINE_MS, three.endMs)

        // No length on the words: the app's length is kept, and 0 is not one.
        // Each look starts with no line kept, so saving Two keeps it rather than letting the last one go.
        val none = """[{"trackName":"Hush","artistName":"Band","syncedLyrics":"[00:00.00] One\n[00:10.00] Two\n[00:20.00] Three"}]"""
        prefs().edit().remove("saved_lines").commit()
        val fromApp = lyricsModel(FakePlayback(), answer = none, app = following().copy(durationMs = 180_000))
        fromApp.toggleSavedLine(1)
        assertEquals(180_000L, fromApp.uiState.savedLines.single().lengthMs)
        prefs().edit().remove("saved_lines").commit()
        val zero = lyricsModel(FakePlayback(), answer = none, app = following().copy(durationMs = 0))
        zero.toggleSavedLine(1)
        assertNull(zero.uiState.savedLines.single().lengthMs)
        prefs().edit().remove("saved_lines").commit()
        val missing = lyricsModel(FakePlayback(), answer = none)
        missing.toggleSavedLine(1)
        assertNull(missing.uiState.savedLines.single().lengthMs)
        assertEquals(20_000L, missing.uiState.savedLines.single().nextMs)
    }

    @Test
    fun theNextLineOfASavedLineIsTheNextOneSungLater() {
        // Two lines with one time, as a line and its echo are: the next one starts later, not at once.
        val echo = """[{"trackName":"Hush","artistName":"Band","duration":60,"syncedLyrics":"[00:00.00] One\n[00:10.00] Two\n[00:10.00] Echo\n[00:20.00] Three"}]"""
        val model = lyricsModel(FakePlayback(), answer = echo)
        assertEquals(listOf(0L, 10_000L, 10_000L, 20_000L), model.uiState.lyricLines.map { it.timeMs })
        model.toggleSavedLine(1)
        val two = model.uiState.savedLines.single()
        assertEquals("Two", two.text)
        assertEquals(20_000L, two.nextMs)
        assertEquals(20_000L, two.endMs)
    }

    @Test
    fun aLineTimedAgainKeepsItsIntroOnlyWithNoAppPlayingIt() {
        // Kept from a video whose song starts 3 s in, before the words had times.
        val kept = SavedLine("Two", translation = null, title = "Hush", artist = "Band", leadMs = 3_000, app = "com.video")
        SavedLinesStore(prefs()).addAll(listOf(kept))
        val alone = lyricsModel(FakePlayback(), app = null)
        val timed = alone.uiState.savedLines.single()
        assertEquals(10_000L, timed.startMs)
        assertEquals(3_000L, timed.leadMs)
        assertEquals("com.video", timed.app)

        // The words are put in time by hand while a music app plays them. It has no intro, so
        // the line is the app's now, and starts where the words say.
        prefs().edit().remove("saved_lines").commit()
        SavedLinesStore(prefs()).addAll(listOf(kept))
        val inApp = lyricsModel(FakePlayback())
        assertEquals(3_000L, inApp.uiState.savedLines.single().leadMs)
        inApp.syncLine(1)
        val moved = inApp.uiState.savedLines.single()
        assertEquals(0L, moved.leadMs)
        assertEquals("com.spotify", moved.app)
    }

    @Test
    fun wordsWithoutTimingCantBeMovedOrRepeated() {
        val playback = FakePlayback()
        val model = lyricsModel(playback, answer = """[{"trackName":"Hush","artistName":"Band","duration":60,"plainLyrics":"Plain one\nPlain two"}]""")
        assertEquals("Plain one\nPlain two", model.uiState.lyrics)
        assertTrue(model.uiState.lyricLines.isEmpty())
        model.syncLine(0)
        model.nudgeSync(500)
        model.repeatLine(0)
        assertFalse(model.uiState.lyricsSynced)
        assertNull(model.uiState.repeating)
        model.toggleSavedLine(1)
        assertEquals(listOf("Plain two"), model.uiState.savedLines.map { it.text })
        assertNull(model.uiState.savedLines.single().startMs)
    }

    /** Idles until [done] holds, since the account is read and the browser answered on real threads. */
    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Never $what" }
            idle()
            Thread.sleep(10)
        }
    }

    private fun accountFile() = File(app.noBackupFilesDir, "chatgpt.json").apply { delete() }

    /** An account [openAi] answers for, signed in already when [signedIn]. */
    private fun account(openAi: FakeOpenAi, file: File, signedIn: Boolean): ChatGpt {
        fake.handler = { request -> openAi.answer(request) ?: FakeSpotify.html(request, "") }
        val account = ChatGpt(fake.client(), file, app.packageName)
        if (signedIn) {
            val result = kotlinx.coroutines.runBlocking {
                account.signIn(open = { url ->
                    openAi.authorize = url.toHttpUrl()
                    kotlin.concurrent.thread { openAi.comeBack() }
                })
            }
            assertEquals(ChatGptSignInResult.SIGNED_IN, result)
        }
        return account
    }

    @Test
    fun anAccountSignedInBeforeIsShownWithItsModelsAndTheOnePicked() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val account = account(openAi, accountFile(), signedIn = true)
        prefs().edit().putString("chatgpt_model", "gpt-6-terra").commit()
        val model = model(chatGpt = account)
        until("read the account") { model.uiState.chatGpt.models.isNotEmpty() }
        assertEquals(FakeOpenAi.EMAIL, model.uiState.chatGpt.email)
        assertEquals(listOf("gpt-6.1-sol", "gpt-6-terra"), model.uiState.chatGpt.models.map { it.slug })
        assertEquals("gpt-6-terra", account.picked)
        model.pickChatGptModel("gpt-6.1-sol")
        assertEquals("gpt-6.1-sol", model.uiState.chatGpt.model)
        assertEquals("gpt-6.1-sol", prefs().getString("chatgpt_model", null))
        assertEquals("gpt-6.1-sol", account.picked)
        model.signOutOfChatGpt()
        assertEquals(ChatGptState(), model.uiState.chatGpt)
        until("revoked") { fake.requestedUrls.any { it.startsWith(ChatGpt.REVOKE_URL) } }
    }

    @Test
    fun anAccountThatCantBeReadIsLeftSignedOut() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = model(chatGpt = account(openAi, accountFile(), signedIn = false))
        idle()
        Thread.sleep(200)
        idle()
        assertEquals(ChatGptState(), model.uiState.chatGpt)
    }

    @Test
    fun signingInWithChatGptOpensTheBrowserThenWelcomesOnceAndAsksTheModels() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = model(chatGpt = account(openAi, accountFile(), signedIn = false))
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        var done = 0
        model.signInWithChatGpt { done++ }
        assertTrue(model.uiState.chatGpt.signingIn)
        until("opened the browser") { effects.isNotEmpty() }
        val open = effects.single() as Effect.Open
        openAi.authorize = open.url.toHttpUrl()
        kotlin.concurrent.thread { openAi.comeBack() }
        until("signed in") { model.uiState.chatGpt.email != null && model.uiState.chatGpt.models.isNotEmpty() }
        assertEquals(FakeOpenAi.EMAIL, model.uiState.chatGpt.email)
        assertTrue(model.uiState.chatGpt.welcome)
        assertFalse(model.uiState.chatGpt.signingIn)
        assertEquals(1, done)
        model.dismissChatGptWelcome()
        assertFalse(model.uiState.chatGpt.welcome)
        assertTrue(prefs().getBoolean("chatgpt_welcomed", false))
    }

    @Test
    fun aSignInTheBrowserNeverAnswersEndsWhenCancelledAndOneThatReplacesItWinsTheEnd() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = model(chatGpt = account(openAi, accountFile(), signedIn = false))
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        var first = 0
        model.signInWithChatGpt { first++ }
        until("opened the browser") { effects.isNotEmpty() }
        model.cancelChatGptSignIn()
        assertFalse(model.uiState.chatGpt.signingIn)
        until("stopped") { first == 1 }
        // A second sign-in replacing a first one is the only one to say it's over.
        var second = 0
        var replaced = 0
        model.signInWithChatGpt { replaced++ }
        model.signInWithChatGpt { second++ }
        until("opened the browser again") { effects.size >= 2 }
        model.cancelChatGptSignIn()
        until("the second stopped") { second == 1 }
        assertEquals(0, replaced)
        assertEquals(ChatGptState(), model.uiState.chatGpt)
    }

    @Test
    fun aSignInTheBrowserRefusesShowsWhyAndSignsNobodyIn() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = model(chatGpt = account(openAi, accountFile(), signedIn = false))
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        model.signInWithChatGpt { }
        until("opened the browser") { effects.isNotEmpty() }
        openAi.authorize = (effects.single() as Effect.Open).url.toHttpUrl()
        kotlin.concurrent.thread { openAi.comeBack("error=access_denied&state=${openAi.authorize.queryParameter("state")}") }
        until("failed") { model.uiState.chatGpt.problem != null }
        assertNull(model.uiState.chatGpt.email)
        assertFalse(model.uiState.chatGpt.welcome)
        assertFalse(model.uiState.chatGpt.signingIn)
    }

    private val catalogue = mapOf("Hush" to "Band", "Moon" to "Luna", "Stars" to "Orbit")

    /** Deezer holding the [catalogue] songs a search names, Apple nothing, and ChatGPT the account's. */
    private fun searchableCatalogue(openAi: FakeOpenAi) {
        fake.handler = { request ->
            val asked = request.url.queryParameter("q").orEmpty()
            when {
                openAi.answer(request) != null -> openAi.answer(request)!!
                request.url.host == "api.deezer.com" -> {
                    val tracks = catalogue.filter { (title, _) -> title in asked }.entries.joinToString(",") { (title, artist) ->
                        """{"id":${title.length},"title":"$title","link":"https://www.deezer.com/track/${title.length}","artist":{"name":"$artist"},"album":{"cover_big":"https://cdn/$title.jpg"}}"""
                    }
                    FakeSpotify.html(request, """{"data":[$tracks]}""")
                }
                request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                else -> FakeSpotify.html(request, "")
            }
        }
    }

    /** A model signed in with ChatGPT, which [openAi] answers for, searching [catalogue]. */
    private fun searchingModel(openAi: FakeOpenAi): MainViewModel {
        val account = account(openAi, accountFile(), signedIn = true)
        searchableCatalogue(openAi)
        val tutor = ChatGptTutor(account, LookupCache(File.createTempFile("answers", ".json", app.cacheDir).apply { delete() }, Dispatchers.Unconfined))
        val model = model(chatGpt = account, tutor = tutor, songSearcher = SongSearcher(fake.client(), country = "AR", ioDispatcher = Dispatchers.Unconfined))
        until("read the account") { model.uiState.chatGpt.email != null }
        return model
    }

    @Test
    fun aSearchWithNothingToSearchByOrNothingSearchedIsAnInvalidLink() {
        val searching = model(songSearcher = SongSearcher(fake.client(), ioDispatcher = Dispatchers.Unconfined))
        for ((model, words) in listOf(model() to "Hush Band", searching to "   ", searching to "https://example.com/song")) {
            model.searchSongs(words)
            idle()
            assertEquals(AppError.INVALID_URL, model.uiState.error)
            assertTrue(model.uiState.songSearch.isEmpty())
            assertFalse(model.uiState.isSearchingSongs)
        }
    }

    @Test
    fun aSongSearchShowsWhatTheCatalogueHoldsAndChoosingOneResolvesItsOwnLink() {
        searchableCatalogue(FakeOpenAi(System::currentTimeMillis))
        val model = model(songSearcher = SongSearcher(fake.client(), country = "AR", ioDispatcher = Dispatchers.Unconfined))
        model.searchSongs("  Hush  ")
        until("found") { model.uiState.songSearch.isNotEmpty() }
        assertEquals("Hush", model.uiState.songSearchQuery)
        assertEquals(listOf("Hush"), model.uiState.songSearch.map { it.title })
        assertFalse(model.uiState.isAskingForSongs)
        assertEquals(0, model.uiState.songsFromChatGpt)
        model.dismissSongSearch()
        assertTrue(model.uiState.songSearch.isEmpty())
        assertEquals("", model.uiState.songSearchQuery)
        // A song with a link of its own is opened as that link, as if it had been pasted.
        model.chooseSong(MusicMetadata("Hush", "Band", url = "https://www.deezer.com/track/4"))
        assertEquals("https://www.deezer.com/track/4", model.uiState.linkText)
        // Its lookup runs on another thread and ends by clearing the box, which would clear the next song's name as well.
        until("looked the link up") { !model.uiState.isLoading }
        // One without is looked up by its name. Its cover is held, as the box empties once it's found.
        val coverHeld = CountDownLatch(1)
        val answer = fake.handler
        fake.handler = { request -> coverHeld.await(5, TimeUnit.SECONDS); answer(request) }
        model.chooseSong(MusicMetadata("Moon", "Luna"))
        assertEquals(MusicLinks.recognizedSong(MusicMetadata("Moon", "Luna")).text, model.uiState.linkText)
        model.openNamedSong("Stars", "Orbit")
        assertEquals(MusicLinks.recognizedSong(MusicMetadata("Stars", "Orbit")).text, model.uiState.linkText)
        coverHeld.countDown()
        until("looked the song up") { !model.uiState.isLoading }
    }

    @Test
    fun theNextSongHeardIsFollowedOnlyOnceItsWordsAreFound() {
        val ears = ScriptedEars(heardNow())
        val playback = FakePlayback()
        val moonHeld = CountDownLatch(1)
        fake.handler = { request ->
            when {
                request.url.host != "lrclib.net" -> FakeSpotify.html(request, "")
                "Moon" in request.url.toString() -> { moonHeld.await(10, TimeUnit.SECONDS); FakeSpotify.html(request, timed.replace("Hush", "Moon").replace("Band", "Luna")) }
                else -> FakeSpotify.html(request, timed)
            }
        }
        playback.playing.value = null
        // Looked up on a thread of its own, so the next song's words can be held while it's shown.
        val model = playingModel(playback, lyricsFinder = LyricsFinder(fake.client(), "test", Dispatchers.IO, busyPauseMs = 0), listener = ears)
        model.playerLyrics()
        until("found the words") { model.uiState.lyrics.isNotEmpty() }
        playback.onThePhone = null
        model.listenAlong()
        idle()
        assertNotNull(model.uiState.following)
        val next = MusicMetadata("Moon", "Luna")
        ears.queue.add(heardNow(of = next, at = 1_000, match = "m2"))
        pass(8_500)
        until("the next song shown") { model.uiState.lyricsFor == next }
        // Its place has no lines to light yet, so nothing follows it, rather than the last song's place or its own.
        assertNull(model.uiState.following)
        moonHeld.countDown()
        until("found its words") { model.uiState.lyrics.isNotEmpty() }
        until("followed") { model.uiState.following != null }
    }

    @Test
    fun aDescriptionIsAlsoAskedOfChatGptAndItsSongsLeadAsAPlaylist() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = searchingModel(openAi)
        openAi.reply = { _ ->
            200 to FakeOpenAi.stream("""[{"title":"Hush","artist":"Band"},{"title":"Moon","artist":"Luna"},{"title":"Made Up","artist":"Nobody"},{"title":"Stars","artist":"Orbit"},{"title":"hush","artist":"BAND"}]""")
        }
        // Too short a search to be a description: only the catalogue is asked.
        model.searchSongs("Hush")
        until("found") { model.uiState.songSearch.isNotEmpty() }
        assertTrue(openAi.asked.isEmpty())
        // Too few of ChatGPT's songs make no playlist.
        model.playlistFromSearch()
        assertNull(model.uiState.result)

        model.searchSongs("quiet songs about hush and moon")
        until("named") { model.uiState.songsFromChatGpt == 3 }
        assertEquals(listOf("Hush", "Moon", "Stars"), model.uiState.songSearch.map { it.title })
        assertFalse(model.uiState.isAskingForSongs)
        model.playlistFromSearch()
        val playlist = model.uiState.result!!
        assertEquals(ItemType.PLAYLIST, playlist.type)
        assertEquals("quiet songs about hush and moon", playlist.title)
        assertEquals(listOf("Hush", "Moon", "Stars"), playlist.tracks.map { it.title })
        assertEquals("https://cdn/Hush.jpg", playlist.artworkUrl)
        assertTrue(model.uiState.songSearch.isEmpty())
        assertFalse(model.uiState.songsLike)
    }

    @Test
    fun aDescriptionChatGptNamesNothingRealForLeavesTheCatalogueSongsAlone() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = searchingModel(openAi)
        openAi.reply = { _ -> 200 to FakeOpenAi.stream("""[{"title":"Made Up","artist":"Nobody"}]""") }
        model.searchSongs("a song named Hush maybe")
        until("asked") { openAi.asked.isNotEmpty() }
        until("answered") { !model.uiState.isAskingForSongs && model.uiState.songSearchQuery.isNotEmpty() }
        assertEquals(0, model.uiState.songsFromChatGpt)
        assertEquals(listOf("Hush"), model.uiState.songSearch.map { it.title })
    }

    @Test
    fun songsLikeOneAreFoundInTheCatalogueAndSayWhenNoneAre() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = searchingModel(openAi)
        openAi.reply = { _ -> 200 to FakeOpenAi.stream("""[{"title":"Moon","artist":"Luna"},{"title":"Made Up","artist":"Nobody"},{"title":"Stars","artist":"Orbit"},{"title":"Swapped","artist":"Moon"}]""") }
        model.songsLike(song)
        until("listed") { model.uiState.songsLike }
        assertFalse(model.uiState.isLoading)
        assertEquals(listOf("Moon", "Stars"), model.uiState.result?.tracks?.map { it.title })
        assertEquals("Hush", model.uiState.result?.title)
        // None of them in the catalogue is an error to read, not an empty list.
        openAi.reply = { _ -> 200 to FakeOpenAi.stream("""[{"title":"Made Up","artist":"Nobody"}]""") }
        model.songsLike(MusicMetadata("Other", "Band"))
        until("failed") { model.uiState.error == AppError.NO_SONGS_LIKE }
        assertFalse(model.uiState.isLoading)
        // Without ChatGPT or a catalogue there is nothing to ask.
        val before = model().uiState
        val bare = model()
        bare.songsLike(song)
        assertEquals(before, bare.uiState)
    }

    @Test
    fun wordsLeftAreKeptAndComeBackWithoutAskingAgain() {
        val model = lyricsModel(FakePlayback())
        val asked = lrclibAsked()
        assertTrue(asked > 0)
        model.keep()
        model.dismissLyrics()
        assertEquals("", model.uiState.lyrics)
        model.playerLyrics()
        idle()
        assertEquals("One\nTwo\nThree", model.uiState.lyrics)
        assertEquals(3, model.uiState.lyricLines.size)
        assertEquals(asked, lrclibAsked())
        assertFalse(model.uiState.isLoadingLyrics)
    }

    @Test
    fun wordsKeptOnDiskShowAtOnceInTheNextSession() {
        val disk = File(app.cacheDir, "kept-vm.json").apply { delete() }
        val first = lyricsModel(FakePlayback(), keptOnDisk = LookupCache(disk, Dispatchers.Unconfined))
        first.keep()
        first.dismissLyrics()
        idle()
        // Another session whose services have nothing shows the words it kept.
        val second = lyricsModel(FakePlayback(), answer = "[]", keptOnDisk = LookupCache(disk, Dispatchers.Unconfined))
        assertEquals("One\nTwo\nThree", second.uiState.lyrics)
    }

    @Test
    fun wordsNoServiceHasOfferToReportAndAreReportedOnceTheUserAllows() {
        val sender = ReportSender(fake.client(), "9.9.9", Dispatchers.Unconfined)
        val model = lyricsModel(FakePlayback(), answer = "[]", reports = sender, words = false)
        assertEquals("", model.uiState.lyrics)
        assertFalse(model.uiState.lyricsFailed)
        assertTrue(model.uiState.reportOffer)
        assertTrue(fake.requestedUrls.none { it.startsWith(ReportSender.URL) })
        model.allowAutoReports(true)
        assertFalse(model.uiState.reportOffer)
        assertTrue(model.uiState.autoReports == true)
        until("reported") { fake.requestedUrls.any { it.startsWith(ReportSender.URL) } }
        until("remembered") { AutoReports(prefs()).reported(song, SongProblem.NO_LYRICS) }
        // Reported once: finding the same again sends nothing more.
        model.dismissLyrics()
        val sent = fake.requestedUrls.count { it.startsWith(ReportSender.URL) }
        model.playerLyrics()
        until("looked again") { model.uiState.lyricsFor != null && !model.uiState.isLoadingLyrics }
        idle()
        assertEquals(sent, fake.requestedUrls.count { it.startsWith(ReportSender.URL) })
    }

    @Test
    fun wordsNoServiceHasAreNotOfferedAgainOnceTheUserRefused() {
        val model = lyricsModel(FakePlayback(), answer = "[]", reports = ReportSender(fake.client(), "9.9.9", Dispatchers.Unconfined), words = false)
        assertTrue(model.uiState.reportOffer)
        model.allowAutoReports(false)
        assertFalse(model.uiState.reportOffer)
        model.dismissLyrics()
        model.playerLyrics()
        until("looked again") { model.uiState.lyricsFor != null && !model.uiState.isLoadingLyrics }
        assertFalse(model.uiState.reportOffer)
        assertTrue(fake.requestedUrls.none { it.startsWith(ReportSender.URL) })
    }

    @Test
    fun wordsThatCantBeLookedUpSayItAndRetryAsksAgain() {
        val playback = FakePlayback()
        val model = lyricsModel(playback, answer = null, words = false)
        assertTrue(model.uiState.lyricsFailed)
        assertEquals("", model.uiState.lyrics)
        fake.handler = { request -> if (request.url.host == "lrclib.net") FakeSpotify.html(request, timed) else FakeSpotify.html(request, "") }
        model.showLyrics()
        until("found the words") { model.uiState.lyrics.isNotEmpty() }
        assertFalse(model.uiState.lyricsFailed)
    }

    @Test
    fun aReportIsSentWithTheCommentAndSaysHowItWent() {
        val model = lyricsModel(FakePlayback(), reports = ReportSender(fake.client(), "9.9.9", Dispatchers.Unconfined))
        model.sendReport("Out of time")
        until("sent") { model.uiState.report == ReportStatus.SENT }
        assertTrue(fake.requestedUrls.any { it.startsWith(ReportSender.URL) })
        model.closeReport()
        assertNull(model.uiState.report)
        val none = model()
        none.sendReport("Nothing to send with")
        assertNull(none.uiState.report)
    }

    /** Hears one thing after another, then nothing. */
    private class ScriptedEars(vararg heard: Heard) : SongHearing {
        val queue = ArrayDeque(heard.toList())
        var asked = 0
        override suspend fun listen(): Heard {
            asked++
            return queue.removeFirstOrNull() ?: Heard.Nothing
        }
    }

    private fun heardNow(of: MusicMetadata = song, at: Long = 12_000, match: String? = "m1", appleMusicId: String? = null) =
        Heard.Song(of, appleMusicId, offsetMs = at, startedAtMs = android.os.SystemClock.elapsedRealtime(), matchId = match)

    @Test
    fun wordsListenedAlongToFollowTheSongHeardItsSilenceItsSkipsAndTheNextSong() {
        val ears = ScriptedEars(heardNow())
        val playback = FakePlayback()
        val model = lyricsModel(playback, app = null, listener = ears)
        // No app says what plays, so only the microphone does.
        playback.onThePhone = null
        model.listenAlong()
        // A second ask while it listens does nothing more.
        model.listenAlong()
        assertTrue(model.uiState.listeningAlong)
        idle()
        assertEquals(1, ears.asked)
        val first = model.uiState.following!!
        assertNull(first.app)
        assertTrue(first.clock.playing)
        assertTrue(first.clock.positionAt(android.os.SystemClock.elapsedRealtime()) >= 12_000)
        // Heard nothing: the music most likely stopped, so the words stop where they are.
        pass(8_500)
        assertEquals(2, ears.asked)
        assertFalse(model.uiState.following!!.clock.playing)
        // Heard again, far from where the words are, it's where the song is now.
        ears.queue.add(heardNow(at = 50_000))
        pass(8_500)
        assertTrue(model.uiState.following!!.clock.playing)
        assertTrue(model.uiState.following!!.clock.positionAt(android.os.SystemClock.elapsedRealtime()) >= 50_000)
        // Heard a moment off, nothing moves.
        val placed = model.uiState.following!!.clock
        ears.queue.add(heardNow(at = 50_000 + 8_500 + 700))
        pass(8_500)
        assertEquals(placed, model.uiState.following!!.clock)
        // The next song takes over the words, and joins Recent.
        val next = MusicMetadata("Moon", "Luna")
        ears.queue.add(heardNow(of = next, at = 1_000, match = "m2", appleMusicId = "1234"))
        pass(8_500)
        repeat(6) { if (model.uiState.lyricsFor != next) pass(2_000) }
        assertEquals("asked ${ears.asked}, left ${ears.queue.size}, ${model.uiState.error}, history ${model.uiState.history.map { it.metadata.title }}, loading ${model.uiState.isLoadingLyrics}, following ${model.uiState.following}", next, model.uiState.lyricsFor)
        assertEquals(next, model.uiState.history.first().metadata)
        // The network failing is tried again, the microphone going is the end of it.
        val asked = ears.asked
        ears.queue.add(Heard.Failed(AppError.NETWORK))
        pass(8_500)
        pass(16_000)
        assertTrue(ears.asked > asked + 1)
        assertTrue(model.uiState.listeningAlong)
        ears.queue.add(Heard.Failed(AppError.MICROPHONE))
        pass(8_500)
        assertEquals(AppError.MICROPHONE, model.uiState.error)
        assertFalse(model.uiState.canRetry)
        assertFalse(model.uiState.listeningAlong)
    }

    /** The music app at [ms] into the song now. */
    private fun appAt(playback: FakePlayback, ms: Long) {
        playback.playing.value = following().copy(clock = PlaybackClock(ms, android.os.SystemClock.elapsedRealtime()))
        idle()
    }

    @Test
    fun aRecordingHeardPlayingFasterThanTheWordsAreTimedForGoesOnAtItsPace() {
        val playback = FakePlayback()
        val words = """[{"trackName":"Hush","artistName":"Band","duration":200,"syncedLyrics":"[00:10.00] One\n[00:20.00] Two\n[01:50.00] Three"}]"""
        // Shazam hears it 3.5% faster than the recording it matches, as Bizarre Love Triangle's upload.
        val faster = Heard.Song(song, null, offsetMs = 1_000, startedAtMs = android.os.SystemClock.elapsedRealtime(), matchId = "m1", speed = 1.035f)
        val ears = ScriptedEars(faster)
        val model = lyricsModel(playback, answer = words, listener = ears)
        model.listenAlong()
        idle()
        assertEquals(1, ears.asked)
        model.stopListeningAlong()
        // Kept at once for the next time the song plays, with the words.
        val found = listOf(LyricLine(10_000, "One"), LyricLine(20_000, "Two"), LyricLine(110_000, "Three"))
        assertEquals(1 / 1.035, LyricsSyncStore(prefs()).pace(song, found), 0.0001)
        // Two lines put in time, too close together to tell a pace of their own: "Three", 90 s of the words on, comes 3 s sooner.
        appAt(playback, 12_000)
        model.syncLine(0)
        appAt(playback, 22_000)
        model.syncLine(1)
        fun gap(of: MainViewModel = model) = of.uiState.lyricLines.let { it[2].timeMs - it[1].timeMs }
        assertEquals((90_000 / 1.035).toLong().toDouble(), gap().toDouble(), 1.0)
        // Played again, it goes on at that pace from the start.
        val again = lyricsModel(playback, answer = words)
        assertEquals((90_000 / 1.035).toLong().toDouble(), gap(again).toDouble(), 1.0)
        // Heard just as much off as Shazam tells, it's off all the same; a hair less, it goes on as timed.
        ears.queue.add(faster.copy(speed = 1f + MainViewModel.MIN_SKEW))
        model.listenAlong()
        idle()
        model.stopListeningAlong()
        assertEquals(90_000 / (1.0 + MainViewModel.MIN_SKEW), gap().toDouble(), 1.0)
        ears.queue.add(faster.copy(speed = Math.nextDown(1f + MainViewModel.MIN_SKEW)))
        model.listenAlong()
        idle()
        model.stopListeningAlong()
        assertEquals(90_000L, gap())
        // Heard as another song, or with no place in it, Shazam's speed says nothing of this one.
        ears.queue.add(faster.copy(metadata = MusicMetadata("Moon", "Luna")))
        ears.queue.add(faster.copy(offsetMs = null))
        model.listenAlong()
        pass(20_000)
        model.stopListeningAlong()
        assertEquals(90_000L, model.uiState.lyricLines.let { it[2].timeMs - it[1].timeMs })
    }

    @Test
    fun wordsTimedForARecordingAsLongAsTheOnePlayingKeepItsPace() {
        val playback = FakePlayback()
        // The app says it's as long as the recording the words are timed for: they're timed for it, sped up or not.
        val words = """[{"trackName":"Hush","artistName":"Band","duration":200,"syncedLyrics":"[00:10.00] One\n[00:20.00] Two\n[01:50.00] Three"}]"""
        val ears = ScriptedEars(Heard.Song(song, null, offsetMs = 1_000, startedAtMs = android.os.SystemClock.elapsedRealtime(), matchId = "m1", speed = 1.035f))
        val model = lyricsModel(playback, answer = words, app = following().copy(durationMs = 200_000), listener = ears)
        model.listenAlong()
        idle()
        assertEquals(1, ears.asked)
        model.stopListeningAlong()
        playback.playing.value = following().copy(clock = PlaybackClock(12_000, android.os.SystemClock.elapsedRealtime()), durationMs = 200_000)
        idle()
        model.syncLine(0)
        playback.playing.value = following().copy(clock = PlaybackClock(22_000, android.os.SystemClock.elapsedRealtime()), durationMs = 200_000)
        idle()
        model.syncLine(1)
        assertEquals(90_000L, model.uiState.lyricLines.let { it[2].timeMs - it[1].timeMs })
    }

    @Test
    fun stepsToFixGoOneByOneAndComeBackToTheListOnlyIfTheyCameFromIt() {
        val model = model()
        // With nothing being fixed there is nothing to go on or back from.
        model.nextFix()
        model.fixBack()
        assertNull(model.uiState.fixes)
        model.fixSteps(listOf(Need.Links, Need.Follow))
        assertEquals(Need.Links, model.uiState.fixes?.need)
        model.nextFix()
        assertEquals(Need.Follow, model.uiState.fixes?.need)
        // After the last step, out: they didn't come from the list.
        model.nextFix()
        assertNull(model.uiState.fixes)
        model.fixSteps(listOf(Need.Links))
        model.fixBack()
        assertNull(model.uiState.fixes)
        // From the list, they come back to it.
        model.openFixes()
        model.fixSteps(listOf(Need.Links, Need.Follow))
        assertEquals(Fixes(list = true, steps = listOf(Need.Links, Need.Follow)), model.uiState.fixes)
        model.nextFix()
        model.nextFix()
        assertEquals(Fixes(list = true), model.uiState.fixes)
        model.fixSteps(listOf(Need.Float))
        model.fixBack()
        assertEquals(Fixes(list = true), model.uiState.fixes)
        model.closeFixes()
        assertNull(model.uiState.fixes)
    }

    @Test
    fun thePlayersAppOpensInFrontFromTheAppItFollowsOrElseTheOneItWasOpenedWith() {
        val playback = FakePlayback()
        playback.playing.value = following(app = "com.tidal")
        val model = playingModel(playback)
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        until("following the app") { model.uiState.player?.following?.appPackage == "com.tidal" }
        model.openPlayerApp()
        idle()
        assertEquals(listOf<Effect>(Effect.Show("com.tidal", finishAfterOpen = false, saysWhenItFails = false)), effects)
        // The app let go: the one the song plays in is still the one to open.
        playback.playing.value = null
        until("the app let go") { model.uiState.player?.following == null }
        val opened = model.uiState.player?.app
        assertTrue(opened != null)
        model.openPlayerApp()
        idle()
        assertEquals(Effect.Show(opened!!, finishAfterOpen = false, saysWhenItFails = false), effects.last())
        assertEquals(2, effects.size)
    }

    @Test
    fun theReadingSettingsAreKeptAndShownAtOnce() {
        val model = model()
        model.setReadings(true)
        model.setRomanized(true)
        model.setTranslation(true)
        model.setRomajiTyped(true)
        model.setKatakanaAsHiragana(true)
        model.setWordsCentered(true)
        model.setWordsAs(WordsAs.KANA)
        model.setChatGptTranslation(false)
        idle()
        val learning = model.uiState.learning
        assertTrue(learning.readings && learning.romanized && learning.translation && learning.romajiTyped && learning.katakanaAsHiragana)
        assertEquals(true, learning.centered)
        assertEquals(WordsAs.KANA, learning.wordsAs)
        assertFalse(learning.chatGptTranslation)
        val kept = prefs()
        assertTrue(kept.getBoolean("lyrics_readings", false) && kept.getBoolean("lyrics_romanized", false) && kept.getBoolean("lyrics_translation", false))
        assertTrue(kept.getBoolean("lyrics_romaji_typed", false) && kept.getBoolean("words_centered", false))
        assertEquals("KANA", kept.getString("lyrics_words_as", null))
        assertTrue(kept.contains("translation_chatgpt") && !kept.getBoolean("translation_chatgpt", true))
        model.retryTranslation()
        assertFalse(model.uiState.learning.translationFailed)
        // A second model starts the way the first was left.
        assertEquals(learning.wordsAs, model().uiState.learning.wordsAs)
        assertTrue(model().uiState.learning.katakanaAsHiragana)
    }

    @Test
    fun theSizesAreKeptOnlyOnceLetGo() {
        val model = model()
        model.setReadingScale(0.7f, save = false)
        model.setWordsScale(1.4f, save = false)
        model.setTranslationScale(0.8f, save = false)
        assertEquals(listOf(0.7f, 1.4f, 0.8f), model.uiState.learning.let { listOf(it.readingScale, it.wordsScale, it.translationScale) })
        assertFalse(prefs().contains("reading_scale") || prefs().contains("words_scale") || prefs().contains("translation_scale"))
        model.setReadingScale(0.6f, save = true)
        model.setWordsScale(1.5f, save = true)
        model.setTranslationScale(0.9f, save = true)
        assertEquals(listOf(0.6f, 1.5f, 0.9f), listOf("reading_scale", "words_scale", "translation_scale").map { prefs().getFloat(it, 0f) })
    }

    @Test
    fun theLanguagesAreKeptOnlyIfTheAppKnowsThemAndTranslatingAgainStartsAfresh() {
        val model = model()
        model.setUntranslated(setOf("es", "ja", "xx"))
        assertEquals(setOf("es", "ja"), model.uiState.learning.untranslated)
        assertEquals(setOf("es", "ja"), prefs().getStringSet("untranslated_languages", null))
        model.setTranslateInto("es-AR")
        assertEquals("es-AR", model.uiState.learning.into)
        assertEquals("es-AR", prefs().getString("translate_into", null))
        // One it doesn't translate into is the app's own language.
        model.setTranslateInto("xx")
        assertNull(model.uiState.learning.into)
        assertFalse(prefs().contains("translate_into"))
        model.setTranslateInto("de")
        model.setTranslateInto(null)
        assertNull(model.uiState.learning.into)
        assertFalse(prefs().contains("translate_into"))
    }

    @Test
    fun cardKindsAreKeptOnlyWhenAskedAndOneStaysOn() {
        val model = model()
        val kinds = model.uiState.cardTypes
        model.setCardTypes(kinds.map { it.copy(on = false) }, save = false)
        assertEquals(kinds.mapIndexed { index, _ -> index == 0 }, model.uiState.cardTypes.map { it.on })
        assertFalse(prefs().contains("card_types"))
        val second = kinds.mapIndexed { index, type -> type.copy(on = index == 1) }
        model.setCardTypes(second, save = true)
        assertEquals(second, model.uiState.cardTypes)
        assertTrue(prefs().contains("card_types"))
        assertEquals(second.map { it.on }, loadCardTypes(prefs()).map { it.on })
    }

    @Test
    fun chordsSwitchedOnAndOffAreKeptAndStopLookingThemUp() {
        val model = model()
        model.setChords(true)
        assertTrue(model.uiState.chords.on)
        assertTrue(prefs().getBoolean("lyrics_chords", false))
        model.setChords(false)
        assertFalse(model.uiState.chords.on || model.uiState.chords.loading)
        assertTrue(prefs().contains("lyrics_chords") && !prefs().getBoolean("lyrics_chords", true))
    }

    @Test
    fun textThatIsNoMusicLinkIsRejectedAndAServiceLinkItCantConvertGoesStraightToItsApp() {
        val model = model()
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        for (text in listOf("hello there", null, "https://example.com/page")) {
            model.onUrlChange("")
            model.resolveIncoming(text)
            idle()
            assertEquals(text.toString(), AppError.INVALID_URL, model.uiState.error)
            assertNull(model.uiState.result)
        }
        assertTrue(effects.isEmpty())
        model.resolveIncoming("see https://soundcloud.com/discover.")
        idle()
        val open = effects.single() as Effect.Open
        assertEquals(Triple("https://soundcloud.com/discover", "com.soundcloud.android", true), Triple(open.url, open.packageName, open.finishAfterOpen))
    }

    @Test
    fun theWordsStayForALinkOnlyHandedOnWhileTheyFloatAndMakeWayForOneShown() {
        // Links go straight to YouTube Music.
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false).commit()
        val model = lyricsModel(FakePlayback())
        val song = model.uiState.lyricsFor
        assertNotNull(song)
        // A page Crosstune hands on as it is, and a song it looks up first.
        model.resolveIncoming("see https://soundcloud.com/discover.", keepWords = true)
        idle()
        assertEquals(song, model.uiState.lyricsFor)
        assertTrue(model.uiState.handingOff)
        model.resolveIncoming("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC", keepWords = true)
        idle()
        assertEquals(song, model.uiState.lyricsFor)
        // Handed on, it's done with, so Crosstune opened shows the words.
        model.handedOn()
        assertFalse(model.uiState.handingOff)
        assertFalse(model.uiState.handlingIncomingLink)
        // A link to show here is what's shown, floating or not.
        model.resolveIncoming("see https://soundcloud.com/discover.", show = true, keepWords = true)
        idle()
        assertNull(model.uiState.lyricsFor)
    }

    @Test
    fun anEmptyClipboardSaysSoWhetherItIsPastedOrCameFromTheTile() {
        val model = model()
        for (text in listOf(null, "", "  ")) {
            model.onUrlChange("")
            model.pasteLink(text)
            assertEquals(AppError.CLIPBOARD_EMPTY, model.uiState.error)
            model.onUrlChange("")
            assertNull(model.uiState.error)
            model.resolveClipboard(text, null, show = false, after = AfterLookup.OPEN)
            assertEquals(AppError.CLIPBOARD_EMPTY, model.uiState.error)
        }
        assertEquals("", model.uiState.linkText)
        assertTrue(model.readyForCopy)
        model.onUrlChange("https://open.spotify.com/track/abc")
        assertFalse(model.readyForCopy)
    }

    private val ytMusic = MusicService.YOUTUBE_MUSIC.packageName
    private val ytLink = "https://music.youtube.com/watch?v=sPmul8b17AU"

    /** A model with YouTube Music on the phone and set as where songs go, and [ytLink] looked up, shown as the result. */
    private fun ytMusicModel(playback: PlaybackSource, phone: Phone = Phone.OTHER, oneTap: OneTapSetup? = null): MainViewModel {
        shadowOf(app.packageManager).installPackage(installedApp(ytMusic, "YouTube Music"))
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""") }
        val model = model(playback = playback, phone = phone, oneTap = oneTap)
        model.refreshSystemState()
        idle()
        model.onUrlChange(ytLink)
        model.resolveTypedInput()
        until("looked the song up") { model.uiState.result != null && !model.uiState.isLoading }
        return model
    }

    private fun appPlays(playback: FakePlayback, playing: Boolean = true) {
        playback.playing.value = Following(PlaybackClock(0, android.os.SystemClock.elapsedRealtime(), playing = playing), "Music", canSeek = true, appPackage = ytMusic)
    }

    /** Holds the app's answer while an open is waiting to see if the song is already playing. */
    private fun pendingOpen(action: (MainViewModel, MutableSharedFlow<Following?>, List<Effect>, FakePlayback) -> Unit) {
        val playback = FakePlayback().apply { access = true }
        val answers = MutableSharedFlow<Following?>(extraBufferCapacity = 1)
        val source = object : PlaybackSource by playback {
            override fun follow(song: MusicMetadata, app: String?): Flow<Following?> = answers
        }
        val model = ytMusicModel(source)
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        model.openResult()
        until("the first open waiting on the app") { answers.subscriptionCount.value == 1 }
        action(model, answers, effects, playback)
    }

    @Test
    fun openingAgainCancelsTheOpenAlreadyWaitingOnTheApp() = pendingOpen { model, answers, effects, _ ->
        model.openResult()
        idle()
        until("only the new open waiting") { answers.subscriptionCount.value == 1 }
        assertTrue(answers.tryEmit(null))
        until("opened once") { effects.isNotEmpty() }
        assertEquals(1, effects.size)
    }

    @Test
    fun playingCancelsTheOpenAlreadyWaitingOnTheApp() = pendingOpen { model, answers, effects, playback ->
        model.playResult()
        idle()
        // The player and the play request now follow the app; the old open no longer does.
        assertEquals(2, answers.subscriptionCount.value)
        assertTrue(answers.tryEmit(null))
        until("the app asked to play") { playback.songsAsked.isNotEmpty() }
        assertEquals(1, playback.songsAsked.size)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun openingTheOriginalCancelsTheOpenAlreadyWaitingOnTheApp() = pendingOpen { model, answers, effects, _ ->
        model.openOriginal()
        until("the old open stopped") { answers.subscriptionCount.value == 0 }
        assertEquals(listOf(ytLink), effects.filterIsInstance<Effect.Open>().map { it.url })
    }

    @Test
    fun cancellingAHandoffStopsTheOpenAlreadyWaitingOnTheApp() = pendingOpen { model, answers, effects, _ ->
        model.cancelHandoff()
        until("the old open stopped") { answers.subscriptionCount.value == 0 }
        assertTrue(effects.isEmpty())
    }

    @Test
    fun aSongAskedOfItsAppPlaysInThePlayerAndOnlyOpensTheAppWhenItWouldNot() {
        val playback = FakePlayback().apply { access = true }
        val model = ytMusicModel(playback)
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        assertTrue(model.uiState.canPlayResult)
        // Asked, and seen playing: Crosstune stays, in its player.
        model.playResult()
        idle()
        assertEquals(ytLink, playback.linksAsked.single())
        assertEquals("Dakare Ni Kita Onna", model.uiState.player?.song?.title)
        assertTrue(model.uiState.playingResult)
        appPlays(playback)
        until("done playing it") { !model.uiState.playingResult }
        assertTrue(effects.isEmpty())
        // Already in its app, paused: it carries on, not asked again.
        model.closePlayer()
        appPlays(playback, playing = false)
        model.playResult()
        until("carried on") { playback.plays.isNotEmpty() }
        assertEquals(listOf(true), playback.plays.toList())
        assertEquals(1, playback.songsAsked.size)
        // Asked, never playing: opened on its link, left to come back.
        model.closePlayer()
        playback.playing.value = null
        model.playResult()
        idle()
        pass(MainViewModel.LOAD_SONG_MS + 1_000)
        until("opened there") { playback.opened.isNotEmpty() }
        assertEquals(ytMusic to ytLink, playback.opened.single())
        // And it waits again for the app to play it there, before leaving it.
        pass(MainViewModel.LOAD_SONG_MS + 1_000)
        until("came back") { playback.cameBack == 1 }
        // Its app gone, there is no player: the app is opened by the system.
        model.closePlayer()
        playback.opens = false
        model.playResult()
        pass(MainViewModel.LOAD_SONG_MS + 1_000)
        until("opened by the system") { effects.isNotEmpty() }
        assertNull(model.uiState.player)
        assertTrue(effects.single() is Effect.Open)
    }

    @Test
    fun aSongItsAppCantBeAskedForIsOpenedThereAndWithNoAccessIsTakenAsPlayed() {
        val playback = FakePlayback().apply { access = false; canPlaySongs = false }
        val model = ytMusicModel(playback)
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        model.playResult()
        until("opened by the system") { effects.isNotEmpty() }
        assertTrue(playback.songsAsked.isEmpty())
        assertNull(model.uiState.player)
        // With no way to see the app play, being asked is enough.
        playback.canPlaySongs = true
        model.playResult()
        until("asked") { playback.songsAsked.isNotEmpty() }
        until("done") { !model.uiState.playingResult }
        assertEquals(1, effects.size)
        assertEquals("Dakare Ni Kita Onna", model.uiState.player?.song?.title)
    }

    @Test
    fun onATvPlayingWaitsForTheWordsToBeAllowedOverTheApp() {
        val playback = FakePlayback().apply { access = true }
        val model = ytMusicModel(playback, phone = Phone.TV)
        model.playResult()
        idle()
        assertEquals(Need.Float, model.uiState.fixes?.need)
        assertTrue(playback.songsAsked.isEmpty())
        model.closeFixes()
        model.setCanFloatOverApps(true)
        model.playResult()
        until("asked") { playback.songsAsked.isNotEmpty() }
        // Nothing to play with no song, or from a destination that isn't an app.
        val bare = model()
        bare.playResult()
        idle()
        assertNull(bare.uiState.player)
    }

    @Test
    fun thePlayersUpNextIsMovedAmongTheAppsSongsAndTheListsOwnApartWhereTheyCantMeet() {
        val playback = FakePlayback()
        val order = QueueOrder(playback, MainScope())
        playback.playing.value = following(upNext = listOf(Queued("A", "x", 1), Queued("B", "x", 2), Queued("L1", "y"), Queued("L2", "y")))
        val model = playingModel(playback, queueOrder = order)
        val upNext = { model.uiState.player?.following?.upNext?.map { it.title } }
        until("following") { upNext() != null }
        // One of the app's onto one of the list's, or either one out of the queue, is no move.
        model.playerMoveQueued(0, 2)
        model.playerMoveQueued(2, 0)
        model.playerMoveQueued(9, 0)
        model.playerMoveQueued(0, 9)
        // The list's own are the list's to move, and there is none here.
        model.playerMoveQueued(2, 3)
        model.playerPlayLater(2)
        idle()
        assertEquals(listOf("A", "B", "L1", "L2"), upNext())
        assertTrue(model.uiState.player?.played == true)
        // The app's are moved by the order Crosstune keeps over theirs.
        model.playerMoveQueued(0, 1)
        until("moved") { upNext() == listOf("B", "A", "L1", "L2") }
        order.off()
    }

    @Test
    fun aListCanPlayFromItsFirstWaitingSongWhenTheAppIsNoLongerFollowed() {
        val playback = FakePlayback()
        val ytMusic = MusicService.YOUTUBE_MUSIC.packageName
        val tracks = listOf(song, MusicMetadata("List 1", "Band"), MusicMetadata("List 2", "Band"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = PlayQueue(
            playback,
            { _, songs -> YouTubeQueue("https://music.youtube.com/watch?v=${songs.first().title}&list=TL", songs) },
            { _, _ -> null },
            File(app.noBackupFilesDir, "play_queue_unfollowed.json"),
            scope,
            Dispatchers.Unconfined
        )
        try {
            runBlocking { queue.play(tracks, MusicService.YOUTUBE_MUSIC, ytMusic, 1, tracks.take(1)) }
            val model = playingModel(playback, playQueue = queue)
            assertNull(model.uiState.player?.following)
            model.playerPlayLater(0)
            until("the first waiting song asked for") { playback.listsAsked.isNotEmpty() }
            assertEquals("https://music.youtube.com/watch?v=List 1&list=TL", playback.listsAsked.single().second)
        } finally {
            queue.stop()
            scope.cancel()
        }
    }

    @Test
    fun aListsSongsUpNextAreMovedAndPlayedFromByTheirPlaceAmongTheAppsOwnAheadOfThem() {
        val playback = FakePlayback()
        val ytMusic = MusicService.YOUTUBE_MUSIC.packageName
        // The playing song, two the app has queued from this part, then six still to ask for.
        val tracks = listOf(song) + (1..2).map { MusicMetadata("App $it", "Band") } + (1..6).map { MusicMetadata("List $it", "Band") }
        val queue = PlayQueue(
            playback,
            { _, songs -> YouTubeQueue("https://music.youtube.com/watch?v=${songs.first().title}&list=TL", songs) },
            { _, _ -> null },
            File(app.noBackupFilesDir, "play_queue_model.json"),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            Dispatchers.Unconfined
        )
        runBlocking { queue.play(tracks, MusicService.YOUTUBE_MUSIC, ytMusic, 3, tracks.take(3), "Road Trip") }
        playback.playing.value = following(app = ytMusic, upNext = listOf(Queued("App 1", "Band", 1), Queued("App 2", "Band", 2)))
        val model = playingModel(playback, playQueue = queue)
        val upNext = { model.uiState.player?.following?.upNext?.map { it.title } }
        val all = listOf("App 1", "App 2", "List 1", "List 2", "List 3", "List 4", "List 5", "List 6")
        until("following") { upNext() == all }
        // One of the app's onto one of the list's, or the other way, is no move.
        model.playerMoveQueued(0, 2)
        model.playerMoveQueued(2, 0)
        idle()
        assertEquals(all, upNext())
        // The list's own move by their place among the list's, past the app's two ahead of them.
        model.playerMoveQueued(3, 4)
        until("moved") { upNext() == listOf("App 1", "App 2", "List 1", "List 3", "List 2", "List 4", "List 5", "List 6") }
        // Tapped, the list plays on from there, as the app is asked for it.
        model.playerPlayLater(5)
        until("asked") { playback.listsAsked.isNotEmpty() }
        assertEquals("https://music.youtube.com/watch?v=List 4&list=TL", playback.listsAsked.single().second)
        assertTrue(model.uiState.player?.played == true)
        queue.stop()
    }

    @Test
    fun aPlayerTheAppLetGoIsAskedAgainAndOnATvOpenedCoveredWhenItWontPlayInTheBackground() {
        val playback = FakePlayback().apply { access = true }
        val model = ytMusicModel(playback, phone = Phone.TV)
        model.setCanFloatOverApps(true)
        model.playResult()
        until("asked") { playback.songsAsked.isNotEmpty() }
        // Still being asked for, a second ask would start it over.
        model.playerPlayPause()
        idle()
        assertEquals(1, playback.songsAsked.size)
        appPlays(playback)
        until("done") { !model.uiState.playingResult }
        // The app lets go; asked again, it won't, and on a TV it is opened on the song, covered.
        playback.playing.value = null
        until("let go") { model.uiState.player?.following == null }
        playback.canPlaySongs = false
        model.playerPlayPause()
        until("opened on it") { playback.opened.isNotEmpty() }
        assertEquals(ytMusic to ytLink, playback.opened.single())
    }

    @Test
    fun aSongOfAListOpensAsItsOwnLinkOrAsTheVideoOfTheOtherYouTubeOrIsLookedUpAndSaysWhenMissing() {
        val playback = FakePlayback().apply { access = true }
        val model = ytMusicModel(playback)
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        model.openTrack(MusicMetadata("A", "x", url = ytLink))
        until("opened") { effects.size == 1 }
        assertTrue((effects[0] as Effect.Open).url.contains("sPmul8b17AU") && (effects[0] as Effect.Open).packageName == ytMusic)
        model.openTrack(MusicMetadata("B", "x", url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        until("opened the video") { effects.size == 2 }
        assertTrue((effects[1] as Effect.Open).url.startsWith("https://music.youtube.com/") && "dQw4w9WgXcQ" in (effects[1] as Effect.Open).url)
        // One with no link is looked up, and where it isn't, searched, or offered, or opened where it came from, as set.
        val search = "https://music.youtube.com/search?q=Moon%20Luna"
        val searched = { effects.map { it as Effect.Open }.takeLast(1).map { it.url to it.packageName } }
        model.selectNotFoundAction(NotFoundAction.SEARCH)
        model.openTrack(MusicMetadata("Moon", "Luna"))
        until("searched") { effects.size == 3 }
        assertEquals(listOf(search to ytMusic), searched())
        assertEquals("SEARCH", prefs().getString("not_found", null))
        // With no link it came from, "where it came from" is a search too.
        model.selectNotFoundAction(NotFoundAction.ORIGINAL)
        model.openTrack(MusicMetadata("Moon", "Luna"))
        until("searched again") { effects.size == 4 }
        assertEquals(listOf(search to ytMusic), searched())
        // With one, it opens there.
        val spotify = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
        model.openTrack(MusicMetadata("Moon", "Luna", url = spotify))
        until("opened where it came from") { effects.size == 5 }
        assertEquals(listOf(spotify to MusicService.SPOTIFY.packageName), searched())
        // Asked, it is offered both ways, and either one is taken or the offer is let go.
        model.selectNotFoundAction(NotFoundAction.ASK)
        model.openTrack(MusicMetadata("Moon", "Luna", url = spotify))
        until("offered") { model.uiState.notFoundOffer != null }
        model.takeNotFoundOffer(original = true)
        assertNull(model.uiState.notFoundOffer)
        assertEquals(listOf(spotify to MusicService.SPOTIFY.packageName), searched())
        model.openTrack(MusicMetadata("Moon", "Luna", url = spotify))
        until("offered again") { model.uiState.notFoundOffer != null }
        model.takeNotFoundOffer(original = false)
        assertEquals(listOf(search to ytMusic), searched())
        model.takeNotFoundOffer(original = true)
        assertEquals(7, effects.size)
        model.openTrack(MusicMetadata("Moon", "Luna"))
        until("offered with nothing to open") { model.uiState.notFoundOffer != null }
        model.takeNotFoundOffer(original = true)
        assertEquals(listOf(search to ytMusic), searched())
    }

    private val playlistLink = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
    private val queueLink = "https://music.youtube.com/watch?v=first000000&list=TLGGqueue"

    /** A model whose songs go to [service], on the phone, with a playlist of two songs looked up and shown, YouTube finding the first. */
    private fun playlistModel(playback: FakePlayback, service: MusicService, found: Boolean = true): MainViewModel {
        val embed = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[
            {"title":"First Song","subtitle":"Band"},{"title":"Second Song","subtitle":""}]}}}}}}</script>"""
        fake.handler = { request ->
            when {
                request.url.encodedPath.startsWith("/embed/") -> FakeSpotify.html(request, embed)
                request.url.encodedPath == "/watch_videos" -> FakeSpotify.html(request, "").newBuilder().code(303)
                    .header("Location", "https://www.youtube.com/watch?v=first000000&list=TLGGqueue").build()
                request.url.host == "music.youtube.com" && found -> FakeSpotify.html(request, """{"contents":[{"musicResponsiveListItemRenderer":{
                    "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"First Song"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Band • Album • 3:00"}]}}}],
                    "playlistItemData":{"videoId":"first000000"}}}]}""")
                request.url.host == "music.youtube.com" -> FakeSpotify.html(request, "{}")
                else -> FakeSpotify.html(request, FakeSpotify.trackPage("Road Trip | Spotify", "playlist"))
            }
        }
        shadowOf(app.packageManager).installPackage(installedApp(service.packageName, "Music"))
        prefs().edit().putString("default_target", service.name).commit()
        val model = model(playback = playback)
        model.refreshSystemState()
        idle()
        model.onUrlChange(playlistLink)
        model.resolveTypedInput()
        until("looked the playlist up") { model.uiState.result?.tracks?.size == 2 && !model.uiState.isLoading }
        return model
    }

    @Test
    fun everyMusicAppOnThePhoneIsKnownToThePracticeOfCards() {
        shadowOf(app.packageManager).installPackage(installedApp("com.spotify.music", "Spotify"))
        shadowOf(app.packageManager).installPackage(installedApp("app.morphe.android.apps.youtube.music", "Morphe"))
        val model = model(playback = FakePlayback())
        model.refreshSystemState()
        idle()
        until("the apps are read") { model.uiState.musicApps.size == 2 }
        assertEquals(setOf("com.spotify.music", "app.morphe.android.apps.youtube.music"), model.uiState.musicApps.toSet())
    }

    @Test
    fun aPlaylistPlaysAllAtOnceInTheMusicAppsPlayerOrSaysWhenYouTubeFindsNone() {
        val playback = FakePlayback().apply { access = true }
        val model = playlistModel(playback, MusicService.YOUTUBE_MUSIC)
        // The app plays the list as soon as it is asked.
        appPlays(playback)
        model.playAll()
        until("asked for the list") { playback.listsAsked.isNotEmpty() }
        assertEquals(listOf(ytMusic to queueLink), playback.listsAsked.toList())
        assertEquals("First Song", model.uiState.player?.song?.title)
        until("done asking") { model.uiState.queueProgress == null }
        // Shuffled, the whole list is, then asked for the same way.
        model.closePlayer()
        model.playAll(shuffled = true)
        until("asked again") { playback.listsAsked.size == 2 }
        until("done asking again") { model.uiState.queueProgress == null }
        // The app won't play it in the background: it is opened on the list, no player.
        model.closePlayer()
        playback.canPlaySongs = false
        model.playAll()
        until("opened") { playback.opened.isNotEmpty() }
        assertEquals(ytMusic to queueLink, playback.opened.single())
        until("done opening") { model.uiState.queueProgress == null }
        // Where YouTube finds none of its songs there is nothing to play.
        val none = playlistModel(FakePlayback().apply { access = true }, MusicService.YOUTUBE_MUSIC, found = false)
        none.playAll()
        until("said so") { none.uiState.error == AppError.NOT_FOUND }
        assertNull(none.uiState.queueProgress)
        assertNull(none.uiState.player)
    }

    @Test
    fun aPlaylistForAnAppThatTakesNoListPlaysItsFirstSongByNameOrOpensItThereWhenItCant() {
        val playback = FakePlayback().apply { access = true }
        val model = playlistModel(playback, MusicService.TIDAL)
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        model.playAll()
        until("asked for the first song") { playback.songsAsked.isNotEmpty() }
        assertEquals(MusicMetadata("First Song", "Band") to MusicService.TIDAL.packageName, playback.songsAsked.single().let { (song, app) -> song.copy(url = null, type = ItemType.TRACK) to app })
        assertEquals("First Song", model.uiState.player?.song?.title)
        assertTrue(effects.isEmpty())
        // Not asked in the background, the player is closed and the song opened in the app.
        model.closePlayer()
        playback.canPlaySongs = false
        model.playAll()
        until("opened in the app") { effects.isNotEmpty() }
        assertNull(model.uiState.player)
        assertEquals(MusicService.TIDAL.packageName, (effects.single() as Effect.Open).packageName)
    }

    /** The old watch must stop even when the chosen app keeps playing the same song. */
    private fun pendingQobuzAction(action: (MainViewModel, FakePlayback, MutableList<Effect>) -> Unit) {
        val playback = FakePlayback().apply {
            access = true
            onThePhone = Heard.Song(song, null, app = "com.spotify")
            playing.value = following()
        }
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate)
        var looked = 0
        val numbers = QobuzNumbers(playback, { _, _ -> looked++; "https://play.qobuz.com/track/2" }, { true }, scope)
        val model = model(playback = playback, qobuzNumbers = numbers)
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        model.peekPlayingNow()
        idle()
        numbers.prepare(MusicMetadata("Pending", "Artist"), MusicService.QOBUZ.packageName, "https://play.qobuz.com/track/1").started(true)
        assertEquals(1, looked)
        try {
            action(model, playback, effects)
            pass(QobuzNumbers.WAIT_MS * 2)
            assertTrue(playback.opened.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun askingThePlayersAppAgainCancelsThePendingQobuzRetry() = pendingQobuzAction { model, playback, _ ->
        playback.playing.value = null
        until("the app let go") { model.uiState.player?.following == null }
        model.playerPlayPause()
        idle()
        assertEquals(1, playback.songsAsked.size)
    }

    @Test
    fun pausingThePlayerCancelsThePendingQobuzRetry() = pendingQobuzAction { model, playback, _ ->
        model.playerPlayPause()
        assertEquals(listOf(false), playback.plays.toList())
    }

    @Test
    fun bringingThePlayersAppForwardCancelsThePendingQobuzRetry() = pendingQobuzAction { model, _, effects ->
        model.openPlayerApp()
        idle()
        assertEquals(Effect.Show("com.spotify", finishAfterOpen = false, saysWhenItFails = false), effects.single())
    }

    @Test
    fun clearingTheSongCancelsThePendingQobuzRetry() = pendingQobuzAction { model, _, _ ->
        model.clear()
        assertNull(model.uiState.result)
    }

    @Test
    fun aNonMusicVideoCancelsThePendingQobuzRetryBeforeBeingHandedOn() = pendingQobuzAction { model, _, effects ->
        fake.handler = { request -> FakeSpotify.html(request, """{"x":{"musicVideoType":"MUSIC_VIDEO_TYPE_UGC"}}""") }
        val video = "https://www.youtube.com/watch?v=aaaaaaaaaaa"
        model.setOnlyMusicVideos(true)
        model.resolveIncoming(video)
        until("video handed on") { effects.any { it is Effect.Open } }
        assertEquals(video, (effects.last() as Effect.Open).url)
    }

    @Test
    fun playingAnAlreadyPlayingSongCancelsThePendingQobuzWatch() {
        val spotify = MusicService.SPOTIFY.packageName
        prefs().edit().putString("default_target", MusicService.SPOTIFY.name).commit()
        val playback = FakePlayback().apply {
            access = true
            onThePhone = Heard.Song(song, null, app = spotify)
            playing.value = following(app = spotify)
        }
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate)
        val numbers = QobuzNumbers(playback, { _, _ -> "https://play.qobuz.com/track/2" }, { true }, scope)
        val model = model(playback = playback, qobuzNumbers = numbers)
        model.refreshSystemState()
        try {
            model.openNamedSong(song.title, song.artist)
            until("song ready") { model.uiState.destinationUrls.isNotEmpty() }
            numbers.prepare(MusicMetadata("Pending", "Artist"), MusicService.QOBUZ.packageName, "https://play.qobuz.com/track/1").started(true)
            model.playResult()
            pass(QobuzNumbers.WAIT_MS * 2)
            assertTrue(playback.songsAsked.isEmpty())
            assertTrue(playback.plays.isEmpty())
            assertTrue(playback.opened.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aBackgroundQobuzOpenIsntHeldByItsStore() {
        fake.handler = { request -> FakeSpotify.html(request, """
            <li><div class="ListItem">
                <a href="/x/album/slug/a1" class="ListItem__title" title="Hush">Hush</a>
                <p class="ListItem__artists">Band • Album</p>
                <button data-url="&#x2F;v4&#x2F;ajax&#x2F;popin-add-cart&#x2F;track&#x2F;1"></button>
            </div></li>""") }
        val qobuz = MusicService.QOBUZ.packageName
        shadowOf(app.packageManager).installPackage(installedApp(qobuz, "Music"))
        prefs().edit().putString("default_target", MusicService.QOBUZ.name).commit()
        val playback = FakePlayback().apply { access = true; canPlaySongs = false }
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate)
        var looked = 0
        val numbers = QobuzNumbers(playback, { _, _ -> looked++; kotlinx.coroutines.awaitCancellation() }, { true }, scope)
        val model = model(playback = playback, qobuzNumbers = numbers)
        model.refreshSystemState()
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        try {
            model.openNamedSong(song.title, song.artist)
            until("song ready") { model.uiState.destinationUrls.isNotEmpty() }
            model.playResult()
            until("first open") { playback.opened.isNotEmpty() }
            assertEquals(1, looked)
            // And what's asked next stops the retry, the old song staying where it is.
            model.openPlayerApp()
            idle()
            assertTrue(effects.any { it is Effect.Show })
            pass(QobuzNumbers.WAIT_MS * 2)
            assertEquals(listOf(qobuz to "https://play.qobuz.com/track/1"), playback.opened.toList())
            assertTrue(effects.none { it is Effect.Open })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aBackgroundQobuzOpenStartsItsPlaybackWatch() {
        fake.handler = { request -> FakeSpotify.html(request, """
            <li><div class="ListItem">
                <a href="/x/album/slug/a1" class="ListItem__title" title="Hush">Hush</a>
                <p class="ListItem__artists">Band • Album</p>
                <button data-url="&#x2F;v4&#x2F;ajax&#x2F;popin-add-cart&#x2F;track&#x2F;1"></button>
            </div></li>""") }
        val qobuz = MusicService.QOBUZ.packageName
        shadowOf(app.packageManager).installPackage(installedApp(qobuz, "Music"))
        prefs().edit().putString("default_target", MusicService.QOBUZ.name).commit()
        val playback = FakePlayback().apply { access = true; canPlaySongs = false }
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate)
        var looked = 0
        val numbers = QobuzNumbers(playback, { _, _ -> looked++; "https://play.qobuz.com/track/2" }, { true }, scope)
        val model = model(playback = playback, qobuzNumbers = numbers)
        model.refreshSystemState()
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        try {
            model.openNamedSong(song.title, song.artist)
            until("song ready") { model.uiState.destinationUrls.isNotEmpty() }
            model.playResult()
            until("first open") { playback.opened.isNotEmpty() }
            pass(QobuzNumbers.WAIT_MS)
            assertEquals(listOf(qobuz to "https://play.qobuz.com/track/1", qobuz to "https://play.qobuz.com/track/2"), playback.opened.toList())
            assertEquals(1, looked)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun onlyASongOpenedInQobuzsAppByItsNumberIsWatched() {
        val song = """
            <li><div class="ListItem">
                <a href="/x/album/slug/a1" class="ListItem__title" title="Thriller">Thriller</a>
                <p class="ListItem__artists">Michael Jackson • Thriller</p>
                <button data-url="&#x2F;v4&#x2F;ajax&#x2F;popin-add-cart&#x2F;track&#x2F;625089"></button>
            </div></li>"""
        var found = true
        fake.handler = { request ->
            when {
                request.url.host == "www.qobuz.com" -> FakeSpotify.html(request, if (found) song else "")
                else -> FakeSpotify.html(request, """{"title":"Thriller","author_name":"Michael Jackson"}""")
            }
        }
        val playback = FakePlayback().apply { access = true }
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate)
        var asked = 0
        val numbers = QobuzNumbers(playback, { _, _ -> asked++; "https://play.qobuz.com/track/2" }, app::hasApp, scope)
        fun opened(service: MusicService?, link: String = ytLink, action: (MainViewModel) -> Unit = { it.openResult() }): Int {
            asked = 0
            playback.opened.clear()
            service?.let { shadowOf(app.packageManager).installPackage(installedApp(it.packageName, "Music")) }
            prefs().edit().putString("default_target", service?.name ?: MusicService.QOBUZ.name).commit()
            val model = model(playback = playback, qobuzNumbers = numbers)
            model.refreshSystemState()
            idle()
            val effects = mutableListOf<Effect>()
            model.viewModelScope.launch { model.effects.collect { effects += it } }
            model.onUrlChange(link)
            model.resolveTypedInput()
            until("looked the song up") { model.uiState.result != null && !model.uiState.isLoading }
            until("destination prepared") { model.uiState.destinationUrls[model.uiState.resultDestination] != null }
            // A previous handoff must lose its retry even when this one is another app or a search.
            numbers.prepare(MusicMetadata("Previous", "Artist"), MusicService.QOBUZ.packageName, "https://play.qobuz.com/track/99").started(true)
            asked = 0
            playback.opened.clear()
            action(model)
            until("opened") { effects.isNotEmpty() || model.uiState.resultMissing }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(QobuzNumbers.WAIT_MS))
            idle()
            if (service != MusicService.QOBUZ || !found) assertTrue(playback.opened.isEmpty())
            return asked
        }
        try {
            // Not installed, it opens on the web, where nothing's seen to play.
            assertEquals(0, opened(null))
            assertEquals(1, opened(MusicService.QOBUZ))
            // Any other app's left alone, and a search has no number to swap.
            assertEquals(0, opened(MusicService.DEEZER))
            assertEquals(0, opened(MusicService.DEEZER, action = { it.searchAnyway() }))
            assertEquals(0, opened(MusicService.DEEZER, action = { it.openOriginal() }))
            assertEquals(0, opened(MusicService.DEEZER, action = {
                it.openTrack(MusicMetadata("Next", "Artist", url = "https://www.deezer.com/track/42"))
            }))
            // Another song, as one looked up before is opened on the number kept with it.
            found = false
            assertEquals(0, opened(MusicService.QOBUZ, "https://music.youtube.com/watch?v=aaaaaaaaaaa"))
            assertEquals(0, opened(MusicService.QOBUZ, "https://music.youtube.com/watch?v=bbbbbbbbbbb", action = { it.searchAnyway() }))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSongOpenedBeforeAndroidSaidWhatsInstalledIsStillWatched() {
        // A link that came in with Qobuz's number kept opens at once, while the look at what's installed is still running.
        val song = """
            <li><div class="ListItem">
                <a href="/x/album/slug/a1" class="ListItem__title" title="Thriller">Thriller</a>
                <p class="ListItem__artists">Michael Jackson • Thriller</p>
                <button data-url="&#x2F;v4&#x2F;ajax&#x2F;popin-add-cart&#x2F;track&#x2F;625089"></button>
            </div></li>"""
        fake.handler = { request ->
            when {
                request.url.host == "www.qobuz.com" -> FakeSpotify.html(request, song)
                else -> FakeSpotify.html(request, """{"title":"Thriller","author_name":"Michael Jackson"}""")
            }
        }
        val playback = FakePlayback().apply { access = true }
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate)
        var asked = 0
        val numbers = QobuzNumbers(playback, { _, _ -> asked++; null }, app::hasApp, scope)
        prefs().edit().putString("default_target", MusicService.QOBUZ.name).commit()
        val model = model(playback = playback, qobuzNumbers = numbers)
        model.refreshSystemState()
        idle()
        shadowOf(app.packageManager).installPackage(installedApp(MusicService.QOBUZ.packageName, "Qobuz"))
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        model.onUrlChange(ytLink)
        model.resolveTypedInput()
        until("looked the song up") { model.uiState.result != null && !model.uiState.isLoading }
        assertEquals(emptySet<MusicService>(), model.uiState.installed)
        model.openResult()
        until("opened") { effects.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(QobuzNumbers.WAIT_MS))
        idle()
        scope.cancel()
        assertEquals(1, asked)
    }

    @Test
    fun aCoverPlayedForASongAskedByNameGivesWayToTheExactLink() {
        // Asked by name, Qobuz played a tribute album for Despacito, and K-Pop All-Stars for Gangnam Style, though it has both.
        val page = """
            <li><div class="ListItem">
                <a href="/x/album/slug/a1" class="ListItem__title" title="Despacito">Despacito</a>
                <p class="ListItem__artists">Luis Fonsi • Vida</p>
                <button data-url="&#x2F;v4&#x2F;ajax&#x2F;popin-add-cart&#x2F;track&#x2F;39267477"></button>
            </div></li>"""
        fake.handler = { request ->
            when {
                request.url.host == "www.qobuz.com" -> FakeSpotify.html(request, page)
                else -> FakeSpotify.html(request, """{"title":"Despacito","author_name":"Luis Fonsi"}""")
            }
        }
        val qobuz = MusicService.QOBUZ.packageName
        shadowOf(app.packageManager).installPackage(installedApp(qobuz, "Qobuz"))
        prefs().edit().putString("default_target", MusicService.QOBUZ.name).commit()
        val playback = FakePlayback().apply { access = true; quietYet = true }
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate)
        var looked = 0
        val numbers = QobuzNumbers(playback, { _, _ -> looked++; null }, { true }, scope)
        val model = model(playback = playback, qobuzNumbers = numbers)
        model.refreshSystemState()
        idle()
        model.onUrlChange(ytLink)
        model.resolveTypedInput()
        until("looked the song up") { model.uiState.result != null && !model.uiState.isLoading }
        fun plays(title: String, artist: String) {
            playback.onThePhone = Heard.Song(MusicMetadata(title, artist), null, app = qobuz)
        }
        // The right artist, whatever else the app calls it: it stays in the background.
        model.playResult()
        until("asked by name") { playback.songsAsked.isNotEmpty() }
        plays("Despacito (Remastered)", "Luis Fonsi")
        until("done playing it") { !model.uiState.playingResult }
        assertTrue(playback.opened.isEmpty())
        // Someone else's: the song's own link plays what was asked for.
        model.closePlayer()
        playback.onThePhone = null
        playback.songsAsked.clear()
        model.playResult()
        until("asked by name again") { playback.songsAsked.isNotEmpty() }
        plays("Despacito (Tribute To Luis Fonsi)", "Brava HitMakers")
        until("opened on its link") { playback.opened.isNotEmpty() }
        assertEquals(qobuz to "https://play.qobuz.com/track/39267477", playback.opened.single())
        assertTrue(playback.plays.isEmpty())
        // Opened on its number, that's watched as well, as it may stay on a loading page.
        playback.onThePhone = null
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(QobuzNumbers.WAIT_MS))
        until("watched") { looked == 1 }
        scope.cancel()
    }

    @Test
    fun aCoverPlayedForASongThatIsntThereIsPausedAndSaysSo() {
        fake.handler = { request ->
            when {
                request.url.host == "www.qobuz.com" -> FakeSpotify.html(request, "")
                else -> FakeSpotify.html(request, """{"title":"Song","author_name":"Band"}""")
            }
        }
        val qobuz = MusicService.QOBUZ.packageName
        shadowOf(app.packageManager).installPackage(installedApp(qobuz, "Qobuz"))
        prefs().edit().putString("default_target", MusicService.QOBUZ.name).commit()
        val playback = FakePlayback().apply { access = true; quietYet = true }
        val model = model(playback = playback)
        model.refreshSystemState()
        idle()
        model.onUrlChange(ytLink)
        model.resolveTypedInput()
        until("looked the song up") { model.uiState.result != null && !model.uiState.isLoading }
        // Asked before the lookup has said it isn't there, as a link handed in is.
        model.playResult()
        until("asked by name") { playback.songsAsked.isNotEmpty() }
        playback.onThePhone = Heard.Song(MusicMetadata("Song (Tribute)", "Someone Else"), null, app = qobuz)
        until("paused it") { playback.plays.isNotEmpty() }
        assertEquals(listOf(false), playback.plays.toList())
        until("closed the player") { model.uiState.player == null }
        assertTrue(model.uiState.resultMissing)
        assertTrue(playback.opened.isEmpty())
    }

    @Test
    fun aSongOfAListOpenedInQobuzsAppIsWatchedOnlyWhenOpenedByItsNumber() {
        val song = """
            <li><div class="ListItem">
                <a href="/x/album/slug/a1" class="ListItem__title" title="Thriller">Thriller</a>
                <p class="ListItem__artists">Michael Jackson • Thriller</p>
                <button data-url="&#x2F;v4&#x2F;ajax&#x2F;popin-add-cart&#x2F;track&#x2F;625089"></button>
            </div></li>"""
        var found = true
        fake.handler = { request -> FakeSpotify.html(request, if (found) song else "") }
        shadowOf(app.packageManager).installPackage(installedApp(MusicService.QOBUZ.packageName, "Qobuz"))
        prefs().edit().putString("default_target", MusicService.QOBUZ.name).commit()
        val playback = FakePlayback().apply { access = true }
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate)
        var asked = 0
        val numbers = QobuzNumbers(playback, { _, _ -> asked++; null }, app::hasApp, scope)
        val model = model(playback = playback, qobuzNumbers = numbers)
        model.refreshSystemState()
        idle()
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        model.selectNotFoundAction(NotFoundAction.SEARCH)
        try {
            model.openTrack(MusicMetadata("Thriller", "Michael Jackson"))
            until("opened on its number") { effects.size == 1 }
            assertTrue((effects[0] as Effect.Open).url.endsWith("/track/625089"))
            assertEquals(1, asked)
            // A search has no number to swap.
            found = false
            model.openTrack(MusicMetadata("Song Not There", "Band"))
            until("searched") { effects.size == 2 }
            assertTrue("search" in (effects[1] as Effect.Open).url)
            assertEquals(1, asked)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anAppSeenToHaveTheSongIsntTakenForACoverWhateverItCallsTheArtist() {
        fake.handler = { request ->
            when {
                request.url.host == "www.qobuz.com" -> FakeSpotify.html(request, """
                    <li><div class="ListItem">
                        <a href="/x/album/slug/a1" class="ListItem__title" title="Despacito">Despacito</a>
                        <p class="ListItem__artists">Luis Fonsi • Vida</p>
                        <button data-url="&#x2F;v4&#x2F;ajax&#x2F;popin-add-cart&#x2F;track&#x2F;39267477"></button>
                    </div></li>""")
                else -> FakeSpotify.html(request, """{"title":"Despacito","author_name":"Luis Fonsi"}""")
            }
        }
        val qobuz = MusicService.QOBUZ.packageName
        shadowOf(app.packageManager).installPackage(installedApp(qobuz, "Qobuz"))
        prefs().edit().putString("default_target", MusicService.QOBUZ.name).commit()
        // What the phone says it plays names someone else, and it was already on that as it's asked.
        val playback = FakePlayback().apply { access = true; onThePhone = Heard.Song(MusicMetadata("Despacito", "Fonsi Family"), null, app = qobuz) }
        val model = model(playback = playback)
        model.refreshSystemState()
        idle()
        model.onUrlChange(ytLink)
        model.resolveTypedInput()
        until("looked the song up") { model.uiState.result != null && !model.uiState.isLoading }
        model.playResult()
        until("asked by name") { playback.songsAsked.isNotEmpty() }
        playback.playing.value = following(app = qobuz)
        until("done playing it") { !model.uiState.playingResult }
        assertTrue(playback.opened.isEmpty())
        assertTrue(playback.plays.isEmpty())
    }

    private companion object {
        /** How long UtaTen takes to answer: a slow one, as a test machine with much else to do may be. */
        const val UTATEN_MS = 200L
    }
}
