package com.astrovm.crosstune

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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

/** The card in front of someone practising, drawn on its own with the state it's in. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PracticeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private val line = SavedLine("夜空を見上げて", reading = "よぞらをみあげて", romanized = "yozora o miagete", translation = "Looking up at the night sky", title = "Night Song", artist = "The Band", startMs = 1_000, endMs = 4_000)

    private fun show(state: UiState, actions: ScreenActions = ScreenActions(), dark: Boolean = false) {
        composeRule.setContent { CrosstuneTheme(darkTheme = dark) { PracticeScreen(state, actions) } }
        composeRule.waitForIdle()
    }

    private fun practice(turned: Boolean = false, sound: LineSound = LineSound.NONE) =
        Practice(queue = listOf(line), total = 3, done = 1, turned = turned, sound = sound)

    private fun turnedByItsButton(dark: Boolean) {
        val turned = mutableListOf<Unit>()
        show(UiState(practice = practice(), cardTypes = DEFAULT_CARD_TYPES), ScreenActions(onTurnPractice = { turned += Unit }), dark)
        composeRule.onNodeWithText("Night Song").assertExists()
        composeRule.onNodeWithText(string(R.string.practice_show)).performClick()
        assertEquals(1, turned.size)
    }

    @Test
    fun aCardIsTurnedByItsButton() = turnedByItsButton(dark = false)

    @Test
    fun aCardIsTurnedByItsButtonInTheDarkToo() = turnedByItsButton(dark = true)

    private fun gradedByEachButton(dark: Boolean) {
        val graded = mutableListOf<Grade>()
        show(UiState(practice = practice(turned = true), cardTypes = DEFAULT_CARD_TYPES), ScreenActions(onGradePractice = { graded += it }), dark)
        listOf(R.string.practice_again to Grade.AGAIN, R.string.practice_hard to Grade.HARD, R.string.practice_good to Grade.GOOD, R.string.practice_easy to Grade.EASY).forEach { (label, grade) ->
            composeRule.onNodeWithText(string(label)).performClick()
            assertEquals(grade, graded.last())
        }
        assertEquals(4, graded.size)
    }

    @Test
    fun aTurnedCardOffersEachGrade() = gradedByEachButton(dark = false)

    @Test
    fun aTurnedCardOffersEachGradeInTheDarkToo() = gradedByEachButton(dark = true)

    @Test
    fun aLineWithItsSoundPlaysAgainOnceItHasPlayed() {
        val hear = HEAR_IT.copy(on = true)
        val heard = line.copy(cardType = HEAR_TYPE, app = "com.example.music")
        fun state(sound: LineSound) = UiState(practice = Practice(queue = listOf(heard), total = 2, done = 0, sound = sound), cardTypes = listOf(READ_IT, hear))

        val replays = mutableListOf<Unit>()
        show(state(LineSound.PLAYED), ScreenActions(onReplayLine = { replays += Unit }))
        composeRule.onNodeWithContentDescription(string(R.string.practice_play)).assertIsEnabled().performClick()
        assertEquals(1, replays.size)
    }

    @Test
    fun whileTheLineIsPlayingItCantBePlayedAgain() {
        val heard = line.copy(cardType = HEAR_TYPE)
        show(UiState(practice = Practice(queue = listOf(heard), total = 2, sound = LineSound.PLAYING), cardTypes = listOf(READ_IT, HEAR_IT.copy(on = true))))
        composeRule.onNodeWithContentDescription(string(R.string.practice_play)).assertDoesNotExist()
    }

    @Test
    fun whileTheMusicAppGetsThereItsNameIsOnlySaidWhenItIsKnown() {
        val heard = line.copy(cardType = HEAR_TYPE, app = "com.example.nothing")
        show(UiState(practice = Practice(queue = listOf(heard), total = 2, sound = LineSound.LOADING), cardTypes = listOf(READ_IT, HEAR_IT.copy(on = true))))
        composeRule.onNodeWithText(string(R.string.practice_loading, "com.example.nothing")).assertDoesNotExist()
    }

    @Test
    fun whenDoneItOffersTheMusicThatPlayedBeforeUnlessPracticeLeftOffThere() {
        val before = Before(MusicMetadata("Old Song", "Old Band"), 5_000, "com.example.music")
        val backs = mutableListOf<Unit>()
        show(UiState(practice = Practice(total = 3, done = 3, before = before)), ScreenActions(onBackToBefore = { backs += Unit }))
        composeRule.onNodeWithText(string(R.string.practice_done, 3)).assertExists()
        composeRule.onNodeWithText(string(R.string.practice_back_to, "Old Song")).performClick()
        assertEquals(1, backs.size)
    }

    @Test
    fun whenDoneInTheSongItPlayedBeforeThereIsNothingToGoBackTo() {
        val lastSong = line.copy(title = "Old Song", artist = "Old Band")
        val before = Before(MusicMetadata("Old Song", "Old Band"), 5_000, null)
        show(UiState(practice = Practice(total = 3, done = 3, before = before, lastSong = lastSong)))
        composeRule.onNodeWithText(string(R.string.practice_back_to, "Old Song")).assertDoesNotExist()
    }

    @Test
    fun aDifferentSongThanTheOneBeforeStillOffersGoingBack() {
        val lastSong = line.copy(title = "Old Song", artist = "Another Band")
        val before = Before(MusicMetadata("Old Song", "Old Band"), 5_000, null)
        show(UiState(practice = Practice(total = 3, done = 3, before = before, lastSong = lastSong)))
        composeRule.onNodeWithText(string(R.string.practice_back_to, "Old Song")).assertExists()
    }

    @Test
    fun caughtUpShowsItAndOffersTheCardOptionsOnALineKept() {
        show(UiState(practice = Practice(), savedLines = listOf(line), cardTypes = DEFAULT_CARD_TYPES))
        composeRule.onNodeWithText(string(R.string.practice_caught_up)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.practice_card_options)).assertExists()
    }

    @Test
    fun caughtUpWithNothingKeptHasNoOptionsToSet() {
        show(UiState(practice = Practice(), savedLines = listOf(SavedLine("x", title = "T", artist = "A"))))
        composeRule.onNodeWithContentDescription(string(R.string.practice_card_options)).assertDoesNotExist()
    }

    private fun readingFields(onSave: (List<String>) -> Unit = {}, onCancel: () -> Unit = {}) {
        val ruby = SavedLine("夜空", title = "T", artist = "A", ruby = listOf(Ruby("夜", "よる"), Ruby("の"), Ruby("空", "そら")), line = "夜の空")
        composeRule.setContent { CrosstuneTheme { ReadingFields(ruby, onSave, onCancel) } }
        composeRule.waitForIdle()
    }

    @Test
    fun aReadingIsSavedOnlyWhenEveryKanjiHasOneInKana() {
        var saved: List<String>? = null
        readingFields(onSave = { saved = it })
        composeRule.onNodeWithText(string(R.string.save_button)).assertIsEnabled()
        // Not kana: flagged, and nothing to save.
        composeRule.onNodeWithText("夜").performTextClearance()
        composeRule.onNodeWithText("夜").performTextInput("yoru")
        composeRule.onNodeWithText(string(R.string.save_button)).assertIsNotEnabled()
        // Blank: nothing to save either.
        composeRule.onNodeWithText("夜").performTextClearance()
        composeRule.onNodeWithText(string(R.string.save_button)).assertIsNotEnabled()
        composeRule.onNodeWithText("夜").performTextInput("よ")
        composeRule.onNodeWithText(string(R.string.save_button)).assertIsEnabled().performClick()
        assertTrue(saved.toString(), saved == listOf("よ", "そら"))
    }

    @Test
    fun cancellingReadingsLeavesThemAsTheyWere() {
        var cancelled = false
        readingFields(onCancel = { cancelled = true })
        composeRule.onNodeWithText(string(R.string.cancel_button)).performClick()
        assertTrue(cancelled)
    }
}
