package com.astrovm.crosstune

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class CastButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun castingLightsTheConnectedIconAndNamesTheTvWhileKeepingTheButtonClickable() {
        var to by mutableStateOf<String?>(null)
        var taps = 0
        compose.setContent {
            CrosstuneTheme {
                Row {
                    CastButton(UiState(castingTo = to), { taps++ }, Modifier.size(64.dp).testTag("actual"))
                    IconButton({}, Modifier.size(64.dp).testTag("expected")) {
                        if (to == null) AppIcon(R.drawable.ic_cast, null, tint = LocalContentColor.current)
                        else AppIcon(R.drawable.ic_cast_connected, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        fun pixels(tag: String): IntArray {
            val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        }
        compose.onNodeWithTag("actual").assertWidthIsEqualTo(64.dp).assertHeightIsEqualTo(64.dp)
        assertArrayEquals(pixels("expected"), pixels("actual"))
        compose.onNodeWithTag("actual").performClick()
        compose.runOnIdle { to = "Living room" }
        compose.onNodeWithContentDescription("Casting to Living room").assertExists()
        assertArrayEquals(pixels("expected"), pixels("actual"))
        compose.onNodeWithTag("actual").performClick()
        assertEquals(2, taps)
    }
    @Test
    fun theSongCardUsesTheConnectedIconOnlyWhileCasting() {
        var to by mutableStateOf<String?>(null)
        var primary = 0
        compose.setContent {
            CrosstuneTheme {
                primary = MaterialTheme.colorScheme.primary.toArgb()
                Box {
                    CrosstuneScreen(UiState(result = MusicMetadata("Song", "Artist"), castAround = true, castingTo = to), ScreenActions())
                    AppIcon(if (to == null) R.drawable.ic_cast else R.drawable.ic_cast_connected, null,
                        Modifier.size(22.dp).testTag("reference"), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        for (name in listOf(null, "Living room")) {
            compose.runOnIdle { to = name }
            val actual = compose.onNodeWithContentDescription(if (name == null) "Cast to a TV" else "Casting to Living room")
                .captureToImage().asAndroidBitmap()
            val expected = compose.onNodeWithTag("reference").captureToImage().asAndroidBitmap()
            val left = (actual.width - expected.width) / 2
            val top = with(compose.density) { 8.dp.roundToPx() }
            val expectedMask = BooleanArray(expected.width * expected.height) { index ->
                expected.getPixel(index % expected.width, index / expected.width) == primary
            }
            val actualMask = BooleanArray(expectedMask.size) { index ->
                actual.getPixel(left + index % expected.width, top + index / expected.width) == primary
            }
            org.junit.Assert.assertTrue("Reference has visible ink", expectedMask.any { it })
            org.junit.Assert.assertTrue("The card shows the expected Cast icon", expectedMask.contentEquals(actualMask))
        }
    }

}
