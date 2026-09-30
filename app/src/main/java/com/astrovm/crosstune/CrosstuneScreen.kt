package com.astrovm.crosstune

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme

internal const val RESULT_TAG = "result"
internal const val RESULT_TEXT_TAG = "result-text"
internal const val DEFAULT_MENU_TAG = "default_destination"

internal data class ScreenActions(
    val onUrlChange: (String) -> Unit = {},
    val onResolve: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpen: () -> Unit = {},
    val onOpenWith: (Destination) -> Unit = {},
    val onOpenOriginal: () -> Unit = {},
    val onDismissPicker: () -> Unit = {},
    val onTargetChange: (Destination) -> Unit = {},
    val onInterceptChange: (MusicService, Boolean) -> Unit = { _, _ -> },
    val onRuleChange: (MusicService, Destination?) -> Unit = { _, _ -> },
    val onAddCustom: (String, String) -> Boolean = { _, _ -> false },
    val onRemoveCustom: (Destination.Custom) -> Unit = {},
    val onCompleteSetup: () -> Unit = {},
    val onAskEachTimeChange: (Boolean) -> Unit = {},
    val onExactMatchChange: (Boolean) -> Unit = {},
    val onCopySearch: () -> Unit = {},
    val onCopyLink: () -> Unit = {},
    val onShareSearch: () -> Unit = {},
    val onHistoryEntryClick: (HistoryEntry) -> Unit = {},
    val onClearHistory: () -> Unit = {},
    val onOpenLinkSettings: () -> Unit = {},
    val onOpenAppLinkSettings: (MusicService) -> Unit = {},
    val onDismissLinkSettingsHelper: () -> Unit = {},
    val loadArtwork: suspend (String) -> ImageBitmap? = { null }
)

/** Switches between the main screen and settings; system back returns from settings. */
@Composable
internal fun CrosstuneScreen(state: UiState, actions: ScreenActions) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }
    // A link from another app is about to open or show its result, so don't leave the user in settings.
    // The count seen at first composition is skipped so rotating the screen keeps settings open.
    val seenIncomingLinks = remember { mutableIntStateOf(state.incomingLinkCount) }
    LaunchedEffect(state.incomingLinkCount) {
        if (state.incomingLinkCount != seenIncomingLinks.intValue) {
            seenIncomingLinks.intValue = state.incomingLinkCount
            showSettings = false
        }
    }
    // A link from another app is handled right away; setup waits for the next regular launch.
    if (!state.setupComplete && !state.handlingIncomingLink) {
        SetupScreen(state, actions)
    } else if (showSettings) {
        SettingsScreen(state, actions, onBack = { showSettings = false })
    } else {
        MainScreen(state, actions, onOpenSettings = { showSettings = true })
    }
}

// if/else rather than an exhaustive when, which compiles an unreachable branch into composables.
@Composable
internal fun Destination.label(): String =
    if (this is Destination.Service) stringResource(service.labelRes) else (this as Destination.Custom).name

@Composable
internal fun Destination.openLabel(): String =
    if (this is Destination.Service) {
        stringResource(service.openLabelRes)
    } else {
        stringResource(R.string.open_in_custom, (this as Destination.Custom).name)
    }

/** Page scaffold shared by every screen: a flat top bar (none when [title] is null) and a centered, scrollable column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Page(
    title: String?,
    titleLeading: (@Composable () -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (title != null) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (titleLeading != null) {
                                titleLeading()
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = navigationIcon,
                    actions = { actions() },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        },
        bottomBar = bottomBar
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun MainScreen(state: UiState, actions: ScreenActions, onOpenSettings: () -> Unit) {
    if (state.showDestinationPicker) {
        DestinationPicker(state.destinations, onPick = actions.onOpenWith, onDismiss = actions.onDismissPicker)
    }

    Page(
        title = stringResource(R.string.app_name),
        titleLeading = { AppLogo() },
        actions = {
            IconButton(onClick = onOpenSettings) {
                AppIcon(R.drawable.ic_settings, contentDescription = stringResource(R.string.settings_button))
            }
        }
    ) {
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        if (state.showLinkSettingsHelper) {
            LinkSettingsHelper(actions, modifier = Modifier.padding(bottom = 16.dp))
        }
        BlockingAppsNotice(state.blockingApps.orEmpty(), actions, modifier = Modifier.padding(bottom = 16.dp))

        LinkField(state, actions)
        DefaultDestinationMenu(
            destinations = state.destinations,
            selected = state.defaultDestination,
            onSelect = actions.onTargetChange,
            modifier = Modifier.padding(top = 8.dp)
        )
        StatusSection(state, actions)
        state.result?.let { ResultCard(it, state.link, state.resultDestination, actions) }

        if (state.history.isNotEmpty()) {
            HistorySection(state.history, actions)
        }
    }
}

/** Reminder to allow links in Android's settings, until done or dismissed. */
@Composable
internal fun LinkSettingsHelper(actions: ScreenActions, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
            AppIcon(R.drawable.ic_info, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = stringResource(R.string.link_settings_helper_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.link_settings_helper_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = actions.onOpenLinkSettings) {
                        Text(stringResource(R.string.open_link_settings_button))
                    }
                    TextButton(onClick = actions.onDismissLinkSettingsHelper) {
                        Text(stringResource(R.string.dismiss_button))
                    }
                }
            }
        }
    }
}

/**
 * Lists installed music apps that still open links meant for Crosstune, each with a button to its
 * own link settings, where "Open supported links" has to be turned off. Nothing shows when there are none.
 */
@Composable
internal fun BlockingAppsNotice(apps: Set<MusicService>, actions: ScreenActions, modifier: Modifier = Modifier) {
    if (apps.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
            AppIcon(R.drawable.ic_info, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(text = stringResource(R.string.blocking_apps_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.blocking_apps_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                MusicService.entries.filter { it in apps }.forEach { app ->
                    TextButton(onClick = { actions.onOpenAppLinkSettings(app) }) {
                        Text(stringResource(R.string.open_app_link_settings_button, stringResource(app.labelRes)))
                    }
                }
            }
        }
    }
}

/** One rounded field: paste when empty, clear once filled; the keyboard's Go button looks it up. */
@Composable
private fun LinkField(state: UiState, actions: ScreenActions) {
    val busy = state.isLoading || state.isMatching
    TextField(
        value = state.linkText,
        onValueChange = actions.onUrlChange,
        singleLine = true,
        label = { Text(stringResource(R.string.spotify_link_label)) },
        placeholder = { Text(stringResource(R.string.spotify_link_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { AppIcon(R.drawable.ic_link, contentDescription = null) },
        trailingIcon = {
            if (state.linkText.isEmpty()) {
                IconButton(onClick = actions.onPaste, enabled = !busy) {
                    AppIcon(R.drawable.ic_content_paste, contentDescription = stringResource(R.string.paste_button))
                }
            } else {
                IconButton(onClick = actions.onClear, enabled = !busy) {
                    AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.clear_button))
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go
        ),
        keyboardActions = KeyboardActions(onGo = { actions.onResolve() }),
        enabled = !busy,
        shape = MaterialTheme.shapes.large,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth()
    )
    // Tonal, so the result's Open button stays the one primary action on screen.
    FilledTonalButton(
        onClick = actions.onResolve,
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(52.dp)
    ) {
        Text(stringResource(R.string.resolve_button), style = MaterialTheme.typography.labelLarge)
    }
}

/** "Opens in YouTube Music ▾": the default destination, one tap away without taking up the screen. */
@Composable
internal fun DefaultDestinationMenu(
    destinations: List<Destination>,
    selected: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    /** In settings the row is labelled like the others, with the choice at the end. */
    label: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        } else {
            Text(
                text = stringResource(R.string.default_open_with_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag(DEFAULT_MENU_TAG)) {
                Text(selected.label())
                AppIcon(
                    R.drawable.ic_expand_more,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp)
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                destinations.forEach { destination ->
                    val isSelected = destination == selected
                    DropdownMenuItem(
                        text = {
                            Text(
                                destination.label(),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(destination)
                        },
                        // The color alone doesn't tell TalkBack which one is chosen.
                        modifier = Modifier.semantics { this.selected = isSelected }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusSection(state: UiState, actions: ScreenActions) {
    AnimatedVisibility(visible = state.isLoading || state.isMatching, enter = fadeIn(), exit = fadeOut()) {
        Column(modifier = Modifier.padding(top = 20.dp)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(if (state.isLoading) R.string.loading_text else R.string.matching_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }

    val error = state.error ?: return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            // Links from other apps fail with nothing else changing on screen, so announce it.
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
            AppIcon(R.drawable.ic_error, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = stringResource(error.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
    }
}

@Composable
private fun ResultCard(
    result: MusicMetadata,
    link: MusicLink?,
    destination: Destination,
    actions: ScreenActions
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .animateContentSize()
            .testTag(RESULT_TAG),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(result.artworkUrl, actions.loadArtwork, size = 96.dp)
                // Tapping the song copies its search text, e.g. to paste into an app Crosstune can't open.
                Column(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .testTag(RESULT_TEXT_TAG)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClickLabel = stringResource(R.string.copy_search_action), onClick = actions.onCopySearch)
                        // Inset inside the clip so the rounded corner doesn't cut into the first glyph.
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = listOfNotNull(
                            stringResource(result.type.labelRes),
                            link?.let { stringResource(R.string.from_service, stringResource(it.service.labelRes)) }
                        ).joinToString(" "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = result.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (result.artist.isNotBlank()) {
                        Text(
                            text = result.artist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Button(
                onClick = actions.onOpen,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .height(52.dp)
            ) {
                AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(destination.openLabel(), style = MaterialTheme.typography.labelLarge)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Same height even when only one label wraps.
                SecondaryAction(
                    R.drawable.ic_content_copy,
                    stringResource(R.string.copy_button),
                    actions.onCopyLink,
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                SecondaryAction(
                    R.drawable.ic_share,
                    stringResource(R.string.share_search_button),
                    actions.onShareSearch,
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
            if (link != null && link.service != (destination as? Destination.Service)?.service) {
                TextButton(
                    onClick = actions.onOpenOriginal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(stringResource(link.service.openLabelRes))
                }
            }
        }
    }
}

@Composable
private fun SecondaryAction(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding, modifier = modifier) {
        AppIcon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        // Long translations wrap to a second line instead of being cut off.
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HistorySection(history: List<HistoryEntry>, actions: ScreenActions) {
    SectionHeader(
        title = stringResource(R.string.history_title),
        action = {
            TextButton(onClick = actions.onClearHistory) {
                Text(stringResource(R.string.clear_history_button))
            }
        },
        modifier = Modifier.padding(top = 8.dp)
    )
    Group {
        history.forEachIndexed { index, entry ->
            if (index > 0) GroupDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.onHistoryEntryClick(entry) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverArt(entry.metadata.artworkUrl, actions.loadArtwork, size = 48.dp)
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(
                        text = entry.metadata.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOf(
                            stringResource(entry.link.type.labelRes),
                            entry.metadata.artist,
                            stringResource(entry.link.service.labelRes)
                        )
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationPicker(destinations: List<Destination>, onPick: (Destination) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                destinations.forEach { destination ->
                    Text(
                        text = destination.openLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(destination) }
                            .padding(vertical = 14.dp, horizontal = 4.dp)
                    )
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
                result = MusicMetadata(
                    "Cut To The Feeling",
                    "Carly Rae Jepsen",
                    artworkUrl = "https://i.scdn.co/image/cut-to-the-feeling"
                ),
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
