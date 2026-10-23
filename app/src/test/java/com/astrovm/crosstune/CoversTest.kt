package com.astrovm.crosstune

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** A cover shown again is there at once, in its color, rather than fading in each time. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoversTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fake = FakeSpotify()

    private fun coverShown() = composeRule.onAllNodesWithTag(ARTWORK_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aCoverShownAgainIsThereOnTheFirstFrameInItsColor() {
        fake.handler = { request -> FakeSpotify.image(request, FakeSpotify.png()) }
        val loader = ArtworkLoader(fake.client(), Dispatchers.Unconfined)
        var shown by mutableStateOf(true)
        var color: Color? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalCovers provides loader) {
                if (shown) {
                    CoverArt(COVER, loader::load, size = 48.dp)
                    color = coverColor(COVER, loader::load)
                }
            }
        }
        composeRule.waitUntil(5_000) { color != null && coverShown() }
        val first = color
        val asked = fake.requestedUrls.size
        shown = false
        composeRule.waitForIdle()
        assertTrue(!coverShown())

        // Back, it's drawn with its color on the very first frame, before anything's loaded.
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            color = null
            shown = true
            // Told now, rather than whenever Compose gets round to it, so the next frame is the first.
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        assertNotNull(first)
        assertEquals(first, color)
        assertTrue(coverShown())
        assertEquals(asked, fake.requestedUrls.size)
    }

    private companion object {
        const val COVER = "https://img.example/cover.jpg"
    }
}
