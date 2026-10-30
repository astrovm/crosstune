package com.astrovm.crosstune

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    val type = types.firstOrNull { it.id == editing }
    val inside = fixing || type != null
    val goingBack = swipedAway(shown = editing to fixing) { if (fixing) fixing = false else if (editing != null) editing = null else onClose() }
    val change = { list: List<CardType>, save: Boolean -> actions.onCardTypesChange(list, save) }
    LaunchedEffect(type?.id) { if (type == null) onPreview(null) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.42f
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().then(if (inside) Modifier else Modifier.goingAway(goingBack, TransformOrigin(1f, 0f))).testTag("practice-card-options")
    ) {
        Column {
        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).then(if (inside) Modifier.goingAway(goingBack, TransformOrigin.Center) else Modifier).padding(16.dp)) {
            if (fixing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { fixing = false }) { AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button)) }
                    Text(stringResource(R.string.practice_fix_reading), style = MaterialTheme.typography.titleSmall)
                }
                ReadingFields(line, onSave = { readings -> actions.onReadCardAs(readings); fixing = false }, onCancel = { fixing = false })
            } else if (type == null) {
                val last = types.count { it.on } == 1
                types.forEach { kind -> KindRow(kind, line, last, onToggle = { on -> change(types.map { if (it.id == kind.id) it.copy(on = on) else it }, true) }, onEdit = { editing = kind.id }) }
                if (turned && line.readable.isNotEmpty()) {
                    TextButton(onClick = { fixing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        AppIcon(R.drawable.ic_edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.practice_fix_reading))
                    }
                }
                KindFrame(on = false, modifier = Modifier.clickable {
                    val id = generateSequence(1) { it + 1 }.map { "type$it" }.first { id -> types.none { it.id == id } }
                    change(types + CardType(id, null, true, types.first().options), true)
                    editing = id
                }) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { AppIcon(R.drawable.ic_add, contentDescription = null, modifier = Modifier.size(24.dp)) }
                    Text(stringResource(R.string.card_type_new), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            } else {
                CardTypeEditor(type, onPreview, change = { value, save -> change(types.map { if (it.id == type.id) value else it }, save) }, onBack = { editing = null }, onDelete = {
                    change(types.filterNot { it.id == type.id }, true)
                    editing = null
                }, preset = DEFAULT_CARD_TYPES.firstOrNull { it.id == type.id }, onReset = { preset ->
                    change(types.map { if (it.id == type.id) preset else it }, true)
                })
            }
        }
        }
    }
}

/** A kind of card in the list: what it is and what each side shows, lit while it's on, a tap to turn it on or off and a pencil to set its sides. */
@Composable
private fun KindRow(kind: CardType, line: SavedLine, last: Boolean, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val title = kind.title()
    val script = cardScript(line)
    // One kind always stays on, so there's always a card.
    KindFrame(kind.on) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
                .toggleable(kind.on, enabled = !(kind.on && last), role = Role.Switch, onValueChange = onToggle)
                .semantics { contentDescription = title }
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { SideMarks(kind.options.front, script, first = true, size = 24.dp) }
            Column(Modifier.weight(1f).clearAndSetSemantics {}) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.alpha(0.7f)) {
                    SideMarks(kind.options.front, script, size = 14.dp)
                    AppIcon(R.drawable.ic_chevron_right, contentDescription = null, modifier = Modifier.size(14.dp))
                    SideMarks(kind.options.back, script, size = 14.dp)
                }
            }
        }
        IconButton(onClick = onEdit) { AppIcon(R.drawable.ic_edit, contentDescription = "$title, ${stringResource(R.string.card_type_edit)}") }
    }
}

/** A row of the list, lit when [on], so what's picked shows without a word. */
@Composable
private fun KindFrame(on: Boolean, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (on) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if (on) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).then(modifier)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), content = content)
    }
}

/** What a side shows, one mark each: its sound, its words, how they read, the same in Latin letters, what they mean; or only the first. */
@Composable
private fun SideMarks(side: CardSide, script: Script?, size: Dp, first: Boolean = false) {
    val marks = buildList<@Composable () -> Unit> {
        if (side.sound) add { AppIcon(R.drawable.ic_play, contentDescription = null, modifier = Modifier.size(size)) }
        if (side.words) add { Text(script?.glyph ?: "文", fontSize = (size.value * 0.85f).sp) }
        if (side.readings) add { Text("あ", fontSize = (size.value * 0.85f).sp) }
        if (side.latin) add { Text("a", fontSize = (size.value * 0.85f).sp) }
        if (side.translation) add { AppIcon(R.drawable.ic_translate, contentDescription = null, modifier = Modifier.size(size)) }
    }
    (if (first) marks.take(1) else marks).forEach { it() }
}

internal data class CardPreview(val type: CardType, val back: Boolean)

/** One kind of card: its name, and which side the card above shows, there to be set by tapping its lines. */
@Composable
private fun CardTypeEditor(type: CardType, onPreview: (CardPreview?) -> Unit, change: (CardType, Boolean) -> Unit, onBack: () -> Unit, onDelete: () -> Unit, preset: CardType?, onReset: (CardType) -> Unit) {
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
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        listOf(R.string.practice_front, R.string.practice_back).forEachIndexed { index, label ->
            SegmentedButton(selected = back == (index == 1), onClick = { back = index == 1 }, shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                Text(stringResource(label))
            }
        }
    }
}

/**
 * One side of the card as the menu: every layer it could show, in the place it takes on the card, and a tap
 * shows or hides it, the hidden ones dashed. The line's sound is an eye and its play button; the words, their
 * reading, the same in Latin letters, and what they mean are the lines themselves. Pinching a line makes it
 * bigger or smaller; holding the words picks how they're written, and holding the Latin letters, how they're spelled.
 */
@Composable
internal fun CardSideEditor(line: SavedLine, side: CardSide, onPlay: () -> Unit, canPlay: Boolean, onChange: (CardSide) -> Unit) {
    val script = cardScript(line)
    val japanese = script == Script.JAPANESE
    val wordsAs = buildList {
        add(WordsAs.WRITTEN)
        if (japanese) add(WordsAs.KANA)
        if (script != null) add(WordsAs.LATIN)
    }
    val words = MaterialTheme.typography.headlineMedium.let { it.copy(fontSize = it.fontSize * side.wordsScale, lineHeight = it.lineHeight * side.wordsScale, fontWeight = FontWeight.Bold) }
    val small = MaterialTheme.typography.bodyLarge
    SoundRow(side.sound, onPlay, canPlay) { onChange(side.copy(sound = it)) }
    // Readings go over the words as written, in Japanese or Chinese.
    if ((script == Script.JAPANESE || script == Script.CHINESE) && side.wordsAs == WordsAs.WRITTEN) {
        CardLayer(R.string.lyrics_readings, side.readings, { onChange(side.copy(readings = it)) }, side.readingScale, MIN_READING_SCALE..MAX_READING_SCALE, { onChange(side.copy(readingScale = it)) }) {
            Text(line.reading ?: stringResource(R.string.lyrics_readings), style = small.copy(fontSize = small.fontSize * side.readingScale, lineHeight = small.lineHeight * side.readingScale).writing(line.reading.orEmpty()))
        }
    }
    var formats by remember { mutableStateOf(false) }
    val original = stringResource(R.string.lyrics_display_original)
    val kana = stringResource(R.string.lyrics_display_kana)
    val latin = stringResource(R.string.lyrics_display_latin)
    val nameOf = { shown: WordsAs -> when (shown) { WordsAs.WRITTEN -> original; WordsAs.KANA -> kana; WordsAs.LATIN -> latin } }
    CardLayer(
        R.string.visuals_show_words, side.words, { onChange(side.copy(words = it)) }, side.wordsScale, MIN_WORDS_SCALE..MAX_WORDS_SCALE, { onChange(side.copy(wordsScale = it)) },
        more = if (wordsAs.size > 1) wordsAs.map { shown -> CustomAccessibilityAction(nameOf(shown)) { onChange(side.copy(wordsAs = shown)); true } } else emptyList(),
        onHold = { if (wordsAs.size > 1) formats = true },
        menu = {
            DropdownMenu(expanded = formats, onDismissRequest = { formats = false }) {
                wordsAs.forEach { shown ->
                    DropdownMenuItem(text = { Text(nameOf(shown)) }, onClick = { onChange(side.copy(wordsAs = shown)); formats = false },
                        trailingIcon = { if (side.wordsAs == shown) AppIcon(R.drawable.ic_check, contentDescription = null) })
                }
            }
        }
    ) {
        Text(line.shownAs(side.wordsAs, side.romajiTyped).ifBlank { line.text }, style = words.writing(line.text))
    }
    if (script != null && side.wordsAs != WordsAs.LATIN) {
        val typed = stringResource(R.string.lyrics_romaji_typed)
        // How the Latin letters are spelled only changes for Japanese: as it sounds, or as it's typed.
        CardLayer(
            R.string.lyrics_romanized, side.latin, { onChange(side.copy(latin = it)) }, side.wordsScale, MIN_WORDS_SCALE..MAX_WORDS_SCALE, { onChange(side.copy(wordsScale = it)) },
            more = if (japanese) listOf(CustomAccessibilityAction(typed) { onChange(side.copy(romajiTyped = !side.romajiTyped)); true }) else emptyList(),
            onHold = { if (japanese) onChange(side.copy(romajiTyped = !side.romajiTyped)) }
        ) {
            Text(line.latin(side.romajiTyped) ?: stringResource(R.string.lyrics_romanized), style = small.copy(fontSize = small.fontSize * side.wordsScale, lineHeight = small.lineHeight * side.wordsScale))
        }
    }
    CardLayer(R.string.lyrics_translation, side.translation, { onChange(side.copy(translation = it)) }, side.translationScale, MIN_WORDS_SCALE..MAX_WORDS_SCALE, { onChange(side.copy(translationScale = it)) }) {
        val title = MaterialTheme.typography.titleLarge
        Text(line.translation ?: stringResource(R.string.lyrics_translation), style = title.copy(fontSize = title.fontSize * side.translationScale, lineHeight = title.lineHeight * side.translationScale))
    }
}

/** The script [line]'s words are written in, which decides what can be shown over or beside them. */
internal fun cardScript(line: SavedLine): Script? =
    if (line.reading?.any(Readings::isKana) == true) Script.JAPANESE else Readings.displayScriptOf(listOf(line.text))

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
 * A line of the card being set: tapped, it shows or hides, hidden it's dashed and dim. Pinching it changes its size within
 * [range], and Bigger and Smaller do the same for those who can't pinch; [more] are other things holding it does, told the same way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardLayer(
    @StringRes label: Int, on: Boolean, onToggle: (Boolean) -> Unit, scale: Float, range: ClosedFloatingPointRange<Float>, onScale: (Float) -> Unit,
    more: List<CustomAccessibilityAction> = emptyList(), onHold: (() -> Unit)? = null, menu: @Composable () -> Unit = {}, content: @Composable () -> Unit
) {
    val name = stringResource(label)
    val bigger = stringResource(R.string.practice_bigger)
    val smaller = stringResource(R.string.practice_smaller)
    var latest by remember(scale) { mutableFloatStateOf(scale) }
    val pinch = rememberTransformableState { _, zoom, _, _ ->
        latest = (latest * zoom).coerceIn(range)
        onScale(latest)
    }
    val line = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("card-layer-$label")
            .transformable(pinch, canPan = { false })
            .clip(shape)
            .then(
                if (on) Modifier.border(1.5.dp, line.copy(alpha = 0.6f), shape)
                else Modifier.drawBehind {
                    drawRoundRect(line.copy(alpha = 0.4f), cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
                }
            )
            .combinedClickable(role = Role.Switch, onClick = { onToggle(!on) }, onLongClick = onHold)
            .semantics {
                contentDescription = name
                toggleableState = ToggleableState(on)
                customActions = listOf(
                    CustomAccessibilityAction(bigger) { onScale((scale * SIZE_STEP).coerceIn(range)); true },
                    CustomAccessibilityAction(smaller) { onScale((scale / SIZE_STEP).coerceIn(range)); true }
                ) + more
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).alpha(if (on) 1f else 0.5f).clearAndSetSemantics {}) { content() }
        menu()
    }
}
