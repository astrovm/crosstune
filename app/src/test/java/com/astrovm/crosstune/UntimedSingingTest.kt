package com.astrovm.crosstune

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
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

/** Words found with no timing, timed by the singing heard in what the phone plays. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h900dp")
class UntimedSingingTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null
    private val playback = FakePlayback().apply { access = true }
    private val singing = FakeSinging()
    private val link = "https://music.youtube.com/watch?v=sPmul8b17AU"
    private val realSinging = MainActivity.singingFactory

    /** Six lines with no timing, sung three seconds apart from two seconds in. */
    private val lines = listOf("Sing to me tonight", "Under the yellow moon", "Every river finds the sea", "Hold the morning light", "Dancing on the water", "Never let it go")

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
        MainActivity.soundTapFactory = { null }
        MainActivity.lyricsBusyPauseMs = 0
        MainActivity.lookAheadLyrics = false
        MainActivity.listenAlongPauseMs = 0
        MainActivity.quietPauseMs = 0
        MainActivity.singingFactory = { _, _ -> singing }
        MainActivity.lyricsLanguageFactory = { LyricsLanguage { "en" } }
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().putBoolean("setup_complete", true)
            .putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false).putBoolean("play_right_away", false).putBoolean("visuals", false).commit()
        listOf("lookups.json", "lyrics.json", "kept_words.json", "heard_untimed.json").forEach { File(app.cacheDir, it).delete() }
        untimedWords()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun tearDown() {
        controller?.destroyAndSettle()
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.checksForUpdates = ::looksForUpdates
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.soundTapFactory = MainActivity.phoneSound::open
        MainActivity.lyricsBusyPauseMs = LYRICS_BUSY_PAUSE_MS
        MainActivity.lookAheadLyrics = true
        MainActivity.listenAlongPauseMs = MainViewModel.LISTEN_ALONG_PAUSE_MS
        MainActivity.quietPauseMs = MainViewModel.LISTEN_ALONG_PAUSE_MS
        MainActivity.singingFactory = realSinging
        MainActivity.lyricsLanguageFactory = null
    }

    private fun model() = ViewModelProvider(controller!!.get())[MainViewModel::class.java]

    private fun state() = model().uiState

    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    /** LRCLIB has the six lines with no timing, for a recording [seconds] long when it says. */
    private fun untimedWords(seconds: Int? = null) {
        val duration = seconds?.let { "\"duration\":$it," }.orEmpty()
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                if (request.url.host == "lrclib.net") """[{"trackName":"Demo","artistName":"Band",$duration"plainLyrics":"${lines.joinToString("\\n")}"}]"""
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
        waitUntil { state().lyrics.isNotEmpty() && !state().isLoadingLyrics }
    }

    /** The music app plays the song from [positionMs], or pauses there; each call is news to Crosstune. */
    private fun plays(positionMs: Long = 0, playing: Boolean = true, durationMs: Long? = SONG_MS, video: Boolean = false) {
        startedAt = SystemClock.elapsedRealtime()
        startedFromMs = positionMs
        playback.playing.value = Following(PlaybackClock(positionMs, startedAt, 1f, playing), "YouTube Music", canSeek = true, video = video, durationMs = durationMs)
        composeRule.waitForIdle()
    }

    /** The first [count] lines heard sung, each at [lineMs], word by word as whisper.cpp hears them: a little late. */
    private fun sung(count: Int = lines.size) = Sung(startedAt, lines.take(count).flatMapIndexed { index, line ->
        var before = 0
        line.split(' ').map { word ->
            HeardWord(word, lineMs(index) + SungTiming.HEARD_LATE_MS + before * SungTiming.LETTER_MS - startedFromMs).also { before += word.length }
        }
    }.filter { it.atMs >= 0 })

    private fun lineMs(index: Int) = 2_000L + index * 3_000L

    private fun lineStarts() = state().lyricLines.map { it.timeMs }

    @Test
    fun linesHeardAreTimedWhereTheyreSungAndTheRestStayUnlit() {
        singing.answer = { sung(count = 4) }
        openLyrics()
        // Nothing heard yet, nothing's timed.
        assertTrue(state().lyricLines.isEmpty())
        assertFalse(state().lyricsTimedByEar)
        plays()
        waitUntil { state().lyricsTimedByEar }
        assertEquals((0..3).map(::lineMs) + List(2) { LyricLine.NOT_HEARD }, lineStarts())
        // The timing log says where each line heard went, by its number.
        assertTrue(TimingLog.lastMinute(), "by ear 0 heard 17 words, 4 lines timed, here 0>2000 1>5000 2>8000 3>11000" in TimingLog.lastMinute())
        // Heard through twice, about, it's heard no more.
        waitUntil { singing.hears == (SONG_MS * MainViewModel.UNTIMED_HEARINGS / (SingingListener.SECONDS * 1000)).toInt() }
        composeRule.waitForIdle()
        assertEquals(4, singing.hears)
        // The words go by the app's clock as it is, with no lead taken off.
        assertEquals(playback.playing.value?.clock, state().following?.clock)
        assertEquals(3, SyncedLyrics.indexAt(state().lyricLines, lineMs(3) + 100))
        // Past the last line heard, sung through, nothing is lit.
        assertEquals(-1, SyncedLyrics.indexAt(state().lyricLines, lineMs(4) + 1_000))
        // Timed by ear, they time themselves: there's nothing to put in time by hand.
        assertTrue(composeRule.onAllNodesWithContentDescription(app.getString(R.string.lyrics_sync)).fetchSemanticsNodes().isEmpty())
        // A line not heard can't be played again.
        composeRule.runOnIdle { model().repeatLine(5) }
        assertEquals(null, state().repeating)
        // A report says the words found weren't timed.
        composeRule.runOnIdle { model().sendReport("") }
        val sent = JSONObject(fake.requestBodies[fake.requestedUrls.indexOfLast { it == ReportSender.URL }])
        assertFalse(sent.getJSONObject("song").getBoolean("timed"))
    }

    @Test
    fun whatsHeardIsKeptForNextTime() {
        singing.answer = { sung() }
        openLyrics()
        plays()
        waitUntil { state().lyricsTimedByEar && singing.hears == 4 }
        controller!!.destroyAndSettle()
        // Opened again, the words are timed from the start, before anything's heard.
        singing.answer = { null }
        openLyrics()
        assertTrue(state().lyricsTimedByEar)
        assertEquals(lines.indices.map(::lineMs), lineStarts())
        // Heard through already, they aren't heard again.
        plays()
        composeRule.waitForIdle()
        assertEquals(4, singing.hears)
    }

    @Test
    fun whatsHeardAsTheAppMovesIsntPlaced() {
        val moved = CompletableDeferred<Unit>()
        singing.answer = {
            val heard = sung()
            moved.await()
            heard
        }
        openLyrics()
        plays()
        waitUntil { singing.hears == 1 }
        // Moved on meanwhile, the words heard can't be placed.
        plays(positionMs = 30_000)
        moved.complete(Unit)
        composeRule.waitForIdle()
        assertTrue(state().lyricLines.isEmpty())
        assertFalse(state().lyricsTimedByEar)
    }

    @Test
    fun withNoSpeechModelItsOffered() {
        singing.missing = true
        openLyrics()
        plays()
        waitUntil { state().singingModel == ModelOffer.MISSING }
        assertTrue(state().lyricLines.isEmpty())
    }

    @Test
    fun nothingHeardSungIsHeardAFewTimes() {
        singing.answer = { Sung(startedAt, emptyList()) }
        openLyrics()
        plays()
        waitUntil { singing.hears >= MainViewModel.SINGING_TRIES }
        composeRule.waitForIdle()
        assertEquals(MainViewModel.SINGING_TRIES, singing.hears)
        assertTrue(state().lyricLines.isEmpty())
    }

    @Test
    fun withNoLengthSaidItsHeardAsLongAsWhatsKept() {
        singing.answer = { sung() }
        openLyrics()
        plays(durationMs = null)
        waitUntil { singing.hears >= HeardSongs.MAX_STRETCHES }
        composeRule.waitForIdle()
        assertEquals(HeardSongs.MAX_STRETCHES, singing.hears)
        // Timed by how far was heard, without a length.
        assertEquals(lines.indices.map(::lineMs), lineStarts())
    }

    @Test
    fun aPausedSongIsntHeard() {
        singing.answer = { sung() }
        openLyrics()
        plays(playing = false)
        composeRule.waitForIdle()
        assertEquals(0, singing.hears)
    }

    /** whisper.cpp, answering what a test says. */
    private class FakeSinging : SingingHearing {
        val languages: MutableList<String?> = CopyOnWriteArrayList()

        @Volatile
        var answer: suspend () -> Sung? = { null }

        @Volatile
        var missing = false

        val hears get() = languages.size

        override val missesModel get() = missing
        override val heardNoSound = false

        override suspend fun fetchModel() = false

        override suspend fun hear(language: String?, first: (suspend (Sung) -> Boolean)?): Sung? {
            languages += language
            return if (missing) null else answer()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SONG_MS = 40_000L
    }
}
