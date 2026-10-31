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
import androidx.compose.ui.test.hasContentDescription
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
import java.util.concurrent.CopyOnWriteArrayList

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

    /** Six lines, three seconds apart, each sung [SHIFT_MS] later than they're timed. */
    private val lines = listOf("Sing to me tonight", "Under the yellow moon", "Every river finds the sea", "Hold the morning light", "Dancing on the water", "Never let it go")

    /** When the music app's clock started, which is when the singing was heard from too, and where in the song. */
    private var startedAt = 0L
    private var startedFromMs = 0L

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
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
        runCatching { controller?.pause()?.stop()?.destroy() }
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
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

    private fun model() = ViewModelProvider(controller!!.get())[MainViewModel::class.java]

    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    /** LRCLIB has the six lines, three seconds apart or at [times] seconds, timed for a recording [seconds] long, when it says. */
    private fun lyricsTimedFor(seconds: Int?, times: List<Int> = lines.indices.map { it * 3 }) {
        val synced = lines.zip(times) { line, at -> "[00:%02d.00] %s".format(at, line) }.joinToString("\n")
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
    private fun plays(positionMs: Long = 0, playing: Boolean = true, durationMs: Long? = null, speed: Float = 1f) {
        startedAt = SystemClock.elapsedRealtime()
        startedFromMs = positionMs
        playback.playing.value = Following(PlaybackClock(positionMs, startedAt, speed, playing), "YouTube Music", canSeek = true, durationMs = durationMs)
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
        controller!!.pause().stop().destroy()
        openLyrics()
        plays(positionMs = 200)
        composeRule.waitForIdle()
        assertEquals(1, singing.hears)
    }

    private fun shown(id: Int) = composeRule.onAllNodesWithText(app.getString(id)).fetchSemanticsNodes().isNotEmpty()

    /** Where the words follow the song to be now. */
    private fun followedAt() = model().uiState.following!!.clock.positionAt(SystemClock.elapsedRealtime())

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
        // Only the first while is told so early.
        singing.early = null
        rest.complete(sungAt(times, times.map { 1_000L }))
        waitUntil { model().uiState.following?.timingKnown == true }
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
        assertTrue(model().uiState.following!!.timingKnown)
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
        assertTrue(model().uiState.following!!.timingKnown)
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
        assertTrue(model().uiState.following!!.timingKnown)
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
        assertTrue(model().uiState.following!!.timingKnown)
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
    fun wordsHeardOffAreReportedWhenProblemsAreButNeverAskedAbout() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        // A second off is already off.
        singing.answer = { sung(-MainViewModel.SUNG_OFF_MS) }
        openLyrics()
        plays(positionMs = 1_000)
        waitUntil { fake.requestedUrls.contains(ReportSender.URL) }
        val sent = org.json.JSONObject(fake.requestBodies[fake.requestedUrls.indexOf(ReportSender.URL)])
        assertEquals("sung_off", sent.getString("problem"))
        // Moved by the singing, not by hand, so leaving them sends nothing more.
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(1, fake.requestedUrls.count { it == ReportSender.URL })
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
        passes(20_000)
        waitUntil { singing.hears == 2 }
        passes(20_000)
        waitUntil { singing.hears == 3 }
        passes(60_000)
        assertEquals(3, singing.hears)
        assertFalse(model().uiState.lyricsSynced)
        // Given up on, the lines are shown sung as they're timed again, as nothing says they're wrong.
        assertTrue(model().uiState.following!!.timingKnown)
    }

    @Test
    fun outOfSightItStopsAndBackInSightItHearsAgain() {
        val heard = CompletableDeferred<Sung?>()
        singing.answer = { heard.await() }
        openLyrics()
        plays()
        waitUntil { singing.hears == 1 }
        controller!!.pause().stop()
        heard.complete(sung(SHIFT_MS))
        composeRule.waitForIdle()
        assertFalse(model().uiState.lyricsSynced)
        singing.answer = { sung(SHIFT_MS) }
        controller!!.start().resume()
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
        assertTrue(model().uiState.following!!.timingKnown)
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
        assertTrue(model().uiState.following!!.timingKnown)
        // Then none's heard where the words are timed, and the singing has still not been found.
        passes(20_000)
        waitUntil { singing.hears == 2 }
        passes(20_000)
        waitUntil { singing.hears == 3 }
        composeRule.waitForIdle()
        assertFalse(model().uiState.following!!.timingKnown)
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
        return model().uiState.following!!.timingKnown.also { rest.complete(null) }
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
        // Three seconds late, two more than where they were put: the fourth line is the first to be heard.
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 33_000L, 36_000L, 39_000L), linesAfterOnlyFirstWordsAreHeard(3_000))
    }

    @Test
    fun aLineSungAsTheHearingStartsIsTheFirstHeardOnlyByItsFirstWord() {
        // Sung as it starts, a second and a half before where the fourth was put, so it's the first heard.
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 29_500L, 32_500L, 35_500L), linesAfterOnlyFirstWordsAreHeard(-500))
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

        override suspend fun fetchModel(): Boolean {
            fetches++
            return fetched()
        }

        /** What the first few seconds of each hearing are told to have, when they're told. */
        @Volatile
        var early: (suspend () -> Sung?)? = null

        override suspend fun hear(language: String?, first: (suspend (Sung) -> Unit)?): Sung? {
            languages += language
            if (first != null) early?.invoke()?.let { first(it) }
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

    /** projectM, when it can't start. */
    private object NoMilkdrop : Milkdrop {
        override fun open(width: Int, height: Int) = false
        override fun size(width: Int, height: Int) = Unit
        override fun show(preset: String, smooth: Boolean) = false
        override fun hear(samples: ByteArray, count: Int) = Unit
        override fun draw() = Unit
        override fun darken(shade: Float) = Unit
        override fun finish() = Unit
        override fun close() = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SHIFT_MS = -1_300L
    }
}
