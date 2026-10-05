package com.astrovm.crosstune

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What listening looks like: waves going out from the microphone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ListeningScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun wavesGoOutFromTheMicrophone() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { CrosstuneTheme { ListeningScreen(ScreenActions()) } }
        // Partway out, the first wave is past the microphone's circle.
        composeRule.mainClock.advanceTimeBy(1_600)
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val density = composeRule.density.density
        // The waves' box is 220dp tall and ends 24dp above the text; this is 80dp above its center.
        val textTop = composeRule.onNodeWithText(context.getString(R.string.listening_text)).getBoundsInRoot().top.value
        val waveY = ((textTop - 24 - 110 - 80) * density).toInt()
        val centerX = image.width / 2
        val background = image.getPixel(10, waveY)
        assertNotEquals(background, image.getPixel(centerX, waveY))
    }
}
