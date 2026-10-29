package com.astrovm.crosstune

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LayerSwipeTest {
    @get:Rule
    val compose = createComposeRule()

    private val said = mutableListOf<String>()
    private val swipe = LayerSwipe().apply {
        distance = 1000f
        flick = 500f
        up = Layer(Screen.PLAYER, { said += "open" }, back = { said += "close" })
        down = Layer(Screen.MAIN, { said += "dismiss" })
    }

    @Test
    fun aSwipeUpOpensWhatItReachesAsItStartsAndFollowsTheFinger() {
        assertEquals(-100f, swipe.drag(-100f, 0), 0.001f)
        // Opened already, so there's something to follow, as far as the finger has gone.
        assertEquals(listOf("open"), said)
        assertEquals(Screen.PLAYER, swipe.to)
        assertEquals(0.1f, swipe.fraction, 0.001f)
        assertTrue(swipe.swiping)
        swipe.release(0f)
        // Short of a third of the way, it goes back, and closes again what it opened.
        assertEquals(listOf("open", "close"), said)
        assertEquals(false, swipe.carryOn)
        assertFalse(swipe.swiping)
        swipe.finish()
        assertNull(swipe.to)
        assertEquals(0f, swipe.fraction, 0f)
    }

    @Test
    fun whatsDoneOnceThereWaitsForTheSwipeToGetThere() {
        swipe.up = Layer(Screen.PLAYER, { said += "open" }, back = { said += "close" }, settled = { said += "card" })
        swipe.drag(-100f, 0)
        swipe.release(0f)
        assertEquals(listOf("open", "close"), said)
        swipe.finish()
        swipe.drag(-350f, 0)
        swipe.release(0f)
        assertEquals(listOf("open", "close", "open", "card"), said)
    }

    @Test
    fun aSwipeUpLetGoPastAThirdOfTheWayCarriesOn() {
        swipe.drag(-350f, 0)
        swipe.release(0f)
        assertEquals(listOf("open"), said)
        assertEquals(true, swipe.carryOn)
    }

    @Test
    fun aSwipeDownDoesItsThingOnlyOnceLetGoPastAThirdOfTheWay() {
        assertEquals(200f, swipe.drag(200f, 0), 0.001f)
        assertEquals(Screen.MAIN, swipe.to)
        swipe.release(0f)
        assertEquals(emptyList<String>(), said)
        assertEquals(false, swipe.carryOn)
        swipe.finish()
        swipe.drag(400f, 0)
        swipe.release(0f)
        assertEquals(listOf("dismiss"), said)
        assertEquals(true, swipe.carryOn)
    }

    @Test
    fun aQuickFlickCarriesOnAndOneTheOtherWayGoesBack() {
        swipe.drag(50f, 0)
        swipe.release(800f)
        assertEquals(listOf("dismiss"), said)
        swipe.finish()
        said.clear()
        // Far along, but flicked back.
        swipe.drag(600f, 0)
        swipe.release(-800f)
        assertEquals(emptyList<String>(), said)
        assertEquals(false, swipe.carryOn)
        swipe.finish()
        // Up, a flick up is toward it.
        swipe.drag(-50f, 0)
        swipe.release(-800f)
        assertEquals(true, swipe.carryOn)
    }

    @Test
    fun aFlingTakesOverASwipeUnderWayAndLeavesAnyOtherBe() {
        assertEquals(0f, swipe.flung(900f), 0f)
        swipe.drag(-100f, 0)
        // Flicked up, it carries on without waiting for a third of the way.
        assertEquals(-900f, swipe.flung(-900f), 0f)
        assertEquals(true, swipe.carryOn)
    }

    @Test
    fun theFingerMovesOnlyAsFarAsThereIsToGo() {
        swipe.drag(900f, 0)
        // Only what's left of the way is taken, so what's left over scrolls.
        assertEquals(100f, swipe.drag(300f, 0), 0.001f)
        assertEquals(1f, swipe.fraction, 0f)
        assertEquals(-1000f, swipe.drag(-2000f, 0), 0.001f)
        assertEquals(0f, swipe.fraction, 0f)
    }

    @Test
    fun noWayThatDirectionOrOneAlreadyLetGoMeansNothingMoves() {
        swipe.up = null
        assertEquals(0f, swipe.drag(-100f, 0), 0f)
        assertNull(swipe.to)
        // Nothing started, so letting go has nothing to do.
        swipe.release(0f)
        assertNull(swipe.carryOn)
        swipe.drag(100f, 0)
        swipe.release(0f)
        // Going back on its own, the finger doesn't move it.
        assertEquals(0f, swipe.drag(100f, 0), 0f)
        assertEquals(0.1f, swipe.fraction, 0.001f)
    }

    @Test
    fun aSwipeBackFollowsTheFingerToWhereBackGoesAndOnlyGoesThereOnceLetGo() {
        swipe.backTo = Screen.MAIN
        assertTrue(swipe.backStart())
        assertEquals(Screen.MAIN, swipe.to)
        assertTrue(swipe.backing)
        swipe.backBy(0.4f)
        assertEquals(0.4f, swipe.fraction, 0f)
        swipe.backDone(done = false) { said += "back" }
        assertEquals(false, swipe.carryOn)
        // Let go, it's taken back without the finger.
        swipe.backBy(0.2f)
        assertEquals(0.4f, swipe.fraction, 0f)
        assertEquals(emptyList<String>(), said)
        swipe.finish()
        assertFalse(swipe.backing)
        swipe.backStart()
        swipe.backDone(done = true) { said += "back" }
        assertEquals(listOf("back"), said)
        assertEquals(true, swipe.carryOn)
    }

    @Test
    fun aSwipeBackIsntFollowedWhereItsNotKnownWhereBackGoesOrWhileAnotherSwipeMovesTheScreens() {
        assertFalse(swipe.backStart())
        assertNull(swipe.to)
        swipe.backTo = Screen.MAIN
        swipe.drag(-100f, 0)
        assertFalse(swipe.backStart())
        assertEquals(Screen.PLAYER, swipe.to)
        assertFalse(swipe.backing)
    }

    private lateinit var back: OnBackPressedDispatcher

    private fun edge(progress: Float) = BackEventCompat(10f, 500f, progress, BackEventCompat.EDGE_LEFT)

    private fun swipeBack(progress: Float) = compose.runOnIdle {
        back.dispatchOnBackStarted(edge(0f))
        back.dispatchOnBackProgressed(edge(progress))
    }

    @Test
    fun aScreensBackMovesTheScreensWithASwipeAndGoesBackOnceItsLetGo() {
        swipe.backTo = Screen.MAIN
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalLayerSwipe provides swipe) { SwipedBack { said += "back" } }
        }
        swipeBack(0.4f)
        compose.runOnIdle { assertEquals(0.4f, swipe.fraction, 0f) }
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.runOnIdle {
            assertEquals(false, swipe.carryOn)
            assertEquals(emptyList<String>(), said)
            swipe.finish()
        }
        swipeBack(0.6f)
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertEquals(listOf("back"), said)
            assertEquals(true, swipe.carryOn)
        }
    }

    @Test
    fun aScreensBackWhereItsNotKnownWhereItGoesOnlyGoesBack() {
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalLayerSwipe provides swipe) { SwipedBack { said += "back" } }
        }
        swipeBack(0.4f)
        compose.runOnIdle { assertNull(swipe.to) }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertEquals(listOf("back"), said) }
    }

    @Test
    fun aPageGoesOffWithASwipeBackAsFarAsItsGoneOverTheOneItGoesBackTo() {
        var shown by mutableStateOf<String?>("Page")
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            SwipedBackPages(shown, back = null, label = "pages", forward = { _, to -> to != null }, onBack = { shown = null }) { page ->
                Text(page ?: "List", Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText("List").assertDoesNotExist()
        val width = compose.onRoot().getBoundsInRoot().right
        swipeBack(0.5f)
        compose.waitForIdle()
        // Half way, it's half off, the list a little way out from under it.
        assertEquals(width / 2, compose.onNodeWithText("Page").getBoundsInRoot().left)
        assertEquals(-width / 10, compose.onNodeWithText("List").getUnclippedBoundsInRoot().left)
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.onNodeWithText("List").assertDoesNotExist()
        assertEquals(0.dp, compose.onNodeWithText("Page").getBoundsInRoot().left)
        swipeBack(0.5f)
        compose.runOnIdle { back.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Page").assertDoesNotExist()
        assertEquals(0.dp, compose.onNodeWithText("List").getUnclippedBoundsInRoot().left)
        // At the first page, Back is left to whatever's behind it.
        compose.runOnIdle { assertFalse(back.hasEnabledCallbacks()) }
    }
    @Test
    fun anInScreenModeFollowsCancelsClosesAndReopensWithoutKeepingItsOldSwipe() {
        var enabled by mutableStateOf(true)
        var mode by mutableStateOf("first")
        lateinit var away: () -> Float
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            away = swipedAway(enabled = enabled, shown = mode) { enabled = false; said += "closed" }
            if (enabled) Box(Modifier.size(200.dp).goingAway(away, TransformOrigin(0f, 0f)).testTag("mode"))
        }
        swipeBack(0.5f)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0.5f, away(), 0.001f) }
        val box = compose.onNodeWithTag("mode").getBoundsInRoot()
        assertEquals(190.dp, box.right - box.left)
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, away(), 0.001f) }
        assertTrue(said.isEmpty())
        swipeBack(0.5f)
        compose.runOnIdle { back.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("closed"), said)
        compose.onNodeWithTag("mode").assertDoesNotExist()
        compose.runOnIdle { assertFalse(back.hasEnabledCallbacks()); enabled = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, away(), 0.001f) }
        // A nested mode returning to its parent is a new view even while the panel stays open.
        swipeBack(0.4f)
        compose.waitForIdle()
        compose.runOnIdle { mode = "parent" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, away(), 0.001f); back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        // A button press of Back takes the same way out, without progress events.
        compose.runOnIdle { back.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("closed", "closed"), said)
    }

}
