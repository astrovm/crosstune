package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LayerSwipeTest {
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
}
