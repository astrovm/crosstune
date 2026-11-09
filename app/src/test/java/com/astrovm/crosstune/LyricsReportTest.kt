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
        TimingLog.clear()
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

    /** How many timing reports are owed, kept to be sent once the words are left, or at the next start. */
    private fun owed() = org.json.JSONArray(prefs().getString("auto_reports_owed", "[]")).length()

    /** The offer's Yes, once it takes taps, as it takes none as it comes in. */
    private fun yes() {
        composeRule.mainClock.advanceTimeBy(OFFER_ARMED_MS)
        click(string(R.string.auto_report_yes))
    }

    /** The offer's close, once it takes taps. */
    private fun notNow() {
        composeRule.mainClock.advanceTimeBy(OFFER_ARMED_MS)
        click(string(R.string.auto_report_not_now))
    }

    /** What the box for reports was sent, as the app that is started anew finds it. */
    private fun startedAnew() {
        controller!!.destroyAndSettle()
        // Another process, which isn't the one that asked.
        prefs().edit().putLong("auto_reports_asked_in", -7L).commit()
    }

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
        yes()
        waitUntil { problems() == listOf("no_lyrics") }
        assertFalse(shown(offer))
        val song = sentReports().single()
        assertEquals("Demo", song.getJSONObject("song").getString("title"))
        assertFalse(song.has("comment"))
        assertEquals(true, model().uiState.autoReports)
        assertTrue(prefs().getBoolean("auto_reports", false))
    }

    @Test
    fun aSongWithNoWordsSaysWhichAppPlaysIt() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("[]")
        // Playing from the start: it's found to have no words before it's followed.
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true)
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        waitUntil { problems() == listOf("no_lyrics") }
        assertEquals("YouTube Music", sentReport().getJSONObject("song").getString("app"))
    }

    @Test
    fun aSongNoAppPlaysNamesNone() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("[]")
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        waitUntil { problems() == listOf("no_lyrics") }
        assertFalse(sentReport().getJSONObject("song").has("app"))
    }

    @Test
    fun withoutNotificationAccessNoAppIsAskedAfter() {
        playback.access = false
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("[]")
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true)
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        waitUntil { problems() == listOf("no_lyrics") }
        assertFalse(sentReport().getJSONObject("song").has("app"))
    }

    @Test
    fun wordsLeftAfterTheAppWentOnStillSayWhichItWas() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        // On to the next song, which is why the words are left: it no longer plays this one.
        playback.playing.value = null
        composeRule.waitForIdle()
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { problems() == listOf("timed_by_hand") }
        assertEquals("YouTube Music", sentReport().getJSONObject("song").getString("app"))
    }

    @Test
    fun wordsTimedBeforeAnyAppWasFollowedAskForItWhenReportedAndSayHowItPlays() {
        // Without access nothing was followed while the words were put in time, so the report finds the app itself.
        playback.access = false
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        playback.access = true
        playback.playing.value = Following(
            PlaybackClock(0, SystemClock.elapsedRealtime(), speed = 1.25f, playing = false), "KDE Connect", canSeek = true, appPackage = "org.kde.kdeconnect_tp"
        )
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { problems() == listOf("timed_by_hand") }
        val song = sentReport().getJSONObject("song")
        assertEquals("KDE Connect", song.getString("app"))
        assertEquals("org.kde.kdeconnect_tp", song.getString("appPackage"))
        assertEquals(1.25, song.getDouble("speed"), 0.0)
    }

    /** Followed in KDE Connect, which says the song plays at [speed], the words open. */
    private fun followedInKdeConnect(speed: Float) {
        playback.playing.value = Following(
            PlaybackClock(0, SystemClock.elapsedRealtime(), speed = speed, playing = false), "KDE Connect", canSeek = true, appPackage = "org.kde.kdeconnect_tp"
        )
        composeRule.waitForIdle()
    }

    @Test
    fun wordsPutInTimeByHandSayTheAppFollowedItsSpeedTheLeadAndTheTimingLog() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        followedInKdeConnect(1.25f)
        composeRule.runOnIdle { model().nudgeSync(500) }
        // What happens after the last line moved isn't what the report is about.
        composeRule.runOnIdle { TimingLog.note("a moment later") }
        // On to the next song, which is why the words are left: it no longer plays this one.
        playback.playing.value = null
        composeRule.waitForIdle()
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { problems() == listOf("timed_by_hand") }
        val song = sentReport().getJSONObject("song")
        assertEquals("KDE Connect", song.getString("app"))
        assertEquals("org.kde.kdeconnect_tp", song.getString("appPackage"))
        assertEquals(1.25, song.getDouble("speed"), 0.0)
        assertEquals(LINES_EARLY_MS, song.getLong("earlyMs"))
        // Nothing says how far ahead a player is until it's heard, so nothing is guessed.
        assertFalse(song.has("leadMs"))
        val log = song.getString("log")
        // Stamped with the phone's clock, which isn't at nothing.
        assertTrue(log, Regex("""(?m)^[1-9]\d* lines in time 0>500""").containsMatchIn(log))
        // Which line was lit, when, and where the player's clock had the song then.
        assertTrue(log, Regex("""lit -?\d+ at \d+ pos \d+""").containsMatchIn(log))
        assertFalse(log, "a moment later" in log)
        // Only positions, times and speeds: nothing of the song or the user.
        assertFalse(log, listOf("Demo", "Band", "KDE", "kdeconnect").any { it in log })
    }

    @Test
    fun wordsMovedByHandAreLoggedAtTheLastLineMovedEvenWhenTheUserAsksToReportLater() {
        openLyrics()
        followedInKdeConnect(1f)
        composeRule.runOnIdle { model().nudgeSync(300) }
        composeRule.runOnIdle { TimingLog.note("a moment later") }
        composeRule.runOnIdle { model().sendReport("Still late") }
        waitUntil { sentReports().isNotEmpty() }
        val song = sentReport().getJSONObject("song")
        assertEquals("org.kde.kdeconnect_tp", song.getString("appPackage"))
        assertFalse(song.getString("log").contains("a moment later"))
        assertTrue(song.getString("log").contains("lines in time 0>300"))
    }

    @Test
    fun aProblemThatIsNotAboutTimingSaysNothingOfIt() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("[]")
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "KDE Connect", canSeek = true, appPackage = "org.kde.kdeconnect_tp")
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        waitUntil { problems() == listOf("no_lyrics") }
        val song = sentReport().getJSONObject("song")
        assertEquals("KDE Connect", song.getString("app"))
        listOf("appPackage", "speed", "leadMs", "earlyMs", "log").forEach { assertFalse(it, song.has(it)) }
    }

    @Test
    fun aReportOnWordsNobodyTimedByHandSaysNothingOfTiming() {
        report()
        click(string(R.string.report_send))
        waitUntil { sentReports().isNotEmpty() }
        val song = sentReport().getJSONObject("song")
        listOf("appPackage", "speed", "leadMs", "earlyMs", "log").forEach { assertFalse(it, song.has(it)) }
    }

    @Test
    fun theAppOfTheWordsLeftIsNotNamedForTheNextOnes() {
        openLyrics()
        playback.playing.value = null
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasText(string(R.string.lyrics_button)) or hasContentDescription(string(R.string.lyrics_button)))[0].performScrollTo().performClick()
        waitUntil { shown("Demo one") }
        composeRule.runOnIdle { model().sendReport("") }
        waitUntil { sentReports().isNotEmpty() }
        assertFalse(sentReport().getJSONObject("song").has("app"))
    }

    @Test
    fun anInstrumentalHasNoWordsToReport() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("""[{"trackName":"Demo","artistName":"Band","instrumental":true}]""")
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_instrumental))).fetchSemanticsNodes().isNotEmpty() }
        settled()
        assertEquals(emptyList<String>(), problems())
        // Nor is it offered, while the user hasn't said.
        controller!!.destroyAndSettle()
        prefs().edit().remove("auto_reports").commit()
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_instrumental))).fetchSemanticsNodes().isNotEmpty() }
        settled()
        assertFalse(shown(offer))
    }

    @Test
    fun saidNoInSettingsItNeverAsksAgainNorSends() {
        prefs().edit().putBoolean("auto_reports", false).commit()
        lyricsAre("[]")
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        settled()
        assertFalse(shown(offer))
        assertEquals(false, model().uiState.autoReports)
        // Another song with a problem, words with no timing this time.
        controller!!.destroyAndSettle()
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
        controller!!.destroyAndSettle()
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
        yes()
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
            controller!!.destroyAndSettle()
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
    fun severalProblemsInOneSongAreOfferedOnceAndYesSendsEveryOneFound() {
        prefs().edit().putBoolean("lyrics_chords", true).commit()
        openLyrics()
        waitUntil { shown(offer) }
        settled()
        // Chords weren't found; then the words are put in time by hand, which is another problem with the same song.
        waitUntil { model().uiState.chords.loading.not() }
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodes(hasText(offer)).fetchSemanticsNodes().size)
        yes()
        // The chords' one goes now; the timing one once the words are left, so the user can go on with them.
        waitUntil { problems() == listOf("no_chords") }
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { problems() == listOf("no_chords", "timed_by_hand") }
    }

    @Test
    fun theOfferClosedIsNotANoAndIsNotMadeAgainInTheSameSessionButIsInTheNext() {
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
        notNow()
        assertFalse(shown(offer))
        // Not a no: nothing saved as the answer, nothing sent, the switch in Settings as it was.
        assertFalse(prefs().contains("auto_reports"))
        assertNull(model().uiState.autoReports)
        assertEquals(1, prefs().getInt("auto_reports_closes", 0))
        assertEquals(emptyList<String>(), problems())
        // Another problem with the same song, in the same session: not asked again.
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.waitForIdle()
        assertFalse(shown(offer))
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), problems())
        // The app started anew asks again.
        startedAnew()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
    }

    @Test
    fun theThirdCloseOfTheOfferIsANoForGoodAndSettingsStillWins() {
        repeat(3) {
            openLyrics()
            composeRule.runOnIdle { model().nudgeSync(500) }
            waitUntil { shown(offer) }
            notNow()
            startedAnew()
        }
        assertEquals(false, prefs().getBoolean("auto_reports", true))
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.waitForIdle()
        assertFalse(shown(offer))
        assertEquals(false, model().uiState.autoReports)
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), problems())
        // Turned on in Settings, it's on.
        composeRule.runOnIdle { model().allowAutoReports(true) }
        assertEquals(true, model().uiState.autoReports)
        assertEquals(true, prefs().getBoolean("auto_reports", false))
    }

    @Test
    fun theThirdCloseOfTheOfferStillLeavesTheTimingReportOwedForSettings() {
        // The third close is a no, but what was put in time stays on the phone, owed.
        prefs().edit().putInt("auto_reports_closes", 2).commit()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
        notNow()
        assertEquals(false, model().uiState.autoReports)
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), problems())
        assertEquals(1, owed())
        // Turned on in Settings, it goes, though the words are no longer there.
        composeRule.runOnIdle { model().allowAutoReports(true) }
        waitUntil { problems() == listOf("timed_by_hand") }
        waitUntil { owed() == 0 }
    }

    @Test
    fun aTimingReportOwedSurvivesTheAppBeingKilledAndGoesAtTheNextStartOnce() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.waitForIdle()
        // Not sent while the words are still in front of the user, who may put them in time some more; but owed.
        assertEquals(emptyList<String>(), problems())
        assertEquals(1, owed())
        // Killed before they were left.
        controller!!.destroyAndSettle()
        assertEquals(emptyList<String>(), problems())
        openLyrics()
        waitUntil { problems() == listOf("timed_by_hand") }
        val sent = sentReports().single()
        assertEquals("Demo", sent.getJSONObject("song").getString("title"))
        val timing = sent.getJSONObject("song").getJSONArray("timing").getJSONObject(0)
        assertEquals(500L, timing.getLong("heardMs") - timing.getLong("timedMs"))
        waitUntil { owed() == 0 }
        // Once a song: put in time again and left, it doesn't go again.
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(listOf("timed_by_hand"), problems())
    }

    @Test
    fun aYesAfterTheWordsWerePutInTimeByHandOwesTheReportToo() {
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
        // Owed from the moment they were put in time, whatever the answer.
        assertEquals(1, owed())
        yes()
        assertEquals(1, owed())
        controller!!.destroyAndSettle()
        openLyrics()
        waitUntil { problems() == listOf("timed_by_hand") }
    }

    @Test
    fun aTimingReportOwedThatDidntGetThereGoesAtALaterStart() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        controller!!.destroyAndSettle()
        reportsDown = true
        openLyrics()
        waitUntil { problems() == listOf("timed_by_hand") }
        assertEquals(1, owed())
        controller!!.destroyAndSettle()
        reportsDown = false
        openLyrics()
        waitUntil { problems() == listOf("timed_by_hand", "timed_by_hand") }
        waitUntil { owed() == 0 }
    }

    /** [songs] put in time before, whose reports wait on the phone: made up, as what they say doesn't matter. */
    private fun owedFor(vararg songs: String) = songs.forEach { AutoReports(prefs()).owe(MusicMetadata(it, "Band"), """{"fake":"$it"}""") }

    private fun sentFakes() = fake.requestedUrls.indices.filter { fake.requestedUrls[it] == ReportSender.URL }.map { fake.requestBodies[it] }

    @Test
    fun noTimingReportIsOwedForWordsPutBackAsTheyWere() {
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        assertEquals(1, owed())
        composeRule.runOnIdle { model().resetSync() }
        assertEquals(0, owed())
        composeRule.runOnIdle { model().nudgeSync(500) }
        assertEquals(1, owed())
        // Said no in Settings, it still waits, on the phone, for a yes.
        composeRule.runOnIdle { model().allowAutoReports(false) }
        assertEquals(1, owed())
        controller!!.destroyAndSettle()
        openLyrics()
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), problems())
        assertEquals(1, owed())
    }

    @Test
    fun aYesFromTheOfferSendsEveryTimingReportOwedForOtherSongsOnce() {
        owedFor("Old one", "Old two")
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
        yes()
        waitUntil { sentFakes().size == 2 }
        assertEquals(setOf("""{"fake":"Old one"}""", """{"fake":"Old two"}"""), sentFakes().toSet())
        // The song in front of the user waits to be left, as it may be put in time some more; the others are dropped.
        waitUntil { owed() == 1 }
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { problems().count { it == "timed_by_hand" } == 1 }
        waitUntil { owed() == 0 }
        // Said again, nothing goes twice.
        composeRule.runOnIdle { model().allowAutoReports(true) }
        composeRule.waitForIdle()
        assertEquals(3, sentFakes().size)
    }

    @Test
    fun turningReportsOnInSettingsSendsEveryTimingReportOwed() {
        owedFor("Old one", "Old two")
        openLyrics()
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.runOnIdle { model().allowAutoReports(true) }
        waitUntil { sentFakes().size == 2 }
        waitUntil { owed() == 0 }
        assertEquals(setOf("""{"fake":"Old one"}""", """{"fake":"Old two"}"""), sentFakes().toSet())
        assertTrue(AutoReports(prefs()).reported(MusicMetadata("Old one", "Band"), SongProblem.TIMED_BY_HAND))
    }

    @Test
    fun timingReportsOwedThatDidntGetThereWaitAndGoOnceTheUserIsOnline() {
        owedFor("Old one", "Old two")
        reportsDown = true
        openLyrics()
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.runOnIdle { model().allowAutoReports(true) }
        waitUntil { sentFakes().size == 1 }
        // Neither got there: both stay, and the first failure doesn't stop the second from being tried.
        waitUntil { sentFakes().size == 2 }
        assertEquals(2, owed())
        reportsDown = false
        // The app started anew: they go at the start.
        controller!!.destroyAndSettle()
        openLyrics()
        waitUntil { owed() == 0 }
        assertEquals(4, sentFakes().size)
    }

    @Test
    fun onlyTheLatestTwentyTimingReportsAreOwed() {
        owedFor(*(1..21).map { "Song $it" }.toTypedArray())
        assertEquals(20, owed())
        openLyrics()
        composeRule.runOnIdle { model().allowAutoReports(true) }
        waitUntil { owed() == 0 }
        assertEquals(20, sentFakes().size)
        assertFalse(sentFakes().contains("""{"fake":"Song 1"}"""))
    }

    @Test
    fun aTimingReportOwedGoesAtEachStartWhileReportsAreAllowedAndNeverWhenTheyAreNot() {
        owedFor("Old one")
        prefs().edit().putBoolean("auto_reports", false).commit()
        openLyrics()
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), sentFakes())
        assertEquals(1, owed())
        controller!!.destroyAndSettle()
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        waitUntil { sentFakes().size == 1 }
        waitUntil { owed() == 0 }
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
        override fun darken(shade: Float, ceiling: Float) = Unit
        override fun finish() = Unit
        override fun close() = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val OFFER_ARMED_MS = 500L
    }
}
