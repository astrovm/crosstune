package com.astrovm.crosstune

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme

private const val STEP_WELCOME = 0
private const val STEP_SOURCES = 1
private const val STEP_DESTINATION = 2
private const val STEP_ALLOW = 3

/**
 * First-run setup: which services' links to open, where to send them, and allowing the links in
 * Android. Choices apply as they're made, so leaving halfway keeps them; setup shows again until finished.
 */
@Composable
internal fun SetupScreen(state: UiState, actions: ScreenActions) {
    var step by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }

    Page(
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
                    .height(52.dp)
                if (step > STEP_WELCOME) {
                    OutlinedButton(onClick = { step-- }, modifier = buttonModifier) {
                        Text(stringResource(R.string.back_button), style = MaterialTheme.typography.labelLarge)
                    }
                }
                val isLast = step == STEP_ALLOW
                Button(
                    onClick = { if (isLast) actions.onCompleteSetup() else step++ },
                    // There's no built-in default any more, so one has to be picked.
                    enabled = step != STEP_DESTINATION || state.hasDefault,
                    modifier = buttonModifier
                ) {
                    Text(
                        stringResource(
                            when {
                                step == STEP_WELCOME -> R.string.setup_get_started
                                isLast -> R.string.setup_finish
                                else -> R.string.next_button
                            }
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    ) {
        AnimatedContent(targetState = step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "setup") { shown ->
            Column {
                if (shown > STEP_WELCOME) StepProgress(shown)
                when (shown) {
                    STEP_WELCOME -> Welcome()
                    STEP_SOURCES -> SourcesStep(state, actions)
                    STEP_DESTINATION -> DestinationStep(state, actions)
                    else -> AllowStep(state, actions)
                }
            }
        }
    }
}

@Composable
private fun StepProgress(step: Int) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            text = stringResource(R.string.setup_step, step, STEP_ALLOW),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LinearProgressIndicator(progress = { step / STEP_ALLOW.toFloat() }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StepHeader(title: Int, body: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 28.dp)
    )
    Text(
        text = stringResource(body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
    )
}

@Composable
private fun Welcome() {
    Column(modifier = Modifier.padding(top = 72.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_music_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        StepHeader(R.string.setup_welcome_title, R.string.setup_welcome_body)
    }
}

/** A small rounded label, e.g. "Installed" or "Allowed". */
@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
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
private fun ChoiceRow(modifier: Modifier, control: @Composable () -> Unit, label: String, tag: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        control()
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

@Composable
private fun SourcesStep(state: UiState, actions: ScreenActions) {
    StepHeader(R.string.setup_sources_title, R.string.setup_sources_body)
    Group {
        MusicService.entries.filter { it.canBeSource }.installedFirst(state.installed) { it }
            .forEachIndexed { index, source ->
                if (index > 0) GroupDivider()
                val checked = source in state.intercepted
                ChoiceRow(
                    modifier = Modifier.toggleable(value = checked, role = Role.Checkbox) { actions.onInterceptChange(source, it) },
                    control = { Checkbox(checked = checked, onCheckedChange = null) },
                    label = stringResource(source.labelRes),
                    tag = { InstalledTag(source, state.installed) }
                )
            }
    }
}

@Composable
private fun DestinationStep(state: UiState, actions: ScreenActions) {
    StepHeader(R.string.setup_destination_title, R.string.setup_destination_body)
    Group {
        state.destinations.installedFirst(state.installed) { (it as? Destination.Service)?.service }
            .forEachIndexed { index, destination ->
                if (index > 0) GroupDivider()
                val selected = state.hasDefault && destination == state.defaultDestination
                ChoiceRow(
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { actions.onTargetChange(destination) },
                    control = { RadioButton(selected = selected, onClick = null) },
                    label = destination.label(),
                    tag = { InstalledTag((destination as? Destination.Service)?.service, state.installed) }
                )
            }
    }
}

@Composable
private fun AllowStep(state: UiState, actions: ScreenActions) {
    if (state.intercepted.isEmpty()) {
        StepHeader(R.string.setup_allow_title, R.string.setup_allow_none)
        return
    }
    StepHeader(R.string.setup_allow_title, R.string.setup_allow_body)
    Group {
        MusicService.entries.filter { it in state.intercepted }.forEachIndexed { index, source ->
            if (index > 0) GroupDivider()
            // Android 12+ reports each domain's state; older versions can't, so no status is shown.
            val unapproved = state.unapprovedHosts?.let { it[source].orEmpty() }
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(source.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (unapproved != null) {
                        val allowed = unapproved.isEmpty()
                        Tag(
                            stringResource(if (allowed) R.string.setup_allowed else R.string.setup_not_allowed),
                            if (allowed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            if (allowed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                // Android lists every service's links, so name the ones still to select for this one.
                val hosts = unapproved ?: LinkInterception.HOSTS[source].orEmpty()
                if (hosts.isNotEmpty()) {
                    Text(
                        text = hosts.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
    FilledTonalButton(
        onClick = actions.onOpenLinkSettings,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .height(52.dp)
    ) {
        AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.open_link_settings_button), style = MaterialTheme.typography.labelLarge)
    }
}

@Preview(showBackground = true)
@Composable
internal fun SetupScreenPreview() {
    CrosstuneTheme(dynamicColor = false) {
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
