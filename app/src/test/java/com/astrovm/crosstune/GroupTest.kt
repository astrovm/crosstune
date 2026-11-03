package com.astrovm.crosstune

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
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
class GroupTest {
    @get:Rule
    val compose = createComposeRule()

    /** Where, from the group's top in dp, a hairline crosses its middle, top to bottom; and whether one crosses near its side. */
    private fun hairlines(): Pair<List<Int>, Boolean> {
        val image = compose.onNodeWithTag("group").captureToImage().asAndroidBitmap()
        val dp = compose.density.density
        val middle = image.width / 2
        val background = image.getPixel(middle, (5 * dp).toInt())
        val lines = (0 until image.height).filter { image.getPixel(middle, it) != background }
        val starts = lines.filterIndexed { index, y -> index == 0 || lines[index - 1] != y - 1 }.map { (it / dp).toInt() }
        val nearSide = lines.any { image.getPixel((10 * dp).toInt(), it) != background }
        return starts to nearSide
    }

    @Test
    fun aGroupHasAHairlineBetweenEachTwoRowsAndNoneAtItsEdgesOrForARowThatTakesNoRoom() {
        var middle by mutableStateOf(true)
        compose.setContent {
            CrosstuneTheme {
                Group(Modifier.width(200.dp).testTag("group")) {
                    Box(Modifier.fillMaxWidth().height(10.dp))
                    // One that takes no room, as a row with nothing to show, is no row.
                    Box(Modifier.fillMaxWidth().height(0.dp))
                    if (middle) Box(Modifier.fillMaxWidth().height(10.dp))
                    Box(Modifier.fillMaxWidth().height(10.dp))
                }
            }
        }
        compose.onNodeWithTag("group").assertHeightIsEqualTo(32.dp)
        // Starting 20dp in from each side, as the rows' words do.
        assertEquals(listOf(10, 21) to false, hairlines())
        // A row gone takes its hairline with it.
        middle = false
        compose.waitForIdle()
        compose.onNodeWithTag("group").assertHeightIsEqualTo(21.dp)
        assertEquals(listOf(10) to false, hairlines())
    }

    @Test
    fun aGroupOfOneRowHasNoHairline() {
        compose.setContent { CrosstuneTheme { Group(Modifier.width(200.dp).testTag("group")) { Box(Modifier.fillMaxWidth().height(10.dp)) } } }
        compose.onNodeWithTag("group").assertHeightIsEqualTo(10.dp)
        assertEquals(emptyList<Int>() to false, hairlines())
    }
}
