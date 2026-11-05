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
import kotlinx.coroutines.awaitCancellation
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
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * Plays a real recording's words through the app as it would on a phone, in a moment rather than
 * the song's length: what whisper.cpp heard in each stretch of it, worked out beforehand by
 * scripts/syncreplay, comes back after the time hearing takes, and when each line is shown is
 * written down, for the script to hold against when it's really sung. Only when the script asks.
 * The music app's clock may be a little behind what it plays, as YouTube Music's is, by more or less
 * each time; asked to, the song plays a second time, with what was learnt the first kept, and Shazam
 * hears it, faster or slower than the recording it matches, as it does a sped-up upload. Several
 * songs asked for at once are each played in an app of their own.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h900dp")
class SyncReplayTest(private val name: String, private val index: Int) {

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
        MainActivity.hearingFactory = null
        MainActivity.singingFactory = realSinging
        MainActivity.lyricsLanguageFactory = null
    }

    @Test
    fun replay() {
        val dir = replays()
        assumeTrue("$name: only when scripts/syncreplay asks", dir != null && index >= 0)
        if (dir == null) return
        val asked = JSONObject(File(dir, "replay.json").readText()).let { it.optJSONArray("songs")?.getJSONObject(index) ?: it }
        val song = asked.getString("song")
        val fromMs = asked.getLong("from")
        playerSpeed = asked.optDouble("playerSpeed", 1.0).toFloat()
        val fingerprints = asked.optJSONObject("fingerprints")
        workMs = asked.optLong("work", WORK_MS)
        workedOut = asked.optJSONArray("works")?.let { works -> (0 until works.length()).map { works.getLong(it) } }.orEmpty()
        whilesFrom = asked.optJSONArray("whiles")?.let { whiles -> (0 until whiles.length()).map { whiles.getLong(it) } }.orEmpty()
        val behind = listOf(asked.optLong("behind", 0L)) + (if (asked.has("behindAgain")) listOf(asked.getLong("behindAgain")) else emptyList())
        // The words as the app really found them, when kept, over those prep was given.
        val kept = File(dir, "$song.found.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        val lyrics = kept?.getJSONArray("lines")?.takeIf { it.length() > 0 }?.let { list -> (0 until list.length()).map { list.getJSONArray(it).let { line -> line.getLong(0) to line.getString(1) } } }
            ?: JSONArray(File(dir, "$song.lyrics.json").readText()).let { list -> (0 until list.length()).map { list.getJSONArray(it).let { line -> line.getLong(0) to line.getString(1) } } }
        // What was heard in each stretch, by how long it is in seconds and where it starts.
        val heard = listOf(SingingListener.SECONDS, SingingListener.FIRST_SECONDS).associateWith { seconds ->
            JSONObject(File(dir, "$song.heard$seconds${asked.optString("heard")}.json").readText()).let { windows ->
                windows.keys().asSequence().associate { start -> start.toLong() to windows.getJSONArray(start).let { words -> (0 until words.length()).map { words.getJSONArray(it).let { word -> word.getLong(0) to word.getString(1) } } } }
            }
        }
        val lengthMs = heard.getValue(SingingListener.SECONDS).keys.max() + SingingListener.SECONDS * 1000L
        MainActivity.singingFactory = { _, _ -> Replayed(heard) }
        if (asked.has("skew")) MainActivity.hearingFactory = { Shazam(asked.getDouble("skew"), asked.optLong("shazamAhead")) }
        // The words may be timed for another release, as long as LRCLIB says, or it may not say.
        val wordsMs = (fingerprints ?: asked).optLong("wordsLength", lengthMs)
        video = asked.optBoolean("video")
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear()
            .putBoolean("setup_complete", true).putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false)
            .putBoolean("play_right_away", false).putBoolean("visuals", false).commit()
        // Words found without timing, -1 for each line's time, come as such: nothing's heard for them, so their lines stay dark.
        val words = if (lyrics.all { it.first < 0 }) JSONObject().put("plainLyrics", lyrics.joinToString("\n") { it.second })
        else JSONObject().put("syncedLyrics", lyrics.joinToString("\n") { (ms, text) -> "[%02d:%02d.%02d] %s".format(ms / 60_000, ms / 1000 % 60, ms % 1000 / 10, text) })
        words.put("trackName", "Demo").put("artistName", "Band")
        if (wordsMs > 0) words.put("duration", wordsMs / 1000)
        val found = JSONArray().put(words).toString()
        // Words timed word by word, as the app found them, only come from NetEase.
        val byWord = kept?.let { foundByWord(it, lengthMs) }
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                when {
                    byWord != null && request.url.host == "lrclib.net" -> "[]"
                    byWord != null && request.url.encodedPath.endsWith("/search/get") -> byWord.first
                    byWord != null && request.url.encodedPath.endsWith("/song/lyric") -> byWord.second
                    request.url.host == "lrclib.net" -> found
                    else -> """{"title":"Demo","author_name":"Band"}"""
                }
            )
        }
        val intent = if (fingerprints == null) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=sPmul8b17AU")).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        } else {
            replayFingerprints(fingerprints, fromMs, lengthMs)
            Intent(Intent.ACTION_MAIN)
        }.setClass(app, MainActivity::class.java)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        if (fingerprints != null) composeRule.runOnIdle { model().listen(lyrics = true) }
        else {
            val button = hasText(app.getString(R.string.lyrics_button)) or hasContentDescription(app.getString(R.string.lyrics_button))
            composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); composeRule.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onAllNodes(button)[0].performScrollTo().performClick()
        }
        // The lookup over, not lines found: words without timing have none, and words timed past the recording's end aren't taken.
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); model().uiState.run { lyricsFor != null && !isLoadingLyrics } }
        behind.forEachIndexed { time, behindMs ->
            val played = play(fromMs, lengthMs, behindMs)
            File(dir, "$song.from$fromMs${if (time < behind.lastIndex) ".first" else ""}.shown.json").writeText(played.toString())
            // Stopped at the end, then played again a while later.
            playback.playing.value = Following(PlaybackClock(lengthMs, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true, video = video, durationMs = lengthMs)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(AGAIN_MS))
        }
    }

    /** The phone's recognition answers, including false occurrences, on its continuous player clock. */
    private var playerSpeed = 1f
    private var fingerprintStartedAt: Long? = null

    private fun replayFingerprints(recorded: JSONObject, fromMs: Long, lengthMs: Long) {
        val startedAt = SystemClock.elapsedRealtime()
        fingerprintStartedAt = startedAt
        val song = MusicMetadata("Demo", "Band", lengthMs = recorded.optLong("wordsLength", lengthMs))
        playback.onThePhone = Heard.Song(song.copy(lengthMs = null, recordingId = "video:replay"), null)
        playback.playing.value = Following(PlaybackClock(fromMs, startedAt, speed = playerSpeed), "YouTube Music", canSeek = true, video = true, durationMs = recorded.optLong("videoLength", lengthMs))
        val initial = recorded.getJSONObject("initial")
        val events = recorded.getJSONArray("events")
        var next = -1
        MainActivity.hearingFactory = { SongHearing {
            val event = if (next < 0) initial else events.optJSONObject(next) ?: awaitCancellation()
            next++
            delay(maxOf(0L, startedAt + ((event.getLong("receivedAt") - fromMs) / playerSpeed).toLong() - SystemClock.elapsedRealtime()))
            Heard.Song(song, null, event.getLong("songMs"), startedAt + ((event.getLong("at") - fromMs) / playerSpeed).toLong(), event.optString("matchId").ifEmpty { null }, speed = event.optDouble("speed", 1.0).toFloat() * playerSpeed)
        } }
    }

    /**
     * NetEase's answers to its search and its lyric request, for words kept as the app keeps them: each
     * line [ms, text, [[ms, word]...], end]. Null for words not timed word by word, which LRCLIB's stand in for.
     */
    private fun foundByWord(kept: JSONObject, lengthMs: Long): Pair<String, String>? {
        val lines = kept.getJSONArray("lines").let { list -> (0 until list.length()).map { list.getJSONArray(it) } }.filter { it.getString(1).isNotBlank() }
        if (lines.isEmpty() || lines.any { it.optJSONArray(2)?.length() ?: 0 == 0 }) return null
        val yrc = lines.joinToString("\n") { line ->
            val words = line.getJSONArray(2).let { list -> (0 until list.length()).map { list.getJSONArray(it) } }
            "[${line.getLong(0)},${line.optLong(3, 0L).let { end -> if (end > 0) end - line.getLong(0) else 0L }}]" + words.joinToString("") { "(${it.getLong(0)},0,0)${it.getString(1)}" }
        }
        val song = JSONObject().put("id", 1).put("name", "Demo").put("duration", lengthMs)
            .put("artists", JSONArray().put(JSONObject().put("name", "Band"))).put("album", JSONObject().put("name", "Demo"))
        return JSONObject().put("result", JSONObject().put("songs", JSONArray().put(song))).toString() to
            JSONObject().put("yrc", JSONObject().put("lyric", yrc)).put("lrc", JSONObject().put("lyric", "")).toString()
    }

    /**
     * The song played from [fromMs] to [lengthMs], the app's clock [behindMs] behind what it plays:
     * each line shown, where the lines were put, and when they were known, all where the song really is.
     */
    private fun play(fromMs: Long, lengthMs: Long, behindMs: Long): JSONObject {
        this.behindMs = behindMs
        heardLog = JSONArray()
        val startedAt = fingerprintStartedAt ?: SystemClock.elapsedRealtime()
        if (fingerprintStartedAt == null) playback.playing.value = Following(PlaybackClock(fromMs, startedAt, speed = playerSpeed), "YouTube Music", canSeek = true, video = video, durationMs = lengthMs)
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
            atMs = fromMs + ((SystemClock.elapsedRealtime() - startedAt) * playerSpeed).toLong()
            val state = model().uiState
            if (state.following?.timingKnown != lastKnown) known.put(JSONObject().put("at", atMs + behindMs).put("known", state.following?.timingKnown))
            lastKnown = state.following?.timingKnown
            val following = state.following?.takeIf { it.timingKnown } ?: continue
            val now = SystemClock.elapsedRealtime()
            // In the song, by the music app's own clock: the lines as shown, less how far the app's clock is put right.
            val appAt = playback.playing.value!!.clock.positionAt(now)
            val heardAt = following.clock.positionAt(now)
            // A video slower than its song has each line where it gets to at that speed, so they stay put as it plays.
            val times = state.lyricLines.map { (appAt + behindMs + (it.timeMs - heardAt) * playerSpeed / following.clock.speed).toLong() / STEP_MS * STEP_MS }
            if (times != lastPlaced) placed.put(JSONObject().put("at", atMs + behindMs).put("times", JSONArray(times)))
            lastPlaced = times
            // Lit as the screen lights it: a moment early, so the eye is there first.
            val index = SyncedLyrics.indexAt(state.lyricLines, following.ahead(state.linesEarlyMs).clock.positionAt(now))
            if (index != last && index >= 0) shown.put(JSONObject().put("line", index).put("text", state.lyricLines[index].text).put("at", atMs + behindMs))
            last = index
        }
        val lines = JSONArray(model().uiState.lyricLines.map { it.text })
        return JSONObject().put("lines", lines).put("shown", shown).put("placed", placed).put("known", known).put("heard", heardLog)
    }

    private fun model() = ViewModelProvider(controller!!.get())[MainViewModel::class.java]

    /** How long the phone takes to work out the words in what it heard, after hearing it. */
    private var workMs = WORK_MS

    /** Whether the music app plays the song's video. */
    private var video = false

    /** How long the phone took over each whole while, one by one, when the replay is held to a phone run. */
    private var workedOut = emptyList<Long>()

    /** Where in the song the phone began each while, when the replay is held to a phone run. */
    private var whilesFrom = emptyList<Long>()

    /** How far the music app's clock is behind what it plays, this time. */
    private var behindMs = 0L

    /** Each stretch heard: where the song was, which stretch heard beforehand stood for it, and what was in it. */
    private var heardLog = JSONArray()

    /**
     * What whisper.cpp heard in the seconds asked for from where the song is when it's asked, from the
     * stretch heard beforehand that starts nearest, given back once hearing it would be done.
     */
    private inner class Replayed(private val heard: Map<Int, Map<Long, List<Pair<Long, String>>>>) : SingingHearing {
        override val missesModel = false
        private var whiles = 0

        override suspend fun fetchModel() = true

        override suspend fun hear(language: String?, first: (suspend (Sung) -> Boolean)?): Sung? = coroutineScope {
            // What's heard is where the song really is, ahead of the app's clock.
            fun position() = playback.playing.value!!.clock.positionAt(SystemClock.elapsedRealtime()) + behindMs
            whilesFrom.getOrNull(whiles)?.let { delay(maxOf(0L, ((it - position()) / playerSpeed).toLong())) }
            val at = SystemClock.elapsedRealtime()
            val from = position()
            if (first != null) launch {
                val again = SingingListener.AGAIN_SECONDS * 1000L
                if (first(heard(SingingListener.FIRST_SECONDS, at, from))) first(heard(SingingListener.FIRST_SECONDS, at + again, from + (again * playerSpeed).toLong()))
            }
            // A while waits for what's still being worked out from before it, so the phone took 3.8 s over one and 27 s over the next.
            heard(SingingListener.SECONDS, at, from, workedOut.getOrElse(whiles++) { workMs })
        }

        /** What was heard in the [seconds] from [from] in the song, at [at], once it's worked out. */
        private suspend fun heard(seconds: Int, at: Long, from: Long, work: Long = workMs): Sung {
            val stretches = heard.getValue(seconds)
            val start = stretches.keys.minBy { kotlin.math.abs(it - from) }
            // Worked out once it's all in: working out the words takes as long however much there is, as the model always works on 30 seconds.
            delay(maxOf(0L, at + (seconds * 1000L / playerSpeed).toLong() + work - SystemClock.elapsedRealtime()))
            heardLog.put(JSONObject().put("from", from).put("start", start).put("seconds", seconds).put("words", stretches.getValue(start).joinToString(" ") { it.second.trim() }))
            return Sung(at, stretches.getValue(start).filter { it.first >= 0 }.map { (ms, text) -> HeardWord(text.trim(), ((ms + start - from) / playerSpeed).toLong()) }.filter { it.text.isNotEmpty() })
        }
    }

    /**
     * Shazam, hearing the song [skew] faster than the recording it matches, where it's [aheadMs] further
     * on at its start, as it hears a sped-up upload of a longer release, a few seconds after it's asked.
     */
    private inner class Shazam(private val skew: Double, private val aheadMs: Long) : SongHearing {
        override suspend fun listen(): Heard {
            delay(SHAZAM_MS)
            val at = SystemClock.elapsedRealtime()
            val playing = playback.playing.value!!.clock.positionAt(at) + behindMs
            return Heard.Song(MusicMetadata("Demo", "Band"), null, aheadMs + (playing * (1 + skew)).toLong(), at, matchId = "replayed", speed = (1 + skew).toFloat() * playerSpeed)
        }
    }

    private object NoMilkdrop : Milkdrop {
        override fun open(width: Int, height: Int) = false
        override fun size(width: Int, height: Int) = Unit
        override fun show(preset: String, smooth: Boolean) = false
        override fun hear(samples: ByteArray, count: Int) = Unit
        override fun draw() = Unit
        override fun darken(shade: Float, ceiling: Float) = Unit
        override fun finish() = Unit
        override fun close() = Unit
    }

    companion object {
        /** Where scripts/syncreplay leaves what to replay, run from the app's own folder or the project's. */
        private fun replays() = listOf(File("../build/syncreplay"), File("build/syncreplay")).firstOrNull { File(it, "replay.json").exists() }

        /**
         * Each song asked for: several named in replay.songs, one line each, in replay.json's "songs" in
         * the same order; or the one replay.json is. None asked, the one test is skipped. Read outside
         * Robolectric, where Android's JSON can't run, so the names come as plain lines.
         */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun songs(): List<Array<Any>> {
            val dir = replays() ?: return listOf(arrayOf("none", -1))
            val names = File(dir, "replay.songs").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: listOf("asked")
            return names.mapIndexed { index, name -> arrayOf(name, index) }
        }

        private const val TIMEOUT_MS = 10_000L
        private const val STEP_MS = 100L

        /** About how long a phone takes to work out the words in what it heard, after hearing it: a Galaxy S22 Ultra took 3.9 to 4.6 s. */
        private const val WORK_MS = 4_500L

        /** How long Shazam takes to name what it hears. */
        const val SHAZAM_MS = 4_000L

        /** How long the song stays stopped before it's played again. */
        private const val AGAIN_MS = 5_000L
    }
}
