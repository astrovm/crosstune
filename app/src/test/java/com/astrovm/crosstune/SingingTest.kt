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

    /** When the music app's clock started, which is when the singing was heard from too. */
    private var startedAt = 0L

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

    /** LRCLIB has the six lines, timed for a recording [seconds] long, when it says. */
    private fun lyricsTimedFor(seconds: Int?) {
        val synced = lines.mapIndexed { index, line -> "[00:%02d.00] %s".format(index * 3, line) }.joinToString("\n")
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
    private fun plays(positionMs: Long = 0, playing: Boolean = true, durationMs: Long? = null) {
        startedAt = SystemClock.elapsedRealtime()
        playback.playing.value = Following(PlaybackClock(positionMs, startedAt, playing = playing), "YouTube Music", canSeek = true, durationMs = durationMs)
        composeRule.waitForIdle()
    }

    /**
     * Each line heard sung [shiftMs] later than timed, word by word, as whisper.cpp hears them: a
     * little late. Heard from the start, so nothing sung before it.
     */
    private fun sung(shiftMs: Long) = Sung(startedAt, lines.flatMapIndexed { index, line ->
        line.split(' ').mapIndexed { word, text -> HeardWord("$text,", index * 3_000L + shiftMs + SungAlignment.HEARD_LATE_MS + word * 300L) }
    }.filter { it.atMs >= 0 })

    private fun firstLineMs() = model().uiState.lyricLines.first().timeMs

    @Test
    fun theWordsMoveToTheSingingHeardOnceForTheSong() {
        singing.answer = { sung(SHIFT_MS) }
        openLyrics()
        plays()
        waitUntil { model().uiState.lyricsSynced }
        assertEquals(SHIFT_MS, firstLineMs())
        // Heard in the words' language, as whisper.cpp names it.
        assertEquals(listOf<String?>("es"), singing.languages)
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

        override suspend fun hear(language: String?): Sung? {
            languages += language
            return answer()
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
        override fun finish() = Unit
        override fun close() = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SHIFT_MS = -1_300L
    }
}
