package com.astrovm.crosstune

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** The timed words lined up to the singing heard in what the phone plays. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h900dp")
class SingingTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null
    private val playback = FakePlayback().apply { access = true }
    private val singing = FakeSinging()
    private val link = "https://music.youtube.com/watch?v=sPmul8b17AU"

    /** Put back after, so other tests hear as the app does. */
    private val realSinging = MainActivity.singingFactory

    /** Six lines, three seconds apart, each sung [SHIFT_MS] later than they're timed; a test may give more. */
    private var lines = listOf("Sing to me tonight", "Under the yellow moon", "Every river finds the sea", "Hold the morning light", "Dancing on the water", "Never let it go")

    /** Lyrics answers matched in step with the screen, as Unconfined does, counting each match it's asked for. */
    private val matched = AtomicInteger()
    private val matching = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = false.also { matched.incrementAndGet() }
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }

    /** When the music app's clock started, which is when the singing was heard from too, and where in the song. */
    private var startedAt = 0L
    private var startedFromMs = 0L

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.lyricsMatchingDispatcher = matching
        MainActivity.shellFactory = { emptyList() }
        MainActivity.checksForUpdates = { false }
        MainActivity.playbackFactory = { playback }
        MainActivity.milkdropFactory = { NoMilkdrop }
        MainActivity.soundTapFactory = { null }
        MainActivity.lyricsBusyPauseMs = 0
        MainActivity.lookAheadLyrics = false
        MainActivity.listenAlongPauseMs = 0
        MainActivity.quietPauseMs = 0
        MainActivity.singingFactory = { _, _ -> singing }
        MainActivity.lyricsLanguageFactory = { LyricsLanguage { "es-AR" } }
        prefs().edit().clear().putBoolean("setup_complete", true).putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false).putBoolean("play_right_away", false).putBoolean("visuals", false).commit()
        File(app.cacheDir, "lookups.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        lyricsTimedFor(null)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun tearDown() {
        controller?.destroyAndSettle()
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.lyricsMatchingDispatcher = null
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.checksForUpdates = ::looksForUpdates
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.milkdropFactory = ::NativeMilkdrop
        MainActivity.soundTapFactory = MainActivity.phoneSound::open
        MainActivity.lyricsBusyPauseMs = LYRICS_BUSY_PAUSE_MS
        MainActivity.lookAheadLyrics = true
        MainActivity.listenAlongPauseMs = MainViewModel.LISTEN_ALONG_PAUSE_MS
        MainActivity.quietPauseMs = MainViewModel.LISTEN_ALONG_PAUSE_MS
        MainActivity.hearingFactory = null
        MainActivity.singingFactory = realSinging
        MainActivity.lyricsLanguageFactory = null
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun model() = ViewModelProvider(checkNotNull(controller) { "Expected controller to be non-null" }.get())[MainViewModel::class.java]

    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    /** LRCLIB has the six lines, three seconds apart or at [times] seconds, timed for a recording [seconds] long, when it says. */
    private fun lyricsTimedFor(seconds: Int?, times: List<Int> = lines.indices.map { it * 3 }) {
        val synced = lines.zip(times) { line, at -> "[%02d:%02d.00] %s".format(at / 60, at % 60, line) }.joinToString("\n")
        val duration = seconds?.let { "\"duration\":$it," }.orEmpty()
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                if (request.url.host == "lrclib.net") """[{"trackName":"Demo","artistName":"Band",$duration"syncedLyrics":"$synced"}]"""
                else """{"title":"Demo","author_name":"Band"}"""
            )
        }
    }

    /** The song's words open, nothing playing yet. */
    private fun openLyrics() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        val button = hasText(app.getString(R.string.lyrics_button)) or hasContentDescription(app.getString(R.string.lyrics_button))
        waitUntil { composeRule.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(button)[0].performScrollTo().performClick()
        waitUntil { model().uiState.lyricLines.size == lines.size }
    }

    /** The music app plays the song from [positionMs], or pauses there; each call is news to Crosstune. */
    private fun plays(positionMs: Long = 0, playing: Boolean = true, durationMs: Long? = null, speed: Float = 1f, video: Boolean = false) {
        startedAt = SystemClock.elapsedRealtime()
        startedFromMs = positionMs
        playback.playing.value = Following(PlaybackClock(positionMs, startedAt, speed, playing), "YouTube Music", canSeek = true, video = video, durationMs = durationMs)
        composeRule.waitForIdle()
    }

    /**
     * Each line heard sung [shiftMs] later than timed, word by word, as whisper.cpp hears them: a
     * little late. Heard from where the app last played from, so nothing sung before it.
     */
    private fun sung(shiftMs: Long) = Sung(startedAt, lines.flatMapIndexed { index, line ->
        line.split(' ').mapIndexed { word, text -> HeardWord("$text,", index * 3_000L + shiftMs + SungAlignment.HEARD_LATE_MS + word * 300L - startedFromMs) }
    }.filter { it.atMs >= 0 })

    /** Time passes, [ms] of it. */
    private fun passes(ms: Long) {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        composeRule.waitForIdle()
    }

    private fun firstLineMs() = model().uiState.lyricLines.first().timeMs

    @Test
    fun theWordsMoveToTheSingingHeardOnceForTheSong() {
        singing.answer = { sung(SHIFT_MS) }
        openLyrics()
        plays(positionMs = 1_000)
        waitUntil { model().uiState.lyricsSynced }
        assertEquals(SHIFT_MS, firstLineMs())
        // Heard in the words' language, as whisper.cpp names it.
        assertEquals(listOf<String?>("es"), singing.languages)
        // A report on them says they were lined up to the singing.
        composeRule.runOnIdle { model().sendReport("") }
        val sent = org.json.JSONObject(fake.requestBodies[fake.requestedUrls.indexOfLast { it == ReportSender.URL }])
        assertTrue(sent.getJSONObject("song").getBoolean("sung"))
        // As a nudge by hand would: Reset puts them back, and they aren't heard again.
        composeRule.runOnIdle { model().resetSync() }
        plays(positionMs = 100)
        composeRule.waitForIdle()
        assertEquals(0L, firstLineMs())
        assertEquals(1, singing.hears)
        // Opened again, the song's words were heard already.
        checkNotNull(controller) { "Expected controller to be non-null" }.destroyAndSettle()
        openLyrics()
        plays(positionMs = 200)
        composeRule.waitForIdle()
        assertEquals(1, singing.hears)
    }

    /** Shazam's answers: each one [songMs] into the song, for audio heard just now, then no more. */
    private fun shazamAnswers(vararg songMs: Long) {
        val answers = songMs.iterator()
        MainActivity.hearingFactory = {
            SongHearing {
                if (!answers.hasNext()) awaitCancellation()
                Heard.Song(MusicMetadata("Demo", "Band"), null, answers.next(), SystemClock.elapsedRealtime(), "reference")
            }
        }
    }

    /** Where the music app's clock is when the first line is lit, whatever part of that is how far ahead it's taken to be. */
    private fun firstLineOnAppsClock() = SystemClock.elapsedRealtime().let { now -> firstLineMs() + checkNotNull(playback.playing.value) { "Expected playback.playing.value to be non-null" }.clock.positionAt(now) - followedAt() }

    @Test
    fun aVideoNoAnswerFitsIsLinedUpByTheSingingAlone() {
        // Heard as a part played again, the answer is minutes from what the video could be.
        shazamAnswers(180_000)
        singing.answer = { sung(SHIFT_MS) }
        openLyrics()
        plays(positionMs = 1_000, video = true)
        waitUntil { singing.hears >= 1 && model().uiState.following?.timingKnown == true }
        assertEquals(SHIFT_MS, firstLineMs())
    }

    @Test
    fun aVideoFarFromItsWordsNoAnswerFitsIsLinedUpByTheSingingAlone() {
        shazamAnswers(180_000)
        singing.answer = { sung(FAR_MS) }
        openLyrics()
        plays(positionMs = 1_000, video = true)
        waitUntil { singing.hears >= 1 && model().uiState.following?.timingKnown == true }
        assertEquals(FAR_MS, firstLineMs())
    }

    @Test
    fun aVideoWhoseSongMayNotHaveStartedIsntHeardForItsSingingYet() {
        // Sixty seconds longer than its words, it may still be in what opens it.
        lyricsTimedFor(30)
        shazamAnswers(180_000)
        openLyrics()
        plays(positionMs = 1_000, video = true, durationMs = 90_000)
        passes(5_000)
        assertEquals(0, singing.hears)
    }

    @Test
    fun anAnswerHeardAfterTheSingingPlacedTheLinesDoesntMoveThem() {
        shazamAnswers(180_000, 4_000)
        singing.answer = { sung(FAR_MS) }
        openLyrics()
        plays(positionMs = 1_000, video = true)
        waitUntil { model().uiState.following?.timingKnown == true }
        val off = firstLineOnAppsClock()
        composeRule.runOnIdle { model().listenAlong() }
        passes(1_000)
        assertEquals(off.toDouble(), firstLineOnAppsClock().toDouble(), 500.0)
    }

    @Test
    fun aVideoPlacedFromOneAnswerIsWhereItWasOpenedAgain() {
        // The video 2 seconds ahead of the words, as one answer says, and the singing another 4 off from there.
        shazamAnswers(0)
        singing.answer = { sung(FAR_MS + 2_000L) }
        openLyrics()
        plays(positionMs = 2_000, video = true)
        waitUntil { model().uiState.lyricsSynced }
        val off = firstLineOnAppsClock()
        // Next time nothing's heard yet, so what's kept has to say it without that answer.
        shazamAnswers()
        singing.answer = { CompletableDeferred<Sung?>().await() }
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.runOnIdle { model().showLyrics() }
        waitUntil { model().uiState.lyricLines.size == lines.size }
        plays(positionMs = 2_000, video = true)
        waitUntil { model().uiState.following?.timingKnown == true }
        assertEquals(off.toDouble(), firstLineOnAppsClock().toDouble(), 500.0)
    }

    private fun shown(id: Int) = composeRule.onAllNodesWithText(app.getString(id)).fetchSemanticsNodes().isNotEmpty()

    /** Where the words follow the song to be now. */
    private fun followedAt() = checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.clock.positionAt(SystemClock.elapsedRealtime())

    @Test
    fun pickedUpPartwayTheSingingPutsItRightThisTimeOnly() {
        // The app was already playing, so where it says it is may be behind, as YouTube Music's often is.
        singing.answer = { sung(SHIFT_MS) }
        openLyrics()
        plays(positionMs = 3_000)
        // The lines heard move to where they're sung by the app's clock, and nothing's kept for next time.
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[2].timeMs == 6_000 + SHIFT_MS }
        assertEquals(3_000 + SystemClock.elapsedRealtime() - startedAt, followedAt())
        assertFalse(model().uiState.lyricsSynced)
        assertFalse(prefs().contains("sung_heard"))
        assertFalse(prefs().contains("lyrics_sync"))
        // A line tapped moves the app to where it's sung by its clock.
        composeRule.runOnIdle { model().seekLyrics(model().uiState.lyricLines[2].timeMs) }
        assertEquals(6_000 + SHIFT_MS, playback.seeks.last())
        // Played on from where it was, it isn't heard again.
        plays(positionMs = 3_000 + SystemClock.elapsedRealtime() - startedAt)
        composeRule.waitForIdle()
        assertEquals(1, singing.hears)
        // Moved elsewhere in the app, it's heard again.
        plays(positionMs = 4_000)
        waitUntil { singing.hears == 2 }
        // Started again, where the app says is sure: the words are lined up for good.
        plays(positionMs = 100)
        waitUntil { model().uiState.lyricsSynced }
        assertEquals(SHIFT_MS, firstLineMs())
        assertEquals(100L, followedAt() - (SystemClock.elapsedRealtime() - startedAt))
        assertEquals(3, singing.hears)
    }

    /** Picked up partway, words timed for another take heard [offMs] off in each while: the first, then the next once the song plays on to it. */
    private fun heardFarOffTwice(offMs: Long) {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { offMs }) }
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 1 }
        passes(30_000)
    }

    @Test
    fun pickedUpPartwayWordsHeardFarOffAreKeptForNextTime() {
        // Timed for another take, the words are seconds off: far more than the app's clock can be behind.
        heardFarOffTwice(FAR_MS)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[2].timeMs == 8_000 + FAR_MS }
        // So it's the words that are off, and the lines heard are kept: played again, they start where they're sung.
        assertTrue(model().uiState.lyricsSynced)
        assertTrue(prefs().contains("lyrics_sync"))
        // The app's clock may still be a little behind, so the singing is heard again next time.
        assertFalse(prefs().contains("sung_heard"))
    }

    /** Lines at [times] seconds, each heard sung [offMs] later, from where the song is as it's heard, for as long as it is, or its first [seconds]. */
    private fun sungAt(times: List<Int>, offMs: List<Long>, seconds: Int = SingingListener.SECONDS) = SystemClock.elapsedRealtime().let { now ->
        val at = now - startedAt + startedFromMs
        Sung(now, lines.indices.filter { times[it] * 1_000L + offMs[it] in at until at + seconds * 1_000L }.flatMap { index ->
            lines[index].split(' ').mapIndexed { word, text -> HeardWord(text, times[index] * 1_000L + offMs[index] + SungAlignment.HEARD_LATE_MS + word * 300L - at) }
        })
    }

    @Test
    fun aSongLongerThanWhatsHeardAtOnceIsHeardThroughEachLineWhereItsSung() {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        // A second later as a whole, and some lines more or less, as the words' own timing is off by a little here and there.
        singing.answer = { sungAt(times, listOf(1_000, 1_000, 1_300, 1_300, 900, 1_300)) }
        openLyrics()
        plays()
        // Heard from just before the first line.
        passes(500)
        waitUntil { singing.hears == 1 && model().uiState.lyricsSynced }
        // The lines heard are where they're sung, and those after keep to how far off the last of them was.
        assertEquals(listOf(3_000L, 6_000L, 9_300L, 31_300L, 34_300L, 37_300L), model().uiState.lyricLines.map { it.timeMs })
        assertFalse(prefs().contains("sung_heard"))
        // Where the next lines are sung, they're heard too, and that's the song.
        passes(32_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[4].timeMs == 33_900L }
        assertEquals(listOf(3_000L, 6_000L, 9_300L, 31_300L, 33_900L, 37_300L), model().uiState.lyricLines.map { it.timeMs })
        assertTrue(prefs().contains("sung_heard"))
        passes(30_000)
        assertEquals(2, singing.hears)
    }

    @Test
    fun aWhileStartsALittleLessBeforeALineWhenThatLetsItsThirdLineBeHeard() {
        // Heard from a second and a half before the first line, the third would start in the while's last seconds, only half heard.
        val times = listOf(2, 10, 18, 24, 28, 32)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { 1_000L }) }
        openLyrics()
        plays()
        // So it's heard from a little later, still before the first line, not from the second.
        passes(1_500)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[0].timeMs == 3_000L }
    }

    @Test
    fun heardNoSingingWhereTheWordsAreTimedItHearsOnWithNothingElseHappening() {
        // Timed into an intro, as YouTube Music's words for an edit can be: the first while hears only music.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { if (singing.hears == 1) Sung(SystemClock.elapsedRealtime(), listOf(HeardWord("[MUSIC]", 380))) else sungAt(times, times.map { 1_000L }) }
        openLyrics()
        plays()
        // Heard from just before the first line.
        passes(500)
        waitUntil { singing.hears == 1 }
        assertFalse(model().uiState.lyricsSynced)
        // The song just plays on, and where the words are sung next, they're heard.
        passes(32_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricsSynced }
        assertEquals(31_000L, model().uiState.lyricLines[3].timeMs)
    }

    @Test
    fun noVoiceInTheFirstSecondsHeardNoLineIsShownSungWhileTheRestIsHeard() {
        // Timed into an intro: the first seconds heard are music alone.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        val rest = CompletableDeferred<Sung?>()
        singing.early = { Sung(SystemClock.elapsedRealtime(), listOf(HeardWord("[MUSIC]", 380))) }
        singing.answer = { rest.await() }
        openLyrics()
        plays()
        passes(500)
        waitUntil { singing.hears == 1 && model().uiState.following?.timingKnown == false }
        // Heard again a little later, as music alone too, before it was believed.
        assertEquals(1, singing.agains)
        // Only the first while is told so early.
        singing.early = null
        rest.complete(sungAt(times, times.map { 1_000L }))
        waitUntil { model().uiState.following?.timingKnown == true }
    }

    @Test
    fun musicAloneInTheFirstSecondsIsHeardAgainALittleLaterBeforeTheLinesAreDoubted() {
        // Sung where the words are timed, but the speech model hears the first seconds as music alone.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        val rest = CompletableDeferred<Sung?>()
        singing.early = { musicAlone() }
        singing.again = { Sung(SystemClock.elapsedRealtime(), listOf(HeardWord("la", 500))) }
        singing.answer = { rest.await() }
        openLyrics()
        plays()
        passes(500)
        waitUntil { singing.agains == 1 }
        composeRule.waitForIdle()
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        // Nor does the whole while heard as music alone doubt them, as a voice was heard in it.
        rest.complete(musicAlone())
        composeRule.waitForIdle()
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
    }

    @Test
    fun aVoiceInTheFirstSecondsLeavesTheLinesShownSung() {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        val rest = CompletableDeferred<Sung?>()
        singing.early = { sungAt(times, times.map { 0L }) }
        singing.answer = { rest.await() }
        openLyrics()
        plays()
        passes(500)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        // A voice heard, they're not heard again.
        assertEquals(0, singing.agains)
        rest.complete(null)
    }

    @Test
    fun movedWhileTheFirstSecondsAreHeardTheyTellNothing() {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        val early = CompletableDeferred<Unit>()
        singing.early = { early.await(); Sung(SystemClock.elapsedRealtime(), listOf(HeardWord("[MUSIC]", 380))) }
        openLyrics()
        plays()
        passes(500)
        waitUntil { singing.hears == 1 }
        plays(positionMs = 60_000)
        early.complete(Unit)
        composeRule.waitForIdle()
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
    }

    @Test
    fun wordsTimedFarEarlierThanSungAreHeardStraightOnNotWhereTheyreTimed() {
        // Timed for a take with a shorter intro: where the words are timed next, the singing's long gone.
        val times = listOf(2, 5, 8, 11, 44, 47)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { 17_000L }) }
        openLyrics()
        plays()
        // Heard from just before the first line.
        passes(500)
        // The first while has a line at most, too little to tell, so no line is shown sung till the singing's found.
        waitUntil { singing.hears == 1 && model().uiState.following?.timingKnown == false }
        assertFalse(model().uiState.lyricsSynced)
        passes(20_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricsSynced }
        assertEquals(22_000L, model().uiState.lyricLines[1].timeMs)
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
    }

    @Test
    fun linesHeardInAWhilesFirstSecondsArePutInTimeBeforeTheRestIsIn() {
        // Timed for a take with a shorter intro: the singing's found straight on, on the next while.
        val times = listOf(2, 5, 8, 11, 44, 47)
        lyricsTimedFor(null, times)
        val rest = CompletableDeferred<Sung?>()
        singing.early = { sungAt(times, times.map { 17_000L }, SingingListener.FIRST_SECONDS) }
        singing.answer = { if (singing.hears == 1) sungAt(times, times.map { 17_000L }) else rest.await() }
        openLyrics()
        plays()
        passes(500)
        waitUntil { singing.hears == 1 && model().uiState.following?.timingKnown == false }
        passes(20_000)
        // Its first seconds hold lines enough to tell, so they're put where they're sung without waiting on the rest.
        waitUntil { singing.hears == 2 && model().uiState.lyricsSynced }
        assertEquals(22_000L, model().uiState.lyricLines[1].timeMs)
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        rest.complete(null)
    }

    @Test
    fun resetPartwayThroughTheSongTheSingingIsntHeardOn() {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { 1_000L }) }
        openLyrics()
        plays()
        // Heard from just before the first line.
        passes(500)
        waitUntil { singing.hears == 1 && model().uiState.lyricsSynced }
        composeRule.runOnIdle { model().resetSync() }
        passes(40_000)
        assertEquals(1, singing.hears)
        assertEquals(2_000L, firstLineMs())
        assertTrue(prefs().contains("sung_heard"))
    }

    @Test
    fun putInTimeByHandPartwayTheSingingIsntHeardOn() {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { 1_000L }) }
        openLyrics()
        plays()
        // Heard from just before the first line.
        passes(500)
        waitUntil { singing.hears == 1 && model().uiState.lyricsSynced }
        composeRule.runOnIdle { model().nudgeSync(200) }
        passes(40_000)
        assertEquals(1, singing.hears)
        assertEquals(3_200L, firstLineMs())
    }

    @Test
    fun aStartSaidLateIsntSure() {
        // The app says it started from the start, but said so a while ago: Crosstune didn't see it start.
        singing.answer = { sung(SHIFT_MS) }
        openLyrics()
        startedAt = SystemClock.elapsedRealtime() - 2_000
        startedFromMs = 0
        playback.playing.value = Following(PlaybackClock(0, startedAt), "YouTube Music", canSeek = true)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertFalse(model().uiState.lyricsSynced)
    }

    @Test
    fun withoutTheSpeechModelItsOfferedByHandThenTheSingingIsHeard() {
        singing.missing = true
        singing.answer = { sung(SHIFT_MS) }
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        singing.fetched = { singing.missing = !gate.await(); !singing.missing }
        openLyrics()
        plays(positionMs = 1_000)
        waitUntil { shown(R.string.singing_model_offer) }
        assertFalse(model().uiState.lyricsSynced)
        // Missed on every try there is, it's still heard once the model's here.
        repeat(MainViewModel.SINGING_TRIES - 1) {
            val heard = singing.hears
            plays(positionMs = 100)
            waitUntil { singing.hears > heard }
        }
        composeRule.onNodeWithText(app.getString(R.string.singing_model_download)).performClick()
        // While it comes, it spins and can't be asked for again.
        waitUntil { !shown(R.string.singing_model_download) }
        assertTrue(shown(R.string.singing_model_offer))
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertExists()
        gate.complete(true)
        // Here now, the singing is heard with it and the words move.
        waitUntil { model().uiState.lyricsSynced }
        assertEquals(SHIFT_MS, firstLineMs())
        assertFalse(shown(R.string.singing_model_offer))
        assertEquals(1, singing.fetches)
    }

    @Test
    fun aFetchThatFailsSaysSoAndClosedTheModelIsntOfferedAgain() {
        singing.missing = true
        openLyrics()
        plays(positionMs = 1_000)
        waitUntil { shown(R.string.singing_model_offer) }
        composeRule.onNodeWithText(app.getString(R.string.singing_model_download)).performClick()
        waitUntil { shown(R.string.singing_model_failed) }
        // Tried again from there.
        composeRule.onNodeWithText(app.getString(R.string.singing_model_download)).performClick()
        waitUntil { singing.fetches == 2 && shown(R.string.singing_model_failed) }
        composeRule.onNodeWithContentDescription(app.getString(R.string.singing_model_close)).performClick()
        composeRule.waitForIdle()
        assertFalse(shown(R.string.singing_model_failed))
        // Missed again while Crosstune stays open, it isn't offered again.
        val heard = singing.hears
        plays(positionMs = 100)
        waitUntil { singing.hears > heard }
        composeRule.waitForIdle()
        assertFalse(shown(R.string.singing_model_offer))
    }

    @Test
    fun nothingHeardWithTheModelThereOffersNoModel() {
        singing.answer = { null }
        openLyrics()
        plays(positionMs = 1_000)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertFalse(shown(R.string.singing_model_offer))
    }

    @Test
    fun wordsHeardOffAreReportedWhenProblemsAreAllowedWithoutAsking() {
        TimingLog.clear()
        prefs().edit().putBoolean("auto_reports", true).commit()
        // A second off is already off.
        singing.answer = { sung(-MainViewModel.SUNG_OFF_MS) }
        openLyrics()
        plays(positionMs = 1_000)
        waitUntil { fake.requestedUrls.contains(ReportSender.URL) }
        val sent = org.json.JSONObject(fake.requestBodies[fake.requestedUrls.indexOf(ReportSender.URL)])
        assertEquals("sung_off", sent.getString("problem"))
        assertFalse(offerShown())
        // Moved by what was sung, not put in time by hand, so nothing is owed to be sent later.
        assertEquals(0, owed())
        // Words heard off are a problem with their timing, so it says how it went, though nothing was put in time by hand.
        val song = sent.getJSONObject("song")
        assertTrue(song.has("earlyMs"))
        assertFalse(song.has("timing"))
        assertTrue(song.getString("log"), song.getString("log").contains("lines in time"))
        // Moved by the singing, not by hand, so leaving them sends nothing more.
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(1, fake.requestedUrls.count { it == ReportSender.URL })
    }

    /** How many timing reports are owed, kept to be sent once the words are left, or at the next start. */
    private fun owed() = org.json.JSONArray(prefs().getString("auto_reports_owed", "[]")).length()

    private fun offerShown() = listOf(R.string.auto_report_offer, R.string.auto_report_offer_words_off).any { composeRule.onAllNodes(hasText(app.getString(it))).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun wordsHeardOffOfferToReportWhenTheUserHasntSaidAndYesSendsThisOne() {
        singing.answer = { sung(-MainViewModel.SUNG_OFF_MS) }
        openLyrics()
        plays(positionMs = 1_000)
        waitUntil { model().uiState.reportOffer }
        composeRule.waitForIdle()
        assertTrue(offerShown())
        // Nothing goes before the user says so.
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
        composeRule.mainClock.advanceTimeBy(OFFER_ARMED_MS)
        composeRule.onNodeWithText(app.getString(R.string.auto_report_yes)).performClick()
        waitUntil { fake.requestedUrls.contains(ReportSender.URL) }
        assertEquals("sung_off", org.json.JSONObject(fake.requestBodies[fake.requestedUrls.indexOf(ReportSender.URL)]).getString("problem"))
        assertFalse(offerShown())
        assertEquals(true, prefs().getBoolean("auto_reports", false))
        // Once a song: heard again, it doesn't go again.
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(1, fake.requestedUrls.count { it == ReportSender.URL })
    }

    @Test
    fun wordsHeardOffAndTheOfferClosedSendNothingNorAskAgainThisSession() {
        singing.answer = { sung(-MainViewModel.SUNG_OFF_MS) }
        openLyrics()
        plays(positionMs = 1_000)
        waitUntil { model().uiState.reportOffer }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(OFFER_ARMED_MS)
        composeRule.onNodeWithContentDescription(app.getString(R.string.auto_report_not_now)).performClick()
        composeRule.waitForIdle()
        assertFalse(offerShown())
        // Not now isn't a no: nothing is saved as the answer, and nothing is sent.
        assertFalse(prefs().contains("auto_reports"))
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
        // Heard off again, not asked again until the app starts anew.
        plays(positionMs = 20_000)
        composeRule.waitForIdle()
        assertFalse(model().uiState.reportOffer)
    }

    @Test
    fun theOfferForWordsHeardOffOverTheBiggestFontInGermanFitsAboveTheBar() {
        org.robolectric.RuntimeEnvironment.setQualifiers("+de")
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        try {
            singing.answer = { sung(-MainViewModel.SUNG_OFF_MS) }
            openLyrics()
            plays(positionMs = 1_000)
            waitUntil { model().uiState.reportOffer }
            composeRule.mainClock.advanceTimeBy(OFFER_ARMED_MS)
            composeRule.waitForIdle()
            val words = app.getString(R.string.auto_report_offer_words_off)
            assertTrue(words, words.startsWith("Der Text"))
            val box = composeRule.onNodeWithText(words)
            box.assertIsDisplayed()
            composeRule.onNodeWithText(app.getString(R.string.auto_report_yes)).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(app.getString(R.string.auto_report_not_now)).assertIsDisplayed()
            val bounds = box.getUnclippedBoundsInRoot()
            val root = composeRule.onRoot().getUnclippedBoundsInRoot()
            assertTrue("$bounds is wider than the screen $root", bounds.left >= 0.dp && bounds.right <= root.right)
            assertTrue("$bounds takes too much of $root", bounds.bottom - bounds.top <= (root.bottom - root.top) / 4)
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun theOfferForWordsHeardOffStaysOnScreenWhileTheBarIsAway() {
        singing.answer = { sung(-MainViewModel.SUNG_OFF_MS) }
        openLyrics()
        plays(positionMs = 1_000)
        waitUntil { model().uiState.reportOffer }
        // The words glide, and the bar leaves them to it; the offer isn't something to find by touching the screen.
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()
        assertFalse(composeRule.onAllNodes(hasContentDescription(app.getString(R.string.lyrics_learn))).fetchSemanticsNodes().isNotEmpty())
        assertTrue(offerShown())
    }

    @Test
    fun wordsPutInTimeByHandWhileTheOfferForWordsHeardOffIsUpJoinIt() {
        singing.answer = { sung(-MainViewModel.SUNG_OFF_MS) }
        openLyrics()
        plays(positionMs = 1_000)
        waitUntil { model().uiState.reportOffer }
        composeRule.onNodeWithContentDescription(app.getString(R.string.lyrics_sync)).performClick()
        composeRule.onNodeWithText(lines[2]).performClick()
        waitUntil { model().uiState.reportOfferFor == setOf(SongProblem.SUNG_OFF, SongProblem.TIMED_BY_HAND) }
        // No longer only words heard off: the offer says what it says for any problem.
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodes(hasText(app.getString(R.string.auto_report_offer))).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun pickedUpPartwayOneWhileHeardFarOffAloneLeavesTheLinesWhereTheyreTimed() {
        // As Never Gonna Give You Up went, picked up partway on Spotify: one while matched a verse seven
        // seconds from where it's sung, though the next is heard where the app's clock says, a second late.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        var whiles = 0
        singing.answer = { sungAt(times, times.map { if (whiles == 0) 7_000L else 1_000L }).also { whiles++ } }
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        // Nothing else says the words are that far off, so the lines lit stay where they're timed.
        assertEquals(times.map { it * 1_000L }, model().uiState.lyricLines.map { it.timeMs })
        assertFalse(model().uiState.lyricsSynced)
        passes(30_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[3].timeMs == 31_000L }
        // Heard about a second late, it's the clock: put right this time only, and nothing's kept.
        assertFalse(model().uiState.lyricsSynced)
        assertFalse(prefs().contains("lyrics_sync"))
        assertFalse(model().uiState.reportOffer)
    }

    @Test
    fun pickedUpPartwayOneWhileHeardFarOffAfterLinesHeardNearDoesntMoveThem() {
        // As the replay of Never Gonna Give You Up went: the first lines heard a second late, then a while
        // the speech model heard as one line over and over put the lines seconds later.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        var whiles = 0
        singing.answer = { sungAt(times, times.map { if (whiles == 1) 7_000L else 1_000L }).also { whiles++ } }
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[0].timeMs == 3_000L }
        val heardNear = model().uiState.lyricLines.map { it.timeMs }
        passes(30_000)
        waitUntil { singing.hears == 2 }
        composeRule.waitForIdle()
        // One while alone says that far off: the lines stay where the first while put them.
        assertEquals(heardNear, model().uiState.lyricLines.map { it.timeMs })
        assertFalse(model().uiState.lyricsSynced)
        assertFalse(prefs().contains("lyrics_sync"))
    }

    @Test
    fun pickedUpPartwayOneWhileHeardFurtherOffThanNearLinesAreLookedAtMovesThemThisTimeOnly() {
        // As Bizarre Love Triangle went, YouTube Music's words timed for another take: one while heard 22.8 s
        // off. That far off, the lines heard are found only where by far the most words agree across the
        // whole song, so they move at once, but for this time only: a chorus may be timed otherwise there.
        prefs().edit().putBoolean("auto_reports", true).commit()
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { 12_000L }) }
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[1].timeMs == 17_000L }
        assertFalse(model().uiState.lyricsSynced)
        assertFalse(prefs().contains("lyrics_sync"))
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
        // The next while hears the words as far off, where they're shown now: they're kept so, a problem found.
        passes(40_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricsSynced }
        assertEquals(times.map { it * 1_000L + 12_000L }, model().uiState.lyricLines.map { it.timeMs })
        assertTrue(prefs().contains("lyrics_sync"))
        waitUntil { fake.requestedUrls.count { it == ReportSender.URL } == 1 }
        composeRule.runOnIdle { model().dismissLyrics() }
        assertEquals(1, fake.requestedUrls.count { it == ReportSender.URL })
    }

    /**
     * Lines sung [keptMs] after they're timed, and kept so, picked up 3 s in, the first while hearing the
     * chorus, lines 3 to 5, 25 s before it's sung, where lines 1 and 2 are: then where it's sung, or [laterMs] after they're timed.
     */
    private fun chorusHeardFarFromWhereItsSung(keptMs: Long = 0, laterMs: Long = keptMs): List<Int> {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        var whiles = 0
        singing.answer = { sungAt(times, if (whiles == 0) listOf(100_000L, 100_000L, 100_000L, keptMs - 25_000L, keptMs - 25_000L, keptMs - 25_000L) else times.map { laterMs }).also { whiles++ } }
        if (keptMs == 0L) openLyrics() else keptOff(times, keptMs)
        plays(positionMs = 3_000)
        // Kept later, the first line shown is later too, and it's heard from just before it.
        if (keptMs > 0) passes(2_000)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[3].timeMs == 5_000L + keptMs }
        return times
    }

    @Test
    fun pickedUpPartwayOneChorusHeardFarFromWhereItsSungIsntKept() {
        // Found where by far the most words agree, but the chorus is sung again where the words time it.
        chorusHeardFarFromWhereItsSung()
        composeRule.waitForIdle()
        // A later while still has a chance to put the words back.
        assertFalse(model().uiState.lyricsSynced)
        assertFalse(prefs().contains("lyrics_sync"))
        assertFalse(model().uiState.reportOffer)
    }

    @Test
    fun closingAfterOneWrongChorusDoesNotKeepItsMove() {
        val times = chorusHeardFarFromWhereItsSung()
        composeRule.runOnIdle { model().dismissLyrics(); model().showLyrics() }
        waitUntil { model().uiState.lyricLines.size == lines.size }
        assertEquals(times.map { it * 1_000L }, model().uiState.lyricLines.map { it.timeMs })
        assertFalse(prefs().contains("lyrics_sync"))
    }

    @Test
    fun oneWrongChorusNineSecondsEarlyDoesNotMoveOrKeepTheSheet() {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = {
            Sung(startedAt, lines.drop(3).flatMapIndexed { line, text ->
                text.split(' ').mapIndexed { word, piece -> HeardWord(piece, 1_000L + line * 3_000L + SungAlignment.HEARD_LATE_MS + word * 300L) }
            })
        }
        openLyrics()
        plays(positionMs = 20_000)
        passes(10_000)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertEquals(times.map { it * 1_000L }, model().uiState.lyricLines.map { it.timeMs })
        composeRule.runOnIdle { model().dismissLyrics() }
        assertFalse(prefs().contains("lyrics_sync"))
    }

    @Test
    fun oneWrongChorusNineSecondsLateDoesNotMoveOrKeepTheSheet() {
        val times = listOf(2, 5, 8, 11, 14, 17)
        lyricsTimedFor(null, times)
        singing.answer = {
            Sung(startedAt, lines.take(3).flatMapIndexed { line, text ->
                text.split(' ').mapIndexed { word, piece -> HeardWord(piece, 1_000L + line * 3_000L + SungAlignment.HEARD_LATE_MS + word * 300L) }
            })
        }
        openLyrics()
        plays(positionMs = 10_000)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertEquals(times.map { it * 1_000L }, model().uiState.lyricLines.map { it.timeMs })
        composeRule.runOnIdle { model().dismissLyrics() }
        assertFalse(prefs().contains("lyrics_sync"))
    }

    @Test
    fun twoDistinctPhrasesOfARepeatedChorusDoNotMoveTheSheet() {
        lines = listOf(
            "scarlet rivers carry lanterns under starlight", "silver mountains gather thunder beyond morning",
            "quiet meadows shelter sparrows beside water", "distant clocks turn endlessly around noon",
            "broken windows shine across empty streets", "golden leaves drift silently over hills"
        )
        val times = listOf(2, 5, 8, 11, 14, 17)
        lyricsTimedFor(null, times)
        singing.answer = {
            Sung(startedAt, lines.take(2).flatMapIndexed { line, text ->
                listOf(HeardWord("noise", 1_000L + line * 3_000L + SungAlignment.HEARD_LATE_MS)) +
                    text.split(' ').drop(1).mapIndexed { word, piece ->
                        HeardWord(piece, 1_300L + line * 3_000L + SungAlignment.HEARD_LATE_MS + word * 300L)
                    }
            })
        }
        openLyrics()
        plays(positionMs = 10_000)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertEquals(times.map { it * 1_000L }, model().uiState.lyricLines.map { it.timeMs })
        composeRule.runOnIdle { model().dismissLyrics() }
        assertFalse(prefs().contains("lyrics_sync"))
    }

    @Test
    fun pickedUpLateOneWhileHeardFarEarlierIsReportedWithoutKeepingIt() {
        val times = listOf(60, 63, 66, 90, 93, 96)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { -27_400L }) }
        openLyrics()
        plays(positionMs = 61_000)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[3].timeMs == 62_600L }
        assertFalse(model().uiState.reportOffer)
        composeRule.runOnIdle { model().pauseFollowingNext() }
        assertEquals(setOf(SongProblem.SUNG_OFF), model().uiState.reportOfferFor)
        assertTrue(offerShown())
        assertFalse(model().uiState.lyricsSynced)
        assertFalse(prefs().contains("lyrics_sync"))
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
    }

    @Test
    fun aFarHearingIsConfirmedByAnotherPartAfterReopening() {
        lines = lines + listOf("A different verse begins", "Its words are elsewhere", "Another line is here", "Over hills we wander", "All the stars are shining", "Home is where we go")
        val times = listOf(60, 63, 66, 90, 93, 96, 103, 106, 109, 130, 133, 136)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { -27_400L }) }
        openLyrics()
        plays(positionMs = 61_000)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[3].timeMs == 62_600L }
        plays(positionMs = 61_000, playing = false)
        composeRule.runOnIdle { model().dismissLyrics() }
        assertFalse(prefs().contains("lyrics_sync"))
        composeRule.runOnIdle { model().showLyrics() }
        waitUntil { model().uiState.lyricLines.size == lines.size }
        assertEquals(90_000L, model().uiState.lyricLines[3].timeMs)
        plays(positionMs = 101_000)
        passes(1_000)
        waitUntil { singing.hears >= 2 }
        composeRule.waitForIdle()
        assertTrue(model().uiState.lyricsSynced)
        assertTrue(prefs().contains("lyrics_sync"))
        assertEquals(102_600L, model().uiState.lyricLines[9].timeMs)
    }

    @Test
    fun hearingTheSameFarWindowAfterReopeningDoesNotConfirmIt() {
        val times = listOf(60, 63, 66, 90, 93, 96)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { -27_400L }) }
        openLyrics()
        plays(positionMs = 61_000)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[3].timeMs == 62_600L }
        plays(positionMs = 61_000, playing = false)
        composeRule.runOnIdle { model().dismissLyrics(); model().showLyrics() }
        waitUntil { model().uiState.lyricLines.size == lines.size }
        plays(positionMs = 61_000)
        waitUntil { singing.hears >= 2 }
        composeRule.waitForIdle()
        assertFalse(model().uiState.lyricsSynced)
        assertFalse(prefs().contains("lyrics_sync"))
    }

    @Test
    fun aLaterNearHearingDiscardsUnconfirmedFarEvidence() {
        lines = lines + listOf("A different verse begins", "Its words are elsewhere", "Another line is here", "Over hills we wander", "All the stars are shining", "Home is where we go")
        val times = listOf(60, 63, 66, 90, 93, 96, 103, 106, 109, 130, 133, 136)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { -27_400L }) }
        openLyrics()
        plays(positionMs = 61_000)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[3].timeMs == 62_600L }
        plays(positionMs = 61_000, playing = false)
        composeRule.runOnIdle { model().dismissLyrics(); model().showLyrics() }
        waitUntil { model().uiState.lyricLines.size == lines.size }
        singing.answer = { sungAt(times, times.map { 0L }) }
        plays(positionMs = 130_000)
        waitUntil { singing.hears >= 2 }
        composeRule.waitForIdle()
        plays(positionMs = 130_000, playing = false)
        composeRule.runOnIdle { model().dismissLyrics(); model().showLyrics() }
        waitUntil { model().uiState.lyricLines.size == lines.size }
        singing.answer = { sungAt(times, times.map { -27_400L }) }
        plays(positionMs = 101_000)
        passes(1_000)
        waitUntil { singing.hears >= 3 }
        composeRule.waitForIdle()
        assertFalse(model().uiState.lyricsSynced)
        assertFalse(prefs().contains("lyrics_sync"))
    }

    @Test
    fun recordedJapaneseHearingWaitsForConfirmation() {
        // Plastic Love, heard from 53.505 s: the phone model's saved 54 s window and the sheet
        // selected with the recording's length. Unrelated lines keep their times but use filler words.
        val times = listOf(30490, 39910, 47620, 57100, 67930, 77360, 85200, 94520, 105320, 114750, 123720, 133360, 139560, 159210, 168690, 177550, 187480, 194590, 204250, 212140, 221540, 232040, 241540, 250980, 260140, 269710, 278970)
        val nearby = mapOf(1 to "恋のプログラムを 狂わせないでね", 2 to "出逢いと別れ 上手に打ち込んで", 3 to "時間がくれば終わる don't hurry", 4 to "愛に傷ついた あの日からずっと")
        lines = times.indices.map { nearby[it] ?: "filler$it" }
        val synced = lines.zip(times) { line, at -> "[%02d:%02d.%02d] %s".format(at / 60_000, at / 1000 % 60, at % 1000 / 10, line) }.joinToString("\n")
        fake.handler = { request ->
            FakeSpotify.html(request, if (request.url.host == "lrclib.net") """[{"trackName":"Demo","artistName":"Band","duration":309,"syncedLyrics":"$synced"}]""" else """{"title":"Demo","author_name":"Band"}""")
        }
        singing.answer = {
            val heard = listOf(1120L to "僕は背ないでね", 4360L to "目会いとは", 6240L to "ファレッツ", 8000L to "衝突にく", 10780L to "自動で", 13660L to "時間が来るレッファ", 16500L to "オーブーの光り")
            Sung(startedAt, heard.map { (at, text) -> HeardWord(text, at + 495) })
        }
        openLyrics()
        plays(positionMs = 53_505)
        passes(2_500)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertEquals(47_620L, model().uiState.lyricLines[2].timeMs)
        assertFalse(prefs().contains("lyrics_sync"))
    }

    @Test
    fun recordedFarHearingWaitsForConfirmationAfterClosing() {
        // The saved 39 s hearing of Bizarre Love Triangle, with unrelated lines replaced by filler.
        val times = listOf(0, 10, 13, 17, 19, 21, 26, 29, 33, 35, 37, 42, 58, 61, 66, 69, 74, 91, 94, 98, 100, 102, 106, 110, 114, 116, 118, 123, 139, 142, 147, 151, 155, 159, 163, 167)
        val nearby = mapOf(1 to "Every time I think of you", 2 to "I feel shot right through with a bolt of blue", 3 to "It's no problem of mine", 4 to "But it's a problem I find", 5 to "Living a life that I can't leave behind", 6 to "But there's no sense in telling me", 7 to "The wisdom of the fool won't set you free", 8 to "But that's the way that it goes")
        lines = times.indices.map { nearby[it] ?: "filler$it" }
        lyricsTimedFor(null, times)
        singing.answer = {
            if (singing.hears > 1) awaitCancellation()
            val heard = listOf(1580L to "It's", 2220L to "no", 2660L to "problem,", 3300L to "it's", 3760L to "no", 3760L to "problem,", 3780L to "it's", 4160L to "a", 4540L to "problem,", 5080L to "it's", 5780L to "fine", 6360L to "Live", 6600L to "in", 6820L to "a", 7240L to "life", 7600L to "that", 7900L to "I", 8280L to "can't", 8720L to "leave", 9020L to "you", 10220L to "head", 10780L to "There's", 11080L to "no", 11660L to "steps", 12240L to "in", 12980L to "standing", 14060L to "There's", 14600L to "no")
            Sung(startedAt, heard.map { (at, text) -> HeardWord(text, at + 485) })
        }
        openLyrics()
        plays(positionMs = 38_515)
        passes(20_000)
        waitUntil { singing.hears >= 1 }
        composeRule.waitForIdle()
        assertTrue("Expected the far hearing to move the line, got ${model().uiState.lyricLines[3].timeMs} ms", model().uiState.lyricLines[3].timeMs > 30_000L)
        composeRule.runOnIdle { model().dismissLyrics() }
        assertFalse(prefs().contains("lyrics_sync"))
        composeRule.runOnIdle { model().showLyrics() }
        waitUntil { model().uiState.lyricLines.size == lines.size }
        assertEquals(17_000L, model().uiState.lyricLines[3].timeMs)
    }

    @Test
    fun aLoneFarWhileIsReportedOnLeavingOnlyOnceItsWholeAnswerIsIn() {
        val times = listOf(2, 4, 6, 30, 32, 34)
        lyricsTimedFor(null, times)
        val whole = CompletableDeferred<Sung?>()
        singing.early = { sungAt(times, times.map { -25_000L }, SingingListener.FIRST_SECONDS) }
        singing.answer = { whole.await() }
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        plays(positionMs = 4_000)
        passes(500)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[3].timeMs == 5_000L }
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
        whole.complete(sungAt(times, times.map { -25_000L }))
        composeRule.waitForIdle()
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { fake.requestedUrls.contains(ReportSender.URL) }
        val sent = org.json.JSONObject(fake.requestBodies[fake.requestedUrls.indexOf(ReportSender.URL)])
        assertEquals("sung_off", sent.getString("problem"))
        assertTrue(sent.getJSONObject("song").getString("log").contains("sung -25000 off, clock sure false"))
        assertFalse(prefs().contains("lyrics_sync"))
        passes(40_000)
        assertEquals(1, fake.requestedUrls.count { it == ReportSender.URL })
    }

    @Test
    fun aFarWhilesFirstSecondsAloneArentReportedWhenTheAppStops() {
        val times = listOf(2, 4, 6, 30, 32, 34)
        lyricsTimedFor(null, times)
        singing.early = { sungAt(times, times.map { -25_000L }, SingingListener.FIRST_SECONDS) }
        singing.answer = { awaitCancellation() }
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        plays(positionMs = 4_000)
        passes(500)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[3].timeMs == 5_000L }
        checkNotNull(controller) { "Expected controller to be non-null" }.pause().stop()
        composeRule.settle(checkNotNull(controller) { "Expected controller to be non-null" }.get())
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
        assertFalse(prefs().contains("lyrics_sync"))
    }

    @Test
    fun closingUnconfirmedFarWordsDoesntUseUpTheReportOffer() {
        chorusHeardFarFromWhereItsSung()
        composeRule.runOnIdle { model().dismissLyrics() }
        // Opening the same words again can still offer once their far match survives the play.
        val times = listOf(2, 5, 8, 30, 33, 36)
        singing.answer = { sungAt(times, times.map { -25_000L }) }
        composeRule.runOnIdle { model().showLyrics() }
        waitUntil { model().uiState.lyricLines.size == lines.size }
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[3].timeMs == 5_000L }
        composeRule.runOnIdle { model().pauseFollowingNext() }
        assertEquals(setOf(SongProblem.SUNG_OFF), model().uiState.reportOfferFor)
    }

    @Test
    fun closingFarWordsOffersTheirReportWhenTheyOpenAgain() {
        chorusHeardFarFromWhereItsSung()
        composeRule.runOnIdle { model().dismissLyrics() }
        assertFalse(offerShown())
        composeRule.runOnIdle { model().showLyrics() }
        waitUntil { model().uiState.lyricLines.size == lines.size }
        waitUntil { model().uiState.reportOfferFor == setOf(SongProblem.SUNG_OFF) }
        assertTrue(offerShown())
        composeRule.runOnIdle { model().allowAutoReports(true) }
        waitUntil { fake.requestedUrls.contains(ReportSender.URL) }
        val sent = org.json.JSONObject(fake.requestBodies[fake.requestedUrls.indexOf(ReportSender.URL)])
        assertEquals("sung_off", sent.getString("problem"))
        assertTrue(sent.getJSONObject("song").getString("log").contains("sung -25000 off"))
        composeRule.runOnIdle { model().dismissLyrics(); model().showLyrics() }
        composeRule.waitForIdle()
        assertFalse(offerShown())
    }

    @Test
    fun decliningADeferredFarReportDeletesIt() {
        chorusHeardFarFromWhereItsSung()
        composeRule.runOnIdle { model().dismissLyrics(); model().showLyrics() }
        waitUntil { model().uiState.reportOfferFor == setOf(SongProblem.SUNG_OFF) }
        composeRule.runOnIdle { model().allowAutoReports(false) }
        composeRule.runOnIdle { model().dismissLyrics(); model().showLyrics() }
        composeRule.waitForIdle()
        assertFalse(offerShown())
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
        assertFalse(AutoReports(prefs()).owes(checkNotNull(model().uiState.lyricsFor), SongProblem.SUNG_OFF))
    }

    @Test
    fun deferredFarReportSurvivesTheActivityClosing() {
        chorusHeardFarFromWhereItsSung()
        composeRule.runOnIdle { model().dismissLyrics() }
        controller?.destroyAndSettle()
        controller = null
        openLyrics()
        waitUntil { model().uiState.reportOfferFor == setOf(SongProblem.SUNG_OFF) }
        assertTrue(offerShown())
    }

    @Test
    fun pickedUpPartwayLinesMovedByOneWrongWhileGoBackByOneRightOne() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        val times = chorusHeardFarFromWhereItsSung()
        // Moved back in the app a little later, the next while hears the first lines where they're timed: one while undoes it.
        passes(5_000)
        plays(positionMs = 3_000)
        // Heard from just before the next line shown.
        passes(5_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[3].timeMs == 30_000L }
        assertEquals(times.map { it * 1_000L }, model().uiState.lyricLines.map { it.timeMs })
        assertFalse(model().uiState.lyricsSynced)
        assertFalse(prefs().contains("lyrics_sync"))
        assertFalse(model().uiState.reportOffer)
        assertFalse(offerShown())
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
        composeRule.runOnIdle { model().pauseFollowingNext() }
        assertFalse(model().uiState.reportOffer)
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
    }

    @Test
    fun pickedUpPartwayLinesMovedByOneWrongWhileDontGoBackByOneHeardJustFarEnoughFromWhereTheyreKept() {
        // Heard 2 s from where the words are kept is off, as anywhere here: that while doesn't say the move was wrong.
        chorusHeardFarFromWhereItsSung(laterMs = MainViewModel.WORDS_OFF_MS)
        passes(5_000)
        plays(positionMs = 3_000)
        passes(5_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[1].timeMs == 5_000L + MainViewModel.WORDS_OFF_MS }
        // Found far off itself, it moves the lines it heard this time only: the rest aren't put back where they're kept.
        assertTrue(model().uiState.lyricLines[3].timeMs < 30_000L)
        assertFalse(model().uiState.lyricsSynced)
    }

    @Test
    fun farWhilesThatDriftWithoutPuttingTheWordsBackReportOnceOnLeaving() {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        var whiles = 0
        singing.answer = { sungAt(times, times.map { if (whiles == 0) 12_000L else 18_600L }).also { whiles++ } }
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[1].timeMs == 17_000L }
        passes(40_000)
        waitUntil { singing.hears == 2 }
        composeRule.waitForIdle()
        // The later while is 6.6 s further off: it neither keeps nor puts back the first move.
        assertEquals(42_000L, model().uiState.lyricLines[3].timeMs)
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
        assertFalse(prefs().contains("lyrics_sync"))
        val next = MusicMetadata("Next", "Other band")
        composeRule.runOnIdle {
            playback.onThePhone = Heard.Song(next, null)
            playback.playing.value = null
        }
        waitUntil { model().uiState.lyricsFor == next }
        waitUntil { fake.requestedUrls.count { it == ReportSender.URL } == 1 }
        val sent = org.json.JSONObject(fake.requestBodies[fake.requestedUrls.indexOf(ReportSender.URL)])
        assertEquals("sung_off", sent.getString("problem"))
        assertEquals("Demo", sent.getJSONObject("song").getString("title"))
        composeRule.runOnIdle { model().pauseFollowingNext(); model().dismissLyrics() }
        assertEquals(1, fake.requestedUrls.count { it == ReportSender.URL })
    }

    @Test
    fun aLoneFarWhileIsReportedWhenTheSongPlaysAgainFromTheStart() {
        val times = listOf(2, 4, 6, 30, 32, 34)
        lyricsTimedFor(null, times)
        var whiles = 0
        singing.early = { if (whiles == 0) sungAt(times, times.map { -25_000L }, SingingListener.FIRST_SECONDS) else null }
        singing.answer = { if (whiles++ == 0) sungAt(times, times.map { -25_000L }) else awaitCancellation() }
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        plays(positionMs = 4_000)
        passes(500)
        waitUntil { singing.hears == 1 && whiles == 1 && model().uiState.lyricLines[3].timeMs == 5_000L }
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
        // Played again from the start, nothing put the words back in the play that ended.
        plays(positionMs = 0)
        waitUntil { fake.requestedUrls.contains(ReportSender.URL) }
        val sent = org.json.JSONObject(fake.requestBodies[fake.requestedUrls.indexOf(ReportSender.URL)])
        assertEquals("sung_off", sent.getString("problem"))
        assertFalse(prefs().contains("lyrics_sync"))
        assertEquals(30_000L, model().uiState.lyricLines[3].timeMs)
        composeRule.runOnIdle { model().dismissLyrics() }
        assertEquals(1, fake.requestedUrls.count { it == ReportSender.URL })
    }

    @Test
    fun aFarWhileHeardAgainPastFirstSecondsThatPutTheWordsBackIsReportedOnLeaving() {
        // Lines every 3 s, each with words of its own. The first while hears every line 25 s early, so they move
        // there. The next while's first seconds hear lines where they're kept, which puts them back for now, but the
        // whole while hears them 25 s early again, where they were shown: the same far move, so it's still reported.
        lines = List(40) { line -> "aeiou".map { vowel -> "b$vowel${'a' + line % 26}${if (line >= 26) "z" else ""}" }.joinToString(" ") }
        val times = List(40) { line -> 2 + line * 3 }
        lyricsTimedFor(null, times)
        var whiles = 0
        val second = CompletableDeferred<Sung?>()
        singing.early = { sungAt(times, times.map { if (whiles == 0) -25_000L else 0L }, SingingListener.FIRST_SECONDS) }
        singing.answer = { if (whiles++ == 0) sungAt(times, times.map { -25_000L }) else second.await() }
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        plays(positionMs = 4_000)
        passes(500)
        waitUntil { singing.hears == 1 && whiles == 1 && model().uiState.lyricLines[10].timeMs == 7_000L }
        while (whiles < 2) passes(1_000)
        // Its first seconds heard where the words are timed: back there for now.
        waitUntil { model().uiState.lyricLines[10].timeMs == 32_000L }
        second.complete(sungAt(times, times.map { -25_000L }))
        waitUntil { model().uiState.lyricLines[10].timeMs == 7_000L }
        assertFalse(prefs().contains("lyrics_sync"))
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
        composeRule.runOnIdle { model().pauseFollowingNext() }
        waitUntil { fake.requestedUrls.contains(ReportSender.URL) }
        val sent = org.json.JSONObject(fake.requestBodies[fake.requestedUrls.indexOf(ReportSender.URL)])
        assertEquals("sung_off", sent.getString("problem"))
    }

    @Test
    fun pickedUpPartwayLinesKeptOffMovedByOneWrongWhileGoBackToWhereTheyreKept() {
        val times = chorusHeardFarFromWhereItsSung(keptMs = 3_000)
        assertFalse(model().uiState.reportOffer)
        // Moved back in the app, the next while hears the first lines where they're kept, 3 s after they're timed.
        passes(5_000)
        plays(positionMs = 3_000)
        passes(5_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[3].timeMs == 33_000L }
        assertEquals(times.map { it * 1_000L + 3_000L }, model().uiState.lyricLines.map { it.timeMs })
        composeRule.runOnIdle { model().pauseFollowingNext() }
        assertFalse(model().uiState.reportOffer)
    }

    @Test
    fun pickedUpPartwayOneWhileHeardJustAsFarOffAsNearLinesAreLookedAtLeavesThem() {
        // Heard ten seconds off, it's found near where they're timed, where words can match by chance too: one while doesn't move them.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { SungAlignment.MAX_SHIFT_MS }) }
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertEquals(times.map { it * 1_000L }, model().uiState.lyricLines.map { it.timeMs })
    }

    @Test
    fun pickedUpPartwayTwoWhilesHeardFarOffASecondApartDontAgreeThatNear() {
        // Eight seconds off, words drift no more than a second through the song, and these two are a second apart.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        var whiles = 0
        singing.answer = { sungAt(times, times.map { if (whiles == 0) 9_000L else 8_000L }).also { whiles++ } }
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 1 }
        passes(30_000)
        waitUntil { singing.hears == 2 }
        composeRule.waitForIdle()
        assertEquals(times.map { it * 1_000L }, model().uiState.lyricLines.map { it.timeMs })
        assertFalse(model().uiState.lyricsSynced)
    }

    @Test
    fun pickedUpPartwayAWhilesFirstSecondsDontAgreeWithTheRestOfItButTheNextWhileDoes() {
        // Moved 12 s by one while found far off, then heard 1.9 s further still, lines close enough
        // together for its first seconds to tell where they are before the rest of it is in.
        lines = lines + listOf("Bright stars above us", "Carry me home again", "Whisper softly forever")
        val times = listOf(2, 5, 8, 30, 32, 34, 60, 62, 64)
        lyricsTimedFor(null, times)
        val offMs = { if (singing.hears == 1) 12_000L else 13_900L }
        singing.answer = { sungAt(times, times.map { offMs() }) }
        singing.early = { if (singing.hears == 1) null else sungAt(times, times.map { offMs() }, SingingListener.FIRST_SECONDS) }
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[1].timeMs == 17_000L }
        passes(40_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[3].timeMs == 43_900L }
        composeRule.waitForIdle()
        // Those first seconds are the same while as the rest of it, so they don't agree with it.
        assertFalse(model().uiState.lyricsSynced)
        assertFalse(prefs().contains("lyrics_sync"))
        // The next while hears them as far off: two whiles agree, and they're kept so.
        passes(33_000)
        waitUntil { singing.hears == 3 && model().uiState.lyricsSynced }
        assertEquals(times.mapIndexed { line, at -> at * 1_000L + if (line < 3) 12_000L else 13_900L }, model().uiState.lyricLines.map { it.timeMs })
    }

    @Test
    fun pickedUpPartwayTwoWhilesHeardFarOffAgreeTheFurtherTheMoreTheyDrift() {
        // Words timed for another take drift through the song: a second apart, nearly ten seconds off, they agree.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        var whiles = 0
        singing.answer = { sungAt(times, times.map { if (whiles == 0) 9_800L else 8_800L }).also { whiles++ } }
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertEquals(times.map { it * 1_000L }, model().uiState.lyricLines.map { it.timeMs })
        passes(30_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[3].timeMs == 38_800L }
        assertTrue(model().uiState.lyricsSynced)
    }

    /** The words at [times] seconds kept [offMs] later than timed, as a play before heard them, then open. */
    private fun keptOff(times: List<Int>, offMs: Long) {
        LyricsSyncStore(prefs()).save(MusicMetadata("Demo", "Band"), lines.zip(times) { line, at -> LyricLine(at * 1_000L, line) }, listOf(SyncPoint(times.first() * 1_000L, times.first() * 1_000L + offMs)))
        openLyrics()
        waitUntil { model().uiState.lyricsSynced }
    }

    @Test
    fun pickedUpPartwayOneWhileHeardFarFromWordsKeptFarOffDoesntMoveThem() {
        // Kept 40 s off, one while hears them 5.7 s further: no more than an eighth of how far off they are,
        // but still one while alone, as the one Never Gonna Give You Up was wrongly heard by, 5.35 s off.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        keptOff(times, 40_000)
        val kept = prefs().getString("lyrics_sync", null)
        singing.answer = { sungAt(times, times.map { 45_700L }) }
        plays(positionMs = 43_000)
        // Heard from just before the next line shown.
        passes(2_000)
        waitUntil { singing.hears >= 1 }
        composeRule.waitForIdle()
        assertEquals(times.map { it * 1_000L + 40_000L }, model().uiState.lyricLines.map { it.timeMs })
        assertEquals(kept, prefs().getString("lyrics_sync", null))
    }

    @Test
    fun pickedUpPartwayLinesKeptFarOffArentMovedByOneWhileHeardALittleFurther() {
        // Kept 20 s later, here by hand, then picked up there: one while hears them 2.5 s further still.
        // No more than an eighth of how far off they're kept, but one while alone never moves them, however
        // they were kept: a while once did, about as far off as words kept far off drift.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { 22_500L }) }
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(20_000) }
        val byHand = prefs().getString("lyrics_sync", null)
        plays(positionMs = 23_000)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertEquals(times.map { it * 1_000L + 20_000L }, model().uiState.lyricLines.map { it.timeMs })
        assertEquals(byHand, prefs().getString("lyrics_sync", null))
    }

    @Test
    fun aVideoPickedUpPartwayPutInTimeByHandIsntMovedByOneWhile() {
        // A video's one while far off is enough to move words found as they are, but not words put in time by hand.
        shazamAnswers(180_000)
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { 3_000L }) }
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        val byHand = prefs().getString("lyrics_sync", null)
        plays(positionMs = 3_000, video = true)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertEquals(times.map { it * 1_000L + 500L }, model().uiState.lyricLines.map { it.timeMs })
        assertEquals(byHand, prefs().getString("lyrics_sync", null))
    }

    @Test
    fun aVideoPutInTimeByHandIsntMovedByOneWhileOnceItsWordsAreOpenedAgain() {
        // Put in time by hand, then left: opened again another time, they're still the person's timing.
        shazamAnswers(180_000, 180_000)
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { 3_000L }) }
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        controller?.destroyAndSettle()
        openLyrics()
        val byHand = prefs().getString("lyrics_sync", null)
        plays(positionMs = 3_000, video = true)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertEquals(times.map { it * 1_000L + 500L }, model().uiState.lyricLines.map { it.timeMs })
        assertEquals(byHand, prefs().getString("lyrics_sync", null))
    }

    /**
     * The remix's own words, three seconds apart; under the song's bare name, two copies that agree with each other, ten
     * seconds later, once [copies] says they answer, each answered once [held] lets it.
     */
    private fun remixWithCopies(copies: () -> Boolean, held: () -> Unit = {}) {
        fun answer(title: String, shiftMs: Int, seconds: Int?): String {
            val synced = lines.mapIndexed { index, line -> "[00:%05.2f] %s".format(Locale.ROOT, (index * 3_000 + shiftMs) / 1000.0, line) }.joinToString("\\n")
            return """{"trackName":"$title","artistName":"Band",${seconds?.let { "\"duration\":$it," }.orEmpty()}"syncedLyrics":"$synced"}"""
        }
        fake.handler = { request ->
            val url = request.url
            FakeSpotify.html(
                request,
                when {
                    url.host == "lrclib.net" && url.queryParameter("track_name") == "Demo (2018 Remix)" -> "[${answer("Demo (2018 Remix)", 0, null)}]"
                    url.host == "lrclib.net" && url.queryParameter("track_name") == "Demo" && copies() ->
                        held().let { "[${answer("Demo", 10_000, 200)},${answer("Demo", 10_600, 201)}]" }
                    url.host == "lrclib.net" -> "[]"
                    url.host == "music.163.com" -> """{"result":{"songs":[]}}"""
                    url.host.endsWith("youtube.com") -> """{"title":"Demo (2018 Remix)","author_name":"Band"}"""
                    else -> """{"results":[]}"""
                }
            )
        }
    }

    /** The words kept by the lookups for good, as they are once they're timed anew or kept as they were. */
    private fun keptForGood() = File(app.cacheDir, "lyrics.json").let { it.isFile && "Sing to me tonight" in it.readText() && "provisional" !in it.readText() }

    @Test
    fun wordsPutInTimeByHandArentTimedAnewByCopiesFoundWhenLookedUpAgain() {
        var copies = false
        remixWithCopies({ copies })
        val times = lines.indices.map { it * 3_000L }
        openLyrics()
        waitUntil { !model().findingWords }
        val song = checkNotNull(model().uiState.lyricsFor) { "Expected the words' song" }
        val found = model().uiState.lyricLines
        assertEquals(times, found.map { it.timeMs })
        // Put in time by hand with Shazam's gap alone, which saves no points, only that they were (Issue #1164).
        LyricsSyncStore(prefs()).save(song, found, emptyList(), byHand = true)
        controller?.destroyAndSettle()
        // Looked up again, as after the lookups change, and now the copies answer; they're not even asked.
        // Once the first lookup finishes, closing the screen keeps its words too. Clear both to force another lookup.
        assertTrue(File(app.cacheDir, "kept_words.json").isFile)
        File(app.cacheDir, "kept_words.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        copies = true
        val asked = fake.requestedUrls.size
        matched.set(0)
        openLyrics()
        waitUntil(::keptForGood)
        // Matched where the test set, in step with the screen, not on the shared pool a busy machine fills.
        assertTrue(matched.get() > 0)
        assertEquals(times, model().uiState.lyricLines.map { it.timeMs })
        assertTrue(fake.requestedUrls.drop(asked).none { "track_name=Demo&" in it })
        // And kept so: opened again once the words as shown aren't kept any more, as after the readings change, they're the
        // same lines, so the mark that they were timed by hand still holds.
        controller?.destroyAndSettle()
        File(app.cacheDir, "kept_words.json").delete()
        openLyrics()
        assertEquals(times, model().uiState.lyricLines.map { it.timeMs })
        assertTrue(LyricsSyncStore(prefs()).byHand(song, model().uiState.lyricLines))
    }

    @Test
    fun wordsPutInTimeByTheSingingArentTimedAnewByCopiesFoundWhenLookedUpAgain() {
        var copies = false
        remixWithCopies({ copies })
        val times = lines.indices.map { it * 3_000L }
        openLyrics()
        waitUntil { !model().findingWords }
        val song = checkNotNull(model().uiState.lyricsFor) { "Expected the words' song" }
        val found = model().uiState.lyricLines
        // The singing put the first line a second later, which keeps a point and no hand mark.
        LyricsSyncStore(prefs()).save(song, found, listOf(SyncPoint(0, 1_000)))
        controller?.destroyAndSettle()
        // Once the first lookup finishes, closing the screen keeps its words too. Clear both to force another lookup.
        assertTrue(File(app.cacheDir, "kept_words.json").isFile)
        File(app.cacheDir, "kept_words.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        copies = true
        // Only what's asked once the words are opened again counts: the first lookup asked for the bare title too.
        val asked = fake.requestedUrls.size
        matched.set(0)
        openLyrics()
        waitUntil { fake.requestedUrls.drop(asked).any { "track_name=Demo&" in it } && keptForGood() }
        composeRule.waitForIdle()
        assertTrue(matched.get() > 0)
        // The copies are looked for, as these lines aren't timed by hand, but the shown ones keep where the singing put them.
        assertEquals(times.map { it + 1_000 }, model().uiState.lyricLines.map { it.timeMs })
    }

    @Test
    fun aLookupRefreshRestoresHandTimingWhenItFindsTheSameTimestampsAgain() {
        remixWithCopies({ true })
        openLyrics()
        waitUntil { !model().findingWords && keptForGood() }
        val song = checkNotNull(model().uiState.lyricsFor)
        val retimed = model().uiState.lyricLines
        assertEquals(10_000L, retimed.first().timeMs)
        val corrected = retimed.map { it.timeMs + 1_500 }
        LyricsSyncStore(prefs()).save(song, retimed, listOf(SyncPoint(10_000, corrected.first())), byHand = true)
        checkNotNull(controller).destroyAndSettle()
        controller = null

        // A refresh asks for the original timestamps first. The copies then give back the sheet we corrected.
        File(app.cacheDir, "kept_words.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        openLyrics()
        waitUntil { !model().findingWords && keptForGood() }
        assertEquals(corrected, model().uiState.lyricLines.map { it.timeMs })
        assertTrue(model().uiState.lyricsSynced)
    }

    @Test
    fun wordsPutInTimeByHandWhileCopiesAreLookedForStayAsTheyAre() {
        val asked = CountDownLatch(1)
        val answer = CountDownLatch(1)
        remixWithCopies({ true }) {
            asked.countDown()
            answer.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        val times = lines.indices.map { it * 3_000L }
        openLyrics()
        // The copies are asked for once the words show; they're put in time by hand before they answer.
        waitUntil { asked.count == 0L }
        composeRule.runOnIdle { model().nudgeSync(500) }
        answer.countDown()
        waitUntil(::keptForGood)
        composeRule.waitForIdle()
        assertEquals(times.map { it + 500 }, model().uiState.lyricLines.map { it.timeMs })
        // Kept so, with the lines that timing is kept for.
        controller?.destroyAndSettle()
        File(app.cacheDir, "kept_words.json").delete()
        openLyrics()
        assertEquals(times.map { it + 500 }, model().uiState.lyricLines.map { it.timeMs })
    }

    @Test
    fun pickedUpPartwayOneChorusHeardAsTheNextIsntKeptByTheNextHeardNearTheOneAfter() {
        // Three choruses, 30 s apart, timed right, each sung a little differently. The first is heard as the
        // second's words, so the lines move 30 s early; where the second is sung, the third is shown now,
        // and their first words are the same, so it would agree. It's heard where the words are kept first.
        lines = listOf(
            "Bright stars above us", "Carry me home again", "Whisper softly forever",
            "Rivers run through valleys", "Mountains stand so tall", "Oceans meet the shore",
            "Rivers fade into dust", "Mountains fall like rain", "Oceans dry and gone",
        )
        val times = listOf(10, 12, 14, 40, 42, 44, 70, 72, 74)
        lyricsTimedFor(null, times)
        var whiles = 0
        singing.answer = { sungAt(times, if (whiles == 0) times.mapIndexed { line, _ -> if (line in 3..5) -30_000L else 100_000L } else times.map { 0L }).also { whiles++ } }
        // The second while's first seconds hear the second chorus too.
        singing.early = { if (whiles == 1) sungAt(times, times.map { 0L }, SingingListener.FIRST_SECONDS) else null }
        openLyrics()
        plays(positionMs = 8_000)
        // Heard from just before the first chorus.
        passes(1_000)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[6].timeMs == 40_000L }
        passes(40_000)
        waitUntil { singing.hears == 2 }
        composeRule.waitForIdle()
        assertEquals(times.map { it * 1_000L }, model().uiState.lyricLines.map { it.timeMs })
        assertFalse(model().uiState.lyricsSynced)
        assertFalse(prefs().contains("lyrics_sync"))
        assertFalse(model().uiState.reportOffer)
        assertFalse(offerShown())
        composeRule.runOnIdle { model().pauseFollowingNext() }
        assertFalse(model().uiState.reportOffer)
    }

    @Test
    fun linesHeardALittleLaterDontTakeTheLitLineBack() {
        // As on the Samsung: a line just lit, then the singing heard puts the lines a second and a half later.
        // The line lit stays lit till its new time, not the one before it again for a moment.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        val heard = CompletableDeferred<Sung?>()
        singing.answer = { heard.await() }
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 1 }
        passes(2_300)
        // The line now sung is lit: the wait for it runs on the real clock.
        waitUntil { "lit 1 " in TimingLog.lastMinute() }
        TimingLog.clear()
        heard.complete(sungAt(times, times.map { 1_500L }))
        waitUntil { model().uiState.lyricLines[1].timeMs == 6_500L }
        composeRule.waitForIdle()
        passes(1_000)
        assertFalse(TimingLog.lastMinute(), Regex("lit 0 ").containsMatchIn(TimingLog.lastMinute()))
    }

    @Test
    fun pickedUpPartwayWordsHeardFarOffOfferToReportOnce() {
        heardFarOffTwice(FAR_MS)
        waitUntil { model().uiState.reportOffer }
        composeRule.waitForIdle()
        assertEquals(setOf(SongProblem.SUNG_OFF), model().uiState.reportOfferFor)
        assertTrue(offerShown())
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
    }

    @Test
    fun pickedUpPartwayWordsHeardFarOffAreReportedOnceWhenAllowed() {
        TimingLog.clear()
        prefs().edit().putBoolean("auto_reports", true).commit()
        heardFarOffTwice(FAR_MS)
        waitUntil { fake.requestedUrls.contains(ReportSender.URL) }
        val sent = org.json.JSONObject(fake.requestBodies[fake.requestedUrls.indexOf(ReportSender.URL)])
        assertEquals("sung_off", sent.getString("problem"))
        val song = sent.getJSONObject("song")
        assertFalse(song.has("timing"))
        val log = song.getString("log")
        assertTrue(log, log.contains("lines in time"))
        // Kept by then, so placing them again from the same while sends nothing more.
        waitUntil { prefs().contains("lyrics_sync") }
        composeRule.waitForIdle()
        assertEquals(1, fake.requestedUrls.count { it == ReportSender.URL })
    }

    @Test
    fun pickedUpPartwayWordsHeardJustFarEnoughOffAreKeptAndOffered() {
        heardFarOffTwice(MainViewModel.WORDS_OFF_MS)
        waitUntil { model().uiState.reportOffer }
        assertTrue(prefs().contains("lyrics_sync"))
    }

    @Test
    fun theTimingNotesSayHowFarOffTheSingingWasHeardAndHowSureTheClockWas() {
        TimingLog.clear()
        heardFarOffTwice(FAR_MS)
        waitUntil { prefs().contains("lyrics_sync") }
        assertTrue(TimingLog.lastMinute(), TimingLog.lastMinute().contains("sung $FAR_MS off, clock sure false, first true"))
    }

    @Test
    fun theTimingNotesSayWhenNothingHeardCouldBePlaced() {
        TimingLog.clear()
        singing.answer = { Sung(SystemClock.elapsedRealtime(), listOf(HeardWord("la", 500))) }
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears >= 1 && TimingLog.lastMinute().contains("sung nothing to place, clock sure false") }
        assertFalse(TimingLog.lastMinute().contains(" off, clock sure"))
    }

    @Test
    fun pickedUpPartwayWordsHeardALittleOffDontOfferToReport() {
        singing.answer = { sung(MainViewModel.SUNG_OFF_MS) }
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[2].timeMs == 6_000 + MainViewModel.SUNG_OFF_MS }
        composeRule.waitForIdle()
        assertFalse(model().uiState.reportOffer)
    }

    @Test
    fun wordsHeardALittleOffDontOfferToReport() {
        singing.answer = { sung(-600) }
        openLyrics()
        plays(positionMs = 1_000)
        waitUntil { model().uiState.lyricsSynced }
        composeRule.waitForIdle()
        assertFalse(model().uiState.reportOffer)
        assertFalse(offerShown())
    }

    @Test
    fun wordsHeardALittleOffArentReported() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        singing.answer = { sung(-600) }
        openLyrics()
        plays(positionMs = 1_000)
        waitUntil { model().uiState.lyricsSynced }
        assertFalse(fake.requestedUrls.contains(ReportSender.URL))
    }

    @Test
    fun wordsSungAboutWhenTheyreTimedStayPut() {
        singing.answer = { sung(SungAlignment.HEARD_LATE_MS / 3) }
        openLyrics()
        plays()
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertFalse(model().uiState.lyricsSynced)
        // And they're not heard again either.
        plays(positionMs = 100)
        assertEquals(1, singing.hears)
    }

    @Test
    fun wordsAQuarterOfASecondOffMove() {
        singing.answer = { sung(250) }
        openLyrics()
        plays()
        waitUntil { model().uiState.lyricsSynced }
        assertEquals(250L, firstLineMs())
    }

    @Test
    fun wherePastTheStartTheyWaitForLinesToBeSung() {
        // A long break after the third line: at 7 seconds, the next lines sung are 23 seconds on, heard
        // from a second and a half before, which at double speed is 10.75 seconds away.
        lyricsTimedFor(null, times = listOf(0, 3, 6, 30, 33, 36))
        openLyrics()
        plays(positionMs = 7_000, speed = 2f)
        assertEquals(0, singing.hears)
        passes(10_650)
        assertEquals(0, singing.hears)
        passes(200)
        assertEquals(1, singing.hears)
    }

    @Test
    fun notWhereTooFewLinesAreLeftToHear() {
        // After the break only two lines are sung within what's heard, less its last 3 seconds.
        lyricsTimedFor(null, times = listOf(0, 3, 6, 30, 33, 47))
        openLyrics()
        plays(positionMs = 7_000, speed = 2f)
        passes(15_000)
        assertEquals(0, singing.hears)
    }

    @Test
    fun aLineThatSaysWhichItIsIsHeardAloneWithNoLinesNearIt() {
        // Every line is too far from the next to hear three, but the third has five words no other line shares:
        // heard from a second and a half before its 40 seconds, which at double speed is 15.75 seconds away.
        lyricsTimedFor(null, times = listOf(0, 3, 40, 60, 80, 99))
        openLyrics()
        plays(positionMs = 7_000, speed = 2f)
        passes(15_650)
        assertEquals(0, singing.hears)
        passes(200)
        assertEquals(1, singing.hears)
    }

    @Test
    fun aLineThatSaysWhichItIsIsHeardAloneJustBeforeItStarts() {
        // Not right at its 40 seconds: the first change to the screen can ask for a frame, and Robolectric moves the clock 15 ms for that.
        lyricsTimedFor(null, times = listOf(0, 3, 40, 60, 80, 99))
        openLyrics()
        plays(positionMs = 39_900)
        passes(1)
        assertEquals(1, singing.hears)
    }

    @Test
    fun aLineAlreadyBegunIsntHeardAlone() {
        // Only this line says which it is, and its first words are gone, so nothing's left to hear.
        lyricsTimedFor(null, times = listOf(0, 3, 40, 60, 80, 99))
        openLyrics()
        plays(positionMs = 40_100)
        passes(60_000)
        assertEquals(0, singing.hears)
    }

    @Test
    fun aSongMovedALittleWhileHeardIsStillPlaced() {
        val heard = CompletableDeferred<Sung?>()
        singing.answer = { heard.await() }
        openLyrics()
        plays()
        waitUntil { singing.hears == 1 }
        val words = sung(SHIFT_MS)
        // 0.3 seconds on from where it was: the app's clock catching up, not the song moved.
        plays(positionMs = 300 + SystemClock.elapsedRealtime() - startedAt)
        heard.complete(words)
        waitUntil { model().uiState.lyricsSynced }
        assertEquals(SHIFT_MS, firstLineMs())
    }

    @Test
    fun closingTheWordsStopsHearingThem() {
        singing.answer = { CompletableDeferred<Sung?>().await() }
        openLyrics()
        plays()
        waitUntil { singing.hears == 1 }
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { singing.stopped == 1 }
    }

    @Test
    fun onlyWhileTheAppPlaysTheSongWithTheMicrophoneAllowed() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        singing.answer = { sung(SHIFT_MS) }
        openLyrics()
        plays()
        assertEquals(0, singing.hears)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        plays(playing = false)
        assertEquals(0, singing.hears)
        plays(positionMs = 100)
        waitUntil { model().uiState.lyricsSynced }
        assertEquals(1, singing.hears)
    }

    @Test
    fun notPastTheLastLinesWhereNothingsLeftToHear() {
        openLyrics()
        plays(positionMs = 14_000)
        assertEquals(0, singing.hears)
    }

    @Test
    fun wordsPutInTimeByHandAreLeftAlone() {
        singing.answer = { sung(SHIFT_MS) }
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        plays()
        assertEquals(0, singing.hears)
        assertEquals(500L, firstLineMs())
    }

    @Test
    fun wordsPutInTimeByHandWhileHearingAreLeftAlone() {
        val heard = CompletableDeferred<Sung?>()
        singing.answer = { heard.await() }
        openLyrics()
        plays()
        waitUntil { singing.hears == 1 }
        composeRule.runOnIdle { model().nudgeSync(500) }
        heard.complete(sung(SHIFT_MS))
        composeRule.waitForIdle()
        assertEquals(500L, firstLineMs())
    }

    @Test
    fun aSongMovedWhileHeardIsHeardAgain() {
        val heard = CompletableDeferred<Sung?>()
        singing.answer = { heard.await() }
        openLyrics()
        plays()
        waitUntil { singing.hears == 1 }
        // Moved back to its start: what was heard can't be placed any more.
        plays(positionMs = 2_000)
        heard.complete(sung(SHIFT_MS))
        composeRule.waitForIdle()
        assertFalse(model().uiState.lyricsSynced)
        singing.answer = { sung(SHIFT_MS) }
        plays(positionMs = 100)
        waitUntil { model().uiState.lyricsSynced }
        assertEquals(2, singing.hears)
    }

    @Test
    fun aSongPausedWhileHeardIsntPlaced() {
        val heard = CompletableDeferred<Sung?>()
        singing.answer = { heard.await() }
        openLyrics()
        plays()
        waitUntil { singing.hears == 1 }
        plays(playing = false)
        heard.complete(sung(SHIFT_MS))
        composeRule.waitForIdle()
        assertFalse(model().uiState.lyricsSynced)
    }

    @Test
    fun nothingHeardIsTriedThreeTimes() {
        openLyrics()
        repeat(5) { plays(positionMs = it.toLong()) }
        waitUntil { singing.hears == 3 }
        composeRule.waitForIdle()
        assertEquals(3, singing.hears)
        assertFalse(model().uiState.lyricsSynced)
        // Moved elsewhere, it's tried three more times from there.
        repeat(5) { plays(positionMs = 5_000L + it) }
        waitUntil { singing.hears == 6 }
        composeRule.waitForIdle()
        assertEquals(6, singing.hears)
    }

    @Test
    fun pickedUpPartwayItHearsOnAsTheWordsDriftFurtherOff() {
        // Timed for another take: a second off at first, and two and a half further on.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, listOf(1_000L, 1_000L, 1_000L, 2_500L, 2_500L, 2_500L)) }
        openLyrics()
        plays(positionMs = 1_800)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[3].timeMs == 31_000L }
        // The song just plays on, and where the words are sung next, they're heard again, for this time only too.
        passes(30_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[3].timeMs == 32_500L }
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 32_500L, 35_500L, 38_500L), model().uiState.lyricLines.map { it.timeMs })
        assertFalse(model().uiState.lyricsSynced)
        assertFalse(prefs().contains("sung_heard"))
    }

    @Test
    fun noSingingHeardIsTriedThreeTimesToo() {
        // Words of another song, or a song whisper.cpp can't make out: what's heard never matches.
        singing.answer = { Sung(SystemClock.elapsedRealtime(), listOf(HeardWord("[MUSIC]", 380))) }
        openLyrics()
        repeat(5) { plays(positionMs = it.toLong()) }
        // Heard straight on, a while at a time.
        waitUntil { singing.hears == 1 }
        // Music alone where the words are timed says they may be timed into an intro.
        assertFalse(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        passes(20_000)
        waitUntil { singing.hears == 2 }
        passes(20_000)
        waitUntil { singing.hears == 3 }
        passes(60_000)
        assertEquals(3, singing.hears)
        assertFalse(model().uiState.lyricsSynced)
        // Given up on, the lines are shown sung as they're timed again, as nothing says they're wrong.
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
    }

    @Test
    fun nothingMadeOutAtAllLeavesTheTimingBe() {
        // whisper.cpp makes out nothing in a while, not even music: that says nothing of where the words are timed.
        singing.answer = { Sung(SystemClock.elapsedRealtime(), emptyList()) }
        openLyrics()
        repeat(5) { plays(positionMs = it.toLong()) }
        // Heard on a few times, the lines are shown sung as they're timed all the while.
        waitUntil { singing.hears == 1 }
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        passes(20_000)
        waitUntil { singing.hears == 2 }
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        passes(20_000)
        waitUntil { singing.hears == 3 }
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        passes(60_000)
        assertEquals(3, singing.hears)
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
    }

    @Test
    fun outOfSightItStopsAndBackInSightItHearsAgain() {
        val heard = CompletableDeferred<Sung?>()
        singing.answer = { heard.await() }
        openLyrics()
        plays()
        waitUntil { singing.hears == 1 }
        checkNotNull(controller) { "Expected controller to be non-null" }.pause().stop()
        heard.complete(sung(SHIFT_MS))
        composeRule.settle(checkNotNull(controller) { "Expected controller to be non-null" }.get())
        assertFalse(model().uiState.lyricsSynced)
        singing.answer = { sung(SHIFT_MS) }
        checkNotNull(controller) { "Expected controller to be non-null" }.start().resume()
        waitUntil { model().uiState.lyricsSynced }
        assertEquals(2, singing.hears)
    }

    @Test
    fun heardWhileShazamLearnsItsGap() {
        // Timed for the recording playing, with Shazam's gap not known yet: Shazam's place says nothing
        // of where the words are sung, so the singing is heard meanwhile, as the same sound is shared.
        lyricsTimedFor(60)
        val shazam = FakeHearing()
        MainActivity.hearingFactory = { shazam }
        singing.answer = { sung(SHIFT_MS) }
        openLyrics()
        plays(durationMs = 60_000)
        waitUntil { shazam.listens == 1 }
        waitUntil { model().uiState.lyricsSynced }
        assertEquals(1, singing.hears)
        shazam.next.trySend(Heard.Failed(AppError.MICROPHONE))
    }

    @Test
    fun notWhileShazamFindsTheLead() {
        // Timed for another release of the song: Shazam tells how far off it is first.
        lyricsTimedFor(90)
        val shazam = FakeHearing()
        MainActivity.hearingFactory = { shazam }
        singing.answer = { sung(SHIFT_MS) }
        openLyrics()
        plays(durationMs = 60_000)
        waitUntil { shazam.listens == 1 }
        passes(5_000)
        composeRule.waitForIdle()
        assertEquals(0, singing.hears)
        shazam.next.trySend(Heard.Failed(AppError.MICROPHONE))
    }

    @Test
    fun aVideoShazamCantLineUpIsHeardSoItsLinesLight() {
        val shazam = FakeHearing()
        MainActivity.hearingFactory = { shazam }
        singing.answer = { sung(SHIFT_MS) }
        openLyrics()
        plays(video = true)
        // As a sped-up upload of a longer release is: Shazam never hears it anywhere a video could be.
        repeat(6) { shazam.next.trySend(Heard.Nothing) }
        waitUntil { singing.hears > 0 }
        waitUntil { model().uiState.following?.timingKnown == true }
        assertEquals(SHIFT_MS, firstLineMs())
    }

    @Test
    fun onlyAPhoneWithMemoryToSpareHearsTheSinging() {
        fun withMemory(bytes: Long): SingingHearing? {
            val memory = android.app.ActivityManager.MemoryInfo().apply { totalMem = bytes }
            shadowOf(app.getSystemService(android.app.ActivityManager::class.java)).setMemoryInfo(memory)
            return realSinging(app, fake.client())
        }
        // A TV's 2 GB would push the music app out.
        assertEquals(null, withMemory(2L * 1024 * 1024 * 1024))
        assertTrue(withMemory(3L * 1024 * 1024 * 1024) is SingingListener)
    }

    /** Words heard, none of them sung: the sound the model tells of, as it does for music alone. */
    private fun musicAlone() = Sung(SystemClock.elapsedRealtime(), listOf(HeardWord("[MUSIC]", 380)))

    /**
     * What's heard from [songMs] on in the song: the first word of each line, the lines [sungAtMs]
     * (by index, where in the song) as whisper.cpp hears them, a little late. Too few words for a
     * line to be placed by them, but enough lines to tell how far off the words are as a whole.
     */
    private fun heardFirstWords(songMs: Long, sungAtMs: Map<Int, Long>) =
        Sung(startedAt + songMs - startedFromMs, sungAtMs.map { (line, at) -> HeardWord(lines[line].substringBefore(' '), at + SungAlignment.HEARD_LATE_MS - songMs) })

    @Test
    fun singingFoundBeforeIsntDoubtedByWhatsHeardAfter() {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        val last = CompletableDeferred<Sung?>()
        singing.answer = { if (singing.hears == 1) sungAt(times, times.map { 1_000L }) else if (singing.hears < 4) musicAlone() else last.await() }
        openLyrics()
        plays()
        passes(500)
        waitUntil { singing.hears == 1 && model().uiState.lyricsSynced }
        // Music alone for a while, as in a break: the words were sung where they're timed, as were found.
        passes(30_000)
        waitUntil { singing.hears == 2 }
        passes(20_000)
        waitUntil { singing.hears == 3 }
        passes(20_000)
        waitUntil { singing.hears == 4 }
        composeRule.waitForIdle()
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        last.complete(null)
    }

    @Test
    fun aVoiceThatMatchesNothingIsntSingingFoundSoMusicAloneAfterDoubtsTheTiming() {
        // Not the words of the song, or not made out, but sung where they should be: nothing's doubted yet.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        val last = CompletableDeferred<Sung?>()
        singing.answer = { if (singing.hears == 1) Sung(startedAt, listOf(HeardWord("la", 1_000))) else if (singing.hears == 2) musicAlone() else last.await() }
        openLyrics()
        plays()
        passes(500)
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        // Then none's heard where the words are timed, and the singing has still not been found.
        passes(20_000)
        waitUntil { singing.hears == 2 }
        passes(20_000)
        waitUntil { singing.hears == 3 }
        composeRule.waitForIdle()
        assertFalse(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        last.complete(null)
    }

    @Test
    fun aVoiceHeardLaterWhereTheWordsAreTimedLightsThemAgainThoughNoneOfItsWordsAreMadeOut() {
        // The first while heard as music alone though it's sung, as the speech model sometimes hears it:
        // the timing's doubted. Then a voice where the words are timed says they're not timed into an intro.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        val last = CompletableDeferred<Sung?>()
        singing.answer = { if (singing.hears == 1) musicAlone() else if (singing.hears == 2) Sung(SystemClock.elapsedRealtime(), listOf(HeardWord("la", 500))) else last.await() }
        openLyrics()
        plays()
        passes(500)
        waitUntil { singing.hears == 1 && model().uiState.following?.timingKnown == false }
        passes(20_000)
        waitUntil { singing.hears == 2 }
        composeRule.waitForIdle()
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        last.complete(null)
    }

    @Test
    fun aVoiceInTheFirstSecondsOfALaterWhileLightsTheLinesAgainBeforeTheRestOfItIsHeard() {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        val rest = CompletableDeferred<Sung?>()
        singing.early = { if (singing.hears == 1) musicAlone() else Sung(SystemClock.elapsedRealtime(), listOf(HeardWord("la", 500))) }
        singing.answer = { if (singing.hears == 1) musicAlone() else rest.await() }
        openLyrics()
        plays()
        passes(500)
        waitUntil { singing.hears == 1 && model().uiState.following?.timingKnown == false }
        passes(20_000)
        waitUntil { singing.hears == 2 }
        composeRule.waitForIdle()
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        // Only the first while's first seconds are heard again, as only they doubt the words.
        assertEquals(1, singing.agains)
        rest.complete(null)
    }

    @Test
    fun laterWhilesFirstSecondsHeardAsMusicAloneArentHeardAgain() {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        val last = CompletableDeferred<Sung?>()
        singing.early = { if (singing.hears == 1) sungAt(times, times.map { 1_000L }, SingingListener.FIRST_SECONDS) else musicAlone() }
        singing.answer = { if (singing.hears == 1) Sung(startedAt, listOf(HeardWord("la", 1_000))) else last.await() }
        openLyrics()
        plays()
        passes(500)
        waitUntil { singing.hears == 1 }
        passes(20_000)
        waitUntil { singing.hears == 2 }
        composeRule.waitForIdle()
        assertEquals(0, singing.agains)
        assertTrue(checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown)
        last.complete(null)
    }

    /** Sung the first seconds of lines timed at 0, 3, 6, ... seconds, with a voice [voiceMs] into them, told as soon as it's heard. */
    private fun voiceHeardAt(voiceMs: Long): Boolean {
        val rest = CompletableDeferred<Sung?>()
        singing.early = { Sung(startedAt, listOf(HeardWord("la", voiceMs))) }
        singing.answer = { rest.await() }
        openLyrics()
        plays()
        waitUntil { singing.hears == 1 }
        composeRule.waitForIdle()
        return checkNotNull(model().uiState.following) { "Expected model().uiState.following to be non-null" }.timingKnown.also { rest.complete(null) }
    }

    @Test
    fun aVoiceBeforeTheThirdLineIsSungIsInTime() {
        // As the third line, at 6 seconds, starts: the voice is there.
        assertTrue(voiceHeardAt(6_000))
    }

    @Test
    fun aVoiceAfterTheThirdLineIsSungDoubtsTheTimingCountingTheLineSungAsHeardStarts() {
        // The line timed where it's heard from counts, so the third is the one at 6 seconds, not 9.
        assertFalse(voiceHeardAt(7_000))
    }

    @Test
    fun wordsOneHundredAndFiftyMillisecondsOffMoveEvenWhenNoLineIsHeardWordAfterWord() {
        // The second word of each line isn't heard: no line is told by a pair of words, so each is as far off as the whole.
        singing.answer = {
            Sung(startedAt, lines.flatMapIndexed { index, line ->
                line.split(' ').mapIndexed { word, text -> HeardWord(text, index * 3_000L + 150 + SungAlignment.HEARD_LATE_MS + word * 300L) }.filterIndexed { word, _ -> word != 1 }
            })
        }
        openLyrics()
        plays()
        waitUntil { model().uiState.lyricsSynced }
        assertEquals(150L, firstLineMs())
    }

    @Test
    fun linesHeardOnTheirOwnAreShownThereThisTimeOnly() {
        // A second off as a whole, one line a little later, one a little sooner.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, listOf(1_000L, 1_300L, 700L, 1_000L, 1_000L, 1_000L)) }
        openLyrics()
        plays(positionMs = 1_800)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[2].timeMs == 8_700L }
        assertEquals(listOf(3_000L, 6_300L, 8_700L), model().uiState.lyricLines.take(3).map { it.timeMs })
    }

    /** The lines shown once a second hearing tells the first words of the last three, sung [offMs] later than timed; the first three were heard a second late. */
    private fun linesAfterOnlyFirstWordsAreHeard(offMs: Long): List<Long> {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        // Heard from a second and a half before the fourth, which the first hearing says is at 31 seconds.
        singing.answer = {
            if (singing.hears == 1) sungAt(times, listOf(1_000L, 1_000L, 1_000L, 0L, 0L, 0L))
            else heardFirstWords(29_500, (3..5).associateWith { times[it] * 1_000L + offMs })
        }
        openLyrics()
        plays(positionMs = 1_800)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[2].timeMs == 9_000L }
        passes(30_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[3].timeMs != 31_000L }
        return model().uiState.lyricLines.map { it.timeMs }
    }

    @Test
    fun linesHeardOnlyByTheirFirstWordsAreAsFarOffAsTheFirstOfThem() {
        // Two and a half seconds late, a second and a half more than where they were put: the fourth line is the first to be heard.
        // Two seconds more would be words off, which one while alone doesn't move (pickedUpPartwayOneWhileHeardFarOffAfterLinesHeardNearDoesntMoveThem).
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 32_500L, 35_500L, 38_500L), linesAfterOnlyFirstWordsAreHeard(2_500))
    }

    @Test
    fun aLineSungAsTheHearingStartsIsTheFirstHeardOnlyByItsFirstWord() {
        // Sung as it starts, a second and a half before where the fourth was put, so it's the first heard.
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 29_500L, 32_500L, 35_500L), linesAfterOnlyFirstWordsAreHeard(-500))
    }

    @Test
    fun linesHeardOnlyByTheirFirstWordsMoveFromTheFirstLineSungSinceHearingStarted() {
        // A seventh line, so three are heard after one sung just before the second hearing starts.
        lines = lines + "Shadows fall away"
        val times = listOf(2, 5, 8, 30, 33, 36, 39)
        lyricsTimedFor(null, times)
        // The first three heard a second late; the second hearing, from 29.5 s, tells only the first words of the last three,
        // 1.8 s sooner than they're shown. The fourth, shown at 31 s, was sung at 29.2 s, before it: the fifth is the first heard.
        singing.answer = {
            if (singing.hears == 1) sungAt(times, times.map { 1_000L })
            else heardFirstWords(29_500, (4..6).associateWith { times[it] * 1_000L - 800 })
        }
        openLyrics()
        plays(positionMs = 1_800)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[2].timeMs == 9_000L }
        passes(30_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[4].timeMs != 34_000L }
        // The fifth is where it's sung, and those between it and the third stretch to meet it.
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 29_416L, 32_200L, 35_200L, 38_200L), model().uiState.lyricLines.map { it.timeMs })
    }

    @Test
    fun hearingStartsNowWhereALineJustPastIsTheFirstOfThoseLeftToHear() {
        // At 1.5 seconds in, the line at 0 is just as far past as hearing starts before a line, so the first words are heard now,
        // not from before the line at 4. A clock that barely moves, so where it is stays put.
        lyricsTimedFor(null, listOf(0, 4, 5, 6, 7, 30))
        openLyrics()
        plays(positionMs = 1_500, speed = 0.001f)
        waitUntil { singing.hears == 1 }
    }

    @Test
    fun startedAgainTheLinesHeardThisTimeAreShownAsTimedAtOnce() {
        val heard = CompletableDeferred<Sung?>()
        singing.answer = { if (singing.hears == 1) sung(SHIFT_MS) else heard.await() }
        openLyrics()
        plays(positionMs = 3_000)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[2].timeMs == 6_000 + SHIFT_MS }
        // The app says it's at the start, which is sure, so what was heard this time is gone while it's heard for good.
        plays(positionMs = 100)
        waitUntil { singing.hears == 2 }
        assertEquals(6_000L, model().uiState.lyricLines[2].timeMs)
        heard.complete(null)
    }

    @Test
    fun theLinesHeardThisTimeAreNotKeptWithTheWords() {
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, times.map { 1_000L }) }
        openLyrics()
        plays(positionMs = 1_800)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[0].timeMs == 3_000L }
        // Closed and opened again, they're as timed till they're heard again.
        singing.answer = { CompletableDeferred<Sung?>().await() }
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.runOnIdle { model().showLyrics() }
        waitUntil { model().uiState.lyricLines.size == lines.size }
        assertEquals(times.map { it * 1_000L }, model().uiState.lyricLines.map { it.timeMs })
    }

    @Test
    fun theLinesHeardThisTimeAreForgottenWhenTheWordsAreLeft() {
        // Two hearings of a song in another take: lines at a second off, and then at two and a half.
        val times = listOf(2, 5, 8, 30, 33, 36)
        lyricsTimedFor(null, times)
        singing.answer = { sungAt(times, listOf(1_000L, 1_000L, 1_000L, 2_500L, 2_500L, 2_500L)) }
        openLyrics()
        plays(positionMs = 1_800)
        waitUntil { singing.hears == 1 && model().uiState.lyricLines[3].timeMs == 31_000L }
        passes(30_000)
        waitUntil { singing.hears == 2 && model().uiState.lyricLines[3].timeMs == 32_500L }
        // Opened again and heard on its first lines only, the rest is no longer where it was heard the other time.
        composeRule.runOnIdle { model().dismissLyrics() }
        val heard = singing.hears
        composeRule.runOnIdle { model().showLyrics() }
        waitUntil { model().uiState.lyricLines.size == lines.size }
        plays(positionMs = 1_800)
        waitUntil { singing.hears > heard && model().uiState.lyricLines[3].timeMs == 31_000L }
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 31_000L, 34_000L, 37_000L), model().uiState.lyricLines.map { it.timeMs })
    }

    /** Hears what a test gives it. */
    private class FakeSinging : SingingHearing {
        val languages: MutableList<String?> = CopyOnWriteArrayList()

        @Volatile
        var answer: suspend () -> Sung? = { null }

        val hears get() = languages.size

        /** How many hearings were stopped before they were done. */
        @Volatile
        var stopped = 0

        /** Whether the speech model is missing, so nothing's heard and it's said to be missed. */
        @Volatile
        var missing = false

        /** How a fetch by hand goes: whether the model's there after. */
        @Volatile
        var fetched: suspend () -> Boolean = { false }

        @Volatile
        var fetches = 0

        override val missesModel get() = missing

        @Volatile
        var noSound = false

        override val heardNoSound get() = noSound

        override suspend fun fetchModel(): Boolean {
            fetches++
            return fetched()
        }

        /** What the first few seconds of each hearing are told to have, when they're told. */
        @Volatile
        var early: (suspend () -> Sung?)? = null

        /** What those seconds heard again a little later have, when asked for; as the first, unless a test says. */
        @Volatile
        var again: (suspend () -> Sung?)? = null

        /** How many times the first seconds were asked to be heard again. */
        @Volatile
        var agains = 0

        override suspend fun hear(language: String?, first: (suspend (Sung) -> Boolean)?): Sung? {
            languages += language
            if (first != null && early?.invoke()?.let { first(it) } == true) {
                agains++
                (again ?: early)?.invoke()?.let { first(it) }
            }
            if (missing) return null
            return try {
                answer()
            } catch (stop: kotlinx.coroutines.CancellationException) {
                stopped++
                throw stop
            }
        }
    }

    /** Shazam, answering what a test sends it. */
    private class FakeHearing : SongHearing {
        val next = Channel<Heard>(Channel.UNLIMITED)

        @Volatile
        var listens = 0

        override suspend fun listen(): Heard {
            listens++
            return next.receive()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SHIFT_MS = -1_300L
        const val FAR_MS = 4_000L
        /** The offer takes no tap for 500 ms from the frame it first shows, so a frame more. */
        const val OFFER_ARMED_MS = 600L
    }
}
