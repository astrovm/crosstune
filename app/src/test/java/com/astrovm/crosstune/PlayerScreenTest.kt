package com.astrovm.crosstune

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import android.os.SystemClock
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
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
    fun shuffleOnHasADotUnderItAndNoBox() {
        var shuffles by mutableStateOf(false)
        compose.setContent {
            val following = Following(PlaybackClock(30_000, 0, playing = false), "YouTube Music", canSeek = true, durationMs = 200_000, shuffle = shuffles)
            CrosstuneTheme { PlayerScreen(UiState(canFollowApps = true, player = PlayerState(MusicMetadata("Song", "Band"), null, following = following)), ScreenActions()) }
        }
        val name = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.player_shuffle)
        val dp = compose.density.density
        // How much of a band of the button, [from] to [to] dp down it, is drawn on: its pixels far from
        // its edge on the same row, which is the ground behind it, as that fades from top to bottom.
        fun inked(from: Int, to: Int): Int {
            val bounds = compose.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val xs = bounds.left.toInt() until bounds.right.toInt()
            val ys = (bounds.top + from * dp).toInt() until (bounds.top + to * dp).toInt()
            fun light(pixel: Int) = android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
            return ys.sumOf { y -> xs.count { x -> kotlin.math.abs(light(image.getPixel(x, y)) - light(image.getPixel(xs.first, y))) > 150 } }
        }
        // Over the icon, and under it, where the dot goes.
        val above = 0 to 10
        val under = 37 to 46
        assertEquals(0, inked(under.first, under.second))
        shuffles = true
        compose.waitForIdle()
        assertTrue("Shuffle on has no dot under it", inked(under.first, under.second) > 0)
        assertEquals("Shuffle on is boxed in", 0, inked(above.first, above.second))
    }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private var shown by mutableStateOf(UiState())

    private lateinit var inputModes: InputModeManager

    private lateinit var back: OnBackPressedDispatcher

    /** The player on "Song" by "Band", paused 25 seconds into its 200, followed as [following] has it on top of that. */
    private fun player(actions: ScreenActions = ScreenActions(), following: (Following) -> Following? = { it }) {
        val paused = Following(PlaybackClock(25_000, 0, playing = false), "YouTube Music", canSeek = true, durationMs = 200_000)
        shown = UiState(canFollowApps = true, player = PlayerState(MusicMetadata("Song", "Band"), null, following = following(paused)))
        compose.setContent {
            inputModes = LocalInputModeManager.current
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CrosstuneTheme { PlayerScreen(shown, actions) }
        }
    }

    private fun showing(change: (UiState) -> UiState) {
        shown = change(shown)
        compose.waitForIdle()
    }

    private fun none(matcher: SemanticsMatcher) = assertTrue(compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty())

    /** A song tapped up next, on its way. */
    private val asked = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)

    private val putsAway = SemanticsMatcher("puts what plays next away") { it.config.getOrNull(SemanticsActions.OnClick)?.label == context.getString(R.string.dismiss_button) }

    /** What plays next's handle, which puts it away, rather than the player's own button that does. */
    private val handle = putsAway and hasContentDescription(context.getString(R.string.dismiss_button))

    /** The cover, small beside the song's name while what plays next is open. */
    private val smallCover = putsAway and SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)

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
        val later = mutableListOf<Int>()
        player(ScreenActions(onPlayerPlayQueued = { played += it }, onPlayerPlayLater = { later += it })) {
            it.copy(from = "Road trip", upNext = listOf(Queued("Next Song", "Band", 7), Queued("Later Song", "")))
        }
        val upNext = context.getString(R.string.player_up_next)
        compose.onNodeWithContentDescription("$upNext, Road trip").performClick()
        compose.waitForIdle()
        // Named over its songs, rather than up top, with the song playing first.
        compose.onNodeWithText(context.getString(R.string.player_playing_from)).assertExists()
        assertEquals(1, compose.onAllNodes(hasText("Road trip"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodes(hasText("Song"), useUnmergedTree = true).fetchSemanticsNodes().size)
        // A song the app has plays at a tap, the list staying open, with it on its way till it plays.
        none(asked)
        compose.onNodeWithText("Next Song").performClick()
        compose.waitForIdle()
        assertEquals(listOf(7L), played)
        compose.onNodeWithText("Next Song").assertExists()
        compose.onNode(asked).assertExists()
        // One still to ask for plays the list on from there.
        compose.onNodeWithText("Later Song").performClick()
        assertEquals(listOf(1), later)
        // Its handle puts it away, as does the small cover, and Back.
        compose.onNode(handle).performClick()
        compose.waitForIdle()
        none(hasText("Later Song"))
        compose.onNodeWithContentDescription("$upNext, Road trip").performClick()
        compose.waitForIdle()
        compose.onNode(smallCover).performClick()
        compose.waitForIdle()
        none(hasText("Later Song"))
        compose.onNodeWithContentDescription("$upNext, Road trip").performClick()
        compose.waitForIdle()
        compose.runOnIdle { back.onBackPressed() }
        compose.waitForIdle()
        none(hasText("Later Song"))
        // Not played from a list, it's only what's next.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(from = null))) }
        compose.onNodeWithContentDescription(upNext).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(upNext).assertExists()
        none(hasText(context.getString(R.string.player_playing_from)))
        compose.onNode(handle).performClick()
        compose.waitForIdle()
        // Played from a list, with nothing after it, it's only the list's name and the song playing.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(from = "Road trip", upNext = emptyList()))) }
        compose.onNodeWithContentDescription("$upNext, Road trip").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.player_playing_from)).assertExists()
        compose.onNode(handle).performClick()
        compose.waitForIdle()
        // With nothing after it, there's nothing to open.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(from = null))) }
        none(hasContentDescription(upNext))
        // Gone with the list while open, it stays gone once there's another.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(upNext = listOf(Queued("Next Song", "Band", 7))))) }
        compose.onNodeWithContentDescription(upNext).performClick()
        compose.waitForIdle()
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(upNext = emptyList()))) }
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(upNext = listOf(Queued("Next Song", "Band", 7))))) }
        none(hasText("Next Song"))
    }

    @Test
    fun pulledDownWhatPlaysNextFollowsTheFingerThenGoesAwayOrBack() {
        player { it.copy(from = "Road trip", upNext = listOf(Queued("Next Song", "Band", 7), Queued("Later Song", ""))) }
        val upNext = context.getString(R.string.player_up_next)
        val open = { compose.onNodeWithContentDescription("$upNext, Road trip").performClick(); compose.waitForIdle() }
        val name = compose.onNodeWithText(context.getString(R.string.player_playing_from))
        fun top() = compose.onNodeWithText("Next Song").fetchSemanticsNode().boundsInRoot.top
        // The list's name up top only shows as it goes, so it's on its way while there's two.
        fun going() = compose.onAllNodes(hasText("Road trip"), useUnmergedTree = true).fetchSemanticsNodes().size == 2
        open()
        val opened = top()
        // Held down by its name, it goes down with the finger, and the player with it.
        name.performTouchInput { down(center); moveBy(Offset(0f, 300f)) }
        compose.waitForIdle()
        assertTrue("It didn't follow the finger: ${top()}, against $opened", top() > opened + 50f)
        assertTrue(going())
        // Back up, it comes back up, and let go there, it stays.
        name.performTouchInput { moveBy(Offset(0f, -300f)); up() }
        compose.waitForIdle()
        assertEquals(opened, top(), 1f)
        assertTrue(!going())
        // Let go a little way down, slowly, it goes back.
        name.performTouchInput { swipeDown(startY = centerY, endY = centerY + 100f, durationMillis = 2_000) }
        compose.waitForIdle()
        assertEquals(opened, top(), 1f)
        // Far enough down, it carries on, however slow.
        name.performTouchInput { swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 6_000) }
        compose.waitForIdle()
        none(hasText("Next Song"))
        // Flicked down, it goes however little it moved.
        open()
        name.performTouchInput { swipeDown(startY = centerY, endY = centerY + 100f, durationMillis = 50) }
        compose.waitForIdle()
        none(hasText("Next Song"))
        // Down past the top of its songs, it's pulled too.
        open()
        compose.onNodeWithText("Next Song").performTouchInput { down(center); moveBy(Offset(0f, 300f)) }
        compose.waitForIdle()
        assertTrue(going())
        compose.onNodeWithText("Next Song").performTouchInput { moveBy(Offset(0f, 600f)); up() }
        compose.waitForIdle()
        none(hasText("Next Song"))
        // And pushed back up there, the songs have the finger again.
        open()
        compose.onNodeWithText("Next Song").performTouchInput { down(center); moveBy(Offset(0f, 200f)); moveBy(Offset(0f, -400f)); up() }
        compose.waitForIdle()
        assertEquals(opened, top(), 1f)
    }

    @Test
    fun aSwipeBackPutsWhatPlaysNextAwayAsItGoes() {
        player { it.copy(from = "Road trip", upNext = listOf(Queued("Next Song", "Band", 7))) }
        compose.onNodeWithContentDescription("${context.getString(R.string.player_up_next)}, Road trip").performClick()
        compose.waitForIdle()
        fun top() = compose.onNodeWithText("Next Song").fetchSemanticsNode().boundsInRoot.top
        val opened = top()
        fun swipe(progress: Float) = compose.runOnIdle { back.dispatchOnBackProgressed(BackEventCompat(10f, 500f, progress, BackEventCompat.EDGE_LEFT)) }
        compose.runOnIdle { back.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT)) }
        swipe(0.5f)
        compose.waitForIdle()
        assertTrue("It didn't follow the swipe: ${top()}, against $opened", top() > opened + 50f)
        // Taken back, it's back where it was, open.
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(opened, top(), 1f)
        compose.runOnIdle { back.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT)) }
        swipe(0.4f)
        compose.runOnIdle { back.onBackPressed() }
        compose.waitForIdle()
        none(hasText("Next Song"))
    }

    @Test
    fun songsUpNextMoveByTheirHandleOrAScreenReaderAmongTheirOwnKind() {
        val moved = mutableListOf<Pair<Int, Int>>()
        player(ScreenActions(onPlayerMoveQueued = { from, to -> moved += from to to })) {
            it.copy(upNext = listOf(Queued("Next Song", "Band", 7), Queued("Third Song", "Band", 8), Queued("Later Song", "")))
        }
        compose.onNodeWithContentDescription(context.getString(R.string.player_up_next)).performClick()
        compose.waitForIdle()
        val down = context.getString(R.string.player_move_down)
        val up = context.getString(R.string.player_move_up)
        fun moves(text: String) = compose.onNode(hasText(text)).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        fun order() = listOf("Next Song", "Third Song", "Later Song").sortedBy { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        // The first goes down only; one still to ask for can't go among the app's.
        assertEquals(listOf(down), moves("Next Song"))
        assertEquals(listOf(up), moves("Third Song"))
        assertEquals(emptyList<String>(), moves("Later Song"))
        compose.onNodeWithText("Next Song").performCustomAccessibilityActionWithLabel(down)
        compose.waitForIdle()
        assertEquals(listOf(0 to 1), moved)
        assertEquals(listOf("Third Song", "Next Song", "Later Song"), order())
        // By its handle, a song follows the finger to where it's let go.
        val rowHeight = compose.onNodeWithText("Next Song").fetchSemanticsNode().boundsInRoot.height + 30f
        val handles = compose.onAllNodesWithContentDescription(context.getString(R.string.player_move))
        handles[0].performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, rowHeight / 6)); advanceEventTime(50) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(0 to 1, 0 to 1), moved)
        assertEquals(listOf("Next Song", "Third Song", "Later Song"), order())
        // Not into one still to ask for.
        handles[1].performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, rowHeight / 6)); advanceEventTime(50) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(0 to 1, 0 to 1), moved)
        assertEquals(listOf("Next Song", "Third Song", "Later Song"), order())
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
        // further, as it ends there; then up to play one, the list staying open.
        assertTrue(focused("Next Song"))
        press(compose.onNodeWithText("Next Song"), Key.DirectionDown)
        assertTrue(focused("Later Song"))
        press(compose.onNodeWithText("Later Song"), Key.DirectionDown)
        assertTrue(focused("Later Song"))
        press(compose.onNodeWithText("Later Song"), Key.DirectionUp)
        assertTrue(focused("Next Song"))
        press(compose.onNodeWithText("Next Song"), Key.DirectionCenter)
        assertEquals(listOf(7L), played)
        assertTrue(focused("Next Song"))
        // Put away, the remote is back where it opened it.
        compose.runOnIdle { back.onBackPressed() }
        compose.waitForIdle()
        upNext.assertIsFocused()

        val sleep = compose.onNodeWithContentDescription(context.getString(R.string.player_sleep_timer))
        sleep.requestFocus()
        press(sleep, Key.DirectionCenter)
        compose.onNodeWithText(context.getString(R.string.player_sleep_minutes, 15)).performClick()
        compose.waitForIdle()
        assertEquals(listOf<Int?>(15), asked)
        sleep.assertIsFocused()

        // Opened from the list's name up top, it's back there.
        showing { it.copy(player = it.player!!.copy(following = it.player.following!!.copy(from = "Road trip"))) }
        val list = compose.onNodeWithContentDescription("${context.getString(R.string.player_up_next)}, Road trip")
        list.requestFocus()
        press(list, Key.DirectionCenter)
        assertTrue(focused("Next Song"))
        compose.runOnIdle { back.onBackPressed() }
        compose.waitForIdle()
        list.assertIsFocused()
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
        // Between verses, the last one sung stays up.
        showing { it.copy(player = it.player!!.copy(lines = listOf(LyricLine(1_000, "One"), LyricLine(20_000, ""), LyricLine(40_000, "Three")))) }
        compose.onNodeWithText("One").assertExists()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp")
    fun widerThanTallTheCoverSitsBesideTheRest() {
        player()
        val cover = compose.onNodeWithTag(PLAYER_COVER_TAG).fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Song").fetchSemanticsNode().boundsInRoot
        assertTrue("The cover isn't beside the title: $cover, $title", cover.right <= title.left)
    }

    @Test
    @Config(qualifiers = "w411dp-h420dp")
    fun withTooLittleRoomTheCoverGivesWayToTheButtons() {
        player()
        none(hasTestTag(PLAYER_COVER_TAG))
        compose.onNodeWithContentDescription(context.getString(R.string.lyrics_play)).assertIsDisplayed()
    }

    @Test
    fun theSongsNameOpensItsCard() {
        var cards = 0
        player(ScreenActions(onPlayerCard = { cards++ }))
        compose.onNodeWithText("Song").performClick()
        assertEquals(1, cards)
    }
}
