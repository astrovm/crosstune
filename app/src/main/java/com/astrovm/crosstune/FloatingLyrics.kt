package com.astrovm.crosstune

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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal const val FLOATING_LYRICS_TAG = "floating-lyrics"

/** What the floating words sit on. */
internal enum class FloatingLook {
    /** The cover's color, fading into the app's. */
    COVER,
    PLAIN,
    /** A dark, see-through band over whatever's below. */
    SEE_THROUGH,
    /** Only the words, outlined so they read on anything. */
    NONE;

    /**
     * Android's own floating window is always a solid box, so what shows through floats over other
     * apps instead, which Android has to allow.
     */
    val overApps: Boolean get() = this == SEE_THROUGH || this == NONE
}

internal enum class FloatingSize(val scale: Float) { SMALL(0.85f), MEDIUM(1f), LARGE(1.3f) }

/** How the floating words look, and, over other apps, where they are and whether touches go through. */
internal data class FloatingOptions(
    val look: FloatingLook = FloatingLook.COVER,
    val size: FloatingSize = FloatingSize.MEDIUM,
    val nextLine: Boolean = true,
    val locked: Boolean = false,
    /** How far down the screen they float, in pixels; unset until first moved. */
    val top: Int = -1
)

/**
 * The floating words: only what fits a glance, the line being sung and, as set, the next one. With
 * nothing saying where the song is, or no timed words, the song itself.
 */
@Composable
internal fun FloatingLyrics(
    state: UiState,
    loadArtwork: suspend (String) -> ImageBitmap?,
    overApps: Boolean = false,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val song = state.lyricsFor
    val options = state.floating
    // Android's own window shows nothing of what's below it, so there the words sit on the app's color.
    val look = if (overApps || !options.look.overApps) options.look else FloatingLook.PLAIN
    val surface = MaterialTheme.colorScheme.surface
    val ground = when (look) {
        FloatingLook.COVER -> {
            val tint by animateColorAsState(
                coverColor(song?.artworkUrl, loadArtwork) ?: MaterialTheme.colorScheme.primary,
                tween(durationMillis = 700),
                label = "floating tint"
            )
            Modifier.background(surface).background(Brush.verticalGradient(0f to tint.copy(alpha = 0.55f), 1f to surface))
        }
        FloatingLook.PLAIN -> Modifier.background(surface)
        FloatingLook.SEE_THROUGH -> Modifier.clip(MaterialTheme.shapes.large).background(Color.Black.copy(alpha = 0.55f))
        // None: only the words.
        else -> Modifier
    }
    // Over another app the words are white, which reads on the dark band or, outlined, on anything.
    val ink = if (look.overApps) Color.White else MaterialTheme.colorScheme.onSurface
    val outline = if (look == FloatingLook.NONE) Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 2f), blurRadius = 8f) else null
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Line(first, lit = true, options.size.scale, ink, outline)
                if (options.nextLine) second?.let { Line(it, lit = false, options.size.scale, ink, outline) }
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
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}

/** What the words floating over other apps can do, besides showing. */
internal class FloatingActions(
    val onOpen: () -> Unit,
    val onToggleListening: () -> Unit,
    val onLock: () -> Unit,
    val onClose: () -> Unit
)

/**
 * The words over other apps, tapped for what they can do, which hides again after a moment. Dragging
 * them up or down is up to their window. Locked, touches go through to the app below, so none reach here.
 */
@Composable
internal fun FloatingOverApps(state: UiState, actions: FloatingActions) {
    var controls by remember { mutableStateOf(false) }
    LaunchedEffect(controls) {
        if (controls) {
            delay(CONTROLS_SHOWN_MS)
            controls = false
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        FloatingLyrics(
            state,
            // Over other apps the words show what's below, never the cover's color.
            loadArtwork = { null },
            overApps = true,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { controls = !controls } }
        )
        AnimatedVisibility(controls) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Row {
                    Control(R.drawable.ic_open_in_new, stringResource(R.string.floating_open), actions.onOpen)
                    Control(
                        R.drawable.ic_recognize,
                        stringResource(if (state.listeningAlong) R.string.lyrics_stop_listening else R.string.lyrics_listen_along),
                        actions.onToggleListening
                    )
                    // Locked, they couldn't be tapped anyway.
                    Control(R.drawable.ic_lock, stringResource(R.string.floating_lock)) {
                        controls = false
                        actions.onLock()
                    }
                    Control(R.drawable.ic_close, stringResource(R.string.floating_close), actions.onClose)
                }
            }
        }
    }
}

@Composable
private fun Control(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { AppIcon(icon, contentDescription = label) }
}

/** How long the floating words' buttons stay after a tap. */
private const val CONTROLS_SHOWN_MS = 4_000L
