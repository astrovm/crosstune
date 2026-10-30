package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl

/** Played on a TV, the song's app is covered by Crosstune rather than left: brought in front of it, YouTube stops. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class TvPlayTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null
    private var phone = Phone.TV

    // YouTube on a TV has no song to be asked for in the background, so it's opened on it.
    private val playback = FakePlayback().apply {
        access = true
        running = emptySet()
    }
    private val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
    private val link = "https://music.youtube.com/watch?v=sPmul8b17AU"
    private val lines = "[00:00.00] Demo one\n[00:10.00] Demo two\n[00:40.00] Demo three"

    /** Put back after, so other tests still tell the phone as the app does. */
    private val realPhone = MainActivity.phoneFactory

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.phoneFactory = { phone }
        MainActivity.checksForUpdates = { false }
        MainActivity.shellFactory = { emptyList() }
        MainActivity.playbackFactory = { playback }
        prefs().edit().clear().putString("default_target", "YOUTUBE_MUSIC").commit()
        // A TV has YouTube's TV app, which plays YouTube Music's songs too.
        shadowOf(app.packageManager).installPackage(installedApp(MusicService.YOUTUBE_TV, "YouTube"))
        ShadowSettings.setCanDrawOverlays(true)
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                if (request.url.host == "lrclib.net") """[{"trackName":"Demo","artistName":"Band","duration":60,"syncedLyrics":"$lines"}]"""
                else """{"title":"Demo","author_name":"Band"}"""
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.phoneFactory = realPhone
        MainActivity.checksForUpdates = ::looksForUpdates
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.playbackFactory = ::MediaSessionPlayback
        FloatingLyricsService.host = null
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private val play get() = string(R.string.play_in_background, "YouTube Music")

    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text) or hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    private fun onPlayer() = shown(string(R.string.lyrics_play)) || shown(string(R.string.lyrics_pause))

    private fun click(text: String) {
        // Pressed as the remote does, which reaches what's below the TV's edge too, scrolling to it.
        composeRule.onAllNodes(hasText(text) or hasContentDescription(text))[0].performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    /** The song's card, shown as a link shared to Crosstune is. */
    private fun launch() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun playing() {
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube", canSeek = true, appPackage = MusicService.YOUTUBE_TV)
    }

    /** Play pressed: YouTube opens on the song, Crosstune goes behind it, and the cover Crosstune started goes over it. */
    private fun playCovered(): ServiceController<FloatingLyricsService> {
        click(play)
        waitUntil { shadowOf(app).peekNextStartedService() != null }
        val started = shadowOf(app).nextStartedService
        assertEquals(FloatingLyricsService::class.java.name, started.component!!.className)
        assertTrue(started.getBooleanExtra(FloatingLyricsService.EXTRA_COVER, false))
        controller!!.pause().stop()
        return Robolectric.buildService(FloatingLyricsService::class.java, started).create().startCommand(0, 1).also { composeRule.waitForIdle() }
    }

    private fun overlays(service: FloatingLyricsService): List<View> =
        Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java)).views
            .filter { (it.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }

    private fun params(service: FloatingLyricsService) = overlays(service).single().layoutParams as WindowManager.LayoutParams

    private fun pressBack(service: FloatingLyricsService) {
        val view = overlays(service).single()
        assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertTrue(view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        composeRule.waitForIdle()
    }

    /** Back pressed until Crosstune is left, through the words, the player and the main screen, as it closes the cover. */
    private fun backOut(service: ServiceController<FloatingLyricsService>) {
        repeat(4) { if (!shadowOf(service.get()).isStoppedBySelf) pressBack(service.get()) }
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
    }

    @Test
    fun onATvPlayCoversYouTubeWithTheSongsWordsRatherThanComeInFrontOfIt() {
        launch()
        val floating = playCovered()
        assertEquals(youTubeMusic to link, playback.opened.single())
        // The whole screen, taking the remote's keys.
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params(floating.get()).width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params(floating.get()).height)
        assertEquals(0, params(floating.get()).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // Nothing of YouTube's video shows through it, as it would through the visuals on a TV.
        assertEquals(PixelFormat.OPAQUE, params(floating.get()).format)

        // Once it plays there, its words show over it, and Crosstune stays behind.
        playing()
        waitUntil { shown("Demo one") }
        assertEquals(0, playback.cameBack)

        // Back goes back in Crosstune, from the words to the player and on to the main screen, still over YouTube.
        pressBack(floating.get())
        waitUntil { composeRule.onAllNodes(hasTestTag(PLAYER_COVER_TAG)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onPlayer())
        assertFalse(shadowOf(floating.get()).isStoppedBySelf)
        pressBack(floating.get())
        waitUntil { !onPlayer() }
        assertFalse(shadowOf(floating.get()).isStoppedBySelf)
        // From there, Back leaves Crosstune, to YouTube, which plays on.
        pressBack(floating.get())
        waitUntil { shadowOf(floating.get()).isStoppedBySelf }
        controller!!.start().resume()
        composeRule.waitForIdle()
        assertFalse(shown("Demo one"))
    }

    @Test
    fun backBeforeTheWordsOpenGoesBackInCrosstuneToo() {
        launch()
        val floating = playCovered()
        // Not playing yet, Back goes back from the player to the main screen, and from there to YouTube.
        pressBack(floating.get())
        waitUntil { !onPlayer() }
        assertFalse(shadowOf(floating.get()).isStoppedBySelf)
        pressBack(floating.get())
        waitUntil { shadowOf(floating.get()).isStoppedBySelf }
    }

    @Test
    fun onATvWithoutShowingOverOtherAppsPlayShowsHowFirst() {
        ShadowSettings.setCanDrawOverlays(false)
        launch()
        click(play)
        assertTrue(shown(string(R.string.floating_permission_title)))
        assertTrue(playback.opened.isEmpty())
    }

    @Test
    fun anAppThatCantBeOpenedIsntCovered() {
        playback.opens = false
        launch()
        click(play)
        // Opened as Open does, with nothing over it.
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals(link, shadowOf(app).nextStartedActivity.dataString)
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun anAppThatCantBeOpenedLeavesNothingCoveringSoPlayCoversOnceItCan() {
        playback.opens = false
        launch()
        click(play)
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        composeRule.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).peekNextStartedService())

        // Nothing is left thinking it's covering, which would let the next cover go unnoticed.
        playback.opens = true
        waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        playCovered()
        assertEquals(youTubeMusic to link, playback.opened.single())
    }

    @Test
    fun playThatCantOpenYouTubeAgainCoversNothing() {
        launch()
        val floating = playCovered()
        playing()
        waitUntil { shown("Demo one") }
        backOut(floating)
        playback.playing.value = null
        controller!!.start().resume()
        composeRule.waitForIdle()
        // Nothing opened, so there's nothing to cover, and nothing in the app goes on thinking there is.
        playback.opens = false
        waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        click(play)
        composeRule.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun playAgainAfterTheCoverWasClosedCoversYouTubeAgain() {
        launch()
        val floating = playCovered()
        playing()
        waitUntil { shown("Demo one") }
        backOut(floating)
        // Back in Crosstune, YouTube lets go of the song, and Play opens it on the song again, covered again.
        playback.playing.value = null
        controller!!.start().resume()
        composeRule.waitForIdle()
        waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        playCovered()
        assertEquals(2, playback.opened.size)
    }

    @Test
    fun playAgainAfterAnAppWasOpenedFromTheCoverCoversYouTubeAgain() {
        launch()
        playCovered()
        playing()
        waitUntil { shown("Demo one") }
        controller!!.get().startActivity(Intent(Settings.ACTION_SETTINGS))
        assertEquals(FloatingLyricsService::class.java.name, shadowOf(app).nextStoppedService.component!!.className)
        // Robolectric doesn't stop the activity as Android would, so only the cover being closed lets it cover again.
        playback.playing.value = null
        composeRule.waitForIdle()
        click(string(R.string.lyrics_play))
        waitUntil { shadowOf(app).peekNextStartedService() != null }
        assertTrue(shadowOf(app).nextStartedService.getBooleanExtra(FloatingLyricsService.EXTRA_COVER, false))
        assertEquals(2, playback.opened.size)
    }

    @Test
    fun theCoverBeingClosedBeforeYouTubePlaysBringsCrosstuneBackLikeOnAPhone() {
        launch()
        val floating = playCovered()
        backOut(floating)
        assertEquals(0, playback.cameBack)
        // Nothing is over YouTube to show the words, so Crosstune comes in front of it.
        playing()
        waitUntil { playback.cameBack == 1 }
    }

    @Test
    fun withTheWordsAlreadyOpenTheCoverShowsThemAndCrosstuneStaysBehind() {
        launch()
        click(string(R.string.lyrics_button))
        waitUntil { shown("Demo one") }
        playCovered()
        playing()
        waitUntil { shown("Demo one") }
        // Given time to have come back, which it doesn't.
        composeRule.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, playback.cameBack)
    }

    @Test
    fun onATvWithoutShowingOverOtherAppsOneTapAllowsItAndPlayGoesOn() {
        ShadowSettings.setCanDrawOverlays(false)
        val shell = FakeShell { command ->
            if ("SYSTEM_ALERT_WINDOW" in command) ShadowSettings.setCanDrawOverlays(true)
            true
        }
        MainActivity.shellFactory = { listOf(shell) }
        launch()
        click(play)
        waitUntil { playback.opened.isNotEmpty() }
        assertEquals(youTubeMusic to link, playback.opened.single())
        assertTrue(shell.ran.any { "SYSTEM_ALERT_WINDOW" in it })
        // Allowed once, not over and over.
        assertEquals(1, shell.ran.count { "SYSTEM_ALERT_WINDOW" in it })
        waitUntil { shadowOf(app).peekNextStartedService() != null }
        assertTrue(shadowOf(app).nextStartedService.getBooleanExtra(FloatingLyricsService.EXTRA_COVER, false))
    }

    @Test
    fun floatedFromTheCoverTheWordsFloatOverYouTube() {
        launch()
        val floating = playCovered()
        playing()
        waitUntil { shown("Demo one") }
        floating.get().onStartCommand(Intent(app, FloatingLyricsService::class.java), 0, 2)
        composeRule.waitForIdle()
        // A window of their own, which lets the remote's keys go on to YouTube.
        assertTrue(params(floating.get()).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        // The cover gone, closing the words closes nothing else.
        assertFalse(shadowOf(floating.get()).isStoppedBySelf)
    }

    @Test
    fun anAppOpenedFromTheCoverShowsWithNothingOverIt() {
        launch()
        playCovered()
        controller!!.get().startActivity(Intent(Settings.ACTION_SETTINGS))
        assertEquals(FloatingLyricsService::class.java.name, shadowOf(app).nextStoppedService.component!!.className)
        assertNull(FloatingLyricsService.host)
    }

    @Test
    fun turnedTheCoverStillTakesTheWholeScreen() {
        launch()
        val floating = playCovered()
        floating.get().onConfigurationChanged(Configuration(app.resources.configuration))
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params(floating.get()).width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params(floating.get()).height)
    }

    @Test
    fun withShowingOverOtherAppsTakenBackThereIsNoCover() {
        launch()
        click(play)
        waitUntil { shadowOf(app).peekNextStartedService() != null }
        ShadowSettings.setCanDrawOverlays(false)
        val floating = Robolectric.buildService(FloatingLyricsService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, 1)
        assertTrue(overlays(floating.get()).isEmpty())
        assertTrue(shadowOf(floating.get()).isStoppedBySelf)
    }

    @Test
    fun onATvThePlayersPlayOpensYouTubeAgainOnceItLetGoOfTheSong() {
        launch()
        val floating = playCovered()
        playing()
        waitUntil { shown("Demo one") }
        pressBack(floating.get())
        // On the player, YouTube stopped, and lets go of the song.
        playback.playing.value = null
        controller!!.start().resume()
        composeRule.waitForIdle()
        click(string(R.string.lyrics_play))
        waitUntil { playback.opened.size == 2 }
        assertEquals(youTubeMusic to link, playback.opened.last())
    }

    @Test
    fun onAPhoneThePlayersPlayDoesntOpenTheAppItCantAsk() {
        phone = Phone.OTHER
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        launch()
        click(play)
        waitUntil { playback.opened.size == 1 }
        // The phone's own app plays it, as it's installed.
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true, appPackage = youTubeMusic)
        waitUntil { playback.cameBack == 1 }
        playback.playing.value = null
        composeRule.waitForIdle()
        click(string(R.string.lyrics_play))
        // A phone's app keeps its song in front of Crosstune too; there's nothing to open it on again for.
        assertEquals(1, playback.opened.size)
    }

    @Test
    fun youTubesTvAppPlaysYouTubeMusicsSongs() {
        assertTrue(MusicService.YOUTUBE_MUSIC in LinkInterception(app).installedServices())
        // Its phone app isn't on a TV, so YouTube's TV app stands in for it.
        assertTrue(sameApp(youTubeMusic, MusicService.YOUTUBE_TV, app::hasApp))
        assertFalse(MusicService.YOUTUBE_TV in MusicService.SPOTIFY.appPackages)
    }

    @Test
    fun onATvTheRemoteStartsOnTheWordsBarAndRightFromALineGoesBackThere() {
        launch()
        click(string(R.string.lyrics_button))
        waitUntil { shown("Demo one") }
        val learn = composeRule.onNode(hasContentDescription(string(R.string.lyrics_learn)))
        waitUntil { runCatching { learn.assertIsFocused() }.isSuccess }
        val line = composeRule.onAllNodes(hasText("Demo two"))[0]
        line.requestFocus()
        learn.assertIsNotFocused()
        line.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        learn.assertIsFocused()
    }

    @Test
    fun onAPhoneTheWordsBarIsLeftAlone() {
        phone = Phone.OTHER
        launch()
        click(string(R.string.lyrics_button))
        waitUntil { shown("Demo one") }
        val learn = composeRule.onNode(hasContentDescription(string(R.string.lyrics_learn)))
        learn.assertIsNotFocused()
        val line = composeRule.onAllNodes(hasText("Demo two"))[0]
        line.requestFocus()
        line.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        learn.assertIsNotFocused()
    }

    @Test
    fun playIsTheLastOnTheWordsBar() {
        launch()
        click(string(R.string.lyrics_button))
        waitUntil { shown("Demo one") }
        val playButton = composeRule.onNode(hasContentDescription(play)).fetchSemanticsNode().boundsInRoot
        val microphone = composeRule.onNode(hasContentDescription(string(R.string.lyrics_listen_along))).fetchSemanticsNode().boundsInRoot
        assertTrue(playButton.left > microphone.right)
    }

    /** Whether the remote is on something in a dialog or a sheet. */
    private fun inDialog() = composeRule.onAllNodes(hasAnyAncestor(isDialog())).fetchSemanticsNodes().any { it.config.getOrElseNullable(SemanticsProperties.Focused) { null } == true }

    @Test
    fun onATvASheetOverTheCoverIsDrawnInItRatherThanInAWindowItCantOpen() {
        launch()
        val floating = playCovered()
        playing()
        waitUntil { shown("Demo one") }
        // The cover is a window of a service's, which no other window opens from: nothing opens beside it.
        val windows = { Shadow.extract<ShadowWindowManagerImpl>(floating.get().getSystemService(WindowManager::class.java)).views.size }
        val before = windows()

        val info = composeRule.onNode(hasContentDescription(string(R.string.lyrics_about_song)))
        // The remote's, as a TV is: Robolectric starts as a touch screen, where rows take no focus.
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        info.requestFocus()
        info.performSemanticsAction(SemanticsActions.OnClick)
        val report = hasContentDescription(string(R.string.report_problem)) and hasAnyAncestor(isDialog())
        waitUntil { composeRule.onAllNodes(report).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, windows())
        // The remote starts in it, and can't leave it for the words under it.
        waitUntil { inDialog() }
        composeRule.onNode(hasText("Demo two")).assertIsNotFocused()

        // One over another: the report, over the sheet.
        composeRule.onNode(report).requestFocus()
        composeRule.onNode(report).performSemanticsAction(SemanticsActions.OnClick)
        waitUntil { shown(string(R.string.report_send)) }
        assertEquals(before, windows())
        // Where the remote starts is Send, rather than the comment, which opens the keyboard.
        waitUntil { composeRule.onNode(hasText(string(R.string.report_send)) and hasAnyAncestor(isDialog())).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Focused) { null } == true }

        // Back puts away the one on top, and the remote goes back to what opened it, one then the other.
        pressBack(floating.get())
        waitUntil { !shown(string(R.string.report_send)) }
        assertTrue(shown(string(R.string.lyrics_songs_like)) || composeRule.onAllNodes(report).fetchSemanticsNodes().isNotEmpty())
        composeRule.onNode(report).assertIsFocused()
        pressBack(floating.get())
        waitUntil { composeRule.onAllNodes(report).fetchSemanticsNodes().isEmpty() }
        assertTrue(shown("Demo one"))
        info.assertIsFocused()
        assertFalse(shadowOf(floating.get()).isStoppedBySelf)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
