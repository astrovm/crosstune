package com.astrovm.crosstune

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** While the microphone listens for a song: waves going out from it, and a way to stop. */
@Composable
internal fun ListeningScreen(actions: ScreenActions) {
    BackHandler(onBack = actions.onStopListening)
    Page(title = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Waves()
            Text(
                text = stringResource(R.string.listening_text),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            TextButton(onClick = actions.onStopListening, modifier = Modifier.padding(top = 24.dp)) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    }
}

/** Rings that grow and fade from the microphone, one after another. */
@Composable
private fun Waves() {
    val transition = rememberInfiniteTransition(label = "waves")
    val waves = (0 until WAVES).map { wave ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(WAVE_MS, easing = LinearEasing),
                initialStartOffset = StartOffset(wave * WAVE_MS / WAVES)
            ),
            label = "wave"
        )
    }
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(220.dp)
            .drawBehind {
                waves.forEach { wave ->
                    val grown = wave.value
                    drawCircle(color.copy(alpha = 0.3f * (1 - grown)), radius = size.minDimension / 2 * (0.4f + 0.6f * grown))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                AppIcon(R.drawable.ic_recognize, contentDescription = null, modifier = Modifier.size(40.dp))
            }
        }
    }
}

private const val WAVES = 3
private const val WAVE_MS = 2_400
