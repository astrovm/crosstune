package com.astrovm.crosstune

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.view.children
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What a screen asks of the visuals reaches what draws them, each time it asks. */
@RunWith(RobolectricTestRunner::class)
class MilkdropVisualsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeMilkdrop : Milkdrop {
        var shows = 0
        val darkened = mutableListOf<Pair<Float, Float>>()

        override fun open(width: Int, height: Int) = true
        override fun size(width: Int, height: Int) = Unit
        override fun show(preset: String, smooth: Boolean): Boolean {
            shows++
            return true
        }
        override fun hear(samples: ByteArray, count: Int) = Unit
        override fun draw() = Unit
        override fun darken(shade: Float, ceiling: Float) {
            darkened += shade to ceiling
        }
        override fun finish() = Unit
        override fun close() = Unit
    }

    private val milkdrop = FakeMilkdrop()
    private var play by mutableStateOf(VisualsPlay())

    @Before
    fun fakeTheVisuals() {
        MainActivity.milkdropFactory = { milkdrop }
        MainActivity.soundTapFactory = { null }
    }

    @After
    fun putTheVisualsBack() {
        MainActivity.milkdropFactory = ::NativeMilkdrop
        MainActivity.soundTapFactory = MainActivity.phoneSound::open
    }

    private fun View.visuals(): MilkdropView? =
        this as? MilkdropView ?: (this as? ViewGroup)?.children?.firstNotNullOfOrNull { it.visuals() }

    /** The renderer behind the visuals on screen, started and past its first frame, as the GL thread would have it. */
    private fun drawing(): MilkdropRenderer {
        lateinit var root: View
        composeRule.setContent {
            root = LocalView.current
            MilkdropVisuals(play)
        }
        composeRule.waitForIdle()
        val renderer = checkNotNull(root.visuals()).renderer
        renderer.created()
        renderer.frame()
        return renderer
    }

    @Test
    fun askingForTheNextVisualMovesOn() {
        val renderer = drawing()
        assertEquals(1, milkdrop.shows)
        play = play.copy(skips = 1)
        composeRule.waitForIdle()
        renderer.frame()
        assertEquals(2, milkdrop.shows)
    }

    @Test
    fun droppingAVisualMovesOn() {
        val renderer = drawing()
        assertEquals(1, milkdrop.shows)
        play = play.copy(drops = 1)
        composeRule.waitForIdle()
        renderer.frame()
        assertEquals(2, milkdrop.shows)
    }

    @Test
    fun aNewShadeAndCeilingDarkenTheNextFrame() {
        val renderer = drawing()
        play = play.copy(shade = 0.6f, ceiling = 0.3f)
        composeRule.waitForIdle()
        renderer.frame()
        assertEquals(0.6f to 0.3f, milkdrop.darkened.last())
    }

    @Test
    fun newSettingsReachWhatDraws() {
        val renderer = drawing()
        val settings = VisualsSettings(changeMs = 12_345)
        play = play.copy(settings = settings)
        composeRule.waitForIdle()
        assertEquals(settings, renderer.settings)
    }
}
