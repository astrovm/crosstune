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
        fake.handler = { request ->
            when (request.url.host) {
                "report.crosstune.4st.li" -> FakeSpotify.html(request, "", code = 204)
                "lrclib.net" -> FakeSpotify.html(request, "[]")
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
    private fun openLyrics(shown: Int = R.string.lyrics_none) {
        plays()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        val lyrics = hasText(app.getString(R.string.lyrics_button)) or hasContentDescription(app.getString(R.string.lyrics_button))
        waitUntil { composeRule.onAllNodes(lyrics).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(lyrics)[0].performScrollTo().performClick()
        waitUntil { shows(shown) && !model().findingWords }
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
    }

    @Test
    fun aMedleyOrAWholeConcertHasNoOneSongsWordsToMiss() {
        title = "Live 2007 (Recital Completo)"
        openLyrics()
        passes(MainViewModel.VOICE_PAUSE_MS)
        assertEquals(0, singing.hears)
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

        override suspend fun fetchModel() = false

        override suspend fun hear(language: String?, first: (suspend (Sung) -> Boolean)?): Sung? {
            val heard = answers.getOrElse(languages.size) { answers.last() }
            languages += language
            before()
            // A while takes time to hear, in which what the music app says gets through.
            yield()
            return if (missing) null else heard
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
