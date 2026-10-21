package com.astrovm.crosstune

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
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
class CardLayerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aHiddenLayerIsOutlinedWithDashes() {
        compose.setContent {
            CrosstuneTheme {
                Box(Modifier.size(360.dp)) {
                    CardSideEditor(SavedLine("夜の空", title = "Demo", artist = "Band"), CardSide(translation = false), onPlay = {}, canPlay = false, onChange = {})
                }
            }
        }
        val layer = compose.onNodeWithTag("card-layer-${R.string.lyrics_translation}").captureToImage().asAndroidBitmap()
        // Down the straight part of the left edge, a dashed outline comes and goes.
        val edge = (12 until layer.height - 12).map { layer.getPixel(0, it) }.toSet()
        assertTrue("A hidden layer's outline has gaps in it", edge.size > 1)
    }
}
