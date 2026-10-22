package com.astrovm.crosstune

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.swipeDown

import androidx.compose.ui.test.hasClickAction

import androidx.compose.ui.test.longClick

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.graphics.asAndroidBitmap

import androidx.compose.ui.test.performSemanticsAction

import androidx.compose.ui.semantics.SemanticsActions

import androidx.lifecycle.ViewModelProvider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import android.os.Looper
import android.Manifest
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.shadow.api.Shadow
import org.robolectric.android.controller.ServiceController
import android.view.WindowManager
import android.view.View
import android.widget.ImageView
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.app.NotificationManager
import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import java.text.NumberFormat
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Crosstune naming a song playing nearby itself: asking for the microphone, listening, and what it finds. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class ListeningTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var microphone = FakeMicrophone()
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.microphoneFactory = { microphone }
        // Unit tests have no GPU or native library to draw visuals with.
        MainActivity.milkdropFactory = { NoMilkdrop }
        MainActivity.soundTapFactory = { null }
        // Visuals are on from the start; the tests about turning them on start with them off.
        prefs().edit().putBoolean("setup_complete", true).putBoolean("exact_match", false).putBoolean("visuals", false)
            .putBoolean(SongRecognizers.KEY_PICK_RESET, true).commit()
        File(app.cacheDir, "lookups.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "sung_readings.json").delete()
        MainActivity.lyricsBusyPauseMs = 0
        shazamAnswers(MATCH)
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        // Anything still listening hears the end.
        microphone.end()
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.microphoneFactory = { AudioRecordMicrophone() }
        MainActivity.listenAlongPauseMs = MainViewModel.LISTEN_ALONG_PAUSE_MS
        MainActivity.quietPauseMs = MainViewModel.LISTEN_ALONG_PAUSE_MS
        MainActivity.hearingFactory = null
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.lyricsBusyPauseMs = LYRICS_BUSY_PAUSE_MS
        MainActivity.milkdropFactory = ::NativeMilkdrop
        MainActivity.soundTapFactory = { OutputMixTap.open() }
        MainActivity.lyricsLanguageFactory = null
        FloatingLyricsService.host = null
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun launch(intent: Intent = Intent(Intent.ACTION_MAIN).setClass(app, MainActivity::class.java)): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        return controller!!.get()
    }

    private fun allowMicrophone() = shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

    /** Shazam answers [body]; Apple Music knows Iris. */
    private fun shazamAnswers(body: String, code: Int = 200) {
        fake.handler = { request ->
            when (request.url.host) {
                "amp.shazam.com" -> FakeSpotify.html(request, body, code = code)
                else -> FakeSpotify.html(request, """{"results":[{"trackName":"Iris","artistName":"The Goo Goo Dolls"}]}""")
            }
        }
    }

    private fun shazamRequests() = fake.requestedUrls.count { it.startsWith("https://amp.shazam.com/") }

    private fun click(text: String) {
        composeRule.onNode(hasText(text) or hasContentDescription(text)).performClick()
        composeRule.waitForIdle()
    }

    /**
     * Waits for [condition], meanwhile running what background work hands back to the main thread,
     * which Compose's own wait leaves queued until something else runs it: on a slow machine, words
     * found in the background never reached the screen.
     */
    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) =
        composeRule.waitUntil(timeoutMs) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    private fun barShown() = composeRule.onAllNodesWithTag(NOW_PLAYING_TAG).fetchSemanticsNodes().isNotEmpty()

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) = waitUntil(TIMEOUT_MS) { shown(text) }

    private fun waitForDescription(label: String) = waitUntil(TIMEOUT_MS) { described(label) }

    /** Whether [reading] is drawn over a line's words. */
    private fun readingShown(reading: String) = composeRule.onAllNodes(
        androidx.compose.ui.test.SemanticsMatcher("reads $reading") { reading in it.config.getOrElseNullable(ReadingsShown) { null }.orEmpty() },
        useUnmergedTree = true
    ).fetchSemanticsNodes().isNotEmpty()

    private fun recognize() = click(string(R.string.recognize_button))

    @Test
    fun aSongPlayingOnThePhoneIsNamedByItsAppWithoutListening() {
        val playing = MusicMetadata("Iris", "The Goo Goo Dolls")
        MainActivity.playbackFactory = { FakePlayback().apply { access = true; onThePhone = Heard.Song(playing, null, 42_000, SystemClock.elapsedRealtime()) } }
        allowMicrophone()
        launch()
        recognize()
        waitForText("Iris")
        composeRule.onNodeWithTag(RESULT_TAG).assertExists()
        assertEquals(0, microphone.opened)
        assertEquals(0, shazamRequests())
        assertEquals(playing, HistoryStore(prefs()).load().first().metadata)
    }

    @Test
    fun aSongPlayingAsTheAppOpensIsOfferedWithItsWordsAlreadyFetched() {
        val playing = MusicMetadata("Demo", "Band")
        val phone = FakePlayback().apply {
            access = true
            onThePhone = Heard.Song(playing, null, 1_000, SystemClock.elapsedRealtime())
            this.playing.value = Following(PlaybackClock(1_000, SystemClock.elapsedRealtime(), playing = true), "Music", canSeek = true, appPackage = MusicService.YOUTUBE_MUSIC.packageName)
        }
        MainActivity.playbackFactory = { phone }
        fake.handler = { request -> FakeSpotify.html(request, """[{"trackName":"Demo","artistName":"Band","plainLyrics":"Night sky\nStars"}]""") }
        launch()
        waitUntil(TIMEOUT_MS) { barShown() }
        assertTrue(shown("Band \u00b7 Music"))
        // Fetched before they're asked for, so asking shows them with no wait.
        val kept = java.io.File(app.cacheDir, "lyrics.json")
        waitUntil(TIMEOUT_MS) { kept.exists() && "Stars" in kept.readText() }
        composeRule.onNodeWithTag(NOW_PLAYING_TAG).performClick()
        waitForDescription(string(R.string.lyrics_button))
        click(string(R.string.lyrics_button))
        waitForText("Night sky\nStars")
        // Shown from what was kept: the sites the words come from were each asked once.
        assertEquals(1, fake.requestedUrls.count { it.startsWith("https://music.163.com/") })
        // Named by the app, nothing was listened to.
        assertEquals(0, microphone.opened)
    }

    @Test
    fun tappingTheSongPlayingShowsItAndItsOfferGoes() {
        val playing = MusicMetadata("Iris", "The Goo Goo Dolls")
        MainActivity.playbackFactory = { FakePlayback().apply { access = true; onThePhone = Heard.Song(playing, null, 42_000, SystemClock.elapsedRealtime()) } }
        launch()
        waitUntil(TIMEOUT_MS) { barShown() }
        click("Iris")
        // The song's player opens, so the bar has nothing left to offer.
        waitUntil(TIMEOUT_MS) { !barShown() }
        assertFalse(barShown())
        assertEquals(0, microphone.opened)
    }

    @Test
    fun theBarSwipesUpToThePlayerAndStaysWithTheSongsCardUnderIt() {
        val playing = MusicMetadata("Iris", "The Goo Goo Dolls")
        MainActivity.playbackFactory = { FakePlayback().apply { access = true; onThePhone = Heard.Song(playing, null, 42_000, SystemClock.elapsedRealtime()) } }
        launch()
        waitUntil(TIMEOUT_MS) { barShown() }
        // A swipe the way a finger goes, well past the bar.
        composeRule.onNodeWithTag(NOW_PLAYING_TAG).performTouchInput { swipe(center, center - Offset(0f, 1500f)) }
        // Its player opens, with the song's card made under it for its link tools.
        waitForDescription(string(R.string.more_button))
        assertFalse(barShown())
        click(string(R.string.dismiss_button))
        // The card shows the song that plays, and the bar stays, a swipe from its player.
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithTag(RESULT_TAG).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(barShown())
        assertEquals(playing, HistoryStore(prefs()).load().first().metadata)
    }

    @Test
    fun aShortSwipeUpFromTheBarGoesBackAndLeavesTheBarBe() {
        val playing = MusicMetadata("Iris", "The Goo Goo Dolls")
        MainActivity.playbackFactory = { FakePlayback().apply { access = true; onThePhone = Heard.Song(playing, null, 42_000, SystemClock.elapsedRealtime()) } }
        launch()
        waitUntil(TIMEOUT_MS) { barShown() }
        composeRule.onNodeWithTag(NOW_PLAYING_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, -80f))
            up()
        }
        // The player opened as the finger began, and is put away again as it lets go early.
        waitUntil(TIMEOUT_MS) { barShown() && composeRule.onAllNodesWithContentDescription(string(R.string.more_button)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aSwipeUpFromTheBarTakenAwayHalfwayGoesBackToo() {
        val playing = MusicMetadata("Iris", "The Goo Goo Dolls")
        MainActivity.playbackFactory = { FakePlayback().apply { access = true; onThePhone = Heard.Song(playing, null, 42_000, SystemClock.elapsedRealtime()) } }
        launch()
        waitUntil(TIMEOUT_MS) { barShown() }
        composeRule.onNodeWithTag(NOW_PLAYING_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, -80f))
            cancel()
        }
        waitUntil(TIMEOUT_MS) { barShown() && composeRule.onAllNodesWithContentDescription(string(R.string.more_button)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theWordsPulledDownALittleAndLetGoStay() {
        wordsOf(null, "Night sky\\nStars")
        waitUntil(TIMEOUT_MS) { shown("Night sky\nStars") }
        composeRule.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, 150f))
            // Back up, the pull is undone before the words scroll.
            moveBy(Offset(0f, -100f))
            moveBy(Offset(0f, -100f))
            up()
        }
        composeRule.waitForIdle()
        assertTrue(shown("Night sky\nStars"))
    }

    @Test
    fun theOfferUpdatesWhileTheScreenStaysOpenAndResubscribesOnResume() {
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        launch()
        assertFalse(barShown())
        val first = Heard.Song(MusicMetadata("First Test Song", "First Test Artist"), null, 1000, SystemClock.elapsedRealtime())
        composeRule.runOnIdle { phone.onThePhone = first }
        waitForText("First Test Song")
        assertTrue(barShown())
        composeRule.runOnIdle { phone.onThePhone = null }
        composeRule.waitForIdle()
        assertFalse(barShown())
        val next = first.copy(metadata = MusicMetadata("Next Test Song", "Next Test Artist"))
        composeRule.runOnIdle { phone.onThePhone = next }
        waitForText("Next Test Song")
        assertFalse(shown("First Test Song"))
        // Position updates don't keep asking for the same song's lyrics.
        composeRule.runOnIdle { phone.onThePhone = next.copy(offsetMs = 2000) }
        composeRule.waitForIdle()
        assertTrue(shown("Next Test Song"))
        controller!!.pause()
        val model = ViewModelProvider(controller!!.get())[MainViewModel::class.java]
        phone.onThePhone = null
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(next.metadata, model.uiState.playingNow?.song)
        controller!!.resume()
        composeRule.waitForIdle()
        assertFalse(barShown())
        composeRule.runOnIdle { phone.onThePhone = first }
        waitForText("First Test Song")
        click("First Test Song")
        // The song's player opens, so the bar has nothing left to offer.
        waitUntil(TIMEOUT_MS) { !barShown() }
    }

    @Test
    fun theBarForTheSongPlayingPausesSkipsAndOpensItsPlayerAndStaysWhilePaused() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        phone.onThePhone = Heard.Song(MusicMetadata("Iris", "The Goo Goo Dolls"), null, 42_000, SystemClock.elapsedRealtime())
        launch()
        waitUntil(TIMEOUT_MS) { barShown() }
        // Before its app says where it is, it's the song and its artist.
        assertTrue(shown("The Goo Goo Dolls"))
        val now = SystemClock.elapsedRealtime()
        phone.playing.value = Following(
            PlaybackClock(60_000, now, playing = true), "YouTube Music", canSeek = true, appPackage = youTubeMusic,
            canSkipNext = true, durationMs = 200_000
        )
        waitForText("The Goo Goo Dolls \u00b7 YouTube Music")
        // Its buttons are the app's.
        click(string(R.string.lyrics_pause))
        assertEquals(listOf(false), phone.plays)
        click(string(R.string.lyrics_next_song))
        assertEquals(listOf(true), phone.skips)
        // The app can't go on to another song.
        phone.playing.value = Following(PlaybackClock(60_000, now, playing = true), "YouTube Music", canSeek = true, appPackage = youTubeMusic, durationMs = 200_000)
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_next_song)) and !isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        // Paused, the app stops saying it plays it, but still has it: the bar stays, to play it again.
        phone.playing.value = Following(PlaybackClock(60_000, now, playing = false), "YouTube Music", canSeek = true, appPackage = youTubeMusic, durationMs = 200_000)
        phone.onThePhone = null
        waitForDescription(string(R.string.lyrics_play))
        assertTrue(barShown())
        click(string(R.string.lyrics_play))
        assertEquals(listOf(false, true), phone.plays)
        // Returning while it's still paused keeps the song, but checks that its app still has it.
        controller!!.pause().stop().restart().resume()
        waitForDescription(string(R.string.lyrics_play))
        assertTrue(barShown())
        // A tap on the song opens its player, and closing that brings the bar back.
        click("Iris")
        waitForDescription(string(R.string.player_playing_in, "YouTube Music"))
        assertFalse(barShown())
        click(string(R.string.dismiss_button))
        assertTrue(barShown())
        // The app letting it go takes the bar away.
        phone.playing.value = null
        waitUntil(TIMEOUT_MS) { !barShown() }
        // An old offer mustn't come back after its app has let the song go.
        controller!!.pause().stop().restart().resume()
        composeRule.waitForIdle()
        assertFalse(barShown())
    }

    @Test
    fun theSongPlayingIsOfferedOnlyWhileNothingElseIsOnScreen() {
        val phone = FakePlayback().apply { access = true; onThePhone = Heard.Song(MusicMetadata("Iris", "The Goo Goo Dolls"), null, 42_000, SystemClock.elapsedRealtime()) }
        MainActivity.playbackFactory = { phone }
        shazamAnswers(NO_MATCH)
        allowMicrophone()
        launch()
        waitUntil(TIMEOUT_MS) { barShown() }
        // Typing a song in, it gives way.
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Other Song")
        composeRule.waitForIdle()
        assertFalse(barShown())
        composeRule.onAllNodes(hasSetTextAction())[0].performTextClearance()
        waitUntil(TIMEOUT_MS) { barShown() }
        // Stopped meanwhile, Crosstune listens itself, and what it heard is shown alone.
        phone.onThePhone = null
        recognize()
        waitForText(string(R.string.listening_text))
        assertFalse(barShown())
        microphone.play(1.0)
        microphone.end()
        waitForText(string(R.string.error_no_match))
        assertFalse(barShown())
    }

    @Test
    fun anotherSongOpenKeepsTheSongPlayingOfferedAndClosesWithItsBoxEmpty() {
        val url = "https://www.google.com/search?q=A%20Song%20by%20Example%20Band"
        HistoryStore(prefs()).add(HistoryEntry(MusicLink(null, ItemType.TRACK, url, url), MusicMetadata("A Song", "Example Band")))
        MainActivity.playbackFactory = { FakePlayback().apply { access = true; onThePhone = Heard.Song(MusicMetadata("Iris", "The Goo Goo Dolls"), null, 42_000, SystemClock.elapsedRealtime()) } }
        launch()
        waitUntil(TIMEOUT_MS) { barShown() }
        // Found by its name, the song leaves the box empty, yet still closes.
        click("A Song")
        composeRule.onNodeWithTag(RESULT_TAG).assertExists()
        assertTrue(barShown())
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Other Song")
        composeRule.waitForIdle()
        assertFalse(barShown())
        composeRule.onAllNodes(hasSetTextAction())[0].performTextClearance()
        waitUntil(TIMEOUT_MS) { barShown() }
        click(string(R.string.clear_button))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RESULT_TAG).assertDoesNotExist()
        assertTrue(barShown())
    }

    @Test
    fun withoutAccessToMusicAppsNothingIsOfferedAsPlaying() {
        val playing = MusicMetadata("Iris", "The Goo Goo Dolls")
        val phone = FakePlayback().apply { access = true; onThePhone = Heard.Song(playing, null, 42_000, SystemClock.elapsedRealtime()) }
        MainActivity.playbackFactory = { phone }
        launch()
        waitUntil(TIMEOUT_MS) { barShown() }
        // Access taken away while away, what was offered goes with it.
        phone.access = false
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertFalse(barShown())
        assertFalse(shown("Iris"))
    }

    @Test
    fun itListensThenShowsTheSongLikeAShazamLink() {
        allowMicrophone()
        launch()
        // With no other app, Crosstune listens itself.
        recognize()
        // It listens until there's something to hear.
        waitForText(string(R.string.listening_text))
        assertEquals(1, microphone.opened)
        microphone.play(3.0)
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(hasText("Iris")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(RESULT_TAG).assertExists()
        assertTrue(shown("https://music.apple.com/us/song/1109658204"))
        assertTrue(fake.requestedUrls.contains("https://itunes.apple.com/lookup?id=1109658204&country=us"))
        assertTrue(microphone.closed)
        assertEquals(MusicService.APPLE_MUSIC, HistoryStore(prefs()).load().first().link.service)
        assertTrue(!shown(string(R.string.listening_text)))
    }

    @Test
    fun theWordsOfASongHeardFollowOnFromWhereItWasHeard() {
        // Shazam says the sound sent starts 12.5 seconds into the song.
        val synced = """[{"trackName":"Demo","artistName":"Band","syncedLyrics":"[00:00.00] Start\n[00:10.00] Ten\n[10:00.00] Late"}]"""
        fake.handler = { request ->
            when (request.url.host) {
                "amp.shazam.com" -> FakeSpotify.html(request, """{"matches":[{"id":"1","offset":12.5}],"track":{"title":"Demo","subtitle":"Band"}}""")
                "lrclib.net" -> FakeSpotify.html(request, synced)
                else -> FakeSpotify.html(request, "{}")
            }
        }
        allowMicrophone()
        launch()
        microphone.play(3.0)
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        // No music app plays it, so it follows what Crosstune heard: the line sung from 10 seconds in.
        waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(hasText("Ten") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("Late") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)).assertExists()
    }

    @Test
    fun aSongAppleMusicDoesntHaveIsKnownByName() {
        shazamAnswers("""{"matches":[{"id":"1"}],"track":{"title":"Demo","subtitle":"Band","images":{"coverart":"https://example.com/demo.jpg"}}}""")
        allowMicrophone()
        launch()
        microphone.play(3.0)
        recognize()
        waitForText("Demo")
        composeRule.onNodeWithTag(RESULT_TAG).assertExists()
        val saved = HistoryStore(prefs()).load().first()
        assertEquals(MusicMetadata("Demo", "Band", artworkUrl = "https://example.com/demo.jpg"), saved.metadata)
        assertEquals(null, saved.link.service)
        // Shazam's cover is enough; nothing else is looked up.
        assertEquals(listOf("amp.shazam.com", "example.com"), fake.requestedUrls.map { it.toHttpUrl().host }.distinct())
    }

    @Test
    fun noMatchSaysSoAndTryAgainListensAgain() {
        shazamAnswers(NO_MATCH)
        allowMicrophone()
        launch()
        microphone.play(1.0)
        microphone.end()
        recognize()
        waitForText(string(R.string.error_no_match))
        assertEquals(1, shazamRequests())

        shazamAnswers(MATCH)
        microphone.play(1.0)
        microphone.end()
        click(string(R.string.retry_button))
        waitForText("Iris")
        assertEquals(2, microphone.opened)
        assertTrue(!shown(string(R.string.error_no_match)))
    }

    @Test
    fun offlineItsTheNetworkError() {
        fake.handler = { throw IOException("offline") }
        allowMicrophone()
        launch()
        // Asked again with more sound once, in case it was a moment, then it says so.
        microphone.play(12.0)
        recognize()
        waitForText(string(R.string.error_network))
        // Try again listens again, rather than looking anything up.
        shazamAnswers(MATCH)
        microphone.play(3.0)
        click(string(R.string.retry_button))
        waitForText("Iris")
        assertEquals(2, microphone.opened)
    }

    @Test
    fun cancelStopsListening() {
        allowMicrophone()
        launch()
        recognize()
        waitForText(string(R.string.listening_text))
        click(string(R.string.cancel_button))
        waitUntil(TIMEOUT_MS) { microphone.closed }
        waitUntil(TIMEOUT_MS) { !shown(string(R.string.listening_text)) }
        assertEquals(0, shazamRequests())
        assertTrue(composeRule.onAllNodes(hasText(string(R.string.error_no_match))).fetchSemanticsNodes().isEmpty())
        // Back stops it too.
        recognize()
        waitForText(string(R.string.listening_text))
        assertEquals(2, microphone.opened)
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        waitUntil(TIMEOUT_MS) { microphone.closed }
        waitUntil(TIMEOUT_MS) { !shown(string(R.string.listening_text)) }
    }

    @Test
    fun leavingCrosstuneStopsListeningButTurningThePhoneDoesnt() {
        allowMicrophone()
        launch()
        recognize()
        waitForText(string(R.string.listening_text))
        // A rotation recreates the screen, still listening.
        controller = controller!!.recreate()
        composeRule.waitForIdle()
        assertTrue(shown(string(R.string.listening_text)))
        assertTrue(!microphone.closed)
        controller!!.pause().stop()
        waitUntil(TIMEOUT_MS) { microphone.closed }
        controller!!.start().resume()
        composeRule.waitForIdle()
        assertTrue(!shown(string(R.string.listening_text)))
    }

    @Test
    fun itAsksForTheMicrophoneFirstAndOffersSettingsWithoutIt() {
        val activity = launch()
        recognize()
        val asked = shadowOf(activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), asked.requestedPermissions.toList())
        assertEquals(0, microphone.opened)
        activity.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        composeRule.waitForIdle()
        waitForText(string(R.string.error_microphone))
        // No Try again: Android won't ask again, so the way is in its settings.
        assertTrue(!described(string(R.string.retry_button)))
        click(string(R.string.open_settings_button))
        val settings = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
        assertEquals("package:${app.packageName}", settings.dataString)

        // Allowed, it listens right away.
        controller!!.pause().stop().destroy()
        val next = launch()
        recognize()
        val again = shadowOf(next).lastRequestedPermission
        allowMicrophone()
        next.onRequestPermissionsResult(again.requestCode, again.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        composeRule.waitForIdle()
        waitForText(string(R.string.listening_text))
        assertEquals(1, microphone.opened)
    }

    @Test
    fun theMicrophoneAskedForToListenAlongIsForThatThoughTheScreenTurns() {
        val hearing = FakeHearing()
        demoWords(hearing)
        click(string(R.string.lyrics_stop_listening))
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        click(string(R.string.lyrics_listen_along))
        val asked = shadowOf(controller!!.get()).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), asked.requestedPermissions.toList())
        // Turned while Android asks, then allowed: it listens along, rather than for a new song.
        controller!!.recreate()
        allowMicrophone()
        controller!!.get().onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        hearing.song("Demo", 3.0)
        waitUntil(TIMEOUT_MS) { described(string(R.string.lyrics_stop_listening)) }
        assertFalse(controllerModel().uiState.listening)
    }

    @Test
    fun theFloatingWordsMicrophoneAsksForItBackInCrosstune() {
        val hearing = FakeHearing()
        floatOverApps(hearing)
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        composeRule.waitForIdle()
        click(string(R.string.lyrics_stop_listening))
        // Taken back meanwhile, the microphone is asked for in Crosstune, which comes to the front.
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        click(string(R.string.lyrics_listen_along))
        assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component!!.className)
        val before = hearing.listens
        controller!!.restart()
        val asked = shadowOf(controller!!.get()).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), asked.requestedPermissions.toList())
        allowMicrophone()
        controller!!.get().onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        waitUntil(TIMEOUT_MS) { hearing.listens > before }
        // Asked once: back again, it isn't asked again.
        controller!!.pause().stop()
        controller!!.restart()
        assertEquals(asked, shadowOf(controller!!.get()).lastRequestedPermission)
    }

    @Test
    fun whenShazamIsBusyTheOtherAppsAreOffered() {
        val pm = shadowOf(app.packageManager)
        pm.installPackage(installedApp(SongRecognizers.SHAZAM, "Shazam"))
        val tagging = ComponentName(SongRecognizers.SHAZAM, "Tagging")
        pm.addActivityIfNotPresent(tagging)
        pm.addIntentFilterForActivity(tagging, IntentFilter(SongRecognizers.SHAZAM_LISTEN).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        prefs().edit().putString(SongRecognizers.KEY_PICK, app.packageName).commit()
        shazamAnswers("", code = 429)
        allowMicrophone()
        launch()
        microphone.play(3.0)
        recognize()
        waitForText(string(R.string.error_recognition_unavailable))
        assertTrue(described(string(R.string.retry_button)))
        // Only the other apps: Crosstune is what just failed.
        assertTrue(!shown(string(R.string.open_app_button, string(R.string.app_name))))
        click(string(R.string.open_app_button, "Shazam"))
        assertEquals(SongRecognizers.SHAZAM_LISTEN, shadowOf(app).nextStartedActivity.action)
    }

    @Test
    fun theWidgetsButtonListensButOtherAppsCant() {
        allowMicrophone()
        launch(Intent(MainActivity.ACTION_LISTEN).setClassName(app, MainActivity.LISTEN_ALIAS))
        waitForText(string(R.string.listening_text))
        assertEquals(1, microphone.opened)
        controller!!.pause().stop().destroy()

        // MainActivity itself is exported; asked directly, it doesn't listen.
        microphone = FakeMicrophone()
        launch(Intent(MainActivity.ACTION_LISTEN).setClass(app, MainActivity::class.java))
        assertTrue(!shown(string(R.string.listening_text)))
        assertEquals(0, microphone.opened)
    }

    /** Hears whatever a test says, one song or silence per listen, and counts the listens. */
    private class FakeHearing : SongHearing {
        val next = Channel<Heard>(Channel.UNLIMITED)

        @Volatile
        var listens = 0

        override suspend fun listen(): Heard {
            listens++
            return next.receive()
        }

        /** [title] by Band, heard [seconds] into it, just now. */
        fun song(title: String, seconds: Double) =
            next.trySend(Heard.Song(MusicMetadata(title, "Band"), null, (seconds * 1000).toLong(), SystemClock.elapsedRealtime()))
    }

    /**
     * Listening along with [hearing] for ears; LRCLIB has "<title> one" at the start of each song,
     * "<title> two" from 10 seconds in, and "<title> three" from 40.
     */
    private fun listeningAlong(hearing: FakeHearing) {
        MainActivity.listenAlongPauseMs = 0
        MainActivity.quietPauseMs = 0
        MainActivity.hearingFactory = { hearing }
        timedWordsOfAnySong()
    }

    /** Every song's words are timed: "<title> one" from the start, "two" at 10 seconds and "three" at 40. */
    private fun timedWordsOfAnySong() {
        fake.handler = { request ->
            val title = request.url.queryParameter("track_name")
            FakeSpotify.html(request, """[{"trackName":"$title","artistName":"Band","syncedLyrics":"[00:00.00] $title one\n[00:10.00] $title two\n[00:40.00] $title three"}]""")
        }
    }

    /** [title] by Band in Recent, found by its name. */
    private fun inRecent(title: String) {
        val url = "https://www.google.com/search?q=${title.replace(" ", "%20")}%20by%20Band"
        HistoryStore(prefs()).add(HistoryEntry(MusicLink(null, ItemType.TRACK, url, url), MusicMetadata(title, "Band")))
    }

    /** A music app on the phone playing [song], 2 seconds in, that can go on to the next song and back. */
    private fun appPlaying(song: String? = "Demo", skips: Boolean = true) = FakePlayback().apply {
        access = true
        if (song != null) {
            onThePhone = Heard.Song(MusicMetadata(song, "Band"), null, 2_000, SystemClock.elapsedRealtime())
            playing.value = Following(PlaybackClock(2_000, SystemClock.elapsedRealtime()), "Music", canSeek = true, canSkipNext = skips, canSkipPrevious = skips)
        }
    }.also { phone -> MainActivity.playbackFactory = { phone } }

    /** The app moves on to [song], which it plays from the start. */
    private fun FakePlayback.goesOnTo(song: String) = composeRule.runOnIdle {
        playing.value = null
        onThePhone = Heard.Song(MusicMetadata(song, "Band"), null, 0, SystemClock.elapsedRealtime())
    }

    /** Opens the words of [title] from Recent, as from its card, below the song playing when one is. */
    private fun openWordsFromRecent(title: String) {
        composeRule.onAllNodes(hasText(title) and hasClickAction()).onLast().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_button)) or hasText(string(R.string.lyrics_button))).onLast().performClick()
        waitForText("$title one")
    }

    @Test
    fun wordsOfASongPlayingGoOnToTheNextSongTheAppPlays() {
        timedWordsOfAnySong()
        inRecent("Demo")
        val phone = appPlaying("Demo")
        launch()
        openWordsFromRecent("Demo")
        phone.goesOnTo("Other")
        waitForText("Other one")
        assertFalse(shown("Demo one"))
        // Named by the app, nothing was listened to, and it joins Recent.
        assertEquals(0, microphone.opened)
        assertEquals("Other", HistoryStore(prefs()).load().first().metadata.title)
    }

    @Test
    fun wordsOfASongPickedWhileAnotherPlaysStayUntilItPlays() {
        timedWordsOfAnySong()
        inRecent("Demo")
        val phone = appPlaying(song = null)
        phone.onThePhone = Heard.Song(MusicMetadata("Other", "Band"), null, 2_000, SystemClock.elapsedRealtime())
        launch()
        openWordsFromRecent("Demo")
        // Another song comes on: the words picked stay.
        composeRule.runOnIdle { phone.onThePhone = Heard.Song(MusicMetadata("Third", "Band"), null, 0, SystemClock.elapsedRealtime()) }
        Thread.sleep(300)
        composeRule.waitForIdle()
        assertTrue(shown("Demo one"))
        assertFalse(shown("Other one") || shown("Third one"))
        // Once the song picked plays, the next one takes over.
        composeRule.runOnIdle { phone.playing.value = Following(PlaybackClock(2_000, SystemClock.elapsedRealtime()), "Music", canSeek = true) }
        composeRule.waitForIdle()
        assertTrue(shown("Demo one"))
        phone.goesOnTo("Fourth")
        waitForText("Fourth one")
    }

    @Test
    fun theSongAnAppPlaysByAnotherNameKeepsItsWords() {
        timedWordsOfAnySong()
        inRecent("Demo")
        val phone = appPlaying("Demo")
        launch()
        openWordsFromRecent("Demo")
        // The app says it still plays the song shown, while what plays goes by another name.
        composeRule.runOnIdle { phone.onThePhone = Heard.Song(MusicMetadata("Demo (Remix)", "Band"), null, 3_000, SystemClock.elapsedRealtime()) }
        Thread.sleep(300)
        composeRule.waitForIdle()
        assertTrue(shown("Demo one"))
        assertFalse(shown("Demo (Remix) one"))
        // Paused there, it's still the song shown.
        composeRule.runOnIdle { phone.playing.value = Following(PlaybackClock(3_000, SystemClock.elapsedRealtime(), playing = false), "Music", canSeek = true) }
        Thread.sleep(300)
        composeRule.waitForIdle()
        assertTrue(shown("Demo one"))
        assertFalse(shown("Demo (Remix) one"))
    }

    @Test
    fun wordsAskedForFromTheSearchBarGoOnToTheNextSongTheAppPlays() {
        timedWordsOfAnySong()
        val phone = appPlaying("Demo")
        allowMicrophone()
        launch()
        click(string(R.string.tile_lyrics_label))
        waitForText("Demo one")
        phone.goesOnTo("Other")
        waitForText("Other one")
        assertEquals(0, microphone.opened)
    }

    @Test
    fun outOfSightTheWordsStayAndCatchUpOnceBack() {
        timedWordsOfAnySong()
        inRecent("Demo")
        val phone = appPlaying("Demo")
        launch()
        openWordsFromRecent("Demo")
        controller!!.pause().stop()
        phone.goesOnTo("Other")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Demo", controllerModel().uiState.lyricsFor?.title)
        assertFalse(fake.requestedUrls.any { "Other" in it })
        controller!!.restart().resume()
        waitForText("Other one")
    }

    @Test
    fun swipingTheSongHasTheAppGoOnToTheNextSongOrBack() {
        timedWordsOfAnySong()
        inRecent("Demo")
        val phone = appPlaying("Demo")
        launch()
        openWordsFromRecent("Demo")
        val song = composeRule.onNodeWithTag(LYRICS_SONG_TAG)
        // Held still, the song swiped away stays out while it waits for the next one.
        composeRule.mainClock.autoAdvance = false
        song.performTouchInput { swipeLeft() }
        composeRule.mainClock.advanceTimeBy(500)
        assertEquals(listOf(true), phone.skips)
        // Swiped a little way meanwhile, it's that little way that counts, not where it was: it stays.
        song.performTouchInput { swipe(center, center + Offset(width * 0.1f, 0f)) }
        composeRule.mainClock.advanceTimeBy(500)
        assertEquals(listOf(true), phone.skips)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        song.performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertEquals(listOf(true, false), phone.skips)
        // Taken away halfway, e.g. by the phone's own gesture, it springs back and skips nothing.
        song.performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.4f, 0f))
            moveBy(Offset(-width * 0.2f, 0f))
            cancel()
        }
        composeRule.waitForIdle()
        assertEquals(listOf(true, false), phone.skips)
        // A screen reader offers both.
        fun action(label: Int) = composeRule.runOnIdle {
            song.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == string(label) }.action()
        }
        action(R.string.lyrics_next_song)
        action(R.string.lyrics_previous_song)
        assertEquals(listOf(true, false, true, false), phone.skips)
        // The app goes on to the song skipped to, and its words show.
        phone.goesOnTo("Other")
        waitForText("Other one")
    }

    @Test
    fun swipingTheSongOfAnAppThatCantSkipDoesNothing() {
        timedWordsOfAnySong()
        inRecent("Demo")
        val phone = appPlaying("Demo", skips = false)
        launch()
        openWordsFromRecent("Demo")
        val song = composeRule.onNodeWithTag(LYRICS_SONG_TAG)
        song.performTouchInput { swipeLeft() }
        song.performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertTrue(phone.skips.isEmpty())
        assertFalse(SemanticsActions.CustomActions in song.fetchSemanticsNode().config)
        assertTrue(shown("Demo one"))
    }

    @Test
    fun wordsHeardWithTheMicrophoneCantBeSwipedToAnotherSong() {
        val hearing = FakeHearing()
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        demoWords(hearing)
        composeRule.onNodeWithTag(LYRICS_SONG_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertTrue(phone.skips.isEmpty())
        assertTrue(lit("Demo one"))
    }

    private fun described(label: String) = composeRule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()

    private fun lit(line: String) = composeRule.onAllNodes(hasText(line) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        .fetchSemanticsNodes().isNotEmpty()

    /** Recognizes Demo 2 seconds in, and opens its words, which keep listening along. */
    private fun demoWords(hearing: FakeHearing) {
        listeningAlong(hearing)
        allowMicrophone()
        launch()
        hearing.song("Demo", 2.0)
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        waitUntil(TIMEOUT_MS) { lit("Demo one") && described(string(R.string.lyrics_stop_listening)) }
    }

    @Test
    fun hearingNothingRestsTheMicrophoneUntilTheAppPlayingTheSongPlaysAgain() {
        val hearing = FakeHearing()
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        listeningAlong(hearing)
        MainActivity.quietPauseMs = 60_000
        allowMicrophone()
        launch()
        hearing.song("Demo", 2.0)
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        waitUntil(TIMEOUT_MS) { lit("Demo one") && hearing.listens == 2 }

        // Nothing heard, the music has stopped: the microphone rests rather than listening on and on.
        hearing.next.trySend(Heard.Nothing)
        Thread.sleep(300)
        composeRule.waitForIdle()
        assertEquals(2, hearing.listens)
        // The app playing it plays again, so it listens again straight away.
        phone.playing.value = Following(PlaybackClock(5_000, SystemClock.elapsedRealtime(), playing = false), "Music", canSeek = true)
        composeRule.waitForIdle()
        assertEquals(2, hearing.listens)
        phone.playing.value = Following(PlaybackClock(5_000, SystemClock.elapsedRealtime()), "Music", canSeek = true)
        waitUntil(TIMEOUT_MS) { hearing.listens == 3 }
    }

    @Test
    fun theMicrophoneTakenAwayWhileListeningAlongIsSaidOnTheWords() {
        val hearing = FakeHearing()
        demoWords(hearing)
        hearing.next.trySend(Heard.Failed(AppError.MICROPHONE))
        waitForText(string(AppError.MICROPHONE.messageRes))
        // Still on the words, with a way to allow it.
        assertTrue(lit("Demo one"))
        click(string(R.string.open_settings_button))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    @Test
    fun anotherSongHeardEarlyOnTakesOverThoughItsWhereTheSongShownIs() {
        val hearing = FakeHearing()
        demoWords(hearing)
        // A few seconds in, it may as well be the next song, skipped to.
        hearing.song("Other", 3.0)
        waitUntil(TIMEOUT_MS) { lit("Other one") }
    }

    @Test
    fun anotherReleaseOfTheSongHeardWhereItIsKeepsItsWordsButTheNextSongTakesOver() {
        val hearing = FakeHearing()
        demoWords(hearing)
        // Heard twice further on, as a skip is, it's 41 seconds in.
        hearing.song("Demo", 41.0)
        hearing.song("Demo", 41.0)
        waitUntil(TIMEOUT_MS) { lit("Demo three") }
        val asked = fake.requestedUrls.size
        // Well into it, Shazam names it otherwise, a moment on from where it is: the same recording.
        hearing.song("Demo (Live at Home)", 42.0)
        hearing.song("Demo (Live at Home)", 43.0)
        waitUntil(TIMEOUT_MS) { hearing.listens >= 4 }
        composeRule.waitForIdle()
        assertTrue(lit("Demo three"))
        assertTrue(fake.requestedUrls.drop(asked).none { "Live" in java.net.URLDecoder.decode(it, "UTF-8") })
        assertEquals("Demo", HistoryStore(prefs()).load().first().metadata.title)
        // The next song, from its start, takes over.
        hearing.song("Other", 1.0)
        waitUntil(TIMEOUT_MS) { lit("Other one") }
    }

    @Test
    fun aSongHeardAlongWithNoCoverGetsOneByItsNameInRecentAndItsWords() {
        val hearing = FakeHearing()
        demoWords(hearing)
        val lyrics = fake.handler
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com" && "Kite" in request.url.queryParameter("q").orEmpty()) {
                FakeSpotify.html(request, """{"data":[{"title":"Kite","artist":{"name":"Band"},"album":{"cover_big":"https://cdn/kite.jpg"}}]}""")
            } else {
                lyrics(request)
            }
        }
        hearing.song("Kite", 0.0)
        waitUntil(TIMEOUT_MS) { lit("Kite one") }
        waitUntil(TIMEOUT_MS) { HistoryStore(prefs()).load().firstOrNull()?.metadata?.artworkUrl == "https://cdn/kite.jpg" }
        assertEquals("Kite", HistoryStore(prefs()).load().first().metadata.title)
        // Its words show it too.
        waitUntil(TIMEOUT_MS) { controllerModel().uiState.lyricsFor?.artworkUrl == "https://cdn/kite.jpg" }
        // One the app named with a cover keeps it, and none is looked for.
        val asked = fake.requestedUrls.size
        hearing.next.trySend(Heard.Song(MusicMetadata("Third", "Band", artworkUrl = "https://cdn/third.jpg"), null, 0, SystemClock.elapsedRealtime()))
        waitUntil(TIMEOUT_MS) { HistoryStore(prefs()).load().first().metadata.title == "Third" }
        composeRule.waitForIdle()
        assertEquals("https://cdn/third.jpg", HistoryStore(prefs()).load().first().metadata.artworkUrl)
        assertTrue(fake.requestedUrls.drop(asked).none { "deezer" in it && "Third" in it })
        // One with only the cover its app shows, kept for a while only, gets one that lasts once it's found.
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com" && "Fourth" in request.url.queryParameter("q").orEmpty()) {
                FakeSpotify.html(request, """{"data":[{"title":"Fourth","artist":{"name":"Band"},"album":{"cover_big":"https://cdn/fourth.jpg"}}]}""")
            } else {
                lyrics(request)
            }
        }
        val shown = File(app.cacheDir, "$ARTWORK_DIR/playing-fourth.jpg").toURI().toString()
        hearing.next.trySend(Heard.Song(MusicMetadata("Fourth", "Band", artworkUrl = shown), null, 0, SystemClock.elapsedRealtime()))
        waitUntil(TIMEOUT_MS) { HistoryStore(prefs()).load().first().metadata.artworkUrl == "https://cdn/fourth.jpg" }
    }

    @Test
    fun aSongHeardByAnotherNameDoesntReplaceTheWordsOfTheOneAnAppPlays() {
        val phone = FakePlayback().apply {
            access = true
            playing.value = Following(PlaybackClock(2_000, SystemClock.elapsedRealtime()), "Music", canSeek = true)
        }
        MainActivity.playbackFactory = { phone }
        val hearing = FakeHearing()
        listeningAlong(hearing)
        allowMicrophone()
        launch()
        hearing.song("Demo", 2.0)
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        // Followed in the app, which shows instead of the microphone, still listening along.
        waitUntil(TIMEOUT_MS) { lit("Demo one") && described(string(R.string.lyrics_following_app, "Music")) }
        // Shazam names it otherwise while the app plays it: the words stay.
        hearing.song("Demo (Remix)", 5.0)
        waitUntil(TIMEOUT_MS) { hearing.listens >= 3 }
        assertTrue(shown("Demo one"))
        assertFalse(shown("Demo (Remix) one"))
        // The app no longer plays it: the song heard takes over.
        phone.playing.value = null
        hearing.song("Other", 1.0)
        waitUntil(TIMEOUT_MS) { lit("Other one") }
    }

    @Test
    fun theWordsStopWhereTheAppStopped() {
        val phone = FakePlayback().apply {
            access = true
            playing.value = Following(PlaybackClock(39_990, SystemClock.elapsedRealtime(), playing = false), "Music", canSeek = true)
        }
        MainActivity.playbackFactory = { phone }
        val hearing = FakeHearing()
        listeningAlong(hearing)
        allowMicrophone()
        launch()
        hearing.song("Demo", 2.0)
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        waitUntil(TIMEOUT_MS) { lit("Demo two") && described(string(R.string.lyrics_following_app, "Music")) }
        phone.playing.value = Following(PlaybackClock(39_000, SystemClock.elapsedRealtime()), "Music", canSeek = true)
        // Closed, or on to a song of its own: the words stay where it was, not back to where the song was heard.
        phone.playing.value = null
        waitUntil(TIMEOUT_MS) { !described(string(R.string.lyrics_following_app, "Music")) }
        Thread.sleep(1_500)
        composeRule.waitForIdle()
        assertTrue(lit("Demo two"))
        assertFalse(lit("Demo one") || lit("Demo three"))
        // Heard again, they go on from there.
        hearing.song("Demo", 45.0)
        waitUntil(TIMEOUT_MS) { lit("Demo three") }
    }

    @Test
    fun wordsClosedAndOpenedAgainDontListenAlongToWhatPlaysByThen() {
        val hearing = FakeHearing()
        demoWords(hearing)
        waitUntil(TIMEOUT_MS) { described(string(R.string.lyrics_stop_listening)) }
        click(string(R.string.dismiss_button))
        waitForText(string(R.string.lyrics_button))
        // The music moves on meanwhile; the words opened again are still the song shown, and don't listen.
        hearing.song("Other", 1.0)
        val listens = hearing.listens
        click(string(R.string.lyrics_button))
        waitForText("Demo one")
        Thread.sleep(500)
        composeRule.waitForIdle()
        assertEquals(listens, hearing.listens)
        assertFalse(shown("Other one"))
        assertFalse(described(string(R.string.lyrics_stop_listening)))
    }

    @Test
    fun wordsOutOfTimeArePutInTimeByTappingTheLineHeard() {
        val synced = """[{"trackName":"Demo","artistName":"Band","syncedLyrics":"[00:00.00] Demo one\n[00:10.00] Demo two\n[00:40.00] Demo three"}]"""
        fake.handler = { request -> FakeSpotify.html(request, if (request.url.host == "lrclib.net") synced else "{}") }
        // The music app is paused 5 seconds in, where it's singing the second line, not the first as the words have it.
        val demo = MusicMetadata("Demo", "Band")
        // A line kept to practise, timed as the words were found.
        prefs().edit().putString("saved_lines", """[{"text":"Demo two","title":"Demo","artist":"Band","start":10000,"end":40000}]""").commit()
        val phone = FakePlayback().apply {
            access = true
            onThePhone = Heard.Song(demo, null, 5_000, SystemClock.elapsedRealtime())
            playing.value = Following(PlaybackClock(5_000, SystemClock.elapsedRealtime(), playing = false), "Music", canSeek = true)
        }
        MainActivity.playbackFactory = { phone }
        allowMicrophone()
        launch()
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        waitUntil(TIMEOUT_MS) { lit("Demo one") }

        click(string(R.string.lyrics_sync))
        waitForText(string(R.string.lyrics_sync_hint))
        // Nothing to reset before a line is put in time.
        assertFalse(shown(string(R.string.lyrics_sync_reset)))
        composeRule.onNodeWithText("Demo two").performClick()
        waitUntil(TIMEOUT_MS) { lit("Demo two") }
        // Tapped while putting the words in time, the app isn't moved.
        assertEquals(emptyList<Long>(), phone.seeks)
        // Kept for the song, so it's in time next time too.
        assertEquals(listOf(SyncPoint(10_000, 5_000)), LyricsSyncStore(prefs()).load(demo, SyncedLyrics.parse("[00:00.00] Demo one\n[00:10.00] Demo two\n[00:40.00] Demo three")))
        // The line kept to practise plays from where it's heard now.
        fun kept() = SavedLinesStore(prefs()).load().single().let { it.startMs to it.endMs }
        assertEquals(5_000L to 35_000L, kept())

        // Reset puts them back as found.
        click(string(R.string.lyrics_sync_reset))
        waitUntil(TIMEOUT_MS) { lit("Demo one") }
        assertFalse(shown(string(R.string.lyrics_sync_reset)))
        assertEquals(10_000L to 40_000L, kept())

        // Done, a line tapped moves the app again: to where it is now that the words are in time.
        composeRule.onNodeWithText("Demo two").performClick()
        waitUntil(TIMEOUT_MS) { lit("Demo two") }
        click(string(R.string.lyrics_sync_done))
        waitUntil(TIMEOUT_MS) { !shown(string(R.string.lyrics_sync_hint)) }
        composeRule.onNodeWithText("Demo three").performClick()
        waitUntil(TIMEOUT_MS) { phone.seeks.isNotEmpty() }
        assertEquals(listOf(35_000L), phone.seeks)
    }

    private val demoLines = "[00:00.00] Demo one\n[00:10.00] Demo two\n[00:40.00] Demo three"

    /** Demo's words timed for a take [takeSeconds] long, found while the music app plays it [playingMs] long, 5 seconds in. */
    private fun demoWordsTimedFor(takeSeconds: Int, playingMs: Long?): FakePlayback {
        val synced = """[{"trackName":"Demo","artistName":"Band","duration":$takeSeconds,"syncedLyrics":"$demoLines"}]"""
        fake.handler = { request -> FakeSpotify.html(request, if (request.url.host == "lrclib.net") synced else "{}") }
        val phone = FakePlayback().apply {
            access = true
            onThePhone = Heard.Song(MusicMetadata("Demo", "Band"), null, 5_000, SystemClock.elapsedRealtime())
            playing.value = Following(PlaybackClock(5_000, SystemClock.elapsedRealtime(), playing = false), "Music", canSeek = true, durationMs = playingMs)
        }
        MainActivity.playbackFactory = { phone }
        allowMicrophone()
        launch()
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        waitUntil(TIMEOUT_MS) { lit("Demo one") }
        return phone
    }

    @Test
    fun wordsAreNudgedHalfASecondAtATimeWhilePuttingThemInTime() {
        demoWordsTimedFor(takeSeconds = 308, playingMs = 308_000)
        val demo = MusicMetadata("Demo", "Band")
        val found = SyncedLyrics.parse(demoLines)
        fun kept() = LyricsSyncStore(prefs()).load(demo, found)
        click(string(R.string.lyrics_sync))
        waitForText(string(R.string.lyrics_sync_hint))
        // With nothing tapped yet, the first nudge puts the first line in time, half a second on.
        click(string(R.string.lyrics_sync_later))
        assertEquals(listOf(SyncPoint(0, 500)), kept())
        click(string(R.string.lyrics_sync_later))
        click(string(R.string.lyrics_sync_earlier))
        assertEquals(listOf(SyncPoint(0, 500)), kept())
        // Once a line is tapped, a nudge moves from there.
        composeRule.onNodeWithText("Demo two").performClick()
        waitUntil(TIMEOUT_MS) { lit("Demo two") }
        click(string(R.string.lyrics_sync_earlier))
        assertEquals(listOf(SyncPoint(0, 0), SyncPoint(10_000, 4_500)), kept())
    }

    @Test
    fun wordsTimedForALongerTakeThanThePlayingOneOfferToPutThemInTime() {
        demoWordsTimedFor(takeSeconds = 295, playingMs = 308_000)
        // Off by 13 seconds, so the timing is offered without being asked for, once.
        waitForText(string(R.string.lyrics_sync_hint))
        click(string(R.string.lyrics_sync_done))
        waitUntil(TIMEOUT_MS) { !shown(string(R.string.lyrics_sync_hint)) }
        composeRule.waitForIdle()
        assertFalse(shown(string(R.string.lyrics_sync_hint)))
    }

    @Test
    fun wordsTimedForTheTakePlayingOrOneNotKnownAreNotOffered() {
        demoWordsTimedFor(takeSeconds = 308, playingMs = 309_000)
        Thread.sleep(500)
        composeRule.waitForIdle()
        assertFalse(shown(string(R.string.lyrics_sync_hint)))
    }

    @Test
    fun wordsWithNoLengthToCompareAreNotOffered() {
        demoWordsTimedFor(takeSeconds = 295, playingMs = null)
        Thread.sleep(500)
        composeRule.waitForIdle()
        assertFalse(shown(string(R.string.lyrics_sync_hint)))
    }

    private fun videoWithWords(hearing: FakeHearing, permission: Boolean = true): FakePlayback {
        listeningAlong(hearing)
        fake.handler = { request -> FakeSpotify.html(request, if (request.url.host == "lrclib.net") """[{"trackName":"Demo","artistName":"Band","duration":52,"syncedLyrics":"[00:00.00] Demo one\n[00:10.00] Demo two\n[00:40.00] Demo three"}]""" else "{}") }
        val song = MusicMetadata("Demo", "Band", recordingId = "video:demo")
        val phone = FakePlayback().apply {
            access = true
            onThePhone = Heard.Song(song.copy(title = "デモ - Demo"), null)
            playing.value = Following(PlaybackClock(12_000, SystemClock.elapsedRealtime()), "YouTube", canSeek = true, video = true, durationMs = 62_000)
        }
        MainActivity.playbackFactory = { phone }
        inRecent("Demo")
        if (permission) allowMicrophone()
        launch()
        openWordsFromRecent("Demo")
        return phone
    }

    @Test
    fun videoIntrosAreConfirmedAutomaticallyAndRememberedForTheRecording() {
        val hearing = FakeHearing()
        val phone = videoWithWords(hearing)
        waitUntil(TIMEOUT_MS) { hearing.listens == 1 }
        val song = controllerModel().uiState.lyricsFor!!
        assertEquals("video:demo", song.recordingId)
        for (index in 0..2) {
            composeRule.runOnIdle {
                val position = 2_000L + index * 10_000
                phone.playing.value = phone.playing.value!!.copy(clock = PlaybackClock(position + 10_000, SystemClock.elapsedRealtime()))
                hearing.song("Demo", position / 1000.0)
            }
            if (index < 2) waitUntil(TIMEOUT_MS) { hearing.listens == index + 2 }
        }
        waitUntil(TIMEOUT_MS) { VideoTimingStore(prefs()).load(song) != null && !controllerModel().uiState.listeningAlong }
        assertTrue(VideoTimingStore(prefs()).load(song)!! in 9_000..11_000)
        assertFalse(controllerModel().uiState.lyricsOffTake)
        assertTrue(controllerModel().uiState.following.toString(), controllerModel().uiState.following?.timingKnown == true)
        assertTrue(controllerModel().uiState.following.toString(), controllerModel().uiState.following?.canSeek == true)
        composeRule.onNodeWithText("Demo two").performClick()
        assertTrue(phone.seeks.last() in 19_000..21_000)
        composeRule.runOnIdle { controllerModel().dismissLyrics() }
        click(string(R.string.lyrics_button))
        composeRule.waitForIdle()
        assertEquals(3, hearing.listens)
        assertFalse(controllerModel().uiState.listeningAlong)
    }

    @Test
    fun automaticVideoCalibrationNeedsPermissionAndStopsAfterFiveFailedAttempts() {
        val hearing = FakeHearing()
        videoWithWords(hearing, permission = false)
        composeRule.waitForIdle()
        assertEquals(0, hearing.listens)
        assertFalse(controllerModel().uiState.following!!.timingKnown)
        assertFalse(lit("Demo one"))
        composeRule.runOnIdle { controllerModel().seekLyrics(10_000) }
        composeRule.runOnIdle { controllerModel().dismissLyrics() }
        allowMicrophone()
        click(string(R.string.lyrics_button))
        waitUntil(TIMEOUT_MS) { hearing.listens == 1 }
        repeat(5) { index ->
            hearing.next.trySend(Heard.Failed(AppError.RECOGNITION_UNAVAILABLE))
            if (index < 4) waitUntil(TIMEOUT_MS) { hearing.listens == index + 2 }
        }
        waitUntil(TIMEOUT_MS) { !controllerModel().uiState.listeningAlong }
        assertEquals(5, hearing.listens)
        assertNull(VideoTimingStore(prefs()).load(controllerModel().uiState.lyricsFor!!))
    }

    @Test
    fun aVideoFollowedIsLinedUpWithItsSongByWhereItWasHeard() {
        // YouTube plays the song's video, which opens with 45 seconds before the song.
        val phone = FakePlayback().apply {
            access = true
            playing.value = Following(PlaybackClock(47_000, SystemClock.elapsedRealtime()), "YouTube", canSeek = true, appPackage = "com.google.android.youtube", video = true)
        }
        MainActivity.playbackFactory = { phone }
        val hearing = FakeHearing()
        listeningAlong(hearing)
        allowMicrophone()
        launch()
        hearing.song("Demo", 2.0)
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        // Heard 2 seconds into the song, so its first line, not where the video is.
        waitUntil(TIMEOUT_MS) { lit("Demo one") }
        assertFalse(lit("Demo three"))
        // A line tapped moves the video to it, 45 seconds on.
        composeRule.onNodeWithText("Demo two").performClick()
        waitUntil(TIMEOUT_MS) { phone.seeks.isNotEmpty() }
        assertTrue(phone.seeks.toString(), phone.seeks.last() in 54_000..56_000)
    }

    @Test
    fun wordsListeningAlongKeepSteadyFollowSkipsAndTheNextSong() {
        val hearing = FakeHearing()
        demoWords(hearing)

        // Heard far from where the words are, once, it might be the chorus heard as its other time:
        // nothing moves until a second hearing agrees, as a real skip would.
        hearing.song("Demo", 12.5)
        waitUntil(TIMEOUT_MS) { hearing.listens == 3 }
        assertTrue(lit("Demo one"))
        // Close to where they are, a hearing changes nothing either.
        hearing.song("Demo", 2.5)
        waitUntil(TIMEOUT_MS) { hearing.listens == 4 }
        assertTrue(lit("Demo one"))
        hearing.song("Demo", 12.5)
        hearing.song("Demo", 12.6)
        waitUntil(TIMEOUT_MS) { lit("Demo two") }

        // Nothing heard, the song's taken to have stopped; heard again further on, it follows on from there.
        hearing.next.trySend(Heard.Nothing)
        hearing.song("Demo", 41.0)
        waitUntil(TIMEOUT_MS) { lit("Demo three") }

        // Shazam busy, it's asked again, and the next song takes over the words.
        hearing.next.trySend(Heard.Failed(AppError.RECOGNITION_UNAVAILABLE))
        hearing.next.trySend(Heard.Song(MusicMetadata("Other", "Band"), "1109658204", 0, SystemClock.elapsedRealtime()))
        waitUntil(TIMEOUT_MS) { lit("Other one") }
        assertTrue(shown("Other"))
        // Each song heard along the way joins Recent: by its Apple Music link when Shazam knows one.
        val recent = HistoryStore(prefs()).load()
        assertEquals(listOf("Other", "Demo"), recent.map { it.metadata.title })
        assertEquals(MusicService.APPLE_MUSIC, recent.first().link.service)
        assertEquals(null, recent[1].link.service)
        waitUntil(TIMEOUT_MS) { hearing.listens == 10 }

        // Stopped, it listens no more, and the words stay.
        click(string(R.string.lyrics_stop_listening))
        hearing.song("Demo", 0.0)
        composeRule.waitForIdle()
        assertEquals(10, hearing.listens)
        assertTrue(shown("Other one"))
        assertTrue(described(string(R.string.lyrics_listen_along)))
    }

    @Test
    fun withoutTheMicrophoneListeningAlongStopsAndSaysSo() {
        val hearing = FakeHearing()
        demoWords(hearing)
        hearing.next.trySend(Heard.Failed(AppError.MICROPHONE))
        waitUntil(TIMEOUT_MS) { described(string(R.string.lyrics_listen_along)) }
        // The words stay; closed, the song says why it stopped.
        click(string(R.string.dismiss_button))
        waitForText(string(R.string.error_microphone))
    }

    @Test
    fun listeningAlongPausesOutOfSightAndTurnsOffAndOn() {
        val hearing = FakeHearing()
        demoWords(hearing)
        // The next song, one with no Apple Music link, joins Recent by its name.
        hearing.song("Third", 0.0)
        waitUntil(TIMEOUT_MS) { lit("Third one") }
        val third = HistoryStore(prefs()).load().first()
        assertEquals("Third", third.metadata.title)
        assertEquals(null, third.link.service)

        // Out of sight, it stops listening; back, it listens again.
        waitUntil(TIMEOUT_MS) { hearing.listens == 3 }
        controller!!.pause().stop()
        controller!!.start().resume()
        waitUntil(TIMEOUT_MS) { hearing.listens == 4 }

        // Turned off and on from the words themselves.
        click(string(R.string.lyrics_stop_listening))
        click(string(R.string.lyrics_listen_along))
        waitUntil(TIMEOUT_MS) { hearing.listens == 5 }
    }

    @Test
    fun listeningForWordsBeforeSetupShowsListeningThenTheWordsThenSetup() {
        prefs().edit().putBoolean("setup_complete", false).commit()
        val hearing = FakeHearing()
        listeningAlong(hearing)
        allowMicrophone()
        launch(MainActivity.lyricsIntent(app))
        // Listening is in sight, to be stopped, not hidden behind setup.
        waitForText(string(R.string.listening_text))
        hearing.song("Demo", 2.0)
        waitUntil(TIMEOUT_MS) { shown("Demo one") }
        // Done with the words, setup's next.
        controllerModel().dismissLyrics()
        composeRule.waitForIdle()
        assertFalse(shown("Demo one"))
        waitForText(string(R.string.setup_get_started))
    }

    @Test
    fun lyricsFromAnywhereNameTheSongAndShowItsWordsListeningAlong() {
        val hearing = FakeHearing()
        listeningAlong(hearing)
        allowMicrophone()
        hearing.song("Demo", 2.0)
        launch(MainActivity.lyricsIntent(app))
        // No result to tap Lyrics on first: the words open as soon as the song is named, and keep listening.
        waitUntil(TIMEOUT_MS) { lit("Demo one") && described(string(R.string.lyrics_stop_listening)) }
    }

    @Test
    fun theLyricsButtonOnTheMainScreenNamesTheSongAndShowsItsWords() {
        val hearing = FakeHearing()
        listeningAlong(hearing)
        allowMicrophone()
        launch()
        // Named without a cover, the song gets Deezer's, which the words show too.
        val words = fake.handler
        val cover = "https://cdn.example/demo.jpg"
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                FakeSpotify.html(request, """{"data":[{"title":"Demo","artist":{"name":"Band"},"album":{"cover_big":"$cover"}}]}""")
            } else {
                words(request)
            }
        }
        hearing.song("Demo", 2.0)
        click(string(R.string.tile_lyrics_label))
        // No result to tap Lyrics on first: the words open as soon as the song is named.
        waitUntil(TIMEOUT_MS) { lit("Demo one") && described(string(R.string.lyrics_stop_listening)) }
        waitUntil(TIMEOUT_MS) { fake.requestedUrls.contains(cover) }
    }

    @Test
    fun leavingCrosstuneNeverFloatsTheWordsByThemselves() {
        ShadowSettings.setCanDrawOverlays(true)
        demoWords(FakeHearing())
        // Even with the song playing and listened along to: only Float does.
        controller!!.userLeaving()
        controller!!.pause().stop()
        assertEquals(null, shadowOf(app).nextStartedService)
    }

    @Test
    fun visualsMoveBehindTheWordsUntilTurnedOff() {
        demoWords(FakeHearing())
        assertTrue(visualsShown().isEmpty())
        click(string(R.string.visuals_show))
        waitUntil(TIMEOUT_MS) { visualsShown().isNotEmpty() }
        // On, the button opens what else they can do.
        assertTrue(described(string(R.string.visuals_options)))
        // The words are still there, over them.
        assertTrue(lit("Demo one"))
        // Kept for next time.
        assertTrue(prefs().getBoolean("visuals", false))

        click(string(R.string.visuals_options))
        click(string(R.string.visuals_hide))
        waitUntil(TIMEOUT_MS) { visualsShown().isEmpty() }
        assertTrue(described(string(R.string.visuals_show)))
        assertFalse(prefs().getBoolean("visuals", true))
    }

    @Test
    fun visualsMoveBehindTheWordsFromTheStart() {
        prefs().edit().remove("visuals").commit()
        demoWords(FakeHearing())
        waitUntil(TIMEOUT_MS) { visualsShown().isNotEmpty() }
        assertTrue(described(string(R.string.visuals_options)))
        assertTrue(lit("Demo one"))
    }

    @Test
    fun onlyTheVisualsCanShowUntilATapBringsTheWordsBack() {
        demoWords(FakeHearing())
        click(string(R.string.visuals_show))
        waitUntil(TIMEOUT_MS) { visualsShown().isNotEmpty() }
        click(string(R.string.visuals_options))
        click(string(R.string.visuals_only))
        // The words and the buttons make way, the visuals stay.
        waitUntil(TIMEOUT_MS) { !shown("Demo one") }
        assertFalse(described(string(R.string.visuals_options)))
        assertTrue(visualsShown().isNotEmpty())
        // A tap anywhere brings the words back.
        val showWords = string(R.string.visuals_show_words)
        composeRule.onNode(SemanticsMatcher("shows the words") {
            androidx.compose.ui.semantics.SemanticsActions.OnClick in it.config && it.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label == showWords
        }).performClick()
        waitUntil(TIMEOUT_MS) { shown("Demo one") }
        assertTrue(visualsShown().isNotEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theWordsShowThroughTheirFadedEdges() {
        demoWords(FakeHearing())
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        // A row through the first line, which sits clear of the edges, has its letters on it.
        val line = composeRule.onNodeWithText("Demo one").getBoundsInRoot()
        val density = composeRule.density.density
        val y = ((line.top.value + line.bottom.value) / 2 * density).toInt()
        val row = ((line.left.value * density).toInt() until (line.right.value * density).toInt()).map { image.getPixel(it, y) }
        val background = image.getPixel(1, y)
        assertTrue(row.count { kotlin.math.abs(android.graphics.Color.luminance(it) - android.graphics.Color.luminance(background)) > 0.3f } > 10)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theVisualsCanBeDarkenedOrSkippedAndTheBarMakesWayWhileScrolling() {
        demoWords(FakeHearing())
        click(string(R.string.visuals_show))
        waitUntil(TIMEOUT_MS) { visualsShown().isNotEmpty() }
        // How dark they're shaded, kept once let go.
        click(string(R.string.visuals_options))
        composeRule.onNode(hasContentDescription(string(R.string.visuals_shade))).performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
        composeRule.waitForIdle()
        assertEquals(0.7f, prefs().getFloat("visuals_shade", 0f), 0.01f)
        // On to the next, with the menu closing.
        click(string(R.string.visuals_next))
        assertFalse(shown(string(R.string.visuals_next)))
        assertTrue(visualsShown().isNotEmpty())
        // Never this one again, with the menu closing.
        click(string(R.string.visuals_options))
        click(string(R.string.visuals_drop))
        assertFalse(shown(string(R.string.visuals_drop)))
        assertEquals(1, controllerModel().uiState.visualsDrops)

        // Scrolling the words, the bar makes way, and comes back once they rest.
        composeRule.onNode(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(hasText("Demo one")))
            .performTouchInput { swipeUp() }
        waitUntil(TIMEOUT_MS) { !described(string(R.string.visuals_options)) }
        waitUntil(TIMEOUT_MS) { described(string(R.string.visuals_options)) }
    }

    @Test
    fun theVisualsMenuSetsHowLongEachShows() {
        demoWords(FakeHearing())
        click(string(R.string.visuals_show))
        waitUntil(TIMEOUT_MS) { visualsShown().isNotEmpty() }
        click(string(R.string.visuals_options))
        // Beside the visuals' other options, with no hidden long press.
        assertTrue(shown(string(R.string.visuals_next)))
        assertFalse(shown(string(R.string.setting_skip_heavy_visuals)))
        // Swapping every few seconds.
        composeRule.onNode(hasContentDescription(string(R.string.setting_visuals_change)))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        composeRule.waitForIdle()
        assertEquals(5_000L, prefs().getLong("visuals_change_ms", 0))
        assertEquals(5_000L, controllerModel().uiState.visualsSettings.changeMs)
        assertTrue(shown("5 sec"))
        assertTrue(visualsShown().isNotEmpty())
    }

    @Test
    fun eachVisualHasAStillAndPlaysOneAtATimeOnceTapped() {
        val names = milkdropPresets(app.assets)
        names.forEach { assertNotNull(it, milkdropThumb(app.assets, it)) }
        // Each of a kind, once; listed and swiped through kind by kind.
        val inOrder = milkdropKinds(app.assets).flatMap { it.second }
        assertEquals(names, inOrder.sorted())
        val heavy = names[40]
        prefs().edit().putStringSet("visuals_heavy", setOf(heavy)).commit()
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.visuals_list_summary, names.size - 1, names.size)).performScrollTo().performClick()
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithTag(ARTWORK_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // Tapped, the heavy one plays though it's off, on its own to swipe from, and is switched on from there.
        composeRule.onNodeWithText(heavy).performScrollTo().performClick()
        waitUntil(TIMEOUT_MS) { visualsShown().isNotEmpty() }
        assertTrue(shown(string(R.string.visuals_position, inOrder.indexOf(heavy) + 1, names.size)))
        assertTrue(shown(string(R.string.visual_heavy)))
        click(string(R.string.visual_show))
        assertEquals(setOf(heavy), prefs().getStringSet("visuals_keep", null))
        // Back in the list, where it was.
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(visualsShown().isEmpty())
        composeRule.onNodeWithText(heavy).assertIsDisplayed()
    }

    @Test
    fun floatingLyricsCanFloatOverTheVisuals() {
        prefs().edit().putBoolean("floating_visuals", true).commit()
        val floating = floatOverApps(FakeHearing())
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).assertExists()
        assertTrue(floating.get().let { service -> windowViews(service).any { root -> root.allViews().any { it is MilkdropView } } })
        floating.destroy()
    }

    @Test
    fun visualsAskForTheMicrophoneFirstWhichAndroidNeedsToShareWhatPlays() {
        demoWords(FakeHearing())
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val activity = controller!!.get()
        click(string(R.string.visuals_show))
        val asked = shadowOf(activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), asked.requestedPermissions.toList())
        assertTrue(visualsShown().isEmpty())
        allowMicrophone()
        activity.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        composeRule.waitForIdle()
        waitUntil(TIMEOUT_MS) { visualsShown().isNotEmpty() }
    }

    /** The visuals on screen, which draw nothing here, with no GPU. */
    private fun visualsShown(): List<MilkdropView> {
        fun find(view: View): List<MilkdropView> = when (view) {
            is MilkdropView -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { find(view.getChildAt(it)) }
            else -> emptyList()
        }
        return find(controller!!.get().window.decorView)
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

    @Test
    fun wordsFoundUntimedTakeTheTimesOfATimedCopyOnceItsFound() {
        MainActivity.listenAlongPauseMs = 0
        MainActivity.hearingFactory = { FakeHearing().apply { song("Demo", 2.0) } }
        var asked = 0
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") {
                // Answering late, as on a slow machine.
                Thread.sleep(500)
                // Found untimed at first, shown as they are; a timed copy comes from asking again.
                val timed = if (++asked == 1) "" else ""","syncedLyrics":"[00:01.00] Night sky\n[00:05.00] Stars""""
                FakeSpotify.html(request, """[{"trackName":"Demo","artistName":"Band","plainLyrics":"Night sky\nStars"$timed}]""")
            } else {
                FakeSpotify.html(request, """{"results":[]}""")
            }
        }
        allowMicrophone()
        launch()
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        // Looked for in more places first, as they're untimed, which can take a while on a slow machine.
        waitUntil(TIMEOUT_MS * 3) { controllerModel().uiState.lyricLines.isNotEmpty() }
        assertEquals(listOf(LyricLine(1_000, "Night sky"), LyricLine(5_000, "Stars")), controllerModel().uiState.lyricLines)
        assertEquals(2, asked)
    }

    @Test
    fun wordsThatArentTimedHaveNothingToFloat() {
        MainActivity.hearingFactory = { FakeHearing().apply { song("Plain", 2.0) } }
        fake.handler = { request -> FakeSpotify.html(request, """[{"trackName":"Plain","artistName":"Band","plainLyrics":"Just words"}]""") }
        allowMicrophone()
        launch()
        recognize()
        waitForText("Plain")
        click(string(R.string.lyrics_button))
        waitForText("Just words")
        assertFalse(described(string(R.string.floating_float)))
    }

    @Test
    fun wordsThatArentTimedStillFollowTheAppPlayingThem() {
        val phone = FakePlayback().apply {
            access = true
            playing.value = Following(PlaybackClock(2_000, SystemClock.elapsedRealtime()), "Music", canSeek = true)
        }
        MainActivity.playbackFactory = { phone }
        MainActivity.hearingFactory = { FakeHearing().apply { song("Plain", 2.0) } }
        fake.handler = { request -> FakeSpotify.html(request, """[{"trackName":"Plain","artistName":"Band","plainLyrics":"Just words"}]""") }
        allowMicrophone()
        launch()
        recognize()
        waitForText("Plain")
        click(string(R.string.lyrics_button))
        waitForText("Just words")
        // The app playing them shows, to pause it from the words; with no times, there's nothing to put in time.
        waitUntil(TIMEOUT_MS) { described(string(R.string.lyrics_following_app, "Music")) }
        assertFalse(described(string(R.string.lyrics_sync)))
        click(string(R.string.lyrics_following_app, "Music"))
        waitUntil(TIMEOUT_MS) { phone.plays.lastOrNull() == false }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun floatingOnToASongWithoutTimedWordsShowsTheSongWhole() {
        val title = "A Song With A Name Long Enough To Need Several Lines Of The Floating Window To Be Read Whole"
        val hearing = FakeHearing()
        demoWords(hearing)
        floatNow()
        // The next song heard has only plain words.
        fake.handler = { request -> FakeSpotify.html(request, """[{"trackName":"$title","artistName":"Band","plainLyrics":"Just words"}]""") }
        hearing.song(title, 2.0)
        val floating = hasText(title) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(FLOATING_LYRICS_TAG))
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(floating, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(shown("Band"))
        // Never cut short: every line it takes is shown.
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNode(floating, useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 2)
        assertFalse(layouts.single().hasVisualOverflow)
    }

    /** Floats the words on screen with Float, Android letting them over other apps, and Crosstune goes out of sight. */
    private fun floatNow(): ServiceController<FloatingLyricsService> {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        click(string(R.string.floating_float))
        val started = shadowOf(app).nextStartedService
        assertEquals(FloatingLyricsService::class.java.name, started.component!!.className)
        controller!!.pause().stop()
        return Robolectric.buildService(FloatingLyricsService::class.java, started).create().startCommand(0, 1).also { composeRule.waitForIdle() }
    }

    /** Floats Demo's words on [background], with Android letting them over other apps. */
    private fun floatOverApps(hearing: FakeHearing, background: Float = 0f): ServiceController<FloatingLyricsService> {
        prefs().edit().putFloat("floating_background", background).commit()
        demoWords(hearing)
        return floatNow()
    }

    private fun View.allViews(): List<View> =
        listOf(this) + ((this as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).flatMap { group.getChildAt(it).allViews() } } ?: emptyList())

    private fun windowViews(service: FloatingLyricsService): List<View> =
        Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java)).views

    private fun overlays(service: FloatingLyricsService): List<View> =
        windowViews(service).filter { (it.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }

    private fun overlay(service: FloatingLyricsService): View = overlays(service).single { it !is ImageView }

    /** The button beside locked words, which still takes a touch. */
    private fun unlockButton(service: FloatingLyricsService): ImageView? =
        overlays(service).filterIsInstance<ImageView>().singleOrNull { it.contentDescription == string(R.string.floating_unlock) }

    /** Where the words are dropped to close them, shown while they're dragged. */
    private fun closeTarget(service: FloatingLyricsService): ImageView? =
        overlays(service).filterIsInstance<ImageView>().singleOrNull { it.contentDescription == string(R.string.floating_close) }

    /** Taps the floating words, which puts their buttons out, or away. */
    private fun tapWords() {
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        composeRule.waitForIdle()
    }

    /** Opens the floating words' settings, from their buttons. */
    private fun floatingSettings() {
        tapWords()
        click(string(R.string.floating_customize))
        composeRule.onNodeWithTag(FLOATING_CUSTOMIZE_TAG).assertExists()
    }

    @Test
    fun wordsLeftAtTheBottomMoveUpToFitAndBackWithoutForgettingWhereTheyWere() {
        // Left lower than they fit, e.g. on a smaller screen, or once their buttons are out.
        prefs().edit().putInt("floating_top", 100_000).commit()
        val floating = floatOverApps(FakeHearing())
        val view = overlay(floating.get())
        shadowOf(Looper.getMainLooper()).idle()
        val window = view.layoutParams as WindowManager.LayoutParams
        assertEquals(app.resources.displayMetrics.heightPixels - view.measuredHeight, window.y)
        assertEquals(100_000, prefs().getInt("floating_top", -1))
    }

    @Test
    fun floatingWordsGoAcrossTheScreenShortOfItsSidesAsItTurns() {
        // Left at the far right of a wider screen.
        prefs().edit().putInt("floating_left", 100_000).commit()
        val floating = floatOverApps(FakeHearing())
        val view = overlay(floating.get())
        val window = view.layoutParams as WindowManager.LayoutParams
        fun margin() = (32 * app.resources.displayMetrics.density).toInt()
        assertEquals(app.resources.displayMetrics.widthPixels - 2 * margin(), window.width)
        assertEquals(app.resources.displayMetrics.widthPixels - window.width, window.x)
        // Turned narrower, they fit it.
        org.robolectric.RuntimeEnvironment.setQualifiers("w300dp-h700dp")
        floating.get().onConfigurationChanged(app.resources.configuration)
        assertEquals(app.resources.displayMetrics.widthPixels - 2 * margin(), window.width)
        assertEquals(app.resources.displayMetrics.widthPixels - window.width, window.x)
        // On a big screen, they're no wider than reads at a glance.
        org.robolectric.RuntimeEnvironment.setQualifiers("w1200dp-h800dp")
        floating.get().onConfigurationChanged(app.resources.configuration)
        assertEquals((560 * app.resources.displayMetrics.density).toInt(), window.width)
    }

    @Test
    fun lockedWordsAlwaysHaveAWayOutEvenFloatingLockedFromLastTime() {
        // Locked when they last floated, and with no notification to unlock them from.
        prefs().edit().putBoolean("floating_locked", true).commit()
        val floating = floatOverApps(FakeHearing())
        val service = floating.get()
        assertTrue((overlay(service).layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        val unlock = unlockButton(service)!!
        assertEquals(string(R.string.floating_unlock), unlock.contentDescription)
        // It takes touches, at the words' top right corner.
        val button = unlock.layoutParams as WindowManager.LayoutParams
        assertTrue(button.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        val words = overlay(service).layoutParams as WindowManager.LayoutParams
        assertEquals(words.y, button.y)
        assertEquals(words.x + words.width - button.width, button.x)

        unlock.performClick()
        composeRule.waitForIdle()
        assertTrue((overlay(service).layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        assertFalse(prefs().getBoolean("floating_locked", true))
        assertEquals(null, unlockButton(service))
        // Locked again, from their settings, it's back, with the settings put away; closed, it goes with the words.
        floatingSettings()
        click(string(R.string.floating_lock))
        assertTrue(unlockButton(service) != null)
        composeRule.onNodeWithTag(FLOATING_CUSTOMIZE_TAG).assertDoesNotExist()
        floating.destroy()
        assertTrue(overlays(service).isEmpty())
    }

    private fun ServiceController<FloatingLyricsService>.command(action: String) {
        withIntent(Intent(app, FloatingLyricsService::class.java).setAction(action)).startCommand(0, 2)
        composeRule.waitForIdle()
    }

    @Test
    fun seeThroughWordsFloatInTheirOwnWindowStillListeningAlong() {
        val hearing = FakeHearing()
        val floating = floatOverApps(hearing)
        val service = floating.get()
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).assertExists()
        assertTrue(shown("Demo two"))
        // Out of sight, it still listens along.
        val listens = hearing.listens
        hearing.song("Demo", 12.0)
        waitUntil(TIMEOUT_MS) { hearing.listens > listens }
        val notification = shadowOf(service.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(string(R.string.floating_notification), shadowOf(notification).contentTitle)

        // Tapped, they show what they can do, until tapped again, however long that takes. A click, so
        // TalkBack can too.
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).assertHasClickAction()
        tapWords()
        assertTrue(described(string(R.string.floating_close)))
        assertTrue(described(string(R.string.floating_customize)))
        composeRule.mainClock.advanceTimeBy(60_000)
        composeRule.waitForIdle()
        assertTrue(described(string(R.string.floating_close)))
        tapWords()
        assertFalse(described(string(R.string.floating_close)))
        // A touch anywhere else puts them away too, and is let through to what's there.
        tapWords()
        val outside = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0)
        assertTrue(overlay(service).dispatchTouchEvent(outside))
        outside.recycle()
        composeRule.waitForIdle()
        assertFalse(described(string(R.string.floating_close)))
        // With no app to play and pause, it's the microphone: stop listening along, and start it again.
        tapWords()
        click(string(R.string.lyrics_stop_listening))
        assertTrue(described(string(R.string.lyrics_listen_along)))
        click(string(R.string.lyrics_listen_along))
        assertTrue(described(string(R.string.lyrics_stop_listening)))

        // Locked, touches go through to the app below, and the notification can unlock them.
        click(string(R.string.floating_customize))
        click(string(R.string.floating_lock))
        val window = overlay(service).layoutParams as WindowManager.LayoutParams
        assertTrue(window.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals(0.8f, window.alpha)
        assertTrue(prefs().getBoolean("floating_locked", false))
        val locked = shadowOf(service.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(listOf(string(R.string.floating_unlock), string(R.string.floating_close)), locked.actions.map { it.title.toString() })
        floating.command(FloatingLyricsService.ACTION_UNLOCK)
        assertTrue((overlay(service).layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        assertFalse(prefs().getBoolean("floating_locked", true))

        // Open Crosstune, in their settings, brings it back, and back in sight they stop floating.
        floatingSettings()
        click(string(R.string.floating_open))
        assertEquals(MainActivity::class.java.name, shadowOf(service).nextStartedActivity.component!!.className)
        controller!!.start()
        assertEquals(FloatingLyricsService::class.java.name, shadowOf(app).nextStoppedService.component!!.className)
        assertEquals(null, FloatingLyricsService.host)
    }

    @Test
    fun floatingWordsAreDraggedUpAndDownAndStayWhereTheyWereLeft() {
        // Where they were left last time, in the app's dark look.
        prefs().edit().putInt("floating_top", 300).putString("theme", "DARK").commit()
        val floating = floatOverApps(FakeHearing())
        val view = overlay(floating.get())
        val top = (view.layoutParams as WindowManager.LayoutParams).y
        assertEquals(300, top)
        fun touch(action: Int, y: Float) = MotionEvent.obtain(0, 0, action, 100f, y, 0).also { view.dispatchTouchEvent(it) }.recycle()
        // On the words, a finger's wobble is still a tap; past it, a drag.
        touch(MotionEvent.ACTION_DOWN, 20f)
        touch(MotionEvent.ACTION_MOVE, 21f)
        touch(MotionEvent.ACTION_MOVE, 320f)
        touch(MotionEvent.ACTION_MOVE, 420f)
        touch(MotionEvent.ACTION_UP, 420f)
        assertEquals(top + 400, (view.layoutParams as WindowManager.LayoutParams).y)
        assertEquals(top + 400, prefs().getInt("floating_top", -1))
        // Never off the screen.
        touch(MotionEvent.ACTION_DOWN, 20f)
        touch(MotionEvent.ACTION_MOVE, -100_000f)
        touch(MotionEvent.ACTION_CANCEL, -100_000f)
        assertEquals(0, prefs().getInt("floating_top", -1))
        floating.destroy()
        assertTrue(windowViews(floating.get()).none { (it.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY })
    }

    @Test
    fun floatingWordsAreMovedSidewaysOrPinchedBiggerWhereTheyAre() {
        val floating = floatOverApps(FakeHearing())
        val view = overlay(floating.get())
        val window = view.layoutParams as WindowManager.LayoutParams
        val screen = app.resources.displayMetrics.widthPixels
        val (left, width) = window.x to window.width
        fun touch(action: Int, x: Float, y: Float = 20f) = MotionEvent.obtain(0, 0, action, x, y, 0).also { view.dispatchTouchEvent(it) }.recycle()
        // Dragged sideways, even from a side, they move, as wide as they were.
        touch(MotionEvent.ACTION_DOWN, view.width - 2f)
        touch(MotionEvent.ACTION_MOVE, view.width - 22f)
        touch(MotionEvent.ACTION_UP, view.width - 22f)
        assertEquals(left - 20, window.x)
        assertEquals(width, window.width)
        assertEquals(left - 20, prefs().getInt("floating_left", -1))
        // Kept on screen.
        touch(MotionEvent.ACTION_DOWN, 100f)
        touch(MotionEvent.ACTION_MOVE, 100_000f)
        touch(MotionEvent.ACTION_UP, 100_000f)
        assertEquals(screen - window.width, window.x)

        // Two fingers spread apart make the words bigger, as big as they go.
        fun pinch(action: Int, spread: Float) {
            val ids = arrayOf(MotionEvent.PointerProperties().apply { id = 0 }, MotionEvent.PointerProperties().apply { id = 1 })
            val at = arrayOf(MotionEvent.PointerCoords().apply { x = 100f; y = 20f }, MotionEvent.PointerCoords().apply { x = 100f + spread; y = 20f })
            MotionEvent.obtain(0, 0, action, 2, ids, at, 0, 0, 1f, 1f, 0, 0, 0, 0).also { view.dispatchTouchEvent(it) }.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 100f)
        pinch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f)
        pinch(MotionEvent.ACTION_MOVE, 150f)
        composeRule.waitForIdle()
        assertEquals(1.5f, controllerModel().uiState.floating.scale, 0.01f)
        pinch(MotionEvent.ACTION_MOVE, 10_000f)
        pinch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0f)
        pinch(MotionEvent.ACTION_MOVE, 0f)
        touch(MotionEvent.ACTION_UP, 100f)
        assertEquals(FloatingOptions.MAX_SCALE, prefs().getFloat("floating_scale", 1f))
        floating.destroy()
        assertTrue(windowViews(floating.get()).none { (it.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY })
    }

    private fun controllerModel() = ViewModelProvider(controller!!.get())[MainViewModel::class.java]

    private val sliders = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun floatingWordsKeepTheirHeightThroughShorterLinesTillTheNextSong() {
        val hearing = FakeHearing()
        floatOverApps(hearing)
        // "Long" starts with a line that takes a few rows, then short ones; any other song's are short.
        fake.handler = { request ->
            val title = request.url.queryParameter("track_name")
            val first = if (title == "Long") "A first line long enough to need a few rows of the floating window to show it whole" else "$title one"
            FakeSpotify.html(request, """[{"trackName":"$title","artistName":"Band","syncedLyrics":"[00:00.00] $first\n[00:10.00] $title two\n[00:20.00] $title three"}]""")
        }
        fun height() = composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).fetchSemanticsNode().boundsInWindow.height
        hearing.song("Long", 2.0)
        waitUntil(TIMEOUT_MS) { shown("A first line long enough to need a few rows of the floating window to show it whole") }
        val tall = height()
        // Shorter lines don't pull up what's under them.
        hearing.song("Long", 12.0)
        hearing.song("Long", 12.1)
        waitUntil(TIMEOUT_MS) { shown("Long three") && !shown("A first line long enough to need a few rows of the floating window to show it whole") }
        assertEquals(tall, height(), 1f)
        // The next song starts from its own.
        hearing.song("Short", 2.0)
        waitUntil(TIMEOUT_MS) { shown("Short one") && !shown("Long two") }
        assertTrue(height() < tall - 10f)
    }

    @Test
    fun floatingWordsDroppedOnTheCloseTargetClose() {
        val floating = floatOverApps(FakeHearing())
        val service = floating.get()
        val view = overlay(service)
        fun touch(action: Int, x: Float, y: Float) = MotionEvent.obtain(0, 0, action, x, y, 0).also { view.dispatchTouchEvent(it) }.recycle()
        // Not there until they're dragged.
        assertEquals(null, closeTarget(service))
        touch(MotionEvent.ACTION_DOWN, 100f, 20f)
        touch(MotionEvent.ACTION_MOVE, 100f, 60f)
        val target = closeTarget(service)!!
        val spot = target.layoutParams as WindowManager.LayoutParams
        // Near the bottom, in the middle, and touches go through it.
        assertEquals((app.resources.displayMetrics.widthPixels - spot.width) / 2, spot.x)
        assertTrue(spot.y > app.resources.displayMetrics.heightPixels / 2)
        assertTrue(spot.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        fun fill() = (target.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor
        val idle = fill()
        // Over it, it turns red, with a tick felt once; off it again, it's back as it was.
        val (centerX, centerY) = spot.x + spot.width / 2f to spot.y + spot.height / 2f
        touch(MotionEvent.ACTION_MOVE, centerX, centerY)
        assertEquals(0xFFD32F2F.toInt(), fill())
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, shadowOf(target).lastHapticFeedbackPerformed())
        // Near it is enough, for a finger that covers it.
        touch(MotionEvent.ACTION_MOVE, centerX + spot.width * 0.8f, centerY)
        assertEquals(0xFFD32F2F.toInt(), fill())
        touch(MotionEvent.ACTION_MOVE, centerX + spot.width * 1.5f, centerY)
        assertEquals(idle, fill())
        // Let go elsewhere, it goes, and they stay floating.
        touch(MotionEvent.ACTION_UP, centerX + spot.width * 1.5f, centerY)
        assertEquals(null, closeTarget(service))
        assertFalse(shadowOf(service).isStoppedBySelf)
        // A drag cut short over it doesn't close them either.
        touch(MotionEvent.ACTION_DOWN, 100f, 20f)
        touch(MotionEvent.ACTION_MOVE, centerX, centerY)
        touch(MotionEvent.ACTION_CANCEL, centerX, centerY)
        assertEquals(null, closeTarget(service))
        assertFalse(shadowOf(service).isStoppedBySelf)
        // Dropped on it, they close; dragged straight onto it again, it still turns red.
        touch(MotionEvent.ACTION_DOWN, 100f, 20f)
        touch(MotionEvent.ACTION_MOVE, centerX, centerY)
        assertEquals(0xFFD32F2F.toInt(), (closeTarget(service)!!.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        touch(MotionEvent.ACTION_UP, centerX, centerY)
        assertEquals(null, closeTarget(service))
        assertTrue(shadowOf(service).isStoppedBySelf)
        // Closed some other way mid-drag, the target goes with them.
        touch(MotionEvent.ACTION_DOWN, 100f, 20f)
        touch(MotionEvent.ACTION_MOVE, 100f, 60f)
        assertTrue(closeTarget(service) != null)
        floating.destroy()
        assertTrue(overlays(service).isEmpty())
    }

    @Test
    fun floatingWordsOfAnAppPlayingPlayAndPauseIt() {
        timedWordsOfAnySong()
        inRecent("Demo")
        val phone = appPlaying("Demo")
        launch()
        openWordsFromRecent("Demo")
        floatNow()
        tapWords()
        // The app plays the song, so its words play and pause it, rather than listen along.
        assertFalse(described(string(R.string.lyrics_listen_along)) || described(string(R.string.lyrics_stop_listening)))
        click(string(R.string.lyrics_pause))
        assertEquals(listOf(false), phone.plays)
        composeRule.runOnIdle { phone.playing.value = Following(PlaybackClock(2_000, SystemClock.elapsedRealtime(), playing = false), "Music", canSeek = true) }
        composeRule.waitForIdle()
        click(string(R.string.lyrics_play))
        assertEquals(listOf(false, true), phone.plays)
    }

    @Test
    fun floatingWordsAreChangedOnThemselves() {
        val hearing = FakeHearing()
        val floating = floatOverApps(hearing)
        val view = overlay(floating.get())
        floatingSettings()
        // In their place, the buttons, which come back with Done.
        assertFalse(described(string(R.string.floating_close)))
        // Size is a share of its largest, and the band runs from none to full.
        val percent = NumberFormat.getPercentInstance()
        assertTrue(shown(percent.format(sizeShare(1f, FloatingOptions.MIN_SCALE..FloatingOptions.MAX_SCALE))))
        assertTrue(shown(percent.format(sizeShare(0f, 0f..FloatingOptions.MAX_BACKGROUND))))

        // Their size, and the band behind them, slid, are kept once let go; each slider named for TalkBack.
        composeRule.onNode(sliders and hasContentDescription(string(R.string.floating_overlay_size))).performSemanticsAction(SemanticsActions.SetProgress) { it(1.4f) }
        composeRule.onNode(sliders and hasContentDescription(string(R.string.floating_background))).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        composeRule.waitForIdle()
        assertEquals(0.5f, controllerModel().uiState.floating.background, 0.01f)
        assertEquals(0.5f, prefs().getFloat("floating_background", 0f), 0.01f)
        assertEquals(1.4f, prefs().getFloat("floating_scale", 1f), 0.01f)
        // A drag on a slider is the slider's, and doesn't move them.
        val top = (view.layoutParams as WindowManager.LayoutParams).y
        val slider = composeRule.onAllNodes(sliders)[0].fetchSemanticsNode().boundsInWindow
        fun touch(action: Int, y: Float) = MotionEvent.obtain(0, 0, action, slider.center.x, y, 0).also { view.dispatchTouchEvent(it) }.recycle()
        touch(MotionEvent.ACTION_DOWN, slider.center.y)
        touch(MotionEvent.ACTION_MOVE, slider.center.y + 200f)
        touch(MotionEvent.ACTION_UP, slider.center.y + 200f)
        assertEquals(top, (view.layoutParams as WindowManager.LayoutParams).y)
        // A vertical gesture over settings belongs to their scroll area, not the floating window.
        val setting = composeRule.onNode(hasText(string(R.string.floating_previous_line))).fetchSemanticsNode().boundsInWindow
        fun overSettings(action: Int, y: Float) = MotionEvent.obtain(0, 0, action, setting.center.x, y, 0).also { view.dispatchTouchEvent(it) }.recycle()
        overSettings(MotionEvent.ACTION_DOWN, setting.center.y)
        overSettings(MotionEvent.ACTION_MOVE, setting.center.y - 100f)
        overSettings(MotionEvent.ACTION_UP, setting.center.y - 100f)
        assertEquals(top, (view.layoutParams as WindowManager.LayoutParams).y)

        // The line before the one sung shows too, once there is one; the next one, no longer.
        click(string(R.string.floating_previous_line))
        click(string(R.string.floating_next_line))
        assertTrue(prefs().getBoolean("floating_previous_line", false))
        assertFalse(prefs().getBoolean("floating_next_line", true))
        hearing.song("Demo", 12.5)
        hearing.song("Demo", 12.6)
        waitUntil(TIMEOUT_MS) { shown("Demo two") && shown("Demo one") && !shown("Demo three") }

        // Done puts it all away.
        click(string(R.string.floating_done))
        composeRule.onNodeWithTag(FLOATING_CUSTOMIZE_TAG).assertDoesNotExist()
        assertFalse(described(string(R.string.floating_close)))
        // Tapped again, all of it goes too, and next time only the buttons come out.
        floatingSettings()
        tapWords()
        composeRule.onNodeWithTag(FLOATING_CUSTOMIZE_TAG).assertDoesNotExist()
        tapWords()
        composeRule.onNodeWithTag(FLOATING_CUSTOMIZE_TAG).assertDoesNotExist()
        assertTrue(described(string(R.string.floating_close)))
    }

    @Test
    fun theFloatButtonFloatsTheWordsAtOnceOnceAndroidAllowsIt() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        demoWords(FakeHearing())
        click(string(R.string.floating_float))
        assertEquals(FloatingLyricsService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        // Crosstune goes behind the app the words float over.
        assertTrue(shadowOf(controller!!.get()).isTaskMovedToBack)
    }

    @Test
    fun theFloatButtonSaysWhyAndroidAsksAndFloatsOnceAllowed() {
        ShadowSettings.setCanDrawOverlays(false)
        demoWords(FakeHearing())
        // Said why first, which can be put off.
        click(string(R.string.floating_float))
        waitForText(string(R.string.floating_permission_title))
        click(string(R.string.cancel_button))
        assertFalse(shown(string(R.string.floating_permission_title)))
        assertEquals(null, shadowOf(app).nextStartedActivity)

        click(string(R.string.floating_float))
        click(string(R.string.floating_permission_continue))
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(app).nextStartedActivity.action)
        // Back, allowed: Android 13 asks about their notification once, and they float either way.
        ShadowSettings.setCanDrawOverlays(true)
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertTrue(prefs().getBoolean("asked_floating_notifications", false))
        shadowOf(controller!!.get()).getLastRequestedPermission()?.let { request ->
            shadowOf(controller!!.get()).grantPermissions(*request.requestedPermissions)
            controller!!.get().onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        }
        composeRule.waitForIdle()
        assertEquals(FloatingLyricsService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    @Test
    fun theFloatButtonLeftUnallowedFloatsNothing() {
        ShadowSettings.setCanDrawOverlays(false)
        demoWords(FakeHearing())
        click(string(R.string.floating_float))
        click(string(R.string.floating_permission_continue))
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertEquals(null, shadowOf(app).nextStartedService)
    }

    @Test
    fun floatingWordsCloseFromThemselvesTheNotificationOrCrosstuneClosing() {
        prefs().edit().putString("theme", "LIGHT").commit()
        val floating = floatOverApps(FakeHearing(), background = 0.6f)
        tapWords()
        click(string(R.string.floating_close))
        assertTrue(shadowOf(floating.get()).isStoppedBySelf)

        floating.command(FloatingLyricsService.ACTION_CLOSE)
        assertTrue(shadowOf(floating.get()).isStoppedBySelf)

        controller!!.destroy()
        controller = null
        assertEquals(null, FloatingLyricsService.host)
    }

    @Test
    fun closingTheFloatingWordsStopsListeningAlongTillCrosstuneIsBack() {
        val hearing = FakeHearing()
        val floating = floatOverApps(hearing)
        // Floating, they listen along, out of sight.
        hearing.song("Demo", 3.0)
        waitUntil(TIMEOUT_MS) { hearing.listens == 3 }
        floating.command(FloatingLyricsService.ACTION_CLOSE)
        composeRule.waitForIdle()
        hearing.song("Demo", 4.0)
        Thread.sleep(200)
        composeRule.waitForIdle()
        assertEquals(3, hearing.listens)
        // Back in sight, they listen along again.
        controller!!.restart()
        waitUntil(TIMEOUT_MS) { hearing.listens > 3 }
    }

    @Test
    fun withoutAnythingToFloatOutOfOrAllowedTheWindowStopsAtOnce() {
        FloatingLyricsService.host = null
        val alone = Robolectric.buildService(FloatingLyricsService::class.java).create().startCommand(0, 1)
        assertTrue(shadowOf(alone.get()).isStoppedBySelf)
        // Nothing binds to it: it's only started.
        assertEquals(null, alone.get().onBind(Intent()))
        // An unlock from an old notification finds nothing to unlock.
        alone.command(FloatingLyricsService.ACTION_UNLOCK)

        // Taken back in Android's settings since Crosstune was left.
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        demoWords(FakeHearing())
        click(string(R.string.floating_float))
        ShadowSettings.setCanDrawOverlays(false)
        val service = Robolectric.buildService(FloatingLyricsService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, 1).get()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(windowViews(service).none { (it.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY })
    }

    @Test
    fun floatingWordsWithoutTheMicrophoneOpenCrosstuneToAskForIt() {
        val hearing = FakeHearing()
        val floating = floatOverApps(hearing)
        tapWords()
        click(string(R.string.lyrics_stop_listening))
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        click(string(R.string.lyrics_listen_along))
        assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component!!.className)
        floating.get()
    }

    /** Google Translate translating each line it's asked into "[line]", or answering [code]. */
    private fun translation(request: okhttp3.Request, code: Int = 200): okhttp3.Response {
        return FakeSpotify.html(request, googleRows(request) { "[$it]" }, code = code)
    }

    /** Google Translate's answer to [request]: each line it asked as [row], and the language it was in. */
    private fun googleRows(request: okhttp3.Request, row: (String) -> String): String =
        org.json.JSONArray(FakeSpotify.bodyOf(request).split("&").map { org.json.JSONArray().put(row(java.net.URLDecoder.decode(it.removePrefix("q="), "UTF-8"))).put("en") }).toString()

    /** MyMemory, where Google Translate can't: answering [code], with each row of what it's asked as "[row]". */
    private fun myMemoryTranslation(request: okhttp3.Request, code: Int = 200): okhttp3.Response {
        val rows = request.url.queryParameter("q").orEmpty().split("\n").joinToString("\\n") { "[$it]" }
        return FakeSpotify.html(request, """{"responseData":{"translatedText":"$rows"},"responseStatus":200}""", code = code)
    }

    /**
     * Hears Demo, whose words LRCLIB has as [synced] lines, or else as [plain] ones; Google Translate,
     * and MyMemory where it can't, answer [translations], and [openAi] for ChatGPT when signed in.
     */
    private fun wordsOf(synced: String?, plain: String, translations: Int = 200, openAi: FakeOpenAi? = null, sites: (okhttp3.Request) -> okhttp3.Response? = { null }) {
        MainActivity.listenAlongPauseMs = 0
        MainActivity.hearingFactory = { FakeHearing().apply { song("Demo", 2.0) } }
        fake.handler = { request ->
            openAi?.answer(request) ?: sites(request) ?: when (request.url.host) {
                GOOGLE_TRANSLATE_HOST -> translation(request, translations)
                "api.mymemory.translated.net" -> myMemoryTranslation(request, translations)
                else -> {
                    val timed = synced?.let { ""","syncedLyrics":"$it"""" }.orEmpty()
                    FakeSpotify.html(request, """[{"trackName":"Demo","artistName":"Band","plainLyrics":"$plain"$timed}]""")
                }
            }
        }
        allowMicrophone()
        launch()
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
    }

    /** Opens the readings palette. Tapping its button again closes it, so a second call stays put. */
    private fun learnMenu() {
        if (composeRule.onAllNodesWithTag("lyrics-display-options").fetchSemanticsNodes().isEmpty()) {
            click(string(R.string.lyrics_learn))
        }
    }

    /** Opens the study card. Tapping its button again closes it, so a second call stays put. */
    private fun studyMenu() {
        if (composeRule.onAllNodesWithTag("lyrics-study-options").fetchSemanticsNodes().isEmpty()) {
            click(string(R.string.lyrics_study))
        }
    }

    /** Opens what the song's about, from the song at the top, at [tab]. */
    private fun aboutSong(tab: Int? = null) {
        click(string(R.string.lyrics_about_song))
        tab?.let { click(string(it)) }
    }

    /** Signed in with ChatGPT before Crosstune opens, which [openAi] answers for; it answers each feature as asked. */
    private fun signedInWithChatGpt(): FakeOpenAi {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val file = File(app.noBackupFilesDir, "chatgpt.json").apply { delete() }
        fake.handler = { request -> openAi.answer(request) ?: FakeSpotify.html(request, "") }
        val result = runBlocking {
            ChatGpt(fake.client(), file, app.packageName).signIn(open = { url ->
                openAi.authorize = url.toHttpUrl()
                kotlin.concurrent.thread { openAi.comeBack() }
            })
        }
        assertEquals(ChatGptSignInResult.SIGNED_IN, result)
        openAi.reply = { body ->
            val instructions = body.getString("instructions")
            val input = body.getJSONArray("input").getJSONObject(0).getString("content")
            200 to when {
                "a word in it" in instructions -> FakeOpenAi.stream("""{"meaning": "the heavens", "type": "noun"}""")
                "restore song lyrics written in romaji" in instructions -> FakeOpenAi.stream(org.json.JSONObject(input).getJSONArray("lines").let { lines -> org.json.JSONArray((0 until lines.length()).map { ROMAJI_JAPANESE[lines.getString(it)] ?: lines.getString(it) }) }.toString())
                "6 to 10 words" in instructions -> FakeOpenAi.stream("""[{"word": "sky", "reading": "", "meaning": "cielo"}, {"word": "Stars", "reading": "", "meaning": "estrellas"}]""")
                "covers and samples" in instructions -> FakeOpenAi.stream("""{"original": {"title": "First Demo", "artist": "Old Band"}, "samples": [{"title": "Beat", "artist": "Drummer"}]}""")
                "get to know the artists" in instructions -> FakeOpenAi.stream("A band from Rome.\n---\nNight pop.\n---\nDemo (2020).")
                "example sentences" in instructions -> FakeOpenAi.stream("""[{"sentence": "The sky is blue.", "meaning": "El cielo es azul."}]""")
                "You recommend songs" in instructions -> FakeOpenAi.stream("""[{"title": "Moon", "artist": "Luna"}, {"title": "Made Up", "artist": "Nobody"}]""")
                "about this song, in at most 80 words" in instructions -> FakeOpenAi.stream("It's ", "about stars.")
                "you can only help with the song" in instructions -> FakeOpenAi.stream("Because ", "it rhymes.")
                "You check the readings" in instructions -> FakeOpenAi.stream("[{\"line\": 1, \"kanji\": \"君\", \"reading\": \"きみ\"}]")
                "explain that line" in instructions -> FakeOpenAi.stream("Looking up ", "at night.\n--", "-\nA noun and its adjective.")
                "explain the song in four parts" in instructions -> FakeOpenAi.stream("From 1985.\n---\nA night ", "under the stars.\n---\nCalm.\n---\n")
                else -> FakeOpenAi.stream(org.json.JSONArray(input).let { lines -> org.json.JSONArray((0 until lines.length()).map { "«${lines.getString(it)}»" }) }.toString())
            }
        }
        return openAi
    }

    @Test
    fun signedInWithChatGptTheSongIsTranslatedWholeLinesExplainedAndWordsLookedUpInThem() {
        prefs().edit().putBoolean("lyrics_translation", true).commit()
        val openAi = signedInWithChatGpt()
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        // The whole song at once, and Google Translate isn't asked, nor MyMemory.
        waitUntil(TIMEOUT_MS) { shown("«Stars»") }
        assertTrue(shown("«Night sky»"))
        assertFalse(fake.requestedUrls.any { GOOGLE_TRANSLATE_HOST in it || "mymemory" in it })

        // A line opens to study; ChatGPT explains it as it writes, and keeps it with the line once saved.
        composeRule.onAllNodesWithTag(LYRIC_LINE_TAG)[0].performClick()
        click(string(R.string.lyrics_explain_line))
        waitUntil(TIMEOUT_MS) { shown("Looking up at night.") }
        // In its parts, each under its label.
        waitUntil(TIMEOUT_MS) { shown("A noun and its adjective.") }
        assertTrue(shown(string(R.string.explanation_in_song)) && shown(string(R.string.explanation_language)))
        click(string(R.string.lyrics_save_line))
        assertEquals("Looking up at night.\n---\nA noun and its adjective.", SavedLinesStore(prefs()).load().single().explanation)

        // A word in it means what it does in that line.
        val line = hasText("Night sky") and androidx.compose.ui.test.hasTestTag(STUDY_LINE_TAG)
        waitUntil(TIMEOUT_MS) { runCatching { composeRule.onNode(line).performFirstLinkClick() }.isSuccess }
        waitUntil(TIMEOUT_MS) { shown("the heavens") }
        val asked = openAi.asked.last().getJSONArray("input").getJSONObject(0).getString("content")
        assertEquals("Night sky", org.json.JSONObject(asked).getString("line"))
    }

    @Test
    fun signedInTranslationsComeFromChatGptOrGoogleTranslateAsPicked() {
        prefs().edit().putBoolean("lyrics_translation", true).commit()
        val openAi = signedInWithChatGpt()
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        waitUntil(TIMEOUT_MS) { shown("«Stars»") }
        // Back to the song, then to Settings, where ChatGPT is picked.
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.setting_translation_server)).performScrollTo()
        click(string(R.string.setting_translation_server))
        click("Google Translate")
        assertFalse(prefs().getBoolean("translation_chatgpt", true))
        assertTrue(shown("Google Translate"))
        // The words are translated again, by Google Translate now.
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        click(string(R.string.lyrics_button))
        waitUntil(TIMEOUT_MS) { shown("[Stars]") }
    }

    @Test
    fun theModelAskedIsPickedInSettingsAndTranslatesAnew() {
        prefs().edit().putBoolean("lyrics_translation", true).commit()
        val openAi = signedInWithChatGpt()
        openAi.models = { 200 to FakeOpenAi.modelsJson("gpt-6.1-sol" to "list", "gpt-6.1-luna" to "list") }
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        waitUntil(TIMEOUT_MS) { shown("«Stars»") }
        assertEquals("gpt-6.1-luna", openAi.asked.last().getString("model"))
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.chatgpt_model)).performScrollTo()
        click(string(R.string.chatgpt_model))
        click("gpt-6.1-sol")
        assertEquals("gpt-6.1-sol", prefs().getString("chatgpt_model", null))
        // Translated again, by the model picked.
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        click(string(R.string.lyrics_button))
        waitUntil(TIMEOUT_MS) { openAi.asked.last().getString("model") == "gpt-6.1-sol" }
    }

    @Test
    fun theWordsAreClosedByASwipeDownFromTheTopNotByPullingThemDown() {
        wordsOf(null, "Night sky\\nStars")
        waitUntil(TIMEOUT_MS) { shown("Night sky\nStars") }
        // Pulled down from the first line, they only scroll, which they can't: they stay.
        composeRule.onNode(hasText("Night sky\nStars")).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertTrue(shown("Night sky\nStars"))
        // From the top of the screen, it closes them.
        composeRule.onRoot().performTouchInput { swipeDown() }
        waitUntil(TIMEOUT_MS) { !shown("Night sky\nStars") }
    }

    @Test
    fun whatTheSongMeansIsInTheTranslateMenuSignedIn() {
        prefs().edit().putBoolean("lyrics_translation", true).commit()
        val openAi = signedInWithChatGpt()
        val explained = openAi.reply
        openAi.reply = { body -> if ("explain the song in four parts" in body.getString("instructions")) 500 to "" else explained(body) }
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        waitUntil(TIMEOUT_MS) { shown("«Stars»") }
        aboutSong()
        waitUntil(TIMEOUT_MS) { shown(string(R.string.lyrics_song_meaning_failed)) }
        // Asked again, it's explained.
        openAi.reply = explained
        click(string(R.string.retry_button))
        waitUntil(TIMEOUT_MS) { shown("A night under the stars.") }
        // What's known about it, its story and mood; no key lines, so their label isn't shown.
        assertTrue(shown("From 1985.") && shown("Calm.") && shown(string(R.string.song_about)) && shown(string(R.string.song_story)) && shown(string(R.string.song_mood)))
        assertFalse(shown(string(R.string.song_key_lines)))
        val asked = openAi.asked.count { "explain the song in four parts" in it.getString("instructions") }
        // Closed and opened again, it's there, not asked again.
        composeRule.onNode(hasText("A night under the stars.")).performTouchInput { swipeDown() }
        waitUntil(TIMEOUT_MS) { !shown("A night under the stars.") }
        aboutSong()
        waitForText("A night under the stars.")
        assertEquals(asked, openAi.asked.count { "explain the song in four parts" in it.getString("instructions") })
    }

    @Test
    fun aLineExplainedCanBeAskedAboutAgainAndAgain() {
        prefs().edit().putBoolean("lyrics_translation", true).commit()
        val openAi = signedInWithChatGpt()
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        waitUntil(TIMEOUT_MS) { shown("«Stars»") }
        composeRule.onAllNodesWithTag(LYRIC_LINE_TAG)[0].performClick()
        // Nothing to ask about before it's explained.
        assertFalse(shown(string(R.string.lyrics_ask_line)))
        click(string(R.string.lyrics_explain_line))
        waitUntil(TIMEOUT_MS) { shown(string(R.string.lyrics_ask_line)) }
        composeRule.onNode(hasText(string(R.string.lyrics_ask_line))).performTextInput("Why night?")
        click(string(R.string.lyrics_ask_send))
        waitUntil(TIMEOUT_MS) { shown("Because it rhymes.") }
        assertTrue(shown("Why night?"))
        // The next question goes with the last one and its answer.
        composeRule.onNode(hasText(string(R.string.lyrics_ask_line))).performTextInput("And stars?")
        click(string(R.string.lyrics_ask_send))
        waitUntil(TIMEOUT_MS) { openAi.asked.last().getJSONArray("input").getJSONObject(0).getString("content").endsWith("Question: And stars?") }
        assertTrue(openAi.asked.last().getJSONArray("input").getJSONObject(0).getString("content").contains("Question: Why night?\nAnswer: Because it rhymes."))
        // Failing says so.
        openAi.reply = { 500 to "" }
        composeRule.onNode(hasText(string(R.string.lyrics_ask_line))).performTextInput("Again?")
        click(string(R.string.lyrics_ask_send))
        waitUntil(TIMEOUT_MS) { shown(string(R.string.lyrics_ask_failed)) }
    }

    @Test
    fun kanjiAreReadAsTheyreSungWhereALyricsSiteSaysAndLinesKeptAreReadSoButOnesPutRightByHand() {
        prefs().edit().putString(
            "saved_lines",
            """[{"text":"都会の夜","reading":"とかいのよる","title":"Demo","artist":"Band","ruby":[["都会","とかい"],["の",null],["夜","よる"]]},""" +
                """{"text":"都会の朝","reading":"とかいのあした","title":"Demo","artist":"Band","ruby":[["都会","とかい"],["の",null],["朝","あした"]],"byHand":true}]"""
        ).commit()
        val ruby = { kanji: String, kana: String -> """<span class="ruby"><span class="rb">$kanji</span><span class="rt">$kana</span></span>""" }
        wordsOf(null, "都会の夜\\n都会の朝", sites = { request ->
            when {
                request.url.host != "utaten.com" -> null
                request.url.encodedPath == "/search" ->
                    FakeSpotify.html(request, """<p class="searchResult__title"><a href="/lyric/ab0001/">Demo</a></p></td><td class="searchResult__artist"><p><a href="/artist/1/">Band</a>""")
                else -> FakeSpotify.html(request, """<div class="hiragana" >${ruby("都会", "まち")}の${ruby("夜", "よる")}<br />${ruby("都会", "まち")}の${ruby("朝", "あさ")}<br /></div>""")
            }
        })
        // The dictionary reads 都会 as とかい; it's sung まち.
        waitUntil(TIMEOUT_MS) { readingShown("まち") }
        assertFalse(readingShown("とかい"))
        val kept = SavedLinesStore(prefs()).load()
        assertEquals("まちのよる", kept.first { it.text == "都会の夜" }.reading)
        // Put right by hand, it stays so.
        assertEquals("とかいのあした", kept.first { it.text == "都会の朝" }.reading)
    }

    @Test
    fun japaneseWordsWrittenChinasWayAreReadAsJapanWritesThemAndSoAreLinesKeptFromThem() {
        // Kept from words a Chinese site wrote with 瞬间 for 瞬間, and China's comma, so read wrong.
        prefs().edit().putString(
            "saved_lines",
            """[{"text":"真実の瞬间はいつも，","reading":"しんじつのまどか间はいつも，","title":"Demo","artist":"Band","ruby":[["真実","しんじつ"],["の",null],["瞬","まどか"],["间はいつも，",null]]},""" +
                """{"text":"都会の夜","reading":"まちのよる","title":"Demo","artist":"Band","ruby":[["都会","まち"],["の",null],["夜","よる"]]}]"""
        ).commit()
        wordsOf(null, "真実の瞬间はいつも，\\n仆や君のため，\\n都会の夜")
        waitUntil(TIMEOUT_MS) { readingShown("しゅんかん") && readingShown("ぼく") }
        assertTrue(shown("真実の瞬間はいつも"))
        val kept = SavedLinesStore(prefs()).load()
        assertEquals("真実の瞬間はいつも", kept[0].text)
        assertEquals("しんじつのしゅんかんはいつも", kept[0].reading)
        // One read already, as it's sung, stays so.
        assertEquals("まちのよる", kept[1].reading)
    }

    @Test
    fun signedInReadingsTheDictionaryGetsWrongArePutRight() {
        prefs().edit().putBoolean("lyrics_readings", true).commit()
        val openAi = signedInWithChatGpt()
        wordsOf(null, "君はアスファルト・レディ\\n夜の雨", openAi = openAi)
        // The dictionary reads 君 as くん here; ChatGPT puts it right.
        waitUntil(TIMEOUT_MS) { readingShown("きみ") }
        assertFalse(readingShown("くん"))
    }

    @Test
    fun aLineSavedIsPractisedStraightFromTheStudyMenu() {
        prefs().edit().putBoolean("lyrics_translation", true).commit()
        val openAi = signedInWithChatGpt()
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        waitUntil(TIMEOUT_MS) { shown("«Night sky»") }
        composeRule.onAllNodesWithTag(LYRIC_LINE_TAG)[0].performClick()
        click(string(R.string.lyrics_save_line))
        // The sheet closed, back to the words.
        composeRule.onNode(hasText(string(R.string.lyrics_save_line)) or hasText(string(R.string.lyrics_line_saved))).performTouchInput { swipeDown() }
        waitUntil(TIMEOUT_MS) { !shown(string(R.string.lyrics_line_saved)) }
        // The study menu says how many of this song's are due.
        studyMenu()
        assertTrue(shown(string(R.string.practice_due, 1)))
        click(string(R.string.practice_song))
        click(string(R.string.practice_show))
        click(string(R.string.practice_good))
        assertEquals(1, SavedLinesStore(prefs()).load().single().step)
        // Nothing else is due: one practised.
        waitForText(string(R.string.practice_done, 1))
        // Closed, it's back to the words.
        click(string(R.string.floating_close))
        waitForText("«Night sky»")
    }

    @Test
    fun savedLinesArePractisedAsCardsTurnedThenSaidHowWellTheyWereKnown() {
        val saved = """[{"text":"Night sky","translation":"Cielo nocturno","title":"Demo","artist":"Band"},{"text":"Stars","translation":"Estrellas","title":"Demo","artist":"Band","box":1,"due":1}]"""
        prefs().edit().putString("saved_lines", saved).putBoolean("lyrics_translation", true).commit()
        wordsOf(null, "Night sky\nStars")
        waitForText("[Stars]")
        studyMenu()
        click(string(R.string.practice_song))
        // The one due longest first, the line on its face, its meaning to think of; with how far along it is.
        waitForDescription(string(R.string.practice_progress, 0, 2))
        assertFalse(shown("[Night sky]") || shown(string(R.string.practice_good)))
        // Not turned yet, it can't be said how well it was known.
        controllerModel().gradePractice(Grade.GOOD)
        assertEquals(0L, SavedLinesStore(prefs()).load().first { it.text == "Night sky" }.due)
        // Tapped, the card turns.
        composeRule.onNode(hasText("Night sky") and androidx.compose.ui.test.hasClickAction()).performClick()
        waitForText("[Night sky]")
        assertFalse(shown(string(R.string.practice_show)))
        val before = System.currentTimeMillis()
        click(string(R.string.practice_good))
        val good = SavedLinesStore(prefs()).load().first { it.text == "Night sky" }
        assertEquals(1, good.step)
        assertTrue(good.due >= before + 10 * 60_000L && good.due <= System.currentTimeMillis() + 10 * 60_000L)
        // Known once before, it asks the same, and known again, it's back later.
        waitForDescription(string(R.string.practice_progress, 1, 2))
        assertTrue(shown("Stars") && !shown("[Stars]"))
        click(string(R.string.practice_show))
        click(string(R.string.practice_good))
        val again = SavedLinesStore(prefs()).load().first { it.text == "Stars" }
        assertEquals(3, again.interval)
        assertTrue(again.due >= before + 3 * DAY_MS)
        waitForText(string(R.string.practice_done, 2))
    }

    @Test
    fun linesKeptFollowTheSongsWordsWhenTheyChangeAndAreTranslatedAsTheyAreNow() {
        // Kept from the words as another site wrote them, and translated then.
        val saved = """[{"text":"Night  Sky!","translation":"Cielo","title":"Demo","artist":"Band","box":2,"due":5,"explanation":"Looking up.","start":500,"end":4000},""" +
            """{"text":"Stars shine bright","title":"Demo","artist":"Band","box":1,"byHand":true},""" +
            """{"text":"Stars shine so  bright","title":"Demo","artist":"Band"},""" +
            """{"text":"Moonlight","translation":"Luz de luna","title":"Demo","artist":"Band","box":3,"byHand":true},""" +
            """{"text":"sky","line":"Night  Sky!","translation":"cielo","title":"Demo","artist":"Band"},""" +
            """{"text":"shines","line":"Stars shines bright","translation":"brilla","title":"Demo","artist":"Band"},""" +
            """{"text":"Rain on the roof","translation":"Lluvia","title":"Demo","artist":"Band"},""" +
            """{"text":"Night sky","translation":"Noche","title":"Other","artist":"Band"}]"""
        prefs().edit().putString("saved_lines", saved).putBoolean("lyrics_translation", true).commit()
        wordsOf("[00:01.00] Night sky\\n[00:05.00] Stars shine so bright\\n[00:09.00] Moonlight", "Night sky\\nStars shine so bright\\nMoonlight")
        waitUntil(TIMEOUT_MS) { SavedLinesStore(prefs()).load().any { it.translation == "[Night sky]" } }
        val kept = SavedLinesStore(prefs()).load()
        // Each the line it is now, practised as far as it was, translated and timed as the song is now.
        assertEquals(listOf("Night sky", "Stars shine so bright", "Stars shine so  bright", "Moonlight", "sky", "shines", "Rain on the roof", "Night sky"), kept.map { it.text })
        assertEquals(listOf(3, 1), kept.take(2).map { it.interval })
        assertEquals(7, kept[3].interval)
        assertEquals(5L, kept[0].due)
        assertEquals("Looking up.", kept[0].explanation)
        assertEquals(1_000L to 5_000L, kept[0].startMs to kept[0].endMs)
        // Its reading put right by hand was for words no longer sung.
        assertFalse(kept[1].readByHand)
        assertEquals(listOf("[Night sky]", "[Stars shine so bright]"), kept.take(2).map { it.translation })
        assertEquals("[Moonlight]", kept[3].translation)
        // A word stays with its line, with its own meaning, while it's still sung in it.
        assertEquals("Night sky", kept[4].line)
        assertEquals("cielo", kept[4].translation)
        assertEquals("Stars shines bright", kept[5].line)
        // A line no longer sung, and another song's, are left as they were; so is one whose line is kept already.
        assertEquals("Lluvia", kept[6].translation)
        assertEquals("Noche", kept[7].translation)
    }

    @Test
    fun aTimedLineKeptInRomajiFollowsItOnceWrittenInJapanese() {
        prefs().edit().putString("saved_lines", """[{"text":"hoshi ga hikaru","title":"Demo","artist":"Band","box":2,"start":1000,"end":30000}]""").commit()
        val openAi = signedInWithChatGpt()
        wordsOf(ROMAJI, "yoru ni kimi wa", openAi = openAi)
        waitUntil(TIMEOUT_MS) { SavedLinesStore(prefs()).load().single().text == "星が光る" }
        val kept = SavedLinesStore(prefs()).load().single()
        assertEquals(3, kept.interval)
        assertEquals(1000L, kept.startMs)
        assertEquals(30000L, kept.endMs)
    }

    @Test
    fun savedLinesArePractisedAsCardsSignedOut() {
        prefs().edit().putString("saved_lines", """[{"text":"Night sky","translation":"Cielo nocturno","title":"Demo","artist":"Band"}]""").commit()
        prefs().edit().putBoolean("lyrics_translation", true).commit()
        wordsOf(null, "Night sky\\nStars")
        waitForText("[Stars]")
        studyMenu()
        click(string(R.string.practice_song))
        click(string(R.string.practice_show))
        // The meaning the song has now, not the one it had when kept.
        waitForText("[Night sky]")
        // Missed, it's back in ten minutes, and once more before the session's done.
        click(string(R.string.practice_again))
        assertEquals(0, SavedLinesStore(prefs()).load().single().step)
        waitUntil(TIMEOUT_MS) { controllerModel().uiState.practice?.missed == 1 && shown(string(R.string.practice_show)) }
        assertEquals(1, controllerModel().uiState.practice!!.queue.size)
        // Back goes back to the words.
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertNull(controllerModel().uiState.practice)
        waitForText("[Stars]")
    }

    @Test
    fun aLineSavedFirstGetsItsExplanationAndAUsedUpPlanSaysSo() {
        prefs().edit().putBoolean("lyrics_translation", true).commit()
        val openAi = signedInWithChatGpt()
        val explained = openAi.reply
        openAi.reply = { 429 to "" }
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        // ChatGPT can't translate with the plan used up, so Google Translate does, as before.
        waitForText("[Stars]")
        composeRule.onAllNodesWithTag(LYRIC_LINE_TAG)[1].performClick()
        click(string(R.string.lyrics_save_line))
        click(string(R.string.lyrics_explain_line))
        waitUntil(TIMEOUT_MS) { shown(string(R.string.chatgpt_limit_reached)) }
        assertTrue(shown(string(R.string.chatgpt_manage_usage)))
        // Anything else failing says so, and it can be asked again.
        openAi.reply = { 500 to "" }
        click(string(R.string.lyrics_explain_line))
        waitUntil(TIMEOUT_MS) { shown(string(R.string.lyrics_explain_failed)) }
        openAi.reply = explained
        click(string(R.string.lyrics_explain_line))
        waitUntil(TIMEOUT_MS) { shown("Looking up at night.") }
        // Saved before it was explained, the line keeps the explanation too.
        assertEquals("Looking up at night.\n---\nA noun and its adjective.", SavedLinesStore(prefs()).load().single().explanation)
    }

    @Test
    fun theLineSheetShowsJapaneseWithItsReadingsOverItAndItsWordsToTap() {
        wordsOf("[00:00.00] 夜空に星が\\n[00:30.00] 食べる", "夜空に星が\\n食べる")
        waitForText("食べる")
        // Readings are on from the start.
        waitUntil(TIMEOUT_MS) { readingShown("よぞら") }
        composeRule.onAllNodes(hasText("夜空に星が")).onFirst().performTouchInput { longClick() }
        composeRule.waitForIdle()
        // In the sheet, the line as the lyrics show it: its kanji with their readings over them.
        val inSheet = androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(STUDY_LINE_TAG))
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(hasText("よぞら") and inSheet).fetchSemanticsNodes().isNotEmpty() }
        // Each of its words still taps for its meaning, with its reading over it, once the dictionary
        // that finds them is loaded; the line may show a moment without its readings meanwhile.
        waitUntil(TIMEOUT_MS) {
            if (composeRule.onAllNodes(hasText("よぞら") and inSheet).fetchSemanticsNodes().isNotEmpty()) {
                runCatching { composeRule.onAllNodes(hasText("夜空") and inSheet).onFirst().performFirstLinkClick() }
            }
            composeRule.waitForIdle()
            shown("yozora")
        }
    }

    @Test
    fun aProblemWithTheWordsIsReportedWithTheSongAndHowTheyFollowedIt() {
        wordsOf("[00:00.00] Sky full of stars\\n[00:30.00] Eat", "Sky full of stars\\nEat")
        waitForText("Sky full of stars")
        // Signed out, the song's sheet holds only that, with none of what ChatGPT says.
        aboutSong()
        assertFalse(shown(string(R.string.song_tab_meaning)))
        // The report action lines up with the song's name in the sheet, not under its middle.
        val report = composeRule.onNode(hasText(string(R.string.report_problem)) and hasClickAction()).getBoundsInRoot()
        val title = composeRule.onAllNodesWithText("Demo")
        var titleLeft = -1f
        var titleGap = Float.MAX_VALUE
        repeat(title.fetchSemanticsNodes().size) { index ->
            val bounds = title[index].getBoundsInRoot()
            val gap = (report.top - bounds.bottom).value
            if (gap in -4f..48f && gap < titleGap) {
                titleGap = gap
                titleLeft = bounds.left.value
            }
        }
        assertEquals(titleLeft, report.left.value, 2f)
        click(string(R.string.report_problem))
        click(string(R.string.report_by_email))
        val email = Uri.decode(shadowOf(app).nextStartedActivity.dataString)
        assertTrue(email, email.contains("subject=Lyrics of Demo by "))
        assertTrue(email, email.contains("Song: Demo\n") && email.contains("Lyrics: timed\n"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theWordsCanBeMadeBiggerOrSmallerAndStaySo() {
        // Kept bigger than they go, from a build that let them, they're as big as they go now.
        prefs().edit().putFloat("words_scale", 5f).commit()
        wordsOf("[00:00.00] Sky full of stars\\n[00:30.00] Eat", "Sky full of stars\\nEat")
        waitForText("Sky full of stars")
        assertEquals(MAX_WORDS_SCALE, controllerModel().uiState.learning.wordsScale)
        val big = composeRule.onNodeWithText("Sky full of stars").getBoundsInRoot().let { it.bottom - it.top }
        learnMenu()
        composeRule.onNode(hasContentDescription(string(R.string.lyrics_words_size))).performSemanticsAction(SemanticsActions.SetProgress) { it(MIN_WORDS_SCALE) }
        composeRule.waitForIdle()
        assertEquals(MIN_WORDS_SCALE, prefs().getFloat("words_scale", 0f), 0.01f)
        assertTrue(shown(NumberFormat.getPercentInstance().format(sizeShare(MIN_WORDS_SCALE, MIN_WORDS_SCALE..MAX_WORDS_SCALE))))
        assertTrue(composeRule.onNodeWithText("Sky full of stars").getBoundsInRoot().let { it.bottom - it.top } < big)
    }

    @Test
    fun theTranslationHasASizeOfItsOwnThatStartsAsBigAsTheWordsWere() {
        prefs().edit().putFloat("words_scale", 1.5f).putBoolean("lyrics_translation", true).commit()
        wordsOf("[00:00.00] Sky full of stars\\n[00:30.00] Eat", "Sky full of stars\\nEat")
        waitForText("Sky full of stars")
        assertEquals(1.5f, controllerModel().uiState.learning.translationScale, 0f)
        learnMenu()
        composeRule.onNode(hasContentDescription(string(R.string.lyrics_translation_size))).performSemanticsAction(SemanticsActions.SetProgress) { it(MAX_WORDS_SCALE) }
        composeRule.waitForIdle()
        assertEquals(MAX_WORDS_SCALE, prefs().getFloat("translation_scale", 0f), 0.01f)
        assertEquals(1.5f, controllerModel().uiState.learning.wordsScale, 0f)
        // With the translation off there's no size to pick.
        click(string(R.string.lyrics_translation))
        assertFalse(composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_translation_size))).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun lyricsDisplayShowsEveryLayerAsOneTapAndKeepsWordsClearOfThePalette() {
        wordsOf("[00:00.00] 夜空に星が\\n[00:30.00] 食べる", "夜空に星が\\n食べる")
        waitForText("食べる")
        learnMenu()
        // Readings, Latin and the translation are chips beside the sizes, with no page of their own.
        listOf(R.string.lyrics_readings, R.string.lyrics_romanized, R.string.lyrics_translation, R.string.lyrics_words_size).forEach { assertTrue(described(string(it))) }
        val palette = composeRule.onNodeWithTag("lyrics-display-options").getBoundsInRoot()
        assertTrue(composeRule.onAllNodesWithTag(LYRIC_LINE_TAG)[0].getBoundsInRoot().bottom <= palette.top)
        // Back closes them without leaving the song.
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lyrics-display-options").assertDoesNotExist()
        assertTrue(shown("食べる"))
        learnMenu()
        // The icon also closes the palette.
        click(string(R.string.lyrics_learn))
        composeRule.onNodeWithTag("lyrics-display-options").assertDoesNotExist()
    }

    @Test
    fun tappingOutsideTheLyricsPaletteClosesIt() {
        wordsOf("[00:00.00] 夜空に星が\\n[00:30.00] 食べる", "夜空に星が\\n食べる")
        waitForText("食べる")
        learnMenu()
        // The top of the scrim is the words, clear of the card.
        composeRule.onNodeWithTag("lyrics-menu-scrim").performTouchInput {
            down(topCenter)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lyrics-display-options").assertDoesNotExist()
        composeRule.onNodeWithTag("lyrics-menu-scrim").assertDoesNotExist()
        assertTrue(shown("食べる"))
        // Held on the words, it also goes, and the line does not open to study.
        learnMenu()
        composeRule.onNodeWithText("夜空に星が").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lyrics-display-options").assertDoesNotExist()
        composeRule.onNodeWithTag("lyrics-menu-scrim").assertDoesNotExist()
        composeRule.onNodeWithTag(STUDY_LINE_TAG).assertDoesNotExist()
        assertTrue(shown("食べる"))
    }

    @Test
    fun studyAndVisualsOpenAsCardsClearOfTheWordsAndATouchOutsideClosesThem() {
        wordsOf("[00:00.00] Night sky\\n[00:30.00] Stars", "Night sky\\nStars")
        waitForText("Stars")
        click(string(R.string.lyrics_study))
        composeRule.onNodeWithTag("lyrics-study-options").assertExists()
        assertTrue(shown(string(R.string.practice_song)))
        val study = composeRule.onNodeWithTag("lyrics-study-options").getBoundsInRoot()
        assertTrue(composeRule.onAllNodesWithTag(LYRIC_LINE_TAG)[0].getBoundsInRoot().bottom <= study.top)
        composeRule.onNodeWithTag("lyrics-menu-scrim").performTouchInput {
            down(topCenter)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lyrics-study-options").assertDoesNotExist()

        click(string(R.string.visuals_show))
        waitUntil(TIMEOUT_MS) { visualsShown().isNotEmpty() }
        click(string(R.string.visuals_options))
        composeRule.onNodeWithTag("lyrics-visuals-options").assertExists()
        assertTrue(shown(string(R.string.visuals_shade)))
        assertTrue(shown(string(R.string.visuals_next)))
        val percent = NumberFormat.getPercentInstance()
        assertTrue(shown(percent.format(sizeShare(DEFAULT_VISUALS_SHADE, 0f..MAX_VISUALS_SHADE))))
        val shade = hasContentDescription(string(R.string.visuals_shade))
        composeRule.onNode(shade).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        composeRule.waitForIdle()
        assertTrue(shown(percent.format(sizeShare(0f, 0f..MAX_VISUALS_SHADE))))
        composeRule.onNode(shade).performSemanticsAction(SemanticsActions.SetProgress) { it(MAX_VISUALS_SHADE) }
        composeRule.waitForIdle()
        assertTrue(shown(percent.format(sizeShare(MAX_VISUALS_SHADE, 0f..MAX_VISUALS_SHADE))))
        val visuals = composeRule.onNodeWithTag("lyrics-visuals-options").getBoundsInRoot()
        assertTrue(composeRule.onAllNodesWithTag(LYRIC_LINE_TAG)[0].getBoundsInRoot().bottom <= visuals.top)
        composeRule.onNodeWithTag("lyrics-menu-scrim").performTouchInput {
            down(topCenter)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lyrics-visuals-options").assertDoesNotExist()
        assertTrue(shown("Night sky"))
    }

    @Test
    fun readingsAreOnFromTheStartAndCanBeMadeBigger() {
        // Kept bigger than they go, from a build that let them, they're as big as they go now.
        prefs().edit().putFloat("reading_scale", 5f).commit()
        wordsOf("[00:00.00] 夜空に星が\\n[00:30.00] 食べる", "夜空に星が\\nたべる")
        waitForText("夜空に星が")
        assertEquals(MAX_READING_SCALE, controllerModel().uiState.learning.readingScale)
        learnMenu()
        val size = hasContentDescription(string(R.string.lyrics_reading_size))
        composeRule.onNode(size).performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
        composeRule.waitForIdle()
        assertEquals(0.6f, controllerModel().uiState.learning.readingScale, 0.01f)
        assertEquals(0.6f, prefs().getFloat("reading_scale", 0f), 0.01f)
        // The small end reads 1% while keeping readings at a readable size.
        composeRule.onNode(size).performSemanticsAction(SemanticsActions.SetProgress) { it(MIN_READING_SCALE) }
        composeRule.waitForIdle()
        assertEquals(MIN_READING_SCALE, controllerModel().uiState.learning.readingScale, 0.0001f)
        assertEquals(MIN_READING_SCALE, prefs().getFloat("reading_scale", 0f), 0.0001f)
        assertTrue(shown(NumberFormat.getPercentInstance().format(sizeShare(MIN_READING_SCALE, MIN_READING_SCALE..MAX_READING_SCALE))))
        // Without readings, there's no size to change.
        click(string(R.string.lyrics_readings))
        composeRule.waitForIdle()
        assertFalse(composeRule.onAllNodes(size).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun japaneseWordsShowOnlyInKanaOrRomajiAlsoFloating() {
        wordsOf("[00:00.00] 夜空に星が\\n[00:30.00] 食べる", "夜空に星が\\n食べる")
        waitForText("食べる")
        learnMenu()
        // Only in kana, readings over kanji have nothing to sit over; romaji can still go under.
        click(string(R.string.lyrics_only_kana))
        waitUntil(TIMEOUT_MS) { shown("よぞらにほしが") && shown("たべる") }
        assertFalse(shown("夜空に星が"))
        assertFalse(described(string(R.string.lyrics_readings)))
        assertEquals("KANA", prefs().getString("lyrics_words_as", null))
        click(string(R.string.lyrics_romanized))
        waitUntil(TIMEOUT_MS) { shown("yozora ni hoshi ga") }
        // Only in romaji, it isn't said twice.
        click(string(R.string.lyrics_only_latin))
        waitUntil(TIMEOUT_MS) { !shown("よぞらにほしが") }
        assertEquals(1, composeRule.onAllNodes(hasText("yozora ni hoshi ga")).fetchSemanticsNodes().size)
        assertFalse(described(string(R.string.lyrics_romanized)))
        // Floating, too.
        val floating = floatNow()
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).assertExists()
        assertTrue(shown("yozora ni hoshi ga"))
        assertFalse(shown("夜空に星が"))
        floating.destroy()
        controller!!.start().resume()
        composeRule.waitForIdle()
        // As written again.
        learnMenu()
        click(string(R.string.lyrics_as_written))
        waitUntil(TIMEOUT_MS) { shown("夜空に星が") }
        assertEquals("WRITTEN", prefs().getString("lyrics_words_as", null))
    }

    /** The chords over Demo's line [line], once they're drawn. */
    private fun chordsOver(line: String): List<String>? =
        composeRule.onAllNodes(hasText(line), useUnmergedTree = true).fetchSemanticsNodes().firstNotNullOfOrNull { it.config.getOrElseNullable(ChordsShown) { null } }

    @Test
    fun chordsSwitchedOnShowOverTheWordsFromNowOn() {
        wordsOf(null, "Walking down the river road\\nSinging to the morning light")
        waitForText("Walking down the river road\nSinging to the morning light")
        val words = fake.handler
        var up = false
        val page = { content: String ->
            val store = org.json.JSONObject().put("store", org.json.JSONObject().put("page", org.json.JSONObject().put("data", org.json.JSONObject(content)))).toString()
            """<div class="js-store" data-content="${store.replace("&", "&amp;").replace("\"", "&quot;")}"></div>"""
        }
        fake.handler = { request ->
            when (request.url.host) {
                "www.ufret.jp" -> FakeSpotify.html(request, "")
                "www.ultimate-guitar.com" -> if (up) {
                    FakeSpotify.html(request, page("""{"results":[{"song_name":"Demo","artist_name":"Band","type":"Chords","votes":5,"tab_url":"https://tabs.ultimate-guitar.com/tab/demo-1"}]}"""))
                } else {
                    throw IOException("offline")
                }
                "tabs.ultimate-guitar.com" -> FakeSpotify.html(
                    request,
                    page("""{"tab_view":{"meta":{"capo":3},"wiki_tab":{"content":"[ch]G[/ch]      [ch]C[/ch]\nWalking down the river road\n[ch]D[/ch]\nSinging to the morning light"}}}""")
                )
                else -> words(request)
            }
        }
        // A site that won't answer shows nothing; switched off and on, it's asked again.
        studyMenu()
        click(string(R.string.lyrics_chords))
        assertTrue(prefs().getBoolean("lyrics_chords", false))
        waitUntil(TIMEOUT_MS) { fake.requestedUrls.any { it.startsWith("https://www.ultimate-guitar.com/search.php") } }
        assertNull(chordsOver("Walking down the river road"))
        up = true
        click(string(R.string.lyrics_chords))
        click(string(R.string.lyrics_chords))
        waitUntil(TIMEOUT_MS) { chordsOver("Walking down the river road") == listOf("G", "C") }
        assertTrue(shown(string(R.string.lyrics_capo, 3)))
        assertEquals(listOf("D"), chordsOver("Singing to the morning light"))

        // Switched off, they go; on again, they're back with no lookup.
        val asked = fake.requestedUrls.size
        click(string(R.string.lyrics_chords))
        waitUntil(TIMEOUT_MS) { chordsOver("Walking down the river road") == null }
        assertFalse(prefs().getBoolean("lyrics_chords", true))
        click(string(R.string.lyrics_chords))
        waitUntil(TIMEOUT_MS) { chordsOver("Walking down the river road") != null }
        assertEquals(asked, fake.requestedUrls.size)
    }

    @Test
    fun chordsSwitchedOffStopBeingLookedUp() {
        wordsOf(null, "Walking down the river road\\nSinging to the morning light")
        waitForText("Walking down the river road\nSinging to the morning light")
        val words = fake.handler
        val searches = java.util.concurrent.atomic.AtomicInteger()
        val page = { content: String ->
            val store = org.json.JSONObject().put("store", org.json.JSONObject().put("page", org.json.JSONObject().put("data", org.json.JSONObject(content)))).toString()
            """<div class="js-store" data-content="${store.replace("&", "&amp;").replace("\"", "&quot;")}"></div>"""
        }
        fake.handler = { request ->
            when (request.url.host) {
                // The first search hangs, then fails; those after it find them.
                "www.ultimate-guitar.com" -> if (searches.incrementAndGet() == 1) {
                    Thread.sleep(2_000)
                    throw IOException("timed out")
                } else {
                    FakeSpotify.html(request, page("""{"results":[{"song_name":"Demo","artist_name":"Band","type":"Chords","votes":5,"tab_url":"https://tabs.ultimate-guitar.com/tab/demo-1"}]}"""))
                }
                "tabs.ultimate-guitar.com" -> FakeSpotify.html(
                    request,
                    page("""{"tab_view":{"wiki_tab":{"content":"[ch]G[/ch]      [ch]C[/ch]\nWalking down the river road\n[ch]D[/ch]\nSinging to the morning light"}}}""")
                )
                else -> words(request)
            }
        }
        studyMenu()
        click(string(R.string.lyrics_chords))
        waitUntil(TIMEOUT_MS) { searches.get() == 1 }
        // Off mid-lookup and on again: looked up anew, and the one dropped doesn't take them away when it ends.
        click(string(R.string.lyrics_chords))
        click(string(R.string.lyrics_chords))
        waitUntil(TIMEOUT_MS) { chordsOver("Walking down the river road") == listOf("G", "C") }
        Thread.sleep(2_500)
        composeRule.waitForIdle()
        assertEquals(listOf("G", "C"), chordsOver("Walking down the river road"))
    }

    @Test
    fun aSongWithoutChordsShowsJustItsWords() {
        prefs().edit().putBoolean("lyrics_chords", true).commit()
        wordsOf("[00:01.00] Walking down the river road\\n[00:05.00] Singing to the morning light", "Walking down the river road\\nSinging to the morning light")
        // On from before, they're looked up with the words; neither site has them, and nothing says so.
        waitUntil(TIMEOUT_MS) { fake.requestedUrls.any { it.startsWith("https://www.ufret.jp/search.php") } }
        assertTrue(fake.requestedUrls.any { it.startsWith("https://www.ultimate-guitar.com/search.php") })
        assertTrue(shown("Walking down the river road"))
        assertNull(chordsOver("Walking down the river road"))
    }

    @Test
    fun theNextSongsChordsAreLookedUpThoughTheLastOneHadNone() {
        prefs().edit().putBoolean("lyrics_chords", true).commit()
        val hearing = FakeHearing()
        listeningAlong(hearing)
        val words = fake.handler
        val page = { content: String ->
            val store = org.json.JSONObject().put("store", org.json.JSONObject().put("page", org.json.JSONObject().put("data", org.json.JSONObject(content)))).toString()
            """<div class="js-store" data-content="${store.replace("&", "&amp;").replace("\"", "&quot;")}"></div>"""
        }
        fake.handler = { request ->
            when (request.url.host) {
                "www.ufret.jp" -> FakeSpotify.html(request, "")
                // Only Other has chords.
                "www.ultimate-guitar.com" -> FakeSpotify.html(
                    request,
                    if ("Other" in request.url.queryParameter("value").orEmpty()) {
                        page("""{"results":[{"song_name":"Other","artist_name":"Band","type":"Chords","votes":5,"tab_url":"https://tabs.ultimate-guitar.com/tab/other-1"}]}""")
                    } else {
                        page("""{"results":[]}""")
                    }
                )
                "tabs.ultimate-guitar.com" -> FakeSpotify.html(request, page("""{"tab_view":{"wiki_tab":{"content":"[ch]G[/ch]\nOther one\n[ch]C[/ch]\nOther two\n[ch]D[/ch]\nOther three"}}}"""))
                else -> words(request)
            }
        }
        allowMicrophone()
        launch()
        hearing.song("Demo", 2.0)
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        waitUntil(TIMEOUT_MS) { fake.requestedUrls.any { it.startsWith("https://www.ufret.jp/search.php") } }
        hearing.song("Other", 3.0)
        waitUntil(TIMEOUT_MS) { chordsOver("Other one") == listOf("G") }
    }

    @Test
    fun japaneseWordsShowTheirKanaRomajiAndTranslationAlsoFloating() {
        wordsOf("[00:00.00] 夜空に星が\\n[00:30.00] 食べる", "夜空に星が\\n食べる")
        waitForText("食べる")
        // Readings are on from the start.
        waitUntil(TIMEOUT_MS) { readingShown("よぞら") }
        // Each line laid out as a whole, the kana after a kanji as written.
        assertTrue(shown("食べる") && readingShown("た"))
        learnMenu()
        click(string(R.string.lyrics_romanized))
        waitUntil(TIMEOUT_MS) { shown("yozora ni hoshi ga") }
        click(string(R.string.lyrics_translation))
        waitUntil(TIMEOUT_MS) { shown("[夜空に星が]") }
        assertTrue(shown("[食べる]"))

        // Floating, the line being sung brings its reading, romaji and translation along.
        val floating = floatNow()
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).assertExists()
        assertTrue(shown("よぞらにほしが"))
        assertTrue(shown("yozora ni hoshi ga"))
        assertTrue(shown("[夜空に星が]"))
        floating.destroy()
        controller!!.start().resume()
        composeRule.waitForIdle()

        // Switched off, each goes; kept off for the next song.
        learnMenu()
        click(string(R.string.lyrics_readings))
        click(string(R.string.lyrics_romanized))
        click(string(R.string.lyrics_translation))
        composeRule.waitForIdle()
        assertFalse(readingShown("よぞら"))
        assertFalse(shown("[食べる]"))
        assertFalse(prefs().getBoolean("lyrics_readings", true))
        assertFalse(prefs().getBoolean("lyrics_translation", true))
    }

    @Test
    fun japaneseRomajiCanBeSpelledAsTyped() {
        wordsOf("[00:00.00] 東京を\\n[00:30.00] 食べる", "東京を\\n食べる")
        waitForText("食べる")
        learnMenu()
        // Only with romaji shown.
        assertFalse(shown(string(R.string.lyrics_romaji_typed)))
        click(string(R.string.lyrics_romanized))
        waitUntil(TIMEOUT_MS) { shown("tookyoo o") }
        click(string(R.string.lyrics_romaji_typed))
        waitUntil(TIMEOUT_MS) { shown("toukyou wo") }
        assertFalse(shown("tookyoo o"))
        assertTrue(prefs().getBoolean("lyrics_romaji_typed", false))
        // Only in romaji, too.
        click(string(R.string.lyrics_only_latin))
        waitUntil(TIMEOUT_MS) { !shown("東京を") }
        assertTrue(shown("toukyou wo"))
        assertTrue(shown(string(R.string.lyrics_romaji_typed)))
    }

    @Test
    fun koreanWordsFloatTheirLatinLettersOnce() {
        wordsOf("[00:00.00] 사랑해\\n[00:30.00] 좋아", "사랑해\\n좋아")
        waitForText("사랑해")
        learnMenu()
        click(string(R.string.lyrics_romanized))
        waitUntil(TIMEOUT_MS) { shown("saranghae") }

        // Hangul's reading is its Latin letters, so floating, they show once, not as both.
        val floating = floatNow()
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).assertExists()
        assertEquals(1, composeRule.onAllNodesWithText("saranghae", useUnmergedTree = true).fetchSemanticsNodes().size)
        floating.destroy()
    }

    @Test
    fun wordsAlreadyInTheAppsLanguageSayNothingAndTheirWordsMeanWhatTheDictionarySays() {
        wordsOf("[00:00.00] Night sky\\n[00:30.00] Stars", "Night sky\\nStars")
        fake.handler = { request ->
            when {
                // Already in English, each line, or word, comes back as it was.
                request.url.host == GOOGLE_TRANSLATE_HOST -> FakeSpotify.html(request, googleRows(request) { it })
                request.url.encodedPath.endsWith("/night") -> FakeSpotify.html(request, """{"en":[{"definitions":[{"definition":"The time between sunset and sunrise."}]}]}""")
                else -> FakeSpotify.html(request, "{}", code = 404)
            }
        }
        waitForText("Stars")
        learnMenu()
        click(string(R.string.lyrics_translation))
        // Not an error, and nothing worth saying: there's just nothing to translate them into.
        waitUntil(TIMEOUT_MS) { fake.requestedUrls.any { GOOGLE_TRANSLATE_HOST in it } }
        composeRule.waitForIdle()
        assertFalse(described(string(R.string.lyrics_translation_failed)))

        // The card covers the words, so it goes away before one can be held.
        click(string(R.string.lyrics_learn))
        composeRule.onNodeWithText("Night sky").performTouchInput { longClick() }
        composeRule.waitForIdle()
        val line = composeRule.onNode(hasText("Night sky") and androidx.compose.ui.test.hasTestTag(STUDY_LINE_TAG))
        waitUntil(TIMEOUT_MS) { runCatching { line.performFirstLinkClick { (it.item as androidx.compose.ui.text.LinkAnnotation.Clickable).tag == "Night" } }.isSuccess }
        waitForText("The time between sunset and sunrise.")
        // Signed out, there are no examples to ask for.
        assertFalse(shown(string(R.string.lyrics_examples)))
        line.performFirstLinkClick { (it.item as androidx.compose.ui.text.LinkAnnotation.Clickable).tag == "sky" }
        waitForText(string(R.string.lyrics_word_unknown))
    }

    @Test
    fun wordsAreTranslatedIntoTheLanguagePicked() {
        prefs().edit().putBoolean("lyrics_translation", true).putString("translate_into", "pt-BR").commit()
        wordsOf("[00:00.00] Night sky\\n[00:30.00] Stars", "Night sky\\nStars")
        waitUntil(TIMEOUT_MS) { shown("[Stars]") }
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.any { GOOGLE_TRANSLATE_HOST in it && "tl=pt-BR" in it })
    }

    @Test
    fun songsInALanguageTheUserReadsArentTranslated() {
        var language = "en"
        MainActivity.lyricsLanguageFactory = { LyricsLanguage { language } }
        prefs().edit().putBoolean("lyrics_translation", true).putStringSet("untranslated_languages", setOf("en", "fr")).commit()
        wordsOf("[00:00.00] Night sky\\n[00:30.00] Stars", "Night sky\\nStars")
        waitForText("Stars")
        composeRule.waitForIdle()
        assertFalse(shown("[Stars]"))
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.none { GOOGLE_TRANSLATE_HOST in it || "api.mymemory.translated.net" in it })
        // One in a language not picked is.
        language = "es"
        controllerModel().setUntranslated(setOf("en"))
        waitUntil(TIMEOUT_MS) { shown("[Stars]") }
    }

    @Test
    fun aSongWhoseLanguageCantBeToldIsTranslated() {
        MainActivity.lyricsLanguageFactory = { LyricsLanguage { null } }
        prefs().edit().putBoolean("lyrics_translation", true).putStringSet("untranslated_languages", setOf("en")).commit()
        wordsOf("[00:00.00] Night sky\\n[00:30.00] Stars", "Night sky\\nStars")
        waitUntil(TIMEOUT_MS) { shown("[Stars]") }
    }

    @Test
    fun wordsAreTranslatedIntoTheAppsLanguageAsItIsNow() {
        prefs().edit().putBoolean("lyrics_translation", true).commit()
        MainActivity.listenAlongPauseMs = 0
        MainActivity.hearingFactory = { FakeHearing().apply { song("Demo", 2.0) } }
        fake.handler = { request ->
            when (request.url.host) {
                GOOGLE_TRANSLATE_HOST -> translation(request, 200)
                else -> FakeSpotify.html(request, """[{"trackName":"Demo","artistName":"Band","plainLyrics":"Night sky\nStars"}]""")
            }
        }
        allowMicrophone()
        launch()
        // The app's language changed since Crosstune opened, as in its settings.
        app.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags("ja")
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        waitForText("Night sky")
        waitUntil(TIMEOUT_MS) { fake.requestedUrls.any { GOOGLE_TRANSLATE_HOST in it } }
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.any { GOOGLE_TRANSLATE_HOST in it && "tl=ja" in it })
    }

    @Test
    fun aTranslationThatCantBeDoneOffersToTryAgain() {
        prefs().edit().putBoolean("lyrics_translation", true).commit()
        wordsOf("[00:00.00] Night sky\\n[00:30.00] Stars", "Night sky\\nStars", translations = 503)
        // Shown as a button to try again, with no words of its own.
        waitUntil(TIMEOUT_MS) { described(string(R.string.lyrics_translation_failed)) }
        assertFalse(shown(string(R.string.lyrics_translation_failed)))
        assertFalse(shown("[Stars]"))
        fake.handler = { request -> translation(request) }
        click(string(R.string.lyrics_translation_failed))
        waitUntil(TIMEOUT_MS) { shown("[Stars]") }
        assertFalse(described(string(R.string.lyrics_translation_failed)))
        // English has no readings to offer, only a translation; it can still be learned.
        assertTrue(described(string(R.string.lyrics_study)))
        learnMenu()
        assertFalse(described(string(R.string.lyrics_as_written)))
        assertFalse(described(string(R.string.lyrics_readings)))
        assertFalse(described(string(R.string.lyrics_romanized)))
    }

    @Test
    fun russianWordsShowAsWrittenOrInLatinLettersWithTheirOwnLetterForWritten() {
        wordsOf(null, "Привет мир")
        waitForText("Привет мир")
        learnMenu()
        // Each script has a letter of its own in the picker, and only Japanese has kana.
        assertTrue(described(string(R.string.lyrics_as_written)))
        assertFalse(described(string(R.string.lyrics_only_kana)))
        assertFalse(described(string(R.string.lyrics_readings)))
        click(string(R.string.lyrics_only_latin))
        waitUntil(TIMEOUT_MS) { shown("Privet mir") }
    }

    @Test
    fun untimedKoreanWordsAreRomanizedLineByLine() {
        prefs().edit().putBoolean("lyrics_readings", true).commit()
        wordsOf(null, "사랑해\\n한국")
        waitForText("한국")
        learnMenu()
        // Hangul reads as it's written, so there are only Latin letters, and no kana.
        assertFalse(described(string(R.string.lyrics_readings)))
        assertFalse(described(string(R.string.lyrics_only_kana)))
        click(string(R.string.lyrics_romanized))
        waitUntil(TIMEOUT_MS) { shown("saranghae") }
        assertTrue(shown("hanguk"))

        // The card covers the line. Once it is out of the way, a tap opens the line to study,
        // its words looked up as the lines are translated.
        click(string(R.string.lyrics_learn))
        composeRule.onAllNodesWithTag(LYRIC_LINE_TAG)[0].performClick()
        composeRule.waitForIdle()
        assertFalse(shown(string(R.string.lyrics_repeat_line)))
        // The line, once, with its words to tap in it.
        val line = hasText("사랑해") and androidx.compose.ui.test.hasTestTag(STUDY_LINE_TAG)
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(line).fetchSemanticsNodes().isNotEmpty() }
        // Written once in the sheet: no list of its words besides.
        assertEquals(1, composeRule.onAllNodes(hasText("사랑해") and !androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(LYRIC_LINE_TAG)) and !androidx.compose.ui.test.hasTestTag(LYRIC_LINE_TAG)).fetchSemanticsNodes().size)
        // Its words are found as Readings splits them, a moment after it opens, then tapped.
        waitUntil(TIMEOUT_MS) { runCatching { composeRule.onNode(line).performFirstLinkClick() }.isSuccess }
        waitUntil(TIMEOUT_MS) { shown("[사랑해]") }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val NO_MATCH = """{"matches":[]}"""
        const val MATCH = """{"matches":[{"id":"1"}],"track":{"title":"Iris","subtitle":"The Goo Goo Dolls",""" +
            """"hub":{"actions":[{"type":"applemusicplay","id":"1109658204"}]}}}"""

        /** Timed words in romaji, as LRCLIB has some Japanese songs. */
        const val ROMAJI = "[00:00.00] yoru ni kimi wa\\n[00:01.00] hoshi ga hikaru\\n[00:30.00] boku mo namida\\n[09:00.00] ashita no uta"

        /** What ChatGPT writes the romaji lines of the tests in. */
        val ROMAJI_JAPANESE = mapOf("yoru ni kimi wa" to "夜に君は", "hoshi ga hikaru" to "星が光る", "boku mo namida" to "僕も涙", "ashita no uta" to "明日の歌")
    }

    /** Waits for the words, shown whole when nothing helps read them. */
    private fun waitForWords() = waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(hasText("Stars", substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** The catalogue has Moon by Luna, and whatever else ChatGPT names isn't found; otherwise as [before]. */
    private fun withCatalogue(before: (okhttp3.Request) -> okhttp3.Response) {
        fake.handler = { request ->
            val q = request.url.queryParameter("q").orEmpty()
            when {
                request.url.host == "api.deezer.com" && "Moon" in q -> FakeSpotify.html(request, """{"data":[{"id":1,"title":"Moon","link":"https://www.deezer.com/track/1","artist":{"name":"Luna"}}]}""")
                request.url.host == "api.deezer.com" && "Rain" in q -> FakeSpotify.html(request, """{"data":[{"id":2,"title":"Rain","link":"https://www.deezer.com/track/2","artist":{"name":"Cloud"}}]}""")
                request.url.host == "api.deezer.com" && "Sun" in q -> FakeSpotify.html(request, """{"data":[{"id":3,"title":"Sun","link":"https://www.deezer.com/track/3","artist":{"name":"Day"}}]}""")
                request.url.host == "api.deezer.com" || request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"data":[],"results":[]}""")
                else -> before(request)
            }
        }
    }

    @Test
    fun theWordsToKnowAreSavedToPractiseAndAskedAgainAfterFailing() {
        val openAi = signedInWithChatGpt()
        val answers = openAi.reply
        openAi.reply = { body -> if ("6 to 10 words" in body.getString("instructions")) 500 to "" else answers(body) }
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        waitForWords()
        aboutSong(R.string.song_tab_words)
        waitUntil(TIMEOUT_MS) { shown(string(R.string.lyrics_words_to_know_failed)) }
        openAi.reply = answers
        click(string(R.string.retry_button))
        waitForText("cielo")
        assertTrue(shown("estrellas"))
        // Saved, it's practised like a line, with its meaning.
        click(string(R.string.lyrics_save_word, "sky"))
        assertEquals(SavedLine("sky", translation = "cielo", title = "Demo", artist = "Band", line = "Night sky"), SavedLinesStore(prefs()).load().single())
        assertTrue(composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_word_saved, "sky"))).fetchSemanticsNodes().isNotEmpty())
        // Tapped again, it's let go.
        click(string(R.string.lyrics_word_saved, "sky"))
        assertTrue(SavedLinesStore(prefs()).load().isEmpty())
        // Closed and opened again, they're there, not asked again.
        val asked = openAi.asked.size
        composeRule.onNode(hasText("cielo")).performTouchInput { swipeDown() }
        waitUntil(TIMEOUT_MS) { !shown("cielo") }
        aboutSong(R.string.song_tab_words)
        waitForText("cielo")
        assertEquals(asked, openAi.asked.size)
        // Choosing the song's lines, those with words worth knowing say so.
        composeRule.onNode(hasText("cielo")).performTouchInput { swipeDown() }
        waitUntil(TIMEOUT_MS) { !shown("cielo") }
        studyMenu()
        click(string(R.string.practice_song))
        assertTrue(described(string(R.string.learn_song_new_words)))
    }

    @Test
    fun theArtistIsToldInPartsAndAskedAgainAfterFailing() {
        val openAi = signedInWithChatGpt()
        val answers = openAi.reply
        openAi.reply = { body -> if ("get to know the artists" in body.getString("instructions")) 500 to "" else answers(body) }
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        waitForWords()
        aboutSong(R.string.song_tab_artist)
        waitUntil(TIMEOUT_MS) { shown(string(R.string.lyrics_about_artist_failed)) }
        openAi.reply = answers
        click(string(R.string.retry_button))
        waitForText("A band from Rome.")
        assertTrue(shown("Night pop.") && shown("Demo (2020).") && shown(string(R.string.artist_who)) && shown(string(R.string.artist_style)) && shown(string(R.string.artist_known_for)))
        // Asked about again, it's there already.
        val asked = openAi.asked.size
        composeRule.onNode(hasText("A band from Rome.")).performTouchInput { swipeDown() }
        waitUntil(TIMEOUT_MS) { !shown("A band from Rome.") }
        aboutSong(R.string.song_tab_artist)
        waitForText("A band from Rome.")
        assertEquals(asked, openAi.asked.size)
    }

    @Test
    fun whatTheSongMeansSaysWhatItCoversAndAnswersQuestions() {
        val openAi = signedInWithChatGpt()
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        waitForWords()
        aboutSong()
        waitForText("A night under the stars.")
        // The song it covers and the one it samples.
        waitUntil(TIMEOUT_MS) { shown("Old Band") }
        assertTrue(shown(string(R.string.song_original)) && shown(string(R.string.song_samples)) && shown("Drummer"))
        // Explained, more can be asked about it.
        composeRule.onNode(hasText(string(R.string.lyrics_ask_song))).performScrollTo().performTextInput("Why stars?")
        click(string(R.string.lyrics_ask_send))
        waitForText("It's about stars.")
        val asked = openAi.asked.last().getJSONArray("input").getJSONObject(0).getString("content")
        assertTrue(asked, asked.contains("What you said about it:\nFrom 1985.") && asked.endsWith("Question: Why stars?"))
        // The next goes with it; failing says so.
        openAi.reply = { 500 to "" }
        composeRule.onNode(hasText(string(R.string.lyrics_ask_song))).performScrollTo().performTextInput("And night?")
        click(string(R.string.lyrics_ask_send))
        waitUntil(TIMEOUT_MS) { shown(string(R.string.lyrics_ask_failed)) }
        // The song it covers opens like a song looked up, leaving the words.
        composeRule.onNode(hasText("First Demo")).performScrollTo().performClick()
        waitUntil(TIMEOUT_MS) { !shown(string(R.string.song_original)) && shown("First Demo") && shown("Old Band") }
    }

    @Test
    fun whatChatGptSaysAboutASongArrivingAfterTheNextSongTookOverIsntShownForIt() {
        val openAi = signedInWithChatGpt()
        val answers = openAi.reply
        val late = java.util.concurrent.CountDownLatch(1)
        val answeredLate = java.util.concurrent.atomic.AtomicInteger()
        // What it says about Demo comes only once the next song plays; about any other, there's nothing to say.
        openAi.reply = { body ->
            val instructions = body.getString("instructions")
            val about = "covers and samples" in instructions || "explain the song in four parts" in instructions
            val demo = "Demo" in body.getJSONArray("input").getJSONObject(0).getString("content")
            when {
                about && demo -> { late.await(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS); answers(body).also { answeredLate.incrementAndGet() } }
                about -> 500 to ""
                else -> answers(body)
            }
        }
        val hearing = FakeHearing()
        listeningAlong(hearing)
        val words = fake.handler
        fake.handler = { request -> openAi.answer(request) ?: words(request) }
        allowMicrophone()
        launch()
        hearing.song("Demo", 2.0)
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        waitUntil(TIMEOUT_MS) { lit("Demo one") }
        aboutSong()
        waitUntil(TIMEOUT_MS) { openAi.asked.count { "Demo" in it.getJSONArray("input").getJSONObject(0).getString("content") && ("covers and samples" in it.getString("instructions") || "explain the song in four parts" in it.getString("instructions")) } == 2 }
        hearing.song("Other", 1.0)
        waitUntil(TIMEOUT_MS) { lit("Other one") }
        late.countDown()
        waitUntil(TIMEOUT_MS) { answeredLate.get() == 2 }
        Thread.sleep(300)
        composeRule.waitForIdle()
        // Other's words don't say Demo covers First Demo, or what Demo is about.
        val learning = controllerModel().uiState.learning
        assertNull(learning.songFacts)
        assertFalse(learning.songExplanation?.text.orEmpty().contains("under the stars"))
        assertFalse(shown("Old Band") || shown("A night under the stars."))
    }



    @Test
    fun aWordCanBeSeenInExamplesAndSaysSoWhenItCant() {
        prefs().edit().putBoolean("lyrics_translation", true).commit()
        val openAi = signedInWithChatGpt()
        val answers = openAi.reply
        openAi.reply = { body -> if ("example sentences" in body.getString("instructions")) 500 to "" else answers(body) }
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        waitUntil(TIMEOUT_MS) { shown("«Stars»") }
        composeRule.onAllNodesWithTag(LYRIC_LINE_TAG)[0].performClick()
        // A whole line has no examples: only a word in it does, once it's tapped and its meaning found.
        waitUntil(TIMEOUT_MS) { shown(string(R.string.lyrics_save_line)) }
        assertFalse(shown(string(R.string.lyrics_examples)))
        val line = hasText("Night sky") and androidx.compose.ui.test.hasTestTag(STUDY_LINE_TAG)
        waitUntil(TIMEOUT_MS) { runCatching { composeRule.onNode(line).performFirstLinkClick() }.isSuccess }
        waitUntil(TIMEOUT_MS) { shown("the heavens") }
        click(string(R.string.lyrics_examples))
        waitUntil(TIMEOUT_MS) { shown(string(R.string.lyrics_examples_failed)) }
        openAi.reply = answers
        click(string(R.string.lyrics_examples))
        waitForText("The sky is blue.")
        assertTrue(shown("El cielo es azul."))
        val sent = org.json.JSONObject(openAi.asked.last { "example sentences" in it.getString("instructions") }.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals("Night", sent.getString("word"))
        assertEquals("Night sky", sent.getString("line"))
        val examplesButton = hasText(string(R.string.lyrics_examples)) and androidx.compose.ui.test.hasClickAction()
        assertTrue(composeRule.onAllNodes(examplesButton).fetchSemanticsNodes().isEmpty())
        // Tapped again, the word starts without them.
        composeRule.onNode(line).performFirstLinkClick()
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(examplesButton).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(shown("The sky is blue."))
    }

    @Test
    fun songsLikeOneAreAPlaylistOfThoseInTheCatalogue() {
        val openAi = signedInWithChatGpt()
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        waitForWords()
        withCatalogue(fake.handler)
        aboutSong()
        composeRule.onNode(hasText(string(R.string.lyrics_songs_like))).performScrollTo().performClick()
        // The words close for the songs, the one the catalogue doesn't have left out.
        waitUntil(TIMEOUT_MS) { shown(string(R.string.songs_like_label)) && shown("Moon") }
        assertTrue(shown("Luna") && shown("Demo"))
        assertFalse(shown("Made Up"))
        // A link copied meanwhile doesn't replace them.
        val clip = ClipData.newPlainText("copied", "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC")
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        ClipDescription::class.java.getDeclaredMethod("setTimestamp", Long::class.javaPrimitiveType).invoke(clip.description, System.currentTimeMillis() - 1_000)
        controller!!.windowFocusChanged(false)
        controller!!.windowFocusChanged(true)
        composeRule.waitForIdle()
        assertTrue(shown(string(R.string.songs_like_label)) && shown("Moon"))
    }

    @Test
    fun songsLikeOneThatArentFoundSaySo() {
        val openAi = signedInWithChatGpt()
        val answers = openAi.reply
        openAi.reply = { body -> if ("You recommend songs" in body.getString("instructions")) 200 to FakeOpenAi.stream("[]") else answers(body) }
        wordsOf(null, "Night sky\\nStars", openAi = openAi)
        waitForWords()
        withCatalogue(fake.handler)
        aboutSong()
        composeRule.onNode(hasText(string(R.string.lyrics_songs_like))).performScrollTo().performClick()
        waitUntil(TIMEOUT_MS) { shown(string(R.string.error_no_songs_like)) }
        assertFalse(shown(string(R.string.songs_like_label)))
    }

    @Test
    fun aMoodSearchedMakesAPlaylistOfChatGptsPicks() {
        val openAi = signedInWithChatGpt()
        openAi.reply = { 200 to FakeOpenAi.stream("""[{"title": "Moon", "artist": "Luna"}, {"title": "Rain", "artist": "Cloud"}, {"title": "Sun", "artist": "Day"}]""") }
        withCatalogue(fake.handler)
        launch()
        composeRule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("calm rainy night songs")
        click(string(R.string.search_button))
        waitUntil(TIMEOUT_MS) { shown(string(R.string.song_search_playlist)) }
        click(string(R.string.song_search_playlist))
        // A list named after the mood, with ChatGPT's songs, to play or share like a playlist.
        waitUntil(TIMEOUT_MS) { shown("calm rainy night songs") && shown("Rain") && shown("Sun") }
        assertFalse(shown(string(R.string.songs_like_label)))
    }

    @Test
    fun japaneseFoundInRomajiIsWrittenInJapaneseSignedInKeepingItsTiming() {
        val openAi = signedInWithChatGpt()
        wordsOf(ROMAJI, "yoru ni kimi wa", openAi = openAi)
        // In Japanese, the line being sung still lit, and the menu says ChatGPT wrote it.
        waitUntil(TIMEOUT_MS) { shown("夜に君は") && shown("明日の歌") }
        composeRule.onNode(hasText("星が光る") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertExists()
        assertFalse(shown("yoru ni kimi wa"))
        learnMenu()
        assertTrue(described(string(R.string.lyrics_from_romaji)))
        // Read as Japanese now, it can show in kana, or its own romaji.
        click(string(R.string.lyrics_only_kana))
        waitUntil(TIMEOUT_MS) { shown("よるにきみは") }
        click(string(R.string.lyrics_as_written))
        waitUntil(TIMEOUT_MS) { shown("夜に君は") }
    }

    @Test
    fun japaneseFoundInRomajiStaysSoSignedOut() {
        wordsOf(ROMAJI, "yoru ni kimi wa")
        waitUntil(TIMEOUT_MS) { shown("yoru ni kimi wa") && shown("ashita no uta") }
    }

    @Test
    fun japaneseFoundInRomajiStaysSoWhenChatGptCantWriteIt() {
        val openAi = signedInWithChatGpt()
        val answers = openAi.reply
        openAi.reply = { body -> if ("restore song lyrics written in romaji" in body.getString("instructions")) 500 to "" else answers(body) }
        wordsOf(ROMAJI, "yoru ni kimi wa", openAi = openAi)
        waitUntil(TIMEOUT_MS * 3) { openAi.asked.any { "restore song lyrics written in romaji" in it.getString("instructions") } }
        composeRule.waitForIdle()
        assertTrue(shown("yoru ni kimi wa") && shown("ashita no uta"))
        assertFalse(shown("夜に君は"))
    }
}

private const val GOOGLE_TRANSLATE_HOST = "clients5.google.com"
