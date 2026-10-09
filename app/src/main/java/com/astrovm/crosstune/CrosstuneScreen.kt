package com.astrovm.crosstune

import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import com.astrovm.crosstune.ui.theme.Palette

internal const val RESULT_TAG = "result"
internal const val RESULT_TEXT_TAG = "result-text"
internal const val DEFAULT_MENU_TAG = "default_destination"

internal data class ScreenActions(
    val onUrlChange: (String) -> Unit = {},
    val onResolve: () -> Unit = {},
    val onPaste: () -> Unit = {},
    /** What names a song playing nearby: Crosstune itself first, then Shazam and Google. */
    val recognizers: List<SongRecognizer> = emptyList(),
    /** The one the Recognize button opens: the user's pick, or else the first there is. */
    val recognizer: SongRecognizer? = null,
    val onRecognize: (SongRecognizer) -> Unit = {},
    val onRecognizerChange: (SongRecognizer) -> Unit = {},
    val onStopListening: () -> Unit = {},
    /** Android's settings for Crosstune, where the microphone is allowed. */
    val onOpenMicrophoneSettings: () -> Unit = {},
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
    /** Plays the songs as one queue, from the given one, as many as YouTube takes. */
    val onPlayAll: (Int) -> Unit = {},
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
    /** Fetches the shown song's words and shows them. */
    val onShowLyrics: () -> Unit = {},
    /** Closes the words. */
    val onDismissLyrics: () -> Unit = {},
    /** Keeps the microphone listening along with the words shown, to stay in time with them. */
    val onListenAlong: () -> Unit = {},
    val onStopListeningAlong: () -> Unit = {},
    /** Moves the music app playing the song to where a line of its words is sung. */
    val onSeekLyrics: (Long) -> Unit = {},
    val onReadingsChange: (Boolean) -> Unit = {},
    val onRomanizedChange: (Boolean) -> Unit = {},
    val onTranslationChange: (Boolean) -> Unit = {},
    val onRetryTranslation: () -> Unit = {},
    val onToggleSavedLine: (Int) -> Unit = {},
    val onRemoveSavedLine: (SavedLine) -> Unit = {},
    val onLookUpWord: (Word) -> Unit = {},
    val onDismissWord: () -> Unit = {},
    val onRepeatLine: (Int) -> Unit = {},
    val onStopRepeating: () -> Unit = {},
    /** Sets where translations come from, by address and key; false when the address isn't one. */
    val onTranslationServerChange: (String, String) -> Boolean = { _, _ -> true },
    /** Lets the words follow music apps: opens Android's settings for it, or the steps there are to it. */
    val onAllowFollowing: () -> Unit = {},
    /** Opens Android's page for Crosstune seeing what music apps play, from the steps. */
    val onOpenFollowAccess: () -> Unit = {},
    /** Opens Crosstune's App info, where restricted settings are allowed. */
    val onOpenAppInfo: () -> Unit = {},
    val onDismissFollowHelp: () -> Unit = {},
    val onThemeChange: (ThemeMode) -> Unit = {},
    /** Searches where the result goes, though it wasn't found there. */
    val onSearchAnyway: () -> Unit = {},
    val onNotFoundActionChange: (NotFoundAction) -> Unit = {},
    /** Takes the not-found offer's way: where the song is, or a search where it was going. */
    val onTakeNotFoundOffer: (original: Boolean) -> Unit = {},
    val onDismissNotFoundOffer: () -> Unit = {},
    val onPaletteChange: (Palette) -> Unit = {},
    val onPureBlackChange: (Boolean) -> Unit = {},
    /** Floats the words over other apps now, asking Android first if need be. */
    val onFloat: () -> Unit = {},
    /** Visuals on or off behind the words; on asks for the microphone, which Android needs to share what plays. */
    val onVisualsChange: (Boolean) -> Unit = {},
    /** Opens the chosen song from the list of songs a typed name turned up. */
    val onPickSong: (MusicMetadata) -> Unit = {},
    /** Closes the list of songs. */
    val onDismissSongSearch: () -> Unit = {},
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
        state.listening -> Screen.LISTENING
        guide != null && !state.handingOff -> if (guide == GUIDE_ALLOW) Screen.ALLOW_GUIDE else Screen.APPS_GUIDE
        showSettings -> Screen.SETTINGS
        state.handingOff -> Screen.HANDOFF
        state.lyricsFor != null -> Screen.LYRICS
        else -> Screen.MAIN
    }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            // Setup and a link on its way out aren't places in the app, so they fade rather than slide.
            // The words rise over the song they're of, and sink back into it.
            if (initialState == Screen.LYRICS || targetState == Screen.LYRICS) rise(up = targetState == Screen.LYRICS)
            else if (initialState.depth < 0 || targetState.depth < 0) swap() else slide(forward = targetState.depth > initialState.depth)
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
        } else if (shown == Screen.LISTENING) {
            ListeningScreen(actions)
        } else if (shown == Screen.LYRICS) {
            LyricsScreen(state, actions)
        } else {
            MainScreen(state, guided, onOpenSettings = { showSettings = true })
        }
    }
    // Asked for from the words or from settings, so it shows over either.
    if (state.followHelp) FollowHelp(actions)
    state.notFoundOffer?.let { NotFoundDialog(it, state, actions) }
}

/** What fills the window, each with how deep it is, so moving deeper and coming back slide opposite ways. */
private enum class Screen(val depth: Int) {
    SETUP(-1), HANDOFF(-1), LISTENING(-1), MAIN(0), LYRICS(1), SETTINGS(1), ALLOW_GUIDE(2), APPS_GUIDE(2)
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
    floatingActionButton: @Composable () -> Unit = {},
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
                            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = navigationIcon,
                    actions = { actions() },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = FabPosition.Center
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
    // Only while a name is being looked up: a song picked or dismissed leaves nothing to show.
    if (state.isSearchingSongs || state.songSearch.isNotEmpty() || state.songSearchQuery.isNotBlank()) {
        SongSearchSheet(state, actions.loadArtwork, actions.onPickSong, actions.onDismissSongSearch)
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
    // A long list's main button scrolls away with the card, so a floating one stands in for it.
    var mainButtonShown by remember { mutableStateOf(true) }
    val songList = state.result?.takeIf { state.isSongList && it.tracks.isNotEmpty() }
    // Where each part of a long list starts on screen, so the floating button plays the one in view.
    val partTops = remember(state.link) { mutableStateMapOf<Int, Float>() }
    // A part counts as in view once its header is in the top two thirds, so a short last part counts too.
    val line = LocalWindowInfo.current.containerSize.height * 2 / 3f
    val partInView = partTops.filterValues { it < line }.keys.maxOrNull() ?: 0

    Page(
        title = stringResource(R.string.app_name),
        titleLeading = { AppLogo() },
        actions = {
            IconButton(onClick = onOpenSettings) {
                AppIcon(R.drawable.ic_settings, contentDescription = stringResource(R.string.settings_button))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { songList?.let { PlayButton(it, state, actions, part = partInView, visible = !mainButtonShown) } }
    ) {
        // The empty hint below says what the app does, so there's no tagline above it.
        Spacer(Modifier.height(8.dp))
        LinkNotices(state, actions)
        LinkField(state, actions)
        StatusSection(state, actions)
        // A new result grows in where the last one was; the space for it opens and closes smoothly.
        // Covers found for a playlist's songs fill in where they are rather than bring a new result.
        AnimatedContent(targetState = state.result, contentKey = { it?.copy(tracks = emptyList()) }, transitionSpec = { swap() }, label = "result") { result ->
            result?.let {
                Column {
                    ResultCard(it, state, actions, onMainButtonShown = { shown -> mainButtonShown = shown })
                    if (it.tracks.isNotEmpty()) PlaylistSongs(it, state, actions, onPartTop = { part, top -> partTops[part] = top })
                    // Room to scroll the last song out from under the floating button.
                    if (state.isSongList) Spacer(Modifier.height(72.dp))
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
                            modifier = Modifier.padding(top = 24.dp)
                        )
                        if (shown.artist.isNotBlank()) {
                            Text(
                                text = shown.artist,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
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
                    Row {
                        // Crosstune listens itself, or opens another app, which shares the song back here.
                        actions.recognizer?.let { recognizer ->
                            IconButton(onClick = { actions.onRecognize(recognizer) }, enabled = !busy) {
                                AppIcon(R.drawable.ic_recognize, contentDescription = stringResource(R.string.recognize_button))
                            }
                        }
                        IconButton(onClick = actions.onPaste, enabled = !busy) {
                            AppIcon(R.drawable.ic_content_paste, contentDescription = stringResource(R.string.paste_button))
                        }
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
        .heightIn(min = 56.dp)
    val canConvert = !busy && state.linkText.isNotBlank()
    val convertLabel = @Composable { Text(stringResource(R.string.resolve_button), style = MaterialTheme.typography.labelLarge) }
    // The text the result or error on screen came from: converting it again would change nothing,
    // and an error that's worth retrying has its own Try again.
    val shownText = rememberSaveable(state.result, state.error) { state.linkText }
    val shown = (state.result != null || state.error != null) && state.linkText == shownText
    val press = rememberPress()
    // Only once there's something to convert: an empty field needs no button under it.
    AnimatedVisibility(visible = !shown && state.linkText.isNotBlank(), enter = Motion.appear, exit = Motion.disappear) {
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

/**
 * The songs a typed name turned up, to pick from, since a song can be converted without its link.
 * The name searched for is on the sheet, so a list of songs says what it is a list of.
 */
@Composable
private fun SongSearchSheet(state: UiState, loadArtwork: suspend (String) -> ImageBitmap?, onPick: (MusicMetadata) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.song_search_title))
                Text(
                    stringResource(R.string.song_search_label_for, state.songSearchQuery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            if (state.isSearchingSongs) {
                Column {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.loading_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    state.songSearch.forEach { song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(song) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CoverArt(song.artworkUrl, loadArtwork, size = 48.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                // Whole: versions like "(12 Mix)" are what tell songs of one name apart.
                                Text(song.title, style = MaterialTheme.typography.bodyLarge)
                                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (state.songSearch.isEmpty()) {
                        Text(
                            stringResource(R.string.song_search_none, state.songSearchQuery),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss_button)) }
        }
    )
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
                    if (error == AppError.MICROPHONE) {
                        TextButton(onClick = actions.onOpenMicrophoneSettings, colors = onError) {
                            Text(stringResource(R.string.open_settings_button))
                        }
                    }
                    // Shazam is busy, but another app may still name the song.
                    if (error == AppError.RECOGNITION_UNAVAILABLE) {
                        actions.recognizers.filterNot { it.listensHere }.forEach { recognizer ->
                            TextButton(onClick = { actions.onRecognize(recognizer) }, colors = onError) {
                                Text(stringResource(R.string.open_app_button, recognizer.label))
                            }
                        }
                    }
                    // A link Crosstune couldn't read can still be opened in the app it belongs to.
                    state.link?.service?.let { service ->
                        TextButton(onClick = actions.onOpenOriginal, colors = onError) {
                            Text(stringResource(service.openLabelRes))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: MusicMetadata, state: UiState, actions: ScreenActions, onMainButtonShown: (Boolean) -> Unit = {}) {
    val link = state.link
    val destination = state.resultDestination
    val destinationReady = !state.isMatching
    // The cover lights the space behind it in its own color, which eases in once it's known.
    val glow by animateColorAsState(
        coverColor(result.artworkUrl, actions.loadArtwork) ?: MaterialTheme.colorScheme.primary,
        tween(durationMillis = 700),
        label = "glow"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .animateContentSize(Motion.size)
            .testTag(RESULT_TAG),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(296.dp)
                .background(Brush.radialGradient(listOf(glow.copy(alpha = 0.5f), Color.Transparent)))
        ) {
            Box {
                CoverArt(
                    result.artworkUrl,
                    actions.loadArtwork,
                    size = 208.dp,
                    modifier = Modifier.shadow(28.dp, MaterialTheme.shapes.medium, ambientColor = glow, spotColor = glow)
                )
                // Where it's from, by its icon on the cover's corner rather than in words.
                link?.service?.let { service ->
                    val from = stringResource(R.string.from_service, stringResource(service.labelRes))
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 10.dp, y = 10.dp)
                            .shadow(6.dp, CircleShape)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(4.dp)
                            .semantics { contentDescription = from }
                    ) {
                        DestinationIcon(Destination.Service(service), state.installed, size = 32.dp)
                    }
                }
            }
        }
        // Tapping the song copies its search text, e.g. to paste into an app Crosstune can't open.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag(RESULT_TEXT_TAG)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = stringResource(R.string.copy_search_action), onClick = actions.onCopySearch)
                // Inset inside the clip so the rounded corner doesn't cut into the first glyph.
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // A song needs no saying; an album, playlist or artist does.
            if (result.type != ItemType.TRACK) {
                Text(
                    text = stringResource(result.type.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            // Whole, however long: the edit or version at the end is often what tells it apart.
            Text(
                text = result.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            if (result.artist.isNotBlank()) {
                Text(
                    text = result.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        val main = mainAction(result, state, actions)
        val missing = state.resultMissing && state.notFoundAction != NotFoundAction.SEARCH
        // It was looked for where it's going and isn't there, which is worth saying before the button.
        // Its app's icon, crossed out, says so at a glance, with a search there still a tap away, e.g. for another name.
        AnimatedVisibility(visible = missing, enter = Motion.appear, exit = Motion.disappear) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
                    Box(Modifier.alpha(0.45f)) { DestinationIcon(destination, state.installed, size = 24.dp) }
                    Box(
                        Modifier
                            .size(width = 34.dp, height = 2.5.dp)
                            .rotate(-45f)
                            .background(MaterialTheme.colorScheme.onSurface, CircleShape)
                    )
                }
                Text(
                    stringResource(R.string.not_found_on, destination.label()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp)
                )
                if (state.link?.service != null) {
                    IconButton(onClick = actions.onSearchAnyway, modifier = Modifier.padding(start = 4.dp)) {
                        AppIcon(R.drawable.ic_search, contentDescription = stringResource(R.string.search_anyway, destination.label()))
                    }
                }
            }
        }
        // One split button: the main part opens it, the arrow picks another app for just this result.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val press = rememberPress()
            Button(
                onClick = main.onClick,
                enabled = main.enabled,
                shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 8.dp, bottomEnd = 8.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                interactionSource = press.source,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .then(press.modifier)
                    // Once it scrolls away, a floating copy of it takes over.
                    .onGloballyPositioned { onMainButtonShown(it.boundsInWindow().height > 0f) }
            ) {
                // Picking another app swaps the icon and label in place. The icon says which app,
                // so a short label fits on one line; screen readers hear the app's name too.
                AnimatedContent(targetState = Triple(main.icon ?: destination, main.label, main.description), transitionSpec = { swap() }, label = "open") { (shown, text, description) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (description != text) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier
                    ) {
                        DestinationIcon(shown, state.installed, size = 24.dp)
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    }
                }
            }
            DestinationMenuButton(state, actions)
        }
        // A later part shows its own, down by its button.
        QueueProgress(state.queueProgress?.takeIf { state.queueFrom == 0 })
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
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Same height even when only one label wraps.
            // With its songs listed, the songs are what's worth copying or sharing, e.g. to move
            // the list to another service, rather than a search for its name. The songs listed
            // right below say what's copied, so the label is short; screen readers hear it all.
            val songs = result.tracks.isNotEmpty()
            SecondaryAction(
                R.drawable.ic_content_copy,
                stringResource(if (songs) R.string.copy_button else R.string.copy_link_button),
                if (songs) actions.onCopySongs else actions.onCopyLink,
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                enabled = songs || destinationReady,
                description = if (songs) stringResource(R.string.copy_songs_button) else null
            )
            SecondaryAction(
                R.drawable.ic_share,
                stringResource(if (songs) R.string.share_button else R.string.share_link_button),
                if (songs) actions.onShareSongs else actions.onShareSearch,
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                enabled = songs || destinationReady,
                description = if (songs) stringResource(R.string.share_songs_button) else null
            )
            // Only a song has words, and one whose artist is unknown can't be looked up safely.
            if (result.type == ItemType.TRACK && result.artist.isNotBlank()) {
                SecondaryAction(
                    R.drawable.ic_lyrics,
                    stringResource(R.string.lyrics_button),
                    actions.onShowLyrics,
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

/** What a result's main button says and does. [description] adds the app's name, which its icon shows. */
private class MainAction(val label: String, val description: String, val enabled: Boolean, val icon: Destination? = null, val onClick: () -> Unit)

@Composable
private fun mainAction(result: MusicMetadata, state: UiState, actions: ScreenActions): MainAction {
    val destination = state.resultDestination
    val app = destination.label()
    val enabled = !state.isMatching && state.queueProgress == null
    // A playlist's songs play, since a search for its name elsewhere rarely finds it.
    if (state.isSongList) {
        if (!state.canPlayAll) {
            return MainAction(stringResource(R.string.play_first_button), stringResource(R.string.play_first_in, app), enabled) {
                actions.onOpenTrack(result.tracks.first())
            }
        }
        // Past what one queue takes, it says which part plays.
        val max = MainViewModel.MAX_QUEUE
        return if (result.tracks.size > max) {
            MainAction(stringResource(R.string.play_part, 1, max), stringResource(R.string.play_part_in, 1, max, app), enabled) { actions.onPlayAll(0) }
        } else {
            MainAction(stringResource(R.string.play_all_button), stringResource(R.string.play_all_in, app), enabled) { actions.onPlayAll(0) }
        }
    }
    // Not there, the song plays where it is instead, unless searching anyway was picked.
    state.link?.service?.takeIf { state.resultMissing && state.notFoundAction != NotFoundAction.SEARCH }?.let { original ->
        val label = stringResource(original.openLabelRes)
        return MainAction(label, label, enabled, Destination.Service(original), actions.onOpenOriginal)
    }
    val label = if (state.destinationUrls[destination]?.exact == false) {
        stringResource(R.string.search_in_destination, app)
    } else {
        destination.openLabel()
    }
    return MainAction(label, label, enabled, onClick = actions.onOpen)
}

/**
 * The main button, floating at the bottom once the card it's on has scrolled away. In a long list,
 * it plays the [part] in view, which starts at that song.
 */
@Composable
private fun PlayButton(result: MusicMetadata, state: UiState, actions: ScreenActions, part: Int, visible: Boolean) {
    val first = mainAction(result, state, actions)
    val to = minOf(part + MainViewModel.MAX_QUEUE, result.tracks.size)
    val app = state.resultDestination.label()
    val main = if (part == 0) first else MainAction(
        stringResource(R.string.play_part, part + 1, to),
        stringResource(R.string.play_part_in, part + 1, to, app),
        first.enabled
    ) { actions.onPlayAll(part) }
    val progress = state.queueProgress
    AnimatedVisibility(visible = visible, enter = Motion.appear, exit = Motion.disappear) {
        ExtendedFloatingActionButton(
            onClick = { if (main.enabled) main.onClick() },
            icon = { DestinationIcon(state.resultDestination, state.installed, size = 24.dp) },
            text = {
                // While it finds the songs, it says how far it got.
                val text = progress?.let { stringResource(R.string.play_all_progress, it.first, it.second) } ?: main.label
                Text(text, modifier = if (progress == null && main.description != text) Modifier.clearAndSetSemantics { contentDescription = main.description } else Modifier)
            }
        )
    }
}

/** Matching every song for Play all takes a moment, so it shows how far it got. */
@Composable
private fun QueueProgress(progress: Pair<Int, Int>?, modifier: Modifier = Modifier) {
    // The last count stays on while it fades out.
    var last by remember { mutableStateOf(0 to 1) }
    LaunchedEffect(progress) { progress?.let { last = it } }
    val shown = progress ?: last
    AnimatedVisibility(visible = progress != null, enter = Motion.appear, exit = Motion.disappear) {
        Column(modifier = modifier.padding(top = 16.dp)) {
            LinearProgressIndicator(progress = { shown.first.toFloat() / shown.second }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.play_all_progress, shown.first, shown.second),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

/** The split button's arrow: lists the apps to open this result in instead. */
@Composable
private fun DestinationMenuButton(state: UiState, actions: ScreenActions) {
    var expanded by remember { mutableStateOf(false) }
    val press = rememberPress()
    Box {
        Button(
            onClick = { expanded = true },
            shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 28.dp, bottomEnd = 28.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
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

/** A light tile with an icon over its label, so three fit in a row without crowding the song. */
@Composable
private fun SecondaryAction(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** What screen readers hear instead of a short [label]. */
    description: String? = null
) {
    val press = rememberPress()
    val content = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = content,
        interactionSource = press.source,
        modifier = modifier.then(press.modifier)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 14.dp)
                .then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier)
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f),
                modifier = Modifier.size(22.dp)
            )
            // Long translations wrap to a second line instead of being cut off.
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun HistorySection(history: List<HistoryEntry>, state: UiState, actions: ScreenActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val closeSearch = {
        searchExpanded = false
        query = ""
        keyboard?.hide()
        focus.clearFocus()
    }
    BackHandler(enabled = searchExpanded) { closeSearch() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 4.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(
            targetState = searchExpanded,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(Motion.fadeIn) + expandHorizontally(Motion.size, expandFrom = Alignment.End)) togetherWith fadeOut(Motion.fadeOut)
            },
            label = "recent search"
        ) { expanded ->
            if (expanded) {
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
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            } else {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        IconButton(onClick = { if (searchExpanded) closeSearch() else searchExpanded = true }) {
            AppIcon(
                if (searchExpanded) R.drawable.ic_close else R.drawable.ic_search,
                contentDescription = stringResource(if (searchExpanded) R.string.close_recent_search else R.string.search_recent)
            )
        }
        IconButton(onClick = actions.onClearHistory) {
            AppIcon(R.drawable.ic_delete, contentDescription = stringResource(R.string.clear_history_button))
        }
    }
    val words = query.trim().lowercase().split(" ").filter { it.isNotEmpty() }
    val shown = history.filter { entry ->
        val text = "${entry.metadata.title} ${entry.metadata.artist}".lowercase()
        words.all { it in text }
    }
    Column(modifier = Modifier.animateContentSize(Motion.size), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEachIndexed { index, entry ->
            // Keyed, so swiping one away doesn't hand its swipe to the row that moves up.
            key(entry.link.url) {
                // Only a row going somewhere other than the default app says where.
                HistoryRow(entry, state.ruleFor(entry.link)?.takeIf { it != state.defaultDestination }, state.installed, actions, Modifier.enterIn(index))
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
private fun HistoryRow(entry: HistoryEntry, elsewhere: Destination?, installed: Set<MusicService>, actions: ScreenActions, modifier: Modifier = Modifier) {
    val swipe = rememberSwipeToDismissBoxState()
    val remove = stringResource(R.string.remove_button)
    SwipeToDismissBox(
        state = swipe,
        onDismiss = { actions.onRemoveHistory(entry) },
        // Only while it's swiped, so its color never shows at a resting row's rounded edge.
        backgroundContent = { if (swipe.dismissDirection != SwipeToDismissBoxValue.Settled) RemoveBackground(swipe.dismissDirection) },
        modifier = modifier.clip(MaterialTheme.shapes.medium)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable { actions.onHistoryEntryClick(entry) }
                // Swiping isn't something TalkBack can do, so removing is an action of its own there.
                .semantics { customActions = listOf(CustomAccessibilityAction(remove) { actions.onRemoveHistory(entry); true }) }
                .padding(start = 4.dp, end = 0.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverArt(entry.metadata.artworkUrl, actions.loadArtwork, size = 56.dp)
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
                        entry.link.service?.let { stringResource(it.labelRes) }.orEmpty()
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
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        AppIcon(R.drawable.ic_delete, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

/**
 * A collection's songs, each opening on its own where the result goes. A playlist's show their
 * covers; an album's, which share one, their number. Past what YouTube takes in one queue, each
 * part of a long list can be played on its own.
 */
@Composable
private fun PlaylistSongs(result: MusicMetadata, state: UiState, actions: ScreenActions, onPartTop: (Int, Float) -> Unit = { _, _ -> }) {
    val tracks = result.tracks
    val busy = state.queueProgress != null
    SectionHeader(
        title = stringResource(R.string.playlist_songs_title),
        modifier = Modifier.padding(top = 8.dp),
        action = when {
            // A playlist plays from the button above; an album opens as itself there.
            state.canPlayAll && !state.isSongList -> {
                {
                    TextButton(onClick = { actions.onPlayAll(0) }, enabled = !busy) {
                        AppIcon(R.drawable.ic_play, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.play_all_button), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            // Its service only shows the first ones without signing in.
            result.trackCount != null -> {
                {
                    Text(
                        stringResource(R.string.songs_shown, tracks.size, result.trackCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> null
        }
    )
    val covers = result.type == ItemType.PLAYLIST
    // Past what YouTube takes in one queue, the list comes in parts, each with a header that plays it.
    val parts = state.canPlayAll && tracks.size > MainViewModel.MAX_QUEUE
    Group {
        tracks.forEachIndexed { index, track ->
            if (index > 0) GroupDivider()
            if (parts && index % MainViewModel.MAX_QUEUE == 0) {
                PartHeader(
                    index,
                    minOf(index + MainViewModel.MAX_QUEUE, tracks.size),
                    enabled = !busy,
                    modifier = Modifier.onGloballyPositioned { onPartTop(index, it.positionInWindow().y) }
                ) { actions.onPlayAll(index) }
                // The first part's shows by the button above, which plays it too.
                QueueProgress(
                    state.queueProgress?.takeIf { index > 0 && state.queueFrom == index },
                    Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                )
                GroupDivider()
            }
            val openLabel = stringResource(R.string.history_open, track.title)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = openLabel) { actions.onOpenTrack(track) }
                    .padding(start = if (covers) 12.dp else 20.dp, end = 12.dp, top = if (covers) 8.dp else 10.dp, bottom = if (covers) 8.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (covers) {
                    CoverArt(track.artworkUrl, actions.loadArtwork, size = 44.dp)
                } else {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 28.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = if (covers) 12.dp else 8.dp)) {
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

/** "51 to 100 ▶": where a part of a list longer than one queue starts. Tapping it plays that part. */
@Composable
private fun PartHeader(from: Int, to: Int, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClickLabel = stringResource(R.string.play_part, from + 1, to), onClick = onClick)
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.part_range, from + 1, to),
            style = MaterialTheme.typography.titleSmall,
            color = color,
            modifier = Modifier.weight(1f)
        )
        Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
    }
}

/** How many Recent items fit at a glance; past that, a search shows above them. */

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
    CrosstuneTheme {
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

/**
 * A song from Recent or a playlist that the app it goes to doesn't have: where it is, by its own app,
 * leads, and a search there is still offered, e.g. for another name.
 */
@Composable
private fun NotFoundDialog(offer: NotFoundOffer, state: UiState, actions: ScreenActions) {
    val app = offer.destination.label()
    AlertDialog(
        onDismissRequest = actions.onDismissNotFoundOffer,
        icon = { DestinationIcon(offer.destination, state.installed) },
        title = { Text(offer.song.title) },
        text = { Text(stringResource(R.string.not_found_on, app)) },
        confirmButton = {
            offer.original?.service?.let { service ->
                TextButton(onClick = { actions.onTakeNotFoundOffer(true) }) { Text(stringResource(service.openLabelRes)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { actions.onTakeNotFoundOffer(false) }) { Text(stringResource(R.string.search_anyway, app)) }
        }
    )
}
