package com.astrovm.crosstune

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A setting's [label] with what it's set to, [value], beside it on the right, or under it once the
 * two no longer fit side by side: at a big font or in a long language, the label keeps half the row
 * or all it needs, and never less than its longest word, so it's never squeezed to a word, or part of
 * one, a line beside a long value.
 */
@Composable
internal fun LabelAndValue(label: @Composable () -> Unit, modifier: Modifier = Modifier, gap: Dp = 12.dp, value: @Composable () -> Unit) {
    Layout(contents = listOf(label, value), modifier = modifier, measurePolicy = LabelAndValuePolicy(gap))
}

private class LabelAndValuePolicy(private val gap: Dp) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val label = measurables[0].single()
        val value = measurables[1].single()
        val space = gap.roundToPx()
        // Asked how tall it is at no width in particular, it's side by side.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else label.maxIntrinsicWidth(Constraints.Infinity) + space + value.maxIntrinsicWidth(Constraints.Infinity)
        val valueWidth = value.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(width)
        // Half the row, or less if it needs less, but never less than its longest word.
        val labelNeeds = maxOf(minOf(label.maxIntrinsicWidth(Constraints.Infinity), width / 2), label.minIntrinsicWidth(Constraints.Infinity))
        if (valueWidth + space + labelNeeds <= width) {
            val shown = value.measure(Constraints(maxWidth = valueWidth))
            val labelWidth = width - shown.width - space
            val named = label.measure(Constraints(minWidth = labelWidth, maxWidth = labelWidth))
            val height = maxOf(named.height, shown.height).coerceAtLeast(constraints.minHeight)
            return layout(width, height) {
                named.placeRelative(0, (height - named.height) / 2)
                shown.placeRelative(width - shown.width, (height - shown.height) / 2)
            }
        }
        val named = label.measure(Constraints(maxWidth = width))
        val shown = value.measure(Constraints(maxWidth = width))
        val below = STACKED_GAP.roundToPx()
        return layout(width, (named.height + below + shown.height).coerceAtLeast(constraints.minHeight)) {
            named.placeRelative(0, 0)
            shown.placeRelative(0, named.height + below)
        }
    }

    // Narrowest stacked, widest side by side.
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<List<IntrinsicMeasurable>>, height: Int): Int =
        maxOf(measurables[0].single().minIntrinsicWidth(height), measurables[1].single().maxIntrinsicWidth(height))

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<List<IntrinsicMeasurable>>, height: Int): Int =
        measurables[0].single().maxIntrinsicWidth(height) + gap.roundToPx() + measurables[1].single().maxIntrinsicWidth(height)
}

/** Between a label and the value under it. */
private val STACKED_GAP = 2.dp

/**
 * A [LabelAndValue] label's words. Android keeps "Song-Probleme" on one line unless words may be
 * hyphenated, so a label broke inside it at a big font; and a word wider than the whole row, as
 * "automáticamente" at the biggest, is hyphenated rather than cut at its last letter that fits.
 */
@Composable
internal fun LabelText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Text(text = text, style = style.copy(hyphens = Hyphens.Auto), modifier = modifier)
}

/**
 * An icon, or a step's number, [lead], with its [words] beside it; or the icon over the words where a
 * word of theirs wouldn't fit beside it, as at the biggest font, so no word is broken to make room.
 */
@Composable
internal fun LeadAndWords(lead: @Composable () -> Unit, modifier: Modifier = Modifier, gap: Dp = 12.dp, top: Boolean = false, words: @Composable () -> Unit) {
    Layout(contents = listOf(lead, words), modifier = modifier, measurePolicy = LeadAndWordsPolicy(gap, top))
}

/** Beside the words, the icon is in the middle of them, or at their [top] where more goes under them. */
private class LeadAndWordsPolicy(private val gap: Dp, private val top: Boolean) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val icon = measurables[0].single()
        val text = measurables[1].single()
        val space = gap.roundToPx()
        // Asked how tall it is at no width in particular, it's side by side.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else icon.maxIntrinsicWidth(Constraints.Infinity) + space + text.maxIntrinsicWidth(Constraints.Infinity)
        val shown = icon.measure(Constraints(maxWidth = width))
        if (shown.width + space + text.minIntrinsicWidth(Constraints.Infinity) <= width) {
            val said = text.measure(Constraints(maxWidth = width - shown.width - space))
            val height = maxOf(shown.height, said.height).coerceAtLeast(constraints.minHeight)
            return layout((shown.width + space + said.width).coerceIn(constraints.minWidth, width), height) {
                shown.placeRelative(0, if (top) 0 else (height - shown.height) / 2)
                said.placeRelative(shown.width + space, if (top) 0 else (height - said.height) / 2)
            }
        }
        val said = text.measure(Constraints(maxWidth = width))
        val below = STACKED_GAP.roundToPx()
        return layout(maxOf(shown.width, said.width).coerceIn(constraints.minWidth, width), (shown.height + below + said.height).coerceAtLeast(constraints.minHeight)) {
            shown.placeRelative(0, 0)
            said.placeRelative(0, shown.height + below)
        }
    }

    // Narrowest with the icon over the words, widest beside them.
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<List<IntrinsicMeasurable>>, height: Int): Int =
        maxOf(measurables[0].single().maxIntrinsicWidth(height), measurables[1].single().minIntrinsicWidth(height))

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<List<IntrinsicMeasurable>>, height: Int): Int =
        measurables[0].single().maxIntrinsicWidth(height) + gap.roundToPx() + measurables[1].single().maxIntrinsicWidth(height)
}
