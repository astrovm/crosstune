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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
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
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

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
        // The log is one for all the tests run together: what another test noted isn't this one's.
        TimingLog.clear()
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

    /** LRCLIB has the six lines with no timing, for a recording [seconds] long when it says; the song's cover is [artworkUrl]. */
    private fun untimedWords(seconds: Int? = null, artworkUrl: String? = null) {
        val duration = seconds?.let { "\"duration\":$it," }.orEmpty()
        val cover = artworkUrl?.let { ",\"thumbnail_url\":\"$it\"" }.orEmpty()
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                if (request.url.host == "lrclib.net") """[{"trackName":"Demo","artistName":"Band",$duration"plainLyrics":"${lines.joinToString("\\n")}"}]"""
                else """{"title":"Demo","author_name":"Band"$cover}"""
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
    private fun sung(count: Int = lines.size) =
        Sung(startedAt, (0 until count).flatMap { wordsSung(it, lineMs(it)) }.map { (word, ms) -> HeardWord(word, ms - startedFromMs) }.filter { it.atMs >= 0 })

    private fun lineMs(index: Int) = 2_000L + index * 3_000L

    /** The words of line [index], sung from [startMs], each with where in the song whisper.cpp hears it: a little late. */
    private fun wordsSung(index: Int, startMs: Long): List<Pair<String, Long>> {
        var before = 0
        return lines[index].split(' ').map { word -> (word to startMs + SungTiming.HEARD_LATE_MS + before * SungTiming.LETTER_MS).also { before += word.length } }
    }

    /**
     * The song plays on as it's heard, from where [plays] put it: each while is heard from where the
     * last ended, or from when the app started playing, and the phone's clock moves on to its end.
     * What's heard is the first [words] words of each line sung in it, the lines sung where [singsAt] says.
     */
    private fun playsOn(singsAt: Map<Int, Long>, words: Int = Int.MAX_VALUE) = playsOn(singsAt.toList(), words)

    /** As [playsOn], with a line sung more than once. */
    private fun playsOn(singsAt: List<Pair<Int, Long>>, words: Int = Int.MAX_VALUE) {
        var heardTo = 0L
        singing.answer = {
            val whileMs = SingingListener.SECONDS * 1000L
            val heardAt = maxOf(startedAt, heardTo)
            heardTo = heardAt + whileMs
            val fromMs = startedFromMs + heardAt - startedAt
            ShadowSystemClock.advanceBy(Duration.ofMillis(maxOf(0L, heardTo - SystemClock.elapsedRealtime())))
            val heard = singsAt.flatMap { (index, ms) -> wordsSung(index, ms).take(words) }.filter { it.second in fromMs until fromMs + whileMs }
            Sung(heardAt, heard.map { (word, ms) -> HeardWord(word, ms - fromMs) })
        }
    }

    private fun lineStarts() = state().lyricLines.map { it.timeMs }

    private fun shows(text: String) = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

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
        assertEquals(3, SyncedLyrics.indexAt(state().lyricLines, lineMs(3) + 100, byEar = true))
        // Past the last line heard, sung through, nothing is lit.
        assertEquals(-1, SyncedLyrics.indexAt(state().lyricLines, lineMs(4) + 1_000, byEar = true))
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
    fun linesLitGoDarkWhenWhatsHeardSinceSaysTheyCouldBeElsewhere() {
        // The first line sung in the first while heard, and again in the second: as sure at both, nothing says which is right.
        playsOn(listOf(0 to 2_000L, 0 to 22_000L))
        val heardSo = singing.answer
        val second = CompletableDeferred<Unit>()
        singing.answer = {
            if (singing.hears == 2) second.await()
            heardSo()
        }
        singing.most = 2
        openLyrics()
        plays(durationMs = 200_000)
        waitUntil { state().lyricsTimedByEar }
        assertEquals(listOf(2_000L) + List(5) { LyricLine.NOT_HEARD }, lineStarts())
        second.complete(Unit)
        waitUntil { !state().lyricsTimedByEar }
        assertEquals(emptyList<Long>(), lineStarts())
    }

    @Test
    fun aTimingWorkedOutAsTimedWordsAreFoundIsntShownOverThem() {
        // LRCLIB has the words with no timing when they're first looked up. Looked up again to time them
        // better, it has a copy timed line by line, from 10 s in, held till the test says.
        val timedMs = lines.indices.map { 10_000L + it * 4_000L }
        val synced = lines.indices.joinToString("\\n") { "[00:${timedMs[it] / 1_000}.00] ${lines[it]}" }
        val lookedUp = AtomicInteger()
        val again = CountDownLatch(1)
        fake.handler = { request ->
            val body = when {
                request.url.host != "lrclib.net" -> """{"title":"Demo","author_name":"Band"}"""
                request.url.queryParameter("track_name") == null || lookedUp.incrementAndGet() == 1 -> """[{"trackName":"Demo","artistName":"Band","plainLyrics":"${lines.joinToString("\\n")}"}]"""
                else -> again.await().let { """[{"trackName":"Demo","artistName":"Band","syncedLyrics":"$synced"}]""" }
            }
            FakeSpotify.html(request, body)
        }
        val gate = Gate()
        MainActivity.systemDispatcher = gate
        singing.answer = { sung() }
        singing.most = 1
        openLyrics()
        waitUntil { lookedUp.get() == 2 }
        // The singing heard meanwhile is timed away from the screen, and held there.
        gate.held = true
        plays()
        waitUntil { gate.queued.isNotEmpty() }
        // The timed copy comes first, and its lines show.
        again.countDown()
        waitUntil { lineStarts() == timedMs }
        // What was heard then comes too late: the timed words stay.
        gate.letGo()
        composeRule.waitForIdle()
        assertEquals(timedMs, lineStarts())
        assertFalse(state().lyricsTimedByEar)
    }

    @Test
    fun aSpeedShazamHearsLeavesLinesTimedByEarWhereTheyreHeard() {
        // A video: Shazam listens along as the singing's heard, and hears it played 1.25 times as fast.
        val shazam = Channel<Heard>(Channel.UNLIMITED)
        var listens = 0
        MainActivity.hearingFactory = { SongHearing { listens++; shazam.receive() } }
        try {
            singing.answer = { sung(count = 4) }
            openLyrics()
            plays(video = true)
            waitUntil { state().lyricsTimedByEar && state().lyricLines.isNotEmpty() && listens >= 1 }
            val heard = lineStarts()
            composeRule.runOnIdle { shazam.trySend(Heard.Song(MusicMetadata("Demo", "Band"), null, 12_000, SystemClock.elapsedRealtime(), "sped", speed = 1.25f)) }
            waitUntil { listens >= 2 || !state().listeningAlong }
            composeRule.waitForIdle()
            // Timed by the clock of what plays, the lines are already where it sings them, at any speed.
            assertEquals(heard, lineStarts())
            assertTrue(state().lyricsTimedByEar)
        } finally {
            MainActivity.hearingFactory = null
        }
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
        // Worked out again, as after an update, they're timed from what was heard, before anything's heard.
        controller!!.destroyAndSettle()
        File(app.cacheDir, "kept_words.json").delete()
        openLyrics()
        assertTrue(state().lyricsTimedByEar)
        assertEquals(lines.indices.map(::lineMs), lineStarts())
        assertEquals(4, singing.hears)
    }

    @Test
    fun thePlayersPeekGoesDarkWhereTheWordsDoAfterTheyreOpenedAgain() {
        singing.answer = { sung(count = 4) }
        openLyrics()
        plays()
        waitUntil { state().lyricsTimedByEar && singing.hears == 4 }
        controller!!.destroyAndSettle()
        // Opened again, as after a restart, and on to the player: its peek has the lines kept, timed by ear.
        singing.answer = { null }
        openLyrics()
        composeRule.runOnIdle { model().lyricsToPlayer() }
        waitUntil { state().player?.lines?.isNotEmpty() == true }
        // Past the last line heard, sung through, that line isn't up as if still sung: the next in the words is.
        plays(positionMs = lineMs(4) + 1_000)
        assertFalse(shows(lines[3]))
        assertTrue(shows(lines[4]))
    }

    @Test
    fun aWhileTheModelFailsToMakeOutIsntKept() {
        val microphone = FakeMicrophone().apply {
            play(SingingListener.SECONDS.toDouble())
            end()
        }
        // whisper.cpp fails on it, as when the phone runs out of memory: that's not a while with nothing sung.
        MainActivity.singingFactory = { _, _ ->
            SingingListener(microphone, { File("model") }, { WordHearing { _, _ -> null } }, Dispatchers.Unconfined, Dispatchers.Unconfined, clock = { startedAt })
        }
        openLyrics()
        plays()
        composeRule.waitForIdle()
        assertEquals(1, microphone.opened)
        // Nothing of it is kept, so it counts for nothing towards the song heard enough.
        assertFalse(TimingLog.lastMinute(), "by ear" in TimingLog.lastMinute())
        assertTrue(state().lyricLines.isEmpty())
    }

    @Test
    fun aLongSongHeardEnoughIsLeftAloneAfterARestart() {
        singing.answer = { sung() }
        openLyrics()
        // Fifteen minutes long: heard through twice is half an hour of whiles.
        plays(durationMs = FIFTEEN_MINUTES_MS)
        waitUntil { singing.hears >= 90 }
        composeRule.waitForIdle()
        assertEquals(90, singing.hears)
        // Opened again, as after a restart, it's heard enough already.
        controller!!.destroyAndSettle()
        openLyrics()
        plays(durationMs = FIFTEEN_MINUTES_MS)
        composeRule.waitForIdle()
        assertEquals(90, singing.hears)
    }

    @Test
    fun aSongLongerThanWhatsKeptIsHeardAsLongAsWhatsKept() {
        singing.answer = { sung() }
        openLyrics()
        // An hour long, it's heard as long as what's kept, and no longer, now or after a restart.
        plays(durationMs = 4 * FIFTEEN_MINUTES_MS)
        waitUntil { singing.hears >= HeardSongs.MAX_STRETCHES }
        composeRule.waitForIdle()
        assertEquals(HeardSongs.MAX_STRETCHES, singing.hears)
        controller!!.destroyAndSettle()
        openLyrics()
        plays(durationMs = 4 * FIFTEEN_MINUTES_MS)
        composeRule.waitForIdle()
        assertEquals(HeardSongs.MAX_STRETCHES, singing.hears)
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
        // Moved while a while is heard, it's not heard twice at once.
        assertEquals(1, singing.hears)
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
    fun anIntroWithNoWordsHeardIsOneTryNotTheEnd() {
        // The phone's own listener, as on a phone: the first while is the intro, with no words for whisper.cpp to hear.
        // Two whiles of sound, and then none: a phone that stops sharing it.
        val microphone = FakeMicrophone().apply {
            repeat(2) { play(SingingListener.SECONDS.toDouble()) }
            end()
        }
        val answers = ArrayDeque(listOf(emptyList(), sung(count = 1).words))
        // Each while heard from where the song starts, so the words heard are where they're sung.
        MainActivity.singingFactory = { _, _ ->
            SingingListener(microphone, { File("model") }, { WordHearing { _, _ -> answers.removeFirst() } }, Dispatchers.Unconfined, Dispatchers.Unconfined, clock = { startedAt })
        }
        openLyrics()
        plays()
        // Heard on after it, the first line sung is timed.
        waitUntil { state().lyricsTimedByEar }
        assertEquals(listOf(lineMs(0)) + List(5) { LyricLine.NOT_HEARD }, lineStarts())
        // With no sound left to hear, it's let be.
        composeRule.waitForIdle()
        assertEquals(3, microphone.opened)
        assertTrue(answers.isEmpty())
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

    @Test
    fun eachWhileHeardSaysWhereItsLinesWent() {
        // The second line is sung where the first while heard ends, and heard first.
        playsOn(mapOf(0 to 20_000L, 1 to 40_000L))
        openLyrics()
        plays(positionMs = 40_000, durationMs = 200_000)
        waitUntil { singing.hears == 1 + MainViewModel.SINGING_TRIES }
        assertEquals(listOf(LyricLine.NOT_HEARD, 40_000L) + List(4) { LyricLine.NOT_HEARD }, lineStarts())
        // Moved back to the first line, both are where the while heard starts and ends, and the note says so.
        singing.most = singing.hears + 1
        plays(positionMs = 20_000, durationMs = 200_000)
        waitUntil { singing.hears == singing.most + 1 }
        assertEquals(listOf(20_000L, 40_000L) + List(4) { LyricLine.NOT_HEARD }, lineStarts())
        val note = TimingLog.lastMinute().lines().last { "by ear" in it }
        assertTrue(note, note.endsWith("by ear 20000 heard 4 words, 2 lines timed, here 0>20000 1>40000"))
    }

    @Test
    fun aWordHeardAsTheWhileStartsIsHeard() {
        singing.answer = { sung(count = 1) }
        openLyrics()
        // The first word is sung just as the while heard starts.
        plays(positionMs = lineMs(0) + SungTiming.HEARD_LATE_MS)
        waitUntil { state().lyricsTimedByEar }
        assertTrue(TimingLog.lastMinute(), "by ear 2550 heard 4 words, 1 lines timed" in TimingLog.lastMinute())
    }

    @Test
    fun aClockOffByLessThanAJumpIsStillPlaced() {
        val moved = CompletableDeferred<Unit>()
        singing.answer = {
            val heard = sung()
            moved.await()
            heard
        }
        openLyrics()
        plays(positionMs = 10_000)
        waitUntil { singing.hears == 1 }
        // The app says it's a little further than its clock said, not far enough to have moved.
        plays(positionMs = 10_000 + SystemClock.elapsedRealtime() - startedAt + MainViewModel.STEADY_CLOCK_MS)
        moved.complete(Unit)
        waitUntil { state().lyricsTimedByEar }
        assertEquals(List(3) { LyricLine.NOT_HEARD } + (3..5).map(::lineMs), lineStarts())
    }

    @Test
    fun aSongHeardThroughTwiceIsHeardNoMore() {
        playsOn(mapOf(0 to 2_000L, 3 to 25_000L))
        openLyrics()
        plays()
        waitUntil { singing.hears >= 4 }
        composeRule.waitForIdle()
        // Forty seconds long, it's heard through twice in four whiles, though the last two hear nothing.
        assertEquals(4, singing.hears)
        assertEquals(listOf(2_000L, LyricLine.NOT_HEARD, LyricLine.NOT_HEARD, 25_000L, LyricLine.NOT_HEARD, LyricLine.NOT_HEARD), lineStarts())
    }

    @Test
    fun theLengthTheAppSaysGoesFirst() {
        // The words say the song is half a minute long, and the app forty seconds.
        untimedWords(seconds = 30)
        singing.answer = { sung() }
        openLyrics()
        plays()
        waitUntil { singing.hears >= 4 }
        composeRule.waitForIdle()
        assertEquals(4, singing.hears)
    }

    @Test
    fun withNoLengthFromTheAppTheWordsSayIt() {
        untimedWords(seconds = 30)
        singing.answer = { sung() }
        openLyrics()
        plays(durationMs = null)
        waitUntil { singing.hears >= 3 }
        composeRule.waitForIdle()
        assertEquals(3, singing.hears)
    }

    @Test
    fun withNoLengthSaidTheWhileHeardLastSaysHowLongItIs() {
        // Three words of the second line, sung late in the second while: placed only in a song at least that long.
        playsOn(mapOf(1 to 34_000L), words = 3)
        singing.most = 2
        openLyrics()
        plays(durationMs = null)
        waitUntil { singing.hears == 3 }
        assertEquals(listOf(LyricLine.NOT_HEARD, 34_000L) + List(4) { LyricLine.NOT_HEARD }, lineStarts())
    }

    @Test
    fun aLineKeptIsTimedOnceItsHeard() {
        untimedWords(artworkUrl = COVER)
        singing.answer = { sung(count = 4) }
        openLyrics()
        composeRule.runOnIdle { model().toggleSavedLine(1) }
        assertEquals(null, state().savedLines.single().startMs)
        plays()
        waitUntil { state().lyricsTimedByEar }
        assertEquals(lineMs(1), state().savedLines.single().startMs)
        // Kept once timed, a line heard is timed, and one not heard isn't.
        composeRule.runOnIdle { model().toggleSavedLine(2) }
        composeRule.runOnIdle { model().toggleSavedLine(4) }
        assertEquals(mapOf(lines[1] to lineMs(1), lines[2] to lineMs(2), lines[4] to null), state().savedLines.associate { it.text to it.startMs })
        // Timed or not, each has the song's cover.
        assertEquals(listOf(COVER, COVER, COVER), state().savedLines.map { it.artworkUrl })
    }

    @Test
    fun aVideoTimedByEarGoesByItsClock() {
        singing.answer = { sung(count = 4) }
        openLyrics()
        plays(video = true)
        waitUntil { state().lyricsTimedByEar }
        // Heard where the app has the song, nothing's taken off its clock, and the lines heard are sure.
        assertEquals(playback.playing.value, state().following)
    }

    @Test
    fun outOfSightItsNotHeard() {
        singing.answer = { sung() }
        openLyrics()
        composeRule.runOnIdle { model().pauseFollowingNext() }
        plays()
        assertEquals(0, singing.hears)
        composeRule.runOnIdle { model().resumeFollowingNext() }
        waitUntil { state().lyricsTimedByEar }
    }

    @Test
    fun withNoMicItsNotHeard() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        singing.answer = { sung() }
        openLyrics()
        plays()
        assertEquals(0, singing.hears)
    }

    /** whisper.cpp, answering what a test says. */
    private class FakeSinging : SingingHearing {
        val languages: MutableList<String?> = CopyOnWriteArrayList()

        @Volatile
        var answer: suspend () -> Sung? = { null }

        @Volatile
        var missing = false

        /** The most whiles heard: past them, it hears on till it's stopped, so a test can look at what the first gave. */
        @Volatile
        var most = MOST_HEARS

        val hears get() = languages.size

        override val missesModel get() = missing
        override val heardNoSound = false

        override suspend fun fetchModel() = false

        override suspend fun hear(language: String?, first: (suspend (Sung) -> Boolean)?): Sung? {
            languages += language
            if (hears > most) awaitCancellation()
            return if (missing) null else answer()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SONG_MS = 40_000L
        const val COVER = "https://example.com/demo.jpg"
        const val FIFTEEN_MINUTES_MS = 15 * 60_000L

        /** More whiles than any test hears, past which a hearing that never ends would stop. */
        const val MOST_HEARS = 100
    }
}
