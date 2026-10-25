package com.astrovm.crosstune

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.annotation.DrawableRes
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
internal const val PRACTICE_BADGE_TAG = "practice-badge"
internal const val RESULT_TEXT_TAG = "result-text"
internal const val DEFAULT_MENU_TAG = "default_destination"
internal const val NOW_PLAYING_TAG = "now_playing"

/** Past this font scale, Play and Open stack: a long word like German's "Abspielen" no longer fits half the width. */
private const val STACK_BUTTONS_FROM = 1.3f

internal data class ScreenActions(
    val onUrlChange: (String) -> Unit = {},
    val onResolve: () -> Unit = {},
    val onPaste: () -> Unit = {},
    /** What names a song playing nearby: Crosstune itself first, then Shazam and Google. */
    val recognizers: List<SongRecognizer> = emptyList(),
    /** The one the Recognize button opens: Crosstune, listening itself. */
    val recognizer: SongRecognizer? = null,
    val onRecognize: (SongRecognizer) -> Unit = {},
    /** Names what's playing and opens its words straight away, as the lyrics tile does. */
    val onLyricsNow: () -> Unit = {},
    val onStopListening: () -> Unit = {},
    /** Android's settings for Crosstune, where the microphone is allowed. */
    val onOpenMicrophoneSettings: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpen: () -> Unit = {},
    val onPlayResult: () -> Unit = {},
    val onClosePlayer: () -> Unit = {},
    val onPlayerOpenApp: () -> Unit = {},
    val onPlayerOpenWith: () -> Unit = {},
    val onPlayerPlayPause: () -> Unit = {},
    val onPlayerSkip: (next: Boolean) -> Unit = {},
    val onPlayerSeek: (Long) -> Unit = {},
    val onPlayerLyrics: () -> Unit = {},
    val onOpenWith: (Destination) -> Unit = {},
    val onOpenOriginal: () -> Unit = {},
    val onDismissPicker: () -> Unit = {},
    val onTargetChange: (Destination) -> Unit = {},
    val onResultTargetChange: (Destination) -> Unit = {},
    val onMakeDefault: () -> Unit = {},
    val onInterceptChange: (MusicService, Boolean) -> Unit = { _, _ -> },
    val onFrontendRuleChange: (Frontend, Destination?) -> Unit = { _, _ -> },
    val onRuleChange: (MusicService, Destination?) -> Unit = { _, _ -> },
    val onAddCustom: (String, String) -> Boolean = { _, _ -> false },
    val onRemoveCustom: (Destination.Custom) -> Unit = {},
    val onFrontendInstanceChange: (Frontend, String) -> Boolean = { _, _ -> false },
    /** Fixes all there is at once, through Shizuku or root. */
    val onSetUpInOneTap: () -> Unit = {},
    /** Allows Crosstune's links: in one tap with Shizuku or root, otherwise the steps show how. */
    val onAllowLinks: () -> Unit = {},
    /** The list of things to fix, from its bar. */
    val onOpenFixes: () -> Unit = {},
    /** The steps of these, one at a time. */
    val onFixSteps: (List<Need>) -> Unit = {},
    /** On to the next thing to fix, done or skipped. */
    val onNextFix: () -> Unit = {},
    /** Out of the steps, back to where they were opened. */
    val onFixBack: () -> Unit = {},
    val onCloseFixes: () -> Unit = {},
    /** Opens Android's page where this is done, with the guide floating over it, or asks for the microphone. */
    val onOpenFix: (Need) -> Unit = {},
    /** Before Android 12, a link that shows whether links reach Crosstune. */
    val onTryLink: (Need) -> Unit = {},
    val onOpenShizuku: () -> Unit = {},
    /** Where tapped and shared links go: an app, or null for Crosstune. */
    val onShowInCrosstuneFirstChange: (Boolean) -> Unit = {},
    val onPlayRightAwayChange: (Boolean) -> Unit = {},
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
    val onOpenAppLinkSettings: (LinkApp) -> Unit = {},
    /** Closes the notice about an update, by its version. */
    val onDismissNotices: (Set<String>) -> Unit = {},
    val onUpdate: () -> Unit = {},
    /** Opens the player on the song a music app plays now. */
    val onOpenPlayingNow: () -> Unit = {},
    val onPeekPlayingNow: () -> Unit = {},
    val onPlayingNowPlayPause: () -> Unit = {},
    val onPlayingNowSkip: () -> Unit = {},
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
    /** Plays or pauses the song in the music app the words follow. */
    val onPlayPause: () -> Unit = {},
    /** Has the music app go on to its next song (true), or back to the one before. */
    val onSkipSong: (next: Boolean) -> Unit = {},
    /** The line of the timed words being sung right now, as the user taps it, to put the words in time. */
    val onSyncLine: (Int) -> Unit = {},
    /** All the words moved by this many milliseconds in the song, later when it's positive. */
    val onNudgeSync: (Long) -> Unit = {},
    /** The words back in time as they were found. */
    val onResetSync: () -> Unit = {},
    /** A report on the words sent, from this version of the app, with what the user says is wrong. */
    val onSendReport: (comment: String) -> Unit = {},
    /** The report written, sent or not. */
    val onCloseReport: () -> Unit = {},
    /** Problems Crosstune finds with songs reported by itself from now on, or not. */
    val onAllowAutoReports: (Boolean) -> Unit = {},
    val onReadingsChange: (Boolean) -> Unit = {},
    val onRomanizedChange: (Boolean) -> Unit = {},
    val onRomajiTypedChange: (Boolean) -> Unit = {},
    val onCardTypesChange: (List<CardType>, Boolean) -> Unit = { _, _ -> },
    val onTranslationChange: (Boolean) -> Unit = {},
    val onRetryTranslation: () -> Unit = {},
    val onChordsChange: (Boolean) -> Unit = {},
    val onToggleSavedLine: (Int) -> Unit = {},
    /** Looks up a word, in the line it's in, by the line's index. */
    val onLookUpWord: (Word, Int) -> Unit = { _, _ -> },
    val onExplainLine: (Int) -> Unit = {},
    val onStartPractice: () -> Unit = {},
    /** Practises only the lines of the song whose words show. */
    val onPractiseSong: () -> Unit = {},
    /** Practises only the lines of the song given, from the list of songs to practise. */
    val onPractiseSongOf: (MusicMetadata) -> Unit = {},
    /** The card's line read as given, one reading for each of its kanji, put right by hand. */
    val onReadCardAs: (List<String>) -> Unit = {},
    val onTurnPractice: () -> Unit = {},
    val onGradePractice: (Grade) -> Unit = {},
    val onStopPractice: () -> Unit = {},
    val onReplayLine: () -> Unit = {},
    val onBackToBefore: () -> Unit = {},
    /** Keeps the lines of the words shown given by index, lets the kept ones given go, and practises the song when asked. */
    val onChooseLines: (List<Int>, List<SavedLine>, Boolean) -> Unit = { _, _, _ -> },
    /** Lines let go kept again, as they were, as when a song's removal is undone. */
    val onKeepAgain: (List<SavedLine>) -> Unit = {},
    /** Asks ChatGPT a question about a line, by its index. */
    val onAskAboutLine: (Int, String) -> Unit = { _, _ -> },
    /** Has ChatGPT explain what the whole song is about. */
    val onExplainSong: () -> Unit = {},
    /** Asks ChatGPT a question about the whole song. */
    val onAskAboutSong: (String) -> Unit = {},
    /** The words as written, or only in kana or Latin letters. */
    val onWordsAsChange: (WordsAs) -> Unit = {},
    val onFindWordsToKnow: () -> Unit = {},
    /** Saves one of the song's words to know, or lets it go. */
    val onToggleSavedWord: (KeyWord) -> Unit = {},
    val onAboutArtist: () -> Unit = {},
    /** Lists songs like this one, as ChatGPT finds them. */
    val onSongsLike: (MusicMetadata) -> Unit = {},
    /** Has ChatGPT write sentences using a line's words, by the line's index. */
    val onFindExamples: (Int) -> Unit = {},
    /** Opens a song ChatGPT named, by its title and artist. */
    val onOpenNamedSong: (String, String) -> Unit = { _, _ -> },
    /** Lists the songs ChatGPT found for a description as a playlist. */
    val onPlaylistFromSearch: () -> Unit = {},
    val onDismissWord: () -> Unit = {},
    val onRepeatLine: (Int) -> Unit = {},
    val onStopRepeating: () -> Unit = {},
    /** ChatGPT translates, signed in, or else Google Translate. */
    val onChatGptTranslationChange: (Boolean) -> Unit = {},
    /** Asks ChatGPT this model, by its slug, from now on. */
    val onChatGptModelChange: (String) -> Unit = {},
    /** One of the app's languages to translate into, or null for the app's own. */
    val onTranslateIntoChange: (String?) -> Unit = {},
    /** The languages, by code, whose songs aren't translated. */
    val onUntranslatedChange: (Set<String>) -> Unit = {},
    /** How big readings are beside the words; kept when [save]d, once let go. */
    val onReadingScaleChange: (scale: Float, save: Boolean) -> Unit = { _, _ -> },
    val onWordsScaleChange: (scale: Float, save: Boolean) -> Unit = { _, _ -> },
    /** Centers the words on the lyrics screen, or sets them at the start of the line. */
    val onWordsCenteredChange: (Boolean) -> Unit = {},
    val onTranslationScaleChange: (scale: Float, save: Boolean) -> Unit = { _, _ -> },
    val onSignInWithChatGpt: () -> Unit = {},
    val onCancelChatGptSignIn: () -> Unit = {},
    val onSignOutOfChatGpt: () -> Unit = {},
    val onDismissChatGptWelcome: () -> Unit = {},
    /** Lets the words follow music apps: in one tap with Shizuku or root, otherwise the steps show how. */
    val onAllowFollowing: () -> Unit = {},
    /** Opens Android's page for Crosstune seeing what music apps play, where it's turned off too. */
    val onOpenFollowAccess: () -> Unit = {},
    /** Opens Crosstune's App info, where restricted settings are allowed. */
    val onOpenAppInfo: () -> Unit = {},
    val onThemeChange: (ThemeMode) -> Unit = {},
    /** Searches where the result goes, though it wasn't found there. */
    val onSearchAnyway: () -> Unit = {},
    val onNotFoundActionChange: (NotFoundAction) -> Unit = {},
    /** Takes the not-found offer's way: where the song is, or a search where it was going. */
    val onTakeNotFoundOffer: (original: Boolean) -> Unit = {},
    val onDismissNotFoundOffer: () -> Unit = {},
    val onPaletteChange: (Palette) -> Unit = {},
    val onPureBlackChange: (Boolean) -> Unit = {},
    val onPlayingBarChange: (Boolean) -> Unit = {},
    /** Floats the words over other apps now, asking Android first if need be. */
    val onFloat: () -> Unit = {},
    /** Visuals on or off behind the words; on asks for the microphone, which Android needs to share what plays. */
    val onVisualsChange: (Boolean) -> Unit = {},
    val onNextVisual: () -> Unit = {},
    /** Never shows the visual showing again. */
    val onDropVisual: () -> Unit = {},
    /** A visual on or off, by name, as Settings lists them. */
    val onVisualOnChange: (name: String, on: Boolean) -> Unit = { _, _ -> },
    val onSkipHeavyVisualsChange: (Boolean) -> Unit = {},
    /** How long each visual shows, or [VisualsSettings.NEVER]. */
    val onVisualsChangeEveryChange: (Long) -> Unit = {},
    /** How dark the ground over the visuals is; kept once [save]. */
    val onVisualsShadeChange: (shade: Float, save: Boolean) -> Unit = { _, _ -> },
    /** Opens the chosen song from the list of songs a typed name turned up. */
    val onPickSong: (MusicMetadata) -> Unit = {},
    /** Closes the list of songs. */
    val onDismissSongSearch: () -> Unit = {},
    val loadArtwork: suspend (String) -> ImageBitmap? = { null },
    val covers: Covers? = null
)

/** Switches between the main screen and settings; system back returns from settings. */
@Composable
@OptIn(ExperimentalTransitionApi::class)
internal fun CrosstuneScreen(state: UiState, actions: ScreenActions) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }
    var showPracticeList by rememberSaveable { mutableStateOf(false) }
    // A link from another app is about to open or show its result, so don't leave the user in settings.
    // The request is consumed once handled, so a rotation keeps settings open, while a restored process
    // that re-resolves a pending link still asks for it.
    LaunchedEffect(state.leaveSettings) {
        if (state.leaveSettings) {
            showSettings = false
            showPracticeList = false
            actions.onSettingsLeft()
        }
    }
    var openSource by rememberSaveable { mutableStateOf<String?>(null) }
    // A link from another app on its way out shows just that, the fixes waiting till after.
    val fixes = state.fixes?.takeIf { !state.handingOff }
    val screen = when {
        // Listening, e.g. from a tile or a widget, it's in sight with a way to stop, whatever comes
        // after; so are the words it was for.
        state.listening -> Screen.LISTENING
        // Opened from anywhere, the steps show over it.
        fixes?.need != null -> Screen.FIX_STEP
        fixes?.list == true -> Screen.FIXES
        showSettings -> Screen.SETTINGS
        state.handingOff -> Screen.HANDOFF
        // Over the words, practice shows in their place, so they're as they were after.
        state.practice != null && state.lyricsFor == null -> Screen.PRACTICE
        state.lyricsFor != null -> Screen.LYRICS
        // Under the words it opens, as the song they're of.
        state.player != null -> Screen.PLAYER
        showPracticeList -> Screen.PRACTICE_LIST
        else -> Screen.MAIN
    }
    val layers = remember { LayerSwipe() }
    layers.up = when {
        // The song's card is only made under its player once the swipe gets there, so one let go early leaves the card shown.
        screen == Screen.MAIN && state.playingNow != null -> Layer(Screen.PLAYER, actions.onPeekPlayingNow, back = actions.onClosePlayer, settled = actions.onOpenPlayingNow)
        screen == Screen.PLAYER && state.player?.song?.artist?.isNotBlank() == true -> Layer(Screen.LYRICS, actions.onPlayerLyrics, back = actions.onDismissLyrics)
        else -> null
    }
    layers.down = when (screen) {
        Screen.LYRICS -> Layer(if (state.player != null) Screen.PLAYER else Screen.MAIN, actions.onDismissLyrics)
        Screen.PLAYER -> Layer(Screen.MAIN, actions.onClosePlayer)
        else -> null
    }
    val density = LocalDensity.current
    layers.distance = LocalWindowInfo.current.containerSize.height.toFloat().coerceAtLeast(1f)
    layers.flick = with(density) { 500.dp.toPx() }
    val moving = remember { SeekableTransitionState(screen) }
    // A change that isn't a swipe plays through; the finger holds the screens while it's on them.
    LaunchedEffect(screen, layers.to) {
        if (layers.to != null) return@LaunchedEffect
        moving.animateTo(screen, Motion.rise.takeIf { screen in RISING || moving.currentState in RISING })
    }
    LaunchedEffect(layers) {
        snapshotFlow { Triple(layers.to, layers.fraction, layers.carryOn) }.collectLatest { (to, fraction, carryOn) ->
            if (to == null) return@collectLatest
            if (carryOn == null) {
                moving.seekTo(fraction, to)
            } else {
                moving.animateTo(if (carryOn) to else moving.currentState, Motion.rise)
                layers.finish()
            }
        }
    }
    // A screen the steps to fix something show over is found as it was once they close; any other
    // way back, a screen starts afresh, as it always has.
    val kept = rememberSaveableStateHolder()
    LaunchedEffect(screen) { if (screen != Screen.FIX_STEP) Screen.entries.filter { it != screen }.forEach { kept.removeState(it.name) } }
    CompositionLocalProvider(LocalLayerSwipe provides layers, LocalCovers provides actions.covers) {
    rememberTransition(moving, label = "screen").AnimatedContent(
        transitionSpec = {
            // Setup and a link on its way out aren't places in the app, so they fade rather than slide.
            // The words rise over the song they're of, and sink back into it.
            if (initialState in RISING || targetState in RISING) rise(up = targetState.depth > initialState.depth)
            else if (initialState.depth < 0 || targetState.depth < 0) swap() else slide(forward = targetState.depth > initialState.depth)
        }
    ) { shown ->
        kept.SaveableStateProvider(shown.name) {
            // if/else rather than an exhaustive when, which compiles an unreachable branch into composables.
            if (shown == Screen.FIX_STEP) {
                FixStepPage(state, actions)
            } else if (shown == Screen.FIXES) {
                FixesPage(state, actions)
            } else if (shown == Screen.SETTINGS) {
                SettingsScreen(state, actions, onBack = { showSettings = false }, openSource = openSource, onOpenSource = { openSource = it })
            } else if (shown == Screen.HANDOFF) {
                Handoff(state, actions)
            } else if (shown == Screen.LISTENING) {
                ListeningScreen(actions)
            } else if (shown == Screen.LYRICS) {
                LyricsScreen(state, actions)
            } else if (shown == Screen.PLAYER) {
                PlayerScreen(state, actions)
            } else if (shown == Screen.PRACTICE) {
                PracticeScreen(state, actions)
            } else if (shown == Screen.PRACTICE_LIST) {
                PracticeList(state, actions, onBack = { showPracticeList = false })
            } else {
                MainScreen(state, actions, onOpenSettings = { showSettings = true }, onOpenPractice = { showPracticeList = true })
            }
        }
    }
    }
    state.notFoundOffer?.let { NotFoundDialog(it, state, actions) }
}

/** What fills the window, each with how deep it is, so moving deeper and coming back slide opposite ways. */
internal enum class Screen(val depth: Int) {
    HANDOFF(-1), LISTENING(-1), MAIN(0), PLAYER(1), SETTINGS(1), PRACTICE_LIST(1), FIXES(1), LYRICS(2), PRACTICE(2), FIX_STEP(3)
}

/** Screens that rise over the one under them, and sink back into it. */
private val RISING = setOf(Screen.LYRICS, Screen.PLAYER)

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
private fun MainScreen(state: UiState, actions: ScreenActions, onOpenSettings: () -> Unit, onOpenPractice: () -> Unit) {
    // The picker lists installed apps first, so it waits for Android to say which those are rather
    // than move a row from under the user's finger.
    if (state.showDestinationPicker && state.systemStateKnown) {
        DestinationPicker(state, actions.loadArtwork, onPick = actions.onOpenWith, onDismiss = actions.onDismissPicker)
    }
    // Only while a name is being looked up: a song picked or dismissed leaves nothing to show.
    if (state.isSearchingSongs || state.songSearch.isNotEmpty() || state.songSearchQuery.isNotBlank()) {
        SongSearchSheet(state, actions.loadArtwork, actions.onPickSong, actions.onDismissSongSearch, actions.onPlaylistFromSearch)
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

    // At the bottom, unless it's the song shown or it's turned off; not while a link is typed in.
    val playing = state.playingNow?.takeIf { now ->
        state.playingBar && state.error == null && !state.isLoading && !state.listening &&
            (state.linkText.isBlank() || (state.result != null && state.linkText == state.link?.url))
    }
    Page(
        title = stringResource(R.string.app_name),
        titleLeading = { AppLogo() },
        actions = {
            // The words of what's playing, which open over everything rather than look a link up, so they're not in the field.
            IconButton(onClick = actions.onLyricsNow, enabled = !state.isLoading && !state.isMatching) {
                AppIcon(R.drawable.ic_lyrics, contentDescription = stringResource(R.string.tile_lyrics_label))
            }
            // Saved lines, a tap from what there is to practise, with how many are due.
            if (state.savedLines.any { it.practisable }) {
                PracticeButton(state.savedLines.cardsDueCount(System.currentTimeMillis(), state.cardTypes), onOpenPractice)
            }
            IconButton(onClick = onOpenSettings) {
                AppIcon(R.drawable.ic_settings, contentDescription = stringResource(R.string.settings_button))
            }
        },
        bottomBar = {
            Column {
                // What's broken stays in sight until it's fixed, over what's playing, once it isn't asked below the field.
                val toFix = state.toFix.takeIf { !state.firstAsks } ?: 0
                AnimatedVisibility(visible = toFix > 0, enter = Motion.appear, exit = Motion.disappear) {
                    FixBar(toFix, actions.onOpenFixes, modifier = if (playing == null) Modifier.navigationBarsPadding() else Modifier)
                }
                AnimatedVisibility(visible = playing != null, enter = Motion.appear, exit = Motion.disappear) {
                    // Kept while it fades out, as the song goes when it's tapped.
                    val shown = remember { mutableStateOf(playing) }
                    if (playing != null) shown.value = playing
                    shown.value?.let { NowPlayingBar(it, actions) }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { songList?.let { PlayButton(it, state, actions, part = partInView, visible = !mainButtonShown) } }
    ) {
        // The empty hint below says what the app does, so there's no tagline above it.
        Spacer(Modifier.height(8.dp))
        UpdateNotice(state, actions)
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

        // Before anything's been opened, what Android has to allow is asked here, saying what each gets you.
        AnimatedVisibility(visible = state.firstAsks, enter = fadeIn(Motion.fadeIn), exit = fadeOut(Motion.fadeOut)) {
            FirstAsks(state, actions)
        }
        AnimatedVisibility(visible = state.idle && state.history.isEmpty() && !state.firstAsks, enter = fadeIn(Motion.fadeIn), exit = fadeOut(Motion.fadeOut)) {
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

/** The song a music app plays right now, or has paused: its buttons, where it is, and a tap opens its player. */
@Composable
private fun NowPlayingBar(now: NowPlaying, actions: ScreenActions) {
    val song = now.song
    val following = now.following
    val playing = following?.clock?.playing != false
    val duration = following?.durationMs ?: song.lengthMs
    val position = rememberPosition(following)
    Surface(
        onClick = actions.onOpenPlayingNow,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // Swiped up, it opens the player.
        modifier = Modifier.fillMaxWidth().testTag(NOW_PLAYING_TAG).swipeLayers()
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp).widthIn(max = ContentMaxWidth),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverArt(song.artworkUrl, actions.loadArtwork, size = 48.dp)
                Column(modifier = Modifier.padding(start = 12.dp, end = 8.dp).weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(song.artist.takeIf { it.isNotBlank() }, following?.app).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = actions.onPlayingNowPlayPause, modifier = Modifier.size(52.dp)) {
                    AppIcon(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                        contentDescription = stringResource(if (playing) R.string.lyrics_pause else R.string.lyrics_play)
                    )
                }
                IconButton(onClick = actions.onPlayingNowSkip, enabled = following?.canSkipNext != false, modifier = Modifier.size(52.dp)) {
                    AppIcon(R.drawable.ic_skip_next, contentDescription = stringResource(R.string.lyrics_next_song))
                }
            }
            if (position != null && duration != null) {
                LinearProgressIndicator(progress = { (position.toFloat() / duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
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

/** A newer Crosstune to update to: news rather than a problem, so it can be closed, until the next one. */
@Composable
private fun UpdateNotice(state: UiState, actions: ScreenActions) {
    val update = state.update?.takeIf { "update:${it.version}" !in state.dismissedNotices } ?: return
    val key = setOf("update:${update.version}")
    NoticeStrip(stringResource(R.string.notice_update, update.version), stringResource(R.string.update_button), actions.onUpdate) { actions.onDismissNotices(key) }
}

@Composable
private fun NoticeStrip(text: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
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
            IconButton(onClick = onDismiss) {
                AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.dismiss_button))
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
    // A TV puts the remote on the field as it opens, and its keyboard would cover half the screen,
    // so there it's typed in once pressed.
    val tv = state.phone == Phone.TV
    var typing by remember { mutableStateOf(!tv) }
    LaunchedEffect(typing) { if (tv && typing) keyboard?.show() }
    val field = remember { MutableInteractionSource() }
    // Its buttons are inside it, where looking right of it for the next thing never finds them, so the remote is sent there and back.
    val fieldFocus = remember { FocusRequester() }
    val buttons = remember { FocusRequester() }
    // Keys reach it on their way to its buttons too, which it leaves to them.
    val onField = field.collectIsFocusedAsState().value
    // With no cursor in it, a ring says the remote is on it.
    val ringed = tv && !typing && onField
    TextField(
        value = state.linkText,
        onValueChange = actions.onUrlChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.spotify_link_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { AppIcon(R.drawable.ic_link, contentDescription = null) },
        trailingIcon = {
            // Paste turns into clear as soon as there's text or a song open, and back.
            val bare = state.linkText.isEmpty() && state.result == null
            AnimatedContent(targetState = bare, transitionSpec = { swap() }, label = "trailing") { empty ->
                if (empty) {
                    Row(Modifier.focusRequester(buttons).focusGroup()) {
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
                    IconButton(onClick = actions.onClear, enabled = !busy, modifier = Modifier.focusRequester(buttons)) {
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
        readOnly = !typing,
        interactionSource = field,
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
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fieldFocus)
            .then(if (ringed) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large) else Modifier)
            .onFocusChanged { if (tv && !it.hasFocus) typing = false }
            .onPreviewKeyEvent { event ->
                when {
                    !tv -> false
                    // Up and down leave it, typing or not, as a single line has nowhere to go that way.
                    event.key == Key.DirectionDown || event.key == Key.DirectionUp -> {
                        if (event.type == KeyEventType.KeyDown) focus.moveFocus(if (event.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up)
                        true
                    }
                    // On its buttons too it would take left and right as moving a cursor, so the remote goes from one to the next here,
                    // and from the first back to it.
                    !onField && event.key == Key.DirectionLeft -> {
                        if (event.type == KeyEventType.KeyDown && !focus.moveFocus(FocusDirection.Left)) fieldFocus.requestFocus()
                        true
                    }
                    !onField && event.key == Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) focus.moveFocus(FocusDirection.Right)
                        true
                    }
                    !onField -> false
                    // Not typing, right goes to its buttons, rather than move a cursor that isn't there.
                    !typing && event.key == Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) buttons.requestFocus()
                        true
                    }
                    !typing && event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter) -> {
                        if (event.type == KeyEventType.KeyUp) typing = true
                        true
                    }
                    else -> false
                }
            }
    )
    val convertModifier = Modifier
        .fillMaxWidth()
        .padding(top = 12.dp)
        .heightIn(min = 56.dp)
    val canConvert = !busy && state.linkText.isNotBlank()
    // A name, not a link, is searched for.
    val name = MusicLinks.parse(state.linkText) == null && !MusicLinks.looksLikeALink(state.linkText)
    val label = if (name) R.string.search_button else R.string.resolve_button
    val convertLabel = @Composable { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) }
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

/** "Music app   Spotify ▾": the app songs open and play in, in settings. */
@Composable
internal fun DefaultDestinationMenu(
    destinations: List<Destination>,
    selected: Destination,
    onSelect: (Destination) -> Unit,
    label: String,
    modifier: Modifier,
    installed: Set<MusicService>
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag(DEFAULT_MENU_TAG)) {
                DestinationIcon(selected, installed, size = 20.dp)
                Spacer(Modifier.size(8.dp))
                Text(selected.label())
                AppIcon(
                    R.drawable.ic_expand_more,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp)
                        .flipWhen(expanded)
                )
            }
            var all by remember(expanded) { mutableStateOf(false) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                val short = destinations.shortList(installed, selected).takeUnless { all }
                (short ?: destinations.installedFirst(installed)).forEach { destination ->
                    MenuChoice(destination.label(), destination == selected, icon = { DestinationIcon(destination, installed) }) {
                        expanded = false
                        onSelect(destination)
                    }
                }
                if (short != null) MoreChoice { all = true }
            }
        }
    }
}

/**
 * The songs a typed name turned up, to pick from, since a song can be converted without its link.
 * The name searched for is on the sheet, so a list of songs says what it is a list of.
 */
@Composable
private fun SongSearchSheet(
    state: UiState,
    loadArtwork: suspend (String) -> ImageBitmap?,
    onPick: (MusicMetadata) -> Unit,
    onDismiss: () -> Unit,
    onPlaylist: () -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.song_search_title)) },
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
                    // ChatGPT's picks come in a moment later, ahead of the others.
                    if (state.isAskingForSongs) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    // A mood, more than one song, makes a playlist of ChatGPT's picks.
                    if (state.songsFromChatGpt >= MainViewModel.MIN_PLAYLIST) {
                        FilledTonalButton(onClick = onPlaylist, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            AppIcon(R.drawable.ic_queue_music, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.song_search_playlist), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    state.songSearch.forEachIndexed { index, song ->
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
                            if (index < state.songsFromChatGpt) {
                                AppIcon(R.drawable.ic_translator_chatgpt, contentDescription = stringResource(R.string.song_search_chatgpt), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (state.songSearch.isEmpty() && !state.isAskingForSongs) {
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
            .padding(top = 16.dp)
            // Links from other apps fail with nothing else changing on screen, so announce it.
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            // One line: what went wrong, and trying again as an icon at its end.
            Row(modifier = Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_error, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(error.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)
                )
                if (state.canRetry) {
                    IconButton(onClick = actions.onRetry) {
                        AppIcon(R.drawable.ic_refresh, contentDescription = stringResource(R.string.retry_button))
                    }
                }
            }
            // The theme's accent color is hard to read on the error color.
            val onError = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
            // Only what else can be done, under it.
            val original = state.link?.service
            val others = if (error == AppError.RECOGNITION_UNAVAILABLE) actions.recognizers.filterNot { it.listensHere } else emptyList()
            if (error == AppError.MICROPHONE || others.isNotEmpty() || original != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 20.dp)) {
                    if (error == AppError.MICROPHONE) {
                        TextButton(onClick = actions.onOpenMicrophoneSettings, colors = onError) {
                            Text(stringResource(R.string.open_settings_button))
                        }
                    }
                    // Shazam is busy, but another app may still name the song.
                    others.forEach { recognizer ->
                        TextButton(onClick = { actions.onRecognize(recognizer) }, colors = onError) {
                            Text(stringResource(R.string.open_app_button, recognizer.label))
                        }
                    }
                    // A link Crosstune couldn't read can still be opened in the app it belongs to.
                    original?.let { service ->
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
                    text = stringResource(if (state.songsLike) R.string.songs_like_label else result.type.labelRes),
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
        val playDescription = stringResource(R.string.play_in_background, destination.label())
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
        // Where it opens, picked for just this result.
        DestinationChip(state, actions)
        // A song its app has, or may, can be asked to play, staying here; while it's asked, once is enough.
        val canPlay = state.canPlayResult
        // Side by side, until the words are too big for half the width; then one above the other.
        val stacked = canPlay && LocalDensity.current.fontScale > STACK_BUTTONS_FROM
        val press = rememberPress()
        val mainModifier = Modifier
            .heightIn(min = 56.dp)
            .then(press.modifier)
            // Once it scrolls away, a floating copy of it takes over.
            .onGloballyPositioned { onMainButtonShown(it.boundsInWindow().height > 0f) }
        // The icon and word say what it does, the chip above which app; screen readers hear the app's name too.
        val label = @Composable {
            AnimatedContent(targetState = main, transitionSpec = { swap() }, contentKey = { Triple(it.icon, it.label, it.description) }, label = "open") { shown ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (shown.description != shown.label) Modifier.clearAndSetSemantics { contentDescription = shown.description } else Modifier
                ) {
                    shown.icon?.let { DestinationIcon(it, state.installed, size = 24.dp) }
                        ?: AppIcon(shown.drawable, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(shown.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        }
        val playButton = @Composable { modifier: Modifier ->
            Button(
                onClick = actions.onPlayResult,
                enabled = !state.playingResult && !state.isMatching,
                interactionSource = press.source,
                modifier = modifier.heightIn(min = 56.dp).then(press.modifier)
            ) {
                AppIcon(R.drawable.ic_play, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(
                    stringResource(R.string.lyrics_play),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = playDescription }
                )
            }
        }
        val openButton = @Composable { modifier: Modifier ->
            if (canPlay) {
                FilledTonalButton(onClick = main.onClick, enabled = main.enabled, interactionSource = press.source, modifier = modifier.then(mainModifier)) { label() }
            } else {
                Button(onClick = main.onClick, enabled = main.enabled, interactionSource = press.source, modifier = modifier.then(mainModifier)) { label() }
            }
        }
        if (stacked) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                playButton(Modifier.fillMaxWidth())
                openButton(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (canPlay) playButton(Modifier.weight(1f).fillMaxHeight())
                openButton(Modifier.weight(1f).fillMaxHeight())
            }
        }
        // A later part shows its own, down by its button.
        QueueProgress(state.queueProgress?.takeIf { state.queueFrom == 0 })
        if (destination != state.defaultDestination) {
            TextButton(onClick = actions.onMakeDefault, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.make_default_named, destination.label()))
            }
        }
        // Flat: the song is the point, these are only within reach.
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            // With its songs listed, the songs are what's worth copying or sharing, e.g. to move
            // the list to another service, rather than a search for its name. The songs listed
            // right below say what's copied, so the label is short; screen readers hear it all.
            val songs = result.tracks.isNotEmpty()
            // Only a song has words, and one whose artist is unknown can't be looked up safely.
            if (result.type == ItemType.TRACK && result.artist.isNotBlank()) {
                ToolAction(R.drawable.ic_lyrics, stringResource(R.string.lyrics_button), actions.onShowLyrics)
            }
            ToolAction(
                R.drawable.ic_content_copy,
                stringResource(R.string.copy_button),
                if (songs) actions.onCopySongs else actions.onCopyLink,
                enabled = songs || destinationReady,
                description = stringResource(if (songs) R.string.copy_songs_button else R.string.copy_link_button)
            )
            ToolAction(
                R.drawable.ic_share,
                stringResource(R.string.share_button),
                if (songs) actions.onShareSongs else actions.onShareSearch,
                enabled = songs || destinationReady,
                description = stringResource(if (songs) R.string.share_songs_button else R.string.share_link_button)
            )
        }
    }
}

/** What a result's main button says and does. [description] adds the app's name, which its icon shows. */
private class MainAction(
    val label: String,
    val description: String,
    val enabled: Boolean,
    val icon: Destination? = null,
    @DrawableRes val drawable: Int = R.drawable.ic_open_in_new,
    val onClick: () -> Unit
)

@Composable
private fun mainAction(result: MusicMetadata, state: UiState, actions: ScreenActions): MainAction {
    val destination = state.resultDestination
    val app = destination.label()
    val enabled = !state.isMatching && state.queueProgress == null
    // A playlist's songs play, since a search for its name elsewhere rarely finds it.
    if (state.isSongList) {
        if (!state.canPlayAll) {
            return MainAction(stringResource(R.string.play_first_button), stringResource(R.string.play_first_in, app), enabled, drawable = R.drawable.ic_play) {
                actions.onOpenTrack(result.tracks.first())
            }
        }
        // Past what one queue takes, it says which part plays.
        val max = MainViewModel.MAX_QUEUE
        return if (result.tracks.size > max) {
            MainAction(stringResource(R.string.play_part, 1, max), stringResource(R.string.play_part_in, 1, max, app), enabled, drawable = R.drawable.ic_play) { actions.onPlayAll(0) }
        } else {
            MainAction(stringResource(R.string.play_all_button), stringResource(R.string.play_all_in, app), enabled, drawable = R.drawable.ic_play) { actions.onPlayAll(0) }
        }
    }
    // Not there, the song plays where it is instead, unless searching anyway was picked.
    state.link?.service?.takeIf { state.resultMissing && state.notFoundAction != NotFoundAction.SEARCH }?.let { original ->
        return MainAction(stringResource(R.string.open_button), stringResource(original.openLabelRes), enabled, Destination.Service(original), onClick = actions.onOpenOriginal)
    }
    // The chip above says which app, so one word says what it does.
    return if (state.destinationUrls[destination]?.exact == false) {
        MainAction(stringResource(R.string.search_button), stringResource(R.string.search_in_destination, app), enabled, drawable = R.drawable.ic_search, onClick = actions.onOpen)
    } else {
        MainAction(stringResource(R.string.open_button), destination.openLabel(), enabled, onClick = actions.onOpen)
    }
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

/** The app a result opens in, as a chip under the song: it lists the apps to open it in instead. */
@Composable
private fun DestinationChip(state: UiState, actions: ScreenActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag(DEFAULT_MENU_TAG)) {
            DestinationIcon(state.resultDestination, state.installed, size = 20.dp)
            Spacer(Modifier.size(8.dp))
            Text(state.resultDestination.label(), style = MaterialTheme.typography.labelLarge)
            AppIcon(
                R.drawable.ic_expand_more,
                contentDescription = stringResource(R.string.choose_app_button),
                modifier = Modifier.padding(start = 4.dp).size(18.dp).flipWhen(expanded)
            )
        }
        var all by remember(expanded) { mutableStateOf(false) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val short = state.destinations.shortList(state.installed, state.resultDestination).takeUnless { all }
            (short ?: state.destinations.installedFirst(state.installed)).forEach { destination ->
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
            if (short != null) MoreChoice { all = true }
        }
    }
}

/** A flat tool under the song: an icon over a short label, with no tile around it. */
@Composable
private fun ToolAction(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    /** What screen readers hear instead of a short [label]. */
    description: String? = null
) {
    val content = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        contentColor = content
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .widthIn(min = 72.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier)
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f),
                modifier = Modifier.size(22.dp)
            )
            // Long translations wrap to a second line instead of being cut off.
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
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
                    // Most are songs, so only other kinds say what they are.
                    text = listOf(
                        if (entry.link.type == ItemType.TRACK) "" else stringResource(entry.link.type.labelRes),
                        entry.metadata.artist
                    )
                        .filter { it.isNotBlank() }
                        .joinToString(", "),
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
internal fun RemoveBackground(direction: SwipeToDismissBoxValue) {
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

/** Installed apps first: services whose app is installed, and frontend apps, which are only offered once installed. */
internal fun List<Destination>.installedFirst(installed: Set<MusicService>): List<Destination> =
    sortedBy { if (it.isInstalled(installed)) 0 else 1 }

private fun Destination.isInstalled(installed: Set<MusicService>) =
    (this as? Destination.Service)?.service in installed || (this is Destination.Alternative && packageName != null)

/**
 * What a menu of apps lists before More: the installed ones, the user's own sites, and [chosen];
 * null when that's all of them, or none of them is installed, which lists them all.
 */
internal fun List<Destination>.shortList(installed: Set<MusicService>, chosen: Destination?): List<Destination>? =
    installedFirst(installed).filter { it == chosen || it is Destination.Custom || it.isInstalled(installed) }
        .takeIf { short -> short.size < size && short.any { it.isInstalled(installed) } }

/** A menu's last item, which lists the apps left out. */
@Composable
private fun MoreChoice(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.more_button)) },
        leadingIcon = { Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { AppIcon(R.drawable.ic_expand_more, contentDescription = null) } },
        onClick = onClick
    )
}

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
                            Text(listOf(stringResource(result.type.labelRes), result.artist).filter { it.isNotBlank() }.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val hidden = state.pickerHides?.let(Destination::Service)
                val choices = state.destinations.filter { it != hidden }
                var all by rememberSaveable { mutableStateOf(false) }
                val short = choices.shortList(state.installed, null).takeUnless { all }
                (short ?: choices.installedFirst(state.installed)).forEach { destination ->
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
                if (short != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { all = true }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { AppIcon(R.drawable.ic_expand_more, contentDescription = null) }
                        Text(stringResource(R.string.more_button), style = MaterialTheme.typography.bodyLarge)
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

/** Practice, by its icon, with how many saved lines are due when any are. */
@Composable
private fun PracticeButton(due: Int, onClick: () -> Unit) {
    val label = stringResource(R.string.saved_practice)
    val description = if (due > 0) "$label, ${stringResource(R.string.practice_due, due)}" else label
    // Over the button, not in it: the button clips what's inside to its circle, cutting the count off.
    BadgedBox(
        badge = {
            // The count is in what the icon says, so it isn't said twice.
            if (due > 0) Badge(Modifier.offset(x = (-6).dp, y = 8.dp).testTag(PRACTICE_BADGE_TAG)) { Text(due.toString(), modifier = Modifier.clearAndSetSemantics { }) }
        }
    ) {
        IconButton(onClick = onClick) { AppIcon(R.drawable.ic_school, contentDescription = description) }
    }
}
