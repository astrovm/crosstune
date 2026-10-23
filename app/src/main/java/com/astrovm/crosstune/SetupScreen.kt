package com.astrovm.crosstune

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp

private enum class SetupStep { DESTINATION, LINKS }

/**
 * First-run setup: where the user listens, unless there's just one music app to pick, then one
 * list of what Android needs changed for links to open in Crosstune and lyrics to follow music
 * apps, each with a button to its page and checked off once done. Every source's links start on.
 * Choices apply as they're made, so leaving halfway keeps them; setup shows again until finished.
 */
@Composable
internal fun SetupScreen(state: UiState, actions: ScreenActions) {
    // 0 is the welcome page; after that, a position in [steps].
    var position by rememberSaveable { mutableIntStateOf(0) }
    val appsToFix = appsToStop(state)
    val only = onlyMusicApp(state)
    // With just one music app installed, that's most likely where the user listens, so it's picked
    // and not asked, and can be changed in settings.
    LaunchedEffect(only) { if (only != null && !state.hasDefault) actions.onTargetChange(only) }
    val steps = if (only != null) listOf(SetupStep.LINKS) else SetupStep.entries
    // Installing or removing a music app meanwhile can leave one step fewer.
    val current = position.coerceAtMost(steps.size)
    val step = if (current == 0) null else steps[current - 1]
    // Which steps show, and what they show, depends on what Android says about other apps and
    // links, so a step restored before that's known waits for it.
    val ready = step == null || state.systemStateKnown
    val back = { if (ready) position = current - 1 }
    // System back steps back like the Back button instead of leaving setup.
    BackHandler(enabled = current > 0, onBack = back)

    // A step that was scrolled down mustn't leave the next one scrolled too.
    val scroll = key(position) { rememberScrollState() }
    Page(
        scrollState = scroll,
        title = null,
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                val buttonModifier = Modifier
                    .weight(1f)
                    .widthIn(max = 290.dp)
                    .heightIn(min = 52.dp)
                val backPress = rememberPress()
                val nextPress = rememberPress()
                if (step != null) {
                    OutlinedButton(onClick = back, enabled = ready, interactionSource = backPress.source, modifier = buttonModifier.then(backPress.modifier)) {
                        Text(stringResource(R.string.back_button), style = MaterialTheme.typography.labelLarge)
                    }
                }
                val isLast = current == steps.size
                val onNext = { if (isLast) actions.onCompleteSetup() else position = current + 1 }
                // There's no built-in default any more, so one has to be picked.
                val enabled = ready && (step != SetupStep.DESTINATION || state.hasDefault)
                // Leaving with something still to fix is skipping it, so the button says so and
                // the list's own buttons stay the main ones. What Android can't tell about isn't known to be left.
                if (step == SetupStep.LINKS && state.checks(appsToFix).any { it.done == false }) {
                    FilledTonalButton(onClick = onNext, enabled = enabled, interactionSource = nextPress.source, modifier = buttonModifier.then(nextPress.modifier)) {
                        Text(stringResource(R.string.setup_skip_for_now), style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Button(onClick = onNext, enabled = enabled, interactionSource = nextPress.source, modifier = buttonModifier.then(nextPress.modifier)) {
                        Text(
                            stringResource(
                                when {
                                    step == null -> R.string.setup_get_started
                                    isLast -> R.string.setup_finish
                                    else -> R.string.next_button
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    ) {
        // Next slides the new step in from the end, Back from the start.
        AnimatedContent(
            targetState = step,
            transitionSpec = { slide(forward = (targetState?.ordinal ?: -1) > (initialState?.ordinal ?: -1)) },
            label = "setup"
        ) { shown ->
            Column {
                if (shown == null) return@Column Welcome()
                // A single step needs no counting.
                if (steps.size > 1) StepProgress(steps.indexOf(shown) + 1, steps.size)
                if (!state.systemStateKnown) return@Column
                if (shown == SetupStep.DESTINATION) DestinationStep(state, actions) else LinksStep(appsToFix, state, actions)
            }
        }
    }
}

/** The one music app installed, but YouTube, which comes with most phones and is mostly videos. */
private fun onlyMusicApp(state: UiState): Destination? =
    (state.installed - MusicService.YOUTUBE).singleOrNull()?.let(Destination::Service)?.takeIf { it in state.destinations }

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

@Composable
private fun StepHeader(title: Int, body: Int) = StepHeader(title, stringResource(body))

@Composable
private fun StepHeader(title: Int, body: String?) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 28.dp, bottom = if (body == null) 24.dp else 0.dp)
    )
    body?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
    }
}

@Composable
private fun Welcome() {
    Column(modifier = Modifier.padding(top = 72.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) {
                AppLogo(size = 48.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        StepHeader(R.string.setup_welcome_title, R.string.setup_welcome_body)
    }
}

/** A small rounded label, e.g. "Installed" or "Allowed". */
@Composable
private fun Tag(text: String, container: Color, content: Color) {
    // E.g. "Still opens links" turning into "Fixed" changes color smoothly.
    val shownContainer by animateColorAsState(container, label = "tag")
    val shownContent by animateColorAsState(content, label = "tag text")
    Surface(shape = MaterialTheme.shapes.small, color = shownContainer, contentColor = shownContent) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun InstalledTag(service: MusicService?, installed: Set<MusicService>) {
    if (service != null && service in installed) {
        Tag(
            stringResource(R.string.setup_installed),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** Installed apps first, keeping the usual order otherwise. */
private fun <T> List<T>.installedFirst(installed: Set<MusicService>, service: (T) -> MusicService?) =
    sortedByDescending { service(it) in installed }

@Composable
private fun ChoiceRow(
    modifier: Modifier,
    control: @Composable () -> Unit,
    label: String,
    tag: @Composable () -> Unit = {},
    icon: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        control()
        icon?.let {
            Spacer(Modifier.size(12.dp))
            it()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        )
        tag()
    }
}

/** The service the user listens in, whose links already open where they should. */
internal fun UiState.listeningService(): MusicService? =
    // Opening in Crosstune, links don't go to one app by themselves, so none is the one listened in.
    if (hasDefault && !linksInCrosstune) (defaultDestination as? Destination.Service)?.service else null

@Composable
private fun DestinationStep(state: UiState, actions: ScreenActions) {
    StepHeader(R.string.setup_destination_title, null)
    Group {
        state.destinations.installedFirst(state.installed) { (it as? Destination.Service)?.service }
            .forEachIndexed { index, destination ->
                if (index > 0) GroupDivider()
                val selected = state.hasDefault && destination == state.defaultDestination
                ChoiceRow(
                    // Picking a service also stops Crosstune opening its links (see MainViewModel.selectDefault).
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { actions.onTargetChange(destination) },
                    control = { RadioButton(selected = selected, onClick = null) },
                    label = destination.label(),
                    tag = { InstalledTag((destination as? Destination.Service)?.service, state.installed) },
                    icon = { DestinationIcon(destination, state.installed) }
                )
            }
    }
}

/** Every source's links start on, so all that's left is what Android needs changed, in one list. */
@Composable
private fun LinksStep(apps: List<LinkApp>, state: UiState, actions: ScreenActions) {
    LaunchedEffect(Unit) { actions.onPreselectSources() }
    StepHeader(R.string.setup_links_title, R.string.setup_links_body)
    val checks = state.checks(apps)
    if (state.canSetUpInOneTap && checks.any { it.done == false }) OneTap(state, actions)
    if (!state.interceptsAnything) {
        Text(
            text = stringResource(R.string.setup_allow_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )
    }
    FixList(checks) { actions.onFixSteps(listOf(it)) }
}

/** Shizuku or root does it all, for people who run either; what it can't is left in the list below. */
@Composable
private fun OneTap(state: UiState, actions: ScreenActions) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        val press = rememberPress()
        Button(
            onClick = actions.onSetUpInOneTap,
            enabled = !state.settingUpInOneTap,
            interactionSource = press.source,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(press.modifier)
        ) {
            if (state.settingUpInOneTap) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            }
            Text(stringResource(R.string.setup_one_tap), style = MaterialTheme.typography.labelLarge)
        }
        Text(
            text = stringResource(if (state.oneTapFailed) R.string.fix_failed else if (state.oneTapRoot) R.string.fix_with_root else R.string.fix_with_shizuku),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.oneTapFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 6.dp)
                .align(Alignment.CenterHorizontally)
        )
    }
}

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
 * Installed apps setup should ask the user to stop: on Android 12+ every one seen taking the
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

@Preview(showBackground = true)
@Composable
internal fun SetupScreenPreview() {
    CrosstuneTheme {
        SetupScreen(
            state = UiState(
                setupComplete = false,
                intercepted = setOf(MusicService.SPOTIFY),
                installed = setOf(MusicService.YOUTUBE_MUSIC)
            ),
            actions = ScreenActions()
        )
    }
}
