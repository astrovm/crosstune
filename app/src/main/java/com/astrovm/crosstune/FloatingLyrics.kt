package com.astrovm.crosstune

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal const val FLOATING_LYRICS_TAG = "floating-lyrics"
internal const val FLOATING_CUSTOMIZE_TAG = "floating-customize"

/**
 * How the words floating over other apps look, as set on them: how much of a dark band they sit on,
 * from none, only the words, outlined, to almost solid; how big; which lines show around the one
 * sung; and where they are and how wide, in pixels, unset until first moved or resized.
 */
internal data class FloatingOptions(
    val background: Float = 0f,
    val scale: Float = 1f,
    val previousLine: Boolean = false,
    val nextLine: Boolean = true,
    val locked: Boolean = false,
    val left: Int = -1,
    val top: Int = -1,
    val width: Int = -1
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
    val ground = Modifier.clip(MaterialTheme.shapes.large).background(Color.Black.copy(alpha = options.background))
    // White, outlined while there's little band behind them to read on.
    val ink = Color.White
    val outline = if (options.background < OUTLINED_BELOW) Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 2f), blurRadius = 8f) else null
    val lines = state.lyricLines
    val sung = rememberSungLine(lines, state.following)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .testTag(FLOATING_LYRICS_TAG)
            .then(ground)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Each line rises in as it's sung.
        AnimatedContent(targetState = sung, transitionSpec = { rise(up = true) }, label = "floating line") { line ->
            val (first, second) = if (line >= 0) {
                lines[line].text.ifBlank { "♪" } to lines.getOrNull(line + 1)?.text?.ifBlank { "♪" }
            } else {
                song?.title.orEmpty() to song?.artist
            }
            val before = lines.getOrNull(line - 1)?.text?.ifBlank { "♪" }?.takeIf { line > 0 && options.previousLine }
            // What helps read the line being sung, as switched on for the words: its reading, and its translation.
            val help = state.learning.helpFor(line).takeIf { line >= 0 }
            val reading = help?.reading
            val helps = listOfNotNull(
                reading?.reading?.takeIf { help.readings && it != first },
                reading?.romanized?.takeIf { help.romanized && it.isNotBlank() },
                help?.translation
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                before?.let { Line(it, lit = false, options.scale, ink, outline) }
                Line(first, lit = true, options.scale, ink, outline)
                helps.forEach { Line(it, lit = false, options.scale, ink, outline) }
                if (options.nextLine) second?.let { Line(it, lit = false, options.scale, ink, outline) }
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
    val onLock: () -> Unit,
    val onClose: () -> Unit,
    /** Changed on them; kept when [save]d. */
    val onChange: (options: FloatingOptions, save: Boolean) -> Unit,
    /** A slider let go: keeps how they are now. */
    val onSave: () -> Unit,
    /** Where, in the window, a drag is a slider's own rather than one moving the words; null when nowhere. */
    val onSlidersAt: (android.graphics.Rect?) -> Unit = {}
)

/**
 * The words over other apps, tapped for what they can do, which hides again after a moment unless
 * they're being changed. Moving, resizing and pinching them are up to their window, which shows the
 * sides to drag while the buttons are out. Locked, touches go through to the app below, so none reach here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FloatingOverApps(state: UiState, actions: FloatingActions) {
    var controls by remember { mutableStateOf(false) }
    var customizing by remember { mutableStateOf(false) }
    LaunchedEffect(controls, customizing) {
        if (controls && !customizing) {
            delay(CONTROLS_SHOWN_MS)
            controls = false
        }
    }
    LaunchedEffect(customizing) { if (!customizing) actions.onSlidersAt(null) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center) {
            FloatingLyrics(
                state,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            controls = !controls
                            if (!controls) customizing = false
                        }
                    }
            )
            // The sides to drag to make them wider or narrower.
            if (controls) {
                Handle(Modifier.align(Alignment.CenterStart))
                Handle(Modifier.align(Alignment.CenterEnd))
            }
        }
        AnimatedVisibility(controls) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(top = 6.dp)) {
                // Narrowed, the buttons go on two rows rather than squeezing each other.
                FlowRow(horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Control(R.drawable.ic_open_in_new, stringResource(R.string.floating_open), actions.onOpen)
                    Control(
                        R.drawable.ic_recognize,
                        stringResource(if (state.listeningAlong) R.string.lyrics_stop_listening else R.string.lyrics_listen_along),
                        actions.onToggleListening
                    )
                    Control(R.drawable.ic_tune, stringResource(R.string.floating_customize)) { customizing = !customizing }
                    // Locked, they couldn't be tapped anyway.
                    Control(R.drawable.ic_lock, stringResource(R.string.floating_lock)) {
                        controls = false
                        customizing = false
                        actions.onLock()
                    }
                    Control(R.drawable.ic_close, stringResource(R.string.floating_close), actions.onClose)
                }
            }
        }
        AnimatedVisibility(controls && customizing) {
            Customize(state.floating, actions)
        }
    }
}

/** How they look, changed on them: the band behind, the size, and the lines either side. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Customize(options: FloatingOptions, actions: FloatingActions) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().testTag(FLOATING_CUSTOMIZE_TAG)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(
                modifier = Modifier.onGloballyPositioned { area ->
                    val bounds = area.boundsInWindow()
                    actions.onSlidersAt(android.graphics.Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
                }
            ) {
                Text(stringResource(R.string.floating_background), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = options.background,
                    onValueChange = { actions.onChange(options.copy(background = it), false) },
                    onValueChangeFinished = actions.onSave,
                    valueRange = 0f..FloatingOptions.MAX_BACKGROUND
                )
                Text(stringResource(R.string.floating_text_size), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = options.scale,
                    onValueChange = { actions.onChange(options.copy(scale = it), false) },
                    onValueChangeFinished = actions.onSave,
                    valueRange = FloatingOptions.MIN_SCALE..FloatingOptions.MAX_SCALE
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = options.previousLine,
                    onClick = { actions.onChange(options.copy(previousLine = !options.previousLine), true) },
                    label = { Text(stringResource(R.string.floating_previous_line)) }
                )
                FilterChip(
                    selected = options.nextLine,
                    onClick = { actions.onChange(options.copy(nextLine = !options.nextLine), true) },
                    label = { Text(stringResource(R.string.floating_next_line)) }
                )
            }
        }
    }
}

@Composable
private fun Handle(modifier: Modifier) {
    Box(modifier.padding(horizontal = 3.dp).size(width = 5.dp, height = 32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)))
}

@Composable
private fun Control(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { AppIcon(icon, contentDescription = label) }
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

/** How long the floating words' buttons stay after a tap. */
private const val CONTROLS_SHOWN_MS = 6_000L

/** Below this much band, the words are outlined to read on whatever's under them. */
private const val OUTLINED_BELOW = 0.4f
