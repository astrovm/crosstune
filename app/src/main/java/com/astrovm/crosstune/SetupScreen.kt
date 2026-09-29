package com.astrovm.crosstune

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                when (step) {
                    STEP_WELCOME -> Welcome()
                    STEP_SOURCES -> SourcesStep(state, actions)
                    STEP_DESTINATION -> DestinationStep(state, actions)
                    else -> AllowStep(state, actions)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (step > STEP_WELCOME) {
                        OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.back_button))
                        }
                    }
                    val isLast = step == STEP_ALLOW
                    Button(
                        onClick = { if (isLast) actions.onCompleteSetup() else step++ },
                        // There's no built-in default any more, so one has to be picked.
                        enabled = step != STEP_DESTINATION || state.hasDefault,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(
                                when {
                                    step == STEP_WELCOME -> R.string.setup_get_started
                                    isLast -> R.string.setup_finish
                                    else -> R.string.next_button
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHeader(title: Int, body: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = stringResource(body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
    )
}

@Composable
private fun Welcome() {
    StepHeader(R.string.setup_welcome_title, R.string.setup_welcome_body)
}

@Composable
private fun InstalledTag(service: MusicService?, installed: Set<MusicService>) {
    if (service != null && service in installed) {
        Text(
            text = stringResource(R.string.setup_installed),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** Installed apps first, keeping the usual order otherwise. */
private fun <T> List<T>.installedFirst(installed: Set<MusicService>, service: (T) -> MusicService?) =
    sortedByDescending { service(it) in installed }

@Composable
private fun SourcesStep(state: UiState, actions: ScreenActions) {
    StepHeader(R.string.setup_sources_title, R.string.setup_sources_body)
    Card(modifier = Modifier.fillMaxWidth()) {
        MusicService.entries.filter { it.canBeSource }.installedFirst(state.installed) { it }
            .forEachIndexed { index, source ->
                if (index > 0) HorizontalDivider()
                val checked = source in state.intercepted
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = checked, role = Role.Checkbox) { actions.onInterceptChange(source, it) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(
                        text = stringResource(source.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    )
                    InstalledTag(source, state.installed)
                }
            }
    }
}

@Composable
private fun DestinationStep(state: UiState, actions: ScreenActions) {
    StepHeader(R.string.setup_destination_title, R.string.setup_destination_body)
    Card(modifier = Modifier.fillMaxWidth()) {
        state.destinations.installedFirst(state.installed) { (it as? Destination.Service)?.service }
            .forEachIndexed { index, destination ->
                if (index > 0) HorizontalDivider()
                val selected = state.hasDefault && destination == state.defaultDestination
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton) { actions.onTargetChange(destination) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(
                        text = destination.label(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    )
                    InstalledTag((destination as? Destination.Service)?.service, state.installed)
                }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        MusicService.entries.filter { it in state.intercepted }.forEachIndexed { index, source ->
            if (index > 0) HorizontalDivider()
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(source.labelRes),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                // Android 12+ reports each domain's state; older versions can't, so no status is shown.
                state.approved?.let { approved ->
                    val allowed = source in approved
                    Text(
                        text = stringResource(if (allowed) R.string.setup_allowed else R.string.setup_not_allowed),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
    TextButton(onClick = actions.onOpenLinkSettings, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.open_link_settings_button))
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
