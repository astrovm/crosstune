package com.astrovm.crosstune

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AppSliderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aSliderInStepsHasNoDotsAlongItOrAtItsEnd() {
        compose.setContent {
            CrosstuneTheme { AppSlider(value = 0f, onValueChange = {}, valueRange = 0f..4f, steps = 3, modifier = Modifier.width(300.dp).testTag("slider")) }
        }
        val image = compose.onNodeWithTag("slider").captureToImage().asAndroidBitmap()
        val dp = compose.density.density
        // Along the middle of the track, from past the knob at its start to its very end, where Android
        // puts a dot for each step and one to end it.
        val along = (24 * dp).toInt() until (290 * dp).toInt()
        val y = image.height / 2
        val track = image.getPixel(along.first, y)
        assertEquals(emptyList<Int>(), along.filter { image.getPixel(it, y) != track })
    }
}
