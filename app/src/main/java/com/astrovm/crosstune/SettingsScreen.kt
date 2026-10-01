package com.astrovm.crosstune

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme

@Composable
internal fun SettingsScreen(state: UiState, actions: ScreenActions, onBack: () -> Unit) {
    var addingCustom by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val githubUrl = stringResource(R.string.github_repo_url)
    val privacyUrl = stringResource(R.string.privacy_policy_url)

    Page(
        title = stringResource(R.string.settings_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button))
            }
        }
    ) {
        if (state.showLinkSettingsHelper) {
            LinkSettingsHelper(actions, modifier = Modifier.padding(top = 4.dp))
        }
        BlockingAppsNotice(state.blockingApps.orEmpty(), actions, modifier = Modifier.padding(top = 4.dp))

        SectionHeader(stringResource(R.string.settings_default_title))
        Group {
            DefaultDestinationMenu(
                destinations = state.destinations,
                selected = state.defaultDestination,
                onSelect = actions.onTargetChange,
                installed = state.installed,
                label = stringResource(R.string.settings_default_label),
                modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            )
        }

        SectionHeader(
            title = stringResource(R.string.settings_links_title),
            description = stringResource(R.string.settings_links_description)
        )
        Group {
            MusicService.entries.filter { it.canBeSource }.forEachIndexed { index, source ->
                if (index > 0) GroupDivider()
                SourceRow(source, state, actions)
            }
        }

        SectionHeader(
            title = stringResource(R.string.settings_custom_title),
            description = stringResource(R.string.settings_custom_description)
        )
        Group {
            state.destinations.filterIsInstance<Destination.Custom>().forEach { custom ->
                Row(
                    modifier = Modifier.padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(custom.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            custom.template,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { actions.onRemoveCustom(custom) }) {
                        AppIcon(R.drawable.ic_delete, contentDescription = stringResource(R.string.remove_button))
                    }
                }
                GroupDivider()
            }
            if (addingCustom) {
                AddCustomDestinationForm(
                    onAdd = { name, template ->
                        actions.onAddCustom(name, template).also { added -> if (added) addingCustom = false }
                    },
                    onCancel = { addingCustom = false }
                )
            } else {
                TextButton(
                    onClick = { addingCustom = true },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    AppIcon(R.drawable.ic_add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.add_custom_destination_button), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        SectionHeader(stringResource(R.string.settings_behaviour_title))
        Group {
            SettingSwitch(
                label = stringResource(R.string.setting_ask_each_time),
                description = stringResource(R.string.setting_ask_each_time_description),
                checked = state.askEachTime,
                onCheckedChange = actions.onAskEachTimeChange
            )
            GroupDivider()
            SettingSwitch(
                label = stringResource(R.string.setting_exact_match),
                description = stringResource(R.string.setting_exact_match_description),
                checked = state.exactMatch,
                onCheckedChange = actions.onExactMatchChange
            )
            GroupDivider()
            SettingSwitch(
                label = stringResource(R.string.setting_clean_links),
                description = stringResource(R.string.setting_clean_links_description),
                checked = state.cleanLinks,
                onCheckedChange = actions.onCleanLinksChange
            )
            GroupDivider()
            LanguageRow(actions.onLanguageChange)
        }

        SectionHeader(stringResource(R.string.settings_about_title))
        Group {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_version),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = appVersion(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            GroupDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri(privacyUrl) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.privacy_policy),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            GroupDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri(githubUrl) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (before, after) = stringResource(R.string.made_with_love, HEART).split(HEART).map { it.trim() }
                if (before.isNotEmpty()) Text(before, style = MaterialTheme.typography.bodyLarge)
                Image(
                    painterResource(R.drawable.ic_heart),
                    contentDescription = null,
                    modifier = Modifier.padding(horizontal = 4.dp).size(16.dp)
                )
                Text(after, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Marks where the heart icon goes in [R.string.made_with_love]. */
internal const val HEART = "\uFFFC"

/** Picks the app's language, or the phone's with "System default". */
@Composable
private fun LanguageRow(onSelect: (String?) -> Unit) {
    val context = LocalContext.current
    val current = remember { AppLanguage.current(context) }
    val systemDefault = stringResource(R.string.language_system_default)
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.setting_language),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Box {
            Row(
                modifier = Modifier.clickable { expanded = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = current?.let(AppLanguage::displayName) ?: systemDefault,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                AppIcon(R.drawable.ic_expand_more, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(16.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (listOf(null) + AppLanguage.tags).forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(tag?.let(AppLanguage::displayName) ?: systemDefault) },
                        onClick = {
                            expanded = false
                            onSelect(tag)
                        }
                    )
                }
            }
        }
    }
}

/** The installed version, e.g. "1.2.1", or "1.2.1-dev.5" for a build made after a release. */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
}

/** One source service: a switch for whether Crosstune intercepts its links, and where they go beneath. */
@Composable
private fun SourceRow(source: MusicService, state: UiState, actions: ScreenActions) {
    val intercepted = source in state.intercepted
    var expanded by remember { mutableStateOf(false) }
    val rule = state.rules[source]
    val defaultLabel = stringResource(R.string.rule_default, state.defaultDestination.label())
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = intercepted, role = Role.Switch) { actions.onInterceptChange(source, it) }
                .heightIn(min = 48.dp)
                .padding(start = 20.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(source.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = intercepted, onCheckedChange = null)
        }
        Box(modifier = Modifier.padding(start = 8.dp)) {
            TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(
                    stringResource(R.string.rule_opens_in, rule?.label() ?: defaultLabel),
                    style = MaterialTheme.typography.bodyMedium
                )
                AppIcon(
                    R.drawable.ic_expand_more,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(16.dp)
                )
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
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; invalid = false },
            label = { Text(stringResource(R.string.custom_name_label)) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
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
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        )
        Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
