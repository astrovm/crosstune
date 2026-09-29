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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme

@Composable
internal fun CrosstuneScreen(
    state: UiState,
    onUrlChange: (String) -> Unit,
    onResolveClick: () -> Unit,
    onClearClick: () -> Unit,
    onOpenClick: () -> Unit,
    onTargetChange: (SearchTarget) -> Unit,
    onCopySearchClick: () -> Unit,
    onShareSearchClick: () -> Unit,
    onOpenLinkSettingsClick: () -> Unit,
    onDismissLinkSettingsHelper: () -> Unit
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        )
    )
    val uriHandler = LocalUriHandler.current
    val githubUrl = stringResource(R.string.github_repo_url)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
    ) { innerPadding ->
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
                                Button(
                                    onClick = onOpenLinkSettingsClick,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.open_link_settings_button))
                                }
                                TextButton(
                                    onClick = onDismissLinkSettingsHelper,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.dismiss_button))
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 680.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        OutlinedTextField(
                            value = state.spotifyUrl,
                            onValueChange = onUrlChange,
                            singleLine = true,
                            label = { Text(stringResource(R.string.spotify_link_label)) },
                            placeholder = { Text(stringResource(R.string.spotify_link_placeholder)) },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { onResolveClick() }),
                            enabled = !state.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onResolveClick,
                                enabled = !state.isLoading,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.resolve_button))
                            }

                            OutlinedButton(
                                onClick = onClearClick,
                                enabled = !state.isLoading,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.clear_button))
                            }
                        }

                        SearchTargetSelector(
                            selectedTarget = state.selectedTarget,
                            label = stringResource(R.string.default_open_with_label),
                            onTargetChange = onTargetChange,
                            modifier = Modifier.padding(top = 16.dp)
                        )

                        if (state.isLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp)
                            )
                            Text(
                                text = stringResource(R.string.loading_text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        val error = state.error
                        if (error != null) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Text(
                                    text = stringResource(error.messageRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        val result = state.result
                        if (result != null) {
                            val openButtonLabel = stringResource(state.selectedTarget.openButtonLabelRes)

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
                                        text = result.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(top = 8.dp)
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
                                        onClick = onOpenClick,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 12.dp)
                                    ) {
                                        Text(openButtonLabel)
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = onCopySearchClick,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(stringResource(R.string.copy_search_button))
                                        }
                                        OutlinedButton(
                                            onClick = onShareSearchClick,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(stringResource(R.string.share_search_button))
                                        }
                                    }
                                }
                            }
                        }
                    }
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
private fun SearchTargetSelector(
    selectedTarget: SearchTarget,
    label: String,
    onTargetChange: (SearchTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            SearchTarget.entries.forEachIndexed { index, target ->
                SegmentedButton(
                    selected = target == selectedTarget,
                    onClick = { onTargetChange(target) },
                    shape = SegmentedButtonDefaults.itemShape(index, SearchTarget.entries.size)
                ) {
                    Text(stringResource(target.labelRes))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun CrosstuneScreenPreview() {
    CrosstuneTheme {
        CrosstuneScreen(
            state = UiState(
                spotifyUrl = "https://open.spotify.com/track/11dFghVXANMlKmJXsNCbNl",
                result = TrackMetadata("Cut To The Feeling", "Carly Rae Jepsen")
            ),
            onUrlChange = {},
            onResolveClick = {},
            onClearClick = {},
            onOpenClick = {},
            onTargetChange = {},
            onCopySearchClick = {},
            onShareSearchClick = {},
            onOpenLinkSettingsClick = {},
            onDismissLinkSettingsHelper = {}
        )
    }
}
