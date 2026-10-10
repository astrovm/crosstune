package com.astrovm.crosstune

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.astrovm.crosstune.ui.theme.Palette
import com.astrovm.crosstune.ui.theme.wallpaperColors
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme

@Composable
internal fun SettingsScreen(
    state: UiState,
    actions: ScreenActions,
    onBack: () -> Unit,
    // Kept by the caller, so coming back from a guide returns to the same source's page.
    openSource: String? = null,
    onOpenSource: (String?) -> Unit = {}
) {
    val sources = sourceSettings(state, actions)
    // A source's page slides in over the list, and back out the other way.
    AnimatedContent(
        targetState = sources.firstOrNull { it.key == openSource }?.key,
        transitionSpec = { slide(forward = targetState != null) },
        label = "source"
    ) { key ->
        val item = sources.firstOrNull { it.key == key }
        if (item != null) {
            SourcePage(item, state.destinations, state.installed, state.claimingAppsBySource.orEmpty(), state.onlyMusicVideos, actions, onBack = { onOpenSource(null) })
        } else {
            SettingsList(state, actions, sources, onBack, onOpenSource)
        }
    }
}

@Composable
private fun SettingsList(
    state: UiState,
    actions: ScreenActions,
    sources: List<SourceSettings>,
    onBack: () -> Unit,
    onOpenSource: (String?) -> Unit
) {
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
        Column(modifier = Modifier.padding(top = 4.dp)) { LinkNotices(state, actions, includeNotAllowed = false) }

        // "Open links in" says what this is, also when it's set to ask or show first.
        Spacer(Modifier.height(16.dp))
        Group {
            DefaultDestinationMenu(
                destinations = state.destinations,
                selected = state.defaultDestination,
                mode = state.linkMode,
                onSelect = actions.onTargetChange,
                onMode = actions.onLinkModeChange,
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
            sources.forEachIndexed { index, item ->
                if (index > 0) GroupDivider()
                SourceSummaryRow(item, state.installed, onOpen = { onOpenSource(item.key) })
            }
        }


        SectionHeader(
            title = stringResource(R.string.settings_custom_title)
        )
        Group {
            state.destinations.filterIsInstance<Destination.Custom>().forEach { custom ->
                Row(
                    modifier = Modifier.padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Like every other app and site, it's shown with its icon: its first letter.
                    DestinationIcon(custom, state.installed)
                    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
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
            AnimatedContent(targetState = addingCustom, transitionSpec = { swap() }, label = "add") { adding ->
                if (adding) {
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
        }

        SectionHeader(stringResource(R.string.settings_behaviour_title))
        Group {
            SettingSwitch(
                label = stringResource(R.string.setting_exact_match),
                description = stringResource(R.string.setting_exact_match_description),
                checked = state.exactMatch,
                onCheckedChange = actions.onExactMatchChange
            )
            // Only a song looked for can be not found; without exact matching, everything is searched.
            AnimatedVisibility(visible = state.exactMatch, enter = Motion.appear, exit = Motion.disappear) {
                Column {
                    GroupDivider()
                    NotFoundRow(state.notFoundAction, actions.onNotFoundActionChange)
                }
            }
            GroupDivider()
            SettingSwitch(
                label = stringResource(R.string.setting_clean_links),
                description = stringResource(R.string.setting_clean_links_description),
                checked = state.cleanLinks,
                onCheckedChange = actions.onCleanLinksChange
            )
            GroupDivider()
            SettingSwitch(
                label = stringResource(R.string.setting_share_sheet_apps),
                description = stringResource(R.string.setting_share_sheet_apps_description),
                checked = state.shareSheetApps,
                onCheckedChange = actions.onShareSheetAppsChange
            )
        }

        // What happens around a song once it's found: naming one playing nearby, and its words.
        SectionHeader(stringResource(R.string.settings_listening_title))
        Group {
            // Which app names a song playing nearby, when there's more than one.
            if (actions.recognizers.size > 1) {
                RecognizerRow(actions)
                GroupDivider()
            }
            // On, or off, in Android's settings: switching it opens them, with the steps where there are some.
            TranslateIntoRow(state.learning.into, actions.onTranslateIntoChange)
            GroupDivider()
            TranslationServerRow(state.learning.server, actions.onTranslationServerChange)
            GroupDivider()
            SettingSwitch(
                label = stringResource(R.string.setting_lyrics_follow),
                description = stringResource(R.string.setting_lyrics_follow_description),
                checked = state.canFollowApps,
                onCheckedChange = { on -> if (on) actions.onAllowFollowing() else actions.onOpenFollowAccess() }
            )
        }

        SectionHeader(stringResource(R.string.settings_appearance_title))
        Group {
            ThemeRow(state.theme, actions.onThemeChange)
            GroupDivider()
            PaletteRow(state.palette, actions.onPaletteChange)
            GroupDivider()
            SettingSwitch(
                label = stringResource(R.string.setting_pure_black),
                description = stringResource(R.string.setting_pure_black_description),
                checked = state.pureBlack,
                onCheckedChange = actions.onPureBlackChange
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

@Composable
private fun LinkOwnerRow(app: LinkApp, opensThem: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(if (opensThem) R.string.link_owner_opens_them else R.string.link_owner_lets_crosstune),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/** "Opens videos on yewtu.be": the site a web frontend opens on; tapping it edits the site in place. */
@Composable
private fun FrontendSiteRow(web: Destination.Alternative, onChange: (String) -> Boolean) {
    var editing by rememberSaveable { mutableStateOf(false) }
    if (!editing) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = true }
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.frontend_site_on, web.instance.orEmpty().removePrefix("https://")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            AppIcon(R.drawable.ic_edit, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        return
    }
    var address by rememberSaveable { mutableStateOf(web.instance.orEmpty()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; invalid = false },
            label = { Text(stringResource(R.string.frontend_address_title, web.frontend.label)) },
            supportingText = { if (invalid) Text(stringResource(R.string.frontend_address_invalid)) },
            isError = invalid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel_button)) }
            TextButton(onClick = { if (onChange(address)) editing = false else invalid = true }) {
                Text(stringResource(R.string.save_button))
            }
        }
    }
}

/**
 * Where lyrics are translated: MyMemory, or a LibreTranslate server, with its key when it wants one.
 * Tapping it edits them in place.
 */
@Composable
private fun TranslationServerRow(server: TranslationServer?, onChange: (String, String) -> Boolean) {
    var editing by rememberSaveable { mutableStateOf(false) }
    if (!editing) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = true }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.setting_translation_server), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                server?.url?.substringAfter("://") ?: MYMEMORY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Against the right edge, like the picked choice in the rows around it.
                modifier = Modifier.padding(start = 16.dp).widthIn(max = 200.dp)
            )
            AppIcon(R.drawable.ic_edit, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
        return
    }
    var address by rememberSaveable { mutableStateOf(server?.url.orEmpty()) }
    var key by rememberSaveable { mutableStateOf(server?.key.orEmpty()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; invalid = false },
            label = { Text(stringResource(R.string.translation_server_address)) },
            supportingText = { Text(stringResource(if (invalid) R.string.translation_server_invalid else R.string.translation_server_description)) },
            isError = invalid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(R.string.translation_server_key)) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel_button)) }
            TextButton(onClick = { if (onChange(address, key)) editing = false else invalid = true }) {
                Text(stringResource(R.string.save_button))
            }
        }
    }
}

/** The translation service used when no server is set; a name, so never translated. */
private const val MYMEMORY = "MyMemory"

/** Marks where the heart icon goes in [R.string.made_with_love]. */
internal const val HEART = "\uFFFC"

/** Picks the app the Recognize button opens. */
@Composable
private fun RecognizerRow(actions: ScreenActions) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.setting_recognizer),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                actions.recognizer?.let { PackageIcon(it.packageName, size = 20.dp) }
                Text(
                    text = actions.recognizer?.label.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
                AppIcon(R.drawable.ic_expand_more, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(16.dp).flipWhen(expanded))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                actions.recognizers.forEach { recognizer ->
                    DropdownMenuItem(
                        text = { Text(recognizer.label) },
                        leadingIcon = { PackageIcon(recognizer.packageName, size = 24.dp) },
                        onClick = {
                            expanded = false
                            actions.onRecognizerChange(recognizer)
                        }
                    )
                }
            }
        }
    }
}

/** What happens when the app a song goes to doesn't have it: ask, open it where it's from, or search anyway. */
@Composable
private fun NotFoundRow(action: NotFoundAction, onSelect: (NotFoundAction) -> Unit) = ChoiceRow(
    stringResource(R.string.setting_not_found),
    listOf(
        NotFoundAction.ASK to stringResource(R.string.not_found_ask),
        NotFoundAction.ORIGINAL to stringResource(R.string.not_found_original),
        NotFoundAction.SEARCH to stringResource(R.string.not_found_search)
    ),
    action,
    onSelect
)

/** Picks light or dark, or the phone's with "System default". */
@Composable
private fun ThemeRow(theme: ThemeMode, onSelect: (ThemeMode) -> Unit) = ChoiceRow(
    stringResource(R.string.setting_theme),
    listOf(
        ThemeMode.SYSTEM to stringResource(R.string.language_system_default),
        ThemeMode.LIGHT to stringResource(R.string.theme_light),
        ThemeMode.DARK to stringResource(R.string.theme_dark)
    ),
    theme,
    onSelect
)

/** A setting picked from a short list, with the one picked shown beside its name. */
@Composable
private fun <T> ChoiceRow(label: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = choices.first { it.first == selected }.second,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                AppIcon(R.drawable.ic_expand_more, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(16.dp).flipWhen(expanded))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (choice, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            onSelect(choice)
                        }
                    )
                }
            }
        }
    }
}

/**
 * The app's color, picked from swatches in the look it has now, dark or light. The wallpaper's is
 * offered only where Android shares it.
 */
@Composable
private fun PaletteRow(selected: Palette, onSelect: (Palette) -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        // The picked one by name, as the other rows say theirs, since a swatch alone doesn't say it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.setting_color), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(text = stringResource(selected.labelRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(top = 12.dp)
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
        ) {
            Palette.entries.filter { it != Palette.WALLPAPER || wallpaperColors }.forEach { palette ->
                val wallpaper = if (palette == Palette.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
                } else {
                    null
                }
                val accent = if (dark) palette.dark else palette.light
                Swatch(wallpaper ?: accent.primary, accent.onPrimary, stringResource(palette.labelRes), palette == selected) { onSelect(palette) }
            }
        }
    }
}

/** One color to pick: a round of it, ringed and ticked when it's the one picked. */
@Composable
private fun Swatch(color: Color, onColor: Color, name: String, isSelected: Boolean, onClick: () -> Unit) {
    // The ring fades rather than thins: a border of no width is still drawn, as a hairline.
    val ring by animateFloatAsState(if (isSelected) 1f else 0f, label = "swatch ring")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .border(3.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = ring), CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(color)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name }
    ) {
        AnimatedVisibility(visible = isSelected, enter = Motion.pop, exit = fadeOut(Motion.fadeOut)) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = onColor, modifier = Modifier.size(20.dp))
        }
    }
}

private val Palette.labelRes: Int
    get() = when (this) {
        Palette.VIOLET -> R.string.palette_violet
        Palette.WALLPAPER -> R.string.palette_wallpaper
        Palette.OCEAN -> R.string.palette_ocean
        Palette.FOREST -> R.string.palette_forest
        Palette.SUNSET -> R.string.palette_sunset
        Palette.ROSE -> R.string.palette_rose
    }

/** Picks the language lyrics are translated into, or the app's own. */
@Composable
private fun TranslateIntoRow(into: String?, onSelect: (String?) -> Unit) {
    val appLanguage = stringResource(R.string.translate_into_app_language)
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_translate_into), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = into?.let(AppLanguage::displayName) ?: appLanguage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                AppIcon(R.drawable.ic_expand_more, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(16.dp).flipWhen(expanded))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (listOf(null) + AppLanguage.tags).forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(tag?.let(AppLanguage::displayName) ?: appLanguage) },
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
            .clickable { expanded = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.setting_language),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = current?.let(AppLanguage::displayName) ?: systemDefault,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                AppIcon(R.drawable.ic_expand_more, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(16.dp).flipWhen(expanded))
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

/**
 * One source service: a switch for whether Crosstune opens its links, whether Android lets it yet,
 * and, once on or given its own app, where they go. The app the user listens in can't be turned on
 * while its links would just go back to it.
 */
/** Everything Settings offers for one source of links: a service, or a web frontend's sites. */
private class SourceSettings(
    val key: String,
    /** Whose icon it shows. */
    val icon: Destination,
    val label: String,
    val on: Boolean,
    /** The app the user listens in, which can't be turned on while its links would just go back to it. */
    val locked: Boolean,
    val listeningNote: String?,
    val notAllowed: Boolean,
    val rule: Destination?,
    /** Where its links go without a rule of their own. */
    val fallback: Destination,
    val onToggle: (Boolean) -> Unit,
    val onRule: (Destination?) -> Unit,
    /** YouTube and its frontends, which are mostly videos that aren't music. */
    val video: Boolean = false,
    /** For web frontends, the site they open videos on when picked as where to open things. */
    val site: Destination.Alternative? = null,
    val onSite: (String) -> Boolean = { false }
)

@Composable
private fun sourceSettings(state: UiState, actions: ScreenActions): List<SourceSettings> {
    val services = MusicService.entries.filter { it.canBeSource }.map { source ->
        val intercepted = source in state.intercepted
        val rule = state.rules[source]
        val listeningHere = source == state.listeningService() && rule == null
        val label = stringResource(source.labelRes)
        SourceSettings(
            key = source.name,
            icon = Destination.Service(source),
            label = label,
            on = intercepted,
            // Already on from before the rule existed: it can still be turned off.
            locked = listeningHere && !intercepted,
            // Off and locked, the note also says how to turn it on.
            listeningNote = if (listeningHere) {
                stringResource(if (intercepted) R.string.setup_sources_listening_note else R.string.settings_listening_locked, label)
            } else {
                null
            },
            notAllowed = intercepted && state.unapprovedHosts?.get(source).orEmpty().isNotEmpty(),
            rule = rule,
            fallback = state.defaultDestination,
            onToggle = { actions.onInterceptChange(source, it) },
            onRule = { actions.onRuleChange(source, it) },
            video = source == MusicService.YOUTUBE
        )
    }
    val frontends = Frontend.SOURCES.map { frontend ->
        val on = frontend in state.frontendSources
        SourceSettings(
            key = Destination.FRONTEND_PREFIX + frontend.name,
            icon = Destination.Alternative(frontend),
            label = frontend.label,
            on = on,
            locked = false,
            listeningNote = null,
            notAllowed = on && state.unapprovedFrontendHosts?.get(frontend).orEmpty().isNotEmpty(),
            rule = state.frontendRules[frontend],
            // Without a rule of its own, its videos go where YouTube's do.
            fallback = state.rules[MusicService.YOUTUBE] ?: state.defaultDestination,
            onToggle = { actions.onFrontendInterceptChange(frontend, it) },
            onRule = { actions.onFrontendRuleChange(frontend, it) },
            video = true,
            site = state.destinations.filterIsInstance<Destination.Alternative>().firstOrNull { it.frontend == frontend },
            onSite = { actions.onFrontendInstanceChange(frontend, it) }
        )
    }
    return services + frontends
}

/**
 * One source in the list: its name, what needs attention or where its links go, and its switch.
 * Tapping the name opens the rest of its options.
 */
@Composable
private fun SourceSummaryRow(item: SourceSettings, installed: Set<MusicService>, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DestinationIcon(item.icon, installed)
            Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(item.label, style = MaterialTheme.typography.bodyLarge)
            if (item.notAllowed) {
                Text(
                    stringResource(R.string.setup_not_allowed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (item.locked) {
                // The switch is off and can't be turned on; the reason is on the source's page.
                Text(
                    stringResource(R.string.settings_where_you_listen),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (item.on && item.rule != null) {
                // Only when it differs from the default, so the list stays quiet.
                Text(
                    stringResource(R.string.rule_opens_in, item.rule.label()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            }
        }
        Switch(
            checked = item.on,
            onCheckedChange = item.onToggle,
            enabled = !item.locked,
            modifier = Modifier.semantics { contentDescription = item.label }
        )
    }
}

/**
 * One source's own page: whether Crosstune opens its links, whether Android lets it, where they go,
 * for web frontends their site, and the apps that can open the same links.
 */
@Composable
private fun SourcePage(
    item: SourceSettings,
    destinations: List<Destination>,
    installed: Set<MusicService>,
    claimingApps: Map<String, Map<LinkApp, Boolean>>,
    onlyMusicVideos: Boolean,
    actions: ScreenActions,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Page(
        title = item.label,
        navigationIcon = {
            IconButton(onClick = onBack) {
                AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button))
            }
        }
    ) {
        Group(modifier = Modifier.padding(top = 8.dp)) {
            SettingSwitch(
                label = stringResource(R.string.source_open_links),
                description = item.listeningNote,
                checked = item.on,
                onCheckedChange = item.onToggle,
                enabled = !item.locked
            )
            if (item.notAllowed) NotAllowedRow(actions)
            if (item.video) {
                GroupDivider()
                // Shared by YouTube, Invidious and Piped.
                SettingSwitch(
                    label = stringResource(R.string.setting_only_music_videos),
                    description = stringResource(R.string.setting_only_music_videos_description),
                    checked = onlyMusicVideos,
                    onCheckedChange = actions.onOnlyMusicVideosChange
                )
            }
            GroupDivider()
            RuleMenu(item.rule, item.fallback, destinations, installed, item.onRule)
            item.site?.let { site ->
                GroupDivider()
                FrontendSiteRow(site, item.onSite)
            }
        }
        // Kept here even once they let Crosstune open the links, so the choice can be undone.
        // Locked, the app the user listens in keeping its links is just what should happen.
        val owners = if (item.locked) emptyMap() else claimingApps[item.key].orEmpty()
        if (owners.isNotEmpty()) {
            SectionHeader(
                title = stringResource(R.string.settings_link_owners_title),
                description = stringResource(R.string.settings_link_owners_description)
            )
            Group {
                owners.entries.forEachIndexed { index, (app, opensThem) ->
                    if (index > 0) GroupDivider()
                    LinkOwnerRow(app, opensThem = opensThem, onClick = { actions.onOpenAppLinkSettings(app) })
                }
            }
        }
    }
}

@Composable
private fun NotAllowedRow(actions: ScreenActions) {
    Row(modifier = Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.setup_not_allowed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = actions.onShowAllowGuide) { Text(stringResource(R.string.allow_button)) }
    }
}

@Composable
private fun RuleMenu(rule: Destination?, fallback: Destination, destinations: List<Destination>, installed: Set<MusicService>, onChange: (Destination?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.rule_default, fallback.label())
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
                    .flipWhen(expanded)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(defaultLabel) },
                onClick = {
                    expanded = false
                    onChange(null)
                }
            )
            destinations.forEach { destination ->
                DropdownMenuItem(
                    text = { Text(destination.label()) },
                    leadingIcon = { DestinationIcon(destination, installed) },
                    onClick = {
                        expanded = false
                        onChange(destination)
                    }
                )
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
    CrosstuneTheme {
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
