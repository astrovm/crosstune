package com.astrovm.crosstune

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val FLOATING_LYRICS_TAG = "floating-lyrics"
internal const val FLOATING_CUSTOMIZE_TAG = "floating-customize"

/**
 * How the words floating over other apps look, as set on them: how much of a dark band they sit on,
 * from none, only the words, outlined, to almost solid; how big; which lines show around the one
 * sung; and where they are, in pixels, unset until first moved.
 */
internal data class FloatingOptions(
    val background: Float = 0f,
    val scale: Float = 1f,
    val previousLine: Boolean = false,
    val nextLine: Boolean = true,
    val locked: Boolean = false,
    val left: Int = -1,
    val top: Int = -1,
    /** MilkDrop visuals behind the words, with [background] as how dark they're shaded. */
    val visuals: Boolean = false
) {
    companion object {
        const val MIN_SCALE = 0.7f
        const val MAX_SCALE = 2f
        const val MAX_BACKGROUND = 0.9f
    }
}

/**
 * The floating words: only what fits a glance, the line being sung and, as set, the ones either side,
 * each whole. With nothing saying where the song is, or no timed words, the song itself. They float
 * over other apps, on as much of a dark band as set.
 */
@Composable
internal fun FloatingLyrics(state: UiState, modifier: Modifier) {
    val song = state.lyricsFor
    val options = state.floating
    val shade = Color.Black.copy(alpha = options.background)
    // White, outlined while there's little band behind them to read on.
    val ink = Color.White
    val outline = if (options.background < OUTLINED_BELOW) Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 2f), blurRadius = 8f) else null
    val lines = state.lyricLines
    val sung = rememberSungLine(lines, state.following)
    // As tall as the tallest lines so far, so a shorter line doesn't pull up what's under them; until
    // the song, the size or which lines show changes.
    var tallest by remember(song, options.scale, options.previousLine, options.nextLine) { mutableIntStateOf(0) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .testTag(FLOATING_LYRICS_TAG)
            .heightIn(min = with(LocalDensity.current) { tallest.toDp() })
            .onSizeChanged { if (it.height > tallest) tallest = it.height }
            .clip(MaterialTheme.shapes.large)
            .then(if (options.visuals) Modifier else Modifier.background(shade))
    ) {
        // The visuals behind the words, shaded as dark as the background is set.
        if (options.visuals) {
            MilkdropVisuals(state.visualsPlay, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(shade))
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // Each line rises in as it's sung.
            AnimatedContent(targetState = sung, transitionSpec = { rise(up = true) }, label = "floating line") { line ->
                // A line going out may be from the song before, whose words are gone.
                val current = lines.getOrNull(line)
                // As the words are set to show: as written, or in kana or Latin letters.
                val learning = state.learning
                fun shown(index: Int) = lines.getOrNull(index)?.let { (learning.insteadOf(index) ?: it.text).ifBlank { "♪" } }
                val (first, second) = if (current != null) {
                    shown(line)!! to shown(line + 1)
                } else {
                    song?.title.orEmpty() to song?.artist
                }
                val before = shown(line - 1)?.takeIf { options.previousLine }
                // What helps read the line being sung, as switched on for the words: its reading, and its translation.
                // Hangul's reading is its Latin letters, shown once.
                val help = state.learning.helpFor(line).takeIf { line >= 0 }
                val reading = help?.reading
                val helps = listOfNotNull(
                    reading?.reading?.takeIf { help.readings && it != first },
                    help?.latin?.takeIf { help.romanized && it.isNotBlank() && it != first },
                    help?.translation
                ).distinct()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    before?.let { Line(it, lit = false, options.scale * learning.wordsScale, ink, outline) }
                    Line(first, lit = true, options.scale * if (current != null) learning.wordsScale else 1f, ink, outline)
                    helps.forEach { text ->
                        val layerScale = when (text) {
                            reading?.reading -> (help?.readingScale ?: READING_SCALE) / READING_SCALE
                            help?.translation -> help?.translationScale ?: 1f
                            else -> learning.wordsScale
                        }
                        Line(text, lit = false, options.scale * layerScale, ink, outline)
                    }
                    if (options.nextLine) second?.let { Line(it, lit = false, options.scale * learning.wordsScale, ink, outline) }
                }
            }
        }
    }
}

@Composable
private fun Line(text: String, lit: Boolean, scale: Float, ink: Color, outline: Shadow?) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontSize = (if (lit) 18 else 14).sp * scale,
            lineHeight = (if (lit) 22 else 18).sp * scale,
            fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
            shadow = outline
        ),
        color = ink.copy(alpha = if (lit) 1f else 0.7f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/** What the words floating over other apps can do, besides showing. */
internal class FloatingActions(
    val onOpen: () -> Unit,
    val onToggleListening: () -> Unit,
    val onPlayPause: () -> Unit,
    val onLock: () -> Unit,
    val onClose: () -> Unit,
    /** Changed on them; kept when [save]d. */
    val onChange: (options: FloatingOptions, save: Boolean) -> Unit,
    /** A slider let go: keeps how they are now. */
    val onSave: () -> Unit,
    /** Where, in the window, a drag is a slider's own rather than one moving the words; null when nowhere. */
    val onEditorAt: (android.graphics.Rect?) -> Unit = {},
    val learning: ScreenActions = ScreenActions()
)

/**
 * The words over other apps. Tapped, they're outlined, with a few buttons under them, until they're
 * tapped again, or somewhere else is; the settings take the buttons' place, until Done. Moving and
 * pinching them are up to their window. Locked, touches go through to the app below, so none reach here.
 */
@Composable
internal fun FloatingOverApps(state: UiState, actions: FloatingActions, controls: Boolean, onControls: (Boolean) -> Unit) {
    var settings by remember { mutableStateOf(false) }
    LaunchedEffect(controls) { if (!controls) settings = false }
    LaunchedEffect(settings) { if (!settings) actions.onEditorAt(null) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        FloatingLyrics(
            state,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (controls) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large) else Modifier)
                // A click, not just a tap, so TalkBack can bring the buttons out too; without the ripple,
                // since the outline says it.
                .clickable(interactionSource = null, indication = null) { onControls(!controls) }
        )
        val shown = when {
            !controls -> FloatingPanel.NONE
            settings -> FloatingPanel.SETTINGS
            else -> FloatingPanel.BUTTONS
        }
        AnimatedContent(targetState = shown, transitionSpec = { fade() }, label = "floating panel") { panel ->
            when (panel) {
                FloatingPanel.NONE -> Box(Modifier.fillMaxWidth())
                FloatingPanel.BUTTONS -> FloatingButtons(state, actions, onSettings = { settings = true })
                else -> FloatingSettings(
                    state, actions,
                    onLock = {
                        onControls(false)
                        actions.onLock()
                    },
                    onDone = { onControls(false) }
                )
            }
        }
    }
}

private enum class FloatingPanel { NONE, BUTTONS, SETTINGS }

/**
 * The few things done most: play or pause the song in the app it follows, or, with no app, listen
 * along with the microphone; the settings; and close.
 */
@Composable
private fun FloatingButtons(state: UiState, actions: FloatingActions, onSettings: () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp, modifier = Modifier.padding(top = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
        if (state.following == null) {
            Text(stringResource(if (state.listeningAlong) R.string.listening_text else R.string.lyrics_listen_along),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val app = state.following?.takeIf { it.app != null }
            if (app != null) {
                val playing = app.clock.playing
                FilledIconButton(onClick = actions.onPlayPause) {
                    AppIcon(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                        contentDescription = stringResource(if (playing) R.string.lyrics_pause else R.string.lyrics_play)
                    )
                }
            } else {
                val listening = state.listeningAlong
                IconToggleButton(checked = listening, onCheckedChange = { actions.onToggleListening() }) {
                    AppIcon(
                        R.drawable.ic_recognize,
                        contentDescription = stringResource(if (listening) R.string.lyrics_stop_listening else R.string.lyrics_listen_along),
                        tint = if (listening) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
            }
            IconButton(onClick = onSettings) { AppIcon(R.drawable.ic_tune, contentDescription = stringResource(R.string.floating_customize)) }
            IconButton(onClick = actions.onClose) { AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.floating_close)) }
        }
        }
    }
}

/**
 * How they look, changed on them: the size, the band behind, the lines either side and the visuals;
 * letting touches through to the app below; and Crosstune itself, a tap away. Done puts it all away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FloatingSettings(state: UiState, actions: FloatingActions, onLock: () -> Unit, onDone: () -> Unit) {
    val options = state.floating
    var words by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().testTag(FLOATING_CUSTOMIZE_TAG)
            .onGloballyPositioned { area ->
                val bounds = area.boundsInWindow()
                actions.onEditorAt(android.graphics.Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
            }
    ) {
        Column(modifier = Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.55f)
            .verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (words) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (details) details = false else words = false }) { AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button)) }
                    Text(stringResource(R.string.lyrics_learn), style = MaterialTheme.typography.titleSmall)
                    AppIcon(R.drawable.ic_link, contentDescription = stringResource(R.string.lyrics_learn), modifier = Modifier.padding(start = 8.dp).size(18.dp))
                }
                WordsOptions(state.learning, actions.learning, details, { details = it })
            } else {
            TextButton(onClick = { words = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                AppIcon(R.drawable.ic_translate, contentDescription = null)
                Text(stringResource(R.string.lyrics_learn), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                AppIcon(R.drawable.ic_chevron_right, contentDescription = null)
            }
            Column {
                SizeSlider(R.string.floating_text_size, options.scale, FloatingOptions.MIN_SCALE..FloatingOptions.MAX_SCALE, onChange = { value, save ->
                    if (save) actions.onSave() else actions.onChange(options.copy(scale = value), false)
                })
                SizeSlider(R.string.floating_background, options.background, 0f..FloatingOptions.MAX_BACKGROUND, onChange = { value, save ->
                    if (save) actions.onSave() else actions.onChange(options.copy(background = value), false)
                }, icon = R.drawable.ic_shade)
            }
            MenuSwitch(R.string.floating_previous_line, options.previousLine) { actions.onChange(options.copy(previousLine = it), true) }
            MenuSwitch(R.string.floating_next_line, options.nextLine) { actions.onChange(options.copy(nextLine = it), true) }
            MenuSwitch(R.string.floating_visuals, options.visuals) { actions.onChange(options.copy(visuals = it), true) }
            TextButton(onClick = onLock, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                AppIcon(R.drawable.ic_lock, contentDescription = null)
                Text(stringResource(R.string.floating_lock), modifier = Modifier.weight(1f).padding(start = 12.dp))
            }
            }
            FlowRow(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = actions.onOpen) {
                    AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.floating_open), modifier = Modifier.padding(start = 8.dp))
                }
                Button(onClick = onDone) { Text(stringResource(R.string.floating_done)) }
            }
        }
    }
}

/**
 * Said before Android's own settings open to let the words float over other apps, so it's clear why
 * they're there and that coming back is all it takes after.
 */
@Composable
internal fun FloatPermissionDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppIcon(R.drawable.ic_float, contentDescription = null) },
        title = { Text(stringResource(R.string.floating_permission_title)) },
        text = { Text(stringResource(R.string.floating_permission_text)) },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.floating_permission_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } }
    )
}

/** Below this much band, the words are outlined to read on whatever's under them. */
private const val OUTLINED_BELOW = 0.4f
