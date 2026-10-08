package com.astrovm.crosstune

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val FLOATING_LYRICS_TAG = "floating-lyrics"

/**
 * The words floating over other apps, in Android's picture-in-picture window: only what fits a
 * glance, the line being sung and the next one, on the cover's color. With nothing saying where the
 * song is, or no timed words, the song itself.
 */
@Composable
internal fun FloatingLyrics(state: UiState, loadArtwork: suspend (String) -> ImageBitmap?) {
    val song = state.lyricsFor
    val tint by animateColorAsState(
        coverColor(song?.artworkUrl, loadArtwork) ?: MaterialTheme.colorScheme.primary,
        tween(durationMillis = 700),
        label = "floating tint"
    )
    val surface = MaterialTheme.colorScheme.surface
    val lines = state.lyricLines
    val sung = rememberSungLine(lines, state.following)
    Surface(color = surface, modifier = Modifier.fillMaxSize().testTag(FLOATING_LYRICS_TAG)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to tint.copy(alpha = 0.55f), 1f to surface))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Each line rises in as it's sung.
            AnimatedContent(targetState = sung, transitionSpec = { rise(up = true) }, label = "floating line") { line ->
                if (line >= 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Line(lines[line].text.ifBlank { "♪" }, lit = true)
                        lines.getOrNull(line + 1)?.let { Line(it.text.ifBlank { "♪" }, lit = false) }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Line(song?.title.orEmpty(), lit = true)
                        Line(song?.artist.orEmpty(), lit = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(text: String, lit: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontSize = if (lit) 18.sp else 14.sp,
            lineHeight = if (lit) 22.sp else 18.sp,
            fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium
        ),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (lit) 1f else 0.6f),
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
}
