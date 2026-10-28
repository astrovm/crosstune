package com.astrovm.crosstune

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
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
class PlayerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun timesReadAsAClockDoes() {
        assertEquals("0:00", clockText(0))
        assertEquals("0:00", clockText(-500))
        assertEquals("0:09", clockText(9_999))
        assertEquals("3:07", clockText(187_000))
        assertEquals("59:59", clockText(3_599_000))
        assertEquals("1:02:07", clockText(3_727_000))
    }

    @Test
    fun shuffleSitsLeftOfPreviousAsMusicAppsHaveIt() {
        compose.setContent {
            val following = Following(PlaybackClock(30_000, 0, playing = false), "YouTube Music", canSeek = true, durationMs = 200_000, shuffle = false)
            CrosstuneTheme { PlayerScreen(UiState(canFollowApps = true, player = PlayerState(MusicMetadata("Song", "Band"), null, following = following)), ScreenActions()) }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun bounds(name: Int) = compose.onNodeWithContentDescription(context.getString(name)).fetchSemanticsNode().boundsInRoot
        val shuffle = bounds(R.string.player_shuffle)
        val previous = bounds(R.string.lyrics_previous_song)
        assertTrue("Shuffle isn't left of previous: $shuffle, $previous", shuffle.right <= previous.left)
        assertEquals(previous.center.y, shuffle.center.y, 1f)
    }

    @Test
    fun shuffleOnIsBoxedIn() {
        var shuffles by mutableStateOf(false)
        compose.setContent {
            val following = Following(PlaybackClock(30_000, 0, playing = false), "YouTube Music", canSeek = true, durationMs = 200_000, shuffle = shuffles)
            CrosstuneTheme { PlayerScreen(UiState(canFollowApps = true, player = PlayerState(MusicMetadata("Song", "Band"), null, following = following)), ScreenActions()) }
        }
        val name = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.player_shuffle)
        // How much of the button is drawn on: its pixels far from its edge on the same row, which is
        // the ground behind it, as that fades from top to bottom, dithered.
        fun inked(): Int {
            val bounds = compose.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val xs = bounds.left.toInt() until bounds.right.toInt()
            val ys = bounds.top.toInt() until bounds.bottom.toInt()
            fun light(pixel: Int) = android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
            return ys.sumOf { y -> xs.count { x -> kotlin.math.abs(light(image.getPixel(x, y)) - light(image.getPixel(xs.first, y))) > 150 } }
        }
        val off = inked()
        shuffles = true
        compose.waitForIdle()
        // A color alone is too faint beside the times; on, it's a box, far more of it drawn.
        val on = inked()
        assertTrue("Shuffle on isn't boxed in: $on pixels drawn, against $off off", on > off * 2)
    }
}
