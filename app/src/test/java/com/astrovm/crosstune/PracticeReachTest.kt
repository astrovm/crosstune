package com.astrovm.crosstune

import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every word and every control of practice, reached the way someone would at each font size on a small and a
 * big phone: scrolled to, then drawn whole on the screen and there for TalkBack, not only laid out somewhere.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "es-w411dp-h891dp-xxhdpi")
class PracticeReachTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var view: View
    private var state by mutableStateOf(UiState())
    private val turned = mutableListOf<Unit>()
    private val graded = mutableListOf<Grade>()
    private val actions = ScreenActions(onTurnPractice = { turned += Unit }, onGradePractice = { graded += it })

    private val kind = READ_IT.copy(name = "Práctica de pronunciación muy extendida QA Beta")
    private val kinds = listOf(kind, HEAR_IT.copy(on = true))
    private val line = SavedLine(
        "沈むように溶けてゆくように", romanized = "shizumu you ni tokete yuku you ni", translation = "Como si se hundiera y se derritiera",
        title = "Una canción de prueba con un nombre largo", artist = "Banda inventada", startMs = 1_000, endMs = 4_000
    )

    private lateinit var inputModes: InputModeManager
    private var listed by mutableStateOf(false)
    private var started = false

    /** One screen for the whole test, as Compose sets content once; what it shows changes. */
    private fun start() {
        if (started) return
        started = true
        composeRule.setContent {
            view = LocalView.current
            inputModes = LocalInputModeManager.current
            CrosstuneTheme { if (listed) PracticeList(state, actions, onBack = {}) else PracticeScreen(state, actions) }
        }
    }

    private fun show(practice: Practice) {
        listed = false
        state = UiState(practice = practice, cardTypes = kinds)
        start()
        composeRule.waitForIdle()
    }

    private fun showList() {
        listed = true
        state = UiState(savedLines = listOf(line, line.copy(text = "otra línea"), line.copy(text = "una más", title = "Otra canción inventada con un título muy largo", artist = "Alguien")), cardTypes = kinds)
        start()
        composeRule.waitForIdle()
    }

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private fun byId(id: Int) = SemanticsMatcher("id $id") { it.id == id }

    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    /** Scrolled to when it's in something that scrolls, as a swipe or TalkBack would. */
    private fun reach(node: SemanticsNode): SemanticsNodeInteraction {
        val interaction = composeRule.onNode(byId(node.id), useUnmergedTree = true)
        if (composeRule.onAllNodes(byId(node.id) and hasAnyAncestor(hasScrollAction()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) interaction.performScrollTo()
        composeRule.waitForIdle()
        return interaction
    }

    private fun accessibility() {
        val manager = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        composeRule.waitForIdle()
    }

    /** Where it can be seen: what it scrolls in, or the screen. */
    private fun viewport(node: SemanticsNode): androidx.compose.ui.geometry.Rect =
        generateSequence(node.parent) { it.parent }.firstOrNull { SemanticsActions.ScrollBy in it.config }?.boundsInRoot ?: composeRule.onRoot().fetchSemanticsNode().boundsInRoot

    /**
     * It's in sight, all of it, or the whole of where it's seen when it's taller than that, and Android tells
     * TalkBack it's there. Bounds here are as drawn, cut by whatever holds it.
     */
    private fun onScreen(name: String, node: SemanticsNodeInteraction) {
        val semantics = node.fetchSemanticsNode()
        val drawn = semantics.boundsInRoot
        val sight = viewport(semantics)
        val whole = semantics.size.height.toFloat()
        assertTrue("$name isn't drawn: $drawn", drawn.height > 0f && drawn.width > 0f)
        assertTrue("$name is drawn ${drawn.height}px of ${whole}px, in ${sight.height}px", drawn.height >= minOf(whole, sight.height) - 1f)
        assertTrue("$name is drawn ${drawn.width}px of ${semantics.size.width}px", drawn.width >= semantics.size.width - 1f)
        val info = requireNotNull(requireNotNull(view.accessibilityNodeProvider).createAccessibilityNodeInfo(semantics.id)) { "$name is gone for TalkBack" }
        assertTrue("$name isn't visible to TalkBack", info.isVisibleToUser)
    }

    /** Every text, scrolled to, is whole: no line cut short, no letter outside where it's drawn. */
    private fun everyTextWhole(where: String, except: Set<String> = emptySet()) {
        val texts = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            .filterNot { it.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString() in except }
        assertTrue("$where has no text", texts.isNotEmpty())
        for (text in texts) {
            val words = text.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString()
            val node = reach(text)
            onScreen("$where: \"$words\"", node)
            val laid = layoutOf(node)
            val cut = listOfNotNull(
                "ellipsized".takeIf { (0 until laid.lineCount).any { laid.isLineEllipsized(it) } },
                // Width is held by each letter's box below: a paragraph laid out wider than the words still draws them all.
                "too tall for its box".takeIf { laid.didOverflowHeight }
            )
            assertTrue("$where: \"$words\" is $cut, ${laid.size} for ${laid.multiParagraph.width}x${laid.multiParagraph.height}", cut.isEmpty())
            for (index in words.indices.filterNot { words[it].isWhitespace() || it >= laid.layoutInput.text.length }) {
                val glyph = laid.getBoundingBox(index)
                assertTrue("$where: \"$words\" letter $index at $glyph outside ${laid.size}", glyph.left >= -0.5f && glyph.right <= laid.size.width + 0.5f && glyph.top >= -0.5f && glyph.bottom <= laid.size.height + 0.5f)
            }
        }
    }

    /** Every control but the card itself, scrolled to, is whole on the screen, big enough to tap and named. */
    private fun everyControlReached(where: String) {
        val controls = composeRule.onAllNodes(hasClickAction() and !hasTestTag(PRACTICE_CARD_TAG)).fetchSemanticsNodes()
        for (control in controls) {
            val node = reach(control)
            val name = listOfNotNull(control.config.getOrNull(SemanticsProperties.Text)?.joinToString(), control.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()).joinToString()
            assertTrue("$where: a control has no name", name.isNotBlank())
            onScreen("$where: $name", node)
            // Where a tap lands, which Material makes at least 48dp round a smaller icon.
            val size = node.fetchSemanticsNode().touchBoundsInRoot.size
            val least = with(composeRule.density) { 47.5.dp.toPx() }
            assertTrue("$where: $name is $size px, under 48dp", size.width >= least && size.height >= least)
        }
    }

    private fun everyPartOfEveryScreen() {
        accessibility()
        // The front, with the offer to show over other apps under it.
        show(Practice(queue = listOf(line), total = 5, cantComeBack = true))
        everyTextWhole("front")
        everyControlReached("front")
        // Turned, with the small player and the line's sound.
        show(Practice(queue = listOf(line.copy(cardType = HEAR_TYPE)), total = 5, turned = true, sound = LineSound.PLAYED, video = "fake-video"))
        everyTextWhole("back")
        everyControlReached("back")
        reach(composeRule.onNodeWithTag(LINE_PLAYER_TAG).fetchSemanticsNode()).also { onScreen("player", it) }
        // Each grade still answers once scrolled to.
        listOf(R.string.practice_again, R.string.practice_hard, R.string.practice_good, R.string.practice_easy).forEach { label ->
            composeRule.onNode(hasText(string(label)) and hasClickAction()).performScrollTo().performClick()
        }
        assertEquals(listOf(Grade.AGAIN, Grade.HARD, Grade.GOOD, Grade.EASY), graded)
        // The list of songs to practise.
        showList()
        // The page's title is the shared top bar's, one line by design there, and held to that elsewhere.
        everyTextWhole("list", except = setOf(string(R.string.saved_practice)))
        everyControlReached("list")
    }

    @Test
    fun everyPartIsReachedAtTheDefaultFont() = everyPartOfEveryScreen()

    @Test
    @Config(fontScale = 2f)
    fun everyPartIsReachedAtTwiceTheFont() = everyPartOfEveryScreen()

    @Test
    @Config(fontScale = 3.2f)
    fun everyPartIsReachedAtTheLargestFont() = everyPartOfEveryScreen()

    @Test
    @Config(qualifiers = "es-w411dp-h731dp-xxhdpi", fontScale = 2f)
    fun everyPartIsReachedOnAShorterPhone() = everyPartOfEveryScreen()

    @Test
    @Config(qualifiers = "es-w320dp-h640dp-xhdpi", fontScale = 3.2f)
    fun everyPartIsReachedOnANarrowPhoneAtTheLargestFont() = everyPartOfEveryScreen()

    @Test
    @Config(qualifiers = "de-w320dp-h640dp-xhdpi", fontScale = 3.2f)
    fun everyPartIsReachedInGermanOnANarrowPhone() = everyPartOfEveryScreen()

    private fun theListWhole() {
        accessibility()
        showList()
        everyTextWhole("list", except = setOf(string(R.string.saved_practice)))
        everyControlReached("list")
    }

    @Test
    @Config(fontScale = 3.2f)
    fun theListIsWholeAtTheLargestFont() = theListWhole()

    @Test
    @Config(qualifiers = "es-w320dp-h640dp-xhdpi", fontScale = 3.2f)
    fun theListIsWholeOnANarrowPhone() = theListWhole()

    @Test
    @Config(fontScale = 3.2f)
    fun aSwipeOnTheCardScrollsItAndNeverTurnsIt() {
        show(Practice(queue = listOf(line), total = 5, cantComeBack = true))
        val show = composeRule.onNode(hasText(string(R.string.practice_show)) and hasClickAction())
        val before = show.fetchSemanticsNode().positionInRoot.y
        // Up the middle of the screen, on the card, where the offer to show over other apps is.
        repeat(3) { composeRule.onRoot().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.3f) } }
        composeRule.waitForIdle()
        assertEquals("a swipe turned the card", 0, turned.size)
        val after = show.fetchSemanticsNode().positionInRoot.y
        assertTrue("a swipe didn't scroll: Show answer at ${after}px, was ${before}px", after < before)
        // A tap still turns it.
        show.performScrollTo().performClick()
        assertEquals(1, turned.size)
    }

    @Test
    @Config(fontScale = 3.2f)
    fun aRemoteMovesDownToTheGradesBelowTheFoldAndAnswersWithOne() {
        accessibility()
        show(Practice(queue = listOf(line.copy(cardType = HEAR_TYPE)), total = 5, turned = true, sound = LineSound.PLAYED, video = "fake-video"))
        val again = composeRule.onNode(hasText(string(R.string.practice_again)) and hasClickAction())
        assertTrue("the grades already fit, so this shows nothing", again.fetchSemanticsNode().boundsInRoot.height < again.fetchSemanticsNode().size.height)
        // A remote, not a finger: Compose only focuses buttons then.
        composeRule.runOnIdle { inputModes.requestInputMode(InputMode.Keyboard) }
        composeRule.onNode(hasContentDescription(string(R.string.floating_close)) and hasClickAction()).requestFocus()
        var presses = 0
        while (again.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) != true && presses < 20) {
            composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.waitForIdle()
            presses++
        }
        again.assertIsFocused()
        onScreen("focused Again", again)
        composeRule.onRoot().performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()
        assertEquals(listOf(Grade.AGAIN), graded)
    }

    @Test
    fun theFourGradesShareOneRowAtTheDefaultFont() {
        show(Practice(queue = listOf(line), total = 5, turned = true))
        val tops = listOf(R.string.practice_again, R.string.practice_hard, R.string.practice_good, R.string.practice_easy).map { label ->
            composeRule.onNode(hasText(string(label)) and hasClickAction()).getBoundsInRoot().top
        }
        assertEquals("grades in more than one row: $tops", 1, tops.distinct().size)
    }

    @Test
    fun oneCardDoneIsSaidInTheSingular() {
        show(Practice(total = 1, done = 1))
        composeRule.onNode(hasText("1 practicada")).assertExists()
    }
}
