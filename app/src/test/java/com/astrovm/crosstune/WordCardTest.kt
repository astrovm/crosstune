package com.astrovm.crosstune

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** How a word tapped is laid out, measured with real text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WordCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aLongWordsRomajiGoesUnderItRatherThanALetterALine() {
        val word = Word("謝りたくない", reading = "あやまりたくない", romanized = "ayamaritakunai")
        composeRule.setContent {
            CrosstuneTheme {
                Box(Modifier.width(300.dp)) {
                    WordCard(WordMeaning(word, "pedir disculpas", type = "verbo en forma negativa"), examples = null, canFindExamples = false) {}
                }
            }
        }
        // The word, its kana and its romaji don't fit side by side: each still shows, on one line.
        listOf("あやまりたくない", "ayamaritakunai").forEach { text ->
            val bounds = composeRule.onNodeWithText(text).getBoundsInRoot()
            val height = bounds.bottom - bounds.top
            assertTrue("$text: $bounds", bounds.right > bounds.left && height > 0.dp && height < 36.dp)
        }
    }

    @Test
    fun aWordSpelledOtherwiseWhenTypedShowsBothThePickedFirst() {
        val word = Word("先生", reading = "せんせい", romanized = "sensee", typed = "sensei")
        composeRule.setContent {
            CrosstuneTheme {
                WordCard(WordMeaning(word), examples = null, canFindExamples = false, romajiTyped = true) {}
            }
        }
        val typed = composeRule.onNodeWithText("sensei").getBoundsInRoot()
        val said = composeRule.onNodeWithText("sensee").getBoundsInRoot()
        assertTrue("$typed before $said", typed.left < said.left)
    }
}
