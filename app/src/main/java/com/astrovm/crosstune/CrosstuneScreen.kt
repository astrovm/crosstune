package com.astrovm.crosstune

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.res.painterResource
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
    val onResultTargetChange: (Destination) -> Unit = {},
    val onMakeDefault: () -> Unit = {},
    val onInterceptChange: (MusicService, Boolean) -> Unit = { _, _ -> },
    val onFrontendInterceptChange: (Frontend, Boolean) -> Unit = { _, _ -> },
    val onFrontendRuleChange: (Frontend, Destination?) -> Unit = { _, _ -> },
    val onRuleChange: (MusicService, Destination?) -> Unit = { _, _ -> },
    val onAddCustom: (String, String) -> Boolean = { _, _ -> false },
    val onRemoveCustom: (Destination.Custom) -> Unit = {},
    val onFrontendInstanceChange: (Frontend, String) -> Boolean = { _, _ -> false },
    val onCompleteSetup: () -> Unit = {},
    val onPreselectSources: () -> Unit = {},
    val onAskEachTimeChange: (Boolean) -> Unit = {},
    val onExactMatchChange: (Boolean) -> Unit = {},
    val onCleanLinksChange: (Boolean) -> Unit = {},
    val onOnlyMusicVideosChange: (Boolean) -> Unit = {},
    val onLanguageChange: (String?) -> Unit = {},
    val onCopySearch: () -> Unit = {},
    val onCopyLink: () -> Unit = {},
    val onShareSearch: () -> Unit = {},
    val onHistoryEntryClick: (HistoryEntry) -> Unit = {},
    val onHistoryOpen: (HistoryEntry) -> Unit = {},
    val onHistoryCopy: (HistoryEntry) -> Unit = {},
    val onClearHistory: () -> Unit = {},
    val onUndoClearHistory: () -> Unit = {},
    val onForgetClearedHistory: () -> Unit = {},
    val onCancelHandoff: () -> Unit = {},
    val onOpenLinkSettings: () -> Unit = {},
    val onOpenAppLinkSettings: (LinkApp) -> Unit = {},
    /** Shows how to allow the links, step by step; the screen hosts it, so it's set there. */
    val onShowAllowGuide: () -> Unit = {},
    /** Shows how to stop the apps that keep the links, one at a time. */
    val onShowAppsGuide: () -> Unit = {},
    val onDismissLinkSettingsHelper: () -> Unit = {},
    val onSettingsLeft: () -> Unit = {},
    val loadArtwork: suspend (String) -> ImageBitmap? = { null }
)

/** Switches between the main screen and settings; system back returns from settings. */
@Composable
internal fun CrosstuneScreen(state: UiState, actions: ScreenActions) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }
    // A link from another app is about to open or show its result, so don't leave the user in settings.
    // The request is consumed once handled, so a rotation keeps settings open, while a restored process
    // that re-resolves a pending link still asks for it.
    LaunchedEffect(state.leaveSettings) {
        if (state.leaveSettings) {
            showSettings = false
            actions.onSettingsLeft()
        }
    }
    var guide by rememberSaveable { mutableStateOf<String?>(null) }
    var openSource by rememberSaveable { mutableStateOf<String?>(null) }
    val guided = actions.copy(onShowAllowGuide = { guide = GUIDE_ALLOW }, onShowAppsGuide = { guide = GUIDE_APPS })
    // A link from another app is handled right away; setup waits for the next regular launch.
    if (!state.setupComplete && !state.handlingIncomingLink) {
        SetupScreen(state, actions)
    } else if (guide != null && !state.handingOff) {
        GuidePage(guide == GUIDE_ALLOW, state, actions, onDone = { guide = null })
    } else if (showSettings) {
        SettingsScreen(state, guided, onBack = { showSettings = false }, openSource = openSource, onOpenSource = { openSource = it })
    } else {
        MainScreen(state, guided, onOpenSettings = { showSettings = true })
    }
}

private const val GUIDE_ALLOW = "allow"
private const val GUIDE_APPS = "apps"

/** The setup guides on their own, opened from a notice or settings, with a way back once done. */
@Composable
private fun GuidePage(allow: Boolean, state: UiState, actions: ScreenActions, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    Page(
        title = stringResource(if (allow) R.string.setup_allow_title else R.string.setup_apps_title),
        navigationIcon = {
            IconButton(onClick = onDone) {
                AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button))
            }
        }
    ) {
        if (allow) {
            AllowLinksGuide(state, actions)
        } else {
            Text(
                text = stringResource(R.string.setup_apps_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
            StopAppsGuide(appsToStop(state), state.blockingApps, actions.onOpenAppLinkSettings)
        }
        FilledTonalButton(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .heightIn(min = 52.dp)
        ) {
            Text(stringResource(R.string.setup_done), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// if/else rather than an exhaustive when, which compiles an unreachable branch into composables.
@Composable
internal fun Destination.label(): String =
    if (this is Destination.Service) {
        stringResource(service.labelRes)
    } else if (this is Destination.Alternative) {
        frontend.label
    } else {
        (this as Destination.Custom).name
    }

@Composable
internal fun Destination.openLabel(): String =
    if (this is Destination.Service) stringResource(service.openLabelRes) else stringResource(R.string.open_in_custom, label())

/** Page scaffold shared by every screen: a flat top bar (none when [title] is null) and a centered, scrollable column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Page(
    title: String?,
    titleLeading: (@Composable () -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = snackbarHost,
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
    // A link from another app on its way out shows just that, not all of Crosstune.
    if (state.handingOff) return Handoff(state, actions)
    if (state.showDestinationPicker) {
        DestinationPicker(state, actions.loadArtwork, onPick = actions.onOpenWith, onDismiss = actions.onDismissPicker)
    }
    val snackbar = remember { SnackbarHostState() }
    val clearedMessage = stringResource(R.string.history_cleared)
    val undoLabel = stringResource(R.string.undo_button)
    LaunchedEffect(state.canUndoClearHistory) {
        if (!state.canUndoClearHistory) return@LaunchedEffect
        val result = snackbar.showSnackbar(clearedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) actions.onUndoClearHistory() else actions.onForgetClearedHistory()
    }

    Page(
        title = stringResource(R.string.app_name),
        titleLeading = { AppLogo() },
        actions = {
            IconButton(onClick = onOpenSettings) {
                AppIcon(R.drawable.ic_settings, contentDescription = stringResource(R.string.settings_button))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) {
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        LinkNotices(state, actions)
        LinkField(state, actions)
        StatusSection(state, actions)
        state.result?.let { ResultCard(it, state, actions) }

        val idle = state.result == null && state.error == null && !state.isLoading
        if (idle && state.history.isEmpty()) {
            Text(
                text = stringResource(R.string.empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp)
            )
        }
        // The song on screen isn't listed again right below it.
        val history = state.history.filterNot { state.result != null && it.link.url == state.link?.url }
        if (history.isNotEmpty()) {
            HistorySection(history, actions)
        }
    }
}

/**
 * What the user sees while a link from another app is on its way out: the song once known, where
 * it's going, and a way to stop and stay in Crosstune instead.
 */
@Composable
private fun Handoff(state: UiState, actions: ScreenActions) {
    val result = state.result
    Page(title = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (result != null) {
                CoverArt(result.artworkUrl, actions.loadArtwork, size = 160.dp)
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 24.dp)
                )
                if (result.artist.isNotBlank()) {
                    Text(
                        text = result.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                AppLogo(size = 64.dp)
            }
            LinearProgressIndicator(
                modifier = Modifier
                    .padding(top = 32.dp)
                    .widthIn(max = 240.dp)
                    .fillMaxWidth()
            )
            Text(
                text = if (result == null) {
                    stringResource(R.string.loading_text)
                } else {
                    stringResource(R.string.handoff_opening, state.resultDestination.label())
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            TextButton(onClick = actions.onCancelHandoff, modifier = Modifier.padding(top = 24.dp)) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    }
}

/**
 * One line for each thing still keeping links from Crosstune: links Android doesn't let it open
 * yet (before Android 12, which can't tell, a reminder until dismissed), and installed apps that
 * still open their own links.
 */
@Composable
internal fun LinkNotices(state: UiState, actions: ScreenActions, includeNotAllowed: Boolean = true) {
    // Settings marks each service that isn't allowed yet instead.
    val allow = stringResource(R.string.allow_button)
    // Each opens the matching guide, which shows exactly what to tap in Android's settings.
    if (state.unapprovedHosts != null) {
        if (includeNotAllowed && state.someLinksNotAllowed) {
            NoticeStrip(stringResource(R.string.notice_links_not_allowed), allow, actions.onShowAllowGuide)
        }
    } else if (state.showLinkSettingsHelper) {
        NoticeStrip(
            stringResource(R.string.link_settings_helper_title), allow, actions.onShowAllowGuide,
            onDismiss = actions.onDismissLinkSettingsHelper
        )
    }
    state.blockingApps.orEmpty().forEach { app ->
        NoticeStrip(stringResource(R.string.notice_app_still_opens, app.label), stringResource(R.string.fix_button), actions.onShowAppsGuide)
    }
}

@Composable
private fun NoticeStrip(text: String, action: String, onAction: () -> Unit, onDismiss: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(R.drawable.ic_info, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            TextButton(onClick = onAction) { Text(action) }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.dismiss_button))
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
    val convertModifier = Modifier
        .fillMaxWidth()
        .padding(top = 12.dp)
        .heightIn(min = 52.dp)
    val canConvert = !busy && state.linkText.isNotBlank()
    val convertLabel = @Composable { Text(stringResource(R.string.resolve_button), style = MaterialTheme.typography.labelLarge) }
    // The main action until there's a result; then the result's Open button is.
    if (state.result == null) {
        Button(onClick = actions.onResolve, enabled = canConvert, modifier = convertModifier) { convertLabel() }
    } else {
        FilledTonalButton(onClick = actions.onResolve, enabled = canConvert, modifier = convertModifier) { convertLabel() }
    }
}

/** "Opens in YouTube Music ▾": where a result opens, or, labelled in settings, the default. */
@Composable
internal fun DefaultDestinationMenu(
    destinations: List<Destination>,
    selected: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    /** In settings the row is labelled like the others, with the choice at the end. */
    label: String? = null,
    installed: Set<MusicService> = emptySet()
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
                destinations.installedFirst(installed).forEach { destination ->
                    val isSelected = destination == selected
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    destination.label(),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        leadingIcon = { DestinationIcon(destination, installed) },
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
private fun ResultCard(result: MusicMetadata, state: UiState, actions: ScreenActions) {
    val link = state.link
    val destination = state.resultDestination
    val prepared = state.destinationUrls[destination]
    val destinationReady = !state.isMatching
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
            // Changing it here only changes this result; the default lives in settings.
            Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                DefaultDestinationMenu(
                    destinations = state.destinations,
                    selected = destination,
                    onSelect = actions.onResultTargetChange,
                    installed = state.installed,
                    modifier = Modifier.weight(1f)
                )
                if (destination != state.defaultDestination) {
                    TextButton(onClick = actions.onMakeDefault) { Text(stringResource(R.string.make_default)) }
                }
            }
            val searchFallback = prepared?.exact == false
            Button(
                onClick = actions.onOpen,
                enabled = destinationReady,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .heightIn(min = 52.dp)
            ) {
                AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(
                    if (searchFallback) stringResource(R.string.search_in_destination, destination.label()) else destination.openLabel(),
                    style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center
                )
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
                    stringResource(R.string.copy_link_button),
                    actions.onCopyLink,
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    enabled = destinationReady
                )
                SecondaryAction(
                    R.drawable.ic_share,
                    stringResource(R.string.share_link_button),
                    actions.onShareSearch,
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    enabled = destinationReady
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
private fun SecondaryAction(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding, modifier = modifier) {
        AppIcon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        // Long translations wrap to a second line instead of being cut off.
        Text(label, textAlign = TextAlign.Center)
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
                Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
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
                IconButton(onClick = { actions.onHistoryOpen(entry) }) {
                    AppIcon(R.drawable.ic_open_in_new, contentDescription = stringResource(R.string.history_open, entry.metadata.title))
                }
                IconButton(onClick = { actions.onHistoryCopy(entry) }) {
                    AppIcon(R.drawable.ic_content_copy, contentDescription = stringResource(R.string.history_copy, entry.metadata.title))
                }
            }
        }
    }
}

/** Installed apps first: services whose app is installed, and frontend apps, which are only offered once installed. */
internal fun List<Destination>.installedFirst(installed: Set<MusicService>): List<Destination> =
    sortedBy { if ((it as? Destination.Service)?.service in installed || (it is Destination.Alternative && it.packageName != null)) 0 else 1 }

/** Use installed apps' own icons, with bundled service logos for every other built-in choice. */
@Composable
private fun DestinationIcon(destination: Destination, installed: Set<MusicService>) {
    val context = LocalContext.current
    // A service's app may not be installed; a frontend app is only offered once it is.
    val app = destination.packageName?.takeIf { destination !is Destination.Service || destination.service in installed }
    val icon = remember(app) {
        app?.let { runCatching { context.packageManager.getApplicationIcon(it).toBitmap(64, 64).asImageBitmap() }.getOrNull() }
    }
    Surface(
        modifier = Modifier.size(32.dp), shape = MaterialTheme.shapes.small,
        color = if (destination is Destination.Service) Color.White else MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize().testTag("destination-icon:" + destination.key))
            } else if (destination is Destination.Service) {
                Image(
                    painterResource(destination.service.iconRes), contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(4.dp).testTag("destination-icon:" + destination.key)
                )
            } else {
                AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun DestinationPicker(state: UiState, loadArtwork: suspend (String) -> ImageBitmap?, onPick: (Destination) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                state.result?.let { result ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                        CoverArt(result.artworkUrl, loadArtwork, size = 48.dp)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(result.title, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(stringResource(result.type.labelRes), result.artist).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val hidden = state.pickerHides?.let(Destination::Service)
                state.destinations.filter { it != hidden }.installedFirst(state.installed).forEach { destination ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(destination) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DestinationIcon(destination, state.installed)
                        Column {
                            Text(destination.openLabel(), style = MaterialTheme.typography.bodyLarge)
                        }
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
