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
        MainActivity.soundTapFactory = { OutputMixTap.open() }
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
        // A long break after the third line: at 7 seconds, the next lines sung are 23 seconds on,
        // which at double speed is 11.5 seconds away.
        lyricsTimedFor(null, times = listOf(0, 3, 6, 30, 33, 36))
        openLyrics()
        plays(positionMs = 7_000, speed = 2f)
        assertEquals(0, singing.hears)
        passes(11_400)
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
    fun notWhileShazamHearsThePhonesSoundThenRightAfter() {
        // Timed for the recording playing, with Shazam's gap not known yet: Shazam hears it first.
        lyricsTimedFor(60)
        val shazam = FakeHearing()
        MainActivity.hearingFactory = { shazam }
        singing.answer = { sung(SHIFT_MS) }
        openLyrics()
        plays(durationMs = 60_000)
        waitUntil { shazam.listens == 1 }
        assertEquals(0, singing.hears)
        // Done with it, here because nothing can be heard with.
        shazam.next.trySend(Heard.Failed(AppError.MICROPHONE))
        waitUntil { model().uiState.lyricsSynced }
        assertEquals(1, singing.hears)
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

        override suspend fun hear(language: String?): Sung? {
            languages += language
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
