package com.astrovm.crosstune

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** One line open to study: its words to tap, what ChatGPT says of it, and what can be done with it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsLineSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val song = MusicMetadata("Night Song", "The Band")
    private val calls = mutableListOf<String>()
    private val waiting = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)
    private var state by mutableStateOf(UiState())

    private val actions = ScreenActions(
        onLookUpWord = { word, index -> calls += "look ${word.text} $index" },
        onFindExamples = { calls += "examples $it" },
        onExplainLine = { calls += "explain $it" },
        onRepeatLine = { calls += "repeat $it" },
        onStopRepeating = { calls += "stop" },
        onToggleSavedLine = { calls += "toggle $it" },
        onAskAboutLine = { index, question -> calls += "ask $index $question" },
        onDismissWord = { calls += "dismissed" }
    )

    private fun base(
        learning: Learning = Learning(),
        signedIn: Boolean = false,
        timed: Boolean = false,
        lyrics: String = "Night sky\nStars",
        word: WordMeaning? = null,
        saved: List<SavedLine> = emptyList(),
        repeating: Int? = null
    ) = UiState(
        lyricsFor = song,
        lyrics = lyrics,
        lyricLines = if (timed) lyrics.lines().mapIndexed { i, line -> LyricLine(1_000L * (i + 1), line) } else emptyList(),
        following = if (timed) Following(PlaybackClock(1_500, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true) else null,
        canFollowApps = timed,
        // Words without timings are a page, until something helps read them line by line.
        learning = if (timed) learning else learning.copy(translation = true),
        chatGpt = if (signedIn) ChatGptState(email = "fan@example.com") else ChatGptState(),
        word = word,
        savedLines = saved,
        repeating = repeating
    )

    private fun open(first: UiState, line: String = "Night sky") {
        state = first
        compose.setContent { CrosstuneTheme { LyricsScreen(state, actions) } }
        compose.waitForIdle()
        // Held, as a line followed in time is, which a tap moves the music to.
        compose.onNodeWithText(line).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    private val study = hasTestTag(STUDY_LINE_TAG)

    @Test
    fun aLineIsShownOnceWithItsWordsToTapAndSavedOrNot() {
        open(base())
        compose.onNode(study and hasText("Night sky")).assertExists()
        compose.onNodeWithText("Save line").performClick()
        assertEquals("toggle 0", calls.last())
        // Not signed in, a line can't be explained, and not followed, it can't be repeated.
        compose.onAllNodesWithText("Explain this line").assertCountEquals(0)
        compose.onAllNodesWithText("Repeat this line").assertCountEquals(0)
    }

    @Test
    fun aWordInTheLineIsLookedUpWhereItIs() {
        open(base())
        compose.waitUntil(5_000) { runCatching { compose.onNode(study and hasText("Night sky")).performFirstLinkClick() }.isSuccess }
        assertEquals("look Night 0", calls.last())
    }

    @Test
    fun aLineKeptShowsSavedInTheSheet() {
        open(base(saved = listOf(SavedLine("Night sky", title = song.title, artist = song.artist))))
        compose.onNodeWithText("Saved").assertExists()
    }

    @Test
    fun aLineReadWithReadingsShowsThemOverItsPiecesAndItsLatinLettersUnder() {
        val reading = LineReading(listOf(Ruby("夜", "よる"), Ruby("の"), Ruby("空", "そら")), "よるのそら", "yoru no sora", "yoru no sora")
        open(base(Learning(readings = true, romanized = true, lines = listOf(reading), script = Script.JAPANESE), lyrics = "夜の空\nStars"), line = "夜の空")
        compose.onNode(study).assertExists()
        // Once in the words behind the sheet, and once in it.
        compose.onAllNodesWithText("yoru no sora").assertCountEquals(2)
    }

    @Test
    fun aLineWhoseLatinLettersAreItsReadingShowsThemOnce() {
        val reading = LineReading(listOf(Ruby("夜", "よる")), "yoru", "yoru")
        open(base(Learning(readings = true, lines = listOf(reading)), lyrics = "夜\nStars"), line = "夜")
        compose.onAllNodesWithText("yoru").assertCountEquals(0)
    }

    @Test
    fun aLineWithoutAnyReadingIsShownAsWritten() {
        val reading = LineReading(listOf(Ruby("Night sky")), "", "")
        open(base(Learning(readings = true, lines = listOf(reading))))
        compose.onNode(study and hasText("Night sky")).assertExists()
    }

    @Test
    fun theLineTranslatedIsShownUnderIt() {
        open(base(Learning(translation = true, translations = listOf("Cielo nocturno", null))))
        compose.onAllNodesWithText("Cielo nocturno").assertCountEquals(2)
    }

    @Test
    fun aWordTappedShowsHowItReadsAndWhatItMeansAndSignedInAsksForExamples() {
        val word = WordMeaning(Word("Night", reading = "naito", romanized = "naito", typed = "naito"), "the dark hours", type = "noun")
        open(base(word = word, signedIn = true))
        compose.onNodeWithText("the dark hours").assertExists()
        compose.onNodeWithText("noun").assertExists()
        compose.onNodeWithText("naito").assertExists()
        compose.onNodeWithText("Examples").performClick()
        assertEquals("examples 0", calls.last())
    }

    @Test
    fun withoutChatGptAWordHasNoExamplesToAskFor() {
        open(base(word = WordMeaning(Word("Night"), "the dark hours")))
        compose.onAllNodesWithText("Examples").assertCountEquals(0)
    }

    @Test
    fun aWordBeingLookedUpShowsProgressAndOneNotFoundOrFailedSaysSo() {
        open(base(word = WordMeaning(Word("Night"), looking = true)))
        compose.onNode(waiting).assertExists()
        state = base(word = WordMeaning(Word("Night"), failed = true))
        compose.waitForIdle()
        compose.onNodeWithText("Couldn't find its meaning just now.").assertExists()
        state = base(word = WordMeaning(Word("Night")))
        compose.waitForIdle()
        compose.onNodeWithText("No dictionary entry for this word.").assertExists()
    }

    @Test
    fun examplesForTheWordAreShownOrWaitedForOrFailedAndOnlyForThatWordAndLine() {
        val night = Word("Night")
        val meaning = WordMeaning(night, "the dark hours")
        open(base(Learning(examples = WordExamples(night, 0, Asking(listOf(Example("A night out.", "Una noche fuera."))))), word = meaning, signedIn = true))
        compose.onNodeWithText("A night out.").assertExists()
        compose.onNodeWithText("Una noche fuera.").assertExists()
        // Waiting for them, there is nothing to ask.
        state = base(Learning(examples = WordExamples(night, 0)), word = meaning, signedIn = true)
        compose.waitForIdle()
        compose.onNode(waiting).assertExists()
        compose.onAllNodesWithText("Examples").assertCountEquals(1)
        // Failed, they can be asked for again.
        state = base(Learning(examples = WordExamples(night, 0, Asking(failed = true))), word = meaning, signedIn = true)
        compose.waitForIdle()
        compose.onNodeWithText("Couldn't write examples.").assertExists()
        compose.onNodeWithText("Examples").performClick()
        assertEquals("examples 0", calls.last())
        // Examples for another line are not these.
        state = base(Learning(examples = WordExamples(night, 1, Asking(listOf(Example("A night out.", "Una noche fuera."))))), word = meaning, signedIn = true)
        compose.waitForIdle()
        compose.onAllNodesWithText("A night out.").assertCountEquals(0)
    }

    @Test
    fun signedInALineCanBeExplainedUntilItIs() {
        open(base(signedIn = true))
        compose.onNodeWithText("Explain this line").performClick()
        assertEquals("explain 0", calls.last())
        state = base(Learning(explanation = LineExplanation(0, "Night.\n---\nA noun.")), signedIn = true)
        compose.waitForIdle()
        compose.onAllNodesWithText("Explain this line").assertCountEquals(0)
        compose.onNodeWithText("In the song").assertExists()
        compose.onNodeWithText("Language").assertExists()
    }

    @Test
    fun anExplanationForAnotherLineIsNotThisLines() {
        open(base(Learning(explanation = LineExplanation(1, "Stars.", done = true)), signedIn = true))
        compose.onNodeWithText("Explain this line").assertExists()
        compose.onAllNodesWithText("Stars.").assertCountEquals(0)
    }

    @Test
    fun aLineThatCouldntBeExplainedSaysSoAndCanBeAskedAgain() {
        open(base(Learning(explanation = LineExplanation(0, failed = true)), signedIn = true))
        compose.onNodeWithText("Couldn't explain this line.").assertExists()
        compose.onNodeWithText("Explain this line").performClick()
        assertEquals("explain 0", calls.last())
    }

    @Test
    fun aUsedUpPlanSaysSoAndTheLineCanBeAskedAgain() {
        open(base(Learning(explanation = LineExplanation(0, limitReached = true)), signedIn = true))
        compose.onNodeWithText("Your ChatGPT plan is used up for now.").assertExists()
        compose.onNodeWithText("Explain this line").assertExists()
    }

    @Test
    fun anExplanationBeingWrittenShowsProgressBeforeItsFirstWords() {
        open(base(Learning(explanation = LineExplanation(0, "")), signedIn = true))
        compose.onNode(waiting).assertExists()
    }

    @Test
    fun anExplainedLineCanBeAskedAboutWithItsQuestionsAndAnswers() {
        val learning = Learning(
            explanation = LineExplanation(0, "Night.", done = true),
            questionsAbout = 0,
            questions = listOf(LineQuestion("Why night?", "Because.", done = true))
        )
        open(base(learning, signedIn = true))
        compose.onNodeWithText("Why night?").assertExists()
        compose.onNodeWithText("Because.").assertExists()
        compose.onNodeWithText("Ask about this line").performScrollTo().performTextInput("And stars?")
        compose.onNodeWithContentDescription("Send").performScrollTo().performClick()
        assertEquals("ask 0 And stars?", calls.last())
    }

    @Test
    fun questionsAskedAboutAnotherLineAreNotShownHere() {
        val learning = Learning(
            explanation = LineExplanation(0, "Night.", done = true),
            questionsAbout = 1,
            questions = listOf(LineQuestion("Why stars?", "Because.", done = true))
        )
        open(base(learning, signedIn = true))
        compose.onAllNodesWithText("Why stars?").assertCountEquals(0)
        compose.onNodeWithContentDescription("Send").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun whileTheWordsFollowTheMusicALineCanBeRepeatedAndThenStopped() {
        open(base(timed = true))
        compose.onNodeWithText("Repeat this line").performClick()
        assertEquals("repeat 0", calls.last())
        state = base(timed = true, repeating = 0)
        compose.waitForIdle()
        compose.onNodeWithText("Stop repeating").performClick()
        assertEquals("stop", calls.last())
        // Another line being repeated is not this one.
        state = base(timed = true, repeating = 1)
        compose.waitForIdle()
        compose.onNodeWithText("Repeat this line").assertExists()
    }

    @Test
    fun followingAMusicAppThatCantBeMovedOrWhoseTimingIsntKnownOffersNoRepeat() {
        open(base())
        compose.onAllNodesWithText("Repeat this line").assertCountEquals(0)
    }

    @Test
    fun aWordInALineWithReadingsIsLookedUpFromTheReadPieceItIsIn() {
        val reading = LineReading(listOf(Ruby("Night", "nait"), Ruby(" sky")), "nait sky", "nait sky")
        open(base(Learning(readings = true, lines = listOf(reading)), lyrics = "Night sky\nStars"))
        // Its pieces are in the sheet and in the words behind it, and the sheet's is the last.
        compose.waitUntil(5_000) {
            runCatching { compose.onAllNodes(hasText("Night")).onLast().performFirstLinkClick() }.isSuccess
        }
        assertEquals("look Night 0", calls.last())
    }
}
