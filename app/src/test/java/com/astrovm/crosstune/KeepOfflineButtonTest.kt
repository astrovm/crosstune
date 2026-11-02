package com.astrovm.crosstune

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/** The button under a song, album or playlist that keeps it for offline: who gets it, and how it looks as it's kept. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2000dp")
class KeepOfflineButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var state by mutableStateOf(UiState())
    private lateinit var colors: ColorScheme

    @Before
    fun setUp() {
        composeRule.setContent {
            CrosstuneTheme {
                colors = MaterialTheme.colorScheme
                CrosstuneScreen(state, ScreenActions())
            }
        }
    }

    private fun string(id: Int) = app.getString(id)

    private val song = MusicMetadata("Song", "Band", url = "https://open.spotify.com/track/1")
    private val others = listOf(MusicMetadata("Two", "Band"), MusicMetadata("Three", "Band"))
    private val playlist = MusicMetadata("Mix", "", ItemType.PLAYLIST, url = "https://open.spotify.com/playlist/9", tracks = listOf(song) + others)

    private fun kept(item: MusicMetadata, saved: List<MusicMetadata> = emptyList()) =
        KeptOffline(OfflineSongs.idOf(item), item.copy(tracks = emptyList()), item.tracks.ifEmpty { listOf(item) }, saved.map(OfflineSongs::keyOf).toSet())

    private fun button(description: Int) = composeRule.onNodeWithContentDescription(string(description))

    private fun label(text: String, description: Int) = composeRule.onNode(
        hasText(text) and hasAnyAncestor(hasContentDescription(string(description))),
        useUnmergedTree = true
    )

    @Test
    fun onlyASongWithAnArtistOrAListOfSongsCanBeKept() {
        state = UiState(result = song)
        button(R.string.keep_offline).assertExists()
        // Without an artist it can't be looked up safely.
        state = UiState(result = MusicMetadata("Song", ""))
        button(R.string.keep_offline).assertDoesNotExist()
        state = UiState(result = playlist)
        button(R.string.keep_offline).assertExists()
        // A list whose songs aren't known has nothing to keep, whoever made it.
        state = UiState(result = MusicMetadata("Album", "Band", ItemType.ALBUM))
        button(R.string.keep_offline).assertDoesNotExist()
    }

    @Test
    fun aListBeingSavedSaysHowManyOfItsSongsAreAndASongJustSaysSo() {
        val download = string(R.string.keep_offline_label)
        state = UiState(result = song)
        label(download, R.string.keep_offline).assertExists()

        // One song waiting has no count to give; the screen reader hears it's being saved.
        state = UiState(result = song, keptOffline = listOf(kept(song)))
        label(download, R.string.keeping_offline).assertExists()
        composeRule.onNodeWithText("0/1", useUnmergedTree = true).assertDoesNotExist()

        state = UiState(result = song, keptOffline = listOf(kept(song, listOf(song))))
        label(download, R.string.kept_offline).assertExists()

        state = UiState(result = playlist)
        label(download, R.string.keep_offline).assertExists()
        state = UiState(result = playlist, keptOffline = listOf(kept(playlist, listOf(song))))
        label("1/3", R.string.keeping_offline).assertExists()
        state = UiState(result = playlist, keptOffline = listOf(kept(playlist, listOf(song) + others.take(1))))
        label("2/3", R.string.keeping_offline).assertExists()
        // All of them saved, the count is done with.
        state = UiState(result = playlist, keptOffline = listOf(kept(playlist, listOf(song) + others)))
        label(download, R.string.kept_offline).assertExists()
        composeRule.onNodeWithText("3/3", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theIconIsInTheErrorColorOnlyWhenOutOfRoom() {
        state = UiState(result = song, keptOffline = listOf(kept(song)))
        assertTrue(inkNear(colors.primary, R.string.keeping_offline) > MIN_INK)
        assertTrue(inkNear(colors.error, R.string.keeping_offline) == 0)
        // No room left to fetch it.
        state = state.copy(offlineSpaceMb = 0)
        assertTrue(inkNear(colors.error, R.string.offline_out_of_space) > MIN_INK)
        assertTrue(inkNear(colors.primary, R.string.offline_out_of_space) == 0)
        // Kept already, nothing's left to fetch, so room doesn't matter.
        state = UiState(result = song, keptOffline = listOf(kept(song, listOf(song))), offlineSpaceMb = 0)
        assertTrue(inkNear(colors.primary, R.string.kept_offline) > MIN_INK)
        assertTrue(inkNear(colors.error, R.string.kept_offline) == 0)
    }

    @Test
    fun theIconIsAPinOnceKeptAndADownloadBefore() {
        // The pin covers more of the button than the download does; kept, the button fills out so.
        val pinnedMore = ink(R.drawable.ic_offline_pin) - ink(R.drawable.ic_download_for_offline)
        assertTrue("The two icons need to look different to tell apart", abs(pinnedMore) > 40)
        state = UiState(result = song)
        val before = inkNear(colors.primary, R.string.keep_offline)
        state = UiState(result = song, keptOffline = listOf(kept(song)))
        assertTrue(inkNear(colors.primary, R.string.keeping_offline) == before)
        state = UiState(result = song, keptOffline = listOf(kept(song, listOf(song))))
        assertTrue("${inkNear(colors.primary, R.string.kept_offline)} after, $before before, $pinnedMore by the icons", (inkNear(colors.primary, R.string.kept_offline) - before) * pinnedMore > 0)
    }

    /** How many pixels of the button are [color], which only its icon is drawn in. */
    private fun inkNear(color: Color, description: Int): Int {
        val image = button(description).captureToImage().asAndroidBitmap()
        val want = color.toArgb()
        fun close(a: Int, shift: Int) = abs(((a shr shift) and 0xFF) - ((want shr shift) and 0xFF)) <= TOLERANCE
        return (0 until image.height).sumOf { y -> (0 until image.width).count { x -> image.getPixel(x, y).let { close(it, 16) && close(it, 8) && close(it, 0) } } }
    }

    /** How many pixels [icon] covers at the size the button draws it. */
    private fun ink(icon: Int): Int {
        val size = (ICON_DP * app.resources.displayMetrics.density).roundToInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        app.getDrawable(icon)!!.apply { setBounds(0, 0, size, size) }.draw(Canvas(bitmap))
        return (0 until size).sumOf { y -> (0 until size).count { x -> bitmap.getPixel(x, y) ushr 24 > 128 } }
    }

    private companion object {
        const val ICON_DP = 22
        const val TOLERANCE = 6
        const val MIN_INK = 20
    }
}
