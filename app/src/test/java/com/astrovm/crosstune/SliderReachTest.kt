package com.astrovm.crosstune

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The app's slider takes a finger anywhere in a row 48 dp tall around its track, as Android asks of anything tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SliderReachTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var value by mutableFloatStateOf(0f)

    /** Taps the slider [off] dp above or below its track's middle, three quarters along, and returns where it's slid to. */
    private fun tapped(off: Float): Float {
        value = 0f
        composeRule.waitForIdle()
        val track = composeRule.onNodeWithTag("slider").getBoundsInRoot()
        composeRule.onRoot().performTouchInput {
            val x = (track.left + (track.right - track.left) * 0.75f).toPx()
            val y = ((track.top + track.bottom) / 2 + off.dp).toPx()
            click(Offset(x, y))
        }
        composeRule.waitForIdle()
        return value
    }

    @Test
    fun aTapWithin24DpOfTheTrackSlidesIt() {
        composeRule.setContent {
            CrosstuneTheme {
                Column {
                    Spacer(Modifier.height(100.dp))
                    Box { AppSlider(value, { value = it }, Modifier.testTag("slider").width(300.dp)) }
                    Spacer(Modifier.height(100.dp))
                }
            }
        }
        // Just inside both edges of the 48 dp target, rather than only its central 44 dp.
        for (off in listOf(0f, -23.9f, 23.9f)) assertEquals("tapped $off dp from the track", 0.75f, tapped(off), 0.05f)
        // Further out, the tap is the page's.
        assertEquals(0f, tapped(40f), 0f)
    }
}
