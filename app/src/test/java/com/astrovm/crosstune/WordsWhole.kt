package com.astrovm.crosstune

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Asserts each text on the screen is drawn whole: not cut short or off, and broken onto a new line
 * only between words, with a hyphen, or inside a word wider than [room] for it, which nothing could fit. Returns
 * how many texts it looked at. Texts in [skip] are left out.
 */
internal fun SemanticsNodeInteractionsProvider.assertWordsWhole(where: String, skip: Set<String> = emptySet(), room: (SemanticsNode) -> Float): Int {
    var looked = 0
    for (node in onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.singleOrNull() ?: continue
        val text = layout.layoutInput.text.text
        if (text in skip) continue
        val said = "$where: \"$text\""
        assertFalse("$said is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertFalse("$said is cut off", layout.didOverflowHeight || layout.multiParagraph.height > layout.size.height + 0.5f)
        val input = layout.layoutInput
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = true)
            val between = text.getOrNull(end)?.isWhitespace() != false || text.getOrNull(end - 1)?.let { it.isWhitespace() || it == '-' || it == '/' } == true
            // Broken with a hyphen drawn at its end, as words that may be hyphenated are.
            val hyphened = input.style.hyphens == Hyphens.Auto && layout.getLineRight(line) - layout.getLineLeft(line) > layout.lettersWide(line) + 0.5f
            if (between || hyphened) continue
            val start = text.lastIndexOf(' ', end - 1) + 1
            val stop = text.indexOf(' ', end).takeIf { it >= 0 } ?: text.length
            val word = text.substring(start, stop)
            // In its own bold or not, as it's drawn.
            val wide = Paragraph(word, input.style, Constraints(), input.density, input.fontFamilyResolver, spanStyles = input.text.subSequence(start, stop).spanStyles).maxIntrinsicWidth
            val fits = room(node)
            assertTrue("$said breaks inside \"$word\", ${wide.toInt()} px in ${fits.toInt()} px", wide > fits)
        }
        looked++
    }
    return looked
}

/** How wide a line's letters are on their own, without the hyphen Android may draw after them. */
internal fun TextLayoutResult.lettersWide(line: Int): Float {
    val input = layoutInput
    val start = getLineStart(line)
    val end = getLineEnd(line, visibleEnd = true)
    return Paragraph(input.text.text.substring(start, end), input.style, Constraints(), input.density, input.fontFamilyResolver, spanStyles = input.text.subSequence(start, end).spanStyles).maxIntrinsicWidth
}
