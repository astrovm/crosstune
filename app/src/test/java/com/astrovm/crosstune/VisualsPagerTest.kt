package com.astrovm.crosstune

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Swiping through the visuals in Settings, each playing in turn with its frames a second. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class VisualsPagerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val names = listOf("Alpha", "Beta", "Gamma")
    private val played = mutableListOf<String>()
    private val turned = mutableListOf<Pair<String, Boolean>>()

    private fun string(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    /** Opened at Beta, each one played telling it draws [fps] frames a second. */
    private fun swipeThrough(fps: Int, settings: VisualsSettings = VisualsSettings()) {
        composeRule.setContent {
            CrosstuneTheme {
                CompositionLocalProvider(LocalVisuals provides { play, _ ->
                    LaunchedEffect(play.only) {
                        played += play.only.orEmpty()
                        play.onFps(fps)
                    }
                }) {
                    VisualsPager(names, start = 1, settings, canTurnOff = { true }, ScreenActions(onVisualOnChange = { name, on -> turned += name to on }), onBack = {})
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun itOpensAtTheOneTappedAndSwipesOnToTheNextWithTheSwitchForTheOneInView() {
        swipeThrough(fps = 62)
        assertTrue(composeRule.onAllNodes(hasText("Beta")).fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithText(string(R.string.visuals_position, 2, 3)).assertExists()
        composeRule.onNodeWithText(string(R.string.visual_fps, 62)).assertExists()
        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.visuals_position, 3, 3)).assertExists()
        assertEquals(listOf("Beta", "Gamma"), played)
        composeRule.onNode(isToggleable()).performClick()
        assertEquals(listOf("Gamma" to false), turned)
    }

    @Test
    fun oneTooHeavySaysSoAndHowFewFramesItDraws() {
        swipeThrough(fps = 31, settings = VisualsSettings(heavy = setOf("Beta")))
        composeRule.onNodeWithText(string(R.string.visual_fps, 31)).assertExists()
        composeRule.onNodeWithText(string(R.string.visual_heavy)).assertExists()
        // Off as too heavy, it's switched on from here.
        composeRule.onNode(isToggleable()).performClick()
        assertEquals(listOf("Beta" to true), turned)
    }
}
