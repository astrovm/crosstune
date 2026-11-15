package com.astrovm.crosstune

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.longClick
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/** A word's reading marked wrong, on the word itself, and sent in one tap. Made-up words. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WrongReadingTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val looked = mutableListOf<Word>()
    private var state by mutableStateOf(UiState())
    private lateinit var inputModes: InputModeManager

    private val actions = ScreenActions(
        onLookUpWord = { word, index -> looked += word; calls += "look ${word.text} $index" },
        onReportReading = { word, index, right -> calls += "reading ${word.text} $index $right" },
        onCloseReport = { calls += "closed" }
    )

    private val flagLabel = "Wrong reading"

    private fun with(word: Word?, lines: List<LineReading> = listOf(LineReading(listOf(Ruby("花", "か"), Ruby("の"), Ruby("空", "そら")), "かのそら", "ka no sora"))) = UiState(
        lyricsFor = MusicMetadata("Made Up", "Some Band"),
        lyrics = "花の空\nStars",
        learning = Learning(readings = true, script = Script.JAPANESE, lines = lines, translation = true),
        word = word?.let { WordMeaning(it, "flower") }
    )

    private fun open(first: UiState, tv: Boolean = false) {
        state = first
        compose.setContent {
            inputModes = LocalInputModeManager.current
            CrosstuneTheme { WithOverlays(inside = tv) { LyricsScreen(state, actions) } }
        }
        compose.waitForIdle()
        // Held, as a line followed in time is, which a tap moves the music to.
        compose.onAllNodesWithText("花の空")[0].performTouchInput { longClick() }
        compose.waitForIdle()
    }

    private val flag get() = compose.onNode(hasContentDescription(flagLabel) and hasClickAction())

    private val send get() = compose.onNode(hasText("Send") and hasClickAction())

    @Test
    fun aWordsReadingIsMarkedWrongWithTheRightOneAndSent() {
        open(with(Word("花", "か", "ka", by = listOf(Reader.IPADIC))))
        flag.performClick()
        // Said what it's about, by its flag and its title; then the word and how it read, so it's plain which
        // is sent, and what goes with it.
        compose.onNode(hasTestTag(REPORT_ICON_TAG), useUnmergedTree = true).assertExists()
        compose.onNode(hasText(flagLabel) and hasClickAction().not()).assertExists()
        compose.onNode(hasTestTag(WRONG_READING_TAG) and hasText("花  か  ka")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("Right reading (optional)")).assertExists()
        compose.onNodeWithText("Sends the word, the reading shown and what made it, its line and script, the reading you type, the song's title and artist, where its lyrics came from, and the app and Android versions. Only Crosstune's developer reads it.").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("はな")
        send.performClick()
        assertEquals("reading 花 0 はな", calls.last())
        // On its way, it can't be sent twice; failed, it says so and can be sent again.
        state = state.copy(report = ReportStatus.SENDING)
        compose.waitForIdle()
        send.assertIsNotEnabled()
        state = state.copy(report = ReportStatus.FAILED)
        compose.waitForIdle()
        compose.onNodeWithText("Couldn't send it. Check your connection and try again.").assertExists()
        // Got there, it says so and closes.
        state = state.copy(report = ReportStatus.SENT)
        compose.waitForIdle()
        assertEquals("Sent. Thanks!", ShadowToast.getTextOfLatestToast())
        assertEquals("closed", calls.last())
        compose.onAllNodes(hasTestTag(WRONG_READING_TAG)).assertCountEquals(0)
    }

    @Test
    fun aWrongReadingCanBeSentWithoutTheRightOneOrLeftUnsent() {
        open(with(Word("花", "か", "ka")))
        flag.performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("closed", calls.last())
        compose.onAllNodes(hasTestTag(WRONG_READING_TAG)).assertCountEquals(0)
        flag.performClick()
        send.performClick()
        assertEquals("reading 花 0 ", calls.last())
    }

    @Test
    fun aWordShownWithNoReadingHasNothingToMark() {
        open(with(Word("Stars")))
        compose.onNodeWithText("flower").assertExists()
        compose.onAllNodesWithContentDescription(flagLabel).assertCountEquals(0)
    }

    @Test
    fun aWordTappedInALineUtaTenPutRightReadsAsTheLineShowsIt() {
        val sung = LineReading(listOf(Ruby("花", "はな", Reader.SUNG), Ruby("の"), Ruby("空", "そら")), "はなのそら", "hana no sora")
        open(with(null, listOf(sung)))
        compose.waitUntil(5_000) { runCatching { compose.onAllNodes(hasText("花")).onLast().performFirstLinkClick() }.isSuccess }
        assertEquals(Word("花", "はな", "hana", by = listOf(Reader.SUNG)), looked.last())
    }

    @Test
    fun onATvTheRemoteStartsOnSendAndGoesBackToTheFlag() {
        open(with(Word("花", "か", "ka")), tv = true)
        // The remote's, as a TV is: Robolectric starts as a touch screen.
        compose.runOnIdle { inputModes.requestInputMode(InputMode.Keyboard) }
        flag.requestFocus()
        flag.performClick()
        compose.waitForIdle()
        // Send, rather than the field, which would open the keyboard for what's optional.
        send.assertIsFocused()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        flag.assertIsFocused()
        assertTrue(calls.contains("closed"))
    }
}
