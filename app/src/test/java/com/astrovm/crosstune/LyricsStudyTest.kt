package com.astrovm.crosstune

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The Study menu on the bar, and the sheet of the song's lines to choose which to learn. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsStudyTest {

    @get:Rule
    val compose = createComposeRule()

    private val song = MusicMetadata("River Road", "The Walkers")
    private val calls = mutableListOf<String>()
    private val words = listOf("Walking down", "Walking down", "la la la", "Home again")

    private val actions = ScreenActions(
        onPractiseSong = { calls += "practise" },
        onChordsChange = { calls += "chords $it" },
        onChooseLines = { add, remove, practise -> calls += "choose $add ${remove.map { it.text }} $practise" }
    )

    private fun kept(text: String, line: String? = null, translation: String? = "meaning") =
        SavedLine(text, translation = translation, title = song.title, artist = song.artist, line = line)

    private fun open(saved: List<SavedLine> = emptyList(), learning: Learning = Learning(), chords: Boolean = false) {
        val state = UiState(
            lyricsFor = song, lyrics = words.joinToString("\n"), lyricLines = words.mapIndexed { i, text -> LyricLine(i * 1_000L, text) },
            learning = learning, savedLines = saved, chords = ChordsState(on = chords)
        )
        compose.setContent { CrosstuneTheme { LyricsScreen(state, actions) } }
        compose.onNodeWithContentDescription("Study").performClick()
        compose.waitForIdle()
    }

    /** A line of the sheet to check, not the same words behind it. */
    private fun row(text: String) = compose.onNode(hasText(text) and isToggleable())

    @Test
    fun withNoLinesKeptTheMenuOnlyOffersToPractiseAndThatChoosesThemFirst() {
        open()
        compose.onAllNodesWithText("Choose lines").assertCountEquals(0)
        compose.onNodeWithText("Practice this song").performClick()
        compose.waitForIdle()
        // Each line once, the sounds-only one not picked to start with.
        row("Walking down").assertExists()
        row("Home again").assertExists()
        compose.onNodeWithContentDescription("Sung 2 times").assertExists()
        compose.onNodeWithText("Practice").performClick()
        assertEquals(listOf("choose [0, 3] [] true"), calls)
    }

    @Test
    fun aLineWithWordsToKnowIsStarredAndALineOnlySoundsCanBePickedToo() {
        open(learning = Learning(wordsToKnow = Asking(listOf(KeyWord("Home", null, "house")))))
        compose.onNodeWithText("Practice this song").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Has words to know").assertCountEquals(1)
        row("la la la").performClick()
        row("Home again").performClick()
        compose.onNodeWithText("Practice").performClick()
        assertEquals(listOf("choose [0, 2] [] true"), calls)
    }

    @Test
    fun practisingNeedsALinePickedAndPickingOneAgainAllowsIt() {
        open()
        compose.onNodeWithText("Practice this song").performClick()
        compose.waitForIdle()
        row("Walking down").performClick()
        row("Home again").performClick()
        compose.onNodeWithText("Practice").assertIsNotEnabled()
        row("Home again").performClick()
        compose.onNodeWithText("Practice").assertIsEnabled()
    }

    @Test
    fun withLinesKeptTheMenuCountsThemAndChoosingThemShowsTheOnesKeptChecked() {
        open(saved = listOf(kept("Walking down"), kept("Home", line = "Home again")))
        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("Choose lines").performClick()
        compose.waitForIdle()
        // A word kept is below the lines, with the line it's sung in under it.
        row("Home").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        row("Walking down").assertExists()
        row("Walking down").performClick()
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("choose [] [Walking down] false"), calls)
    }

    @Test
    fun aLineKeptAndLetGoOfIsKeptAgainByCheckingItAndAWordOnlyLeavesWhenUnchecked() {
        open(saved = listOf(kept("Walking down"), kept("Home", line = "Home again")))
        compose.onNodeWithText("Choose lines").performClick()
        compose.waitForIdle()
        row("Walking down").performClick()
        row("Walking down").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        row("Home").performClick()
        row("Home").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        row("Home").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("choose [] [Home] false"), calls)
    }

    @Test
    fun linesToPractiseAreStudiedAndWhatIsDueIsCounted() {
        open(saved = listOf(kept("Walking down")))
        compose.onNodeWithText("1 due").assertExists()
        compose.onNodeWithText("Practice this song").performClick()
        assertEquals(listOf("practise"), calls)
    }

    @Test
    fun linesKeptWithNothingToRecallAreNotPractisedSoTheyAreChosenFirst() {
        open(saved = listOf(kept("Walking down", translation = null)))
        compose.onAllNodesWithText("1 due").assertCountEquals(0)
        compose.onNodeWithText("Practice this song").performClick()
        compose.waitForIdle()
        // Practised, a line kept leaves the button on.
        compose.onNodeWithText("Practice").assertIsEnabled()
        compose.onNodeWithText("Practice").performClick()
        assertEquals(listOf("choose [] [] true"), calls)
    }

    @Test
    fun chordsAreSwitchedOnAndOffFromTheMenu() {
        open()
        compose.onNodeWithText("Chords").performClick()
        assertEquals(listOf("chords true"), calls)
    }

    @Test
    fun chordsAlreadyOnAreSwitchedOff() {
        open(chords = true)
        compose.onNodeWithText("Chords").performClick()
        assertEquals(listOf("chords false"), calls)
    }
}
