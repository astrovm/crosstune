package com.astrovm.crosstune

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.runtime.LaunchedEffect
import java.text.NumberFormat
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role

/** How a kind of card is called: the name it was given, else what the app calls it. */
@Composable
internal fun CardType.title(): String = when {
    !name.isNullOrBlank() -> name
    id == READ_TYPE -> stringResource(R.string.card_type_read)
    id == HEAR_TYPE -> stringResource(R.string.card_type_hear)
    id == TRANSLATE_TYPE -> stringResource(R.string.card_type_translate)
    else -> stringResource(R.string.card_type_new)
}

/**
 * The kinds of practice card, each with a switch for whether a line makes that card, and a tap to
 * set what its sides show; then a row to make another, as the first one is. Fixing how the card's
 * line reads, and each side's layers, open over it, and going back closes them one at a time.
 */
@Composable
internal fun CardTypesPanel(line: SavedLine, types: List<CardType>, turned: Boolean, actions: ScreenActions, onClose: () -> Unit, onPreview: (CardPreview?) -> Unit = {}) {
    var editing by remember { mutableStateOf<String?>(null) }
    var fixing by remember { mutableStateOf(false) }
    BackHandler { if (fixing) fixing = false else if (editing != null) editing = null else onClose() }
    val change = { list: List<CardType>, save: Boolean -> actions.onCardTypesChange(list, save) }
    val type = types.firstOrNull { it.id == editing }
    var undo by remember { mutableStateOf<List<CardType>?>(null) }
    var undoMessage by remember { mutableStateOf(R.string.history_removed) }
    LaunchedEffect(type?.id) { if (type == null) onPreview(null) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.42f
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("practice-card-options")
    ) {
        Column {
        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (fixing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { fixing = false }) { AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button)) }
                    Text(stringResource(R.string.practice_fix_reading), style = MaterialTheme.typography.titleSmall)
                }
                ReadingFields(line, onSave = { readings -> actions.onReadCardAs(readings); fixing = false }, onCancel = { fixing = false })
            } else if (type == null) {
                val last = types.count { it.on } == 1
                types.forEach { kind ->
                    val title = kind.title()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable { editing = kind.id }) {
                        Text(
                            title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                        )
                        // The name opens its editor, and this says so.
                        AppIcon(R.drawable.ic_chevron_right, contentDescription = null, modifier = Modifier.size(20.dp).alpha(0.6f))
                        Spacer(Modifier.width(12.dp))
                        }
                        // One kind always stays on, so there's always a card.
                        Switch(
                            checked = kind.on, enabled = !(kind.on && last), onCheckedChange = { on -> change(types.map { if (it.id == kind.id) it.copy(on = on) else it }, true) },
                            modifier = Modifier.semantics { contentDescription = title },
                            // The last one on can't be turned off, but it still has to look on.
                            colors = SwitchDefaults.colors(
                                disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        )
                    }
                }
                if (turned && line.readable.isNotEmpty()) {
                    TextButton(onClick = { fixing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        AppIcon(R.drawable.ic_edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.practice_fix_reading))
                    }
                }
                TextButton(
                    onClick = {
                        val id = generateSequence(1) { it + 1 }.map { "type$it" }.first { id -> types.none { it.id == id } }
                        change(types + CardType(id, null, true, types.first().options), true)
                        editing = id
                    },
                    contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    AppIcon(R.drawable.ic_add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.card_type_new))
                }
            } else {
                CardTypeEditor(line, type, actions, onPreview, change = { value, save -> change(types.map { if (it.id == type.id) value else it }, save) }, onBack = { editing = null }, onDelete = {
                    undo = types
                    undoMessage = R.string.history_removed
                    change(types.filterNot { it.id == type.id }, true)
                    editing = null
                }, preset = DEFAULT_CARD_TYPES.firstOrNull { it.id == type.id }, onReset = { preset ->
                    undo = types
                    undoMessage = R.string.card_type_reset
                    change(types.map { if (it.id == type.id) preset else it }, true)
                })
            }
        }
        undo?.let { previous ->
            Snackbar(
                action = { TextButton(onClick = { change(previous, true); undo = null }) { Text(stringResource(R.string.undo_button)) } },
                dismissAction = { IconButton(onClick = { undo = null }) { AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.floating_close)) } }
            ) { Text(stringResource(undoMessage)) }
        }
        }
    }
}

internal data class CardPreview(val type: CardType, val back: Boolean)

/** One kind of card: its name, and what its front and its back show, a row for each layer. */
@Composable
private fun CardTypeEditor(line: SavedLine, type: CardType, actions: ScreenActions, onPreview: (CardPreview?) -> Unit, change: (CardType, Boolean) -> Unit, onBack: () -> Unit, onDelete: () -> Unit, preset: CardType?, onReset: (CardType) -> Unit) {
    val title = type.title()
    var back by remember(type.id) { mutableStateOf(false) }
    LaunchedEffect(type, back) { onPreview(CardPreview(type, back)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button)) }
        OutlinedTextField(
            value = type.name ?: title, onValueChange = { change(type.copy(name = it), true) }, singleLine = true,
            label = { Text(stringResource(R.string.card_type_name)) }, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f)
        )
        if (preset == null) IconButton(onClick = onDelete) { AppIcon(R.drawable.ic_delete, contentDescription = stringResource(R.string.card_type_delete)) }
        // A preset can't go, but it can come back as it was, which only shows once it's been changed.
        else if (type.name != preset.name || type.options != preset.options) IconButton(onClick = { onReset(preset.copy(on = type.on)) }) {
            AppIcon(R.drawable.ic_refresh, contentDescription = stringResource(R.string.card_type_reset))
        }
    }
    val japanese = line.reading?.any(Readings::isKana) == true
    val script = if (japanese) Script.JAPANESE else Readings.displayScriptOf(listOf(line.text))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        listOf(R.string.practice_front, R.string.practice_back).forEachIndexed { index, label ->
            SegmentedButton(selected = back == (index == 1), onClick = { back = index == 1 }, shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                Text(stringResource(label))
            }
        }
    }
    SideRows(if (back) R.string.practice_back else R.string.practice_front, line, script, if (back) type.options.back else type.options.front,
        onPlay = actions.onReplayLine, canPlay = line.timed) {
        change(type.copy(options = if (back) type.options.copy(back = it) else type.options.copy(front = it)), true)
    }
}

/**
 * One side's layers, a row each, with an eye to show or hide it: the line's sound; the words, tapped on their tag to
 * show as written, in kana or in Latin letters; their reading over them; the same in Latin letters,
 * tapped to spell as typed; and what they mean. Pinching a row makes it bigger or smaller.
 */
@Composable
private fun SideRows(@StringRes label: Int, line: SavedLine, script: Script?, side: CardSide, onPlay: () -> Unit, canPlay: Boolean, onChange: (CardSide) -> Unit) {
    val japanese = script == Script.JAPANESE
    val wordsAs = buildList {
        add(WordsAs.WRITTEN)
        if (japanese) add(WordsAs.KANA)
        if (script != null) add(WordsAs.LATIN)
    }
    SoundRow(side.sound, onPlay, canPlay) { onChange(side.copy(sound = it)) }
    val glyph = { shown: WordsAs -> when (shown) { WordsAs.WRITTEN -> script?.glyph ?: "文"; WordsAs.KANA -> "あ"; WordsAs.LATIN -> "Aa" } }
    LayerRow(R.string.visuals_show_words, side.words, { onChange(side.copy(words = it)) }, line.shownAs(side.wordsAs, side.romajiTyped), side.wordsScale, MIN_WORDS_SCALE..MAX_WORDS_SCALE, { onChange(side.copy(wordsScale = it)) }) {
        if (wordsAs.size > 1) {
            val description = stringResource(when (side.wordsAs) {
                WordsAs.WRITTEN -> R.string.lyrics_display_original
                WordsAs.KANA -> R.string.lyrics_display_kana
                WordsAs.LATIN -> R.string.lyrics_display_latin
            })
            var formats by remember { mutableStateOf(false) }
            Box {
                Tag(glyph(side.wordsAs), description, null) { formats = true }
                DropdownMenu(expanded = formats, onDismissRequest = { formats = false }) {
                    wordsAs.forEach { format ->
                        val label = when (format) {
                            WordsAs.WRITTEN -> R.string.lyrics_display_original
                            WordsAs.KANA -> R.string.lyrics_display_kana
                            WordsAs.LATIN -> R.string.lyrics_display_latin
                        }
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onChange(side.copy(wordsAs = format)); formats = false },
                            leadingIcon = { Text(glyph(format)) }, trailingIcon = { if (side.wordsAs == format) AppIcon(R.drawable.ic_check, contentDescription = null) })
                    }
                }
            }
        }
    }
    // Readings go over the words as written, in Japanese or Chinese.
    if ((script == Script.JAPANESE || script == Script.CHINESE) && side.wordsAs == WordsAs.WRITTEN) {
        LayerRow(R.string.lyrics_readings, side.readings, { onChange(side.copy(readings = it)) }, line.reading, side.readingScale, MIN_READING_SCALE..MAX_READING_SCALE, { onChange(side.copy(readingScale = it)) })
    }
    if (script != null && side.wordsAs != WordsAs.LATIN) {
        LayerRow(R.string.lyrics_romanized, side.latin, { onChange(side.copy(latin = it)) }, line.latin(side.romajiTyped), side.wordsScale, MIN_WORDS_SCALE..MAX_WORDS_SCALE, { onChange(side.copy(wordsScale = it)) }) {
            if (japanese) Tag(if (side.romajiTyped) "wo" else "o", stringResource(R.string.lyrics_romaji_typed), side.romajiTyped) { onChange(side.copy(romajiTyped = !side.romajiTyped)) }
        }
    }
    LayerRow(R.string.lyrics_translation, side.translation, { onChange(side.copy(translation = it)) }, line.translation, side.translationScale, MIN_WORDS_SCALE..MAX_WORDS_SCALE, { onChange(side.copy(translationScale = it)) })
}

/** The line's sound as a layer of a card's side: an eye to turn it on or off, and the button it makes. */
@Composable
private fun SoundRow(on: Boolean, onPlay: () -> Unit, canPlay: Boolean, onToggle: (Boolean) -> Unit) {
    val name = stringResource(R.string.practice_play)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).toggleable(on, role = Role.Switch, onValueChange = onToggle).semantics { contentDescription = name }
        ) { AppIcon(if (on) R.drawable.ic_visibility else R.drawable.ic_visibility_off, contentDescription = null) }
        IconButton(onClick = onPlay, enabled = canPlay) {
            AppIcon(R.drawable.ic_play, contentDescription = stringResource(R.string.lyrics_play))
        }
    }
}

/** How much one pinch step, or Bigger and Smaller, changes a size. */
private const val SIZE_STEP = 1.15f

/**
 * A layer of a card's side: an eye to show or hide it, a preview of it on the card's line, as big as
 * it shows, and, if any, its [tag]. Pinched, the preview grows or shrinks within [range], and
 * Bigger and Smaller do the same for those who can't pinch.
 */
@Composable
private fun LayerRow(
    @StringRes label: Int, on: Boolean, onToggle: (Boolean) -> Unit, sample: String?, scale: Float, range: ClosedFloatingPointRange<Float>,
    onScale: (Float) -> Unit, tag: @Composable () -> Unit = {}
) {
    val name = stringResource(label)
    val bigger = stringResource(R.string.practice_bigger)
    val smaller = stringResource(R.string.practice_smaller)
    var latest by remember(scale) { mutableFloatStateOf(scale) }
    val pinch = rememberTransformableState { zoom, _, _ ->
        latest = (latest * zoom).coerceIn(range)
        onScale(latest)
    }
    var sizing by remember { mutableStateOf(false) }
    Column {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .transformable(pinch, canPan = { false })
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(bigger) { onScale((scale * SIZE_STEP).coerceIn(range)); true },
                    CustomAccessibilityAction(smaller) { onScale((scale / SIZE_STEP).coerceIn(range)); true }
                )
            }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).toggleable(on, role = Role.Switch, onValueChange = onToggle).semantics { contentDescription = name }
        ) { AppIcon(if (on) R.drawable.ic_visibility else R.drawable.ic_visibility_off, contentDescription = null) }
        Text(
            sample?.takeIf { it.isNotBlank() } ?: name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).alpha(if (on) 1f else 0.4f)
        )
        TextButton(onClick = { sizing = !sizing }) {
            if (label == R.string.visuals_show_words || label == R.string.lyrics_romanized) {
                AppIcon(R.drawable.ic_link, contentDescription = stringResource(R.string.lyrics_words_size), modifier = Modifier.size(14.dp))
            }
            Text(NumberFormat.getPercentInstance().format(sizeShare(latest, range)))
        }
        tag()
    }
    if (sizing) SizeSlider(label, scale, range, { value, _ -> onScale(value) })
    }
}

/** A small control on a row: [text], told to TalkBack as [description], and, when it's a switch, whether it is [on]. */
@Composable
private fun Tag(text: String, description: String, on: Boolean?, onClick: () -> Unit) {
    val modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(MaterialTheme.shapes.small)
    Box(
        contentAlignment = Alignment.Center,
        modifier = (if (on == null) modifier.clickable(role = Role.Button, onClick = onClick) else modifier.toggleable(on, role = Role.Switch, onValueChange = { onClick() }))
            .semantics { contentDescription = description }
    ) {
        Surface(shape = MaterialTheme.shapes.small, color = if (on == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}
