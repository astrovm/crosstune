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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
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

/**
 * Plays a real recording's words through the app as it would on a phone, in a moment rather than
 * the song's length: what whisper.cpp heard in each stretch of it, worked out beforehand by
 * scripts/syncreplay, comes back after the time hearing takes, and when each line is shown is
 * written down, for the script to hold against when it's really sung. Only when the script asks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h900dp")
class SyncReplayTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null
    private val playback = FakePlayback().apply { access = true }
    private val realSinging = MainActivity.singingFactory

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
        MainActivity.lyricsLanguageFactory = { LyricsLanguage { "en" } }
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
        MainActivity.singingFactory = realSinging
        MainActivity.lyricsLanguageFactory = null
    }

    @Test
    fun replay() {
        // Run from the app's own folder, the replay's where the whole project builds.
        val dir = listOf(File("../build/syncreplay"), File("build/syncreplay")).firstOrNull { File(it, "replay.json").exists() }
        assumeTrue("Only when scripts/syncreplay asks", dir != null)
        if (dir == null) return
        val asked = JSONObject(File(dir, "replay.json").readText())
        val song = asked.getString("song")
        val fromMs = asked.getLong("from")
        workMs = asked.optLong("work", WORK_MS)
        val lyrics = JSONArray(File(dir, "$song.lyrics.json").readText()).let { list -> (0 until list.length()).map { list.getJSONArray(it).let { line -> line.getLong(0) to line.getString(1) } } }
        // What was heard in each stretch, by how long it is in seconds and where it starts.
        val heard = listOf(SingingListener.SECONDS, SingingListener.FIRST_SECONDS).associateWith { seconds ->
            JSONObject(File(dir, "$song.heard$seconds.json").readText()).let { windows ->
                windows.keys().asSequence().associate { start -> start.toLong() to windows.getJSONArray(start).let { words -> (0 until words.length()).map { words.getJSONArray(it).let { word -> word.getLong(0) to word.getString(1) } } } }
            }
        }
        val lengthMs = heard.getValue(SingingListener.SECONDS).keys.max() + SingingListener.SECONDS * 1000L
        MainActivity.singingFactory = { _, _ -> Replayed(heard) }
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear()
            .putBoolean("setup_complete", true).putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false)
            .putBoolean("play_right_away", false).putBoolean("visuals", false).commit()
        val lrc = lyrics.joinToString("\n") { (ms, text) -> "[%02d:%02d.%02d] %s".format(ms / 60_000, ms / 1000 % 60, ms % 1000 / 10, text.replace("\"", "\\\"")) }
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                if (request.url.host == "lrclib.net") """[{"trackName":"Demo","artistName":"Band","duration":${lengthMs / 1000},"syncedLyrics":"${lrc.replace("\n", "\\n")}"}]"""
                else """{"title":"Demo","author_name":"Band"}"""
            )
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=sPmul8b17AU")).setClass(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        val button = hasText(app.getString(R.string.lyrics_button)) or hasContentDescription(app.getString(R.string.lyrics_button))
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); composeRule.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(button)[0].performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); model().uiState.lyricLines.isNotEmpty() }
        val startedAt = SystemClock.elapsedRealtime()
        playback.playing.value = Following(PlaybackClock(fromMs, startedAt), "YouTube Music", canSeek = true, durationMs = lengthMs)
        composeRule.waitForIdle()
        // Each line, by its text and the time it's shown first after the one before, where the song is then.
        val shown = JSONArray()
        // When the lines stop or start being shown sung, as nothing says where they are.
        val known = JSONArray()
        var lastKnown: Boolean? = null
        // Where the lines are placed, in the song, each time that changes.
        val placed = JSONArray()
        var lastPlaced: List<Long>? = null
        var last = -1
        var atMs = fromMs
        while (atMs < lengthMs) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(STEP_MS))
            atMs = fromMs + SystemClock.elapsedRealtime() - startedAt
            val state = model().uiState
            if (state.following?.timingKnown != lastKnown) known.put(JSONObject().put("at", atMs).put("known", state.following?.timingKnown))
            lastKnown = state.following?.timingKnown
            val following = state.following?.takeIf { it.timingKnown } ?: continue
            val now = SystemClock.elapsedRealtime()
            // In the song, by the music app's own clock: the lines as shown, less how far the app's clock is put right.
            val off = playback.playing.value!!.clock.positionAt(now) - following.clock.positionAt(now)
            val times = state.lyricLines.map { it.timeMs + off }
            if (times != lastPlaced) placed.put(JSONObject().put("at", atMs).put("times", JSONArray(times)))
            lastPlaced = times
            val index = SyncedLyrics.indexAt(state.lyricLines, following.clock.positionAt(now))
            if (index != last && index >= 0) shown.put(JSONObject().put("line", index).put("text", state.lyricLines[index].text).put("at", atMs))
            last = index
        }
        val lines = JSONArray(model().uiState.lyricLines.map { it.text })
        File(dir, "$song.from$fromMs.shown.json").writeText(JSONObject().put("lines", lines).put("shown", shown).put("placed", placed).put("known", known).put("heard", heardLog).toString())
    }

    private fun model() = ViewModelProvider(controller!!.get())[MainViewModel::class.java]

    /** How long the phone takes to work out the words in what it heard, after hearing it. */
    private var workMs = WORK_MS

    /** Each stretch heard: where the song was, which stretch heard beforehand stood for it, and what was in it. */
    private val heardLog = JSONArray()

    /**
     * What whisper.cpp heard in the seconds asked for from where the song is when it's asked, from the
     * stretch heard beforehand that starts nearest, given back once hearing it would be done.
     */
    private inner class Replayed(private val heard: Map<Int, Map<Long, List<Pair<Long, String>>>>) : SingingHearing {
        override val missesModel = false

        override suspend fun fetchModel() = true

        override suspend fun hear(language: String?, first: (suspend (Sung) -> Unit)?): Sung? = coroutineScope {
            val at = SystemClock.elapsedRealtime()
            val from = playback.playing.value!!.clock.positionAt(at)
            if (first != null) launch { first(heard(SingingListener.FIRST_SECONDS, at, from)) }
            heard(SingingListener.SECONDS, at, from)
        }

        /** What was heard in the [seconds] from [from] in the song, at [at], once it's worked out. */
        private suspend fun heard(seconds: Int, at: Long, from: Long): Sung {
            val stretches = heard.getValue(seconds)
            val start = stretches.keys.minBy { kotlin.math.abs(it - from) }
            // Working out the words takes as long however much there is: the model always works on 30 seconds.
            delay(seconds * 1000L + workMs)
            heardLog.put(JSONObject().put("from", from).put("start", start).put("seconds", seconds).put("words", stretches.getValue(start).joinToString(" ") { it.second.trim() }))
            return Sung(at, stretches.getValue(start).filter { it.first >= 0 }.map { (ms, text) -> HeardWord(text.trim(), ms + start - from) }.filter { it.text.isNotEmpty() })
        }
    }

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
        const val STEP_MS = 100L

        /** About how long a phone takes to work out the words in what it heard, after hearing it. */
        const val WORK_MS = 2_500L
    }
}
