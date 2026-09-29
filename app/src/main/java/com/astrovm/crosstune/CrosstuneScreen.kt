package com.astrovm.crosstune

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme

/** Everything the screen can ask for; the Activity wires these to the ViewModel and system services. */
internal data class ScreenActions(
    val onUrlChange: (String) -> Unit = {},
    val onResolve: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpen: () -> Unit = {},
    val onOpenWith: (MusicService) -> Unit = {},
    val onOpenOriginal: () -> Unit = {},
    val onDismissPicker: () -> Unit = {},
    val onTargetChange: (MusicService) -> Unit = {},
    val onAskEachTimeChange: (Boolean) -> Unit = {},
    val onExactMatchChange: (Boolean) -> Unit = {},
    val onCopySearch: () -> Unit = {},
    val onShareSearch: () -> Unit = {},
    val onHistoryEntryClick: (HistoryEntry) -> Unit = {},
    val onClearHistory: () -> Unit = {},
    val onOpenLinkSettings: () -> Unit = {},
    val onDismissLinkSettingsHelper: () -> Unit = {}
)

@Composable
internal fun CrosstuneScreen(state: UiState, actions: ScreenActions) {
    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        )
    )
    val uriHandler = LocalUriHandler.current
    val githubUrl = stringResource(R.string.github_repo_url)

    if (state.showDestinationPicker) {
        DestinationPicker(onPick = actions.onOpenWith, onDismiss = actions.onDismissPicker)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .safeDrawingPadding()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 20.dp)
                        .widthIn(max = 520.dp)
                )

                if (state.showLinkSettingsHelper) {
                    LinkSettingsHelper(actions)
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 680.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        InputSection(state, actions)
                        DestinationSelector(state.selectedTarget, actions.onTargetChange)
                        SettingSwitch(
                            label = stringResource(R.string.setting_ask_each_time),
                            description = stringResource(R.string.setting_ask_each_time_description),
                            checked = state.askEachTime,
                            onCheckedChange = actions.onAskEachTimeChange
                        )
                        SettingSwitch(
                            label = stringResource(R.string.setting_exact_match),
                            description = stringResource(R.string.setting_exact_match_description),
                            checked = state.exactMatch,
                            onCheckedChange = actions.onExactMatchChange
                        )
                        StatusSection(state, actions)
                        state.result?.let { ResultCard(it, state.link, state.selectedTarget, actions) }
                    }
                }

                if (state.history.isNotEmpty()) {
                    HistorySection(state.history, actions)
                }

                TextButton(
                    onClick = { uriHandler.openUri(githubUrl) },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.made_by),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkSettingsHelper(actions: ScreenActions) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 680.dp)
            .padding(bottom = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.link_settings_helper_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.link_settings_helper_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(onClick = actions.onOpenLinkSettings, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.open_link_settings_button))
                }
                TextButton(onClick = actions.onDismissLinkSettingsHelper, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dismiss_button))
                }
            }
        }
    }
}

@Composable
private fun InputSection(state: UiState, actions: ScreenActions) {
    val busy = state.isLoading || state.isMatching
    OutlinedTextField(
        value = state.linkText,
        onValueChange = actions.onUrlChange,
        singleLine = true,
        label = { Text(stringResource(R.string.spotify_link_label)) },
        placeholder = { Text(stringResource(R.string.spotify_link_placeholder)) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { actions.onResolve() }),
        enabled = !busy,
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = actions.onResolve, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.resolve_button))
        }
        OutlinedButton(onClick = actions.onClear, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.clear_button))
        }
    }
}

@Composable
private fun DestinationSelector(selectedTarget: MusicService, onTargetChange: (MusicService) -> Unit) {
    Text(
        text = stringResource(R.string.default_open_with_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp)
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MusicService.entries.forEach { target ->
            FilterChip(
                selected = target == selectedTarget,
                onClick = { onTargetChange(target) },
                label = { Text(stringResource(target.labelRes)) }
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun StatusSection(state: UiState, actions: ScreenActions) {
    if (state.isLoading || state.isMatching) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        )
        Text(
            text = stringResource(if (state.isLoading) R.string.loading_text else R.string.matching_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    val error = state.error ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(error.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
            )
            if (state.canRetry) {
                TextButton(onClick = actions.onRetry) {
                    Text(stringResource(R.string.retry_button))
                }
            }
            // A link Crosstune couldn't read can still be opened in the app it belongs to.
            state.link?.let { link ->
                TextButton(onClick = actions.onOpenOriginal) {
                    Text(stringResource(link.service.openLabelRes))
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: MusicMetadata,
    link: MusicLink?,
    selectedTarget: MusicService,
    actions: ScreenActions
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.result_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = listOfNotNull(
                    stringResource(result.type.labelRes),
                    link?.let { stringResource(R.string.from_service, stringResource(it.service.labelRes)) }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (result.artist.isNotBlank()) {
                Text(
                    text = result.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Button(
                onClick = actions.onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text(stringResource(selectedTarget.openLabelRes))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = actions.onCopySearch, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.copy_search_button))
                }
                OutlinedButton(onClick = actions.onShareSearch, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_search_button))
                }
            }
            if (link != null && link.service != selectedTarget) {
                TextButton(onClick = actions.onOpenOriginal, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(link.service.openLabelRes))
                }
            }
        }
    }
}

@Composable
private fun HistorySection(history: List<HistoryEntry>, actions: ScreenActions) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 680.dp)
            .padding(top = 12.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = actions.onClearHistory) {
                    Text(stringResource(R.string.clear_history_button))
                }
            }
            history.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.onHistoryEntryClick(entry) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(text = entry.metadata.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = listOf(
                            stringResource(entry.link.type.labelRes),
                            entry.metadata.artist,
                            stringResource(entry.link.service.labelRes)
                        )
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationPicker(onPick: (MusicService) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_title)) },
        text = {
            Column {
                MusicService.entries.forEach { target ->
                    TextButton(onClick = { onPick(target) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(target.openLabelRes))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        }
    )
}

@Preview(showBackground = true)
@Composable
internal fun CrosstuneScreenPreview() {
    CrosstuneTheme(dynamicColor = false) {
        CrosstuneScreen(
            state = UiState(
                linkText = "https://open.spotify.com/track/11dFghVXANMlKmJXsNCbNl",
                result = MusicMetadata("Cut To The Feeling", "Carly Rae Jepsen"),
                link = MusicLink(
                    MusicService.SPOTIFY,
                    ItemType.TRACK,
                    "11dFghVXANMlKmJXsNCbNl",
                    "https://open.spotify.com/track/11dFghVXANMlKmJXsNCbNl"
                ),
                history = listOf(
                    HistoryEntry(
                        MusicLink(MusicService.DEEZER, ItemType.ALBUM, "137272602", "https://www.deezer.com/album/137272602"),
                        MusicMetadata("After Hours", "The Weeknd", ItemType.ALBUM)
                    )
                )
            ),
            actions = ScreenActions()
        )
    }
}
