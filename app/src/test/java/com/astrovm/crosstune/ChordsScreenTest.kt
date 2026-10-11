package com.astrovm.crosstune

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import java.io.File

/** A song's chords over its words, drawn with real text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ChordsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val song = MusicMetadata("River Road", "The Walkers")

    private val lines = listOf(
        LyricLine(1_000, "Walking down the river road", listOf(TimedWord(1_000, "Walking "), TimedWord(1_600, "down "), TimedWord(2_000, "the "), TimedWord(2_300, "river "), TimedWord(2_900, "road"))),
        LyricLine(5_000, ""),
        LyricLine(9_000, "Singing to the morning light, singing all the way back home again tonight")
    )

    private val sheet = ChordSheet(
        listOf(
            SheetLine("", listOf(Chord(0, "G"), Chord(3, "D"))),
            SheetLine(lines[0].text, listOf(Chord(0, "G"), Chord(8, "C"), Chord(16, "Em"), Chord(27, "D"), Chord(27, "D7"))),
            SheetLine("", listOf(Chord(0, "Am"), Chord(3, "F"))),
            // Crowded at the end of a line that wraps: they keep apart and inside the screen.
            SheetLine(lines[2].text, listOf(Chord(0, "F"), Chord(70, "Bbmaj7"), Chord(71, "C#m7-5"), Chord(72, "Gsus4"), Chord(72, "A/C#")))
        ),
        capo = 2,
        site = ChordSite.ULTIMATE_GUITAR,
        url = "https://tabs.ultimate-guitar.com/tab/river-1"
    )

    /** The chords drawn over [line], once they're worked out. */
    private fun chordsOver(line: String): List<String>? {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(line, useUnmergedTree = true).fetchSemanticsNodes().any { it.config.contains(ChordsShown) } }
        return composeRule.onAllNodesWithText(line, useUnmergedTree = true).fetchSemanticsNodes().firstNotNullOfOrNull { it.config.getOrElseNullable(ChordsShown) { null } }
    }

    private fun state(chords: ChordsState, following: Following? = null) = UiState(
        lyricsFor = song,
        lyrics = lines.joinToString("\n") { it.text },
        lyricLines = lines,
        following = following,
        canFollowApps = true,
        chords = chords
    )

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun chordsShowOverTheWordsWithTheCapoAndWhereTheyreFrom() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        // Paused partway into the first line, sung word by word: its chord being played lights.
        val following = Following(PlaybackClock(1_700, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true)
        val chords = ChordsState(on = true, sheet = sheet)
        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                CrosstuneTheme { LyricsScreen(state(chords, following), ScreenActions()) }
            }
        }
        assertEquals(listOf("G", "C", "Em", "D", "D7"), chordsOver("Walking down the river road"))
        // Those played before a line, and in a gap, on rows of their own.
        composeRule.onNodeWithText("G   D", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Am   F", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(string(R.string.lyrics_capo, 2)).assertExists()
        composeRule.onNodeWithText("Ultimate Guitar").performClick()
        assertEquals(listOf(sheet.url), opened)

        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        System.getenv("CHORDS_SCREENSHOT")?.let { path -> File(path).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        assertTrue(image.width > 0)
    }

    @Test
    fun chordsForOtherWordsDontShow() {
        val chords = ChordsState(on = true, sheet = sheet.copy(lines = listOf(SheetLine("Nothing like these words at all, honestly", listOf(Chord(0, "G"))))))
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state(chords), ScreenActions()) } }
        composeRule.waitForIdle()
        Thread.sleep(200)
        composeRule.waitForIdle()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(ChordsShown), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("G   D", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun noChordsOrNoAnswerSaySo() {
        var retried = 0
        var chords by mutableStateOf(ChordsState(on = true, missing = true))
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state(chords), ScreenActions(onRetryChords = { retried++ })) } }
        composeRule.onNodeWithText(string(R.string.lyrics_chords_none)).assertExists()
        chords = ChordsState(on = true, failed = true)
        composeRule.onNodeWithText(string(R.string.lyrics_chords_failed)).assertExists()
        composeRule.onNodeWithText(string(R.string.retry_button)).performClick()
        assertEquals(1, retried)
        // Without a capo, only where they're from shows.
        chords = ChordsState(on = true, sheet = sheet.copy(capo = 0))
        composeRule.onNodeWithText("Ultimate Guitar").assertExists()
        assertEquals(listOf("F", "Bbmaj7", "C#m7-5", "Gsus4", "A/C#"), chordsOver(lines[2].text))
        composeRule.onNodeWithText(string(R.string.lyrics_capo, 0)).assertDoesNotExist()
        // Switched off, nothing does.
        chords = chords.copy(on = false)
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("Ultimate Guitar").fetchSemanticsNodes().size)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(ChordsShown), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
}
