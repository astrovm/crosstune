package com.astrovm.crosstune

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Setting the kinds of card and what each side shows, through the practice screen's card options. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CardTypesPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun string(id: Int) = context.getString(id)

    private val japanese = SavedLine(
        "夜空を見上げて", reading = "よぞらをみあげて", romanized = "yozora o miagete", translation = "Looking up at the night sky",
        title = "Night Song", artist = "The Band", startMs = 1_000, endMs = 4_000, ruby = listOf(Ruby("夜空", "よぞら"), Ruby("を"), Ruby("見上", "みあ"), Ruby("げて")), line = "夜空を見上げて"
    )
    private val english = SavedLine("Look up at the night sky", translation = "Mira el cielo de noche", title = "Night Song", artist = "The Band")

    private var state by mutableStateOf(UiState())
    private val changes = mutableListOf<List<CardType>>()
    private val readAs = mutableListOf<List<String>>()
    private var replays = 0

    private fun show(line: SavedLine = japanese, turned: Boolean = false, caughtUp: Boolean = false, sound: LineSound = LineSound.NONE) {
        state = UiState(
            practice = if (caughtUp) Practice() else Practice(queue = listOf(line), total = 2, done = 0, turned = turned, sound = sound),
            savedLines = listOf(line), cardTypes = DEFAULT_CARD_TYPES
        )
        val actions = ScreenActions(
            onCardTypesChange = { list, _ -> changes += list; state = state.copy(cardTypes = list) },
            onReadCardAs = { readAs += it },
            onReplayLine = { replays++ }
        )
        composeRule.setContent { CrosstuneTheme { PracticeScreen(state, actions) } }
        composeRule.waitForIdle()
    }

    private fun openOptions() {
        composeRule.onNodeWithContentDescription(string(R.string.practice_card_options)).performClick()
        composeRule.waitForIdle()
    }

    private fun edit(title: String) {
        composeRule.onNodeWithContentDescription("$title, ${string(R.string.card_type_edit)}").performClick()
        composeRule.waitForIdle()
    }

    private val read get() = state.cardTypes.first { it.id == READ_TYPE }

    @Test
    fun theLastKindOnStaysOnAndAnotherCanBeTurnedOnAndThenAnyOff() {
        show()
        openOptions()
        composeRule.onNodeWithContentDescription(string(R.string.card_type_read)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.card_type_hear)).performClick()
        assertTrue(state.cardTypes.first { it.id == HEAR_TYPE }.on)
        composeRule.onNodeWithContentDescription(string(R.string.card_type_read)).assertIsEnabled().performClick()
        assertFalse(read.on)
        // Hear it is the one left on now, and stays.
        composeRule.onNodeWithContentDescription(string(R.string.card_type_hear)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.card_type_read)).assertIsEnabled()
    }

    @Test
    fun aNewKindStartsLikeTheFirstOpensItsEditorAndCanBeNamedAndDeleted() {
        show()
        openOptions()
        composeRule.onNodeWithText(string(R.string.card_type_new)).performClick()
        val made = state.cardTypes.last()
        assertEquals("type1", made.id)
        assertEquals(read.options, made.options)
        composeRule.onNodeWithText(string(R.string.card_type_name)).assertExists()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Mine")
        assertEquals("Mine", state.cardTypes.last().name)
        composeRule.onNodeWithContentDescription(string(R.string.card_type_delete)).performClick()
        assertEquals(DEFAULT_CARD_TYPES.map { it.id }, state.cardTypes.map { it.id })
        // Back in the list, a second new one takes the next free name.
        composeRule.onNodeWithText(string(R.string.card_type_new)).performClick()
        assertEquals("type1", state.cardTypes.last().id)
    }

    @Test
    fun aPresetCanComeBackAsItWasOnceItsBeenChanged() {
        show()
        openOptions()
        edit(string(R.string.card_type_read))
        composeRule.onNodeWithContentDescription(string(R.string.card_type_reset)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.card_type_delete)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_translation)).performClick()
        assertFalse(state.cardTypes.first().options.front.translation == DEFAULT_CARD_TYPES.first().options.front.translation)
        composeRule.onNodeWithContentDescription(string(R.string.card_type_reset)).performClick()
        assertEquals(DEFAULT_CARD_TYPES.first(), read)
        // And back to the list.
        composeRule.onNodeWithContentDescription(string(R.string.back_button)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.card_type_hear)).assertExists()
    }

    @Test
    fun theBackOfACardIsSetSeparatelyFromItsFront() {
        show()
        openOptions()
        edit(string(R.string.card_type_read))
        composeRule.onNodeWithText(string(R.string.practice_back)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_translation)).performClick()
        assertEquals(DEFAULT_CARD_TYPES.first().options.front, read.options.front)
        assertFalse(DEFAULT_CARD_TYPES.first().options.back.translation == read.options.back.translation)
        composeRule.onNodeWithText(string(R.string.practice_front)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_translation)).performClick()
        assertEquals(DEFAULT_CARD_TYPES.first().options.front.translation, !read.options.front.translation)
    }

    @Test
    fun caughtUpTheOptionsAreSetOnALineKept() {
        show(caughtUp = true)
        openOptions()
        edit(string(R.string.card_type_read))
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_translation)).assertExists()
        composeRule.onNodeWithText(string(R.string.practice_caught_up)).assertDoesNotExist()
    }

    @Test
    fun aLineWrittenInJapaneseCanBeShownInKanaOrLatinAndItsRomajiAsTyped() {
        show()
        openOptions()
        edit(string(R.string.card_type_read))
        val words = composeRule.onNodeWithContentDescription(string(R.string.visuals_show_words))
        words.performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.lyrics_display_kana)).performClick()
        assertEquals(WordsAs.KANA, read.options.front.wordsAs)
        // Not as written any more, nothing goes over the words.
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_readings)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.visuals_show_words)).performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.lyrics_display_latin)).performClick()
        assertEquals(WordsAs.LATIN, read.options.front.wordsAs)
        // In Latin letters already, the Latin line isn't offered again.
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_romanized)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.visuals_show_words)).performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.lyrics_display_original)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_romanized)).performTouchInput { longClick() }
        assertTrue(read.options.front.romajiTyped)
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_readings)).performClick()
        assertFalse(DEFAULT_CARD_TYPES.first().options.front.readings == read.options.front.readings)
    }

    @Test
    fun aLineInLettersWithNoKanaHasNoWaysToWriteItButTheOne() {
        show(english)
        openOptions()
        edit(string(R.string.card_type_read))
        composeRule.onNodeWithContentDescription(string(R.string.visuals_show_words)).performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.lyrics_display_kana)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_readings)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_romanized)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_translation)).assertExists()
    }

    @Test
    fun aLineCanBeMadeBiggerAndSmallerByTheActionsForThoseWhoCantPinch() {
        show()
        openOptions()
        edit(string(R.string.card_type_read))
        fun act(label: String, name: Int) {
            composeRule.runOnUiThread {
                val node = composeRule.onNodeWithContentDescription(string(name)).fetchSemanticsNode()
                node.config[SemanticsActions.CustomActions].first { it.label == label }.action()
            }
            composeRule.waitForIdle()
        }
        val before = read.options.front.wordsScale
        act(string(R.string.practice_bigger), R.string.visuals_show_words)
        assertTrue(read.options.front.wordsScale > before)
        act(string(R.string.practice_smaller), R.string.visuals_show_words)
        assertEquals(before, read.options.front.wordsScale, 0.001f)
        act(string(R.string.practice_bigger), R.string.lyrics_translation)
        assertTrue(read.options.front.translationScale > DEFAULT_CARD_TYPES.first().options.front.translationScale)
        act(string(R.string.lyrics_romaji_typed), R.string.lyrics_romanized)
        assertTrue(read.options.front.romajiTyped)
        act(string(R.string.lyrics_display_kana), R.string.visuals_show_words)
        assertEquals(WordsAs.KANA, read.options.front.wordsAs)
    }

    @Test
    fun theLinesSoundCanBeShownAndPlayedOnlyWhenItCanPlay() {
        show(sound = LineSound.NONE)
        openOptions()
        edit(string(R.string.card_type_read))
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_play)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.practice_play)).performClick()
        assertTrue(read.options.front.sound)
    }

    @Test
    fun theLinesSoundPlaysFromTheOptionsOnceItHasPlayed() {
        show(sound = LineSound.PLAYED)
        openOptions()
        edit(string(R.string.card_type_read))
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_play)).assertIsEnabled().performClick()
        assertEquals(1, replays)
    }

    @Test
    fun aTurnedCardsReadingCanBePutRightFromItsOptions() {
        show(turned = true)
        openOptions()
        composeRule.onNodeWithText(string(R.string.practice_fix_reading)).performClick()
        composeRule.onNodeWithText(string(R.string.save_button)).performClick()
        assertEquals(listOf(listOf("よぞら", "みあ")), readAs)
        // Back in the list.
        composeRule.onNodeWithContentDescription(string(R.string.card_type_read)).assertExists()
        composeRule.onNodeWithText(string(R.string.practice_fix_reading)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.back_button)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.card_type_read)).assertExists()
        composeRule.onNodeWithText(string(R.string.practice_fix_reading)).performClick()
        composeRule.onNodeWithText(string(R.string.cancel_button)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.card_type_read)).assertExists()
        assertEquals(1, readAs.size)
    }

    @Test
    fun aCardNotTurnedYetOffersNoReadingToFix() {
        show(turned = false)
        openOptions()
        composeRule.onNodeWithText(string(R.string.practice_fix_reading)).assertDoesNotExist()
    }
}
