package com.astrovm.crosstune

import android.app.AppOpsManager
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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private val morpheMusic = "app.morphe.android.apps.youtube.music"
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
        controller?.destroyAndSettle()
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.phoneFactory = realPhone
        MainActivity.checksForUpdates = ::looksForUpdates
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.hearingFactory = null
        FloatingLyricsService.host = null
        FloatingLyricsService.covering = false
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun model() = ViewModelProvider(checkNotNull(controller).get())[MainViewModel::class.java]

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private val play get() = string(R.string.play_in_background, "YouTube Music")

    // Told of changes outside the screen, as a phone tells it each frame, so what watches them, like the cover, sees them.
    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(TIMEOUT_MS) { Snapshot.sendApplyNotifications(); shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text) or hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    // The now-playing bar has a pause button too, so the player is told by its cover.
    private fun onPlayer() = composeRule.onAllNodes(hasTestTag(PLAYER_COVER_TAG)).fetchSemanticsNodes().isNotEmpty()

    private fun click(text: String) {
        // Pressed as the remote does, which reaches what's below the TV's edge too, scrolling to it.
        composeRule.onAllNodes(hasText(text) or hasContentDescription(text))[0].performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    /** The song's card, shown as a link shared to Crosstune is. */
    private fun launch(action: String = play) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        waitUntil { composeRule.onAllNodes((hasContentDescription(action) or hasText(action)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun playing() {
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube", canSeek = true, appPackage = MusicService.YOUTUBE_TV)
    }

    private class Listening : SongHearing {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()

        override suspend fun listen(): Heard {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
    }

    /** What watched what plays once Crosstune was behind YouTube, before its cover came up. */
    private var watchersBehindTheCover = 0

    /** Play pressed: YouTube opens on the song, Crosstune goes behind it, and the cover Crosstune started goes over it. */
    private fun playCovered(canDraw: Boolean = true): ServiceController<FloatingLyricsService> {
        click(play)
        waitUntil { shadowOf(app).peekNextStartedService() != null }
        val started = shadowOf(app).nextStartedService
        assertEquals(FloatingLyricsService::class.java.name, checkNotNull(started.component) { "Expected started.component to be non-null" }.className)
        assertTrue(started.getBooleanExtra(FloatingLyricsService.EXTRA_COVER, false))
        checkNotNull(controller) { "Expected controller to be non-null" }.pause().stop()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        watchersBehindTheCover = playback.nowPlayingWatchers
        ShadowSettings.setCanDrawOverlays(canDraw)
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
        checkNotNull(controller) { "Expected controller to be non-null" }.start().resume()
        composeRule.waitForIdle()
        assertFalse(shown("Demo one"))
    }

    @Test
    fun onATvBackFromTheWordsLandsOnPauseAndFromThePlayerOnPlay() {
        launch()
        val floating = playCovered()
        // The remote's, as a TV is: Robolectric starts as a touch screen, where nothing takes focus.
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        playing()
        waitUntil { shown("Demo one") }
        // Back from the words, the remote is on the player's main button, rather than nowhere.
        pressBack(floating.get())
        waitUntil { onPlayer() }
        composeRule.waitForIdle()
        composeRule.onNode(hasContentDescription(string(R.string.lyrics_pause)) and hasClickAction()).assertIsFocused()
        // Back from the player, it's on the card's Play, the way the user came.
        pressBack(floating.get())
        waitUntil { !onPlayer() }
        composeRule.waitForIdle()
        composeRule.onNode(hasContentDescription(play) and hasClickAction()).assertIsFocused()
    }

    @Test
    fun onATvBackFromTheStepsPlayShowsFirstLandsOnPlay() {
        ShadowSettings.setCanDrawOverlays(false)
        launch()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        val playOnCard = composeRule.onNode(hasContentDescription(play) and hasClickAction())
        playOnCard.requestFocus()
        playOnCard.performKeyInput { pressKey(Key.DirectionCenter) }
        waitUntil { composeRule.onAllNodes(hasContentDescription(play)).fetchSemanticsNodes().isEmpty() }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        waitUntil { composeRule.onAllNodes(hasContentDescription(play)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        playOnCard.assertIsFocused()
    }

    @Test
    fun onATvBackLandsOnPlayWhenTheRemoteWasLastOnAButtonThatOpensNoScreen() {
        launch()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        val playOnCard = composeRule.onNode(hasContentDescription(play) and hasClickAction())
        // Past settings, then on the link field, which opens no screen: settings is no longer where it was.
        composeRule.onNode(hasContentDescription(string(R.string.settings_button))).requestFocus()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).requestFocus()
        composeRule.runOnIdle { model().openFixes() }
        waitUntil { !shown(play) }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        waitUntil { shown(play) }
        composeRule.waitForIdle()
        playOnCard.assertIsFocused()
    }

    @Test
    fun onATvBackLeavesTheRemoteInADialogThatShowsAgain() {
        launch()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        val settings = composeRule.onNode(hasContentDescription(string(R.string.settings_button)))
        settings.requestFocus()
        settings.performKeyInput { pressKey(Key.DirectionCenter) }
        waitUntil { !shown(play) }
        // Songs looked up by name while away: their sheet shows on the main screen come back to, and keeps the remote.
        composeRule.runOnIdle { model().searchSongs("moon") }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        waitUntil { shown(string(R.string.song_search_title)) }
        composeRule.waitForIdle()
        assertTrue(inDialog())
        settings.assertIsNotFocused()
    }

    @Test
    fun onATvTheWordsClosedUnderTheirSheetGiveTheRemoteBackToTheButtonThatOpenedThem() {
        launch()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        val words = composeRule.onNode(hasText(string(R.string.lyrics_button)) and hasClickAction())
        words.requestFocus()
        words.performKeyInput { pressKey(Key.DirectionCenter) }
        waitUntil { shown("Demo two") }
        // Their sheet about the song is up as something else closes them, as a link that comes in does.
        click(string(R.string.lyrics_about_song))
        waitUntil { inDialog() }
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { !shown("Demo two") }
        composeRule.waitForIdle()
        words.assertIsFocused()
    }

    @Test
    fun onATvADialogUpOnTheScreenComeBackGivesTheRemoteBackWhereItWasOnceItsPutAway() {
        launch()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        val words = composeRule.onNode(hasText(string(R.string.lyrics_button)) and hasClickAction())
        words.requestFocus()
        words.performKeyInput { pressKey(Key.DirectionCenter) }
        waitUntil { shown("Demo two") }
        // The apps to open it in, asked for meanwhile: they show over the card come back to, and keep the remote.
        composeRule.runOnIdle { model().playerOpenWith() }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        waitUntil { !shown("Demo two") && shown(string(R.string.picker_title)) }
        composeRule.waitForIdle()
        assertTrue(inDialog())
        // Put away, with nothing of its own to give the remote to, it goes back to what opened the words.
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        waitUntil { !shown(string(R.string.picker_title)) }
        composeRule.waitForIdle()
        words.assertIsFocused()
        // Given back once, it's done: moved on, the next dialog put away doesn't take the remote back to the words.
        val settings = composeRule.onNode(hasContentDescription(string(R.string.settings_button)))
        settings.requestFocus()
        composeRule.runOnIdle { model().playerOpenWith() }
        waitUntil { shown(string(R.string.picker_title)) }
        composeRule.waitForIdle()
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        waitUntil { !shown(string(R.string.picker_title)) }
        composeRule.waitForIdle()
        words.assertIsNotFocused()
    }

    @Test
    fun onAPhoneBackFromTheWordsPutsNothingBackInFocus() {
        phone = Phone.PIXEL
        launch(action = string(R.string.lyrics_button))
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        val words = composeRule.onNode(hasText(string(R.string.lyrics_button)) and hasClickAction())
        words.requestFocus()
        words.performKeyInput { pressKey(Key.DirectionCenter) }
        waitUntil { shown("Demo two") }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        waitUntil { !shown("Demo two") }
        composeRule.waitForIdle()
        // A phone is touched: what it was on is no place to be put back.
        words.assertIsNotFocused()
    }

    @Test
    fun onATvBackFromPracticeOverTheWordsLeavesTheRemoteOnThem() {
        prefs().edit().putString("saved_lines", """[{"text":"Demo one","translation":"Demo uno","title":"Demo","artist":"Band","interval":3,"due":1}]""").commit()
        launch()
        val floating = playCovered()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        playing()
        waitUntil { shown("Demo two") }
        click(string(R.string.lyrics_study))
        click(string(R.string.practice_song))
        waitUntil { !shown("Demo two") }
        pressBack(floating.get())
        waitUntil { shown("Demo two") }
        composeRule.waitForIdle()
        // Practice is drawn in the words' place, the same screen, so nothing closes: the remote goes back to Study, which
        // opened it, not the bar's first button (Issue #1137).
        assertEquals(1, composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().size)
        composeRule.onNode(hasContentDescription(string(R.string.lyrics_study))).assertIsFocused()
    }

    @Test
    fun chooseAppListsYouTubesTvAppOnceAsTheOnlyYouTubeThere() {
        launch()
        // YouTube's own app isn't on a TV, so YouTube there is its TV app, one row, not that and the app it stands in for.
        click(string(R.string.choose_app_button))
        assertEquals(1, composeRule.onAllNodes(hasText(string(R.string.target_youtube))).fetchSemanticsNodes().size)
        composeRule.onAllNodes(hasText(string(R.string.target_youtube)))[0].performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        // Picked, the menu closes: YouTube is only the chip's name now, not a row too.
        assertEquals(1, composeRule.onAllNodes(hasText(string(R.string.target_youtube))).fetchSemanticsNodes().size)
        // Picked, the song opens in that TV app.
        composeRule.onNodeWithTag(DEFAULT_MENU_TAG).assert(hasText(string(R.string.target_youtube)))
    }

    @Test
    fun backOnTheCoversMainScreenTheBarShowsWhatYouTubePlaysAndOpensItsPlayer() {
        launch()
        val floating = playCovered()
        // YouTube starts once it's covered, with Crosstune itself out of sight behind it (Issue #1105).
        playback.onThePhone = Heard.Song(MusicMetadata("Demo", "Band"), null, app = MusicService.YOUTUBE_TV)
        playing()
        waitUntil { shown("Demo one") }
        // The bar has a pause button too, so the player is told by its cover.
        fun onPlayer() = composeRule.onAllNodes(hasTestTag(PLAYER_COVER_TAG)).fetchSemanticsNodes().isNotEmpty()
        pressBack(floating.get())
        waitUntil { onPlayer() }
        pressBack(floating.get())
        waitUntil { !onPlayer() }
        waitUntil { composeRule.onAllNodes(hasTestTag(NOW_PLAYING_TAG)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(shown("Demo"))
        // Selected with the remote, it opens the player again, still over YouTube.
        composeRule.onNode(hasTestTag(NOW_PLAYING_TAG)).performSemanticsAction(SemanticsActions.OnClick)
        waitUntil { onPlayer() }
        assertFalse(shadowOf(floating.get()).isStoppedBySelf)
        assertTrue(playback.nowPlayingWatchers > 0)
        // Back to the main screen and out to YouTube: with nothing of Crosstune left on the TV, it stops watching.
        pressBack(floating.get())
        waitUntil { !onPlayer() }
        pressBack(floating.get())
        waitUntil { shadowOf(floating.get()).isStoppedBySelf }
        waitUntil { playback.nowPlayingWatchers == 0 }
        // Android hasn't destroyed the service yet. The activity must not stop it a second time.
        assertTrue(FloatingLyricsService.covering)
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test
    fun onATvBackFromThePlayerTheBarOpenedLandsOnTheBar() {
        launch()
        val floating = playCovered()
        playback.onThePhone = Heard.Song(MusicMetadata("Demo", "Band"), null, app = MusicService.YOUTUBE_TV)
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        playing()
        waitUntil { shown("Demo one") }
        pressBack(floating.get())
        waitUntil { onPlayer() }
        pressBack(floating.get())
        waitUntil { !onPlayer() && composeRule.onAllNodes(hasTestTag(NOW_PLAYING_TAG)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        // Back from the player the card's Play opened, the remote is on Play, with the bar under it.
        composeRule.onNode(hasContentDescription(play) and hasClickAction()).assertIsFocused()
        // Opened from the bar, back from the player it's on the bar.
        val bar = composeRule.onNode(hasTestTag(NOW_PLAYING_TAG))
        bar.requestFocus()
        bar.performKeyInput { pressKey(Key.DirectionCenter) }
        waitUntil { onPlayer() }
        pressBack(floating.get())
        waitUntil { !onPlayer() }
        composeRule.waitForIdle()
        bar.assertIsFocused()
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
    fun anAppThatNeverPlaysComesOutFromUnderTheCoverToSayWhy() {
        val listening = Listening()
        MainActivity.hearingFactory = { listening }
        launch()
        model().listenAlong()
        waitUntil { listening.started.isCompleted }
        playCovered()
        assertFalse(listening.stopped.isCompleted)
        // Signed out, it shows that instead of playing: Crosstune lets go of it, behind it as it is.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(MainViewModel.LOAD_SONG_MS + 1_000))
        composeRule.waitForIdle()
        assertEquals(FloatingLyricsService::class.java.name, checkNotNull(shadowOf(app).nextStoppedService.component) { "Expected a stopped service" }.className)
        assertFalse(model().uiState.coveringApp)
        assertNull(model().uiState.player)
        assertTrue(listening.stopped.isCompleted)
        // It's paused out of sight, not turned off for when Crosstune comes back.
        assertTrue(model().uiState.listeningAlong)
    }

    @Test
    fun wordsFloatingWhenACoverIsAskedForStayOnceTheAppPlaysNothing() {
        launch()
        click(play)
        waitUntil { shadowOf(app).peekNextStartedService() != null }
        val cover = shadowOf(app).nextStartedService
        checkNotNull(controller) { "Expected controller to be non-null" }.pause().stop()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        ShadowSettings.setCanDrawOverlays(true)
        // The words float already, so the service keeps them rather than cover the app.
        val floating = Robolectric.buildService(FloatingLyricsService::class.java, Intent(app, FloatingLyricsService::class.java)).create().startCommand(0, 1)
        floating.get().onStartCommand(cover, 0, 2)
        composeRule.waitForIdle()
        assertFalse(FloatingLyricsService.covering)
        // The app plays nothing, and Crosstune lets go of it, but the words it never covered stay.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(MainViewModel.LOAD_SONG_MS + 1_000))
        composeRule.waitForIdle()
        assertFalse(model().uiState.coveringApp)
        assertNull(shadowOf(app).nextStoppedService)
        assertTrue(overlays(floating.get()).isNotEmpty())
    }

    @Test
    fun downFromTheAppChipLandsOnPlay() {
        launch()
        val chip = composeRule.onNode(hasTestTag(DEFAULT_MENU_TAG))
        chip.requestFocus()
        chip.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasContentDescription(play))[0].assertIsFocused()
    }

    @Test
    fun aCoverLetGoOfBeforeItCameUpNeverComesUp() {
        val listening = Listening()
        MainActivity.hearingFactory = { listening }
        launch()
        model().listenAlong()
        waitUntil { listening.started.isCompleted }
        click(play)
        waitUntil { shadowOf(app).peekNextStartedService() != null }
        val cover = shadowOf(app).nextStartedService
        checkNotNull(controller) { "Expected controller to be non-null" }.pause().stop()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        ShadowSettings.setCanDrawOverlays(true)
        // The app couldn't be brought in front after all, faster than Android started the cover.
        model().uncover()
        Snapshot.sendApplyNotifications()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(listening.stopped.isCompleted)
        val floating = Robolectric.buildService(FloatingLyricsService::class.java, cover).create().startCommand(0, 1)
        composeRule.settle(checkNotNull(controller).get())
        assertTrue(listening.stopped.isCompleted)
        assertFalse(FloatingLyricsService.covering)
        assertTrue(overlays(floating.get()).isEmpty())
        assertTrue(shadowOf(floating.get()).isStoppedBySelf)
    }

    @Test
    fun playThatCantOpenYouTubeAgainCoversNothing() {
        launch()
        val floating = playCovered()
        playing()
        waitUntil { shown("Demo one") }
        backOut(floating)
        playback.playing.value = null
        checkNotNull(controller) { "Expected controller to be non-null" }.start().resume()
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
        checkNotNull(controller) { "Expected controller to be non-null" }.start().resume()
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
        checkNotNull(controller) { "Expected controller to be non-null" }.get().startActivity(Intent(Settings.ACTION_SETTINGS))
        assertEquals(FloatingLyricsService::class.java.name, checkNotNull(shadowOf(app).nextStoppedService.component) { "Expected shadowOf(app).nextStoppedService.component to be non-null" }.className)
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
        composeRule.runOnIdle { floating.get().onStartCommand(Intent(FloatingLyricsService.ACTION_CLOSE), 0, 2) }
        assertTrue(shadowOf(floating.get()).isStoppedBySelf)
        assertFalse(model().uiState.coveringApp)
        assertNotNull(model().uiState.player)
        assertEquals(0, playback.cameBack)
        // Nothing is over YouTube to show the words, so Crosstune comes in front of it.
        playing()
        waitUntil { playback.cameBack == 1 }
    }

    @Test
    fun backingOutOfThePlayerBeforeYouTubeStartsCancelsTheReturn() {
        launch()
        val floating = playCovered()
        backOut(floating)
        assertNull(model().uiState.player)
        waitUntil { !model().uiState.playingResult }

        playing()
        composeRule.waitForIdle()
        assertEquals(0, playback.cameBack)
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
        checkNotNull(controller) { "Expected controller to be non-null" }.get().startActivity(Intent(Settings.ACTION_SETTINGS))
        assertEquals(FloatingLyricsService::class.java.name, checkNotNull(shadowOf(app).nextStoppedService.component) { "Expected shadowOf(app).nextStoppedService.component to be non-null" }.className)
        assertNull(FloatingLyricsService.host)
    }

    @Test
    fun openingAnotherAppLetsGoOfACoverThatHasntComeUpYet() {
        launch()
        click(play)
        waitUntil { shadowOf(app).peekNextStartedService() != null }
        val cover = shadowOf(app).nextStartedService
        assertTrue(model().uiState.coveringApp)
        assertFalse(FloatingLyricsService.covering)
        val activity = checkNotNull(controller).get()
        activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        assertEquals(Settings.ACTION_SETTINGS, shadowOf(app).nextStartedActivity.action)
        assertFalse(model().uiState.coveringApp)

        // Android may deliver the cover after the new app is in front.
        checkNotNull(controller).pause().stop()
        val floating = Robolectric.buildService(FloatingLyricsService::class.java, cover).create().startCommand(0, 1)
        try {
            composeRule.settle(activity)
            assertTrue(overlays(floating.get()).isEmpty())
            assertTrue(shadowOf(floating.get()).isStoppedBySelf)
        } finally {
            floating.destroy()
        }
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
        // Taken back while Crosstune is out of sight, behind YouTube.
        val floating = playCovered(canDraw = false)
        assertTrue(overlays(floating.get()).isEmpty())
        assertTrue(shadowOf(floating.get()).isStoppedBySelf)
        // Crosstune is told, so it stops watching what plays for a cover that isn't there. What's left is the
        // player's own follow of the song it opened, three watchers, and the wait for YouTube to play anything, one.
        assertTrue(watchersBehindTheCover > 4)
        waitUntil { playback.nowPlayingWatchers == 4 }
    }

    @Test
    fun withShowingOverOtherAppsTakenBackUnderTheCoverTheCoverCloses() {
        launch()
        val floating = playCovered()
        assertEquals(1, overlays(floating.get()).size)
        val appOps = shadowOf(app.getSystemService(AppOpsManager::class.java))
        val uid = app.applicationInfo.uid
        // Another app's permission changing, or Crosstune's staying allowed, leaves the cover as it is.
        appOps.setMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, app.packageName, AppOpsManager.MODE_ALLOWED)
        composeRule.waitForIdle()
        assertFalse(shadowOf(floating.get()).isStoppedBySelf)
        // Taken back with ADB while the cover shows: Settings can't, as it takes the cover's place first.
        ShadowSettings.setCanDrawOverlays(false)
        appOps.setMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, app.packageName, AppOpsManager.MODE_ERRORED)
        composeRule.waitForIdle()
        assertTrue(shadowOf(floating.get()).isStoppedBySelf)
        // Crosstune is told, so it stops watching what plays for a cover that isn't there.
        waitUntil { playback.nowPlayingWatchers == 4 }
        floating.destroy()
        assertTrue(overlays(floating.get()).isEmpty())
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
        checkNotNull(controller) { "Expected controller to be non-null" }.start().resume()
        composeRule.waitForIdle()
        click(string(R.string.lyrics_play))
        waitUntil { playback.opened.size == 2 }
        assertEquals(youTubeMusic to link, playback.opened.last())
    }

    @Test
    fun onAPhoneThePlayersPlayDoesntOpenTheAppItCantAsk() {
        phone = Phone.OTHER
        shadowOf(app.packageManager).installPackage(installedApp(morpheMusic, "YouTube Music"))
        launch()
        click(play)
        waitUntil { playback.opened.size == 1 }
        // The phone's own app plays it, as it's installed.
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true, appPackage = morpheMusic)
        waitUntil { playback.cameBack == 1 }
        playback.playing.value = null
        composeRule.waitForIdle()
        click(string(R.string.lyrics_play))
        // A phone's app keeps its song in front of Crosstune too; there's nothing to open it on again for.
        assertEquals(1, playback.opened.size)
    }

    /**
     * On a phone, Play pressed in an app that only plays in front: running, it's asked
     * from behind, where it won't, so it opens on the song.
     */
    private fun playInFront() {
        phone = Phone.OTHER
        shadowOf(app.packageManager).installPackage(installedApp(morpheMusic, "YouTube Music"))
        playback.running = null
        playback.startsInBackground = false
        launch()
        click(play)
        waitUntil { playback.opened.size == 1 }
    }

    @Test
    fun onAPhoneAnAppThatOnlyPlaysInFrontIsCoveredWithTheSongsWordsAsOnATv() {
        playInFront()
        // Coming back would stop it, so Crosstune covers it instead, the whole screen, from as it's opened.
        waitUntil { shadowOf(app).peekNextStartedService() != null }
        val started = shadowOf(app).nextStartedService
        assertTrue(started.getBooleanExtra(FloatingLyricsService.EXTRA_COVER, false))
        checkNotNull(controller) { "Expected controller to be non-null" }.pause().stop()
        val floating = Robolectric.buildService(FloatingLyricsService::class.java, started).create().startCommand(0, 1)
        composeRule.waitForIdle()
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params(floating.get()).width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params(floating.get()).height)
        // Once it plays there, its words show over it, and Crosstune stays behind.
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true, appPackage = morpheMusic)
        waitUntil { shown("Demo one") }
        assertEquals(0, playback.cameBack)
        // Back goes back through Crosstune, then leaves it to the app, which plays on.
        backOut(floating)
    }

    @Test
    fun onAPhoneNotAllowedToShowOverAppsAnAppThatOnlyPlaysInFrontIsLeftThere() {
        ShadowSettings.setCanDrawOverlays(false)
        phone = Phone.OTHER
        shadowOf(app.packageManager).installPackage(installedApp(morpheMusic, "YouTube Music"))
        playback.running = null
        playback.startsInBackground = false
        launch()
        click(play)
        // Not allowed over other apps, so Play asks once for the way back, then still leaves an app that only plays in front.
        val asked = shadowOf(checkNotNull(controller) { "Expected controller to be non-null" }.get()).lastRequestedPermission
        assertEquals(android.Manifest.permission.POST_NOTIFICATIONS, asked.requestedPermissions.single())
        checkNotNull(controller) { "Expected controller to be non-null" }.get().answer(asked, android.content.pm.PackageManager.PERMISSION_DENIED)
        composeRule.waitForIdle()
        waitUntil { playback.opened.size == 1 }
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true, appPackage = morpheMusic)
        composeRule.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).peekNextStartedService())
        assertEquals(0, playback.cameBack)
    }

    /** On a phone, Play pressed on a song paused in an app that only plays in front. */
    private fun playOnPaused() {
        phone = Phone.OTHER
        shadowOf(app.packageManager).installPackage(installedApp(morpheMusic, "YouTube Music"))
        playback.startsInBackground = false
        playback.playing.value = Following(PlaybackClock(25_000, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true, appPackage = morpheMusic)
        launch()
        click(play)
    }

    @Test
    fun onAPhoneASongPausedInAnAppThatOnlyPlaysInFrontPlaysOnThereCovered() {
        playOnPaused()
        // Asked from behind, it won't, so it's brought to the front on the song, where it was rather than from the start, and covered.
        waitUntil { playback.broughtToFront.size == 1 }
        assertEquals(listOf(true), playback.plays)
        assertTrue(playback.opened.isEmpty())
        waitUntil { shadowOf(app).peekNextStartedService() != null }
        assertTrue(shadowOf(app).nextStartedService.getBooleanExtra(FloatingLyricsService.EXTRA_COVER, false))
    }

    @Test
    fun onAPhoneASongPausedInAnAppThatCantBeBroughtToTheFrontIsntCovered() {
        playback.opens = false
        playOnPaused()
        waitUntil { playback.plays.isNotEmpty() }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun onAPhoneAnAppOpenedOnTheSongThatStopsOnceLeftIsBroughtBackCovered() {
        phone = Phone.OTHER
        shadowOf(app.packageManager).installPackage(installedApp(morpheMusic, "YouTube Music"))
        // Not running, it can't be asked from behind, so it's opened on the song, not known yet to play only in front.
        playback.running = emptySet()
        playback.startsInBackground = false
        launch()
        click(play)
        waitUntil { playback.opened.size == 1 }
        // Crosstune comes back once it plays there, not as soon as it shows the song: YouTube Music, left before it starts, never does.
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true, appPackage = morpheMusic)
        composeRule.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, playback.cameBack)
        assertNull(shadowOf(app).peekNextStartedService())
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true, appPackage = morpheMusic)
        waitUntil { playback.cameBack == 1 }
        // On to an ad under the song's name, it plays on, if not the song: nothing to bring back.
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true, appPackage = morpheMusic, ad = true)
        composeRule.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(playback.plays.isEmpty())
        // Left, it stops, as it only plays in front: it's brought back there, covered, to play on.
        playback.playing.value = Following(PlaybackClock(5_000, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true, appPackage = morpheMusic)
        waitUntil { playback.broughtToFront.size == 1 }
        waitUntil { shadowOf(app).peekNextStartedService() != null }
        assertTrue(shadowOf(app).nextStartedService.getBooleanExtra(FloatingLyricsService.EXTRA_COVER, false))
    }

    @Test
    fun onAPhoneAnAppThatLetsGoOfTheSongOnceLeftIsBroughtBackCoveredToo() {
        phone = Phone.OTHER
        shadowOf(app.packageManager).installPackage(installedApp(morpheMusic, "YouTube Music"))
        playback.running = emptySet()
        playback.startsInBackground = false
        launch()
        click(play)
        waitUntil { playback.opened.size == 1 }
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true, appPackage = morpheMusic)
        waitUntil { playback.cameBack == 1 }
        // Left, YouTube without Premium stops altogether, with nothing paused to follow.
        playback.playing.value = null
        waitUntil { playback.broughtToFront.size == 1 }
        waitUntil { shadowOf(app).peekNextStartedService() != null }
        assertTrue(shadowOf(app).nextStartedService.getBooleanExtra(FloatingLyricsService.EXTRA_COVER, false))
    }

    @Test
    fun onAPhoneAnAppOpenedOnTheSongThatPlaysOnOnceLeftIsLeftToIt() {
        phone = Phone.OTHER
        shadowOf(app.packageManager).installPackage(installedApp(morpheMusic, "YouTube Music"))
        playback.running = emptySet()
        launch()
        click(play)
        waitUntil { playback.opened.size == 1 }
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true, appPackage = morpheMusic)
        waitUntil { playback.cameBack == 1 }
        // Paused from Crosstune, it isn't taken for one that stopped once left.
        click(string(R.string.lyrics_pause))
        playback.playing.value = Following(PlaybackClock(5_000, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true, appPackage = morpheMusic)
        composeRule.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf(false), playback.plays)
        assertTrue(playback.broughtToFront.isEmpty())
    }

    @Test
    fun onAPhoneTheSongPlayingInAnotherBuildOfTheAppAskedIsntTakenForIt() {
        phone = Phone.OTHER
        shadowOf(app.packageManager).installPackage(installedApp(morpheMusic, "YouTube Music"))
        playback.running = null
        // Another build of YouTube Music plays it, not the one asked, which is on the phone.
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true, appPackage = youTubeMusic)
        launch()
        click(play)
        waitUntil { playback.songsAsked.isNotEmpty() }
        // Not seen to play there, the app asked is opened on the song.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(MainViewModel.LOAD_SONG_MS))
        waitUntil { playback.opened.isNotEmpty() }
        assertEquals(listOf(morpheMusic to link), playback.opened)
    }

    @Test
    fun youTubesTvAppPlaysYouTubeMusicsSongs() {
        // Its phone app isn't on a TV, so YouTube's TV app stands in for it.
        assertTrue(sameApp(youTubeMusic, MusicService.YOUTUBE_TV, app::hasApp))
        assertFalse(MusicService.YOUTUBE_TV in MusicService.SPOTIFY.appPackages)
    }

    @Test
    fun onATvThePlayerFollowsTheYouTubeSessionItResumed() {
        val tv = MusicService.YOUTUBE_TV
        val session = 23
        val paused = Following(PlaybackClock(25_000, SystemClock.elapsedRealtime(), playing = false),
            "YouTube", canSeek = true, appPackage = tv, session = session)
        playback.playing.value = paused
        playback.onPlay = { _, playing -> playback.playing.value = paused.copy(clock = paused.clock.copy(playing = playing)) }
        launch()
        val official = Destination.Service(MusicService.YOUTUBE_MUSIC)
        composeRule.runOnIdle {
            assertFalse(app.hasApp(youTubeMusic))
            assertEquals(official, model().uiState.resultDestination)
        }
        click(play)
        waitUntil { playback.plays.isNotEmpty() && onPlayer() }
        assertEquals(listOf(true), playback.plays)
        assertEquals(listOf(tv), playback.playedApps)
        assertEquals(listOf(session), playback.playedSessions)
        val pause = string(R.string.lyrics_pause)
        waitUntil { shown(pause) }
        composeRule.runOnIdle {
            val player = checkNotNull(model().uiState.player)
            assertEquals(tv, player.app)
            assertEquals(session, player.session)
            assertEquals(true, player.following?.clock?.playing)
        }
        assertTrue(session in playback.followedSessions)
        click(pause)
        assertEquals(listOf(true, false), playback.plays)
        assertEquals(listOf(session, session), playback.playedSessions)
        assertTrue(playback.opened.isEmpty())
    }

    @Test
    fun onATvThePlayerKeepsRemoteFocusAsTheAdPausesAndResumesInItsOwnSession() {
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        playback.running = setOf(youTubeMusic)
        playback.rememberYouTubeLength("sPmul8b17AU", 214_000)
        launch()
        click(play)
        waitUntil { playback.linksAsked == listOf(link) }
        val ad = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = false), "YouTube Music",
            canSeek = true, appPackage = youTubeMusic, durationMs = 214_000, ad = true, adPlaying = true, session = 23)
        playback.onPlay = { _, playing -> playback.playing.value = ad.copy(adPlaying = playing) }
        playback.playing.value = ad
        waitUntil { onPlayer() && model().uiState.player?.following?.ad == true }
        val pause = composeRule.onNode(hasContentDescription(string(R.string.lyrics_pause)) and hasClickAction())
        val resume = composeRule.onNode(hasContentDescription(string(R.string.lyrics_play)) and hasClickAction())
        pause.requestFocus()
        pause.assertIsFocused()
        pause.performKeyInput { pressKey(Key.DirectionCenter) }
        waitUntil { model().uiState.player?.following?.adPlaying == false }
        resume.assertIsFocused()
        assertEquals(listOf(false), playback.plays)
        resume.performKeyInput { pressKey(Key.DirectionCenter) }
        waitUntil { model().uiState.player?.following?.adPlaying == true }
        pause.assertIsFocused()
        assertEquals(listOf(false, true), playback.plays)
        assertEquals(listOf(23, 23), playback.playedSessions)
        assertEquals(ad.clock, model().uiState.player?.following?.clock)
        assertTrue(playback.opened.isEmpty())
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
        launch(action = string(R.string.lyrics_button))
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
