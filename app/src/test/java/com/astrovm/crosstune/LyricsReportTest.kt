package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reporting a problem with a song's words, in one tap, to Crosstune's own box for reports. At
 * Robolectric's own screen size: on a tall one it never settles a dialog holding a text field.
 */
@RunWith(RobolectricTestRunner::class)
class LyricsReportTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null
    private val playback = FakePlayback().apply { access = true }
    private val link = "https://music.youtube.com/watch?v=sPmul8b17AU"

    /** Whether the box for reports turns them away, as when it can't be reached. */
    @Volatile
    private var reportsDown = false

    /** Holds a report on its way until it's let through. */
    @Volatile
    private var reportsHeld: CountDownLatch? = null

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
        prefs().edit().clear().putBoolean("setup_complete", true).putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false).putBoolean("play_right_away", false).putBoolean("visuals", false).commit()
        File(app.cacheDir, "lookups.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "kept_words.json").delete()
        fake.handler = { request ->
            when (request.url.host) {
                "report.crosstune.4st.li" -> {
                    reportsHeld?.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    FakeSpotify.html(request, "", code = if (reportsDown) 503 else 204)
                }
                "lrclib.net" -> FakeSpotify.html(request, """[{"trackName":"Demo","artistName":"Band","duration":60,"syncedLyrics":"[00:00.00] Demo one\n[00:10.00] Demo two"}]""")
                else -> FakeSpotify.html(request, """{"title":"Demo","author_name":"Band"}""")
            }
        }
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
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun string(id: Int): String = app.getString(id)

    private fun model() = ViewModelProvider(controller!!.get())[MainViewModel::class.java]

    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    /** Waits for the words' lookup to end: the problems with them are found in it, so one not sent by then never is. */
    private fun settled() = waitUntil { !model().findingWords }

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun click(label: String) {
        composeRule.onAllNodes(hasText(label) or hasContentDescription(label))[0].performClick()
        composeRule.waitForIdle()
    }

    /** The song's words open, YouTube Music paused on them; then its sheet, and the report from its corner. */
    private fun report() {
        openLyrics()
        click(string(R.string.lyrics_about_song))
        click(string(R.string.report_problem))
    }

    /** The song's words open, YouTube Music paused on them, once they're [shown]. */
    private fun openLyrics(shown: () -> Boolean = { shown("Demo one") }) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        val lyrics = hasText(string(R.string.lyrics_button)) or hasContentDescription(string(R.string.lyrics_button))
        waitUntil { composeRule.onAllNodes(lyrics).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(lyrics)[0].performScrollTo().performClick()
        waitUntil(shown)
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true)
        composeRule.waitForIdle()
    }

    /** LRCLIB answers with [answer] for the song's words. */
    private fun lyricsAre(answer: String) {
        val others = fake.handler
        fake.handler = { request -> if (request.url.host == "lrclib.net") FakeSpotify.html(request, answer) else others(request) }
    }

    /** What each report sent said. */
    private fun sentReports() = fake.requestedUrls.indices.filter { fake.requestedUrls[it] == ReportSender.URL }.map { JSONObject(fake.requestBodies[it]) }

    private fun problems() = sentReports().map { it.optString("problem") }

    private val offer get() = string(R.string.auto_report_offer)

    /** What the last report sent said. */
    private fun sentReport() = JSONObject(fake.requestBodies[fake.requestedUrls.indexOfLast { it == ReportSender.URL }])

    @Test
    fun aProblemWithTheWordsIsSentInOneTap() {
        report()
        // What goes is said where it's sent.
        assertTrue(shown(string(R.string.report_what_goes)))
        click(string(R.string.report_send))
        waitUntil { !shown(string(R.string.report_what_goes)) }
        assertEquals(string(R.string.report_sent), ShadowToast.getTextOfLatestToast())
        val sent = sentReport()
        assertFalse(sent.has("comment"))
        assertTrue(sent.getString("version").isNotEmpty())
        val song = sent.getJSONObject("song")
        assertEquals("Demo", song.getString("title"))
        assertEquals("YouTube Music", song.getString("app"))
        assertTrue(song.getBoolean("timed"))
        assertFalse(song.getBoolean("synced"))
        assertEquals(60_000L, song.getLong("lengthMs"))
        assertEquals("lrclib", song.getString("source"))
        assertFalse(song.getBoolean("sung"))
        assertFalse(song.has("timing"))
        // Nothing else opened: no email app or GitHub.
        val opened = generateSequence { shadowOf(app).nextStartedActivity }.map { it.action }.toList()
        assertFalse(opened.any { it == Intent.ACTION_SENDTO || it == Intent.ACTION_VIEW })
        assertNull(model().uiState.report)
    }

    @Test
    fun aReportThatDoesntGetThereSaysSoAndGoesOnceItCan() {
        reportsDown = true
        report()
        click(string(R.string.report_send))
        waitUntil { shown(string(R.string.report_failed)) }
        reportsDown = false
        click(string(R.string.report_send))
        waitUntil { !shown(string(R.string.report_what_goes)) }
        assertEquals(2, fake.requestedUrls.count { it == ReportSender.URL })
        // Opened again, it starts fresh, and closed unsent, nothing goes.
        click(string(R.string.report_problem))
        assertFalse(shown(string(R.string.report_failed)))
        click(string(R.string.cancel_button))
        assertFalse(shown(string(R.string.report_what_goes)))
        assertEquals(2, fake.requestedUrls.count { it == ReportSender.URL })
    }

    @Test
    fun aReportOnItsWayShowsItAndCantBeSentTwice() {
        // Off the main thread, as on a phone, so it can be caught on its way.
        MainActivity.lookupDispatcher = Dispatchers.IO
        reportsHeld = CountDownLatch(1)
        report()
        click(string(R.string.report_send))
        val send = composeRule.onNode(hasText(string(R.string.report_send)) and hasClickAction())
        send.assertIsNotEnabled()
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        reportsHeld!!.countDown()
        waitUntil { !shown(string(R.string.report_what_goes)) }
        assertEquals(1, fake.requestedUrls.count { it == ReportSender.URL })
    }

    @Test
    fun aSongWithNoWordsOffersToReportProblemsLikeItAndYesSendsThisOne() {
        lyricsAre("[]")
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        waitUntil { shown(offer) }
        // Nothing goes before the user says so.
        assertEquals(emptyList<String>(), problems())
        click(string(R.string.auto_report_yes))
        waitUntil { problems() == listOf("no_lyrics") }
        assertFalse(shown(offer))
        val song = sentReports().single()
        assertEquals("Demo", song.getJSONObject("song").getString("title"))
        assertFalse(song.has("comment"))
        assertEquals(true, model().uiState.autoReports)
        assertTrue(prefs().getBoolean("auto_reports", false))
    }

    @Test
    fun anInstrumentalHasNoWordsToReport() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("""[{"trackName":"Demo","artistName":"Band","instrumental":true}]""")
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        settled()
        assertEquals(emptyList<String>(), problems())
        // Nor is it offered, while the user hasn't said.
        controller!!.pause().stop().destroy()
        prefs().edit().remove("auto_reports").commit()
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        settled()
        assertFalse(shown(offer))
    }

    @Test
    fun saidNoItNeverAsksAgainNorSends() {
        lyricsAre("[]")
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        waitUntil { shown(offer) }
        composeRule.onNode(hasContentDescription(string(R.string.auto_report_no))).performClick()
        composeRule.waitForIdle()
        assertFalse(shown(offer))
        assertEquals(false, model().uiState.autoReports)
        // Another song with a problem, words with no timing this time.
        controller!!.pause().stop().destroy()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "kept_words.json").delete()
        lyricsAre("""[{"trackName":"Demo","artistName":"Band","duration":60,"plainLyrics":"Plain one\nPlain two"}]""")
        openLyrics { shown("Plain one") }
        settled()
        assertFalse(shown(offer))
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun allowedItReportsWordsWithNoTimingWithoutAsking() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("""[{"trackName":"Demo","artistName":"Band","duration":60,"plainLyrics":"Plain one\nPlain two"}]""")
        openLyrics { shown("Plain one") }
        waitUntil { problems() == listOf("no_timing") }
        assertFalse(shown(offer))
        assertFalse(sentReports().single().getJSONObject("song").getBoolean("timed"))
        // Once a song: shown again, it doesn't go again.
        controller!!.pause().stop().destroy()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "kept_words.json").delete()
        openLyrics { shown("Plain one") }
        settled()
        assertEquals(listOf("no_timing"), problems())
    }

    @Test
    fun aCoversUntimedWordsTakenFromTheOriginalArentReportedAsUntimed() {
        // Auto report #23: a karaoke track with no words of its own shows its original's, untimed, as a cover's are sung in its own time.
        prefs().edit().putBoolean("auto_reports", true).commit()
        val original = """[{"trackName":"Demo","artistName":"Band","duration":60,"syncedLyrics":"[00:00.00] Demo one\n[00:10.00] Demo two"}]"""
        val others = fake.handler
        fake.handler = { request ->
            when {
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, if ("Karaoke" in request.url.toString()) "[]" else original)
                request.url.host == "report.crosstune.4st.li" -> others(request)
                else -> FakeSpotify.html(request, """{"title":"Demo (In the Style of Band) [Karaoke Version]","author_name":"Karaoke Hits"}""")
            }
        }
        openLyrics { shown("Demo one") }
        settled()
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun wordsPutInTimeByHandAreOfferedThenSentAsTheyreLeft() {
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
        click(string(R.string.auto_report_yes))
        // Not yet: they may be put in time some more.
        assertEquals(emptyList<String>(), problems())
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { problems() == listOf("timed_by_hand") }
        val timing = sentReports().single().getJSONObject("song").getJSONArray("timing").getJSONObject(0)
        assertEquals(1_000L, timing.getLong("heardMs") - timing.getLong("timedMs"))
    }

    @Test
    fun anAutomaticReportThatDidntGetThereGoesTheNextTimeOnly() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        reportsDown = true
        lyricsAre("""[{"trackName":"Demo","artistName":"Band","duration":60,"plainLyrics":"Plain one\nPlain two"}]""")
        openLyrics { shown("Plain one") }
        waitUntil { problems() == listOf("no_timing") }
        reportsDown = false
        repeat(2) {
            controller!!.pause().stop().destroy()
            File(app.cacheDir, "lyrics.json").delete()
            File(app.cacheDir, "kept_words.json").delete()
            openLyrics { shown("Plain one") }
            settled()
        }
        assertEquals(listOf("no_timing", "no_timing"), problems())
    }

    @Test
    fun aLineTappedIntoTimeOffersTooAndLeftUnansweredNothingGoes() {
        openLyrics()
        composeRule.runOnIdle { model().syncLine(1) }
        waitUntil { shown(offer) }
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), problems())
        assertEquals(false, model().uiState.reportOffer)
    }

    @Test
    fun wordsPutBackAsTheyWereArentSent() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.runOnIdle { model().resetSync() }
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), problems())
        assertFalse(shown(offer))
    }

    @Test
    fun noChordsForTheWordsAreReportedWhenAllowed() {
        prefs().edit().putBoolean("auto_reports", true).putBoolean("lyrics_chords", true).commit()
        openLyrics()
        waitUntil { problems() == listOf("no_chords") }
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
    }
}
