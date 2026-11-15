package com.astrovm.crosstune

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
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
    Layout(contents = listOf(label, value), modifier = modifier) { (labels, values), constraints ->
        val label = labels.single()
        val value = values.single()
        val width = constraints.maxWidth
        val space = gap.roundToPx()
        val valueWidth = value.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(width)
        // Half the row, or less if it needs less, but never less than its longest word.
        val labelNeeds = maxOf(minOf(label.maxIntrinsicWidth(Constraints.Infinity), width / 2), label.minIntrinsicWidth(Constraints.Infinity))
        if (valueWidth + space + labelNeeds <= width) {
            val shown = value.measure(Constraints(maxWidth = valueWidth))
            val labelWidth = width - shown.width - space
            val named = label.measure(Constraints(minWidth = labelWidth, maxWidth = labelWidth))
            val height = maxOf(named.height, shown.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                named.placeRelative(0, (height - named.height) / 2)
                shown.placeRelative(width - shown.width, (height - shown.height) / 2)
            }
        } else {
            val named = label.measure(Constraints(maxWidth = width))
            val shown = value.measure(Constraints(maxWidth = width))
            val below = STACKED_GAP.roundToPx()
            layout(width, (named.height + below + shown.height).coerceAtLeast(constraints.minHeight)) {
                named.placeRelative(0, 0)
                shown.placeRelative(0, named.height + below)
            }
        }
    }
}

/** Between a label and the value under it. */
private val STACKED_GAP = 2.dp
