package com.astrovm.crosstune

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the display options offer for each script, and for the words as they are switched to show. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LyricsWordsOptionsTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = ScreenActions(
        onReadingsChange = { calls += "readings $it" },
        onRomanizedChange = { calls += "latin $it" },
        onTranslationChange = { calls += "translation $it" },
        onRomajiTypedChange = { calls += "typed $it" },
        onWordsCenteredChange = { calls += "centered $it" },
        onWordsAsChange = { calls += "as $it" }
    )

    private fun show(learning: Learning, centered: Boolean? = null) {
        compose.setContent { CrosstuneTheme { WordsOptions(learning, actions, centered) } }
    }

    /** Switched by its own action, as a screen reader does, wherever it sits on the screen. */
    private fun tap(description: String) {
        compose.onNodeWithContentDescription(description).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun offered(vararg names: String) = names.forEach { compose.onAllNodesWithContentDescription(it).assertCountEquals(1) }

    private fun notOffered(vararg names: String) = names.forEach { compose.onAllNodesWithContentDescription(it).assertCountEquals(0) }

    @Test
    fun wordsInLatinLettersAlreadyOfferOnlyTheirTranslationAndSize() {
        show(Learning())
        offered("Translation", "Words size")
        notOffered("Readings over the words", "In Latin letters", "Align words to the start", "Reading size", "Translation size")
    }

    @Test
    fun japaneseAsWrittenOffersReadingsAndLatinLettersAndRomajiOnceLatinLettersAreOn() {
        show(Learning(script = Script.JAPANESE, readings = true, romanized = true, translation = true))
        offered("Readings over the words", "In Latin letters", "Translation", "Reading size", "Translation size", "Words size")
        compose.onAllNodesWithText("Romaji as typed").assertCountEquals(1)
        tap("Readings over the words")
        tap("In Latin letters")
        tap("Translation")
        assertEquals(listOf("readings false", "latin false", "translation false"), calls)
    }

    @Test
    fun japaneseOnlyInKanaOffersLatinLettersButNoReadingsOverKana() {
        show(Learning(script = Script.JAPANESE, wordsAs = WordsAs.KANA, readings = true))
        offered("In Latin letters")
        notOffered("Readings over the words", "Reading size")
    }

    @Test
    fun japaneseOnlyInLatinLettersOffersNoMoreLatinLettersButHowRomajiIsSpelled() {
        show(Learning(script = Script.JAPANESE, wordsAs = WordsAs.LATIN))
        notOffered("In Latin letters", "Readings over the words")
        compose.onAllNodesWithText("Romaji as typed").assertCountEquals(1)
    }

    @Test
    fun japaneseWithReadingsOnButNotLatinLettersOffersNoRomajiChoice() {
        show(Learning(script = Script.JAPANESE, readings = true))
        compose.onAllNodesWithText("Romaji as typed").assertCountEquals(0)
    }

    @Test
    fun chineseOffersReadingsAndLatinLettersButNoKana() {
        show(Learning(script = Script.CHINESE, readings = true))
        offered("Readings over the words", "In Latin letters", "Reading size")
        compose.onAllNodesWithText("Romaji as typed").assertCountEquals(0)
    }

    @Test
    fun koreanOffersLatinLettersButNoReadings() {
        show(Learning(script = Script.KOREAN, romanized = true))
        offered("In Latin letters")
        notOffered("Readings over the words", "Reading size")
    }

    @Test
    fun readingsSwitchedOffTakeTheirSizeAwayAndTranslationSwitchedOffItsToo() {
        show(Learning(script = Script.JAPANESE, readings = false, translation = false))
        notOffered("Reading size", "Translation size")
    }

    @Test
    fun whereWordsCanBeAlignedEitherSideIsPickedAndTheOneInUseIsSelected() {
        show(Learning(), centered = true)
        tap("Align words to the start")
        tap("Center words")
        assertEquals(listOf("centered false", "centered true"), calls)
    }

    @Test
    fun theWordsOfAScriptCanBeShownAsWrittenOrInLatinLetters() {
        show(Learning(script = Script.JAPANESE, wordsAs = WordsAs.WRITTEN))
        tap("Only kana")
        assertEquals(listOf("as KANA"), calls)
    }
}
