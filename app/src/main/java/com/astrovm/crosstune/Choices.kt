package com.astrovm.crosstune

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** How far through the things being fixed one by one: step [step] of [lastStep]. */
@Composable
internal fun StepProgress(step: Int, lastStep: Int) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            text = stringResource(R.string.setup_step, step, lastStep),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        // The bar fills in rather than jumping from step to step.
        val progress by animateFloatAsState(step / lastStep.toFloat(), spring(stiffness = Spring.StiffnessLow), label = "progress")
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

/** The service the user listens in, whose links already open where they should. */
internal fun UiState.listeningService(): MusicService? =
    // Opening in Crosstune, links don't go to one app by themselves, so none is the one listened in.
    if (hasDefault && !linksInCrosstune) (defaultDestination as? Destination.Service)?.service else null

/** An installed app's own icon, or nothing if it's just been uninstalled. */
@Composable
internal fun PackageIcon(packageName: String, size: Dp = 32.dp) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(size).clip(MaterialTheme.shapes.small)) }
}

/**
 * Installed apps the user is asked to stop: on Android 12+ every one seen taking the
 * links that still claims some Crosstune opens (kept once seen, so fixing one doesn't remove its
 * row, but dropped once the user stops opening its links); before that, every installed app whose
 * links Crosstune opens, since Android can't say which really do.
 */
@Composable
internal fun appsToStop(state: UiState): List<LinkApp> {
    var seen by rememberSaveable { mutableStateOf("") }
    val blocking = state.blockingApps ?: return state.installedSourceApps
    val claiming = state.claimingApps.orEmpty()
    val known = seen.split(',').filter { it.isNotEmpty() }
    val all = (known + blocking.map { it.packageName }).distinct()
    SideEffect { if (all.size != known.size) seen = all.joinToString(",") }
    return claiming.filter { it.packageName in all }
}
