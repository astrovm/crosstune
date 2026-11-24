package com.astrovm.crosstune

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * The flat tools under a song's main button, in every language the app speaks, at the largest fonts,
 * on a narrow phone, a common one and a TV: each in sight, and each word of its label whole.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardToolsFitTest(private val language: String, private val widthDp: Int, private val fontScale: Float, private val tv: Boolean) {

    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val song = MusicMetadata("Song", "Band", url = "https://open.spotify.com/track/1")
    private val songs = MusicMetadata("Mix", "", ItemType.PLAYLIST, url = "https://open.spotify.com/playlist/9", tracks = listOf(song, MusicMetadata("Two", "Band")))

    @Test
    fun everyToolIsInSightWithItsWordsWhole() {
        RuntimeEnvironment.setQualifiers("$language-w${widthDp}dp-h${if (tv) 540 else 900}dp")
        RuntimeEnvironment.setFontScale(fontScale)
        val phone = if (tv) Phone.TV else Phone.OTHER
        var shown by mutableStateOf(UiState(result = song, phone = phone))
        lateinit var measurer: TextMeasurer
        lateinit var style: TextStyle
        composeRule.setContent {
            CrosstuneTheme {
                measurer = rememberTextMeasurer()
                style = MaterialTheme.typography.labelMedium
                CrosstuneScreen(shown, ScreenActions())
            }
        }
        check(measurer, style, shown)
        // A list YouTube Music plays shuffled has the most tools there are.
        shown = UiState(result = songs, phone = phone)
        assertTrue(shown.canPlayAll)
        check(measurer, style, shown)
    }

    @Test
    fun theMainButtonKeepsItsWordWholeAndItsIconsInSight() {
        RuntimeEnvironment.setQualifiers("$language-w${widthDp}dp-h${if (tv) 540 else 900}dp")
        RuntimeEnvironment.setFontScale(fontScale)
        val phone = if (tv) Phone.TV else Phone.OTHER
        val playable = UiState(result = song, phone = phone, installed = setOf(MusicService.YOUTUBE_MUSIC))
        var shown by mutableStateOf(playable)
        lateinit var measurer: TextMeasurer
        lateinit var style: TextStyle
        composeRule.setContent {
            CrosstuneTheme {
                measurer = rememberTextMeasurer()
                style = MaterialTheme.typography.labelLarge
                CrosstuneScreen(shown, ScreenActions())
            }
        }
        val app = string(R.string.target_youtube_music)
        val play = string(R.string.play_in_background, app)
        val search = string(R.string.search_in_destination, app)
        val open = string(R.string.open_in_youtube_music)
        // Play with a search and an open beside it, then a search first, then a song that only opens, with its search.
        checkMain(measurer, style, string(R.string.lyrics_play) to play, listOf(search, open))
        shown = playable.copy(playRightAway = false)
        checkMain(measurer, style, string(R.string.search_button) to search, listOf(play, open))
        shown = UiState(result = song, phone = phone)
        checkMain(measurer, style, string(R.string.open_button) to open, listOf(search))
    }

    private fun checkMain(measurer: TextMeasurer, style: TextStyle, main: Pair<String, String>, icons: List<String>) {
        composeRule.waitForIdle()
        val width = composeRule.onRoot().getBoundsInRoot().right
        val (word, description) = main
        val button = composeRule.onNode(hasContentDescription(description)).performScrollTo().getBoundsInRoot()
        // Beside the icons, the button has room for its word, its icon and its own room. Short of that, it has the whole
        // width, with the icons under it.
        val needs = measurer.measure(word, style).size.width + with(composeRule.density) { (24.dp + 8.dp + 48.dp).roundToPx() }
        val room = with(composeRule.density) { (button.right - button.left).roundToPx() }
        val whole = with(composeRule.density) { (width - 32.dp).roundToPx() }
        for (icon in icons) {
            val bounds = composeRule.onNode(hasContentDescription(icon)).performScrollTo().assertIsDisplayed().getBoundsInRoot()
            assertTrue("$icon ends at ${bounds.right} of $width", bounds.left.value >= 0f && bounds.right <= width)
            assertTrue("$icon is ${bounds.right - bounds.left} wide", bounds.right - bounds.left >= 48.dp)
            val beside = bounds.top < composeRule.onNode(hasContentDescription(description)).getBoundsInRoot().bottom
            assertTrue("\"$word\" needs $needs px, its button is $room px of $whole, beside $icon: $beside", if (beside) room >= needs else room >= whole)
        }
    }

    /** Each tool by what a screen reader hears, with the label it shows. */
    private fun tools(state: UiState): List<Pair<String, String>> {
        val list = !state.result?.tracks.isNullOrEmpty()
        return buildList {
            if (!list) add(string(R.string.lyrics_button) to string(R.string.lyrics_button))
            if (state.canPlayAll) add(string(R.string.shuffle_all_in, string(R.string.target_youtube_music)) to string(R.string.shuffle_button))
            add(string(if (list) R.string.copy_songs_button else R.string.copy_link_button) to string(R.string.copy_button))
            add(string(if (list) R.string.share_songs_button else R.string.share_link_button) to string(R.string.share_button))
            add(string(R.string.keep_offline) to string(R.string.keep_offline_label))
        }
    }

    private fun check(measurer: TextMeasurer, style: TextStyle, state: UiState) {
        composeRule.waitForIdle()
        val width = composeRule.onRoot().getBoundsInRoot().right
        for ((description, label) in tools(state)) {
            val node = composeRule.onNode(hasContentDescription(description) or (hasText(description) and hasClickAction()))
            node.performScrollTo().assertIsDisplayed()
            val bounds = node.getBoundsInRoot()
            assertTrue("$description ends at ${bounds.right} of $width", bounds.left.value >= 0f && bounds.right <= width)
            // A label wraps between its words, if at all: never a word broken, a letter or two a line.
            val widest = label.split(' ').maxOf { word -> measurer.measure(word, style).size.width }
            val room = with(composeRule.density) { (bounds.right - bounds.left).roundToPx() }
            assertTrue("\"$label\" needs $widest px for its longest word, and its tool is $room px wide", room >= widest)
        }
    }

    private fun string(id: Int, vararg args: Any) = app.getString(id, *args)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}dp x{2} tv={3}")
        fun cases(): List<Array<Any>> {
            val languages = listOf("en", "es", "pt-rBR", "de", "fr", "it", "nl", "pl", "ru", "tr", "in", "hi", "ja", "ko", "zh-rCN", "zh-rTW")
            val phones = languages.flatMap { language -> listOf(320, 411).flatMap { width -> listOf(2f, 3.2f).map { scale -> arrayOf<Any>(language, width, scale, false) } } }
            return phones + languages.map { arrayOf<Any>(it, 960, 2f, true) }
        }
    }
}
