package com.astrovm.crosstune

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
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
    val onLinkModeChange: (LinkMode) -> Unit = {},
    val onExactMatchChange: (Boolean) -> Unit = {},
    val onCleanLinksChange: (Boolean) -> Unit = {},
    val onShareSheetAppsChange: (Boolean) -> Unit = {},
    val onOnlyMusicVideosChange: (Boolean) -> Unit = {},
    val onLanguageChange: (String?) -> Unit = {},
    val onCopySearch: () -> Unit = {},
    val onCopyLink: () -> Unit = {},
    val onShareSearch: () -> Unit = {},
    val onHistoryEntryClick: (HistoryEntry) -> Unit = {},
    val onHistoryOpen: (HistoryEntry) -> Unit = {},
    val onRemoveHistory: (HistoryEntry) -> Unit = {},
    val onOpenTrack: (MusicMetadata) -> Unit = {},
    val onCopySongs: () -> Unit = {},
    val onPlayAll: () -> Unit = {},
    val onShareSongs: () -> Unit = {},
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
    val screen = when {
        // A link from another app is handled right away; setup waits for the next regular launch.
        !state.setupComplete && !state.handlingIncomingLink -> Screen.SETUP
        guide != null && !state.handingOff -> if (guide == GUIDE_ALLOW) Screen.ALLOW_GUIDE else Screen.APPS_GUIDE
        showSettings -> Screen.SETTINGS
        state.handingOff -> Screen.HANDOFF
        else -> Screen.MAIN
    }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            // Setup and a link on its way out aren't places in the app, so they fade rather than slide.
            if (initialState.depth < 0 || targetState.depth < 0) swap() else slide(forward = targetState.depth > initialState.depth)
        },
        label = "screen"
    ) { shown ->
        // if/else rather than an exhaustive when, which compiles an unreachable branch into composables.
        if (shown == Screen.SETUP) {
            SetupScreen(state, actions)
        } else if (shown == Screen.ALLOW_GUIDE || shown == Screen.APPS_GUIDE) {
            GuidePage(shown == Screen.ALLOW_GUIDE, state, actions, onDone = { guide = null })
        } else if (shown == Screen.SETTINGS) {
            SettingsScreen(state, guided, onBack = { showSettings = false }, openSource = openSource, onOpenSource = { openSource = it })
        } else if (shown == Screen.HANDOFF) {
            Handoff(state, actions)
        } else {
            MainScreen(state, guided, onOpenSettings = { showSettings = true })
        }
    }
}

/** What fills the window, each with how deep it is, so moving deeper and coming back slide opposite ways. */
private enum class Screen(val depth: Int) {
    SETUP(-1), HANDOFF(-1), MAIN(0), SETTINGS(1), ALLOW_GUIDE(2), APPS_GUIDE(2)
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
        // Both guides show what Android says, so a guide restored before that's known waits for it.
        if (!state.systemStateKnown) return@Page
        // Leaving with something left to do is skipping it, so the button says so.
        val done = if (allow) {
            AllowLinksGuide(state, actions)
            !state.someLinksNotAllowed
        } else {
            val apps = appsToStop(state)
            Spacer(Modifier.height(8.dp))
            StopAppsGuide(apps, state.blockingApps, actions.onOpenAppLinkSettings)
            appsGuideDone(apps, state.blockingApps)
        }
        val press = rememberPress()
        FilledTonalButton(
            onClick = onDone,
            interactionSource = press.source,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .heightIn(min = 52.dp)
                .then(press.modifier)
        ) {
            Text(stringResource(if (done) R.string.setup_done else R.string.setup_skip_for_now), style = MaterialTheme.typography.labelLarge)
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
    /** Pass the step's scroll state so each setup step starts at the top. */
    scrollState: ScrollState = rememberScrollState(),
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
                    .verticalScroll(scrollState)
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun MainScreen(state: UiState, actions: ScreenActions, onOpenSettings: () -> Unit) {
    // The picker lists installed apps first, so it waits for Android to say which those are rather
    // than move a row from under the user's finger.
    if (state.showDestinationPicker && state.systemStateKnown) {
        DestinationPicker(state, actions.loadArtwork, onPick = actions.onOpenWith, onDismiss = actions.onDismissPicker)
    }
    val snackbar = remember { SnackbarHostState() }
    val clearedMessage = stringResource(if (state.removedOneFromHistory) R.string.history_removed else R.string.history_cleared)
    val undoLabel = stringResource(R.string.undo_button)
    // Each removal or clear gets its own offer, replacing one still showing.
    LaunchedEffect(state.canUndoClearHistory, state.historyUndoId) {
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
        // The empty hint below says what the app does, so there's no tagline above it.
        Spacer(Modifier.height(8.dp))
        LinkNotices(state, actions)
        LinkField(state, actions)
        StatusSection(state, actions)
        // A new result grows in where the last one was; the space for it opens and closes smoothly.
        AnimatedContent(targetState = state.result, transitionSpec = { swap() }, label = "result") { result ->
            result?.let {
                Column {
                    ResultCard(it, state, actions)
                    if (it.tracks.isNotEmpty()) PlaylistSongs(it.tracks, state, actions)
                }
            }
        }

        val idle = state.result == null && state.error == null && !state.isLoading
        AnimatedVisibility(visible = idle && state.history.isEmpty(), enter = fadeIn(Motion.fadeIn), exit = fadeOut(Motion.fadeOut)) {
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
        // Keyed on whether there's any, so clearing it fades the last list out rather than an empty one.
        AnimatedContent(targetState = history, contentKey = { it.isEmpty() }, transitionSpec = { fade() }, label = "history") { shown ->
            if (shown.isNotEmpty()) Column { HistorySection(shown, state, actions) }
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
            // The logo gives way to the song as soon as it's known.
            AnimatedContent(targetState = result, transitionSpec = { swap() }, label = "handoff") { shown ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (shown != null) {
                        CoverArt(shown.artworkUrl, actions.loadArtwork, size = 160.dp)
                        Text(
                            text = shown.title,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 24.dp)
                        )
                        if (shown.artist.isNotBlank()) {
                            Text(
                                text = shown.artist,
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
                }
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
                    stringResource(if (state.afterLookup == AfterLookup.OPEN) R.string.handoff_opening else R.string.handoff_getting_link, state.resultDestination.label())
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
    // Notices come and go as things are fixed, so the space they take follows smoothly.
    Column(modifier = Modifier.animateContentSize(Motion.size)) { Notices(state, actions, includeNotAllowed) }
}

@Composable
private fun Notices(state: UiState, actions: ScreenActions, includeNotAllowed: Boolean) {
    // Nothing is known to be wrong until Android has been asked.
    if (!state.systemStateKnown) return
    // Settings marks each service that isn't allowed yet instead.
    val allow = stringResource(R.string.allow_button)
    // Each opens the matching guide, which shows exactly what to tap in Android's settings.
    if (state.unapprovedHosts != null) {
        // Set to open in the browser, no link reaches Crosstune at all, so settings says so too.
        if (state.ownLinksOff && state.interceptsAnything) {
            NoticeStrip(stringResource(R.string.notice_own_links_off), stringResource(R.string.fix_button), actions.onShowAllowGuide)
        } else if (includeNotAllowed && state.someLinksNotAllowed) {
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // The keyboard would cover the result, so it goes once the link is looked up.
    val resolve = {
        keyboard?.hide()
        focus.clearFocus()
        actions.onResolve()
    }
    TextField(
        value = state.linkText,
        onValueChange = actions.onUrlChange,
        singleLine = true,
        label = { Text(stringResource(R.string.spotify_link_label)) },
        placeholder = { Text(stringResource(R.string.spotify_link_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { AppIcon(R.drawable.ic_link, contentDescription = null) },
        trailingIcon = {
            // Paste turns into clear as soon as there's text, and back.
            AnimatedContent(targetState = state.linkText.isEmpty(), transitionSpec = { swap() }, label = "trailing") { empty ->
                if (empty) {
                    IconButton(onClick = actions.onPaste, enabled = !busy) {
                        AppIcon(R.drawable.ic_content_paste, contentDescription = stringResource(R.string.paste_button))
                    }
                } else {
                    IconButton(onClick = actions.onClear, enabled = !busy) {
                        AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.clear_button))
                    }
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go
        ),
        keyboardActions = KeyboardActions(onGo = { resolve() }),
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
    // The text the result or error on screen came from: converting it again would change nothing,
    // and an error that's worth retrying has its own Try again.
    val shownText = rememberSaveable(state.result, state.error) { state.linkText }
    val shown = (state.result != null || state.error != null) && state.linkText == shownText
    val press = rememberPress()
    AnimatedVisibility(visible = !shown, enter = Motion.appear, exit = Motion.disappear) {
        // The main action until there's a result; then the result's Open button is.
        if (state.result == null) {
            Button(onClick = resolve, enabled = canConvert, interactionSource = press.source, modifier = convertModifier.then(press.modifier)) { convertLabel() }
        } else {
            FilledTonalButton(onClick = resolve, enabled = canConvert, interactionSource = press.source, modifier = convertModifier.then(press.modifier)) { convertLabel() }
        }
    }
}

/**
 * "Open links in   YouTube Music ▾": what tapped and shared links do, in settings. Opening in an
 * app is the usual; asking which app, or showing the song here first, sit at the top of the menu.
 */
@Composable
internal fun DefaultDestinationMenu(
    destinations: List<Destination>,
    selected: Destination,
    mode: LinkMode,
    onSelect: (Destination) -> Unit,
    onMode: (LinkMode) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    installed: Set<MusicService> = emptySet()
) {
    var expanded by remember { mutableStateOf(false) }
    val modeLabels = mapOf(
        LinkMode.ASK to stringResource(R.string.setting_ask_each_time),
        LinkMode.SHOW to stringResource(R.string.show_song_first)
    )
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag(DEFAULT_MENU_TAG)) {
                if (mode == LinkMode.OPEN) DestinationIcon(selected, installed, size = 20.dp) else ModeIcon(mode, size = 20.dp)
                Spacer(Modifier.size(8.dp))
                Text(modeLabels[mode] ?: selected.label())
                AppIcon(
                    R.drawable.ic_expand_more,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp)
                        .flipWhen(expanded)
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                modeLabels.forEach { (itemMode, itemLabel) ->
                    MenuChoice(itemLabel, mode == itemMode, icon = { ModeIcon(itemMode) }) {
                        expanded = false
                        onMode(itemMode)
                    }
                }
                HorizontalDivider()
                destinations.installedFirst(installed).forEach { destination ->
                    MenuChoice(destination.label(), mode == LinkMode.OPEN && destination == selected, icon = { DestinationIcon(destination, installed) }) {
                        expanded = false
                        onSelect(destination)
                        onMode(LinkMode.OPEN)
                    }
                }
            }
        }
    }
}

/** One choice in a dropdown, the chosen one in the accent color. */
@Composable
private fun MenuChoice(text: String, isSelected: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
        leadingIcon = icon,
        onClick = onClick,
        // The color alone doesn't tell TalkBack which one is chosen.
        modifier = Modifier.semantics { this.selected = isSelected }
    )
}

/** Asking shows a grid of apps; showing first, Crosstune's own logo. */
@Composable
private fun ModeIcon(mode: LinkMode, size: Dp = 32.dp) {
    Surface(modifier = Modifier.size(size), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            if (mode == LinkMode.ASK) {
                AppIcon(R.drawable.ic_ask, contentDescription = null, modifier = Modifier.size(size * 0.6f))
            } else {
                AppLogo(size = size * 0.7f)
            }
        }
    }
}

@Composable
private fun StatusSection(state: UiState, actions: ScreenActions) {
    AnimatedVisibility(visible = state.isLoading || state.isMatching, enter = Motion.appear, exit = Motion.disappear) {
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

    // Keyed on whether there's one, so a fixed error fades out with its message still on it.
    AnimatedContent(targetState = state.error, contentKey = { it != null }, transitionSpec = { fade() }, label = "error") { error ->
        if (error != null) ErrorCard(error, state, actions)
    }
}

@Composable
private fun ErrorCard(error: AppError, state: UiState, actions: ScreenActions) {
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
                // The theme's accent color is hard to read on the error color.
                val onError = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.canRetry) {
                        TextButton(onClick = actions.onRetry, colors = onError) {
                            Text(stringResource(R.string.retry_button))
                        }
                    }
                    // A link Crosstune couldn't read can still be opened in the app it belongs to.
                    state.link?.let { link ->
                        TextButton(onClick = actions.onOpenOriginal, colors = onError) {
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
            val searchFallback = prepared?.exact == false
            // One split button: the main part opens it, the arrow picks another app for just this result.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val press = rememberPress()
                val openLabel = if (searchFallback) stringResource(R.string.search_in_destination, destination.label()) else destination.openLabel()
                Button(
                    onClick = actions.onOpen,
                    enabled = destinationReady,
                    colors = openButtonColors(state),
                    shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = 6.dp, bottomEnd = 6.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    interactionSource = press.source,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .then(press.modifier)
                ) {
                    // Picking another app swaps the icon and label in place.
                    AnimatedContent(targetState = destination to openLabel, transitionSpec = { swap() }, label = "open") { (shown, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DestinationIcon(shown, state.installed, size = 24.dp)
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                        }
                    }
                }
                DestinationMenuButton(state, actions)
            }
            // Asking or showing first, the app picked last is remembered instead.
            if (destination != state.defaultDestination && state.linkMode == LinkMode.OPEN) {
                TextButton(onClick = actions.onMakeDefault, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.make_default_named, destination.label()))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Same height even when only one label wraps.
                // With its songs listed, the songs are what's worth copying or sharing, e.g. to move
                // the list to another service, rather than a search for its name.
                val songs = result.tracks.isNotEmpty()
                SecondaryAction(
                    R.drawable.ic_content_copy,
                    stringResource(if (songs) R.string.copy_songs_button else R.string.copy_link_button),
                    if (songs) actions.onCopySongs else actions.onCopyLink,
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    enabled = songs || destinationReady
                )
                SecondaryAction(
                    R.drawable.ic_share,
                    stringResource(if (songs) R.string.share_songs_button else R.string.share_link_button),
                    if (songs) actions.onShareSongs else actions.onShareSearch,
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    enabled = songs || destinationReady
                )
            }
        }
    }
}

/**
 * A playlist only searches its name elsewhere, so with its songs listed below, they come first
 * and the Open button steps back.
 */
@Composable
private fun openButtonColors(state: UiState): ButtonColors =
    if (state.result?.type == ItemType.PLAYLIST && state.result.tracks.isNotEmpty()) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors()

/** The split button's arrow: lists the apps to open this result in instead. */
@Composable
private fun DestinationMenuButton(state: UiState, actions: ScreenActions) {
    var expanded by remember { mutableStateOf(false) }
    val press = rememberPress()
    Box {
        Button(
            onClick = { expanded = true },
            colors = openButtonColors(state),
            shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 26.dp, bottomEnd = 26.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            interactionSource = press.source,
            modifier = Modifier
                .fillMaxHeight()
                .testTag(DEFAULT_MENU_TAG)
                .then(press.modifier)
        ) {
            AppIcon(R.drawable.ic_expand_more, contentDescription = stringResource(R.string.choose_app_button), modifier = Modifier.flipWhen(expanded))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.destinations.installedFirst(state.installed).forEach { destination ->
                val isSelected = destination == state.resultDestination
                DropdownMenuItem(
                    text = {
                        Text(
                            destination.label(),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = { DestinationIcon(destination, state.installed) },
                    onClick = {
                        expanded = false
                        actions.onResultTargetChange(destination)
                    },
                    modifier = Modifier.semantics { this.selected = isSelected }
                )
            }
        }
    }
}

@Composable
private fun SecondaryAction(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val press = rememberPress()
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        interactionSource = press.source,
        modifier = modifier.then(press.modifier)
    ) {
        AppIcon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        // Long translations wrap to a second line instead of being cut off.
        Text(label, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HistorySection(history: List<HistoryEntry>, state: UiState, actions: ScreenActions) {
    SectionHeader(
        title = stringResource(R.string.history_title),
        action = {
            TextButton(onClick = actions.onClearHistory) {
                Text(stringResource(R.string.clear_history_button))
            }
        },
        modifier = Modifier.padding(top = 8.dp)
    )
    // A longer list gets a search, by title or artist.
    var query by rememberSaveable { mutableStateOf("") }
    val searchable = history.size > SEARCHABLE_HISTORY
    if (searchable) {
        TextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_recent)) },
            leadingIcon = { AppIcon(R.drawable.ic_search, contentDescription = null) },
            shape = MaterialTheme.shapes.large,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )
    }
    val words = query.trim().lowercase().split(" ").filter { it.isNotEmpty() }
    val shown = if (!searchable) history else history.filter { entry ->
        val text = "${entry.metadata.title} ${entry.metadata.artist}".lowercase()
        words.all { it in text }
    }
    Group {
        shown.forEachIndexed { index, entry ->
            // Keyed, so swiping one away doesn't hand its swipe to the row that moves up.
            key(entry.link.url) {
                if (index > 0) GroupDivider()
                // Only a row going somewhere other than the default app says where.
                HistoryRow(entry, state.ruleFor(entry.link)?.takeIf { it != state.defaultDestination }, state.installed, actions)
            }
        }
    }
}

/**
 * One item in Recent: its cover, what it is, and a play button, which shows the app's icon instead
 * when it opens somewhere other than the default. Tapping the rest shows the full result; swiping
 * either way removes it.
 */
@Composable
private fun HistoryRow(entry: HistoryEntry, elsewhere: Destination?, installed: Set<MusicService>, actions: ScreenActions) {
    val swipe = rememberSwipeToDismissBoxState()
    val remove = stringResource(R.string.remove_button)
    SwipeToDismissBox(
        state = swipe,
        onDismiss = { actions.onRemoveHistory(entry) },
        backgroundContent = { RemoveBackground(swipe.dismissDirection) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { actions.onHistoryEntryClick(entry) }
                // Swiping isn't something TalkBack can do, so removing is an action of its own there.
                .semantics { customActions = listOf(CustomAccessibilityAction(remove) { actions.onRemoveHistory(entry); true }) }
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverArt(entry.metadata.artworkUrl, actions.loadArtwork, size = 48.dp)
            Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp).weight(1f)) {
                Text(
                    text = entry.metadata.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    // Most are songs, so only other kinds say what they are, which leaves room
                    // for the service before the line is cut.
                    text = listOf(
                        if (entry.link.type == ItemType.TRACK) "" else stringResource(entry.link.type.labelRes),
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
            val press = rememberPress()
            val openLabel = stringResource(R.string.history_open, entry.metadata.title)
            IconButton(
                onClick = { actions.onHistoryOpen(entry) },
                interactionSource = press.source,
                modifier = press.modifier.semantics { contentDescription = openLabel }
            ) {
                if (elsewhere != null) {
                    DestinationIcon(elsewhere, installed, size = 24.dp)
                } else {
                    AppIcon(R.drawable.ic_play, contentDescription = null, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

/** What shows under a row as it's swiped: the error color, and a bin on the side it's going to. */
@Composable
private fun RemoveBackground(direction: SwipeToDismissBoxValue) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        AppIcon(R.drawable.ic_delete, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

/** A playlist's songs, each opening on its own where the result goes. */
@Composable
private fun PlaylistSongs(tracks: List<MusicMetadata>, state: UiState, actions: ScreenActions) {
    val progress = state.queueProgress
    SectionHeader(
        title = stringResource(R.string.playlist_songs_title),
        modifier = Modifier.padding(top = 8.dp),
        action = if (!state.canPlayAll) null else {
            {
                // Matching every song takes a moment, so it shows how far it got.
                AnimatedContent(targetState = progress, contentKey = { it != null }, transitionSpec = { swap() }, label = "play all") { shown ->
                    if (shown != null) {
                        Text(
                            stringResource(R.string.play_all_progress, shown.first, shown.second),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(12.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    } else {
                        TextButton(onClick = actions.onPlayAll) {
                            AppIcon(R.drawable.ic_play, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.play_all_button), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    )
    Group {
        tracks.forEachIndexed { index, track ->
            if (index > 0) GroupDivider()
            val openLabel = stringResource(R.string.history_open, track.title)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = openLabel) { actions.onOpenTrack(track) }
                    .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 28.dp)
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (track.artist.isNotBlank()) {
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                AppIcon(R.drawable.ic_play, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** How many Recent items fit at a glance; past that, a search shows above them. */
private const val SEARCHABLE_HISTORY = 5

/** Installed apps first: services whose app is installed, and frontend apps, which are only offered once installed. */
internal fun List<Destination>.installedFirst(installed: Set<MusicService>): List<Destination> =
    sortedBy { if ((it as? Destination.Service)?.service in installed || (it is Destination.Alternative && it.packageName != null)) 0 else 1 }

/**
 * A destination's icon: an installed app's own, the bundled logo of a service, or, for sites
 * with no logo of their own (Invidious, Piped, custom ones), their first letter on a colored tile.
 */
@Composable
internal fun DestinationIcon(destination: Destination, installed: Set<MusicService>, size: Dp = 32.dp) {
    val context = LocalContext.current
    // A service's app may not be installed; a frontend app is only offered once it is.
    val app = destination.packageName?.takeIf { destination !is Destination.Service || destination.service in installed }
    val icon = remember(app) {
        app?.let { runCatching { context.packageManager.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    val tag = Modifier.testTag("destination-icon:" + destination.key)
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = tag.size(size).clip(MaterialTheme.shapes.small))
        return
    }
    if (destination is Destination.Service) {
        Surface(modifier = Modifier.size(size), shape = MaterialTheme.shapes.small, color = Color.White) {
            Image(painterResource(destination.service.iconRes), contentDescription = null, modifier = tag.fillMaxSize().padding(size / 8))
        }
        return
    }
    val (letter, background) = when (destination) {
        is Destination.Alternative -> destination.frontend.label.take(1) to (destination.frontend.color?.let(::Color) ?: MaterialTheme.colorScheme.primary)
        else -> destination.label().take(1) to MaterialTheme.colorScheme.tertiary
    }
    Surface(modifier = tag.size(size), shape = MaterialTheme.shapes.small, color = background, contentColor = Color.White) {
        Box(contentAlignment = Alignment.Center) {
            Text(letter.uppercase(), style = MaterialTheme.typography.titleMedium, fontSize = (size.value * 0.5f).sp)
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
