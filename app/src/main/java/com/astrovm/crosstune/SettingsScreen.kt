package com.astrovm.crosstune

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme

@Composable
internal fun SettingsScreen(state: UiState, actions: ScreenActions, onBack: () -> Unit) {
    var addingCustom by rememberSaveable { mutableStateOf(false) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onBack) { Text(stringResource(R.string.done_button)) }
                }

                if (state.showLinkSettingsHelper) {
                    Box(modifier = Modifier.padding(top = 12.dp)) { LinkSettingsHelper(actions) }
                }

                SectionTitle(R.string.settings_links_title, R.string.settings_links_description)
                Card(modifier = Modifier.fillMaxWidth()) {
                    MusicService.entries.filter { it.canBeSource }.forEachIndexed { index, source ->
                        if (index > 0) HorizontalDivider()
                        SourceRow(source, state, actions)
                    }
                }

                SectionTitle(R.string.settings_custom_title, R.string.settings_custom_description)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        state.destinations.filterIsInstance<Destination.Custom>().forEach { custom ->
                            Row(
                                modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(custom.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        custom.template,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { actions.onRemoveCustom(custom) }) {
                                    Text(stringResource(R.string.remove_button))
                                }
                            }
                            HorizontalDivider()
                        }
                        if (addingCustom) {
                            AddCustomDestinationForm(
                                onAdd = { name, template ->
                                    actions.onAddCustom(name, template).also { added -> if (added) addingCustom = false }
                                },
                                onCancel = { addingCustom = false }
                            )
                        } else {
                            TextButton(onClick = { addingCustom = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text(stringResource(R.string.add_custom_destination_button))
                            }
                        }
                    }
                }

                SectionTitle(R.string.settings_behaviour_title, null)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
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
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: Int, description: Int?) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
    )
    if (description != null) {
        Text(
            text = stringResource(description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

/** One source service: whether Crosstune intercepts its links, and where they go. */
@Composable
private fun SourceRow(source: MusicService, state: UiState, actions: ScreenActions) {
    val intercepted = source in state.intercepted
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        SettingSwitch(
            label = stringResource(source.labelRes),
            description = stringResource(
                if (intercepted) R.string.source_intercepted else R.string.source_not_intercepted
            ),
            checked = intercepted,
            onCheckedChange = { actions.onInterceptChange(source, it) }
        )
        var expanded by remember { mutableStateOf(false) }
        val rule = state.rules[source]
        val defaultLabel = stringResource(R.string.rule_default, state.defaultDestination.label())
        Box(modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.rule_opens_in, rule?.label() ?: defaultLabel))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(defaultLabel) },
                    onClick = {
                        expanded = false
                        actions.onRuleChange(source, null)
                    }
                )
                state.destinations.forEach { destination ->
                    DropdownMenuItem(
                        text = { Text(destination.label()) },
                        onClick = {
                            expanded = false
                            actions.onRuleChange(source, destination)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddCustomDestinationForm(onAdd: (String, String) -> Boolean, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf("https://") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; invalid = false },
            label = { Text(stringResource(R.string.custom_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = template,
            onValueChange = { template = it; invalid = false },
            label = { Text(stringResource(R.string.custom_template_label)) },
            supportingText = {
                Text(stringResource(if (invalid) R.string.custom_template_invalid else R.string.custom_template_hint))
            },
            isError = invalid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        Row(modifier = Modifier.align(Alignment.End)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel_button)) }
            TextButton(onClick = { invalid = !onAdd(name, template) }) { Text(stringResource(R.string.add_button)) }
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun SettingsScreenPreview() {
    CrosstuneTheme(dynamicColor = false) {
        SettingsScreen(
            state = UiState(
                intercepted = setOf(MusicService.SPOTIFY, MusicService.YOUTUBE),
                rules = mapOf(MusicService.YOUTUBE to Destination.Service(MusicService.YOUTUBE_MUSIC)),
                destinations = MusicService.entries.map(Destination::Service) +
                    Destination.Custom("1", "Invidious", "https://yewtu.be/search?q={query}")
            ),
            actions = ScreenActions(),
            onBack = {}
        )
    }
}
