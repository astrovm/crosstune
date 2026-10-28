package com.astrovm.crosstune

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2000dp")
class PlayerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun timesReadAsAClockDoes() {
        assertEquals("0:00", clockText(0))
        assertEquals("0:00", clockText(-500))
        assertEquals("0:09", clockText(9_999))
        assertEquals("3:07", clockText(187_000))
        assertEquals("59:59", clockText(3_599_000))
        assertEquals("1:02:07", clockText(3_727_000))
    }

    @Test
    fun shuffleSitsLeftOfPreviousAsMusicAppsHaveIt() {
        compose.setContent {
            val following = Following(PlaybackClock(30_000, 0, playing = false), "YouTube Music", canSeek = true, durationMs = 200_000, shuffle = false)
            CrosstuneTheme { PlayerScreen(UiState(canFollowApps = true, player = PlayerState(MusicMetadata("Song", "Band"), null, following = following)), ScreenActions()) }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun bounds(name: Int) = compose.onNodeWithContentDescription(context.getString(name)).fetchSemanticsNode().boundsInRoot
        val shuffle = bounds(R.string.player_shuffle)
        val previous = bounds(R.string.lyrics_previous_song)
        assertTrue("Shuffle isn't left of previous: $shuffle, $previous", shuffle.right <= previous.left)
        assertEquals(previous.center.y, shuffle.center.y, 1f)
    }

    @Test
    fun shuffleOnIsBoxedIn() {
        var shuffles by mutableStateOf(false)
        compose.setContent {
            val following = Following(PlaybackClock(30_000, 0, playing = false), "YouTube Music", canSeek = true, durationMs = 200_000, shuffle = shuffles)
            CrosstuneTheme { PlayerScreen(UiState(canFollowApps = true, player = PlayerState(MusicMetadata("Song", "Band"), null, following = following)), ScreenActions()) }
        }
        val name = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.player_shuffle)
        // How much of the button is drawn on: its pixels far from its edge on the same row, which is
        // the ground behind it, as that fades from top to bottom, dithered.
        fun inked(): Int {
            val bounds = compose.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val xs = bounds.left.toInt() until bounds.right.toInt()
            val ys = bounds.top.toInt() until bounds.bottom.toInt()
            fun light(pixel: Int) = android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
            return ys.sumOf { y -> xs.count { x -> kotlin.math.abs(light(image.getPixel(x, y)) - light(image.getPixel(xs.first, y))) > 150 } }
        }
        val off = inked()
        shuffles = true
        compose.waitForIdle()
        // A color alone is too faint beside the times; on, it's a box, far more of it drawn.
        val on = inked()
        assertTrue("Shuffle on isn't boxed in: $on pixels drawn, against $off off", on > off * 2)
    }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private var shown by mutableStateOf(UiState())

    private lateinit var inputModes: InputModeManager

    /** The player on "Song" by "Band", paused 25 seconds into its 200, followed as [following] has it on top of that. */
    private fun player(actions: ScreenActions = ScreenActions(), following: (Following) -> Following? = { it }) {
        val paused = Following(PlaybackClock(25_000, 0, playing = false), "YouTube Music", canSeek = true, durationMs = 200_000)
        shown = UiState(canFollowApps = true, player = PlayerState(MusicMetadata("Song", "Band"), null, following = following(paused)))
        compose.setContent {
            inputModes = LocalInputModeManager.current
            CrosstuneTheme { PlayerScreen(shown, actions) }
        }
    }

    private fun showing(change: (UiState) -> UiState) {
        shown = change(shown)
        compose.waitForIdle()
    }

    private fun none(matcher: SemanticsMatcher) = assertTrue(compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty())

    @Test
    fun repeatSitsRightOfNextAndSaysHowItRepeats() {
        var repeats = 0
        player(ScreenActions(onPlayerRepeat = { repeats++ })) { it.copy(repeat = Repeat.OFF) }
        val name = context.getString(R.string.player_repeat)
        fun bounds(text: String) = compose.onNodeWithContentDescription(text).fetchSemanticsNode().boundsInRoot
        val next = bounds(context.getString(R.string.lyrics_next_song))
        assertTrue(next.right <= bounds(name).left)
        assertEquals(next.center.y, bounds(name).center.y, 1f)
        compose.onNodeWithContentDescription(name).assertIsOff().performClick()
        assertEquals(1, repeats)
        // On, it says which way, as the icon draws it.
        val how = { text: String -> SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text) }
        none(how(context.getString(R.string.player_repeat_all)))
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(repeat = Repeat.ALL))) }
        compose.onNode(hasContentDescription(name) and how(context.getString(R.string.player_repeat_all))).assertIsOn()
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(repeat = Repeat.ONE))) }
        compose.onNode(hasContentDescription(name) and how(context.getString(R.string.player_repeat_one))).assertIsOn()
        // An app that doesn't say has none.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(repeat = null))) }
        none(hasContentDescription(name))
    }

    @Test
    fun aSwipeOnTheCoverSkipsWhereTheAppCanGo() {
        val skips = mutableListOf<Boolean>()
        player(ScreenActions(onPlayerSkip = { skips += it })) { it.copy(canSkipNext = true) }
        val cover = compose.onNodeWithTag(PLAYER_COVER_TAG)
        // Left for the next song.
        cover.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf(true), skips)
        // Right for the one before, which the app can't go to yet.
        cover.performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(listOf(true), skips)
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(canSkipPrevious = true))) }
        cover.performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(listOf(true, false), skips)
        // A short one is only a touch.
        cover.performTouchInput { swipeLeft(startX = centerX, endX = centerX - width * 0.1f) }
        compose.waitForIdle()
        assertEquals(listOf(true, false), skips)
    }

    @Test
    fun theLikeIsTheAppsOwnAsItNamesAndDrawsIt() {
        var likes = 0
        // Drawn with the app's own drawing; here Crosstune's, as the test has no other app's.
        player(ScreenActions(onPlayerLike = { likes++ })) { it.copy(appPackage = context.packageName, like = AppAction("thumbs_up_action", "Like", R.drawable.ic_bedtime)) }
        compose.onNodeWithContentDescription("Like").performClick()
        assertEquals(1, likes)
        // An app whose drawing can't be had gets a thumb up, still named as the app names it.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(appPackage = "com.example.gone", like = AppAction("thumbs_up_action", "Gefällt mir", 1)))) }
        compose.onNodeWithContentDescription("Gefällt mir").performClick()
        assertEquals(2, likes)
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(like = null))) }
        none(hasContentDescription("Gefällt mir"))
    }

    @Test
    fun theListItPlaysFromOpensWhatPlaysNext() {
        val played = mutableListOf<Long>()
        player(ScreenActions(onPlayerPlayQueued = { played += it })) { it.copy(from = "Road trip", upNext = listOf(Queued("Next Song", "Band", 7), Queued("Later Song", ""))) }
        val upNext = context.getString(R.string.player_up_next)
        compose.onNodeWithContentDescription("$upNext, Road trip").performClick()
        compose.waitForIdle()
        // Named as the list, with the songs the app has playing at a tap, and those Crosstune asks for later not yet.
        assertEquals(2, compose.onAllNodes(hasText("Road trip"), useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Later Song").assertHasNoClickAction()
        compose.onNodeWithText("Next Song").performClick()
        compose.waitForIdle()
        assertEquals(listOf(7L), played)
        none(hasText("Next Song"))
        // Not played from a list, it's only what's next.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(from = null))) }
        compose.onNodeWithContentDescription(upNext).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(upNext).assertExists()
        // A tap beside it puts it away.
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, 5f)) }
        compose.waitForIdle()
        none(hasText(upNext))
        // With nothing after it, there's nothing to open.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(upNext = emptyList()))) }
        none(hasContentDescription(upNext))
    }

    @Test
    fun theRemoteGoesThroughWhatsNextAndTheSleepTimerAndBack() {
        val played = mutableListOf<Long>()
        val asked = mutableListOf<Int?>()
        player(ScreenActions(onPlayerPlayQueued = { played += it }, onPlayerSleep = { asked += it })) {
            it.copy(upNext = listOf(Queued("Next Song", "Band", 7), Queued("Later Song", "")))
        }
        fun press(node: androidx.compose.ui.test.SemanticsNodeInteraction, key: Key) {
            node.performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
        fun focused(text: String) = compose.onAllNodes(hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().isNotEmpty()
        val upNext = compose.onNodeWithContentDescription(context.getString(R.string.player_up_next))
        // The remote's, as a TV is: Robolectric starts as a touch screen, where buttons take no focus.
        compose.runOnIdle { inputModes.requestInputMode(InputMode.Keyboard) }
        upNext.requestFocus()
        press(upNext, Key.DirectionCenter)
        // It starts on the next song; down through the rest, the one still to be asked for too, and no
        // further, as the player's under it; then up to play one.
        assertTrue(focused("Next Song"))
        press(compose.onNodeWithText("Next Song"), Key.DirectionDown)
        assertTrue(focused("Later Song"))
        press(compose.onNodeWithText("Later Song"), Key.DirectionDown)
        assertTrue(focused("Later Song"))
        press(compose.onNodeWithText("Later Song"), Key.DirectionUp)
        assertTrue(focused("Next Song"))
        press(compose.onNodeWithText("Next Song"), Key.DirectionCenter)
        assertEquals(listOf(7L), played)
        // Put away, the remote is back where it opened it.
        upNext.assertIsFocused()

        val sleep = compose.onNodeWithContentDescription(context.getString(R.string.player_sleep_timer))
        sleep.requestFocus()
        press(sleep, Key.DirectionCenter)
        compose.onNodeWithText(context.getString(R.string.player_sleep_minutes, 15)).performClick()
        compose.waitForIdle()
        assertEquals(listOf<Int?>(15), asked)
        sleep.assertIsFocused()
    }

    @Test
    fun theSleepTimerShowsTheMinutesLeftAndTurnsOff() {
        val asked = mutableListOf<Int?>()
        var off = 0
        player(ScreenActions(onPlayerSleep = { asked += it }, onPlayerSleepOff = { off++ }))
        val name = context.getString(R.string.player_sleep_timer)
        val minutes = { count: Int -> context.getString(R.string.player_sleep_minutes, count) }
        compose.onNodeWithContentDescription(name).performClick()
        compose.waitForIdle()
        // Off, there's nothing to turn off.
        none(hasText(context.getString(R.string.player_sleep_off)))
        compose.onNodeWithText(minutes(30)).performClick()
        compose.onNodeWithContentDescription(name).performClick()
        compose.onNodeWithText(context.getString(R.string.player_sleep_song_end)).performClick()
        assertEquals(listOf(30, null), asked)
        // Set, the minutes left show, up to the minute it's in.
        showing { it.copy(sleep = Sleep(SystemClock.elapsedRealtime() + 90_000)) }
        compose.onNode(hasContentDescription(name) and hasText(minutes(2))).assertExists()
        showing { it.copy(sleep = Sleep(SystemClock.elapsedRealtime() - 1_000)) }
        compose.onNode(hasContentDescription(name) and hasText(minutes(1))).assertExists()
        // Till the song ends, it's only filled in.
        showing { it.copy(sleep = Sleep()) }
        none(hasText(minutes(1)))
        compose.onNodeWithContentDescription(name).performClick()
        compose.onNodeWithText(context.getString(R.string.player_sleep_off)).performClick()
        assertEquals(1, off)
        // A song of no known length has no end to pause at.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(durationMs = null))) }
        compose.onNodeWithContentDescription(name).performClick()
        compose.waitForIdle()
        none(hasText(context.getString(R.string.player_sleep_song_end)))
    }

    @Test
    fun theLineBeingSungShowsUnderTheButtonsAndOpensTheWords() {
        var opened = 0
        player(ScreenActions(onPlayerLyrics = { opened++ }))
        val lyrics = context.getString(R.string.lyrics_button)
        // Words not found yet, it's only their button.
        compose.onNodeWithContentDescription(lyrics).performClick()
        assertEquals(1, opened)
        showing { it.copy(player = it.player!!.copy(lines = listOf(LyricLine(1_000, "One"), LyricLine(20_000, "Two"), LyricLine(40_000, "Three")))) }
        compose.onNodeWithText("Two").performClick()
        assertEquals(2, opened)
        none(hasText("One"))
        // Before the singing, the first.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(clock = PlaybackClock(0, 0, playing = false)))) }
        compose.onNodeWithText("One").assertExists()
        // A video's clock may be the video's, so it shows none.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(video = true))) }
        none(hasText("One"))
        compose.onNodeWithContentDescription(lyrics).assertExists()
        // Words without times show their first line; a blank one, nothing.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(video = false), lines = listOf(LyricLine(0, "Untimed")))) }
        compose.onNodeWithText("Untimed").assertExists()
        showing { it.copy(player = it.player!!.copy(lines = listOf(LyricLine(0, " ")))) }
        compose.onNodeWithContentDescription(lyrics).assertExists()
    }

    @Test
    fun theSongsNameOpensItsCard() {
        var cards = 0
        player(ScreenActions(onPlayerCard = { cards++ }))
        compose.onNodeWithText("Song").performClick()
        assertEquals(1, cards)
    }
}
