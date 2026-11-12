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
import kotlinx.coroutines.yield
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

/**
 * A song with no words found, heard for a voice before it's reported: only a song someone sings has
 * words to miss, and one taken for an instrumental by its genre alone is sung after all when one's heard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h900dp")
class UnsungTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null
    private val playback = FakePlayback().apply { access = true }
    private val singing = Hearing()
    private val realSinging = MainActivity.singingFactory
    private val link = "https://music.youtube.com/watch?v=sPmul8b17AU"

    /** The genre the store files the song under, or null when it doesn't list it. */
    @Volatile
    private var genre: String? = null

    /** The song's name, as the link names it. */
    @Volatile
    private var title = "Demo"

    /** What LRCLIB answers for it. */
    @Volatile
    private var lrclib = "[]"

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
        prefs().edit().clear().putBoolean("setup_complete", true).putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false)
            .putBoolean("play_right_away", false).putBoolean("visuals", false).putBoolean("auto_reports", true).commit()
        File(app.cacheDir, "lookups.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "kept_words.json").delete()
        // The log is one for all the tests run together: what another test noted isn't this one's.
        TimingLog.clear()
        fake.handler = { request ->
            when (request.url.host) {
                "report.crosstune.cc" -> FakeSpotify.html(request, "", code = 204)
                "lrclib.net" -> FakeSpotify.html(request, lrclib)
                "itunes.apple.com" -> FakeSpotify.html(request, genre?.let { """{"results":[{"trackId":7,"trackName":"$title","artistName":"Band","primaryGenreName":"$it","trackTimeMillis":200000}]}""" } ?: """{"results":[]}""")
                else -> FakeSpotify.html(request, """{"title":"$title","author_name":"Band"}""")
            }
        }
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
        MainActivity.milkdropFactory = ::NativeMilkdrop
        MainActivity.soundTapFactory = MainActivity.phoneSound::open
        MainActivity.lyricsBusyPauseMs = LYRICS_BUSY_PAUSE_MS
        MainActivity.lookAheadLyrics = true
        MainActivity.listenAlongPauseMs = MainViewModel.LISTEN_ALONG_PAUSE_MS
        MainActivity.quietPauseMs = MainViewModel.LISTEN_ALONG_PAUSE_MS
        MainActivity.singingFactory = realSinging
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun model() = ViewModelProvider(controller!!.get())[MainViewModel::class.java]

    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    private fun problems() = fake.requestedUrls.indices.filter { fake.requestedUrls[it] == ReportSender.URL }.map { JSONObject(fake.requestBodies[it]).optString("problem") }

    private fun shows(description: Int) = composeRule.onAllNodes(hasContentDescription(app.getString(description))).fetchSemanticsNodes().isNotEmpty()

    /** The music app plays the song, or pauses it. */
    private fun plays(playing: Boolean = true) {
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = playing), "YouTube Music", canSeek = true)
        composeRule.waitForIdle()
    }

    /** The song's words looked for while it plays, till it's said to have none, as [shown] says. */
    private fun openLyrics(shown: Int = R.string.lyrics_none) = openLyrics { shows(shown) }

    /** The song's words looked for while it plays, till [done]. */
    private fun openLyrics(done: () -> Boolean) {
        plays()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        val lyrics = hasText(app.getString(R.string.lyrics_button)) or hasContentDescription(app.getString(R.string.lyrics_button))
        waitUntil { composeRule.onAllNodes(lyrics).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(lyrics)[0].performScrollTo().performClick()
        waitUntil { done() && !model().findingWords }
    }

    /** Time passes, [ms] of it. */
    private fun passes(ms: Long) {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        composeRule.waitForIdle()
    }

    private val voice = Sung(0, listOf(HeardWord("Hold", 100), HeardWord("the", 400), HeardWord("light", 700)))
    private val music = Sung(0, listOf(HeardWord("[Music]", 0)))

    @Test
    fun aSongWithNoWordsFoundIsReportedOnceSomeoneIsHeardSingingIt() {
        singing.answers = listOf(music, null, voice)
        openLyrics()
        waitUntil { singing.hears == 1 }
        // Nothing goes while it may be music alone.
        assertEquals(emptyList<String>(), problems())
        passes(MainViewModel.VOICE_PAUSE_MS)
        waitUntil { singing.hears == 2 }
        passes(MainViewModel.VOICE_PAUSE_MS)
        waitUntil { problems() == listOf("no_lyrics") }
        assertEquals(3, singing.hears)
        assertTrue(shows(R.string.lyrics_none))
        // Heard, it isn't heard again.
        passes(MainViewModel.VOICE_PAUSE_MS * 2)
        assertEquals(3, singing.hears)
    }

    @Test
    fun aSongNoOneIsHeardSingingHasNoWordsToReport() {
        singing.answers = listOf(music)
        openLyrics()
        repeat(MainViewModel.VOICE_TRIES) { passes(MainViewModel.VOICE_PAUSE_MS) }
        waitUntil { singing.hears == MainViewModel.VOICE_TRIES }
        passes(MainViewModel.VOICE_PAUSE_MS * 2)
        assertEquals(MainViewModel.VOICE_TRIES, singing.hears)
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun aWhileHeardAsItStopsIsHeardAgainOnceItPlays() {
        singing.answers = listOf(music)
        // Paused as it's heard: the music app says so before the while is in.
        singing.before = { playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true) }
        openLyrics()
        waitUntil { singing.hears == 1 }
        singing.before = {}
        singing.answers = listOf(voice)
        assertEquals(emptyList<String>(), problems())
        plays()
        waitUntil { problems() == listOf("no_lyrics") }
        // The first while didn't count: it's heard again from the start.
        assertEquals(2, singing.hears)
    }

    @Test
    fun withoutTheSpeechModelItIsReportedAsItAlwaysWas() {
        singing.missing = true
        openLyrics()
        waitUntil { problems() == listOf("no_lyrics") }
        // Failed once on GitHub with nothing heard (Issue #886), so what the app noted on the way says why.
        assertEquals(TimingLog.lastMinute(), 1, singing.hears)
    }

    @Test
    fun withNoSoundToHearItIsReportedAsItAlwaysWas() {
        // The phone turned all the way down gives no sound: neither a voice nor music without one.
        singing.noSound = true
        openLyrics()
        waitUntil { problems() == listOf("no_lyrics") }
        assertEquals(1, singing.hears)
    }

    @Test
    fun withoutTheMicrophoneItIsReportedAsItAlwaysWas() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        openLyrics()
        waitUntil { problems() == listOf("no_lyrics") }
        assertEquals(0, singing.hears)
    }

    @Test
    fun withReportsOffOnlyASongTakenForAnInstrumentalIsHeard() {
        prefs().edit().putBoolean("auto_reports", false).commit()
        singing.answers = listOf(voice)
        openLyrics()
        passes(MainViewModel.VOICE_PAUSE_MS)
        // A voice heard would change nothing, so none is listened for.
        assertEquals(0, singing.hears)
        controller!!.destroyAndSettle()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "kept_words.json").delete()
        genre = "Jazz"
        singing.answers = listOf(music, voice)
        openLyrics(shown = R.string.lyrics_instrumental)
        passes(MainViewModel.VOICE_PAUSE_MS)
        // Heard sung, it shows no words found, and still nothing goes.
        waitUntil { shows(R.string.lyrics_none) }
        assertEquals(2, singing.hears)
        assertEquals(emptyList<String>(), problems())
        // So is one LRCLIB marks instrumental.
        controller!!.destroyAndSettle()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "kept_words.json").delete()
        genre = null
        markedOnLrclib()
        // The fake answers by how many times it was heard in all: this third open hears music first.
        singing.answers = listOf(music, voice, music, voice)
        openLyrics(shown = R.string.lyrics_instrumental)
        passes(MainViewModel.VOICE_PAUSE_MS)
        waitUntil { shows(R.string.lyrics_none) }
        assertEquals(4, singing.hears)
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun outOfSightTheListenStopsAndGoesOnOnceTheWordsAreBack() {
        singing.answers = listOf(music)
        openLyrics()
        waitUntil { singing.hears == 1 }
        model().pauseFollowingNext()
        passes(MainViewModel.VOICE_PAUSE_MS * 2)
        // Out of sight, a while already started is not heard out.
        assertEquals(1, singing.hears)
        assertEquals(emptyList<String>(), problems())
        model().resumeFollowingNext()
        repeat(MainViewModel.VOICE_TRIES) { passes(MainViewModel.VOICE_PAUSE_MS) }
        waitUntil { singing.hears == MainViewModel.VOICE_TRIES }
        assertEquals(MainViewModel.VOICE_TRIES, singing.hears)
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun leavingTheWordsStopsTheListen() {
        singing.answers = listOf(music, voice)
        openLyrics()
        waitUntil { singing.hears == 1 }
        model().dismissLyrics()
        passes(MainViewModel.VOICE_PAUSE_MS * 2)
        assertEquals(1, singing.hears)
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun withoutTheMicrophoneAListenThatWasWaitingDoesNotGoOn() {
        singing.answers = listOf(music, voice)
        // Paused as it's heard, so that while ends and the next one has to be allowed again.
        singing.before = { playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true) }
        openLyrics()
        waitUntil { singing.hears == 1 }
        passes(1)
        singing.before = {}
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        plays()
        passes(MainViewModel.VOICE_PAUSE_MS * 2)
        assertEquals(1, singing.hears)
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun aMedleyOrAWholeConcertHasNoOneSongsWordsToMiss() {
        title = "Live 2007 (Recital Completo)"
        openLyrics()
        passes(MainViewModel.VOICE_PAUSE_MS)
        assertEquals(0, singing.hears)
        assertEquals(emptyList<String>(), problems())
    }

    /** LRCLIB's copies of the song, both marked instrumental. */
    private fun markedOnLrclib() {
        val copy = """{"trackName":"$title","artistName":"Band","instrumental":true}"""
        lrclib = "[$copy,$copy]"
    }

    @Test
    fun aSongLrclibMarksInstrumentalIsSungAfterAllWhenSomeoneIsHeard() {
        // Its copies may be marked wrongly, so it's heard as one taken for an instrumental by its genre is.
        markedOnLrclib()
        singing.answers = listOf(music, voice)
        openLyrics(shown = R.string.lyrics_instrumental)
        assertEquals(emptyList<String>(), problems())
        passes(MainViewModel.VOICE_PAUSE_MS)
        waitUntil { problems() == listOf("no_lyrics") }
        assertTrue(shows(R.string.lyrics_none))
        assertFalse(shows(R.string.lyrics_instrumental))
    }

    /** LRCLIB's copies of the song: two marked instrumental, and one with its words, timed. */
    private fun markedOnLrclibWithWords() {
        val copy = """{"trackName":"$title","artistName":"Band","instrumental":true}"""
        lrclib = """[$copy,$copy,{"trackName":"$title","artistName":"Band","syncedLyrics":"[00:01.00] Hold the light\n[00:10.00] Out in the rain"}]"""
    }

    private fun showsWords() = composeRule.onAllNodes(hasText("Hold the light", substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun wordsFoundForASongLrclibMarksInstrumentalShowOnceSomeoneIsHeardSingingIt() {
        // Marks can be wrong, and a sung song mustn't lose its words over them.
        markedOnLrclibWithWords()
        singing.answers = listOf(music, voice)
        openLyrics(shown = R.string.lyrics_instrumental)
        assertFalse(showsWords())
        passes(MainViewModel.VOICE_PAUSE_MS)
        waitUntil { showsWords() }
        assertFalse(shows(R.string.lyrics_instrumental))
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun wordsFoundForASongLrclibMarksInstrumentalStayHiddenWhenNoOneIsHeard() {
        markedOnLrclibWithWords()
        singing.answers = listOf(music)
        openLyrics(shown = R.string.lyrics_instrumental)
        repeat(MainViewModel.VOICE_TRIES) { passes(MainViewModel.VOICE_PAUSE_MS) }
        waitUntil { singing.hears == MainViewModel.VOICE_TRIES }
        composeRule.waitForIdle()
        assertTrue(shows(R.string.lyrics_instrumental))
        assertFalse(showsWords())
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun wordsFoundForASongLrclibMarksInstrumentalShowWhereNoVoiceCanBeHeard() {
        // Without the microphone, a mark alone can't say no one sings: the words show at once.
        markedOnLrclibWithWords()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        openLyrics(::showsWords)
        assertEquals(0, singing.hears)
        assertFalse(shows(R.string.lyrics_instrumental))
        // Nor without the speech model, once that's known.
        controller!!.destroyAndSettle()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "kept_words.json").delete()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        singing.missing = true
        openLyrics(::showsWords)
        assertEquals(1, singing.hears)
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun aSongWhoseTitleSaysNoOneSingsInItShowsItsWordsOnceSomeoneIsHeardSingingIt() {
        // A title can say so wrongly too, "Song - Live at the Instrumental Hall".
        title = "Demo (Instrumental)"
        lrclib = """[{"trackName":"$title","artistName":"Band","syncedLyrics":"[00:01.00] Hold the light\n[00:10.00] Out in the rain"}]"""
        singing.answers = listOf(music, voice)
        openLyrics(shown = R.string.lyrics_instrumental)
        assertFalse(showsWords())
        passes(MainViewModel.VOICE_PAUSE_MS)
        waitUntil { showsWords() }
        assertFalse(shows(R.string.lyrics_instrumental))
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun aSongWhoseTitleSaysNoOneSingsInItWithNoWordsAnywhereIsReportedOnceSomeoneIsHeard() {
        title = "Demo (Instrumental)"
        singing.answers = listOf(music, voice)
        openLyrics(shown = R.string.lyrics_instrumental)
        passes(MainViewModel.VOICE_PAUSE_MS)
        waitUntil { problems() == listOf("no_lyrics") }
        assertTrue(shows(R.string.lyrics_none))
    }

    @Test
    fun aSongWhoseTitleSaysNoOneSingsInItIsAnInstrumentalWhereNoVoiceCanBeHeard() {
        title = "Demo (Instrumental)"
        lrclib = """[{"trackName":"$title","artistName":"Band","plainLyrics":"Hold the light"}]"""
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        openLyrics(shown = R.string.lyrics_instrumental)
        passes(MainViewModel.VOICE_PAUSE_MS)
        assertEquals(0, singing.hears)
        // Nor without the speech model.
        controller!!.destroyAndSettle()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        singing.missing = true
        openLyrics(shown = R.string.lyrics_instrumental)
        waitUntil { singing.hears == 1 }
        passes(MainViewModel.VOICE_PAUSE_MS)
        assertTrue(shows(R.string.lyrics_instrumental))
        assertFalse(showsWords())
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun aSongTakenForAnInstrumentalByItsGenreIsSungAfterAllWhenSomeoneIsHeard() {
        genre = "Jazz"
        singing.answers = listOf(music, voice)
        openLyrics(shown = R.string.lyrics_instrumental)
        assertEquals(emptyList<String>(), problems())
        passes(MainViewModel.VOICE_PAUSE_MS)
        waitUntil { problems() == listOf("no_lyrics") }
        assertTrue(shows(R.string.lyrics_none))
        assertFalse(shows(R.string.lyrics_instrumental))
    }

    @Test
    fun aSongTakenForAnInstrumentalStaysOneWhenNoOneIsHeardOrNothingCanBe() {
        genre = "Classical"
        singing.answers = listOf(music)
        openLyrics(shown = R.string.lyrics_instrumental)
        repeat(MainViewModel.VOICE_TRIES) { passes(MainViewModel.VOICE_PAUSE_MS) }
        waitUntil { singing.hears == MainViewModel.VOICE_TRIES }
        assertTrue(shows(R.string.lyrics_instrumental))
        assertEquals(emptyList<String>(), problems())
        // Without the speech model, it's taken for one as it always was.
        controller!!.destroyAndSettle()
        File(app.cacheDir, "lyrics.json").delete()
        singing.missing = true
        openLyrics(shown = R.string.lyrics_instrumental)
        waitUntil { singing.hears == MainViewModel.VOICE_TRIES + 1 }
        composeRule.waitForIdle()
        assertTrue(shows(R.string.lyrics_instrumental))
        assertEquals(emptyList<String>(), problems())
    }

    /** Hears [answers] in turn, the last for good, after doing [before]. */
    private class Hearing : SingingHearing {
        val languages: MutableList<String?> = CopyOnWriteArrayList()
        val hears get() = languages.size

        @Volatile
        var answers: List<Sung?> = listOf(null)

        @Volatile
        var before: () -> Unit = {}

        @Volatile
        var missing = false

        override val missesModel get() = missing

        @Volatile
        var noSound = false

        override val heardNoSound get() = noSound

        override suspend fun fetchModel() = false

        override suspend fun hear(language: String?, first: (suspend (Sung) -> Boolean)?): Sung? {
            val heard = answers.getOrElse(languages.size) { answers.last() }
            languages += language
            before()
            // A while takes time to hear, in which what the music app says gets through.
            yield()
            return if (missing || noSound) null else heard
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
        const val TIMEOUT_MS = 10_000L
    }
}
