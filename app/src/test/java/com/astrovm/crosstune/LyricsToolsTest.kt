package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import java.io.File
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

/** The song's info and the bar under the words get out of their way, and come back to a touch or the remote's key. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class LyricsToolsTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null
    private var phone = Phone.TV
    private val playback = FakePlayback().apply {
        access = true
        running = emptySet()
    }
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
        prefs().edit().clear().putBoolean("setup_complete", true).putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false).putBoolean("play_right_away", false).putBoolean("visuals", false).commit()
        MainActivity.milkdropFactory = { NoMilkdrop }
        MainActivity.soundTapFactory = { null }
        MainActivity.lyricsBusyPauseMs = 0
        MainActivity.lookAheadLyrics = false
        File(app.cacheDir, "lookups.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        // The song is YouTube Music's, which a TV plays in YouTube's app.
        shadowOf(app.packageManager).installPackage(installedApp(MusicService.YOUTUBE_TV, "YouTube"))
        shadowOf(app.packageManager).installPackage(installedApp(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music"))
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
        shadowOf(app.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(false)
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.phoneFactory = realPhone
        MainActivity.checksForUpdates = ::looksForUpdates
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.milkdropFactory = ::NativeMilkdrop
        MainActivity.soundTapFactory = { OutputMixTap.open() }
        MainActivity.lyricsBusyPauseMs = LYRICS_BUSY_PAUSE_MS
        MainActivity.lookAheadLyrics = true
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    private fun described(label: String) = composeRule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private val about get() = string(R.string.lyrics_about_song)
    private val learn get() = string(R.string.lyrics_learn)

    /** The song's words open, with the music app playing them; from here time only moves as the test moves it. */
    private fun openLyrics() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        val button = hasText(string(R.string.lyrics_button)) or hasContentDescription(string(R.string.lyrics_button))
        waitUntil { composeRule.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(button)[0].performScrollTo().performClick()
        waitUntil { composeRule.onAllNodes(hasText("Demo one", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube", canSeek = true)
        composeRule.waitForIdle()
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

    private fun passes(ms: Long) {
        composeRule.mainClock.advanceTimeBy(ms)
        composeRule.waitForIdle()
    }

    /** A touch on the song's name side of the screen, where nothing but the screen itself takes it. */
    private fun touch() {
        composeRule.onRoot().performTouchInput { click(Offset(2f, 2f)) }
        passes(1)
    }

    /** The tools gone: let a touch start the wait, then let it run out. */
    private fun awayFromTheTools() {
        touch()
        passes(SHOWN_MS + FADE_MS)
        assertFalse(described(about))
        assertFalse(described(learn))
    }

    /** The remote is on one of the bar's tools, whichever it lands on. */
    private fun onTheBar() = listOf(learn, string(R.string.lyrics_study), string(R.string.visuals_show)).any { label ->
        composeRule.onAllNodes(hasContentDescription(label) and isFocused()).fetchSemanticsNodes().isNotEmpty()
    }

    private fun line(text: String) = composeRule.onAllNodes(hasText(text))[0]

    @Test
    fun theInfoAndTheBarShowForFiveSecondsThenFadeOut() {
        phone = Phone.OTHER
        openLyrics()
        touch()
        passes(SHOWN_MS - 500)
        assertTrue(described(about))
        assertTrue(described(learn))
        passes(500 + FADE_MS)
        assertFalse(described(about))
        assertFalse(described(learn))
        // The song and the way back stay as they were.
        assertTrue(shown("Demo"))
        assertTrue(described(string(R.string.dismiss_button)))
    }

    @Test
    fun pausedTheyStayAndPlayingAgainTheyGoFiveSecondsLater() {
        phone = Phone.OTHER
        openLyrics()
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = false), "YouTube", canSeek = true)
        composeRule.waitForIdle()
        passes(SHOWN_MS + FADE_MS)
        assertTrue(described(about))
        assertTrue(described(learn))
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube", canSeek = true)
        composeRule.waitForIdle()
        passes(SHOWN_MS - 500)
        assertTrue(described(learn))
        passes(500 + FADE_MS)
        assertFalse(described(about))
        assertFalse(described(learn))
    }

    @Test
    fun withNoTimeForTheWordsTheyStay() {
        phone = Phone.OTHER
        openLyrics()
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube", canSeek = true, timingKnown = false)
        composeRule.waitForIdle()
        passes(SHOWN_MS + FADE_MS)
        assertTrue(described(about))
        assertTrue(described(learn))
    }

    @Test
    fun wordsWithNoTimingsKeepThemWhilePlaying() {
        phone = Phone.OTHER
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                if (request.url.host == "lrclib.net") """[{"trackName":"Demo","artistName":"Band","duration":60,"plainLyrics":"Demo one\nDemo two"}]"""
                else """{"title":"Demo","author_name":"Band"}"""
            )
        }
        openLyrics()
        passes(SHOWN_MS + FADE_MS)
        assertTrue(described(about))
        assertTrue(described(learn))
    }

    @Test
    fun theBarComesAndGoesOverTheWordsWithoutMovingThem() {
        phone = Phone.OTHER
        openLyrics()
        val words = composeRule.onAllNodes(hasScrollAction())[0]
        touch()
        val withBar = words.getUnclippedBoundsInRoot().bottom
        val barTop = composeRule.onNodeWithContentDescription(learn).getUnclippedBoundsInRoot().top
        assertTrue("the words end at $withBar, before the bar at $barTop", withBar > barTop)
        passes(SHOWN_MS + FADE_MS)
        assertEquals(withBar, words.getUnclippedBoundsInRoot().bottom)
    }

    @Test
    fun aTouchBringsThemBackForFiveSecondsAndEachTouchStartsThemAgain() {
        phone = Phone.OTHER
        openLyrics()
        awayFromTheTools()
        touch()
        assertTrue(described(about))
        assertTrue(described(learn))
        passes(SHOWN_MS - 1_000)
        touch()
        passes(SHOWN_MS - 1_000)
        assertTrue(described(learn))
        passes(1_000 + FADE_MS)
        assertFalse(described(learn))
    }

    @Test
    fun aTouchThatBringsThemBackIsNotATapOnALine() {
        phone = Phone.OTHER
        openLyrics()
        awayFromTheTools()
        line("Demo two").performClick()
        passes(1)
        assertTrue(playback.seeks.isEmpty())
        assertTrue(described(learn))
        // Once they're back, a line takes its taps as ever.
        line("Demo two").performClick()
        passes(1)
        assertEquals(listOf(10_000L), playback.seeks)
    }

    @Test
    fun aLineHeldWhileTheyWereAwayIsNotOpenedToStudyEither() {
        phone = Phone.OTHER
        openLyrics()
        awayFromTheTools()
        line("Demo two").performTouchInput { longClick() }
        passes(1)
        assertFalse(shown(string(R.string.lyrics_repeat_line)))
        assertTrue(described(learn))
        // Once they're back, held, a line opens to study as ever.
        line("Demo two").performTouchInput { longClick() }
        passes(1)
        assertTrue(shown(string(R.string.lyrics_repeat_line)))
    }

    @Test
    fun aKeyOnATvBringsThemBackAsItGoesDownNotAsItComesUp() {
        openLyrics()
        line("Demo two").requestFocus()
        awayFromTheTools()
        line("Demo two").performKeyInput { keyDown(Key.DirectionDown) }
        passes(1)
        assertTrue(described(learn))
        // Letting go isn't another key to wait for.
        passes(SHOWN_MS - 1_000)
        line("Demo two").performKeyInput { keyUp(Key.DirectionDown) }
        passes(1_000 + FADE_MS)
        assertFalse(described(learn))
    }

    @Test
    fun aKeyOnATvBringsThemBackAndAnySelectKeyOnALineWaitsForTheNext() {
        openLyrics()
        line("Demo two").requestFocus()
        awayFromTheTools()
        line("Demo two").performKeyInput { pressKey(Key.DirectionCenter) }
        passes(1)
        assertTrue(playback.seeks.isEmpty())
        assertTrue(described(about))
        line("Demo two").performKeyInput { pressKey(Key.DirectionCenter) }
        passes(1)
        assertEquals(listOf(10_000L), playback.seeks)
    }

    @Test
    fun theOtherSelectKeysWaitForTheNextToo() {
        openLyrics()
        line("Demo two").requestFocus()
        awayFromTheTools()
        line("Demo two").performKeyInput { pressKey(Key.Enter) }
        awayFromTheTools()
        line("Demo two").performKeyInput { pressKey(Key.NumPadEnter) }
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun aKeyThatIsNotSelectGoesOnToWhatHasTheFocusAsItBringsThemBack() {
        openLyrics()
        line("Demo two").requestFocus()
        awayFromTheTools()
        line("Demo two").performKeyInput { pressKey(Key.DirectionDown) }
        passes(1)
        assertTrue(described(learn))
    }

    @Test
    fun onAPhoneAKeyDoesNothingToThem() {
        phone = Phone.OTHER
        openLyrics()
        awayFromTheTools()
        line("Demo two").requestFocus()
        line("Demo two").performKeyInput { pressKey(Key.DirectionDown) }
        passes(1)
        assertFalse(described(learn))
    }

    @Test
    fun theyStayWhileAMenuIsOpen() {
        phone = Phone.OTHER
        openLyrics()
        touch()
        composeRule.onAllNodes(hasContentDescription(learn))[0].performClick()
        passes(1)
        passes(SHOWN_MS * 3)
        assertTrue(described(about))
        assertTrue(described(learn))
        // Closed, they have their five seconds from the touch that closed it.
        composeRule.onAllNodes(hasContentDescription(learn))[0].performClick()
        passes(SHOWN_MS - 1_000)
        assertTrue(described(learn))
        passes(1_000 + FADE_MS)
        assertFalse(described(learn))
    }

    @Test
    fun theyStayWhileTheWordsAreBeingPutInTime() {
        phone = Phone.OTHER
        openLyrics()
        touch()
        composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_sync)))[0].performClick()
        passes(1)
        passes(SHOWN_MS * 3)
        assertTrue(described(about))
        assertTrue(described(learn))
        composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_sync)))[0].performClick()
        passes(SHOWN_MS + FADE_MS + 1_000)
        assertFalse(described(learn))
    }

    @Test
    fun theyStayWhileAScreenReaderIsOn() {
        phone = Phone.OTHER
        openLyrics()
        awayFromTheTools()
        // Turned on while the words show, they come back, and go again once it's off.
        shadowOf(app.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        passes(1)
        assertTrue(described(about))
        assertTrue(described(learn))
        passes(SHOWN_MS * 3)
        assertTrue(described(learn))
        // A tap on a line is the line's, as there's no hiding to bring back.
        line("Demo two").performClick()
        passes(1)
        assertEquals(listOf(10_000L), playback.seeks)
        shadowOf(app.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(false)
        passes(SHOWN_MS + FADE_MS)
        assertFalse(described(learn))
    }

    @Test
    fun anotherAccessibilityServiceStillLetsThemGo() {
        // E.g. a password manager's, which leaves taps as they are.
        shadowOf(app.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        phone = Phone.OTHER
        openLyrics()
        passes(SHOWN_MS + FADE_MS)
        assertFalse(described(about))
        assertFalse(described(learn))
    }

    @Test
    fun withAScreenReaderAlreadyOnTheyNeverGo() {
        shadowOf(app.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        openLyrics()
        passes(SHOWN_MS * 3)
        assertTrue(described(about))
        assertTrue(described(learn))
    }

    @Test
    fun onceTheWordsArriveTheRemoteIsOnTheBarsFirstTool() {
        openLyrics()
        waitUntil { composeRule.onAllNodes(hasContentDescription(learn) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theRemoteStartsOnTheBarWhichStillGoesAndLeavesItOnTheWords() {
        openLyrics()
        waitUntil { onTheBar() }
        passes(SHOWN_MS + FADE_MS)
        assertFalse(described(about))
        assertFalse(described(learn))
        // Any key brings them back, and the words had the remote meanwhile, so it's not lost.
        composeRule.onAllNodes(isFocused())[0].performKeyInput { pressKey(Key.DirectionUp) }
        passes(1)
        assertTrue(described(learn))
    }

    @Test
    fun theRemoteOnTheInfoDoesntKeepItEither() {
        openLyrics()
        val info = composeRule.onAllNodes(hasContentDescription(about))[0]
        info.requestFocus()
        passes(SHOWN_MS + FADE_MS)
        assertFalse(described(about))
        assertFalse(described(learn))
        composeRule.onAllNodes(isFocused())[0].performKeyInput { pressKey(Key.DirectionDown) }
        passes(1)
        assertTrue(described(about))
    }

    @Test
    fun eachKeyOnTheBarKeepsIt() {
        openLyrics()
        waitUntil { onTheBar() }
        repeat(3) {
            passes(SHOWN_MS - 1_000)
            composeRule.onAllNodes(isFocused())[0].performKeyInput { pressKey(Key.DirectionLeft) }
            passes(1)
        }
        assertTrue(described(learn))
    }

    @Test
    fun aHiddenBarHoldsNoFocusAndRightFromALineBringsItBackWithTheFocus() {
        openLyrics()
        line("Demo two").requestFocus()
        awayFromTheTools()
        // Nothing to hold it, and nowhere to jump to.
        assertFalse(described(learn))
        line("Demo two").assertIsFocused()
        line("Demo two").performKeyInput { pressKey(Key.DirectionRight) }
        passes(1)
        assertTrue(onTheBar())
        line("Demo two").assertIsNotFocused()
    }

    @Test
    fun rightFromALineGoesToTheBarWhileItShows() {
        openLyrics()
        waitUntil { onTheBar() }
        touch()
        line("Demo two").requestFocus()
        line("Demo two").performKeyInput { pressKey(Key.DirectionRight) }
        passes(1)
        assertTrue(onTheBar())
    }

    @Test
    fun onAPhoneRightFromALineLeavesTheBarAlone() {
        phone = Phone.OTHER
        openLyrics()
        touch()
        line("Demo two").requestFocus()
        line("Demo two").performKeyInput { pressKey(Key.DirectionRight) }
        passes(1)
        composeRule.onAllNodes(hasContentDescription(learn))[0].assertIsNotFocused()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SHOWN_MS = 5_000L
        const val FADE_MS = 500L
    }
}
