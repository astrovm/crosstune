package com.astrovm.crosstune

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.border
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.drawWithCache
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import android.view.accessibility.AccessibilityManager
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import android.os.SystemClock
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Switch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.app.Activity
import android.util.LruCache
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.animateContentSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator

internal const val LYRIC_LINE_TAG = "lyric-line"
internal const val LYRICS_SONG_TAG = "lyrics-song"
internal const val STUDY_LINE_TAG = "study-line"

/**
 * A song's words, filling the screen in the color of its cover. Timed words follow the song while
 * something says where it is, the line being sung lit and kept in view, and a line tapped moves the
 * music app there; otherwise they read as a page.
 */
@Composable
internal fun LyricsScreen(state: UiState, actions: ScreenActions) {
    val song = state.lyricsFor ?: return
    // Named by the app playing it, a song often comes without a cover, which is then found for the result.
    val cover = song.artworkUrl ?: state.result?.takeIf { SongNames.same(it.title, song.title) && SongNames.sameArtist(it.artist, song.artist) }?.artworkUrl
    // The line open to study, over the words.
    var studying by rememberSaveable(song) { mutableStateOf<Int?>(null) }
    // The sheet about the whole song, open at what it means, its words to know, or its artist.
    var songTab by rememberSaveable(song) { mutableStateOf<SongTab?>(null) }
    // A problem with the words being reported.
    var reporting by rememberSaveable(song) { mutableStateOf(false) }
    val signedIn = state.chatGpt.email != null
    val openTab = { tab: SongTab ->
        songTab = tab
        // ChatGPT finds it all, so without it there's nothing to ask yet.
        if (signedIn && tab == SongTab.MEANING) actions.onExplainSong()
        if (signedIn && tab == SongTab.WORDS) actions.onFindWordsToKnow()
        if (signedIn && tab == SongTab.ARTIST) actions.onAboutArtist()
    }
    // Signed in from the sheet, what it's open at is asked for then.
    LaunchedEffect(signedIn) { if (signedIn) songTab?.let(openTab) }
    if (state.practice != null) {
        PracticeScreen(state, actions)
        return
    }
    // The song's lines to choose which to learn, open over the words; true when it's practised after.
    var choosing by rememberSaveable(song) { mutableStateOf<Boolean?>(null) }
    // Saved lines take the place of the words, so only the words hold the screen, and only while a song plays.
    KeepScreenOn(state.following?.clock?.playing == true)
    SwipedBack(onBack = actions.onDismissLyrics)
    // Where the timed words are scrolled to, kept out here so they're right where they were when they
    // come back, e.g. once the visuals come or go, with nothing to glide back to.
    val timedList = rememberSaveable(song, saver = LazyListState.Saver) { LazyListState() }
    // Only the visuals, full screen, until a tap, Back or a swipe in from a side brings the words back.
    var visualsOnly by rememberSaveable { mutableStateOf(false) }
    // Putting the words in time: a line tapped is the one being sung. Only while something says where the song is.
    var syncingAsked by rememberSaveable(song) { mutableStateOf(false) }
    val syncing = syncingAsked && state.lyricLines.isNotEmpty() && state.following != null
    // Words timed for another take of the song are likely off, so putting them in time is offered once, as soon as there's a clock.
    var offered by rememberSaveable(song) { mutableStateOf(false) }
    LaunchedEffect(state.lyricsOffTake, state.following != null) {
        if (state.lyricsOffTake && state.following?.timingKnown == true && !offered) {
            offered = true
            syncingAsked = true
        }
    }
    val unsyncing = swipedAway(enabled = syncing) { syncingAsked = false }
    val onlyVisuals = visualsOnly && state.visuals
    // How far the words have come back, by Back, or by a swipe in from a side, which with Android's
    // bars hidden comes to the screen rather than as Back.
    val wordsBack = remember { Animatable(0f) }
    swipedAway(enabled = onlyVisuals, away = wordsBack) { visualsOnly = false }
    val tint by animateColorAsState(
        coverColor(cover, actions.loadArtwork) ?: MaterialTheme.colorScheme.primary,
        tween(durationMillis = 700),
        label = "lyrics tint"
    )
    // The song's info and the bar get out of the way of the words a few seconds after the screen opens, or after the last touch or remote key.
    var touches by remember { mutableIntStateOf(0) }
    var awake by remember { mutableStateOf(true) }
    // Counted from when they start to follow too, so playing doesn't hide them the moment it starts.
    LaunchedEffect(touches, state.following?.clock?.playing == true) {
        awake = true
        delay(TOOLS_SHOWN_MS)
        awake = false
    }
    var barMenu by remember(song) { mutableStateOf<BarMenu?>(null) }
    // On a TV the remote starts on the bar, rather than walk every line to reach it, and Right from a line goes back there.
    val tv = state.phone == Phone.TV
    val bar = remember { FocusRequester() }
    var focusBar by remember { mutableStateOf(tv) }
    var infoFocused by remember { mutableStateOf(false) }
    // What the song's info and its report open on a TV give the remote back to.
    val info = remember { FocusRequester() }
    val reportButton = remember { FocusRequester() }
    var barFocused by remember { mutableStateOf(false) }
    // They only go while timed words follow a song that's playing, where the words move on by themselves; paused, or read at
    // one's own pace, the tools stay. They also stay while there's something to do with them: a menu open, the words being put
    // in time, or a screen reader that can't tap to bring them back. The remote resting on them doesn't keep them, as on a TV
    // it starts there; each key it presses does.
    val following = state.following
    val gliding = state.lyricLines.isNotEmpty() && following != null && following.timingKnown && following.clock.playing
    val reader = rememberScreenReaderOn()
    val toolsShown = awake || !gliding || barMenu != null || syncing || reader
    // Going, they hand the remote to the words, where any key brings them back.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(toolsShown) {
        if (!toolsShown && barFocused) focusManager.moveFocus(FocusDirection.Up)
        if (!toolsShown && infoFocused) focusManager.moveFocus(FocusDirection.Down)
    }
    // A touch on the words, when the tools were away, only brings them back.
    val toolsAway by rememberUpdatedState(!toolsShown)
    // Again once the words arrive, which put their own tools first on it; not while the bar is away, where it would only pull the remote back.
    if (tv) LaunchedEffect(state.lyrics.isEmpty()) { if (toolsShown) focusBar = true }
    WithVisuals(state, onlyVisuals, wordsBack, barMenu != null, onShowWords = { visualsOnly = false }) { ground ->
    Surface(color = ground, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.changedToDown() }) touches++
                    }
                }
            }
            .onPreviewKeyEvent { if (tv && it.type == KeyEventType.KeyDown) touches++; false }
            .swipeLayers(downFromTop = true)
            // The cover's colour would only tint the visuals, so it shows without them.
            .then(
                if (state.visuals) Modifier
                else Modifier.background(Brush.verticalGradient(0f to tint.copy(alpha = 0.5f), 0.55f to tint.copy(alpha = 0.12f), 1f to ground))
            )
    ) {
        if (state.visuals) {
            // A little darker behind the phone's bar and the song, so they read over whatever the
            // visuals do; from the very top and edge to edge, so it has no edge of its own.
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(top + HEADER_SHADE)
                    .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = state.visualsShade), 1f to Color.Transparent))
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .align(Alignment.TopCenter)
                .widthIn(max = ContentMaxWidth)
        ) {
            // The song opens to what it's about, or, without ChatGPT, to the way to sign in for it.
            LyricsHeader(
                song.copy(artworkUrl = cover), actions, state.canSkipNext, state.canSkipPrevious, toolsShown,
                onAbout = { openTab(SongTab.MEANING) }, onPlayer = actions.onLyricsToPlayer, onAboutFocus = { infoFocused = it }, info = info
            )
            TranslationStatus(state.learning, actions)
            CapoStatus(state.chords)
            MicrophoneStatus(state.error == AppError.MICROPHONE, actions)
            // Timed words with nothing saying where the song is: on top, how they can follow a music app.
            var followOffered by rememberSaveable { mutableStateOf(true) }
            AnimatedVisibility(
                visible = followOffered && state.lyricLines.isNotEmpty() && !state.canFollowApps && state.following == null && !state.listeningAlong
            ) {
                FollowOffer(actions, fixing = state.fixingInOneTap == OneTapFix.FOLLOW, onDismiss = { followOffered = false })
            }
            AnimatedVisibility(visible = syncing, modifier = Modifier.goingAway(unsyncing, TransformOrigin(0.5f, 0f))) {
                SyncStrip(state.lyricsSynced, actions, onDone = { syncingAsked = false })
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // The bar and its menus come and go over the words, so they never move for them.
                    .padding(bottom = 16.dp)
                    // The touch that brings the tools back isn't also a tap on a line.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { if (toolsAway && it.changedToDown()) it.consume() }
                            }
                        }
                    }
                    .onPreviewKeyEvent {
                        tv && it.type == KeyEventType.KeyDown && when {
                            // Nor is the remote's key that does.
                            toolsAway && it.key in SELECT_KEYS -> true
                            it.key != Key.DirectionRight -> false
                            toolsAway -> { focusBar = true; true }
                            else -> bar.requestFocus()
                        }
                    }
            ) {
                // Each state swaps in for the last, so the words arrive rather than appear.
                AnimatedContent(targetState = lyricsShown(state), transitionSpec = { fade() }, label = "lyrics") { shown ->
                    if (shown == LyricsShown.LOADING) {
                        val loading = stringResource(R.string.lyrics_loading)
                        LyricsMessage(Modifier.semantics { contentDescription = loading }) { LoadingLines() }
                    } else if (shown == LyricsShown.FAILED) {
                        LyricsMessage {
                            FilledTonalIconButton(onClick = actions.onShowLyrics, modifier = Modifier.size(72.dp)) {
                                Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.retry_button), modifier = Modifier.size(36.dp))
                            }
                        }
                    } else if (shown == LyricsShown.NONE) {
                        LyricsMessage {
                            Icon(
                                painterResource(R.drawable.ic_music_note),
                                contentDescription = stringResource(R.string.lyrics_none),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(72.dp)
                            )
                        }
                    } else if (shown == LyricsShown.TIMED) {
                        TimedLyrics(state, actions, timedList, syncing, onStudy = { studying = it })
                    } else if (state.learning.shows || (state.chords.on && state.chords.sheet != null)) {
                        // Read line by line, each with what helps read it, or its chords.
                        LyricLines(state, onStudy = { studying = it })
                    } else {
                        PlainLyrics(state.lyrics, state.learning.wordsScale, state.wordsCentered)
                    }
                }
            }
        }
        // A touch on the words, or anywhere else outside the card, puts the menu away. A tap and a
        // hold both do. The card and the bar are drawn over this, so they still take their own touches.
        if (barMenu != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("lyrics-menu-scrim")
                    .pointerInput(barMenu) {
                        detectTapGestures(
                            onTap = { barMenu = null },
                            onLongPress = { barMenu = null },
                        )
                    }
            )
        }
        AnimatedVisibility(
            visible = toolsShown,
            enter = fadeIn(tween(TOOLS_FADE_MS)),
            exit = fadeOut(tween(TOOLS_FADE_MS)),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp).onFocusChanged { barFocused = it.hasFocus }
        ) {
            // Only while the bar is there to take it.
            LaunchedEffect(focusBar) {
                if (focusBar) {
                    focusBar = false
                    bar.requestFocus()
                }
            }
            LyricsActions(
                state, actions, syncing, onOnlyVisuals = { visualsOnly = true },
                onSync = { syncingAsked = !syncing },
                onChooseLines = { practise -> choosing = practise },
                menu = barMenu, onMenuChange = { barMenu = it }, focus = bar,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
    }
    }
    songTab?.let { tab -> SongSheet(state, actions, tab, onTab = openTab, onReport = { reporting = true }, onDismiss = { songTab = null }, opener = info, reportButton = reportButton) }
    if (reporting) {
        SongReportDialog(state.report, onSend = actions.onSendReport, opener = reportButton, onDismiss = {
            reporting = false
            actions.onCloseReport()
        })
    }
    choosing?.let { practise -> ChooseLinesSheet(state, actions, practise, onDismiss = { choosing = null }) }
    studying?.let { line ->
        LineSheet(line, state, actions) {
            studying = null
            actions.onDismissWord()
        }
    }
}

/**
 * The words over MilkDrop visuals when they're on, always in the dark so they read over them, on
 * no ground of their own, the visuals darkened instead; otherwise on the usual one. With [only], the visuals
 * alone fill the screen, the phone's bars too, until a tap [onShowWords]. The visuals stay as they
 * are either way, so the words coming and going don't start them over.
 */
@Composable
private fun WithVisuals(state: UiState, only: Boolean, wordsBack: Animatable<Float, AnimationVector1D>, menuOpen: Boolean, onShowWords: () -> Unit, content: @Composable (ground: Color) -> Unit) {
    if (!state.visuals) return content(MaterialTheme.colorScheme.surface)
    CrosstuneTheme(darkTheme = true, palette = state.palette, pureBlack = true) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Keep a reading scrim even at the lightest setting. Menus soften the moving background further.
            val readingShade = if (menuOpen) 0.75f else 0.5f
            // Darkened as they're drawn, smaller than the screen, rather than under a shade over the whole of it.
            val reading = readingShade + (1f - readingShade) * state.visualsShade
            // Swiped back to, the words and their shade come as far as the swipe has.
            val coming = if (only) wordsBack.value else 1f
            LocalVisuals.current(state.visualsPlay.copy(shade = reading * coming, behind = true), Modifier.fillMaxSize())
            if (only) {
                HiddenSystemBars()
                if (coming > 0f) Box(Modifier.graphicsLayer { alpha = coming }) { content(Color.Transparent) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .swipedInFromSides(wordsBack, onShowWords)
                        .clickable(interactionSource = null, indication = null, onClickLabel = stringResource(R.string.visuals_show_words), onClick = onShowWords)
                )
            } else {
                content(Color.Transparent)
            }
        }
    }
}

/**
 * Whether a screen reader is on, which takes a tap to read what's under it rather than bring back what's gone. Any other
 * accessibility service, e.g. a password manager's, leaves taps as they are, so it doesn't count.
 */
@Composable
private fun rememberScreenReaderOn(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var on by remember { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/**
 * Holds the screen awake while the words follow a song that's playing. The phone's own timer
 * takes over once the song pauses, nothing says where it is, or these words leave the screen.
 */
@Composable
private fun KeepScreenOn(playing: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, playing) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** The phone's status and navigation bars out of the way while this shows; a swipe from an edge brings them back for a moment. */
@Composable
private fun HiddenSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val bars = WindowCompat.getInsetsController(window, view)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars.show(WindowInsetsCompat.Type.systemBars()) }
    }
}


private enum class LyricsShown { LOADING, FAILED, NONE, TIMED, PLAIN }

private fun lyricsShown(state: UiState): LyricsShown = when {
    state.isLoadingLyrics -> LyricsShown.LOADING
    state.lyricsFailed -> LyricsShown.FAILED
    state.lyrics.isEmpty() -> LyricsShown.NONE
    state.lyricLines.isNotEmpty() -> LyricsShown.TIMED
    else -> LyricsShown.PLAIN
}

/**
 * The song the words are of, and the way back. Swiped sideways, as in a music app's own player, the
 * app playing it goes on to its next song, or back to the one before, while it can.
 */
@Composable
private fun LyricsHeader(
    song: MusicMetadata,
    actions: ScreenActions,
    canSkipNext: Boolean,
    canSkipPrevious: Boolean,
    /** Whether the song's info button shows, rather than leaving the song's name and cover alone. */
    showAbout: Boolean,
    onAbout: () -> Unit,
    /** A tap on the song, which goes down to its player, as a swipe down does. */
    onPlayer: () -> Unit,
    onAboutFocus: (Boolean) -> Unit,
    info: FocusRequester
) {
    // How far the song is swiped; the next one comes in from the side it went out to.
    val swiped = remember { Animatable(0f) }
    var skippedNext by remember { mutableStateOf<Boolean?>(null) }
    // Once the song has changed it's back in place, for the new one, which came in the way it was skipped.
    LaunchedEffect(song.title, song.artist) {
        swiped.snapTo(0f)
        skippedNext = null
    }
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val canNext by rememberUpdatedState(canSkipNext)
    val canPrevious by rememberUpdatedState(canSkipPrevious)
    val onSkip by rememberUpdatedState(actions.onSkipSong)
    val nextSong = stringResource(R.string.lyrics_next_song)
    val previousSong = stringResource(R.string.lyrics_previous_song)
    val skip = { next: Boolean ->
        skippedNext = next
        onSkip(next)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
    ) {
        IconButton(onClick = actions.onDismissLyrics) {
            AppIcon(R.drawable.ic_expand_more, contentDescription = stringResource(R.string.dismiss_button))
        }
        // The next song heard swaps in for the last.
        val about = stringResource(R.string.lyrics_about_song)
        AnimatedContent(
            targetState = song,
            transitionSpec = { skippedNext?.let { slide(forward = it) } ?: swap() },
            label = "lyrics song",
            modifier = Modifier.weight(1f)
                .testTag(LYRICS_SONG_TAG)
                .semantics {
                    val skips = listOfNotNull(
                        CustomAccessibilityAction(previousSong) { skip(false); true }.takeIf { canSkipPrevious },
                        CustomAccessibilityAction(nextSong) { skip(true); true }.takeIf { canSkipNext }
                    )
                    if (skips.isNotEmpty()) customActions = skips
                }
                // The touch area stays put while the song moves, so a short swipe isn't a tap on it.
                .clickable(onClickLabel = stringResource(R.string.lyrics_to_player), onClick = onPlayer)
                .pointerInput(rtl) {
                    // Toward the start of the line, as reading goes, is on to the next song.
                    fun next(offset: Float) = (offset < 0f) != rtl
                    fun can(offset: Float) = if (next(offset)) canNext else canPrevious
                    // How far this swipe went, from wherever the song was, e.g. still on its way out from the last one.
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            val far = dragged
                            scope.launch {
                                if (abs(far) > size.width * SKIP_SWIPE_FRACTION && can(far)) {
                                    skip(next(far))
                                    swiped.animateTo(sign(far) * size.width, tween(SKIP_OUT_MS))
                                    // Still this song a while on, e.g. back to its own start, it comes back.
                                    delay(SKIP_WAIT_MS)
                                }
                                swiped.animateTo(0f, spring())
                            }
                        },
                        onDragCancel = { scope.launch { swiped.animateTo(0f, spring()) } }
                    ) { change, amount ->
                        if (!canNext && !canPrevious) return@detectHorizontalDragGestures
                        change.consume()
                        dragged += amount
                        // With nowhere to go that way, it gives a little and springs back.
                        val moved = if (can(dragged)) amount else amount / SKIP_RESISTANCE
                        scope.launch { swiped.snapTo(swiped.value + moved) }
                    }
                }
                .clip(MaterialTheme.shapes.medium)
                .graphicsLayer {
                    translationX = swiped.value
                    alpha = 1f - (abs(swiped.value) / size.width).coerceIn(0f, 1f)
                }
        ) { shown ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                CoverArt(shown.artworkUrl, actions.loadArtwork, size = 48.dp, modifier = Modifier.padding(start = 4.dp))
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(shown.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        shown.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        AboutButton(showAbout, about, onAbout, onAboutFocus, info)
    }
}

/** The song's info button. It fades out of its place rather than leave it, so the song doesn't move. */
@Composable
private fun AboutButton(shown: Boolean, label: String, onClick: () -> Unit, onFocus: (Boolean) -> Unit, requester: FocusRequester) {
    Box(modifier = Modifier.size(48.dp).focusRequester(requester).onFocusChanged { onFocus(it.hasFocus) }) {
        AnimatedVisibility(visible = shown, enter = fadeIn(tween(TOOLS_FADE_MS)), exit = fadeOut(tween(TOOLS_FADE_MS))) {
            IconButton(onClick = onClick) { AppIcon(R.drawable.ic_info, contentDescription = label) }
        }
    }
}

/**
 * What can be done with the words, in a bar at the bottom, where a thumb reaches: what helps read
 * them, the visuals, floating them, and what they follow.
 */
@Composable
private fun LyricsActions(
    state: UiState,
    actions: ScreenActions,
    syncing: Boolean,
    onOnlyVisuals: () -> Unit,
    onSync: () -> Unit,
    /** Opens the song's lines to choose from, to practise after when true. */
    onChooseLines: (Boolean) -> Unit,
    menu: BarMenu?,
    onMenuChange: (BarMenu?) -> Unit,
    /** Where a TV remote lands on the bar. */
    focus: FocusRequester,
    modifier: Modifier = Modifier
) {
    // The readings page handles back first, while it's open, and returns to its card.
    val closing = swipedAway(enabled = menu != null, shown = menu) { onMenuChange(null) }
    Box(modifier = modifier.widthIn(max = 392.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val close = { onMenuChange(null) }
        // Swiped back, a menu sinks into the bar it came from.
        Column(Modifier.goingAway(closing, TransformOrigin(0.5f, 1f))) {
            LearnMenu(menu == BarMenu.LEARN && state.lyrics.isNotEmpty(), state.learning, state.wordsCentered, actions)
            StudyMenu(menu == BarMenu.STUDY && state.lyrics.isNotEmpty(), state, actions, onChooseLines, close)
            VisualsMenu(menu == BarMenu.VISUALS && state.visuals, state, actions, onOnlyVisuals, close)
        }
        if (state.reportOffer) ReportOffer(actions)
        state.singingModel?.let { SingingModelOffer(it, actions) }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            shape = CircleShape,
            shadowElevation = 6.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.focusRequester(focus).focusGroup().padding(horizontal = 6.dp, vertical = 2.dp)) {
                if (state.lyrics.isNotEmpty()) {
                    BreathingButton(state.learning.translating, R.drawable.ic_translate, R.string.lyrics_learn, selected = menu == BarMenu.LEARN, onClick = { onMenuChange(if (menu == BarMenu.LEARN) null else BarMenu.LEARN) })
                }
                // The song's lines are learned, and lines kept are studied.
                if (state.lyrics.isNotEmpty()) {
                    BreathingButton(state.chords.loading, R.drawable.ic_school, R.string.lyrics_study, selected = menu == BarMenu.STUDY, onClick = { onMenuChange(if (menu == BarMenu.STUDY) null else BarMenu.STUDY) })
                }
                VisualsButton(state.visuals, selected = menu == BarMenu.VISUALS, hears = state.visualsPlay.hears, onClick = { if (state.visuals) onMenuChange(if (menu == BarMenu.VISUALS) null else BarMenu.VISUALS) else actions.onVisualsChange(true) })
                // Floating shows the line being sung, which only timed words have.
                if (state.lyricLines.isNotEmpty()) {
                    IconButton(onClick = actions.onFloat) { AppIcon(R.drawable.ic_float, contentDescription = stringResource(R.string.floating_float)) }
                }
                // Putting the words in time needs to know where the song is.
                if (state.lyricLines.isNotEmpty() && state.following != null) {
                    LitButton(syncing, R.drawable.ic_timer, stringResource(R.string.lyrics_sync), onClick = onSync)
                }
                SyncSource(state, actions)
            }
        }
        }
    }
}

private enum class BarMenu { LEARN, STUDY, VISUALS }

/** Over the bar, a problem the words shown have, offered to be reported by itself from now on, like it. */
@Composable
private fun ReportOffer(actions: ScreenActions) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.auto_report_offer), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            Button(onClick = { actions.onAllowAutoReports(true) }) {
                AppIcon(R.drawable.ic_check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.auto_report_yes))
            }
            IconButton(onClick = { actions.onAllowAutoReports(false) }) { AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.auto_report_no)) }
        }
    }
}

/**
 * Over the bar, while the words can't be lined up to the singing for want of the speech model,
 * which isn't fetched on mobile data by itself: fetched by hand, on any network.
 */
@Composable
private fun SingingModelOffer(offer: ModelOffer, actions: ScreenActions) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Text(
                stringResource(if (offer == ModelOffer.FAILED) R.string.singing_model_failed else R.string.singing_model_offer),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(12.dp))
            if (offer == ModelOffer.FETCHING) {
                CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.padding(12.dp).size(24.dp))
            } else {
                Button(onClick = actions.onFetchSingingModel) {
                    AppIcon(R.drawable.ic_download, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.singing_model_download))
                }
            }
            IconButton(onClick = actions.onCloseSingingModel) { AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.singing_model_close)) }
        }
    }
}

/** What the sheet about the whole song shows: what it means, its words to know, or its artist. */
internal enum class SongTab(@StringRes val label: Int) {
    MEANING(R.string.song_tab_meaning),
    WORDS(R.string.song_tab_words),
    ARTIST(R.string.song_tab_artist)
}

/**
 * Words with no lines of their own fade out at the top and bottom as a whole, rather than being cut
 * off mid-line under the song or at the bottom.
 */
private fun Modifier.fadedEdges(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    val top = 32.dp.toPx() / size.height
    val bottom = (BAR_FADE.toPx() / size.height).coerceAtMost(1f - top)
    drawRect(Brush.verticalGradient(0f to Color.Transparent, top to Color.Black, 1f - bottom to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
}

/** How far down the darker shade behind the song reaches, below the phone's bar: just past the song. */
private val HEADER_SHADE = 72.dp

/** The last words fade out over this much, at the bottom of where they end. */
private val BAR_FADE = 32.dp

/**
 * Each line of [list] fades out as it nears the top or bottom, by where it sits, rather than being
 * cut off mid-line under the song or at the bottom. Line by line, as fading the whole list at once
 * redraws all of it into a layer every frame, which a TV can't keep up with.
 */
private fun Modifier.fadedAtEdges(list: LazyListState, index: Int): Modifier = graphicsLayer {
    val info = list.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@graphicsLayer
    val middle = item.offset + item.size / 2f
    alpha = (minOf(middle - info.viewportStartOffset, info.viewportEndOffset - middle) / EDGE_FADE.toPx()).coerceIn(0f, 1f)
    // Faded line by line, with no layer of its own to fade.
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/** How far from the top or bottom a line's middle is when it starts to fade. */
private val EDGE_FADE = 40.dp

/** The darkest the ground over the visuals goes, so they never vanish altogether. */
internal const val MAX_VISUALS_SHADE = 0.9f

/** How long the song's info and the bar stay after the last touch or remote key. */
private const val TOOLS_SHOWN_MS = 5_000L

/** How long the song's info and the bar take to fade in and out. */
private const val TOOLS_FADE_MS = 400

/** The remote's keys that tap what has the focus. */
private val SELECT_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** How far across the song is swiped, of its width, to go on to the next song or back. */
private const val SKIP_SWIPE_FRACTION = 0.25f

/** How much less the song moves when swiped where there's no song to go to. */
private const val SKIP_RESISTANCE = 4f

private const val SKIP_OUT_MS = 180

/** How long a song swiped away waits for the next one before it comes back. */
private const val SKIP_WAIT_MS = 1_500L

/** Room under the words, so the last of them can be scrolled clear of the bar over them. */
private val BAR_ROOM = 88.dp

/** Whether anything that helps read the words is switched on, and so shown under them. */
private val Learning.shows get() = translation || (script != null && (readings || romanized || wordsAs != WordsAs.WRITTEN))

/**
 * How the words show, set here: how big they are, whether a script that reads otherwise shows as
 * written, in kana or in Latin letters, and what else helps read them, which is readings over them
 * for Japanese and Chinese, which Korean, read as written, doesn't need; Latin letters for all
 * three; and a translation, for any.
 */
@Composable
private fun LearnMenu(open: Boolean, learning: Learning, centered: Boolean, actions: ScreenActions) {
    if (!open) return
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.55f
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth().testTag("lyrics-display-options")
    ) {
        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(16.dp)) {
            WordsOptions(learning, actions, centered)
        }
    }
}

/** Whether the words are centered: as picked, else on a TV, where they read better from the couch. */
internal val UiState.wordsCentered get() = learning.centered ?: (phone == Phone.TV)

/** The same display controls in the lyrics menu and in the floating lyrics' settings; [centered] is null where words have no alignment to pick. */
@Composable
internal fun WordsOptions(learning: Learning, actions: ScreenActions, centered: Boolean? = null) {
    val script = learning.script
    if (script != null) {
        MenuCaption(R.string.lyrics_show_as)
        WordsAsPicker(script, learning.showsAs, actions.onWordsAsChange, learning.fromRomaji)
    }
    // Readings go over words as written, in the scripts that read otherwise; Latin letters, over any that aren't in them.
    val readings = (script == Script.JAPANESE || script == Script.CHINESE) && learning.showsAs == WordsAs.WRITTEN
    val latin = script != null && learning.showsAs != WordsAs.LATIN
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        if (readings) {
            LayerChip(R.string.lyrics_reading_options, R.string.lyrics_readings, learning.readings, actions.onReadingsChange, Modifier.weight(1f)) {
                Text(if (script == Script.JAPANESE) "あ" else "拼", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (latin) {
            LayerChip(R.string.lyrics_display_latin, R.string.lyrics_romanized, learning.romanized, actions.onRomanizedChange, Modifier.weight(1f)) {
                Text("Aa", style = MaterialTheme.typography.titleMedium)
            }
        }
        LayerChip(R.string.lyrics_translation, R.string.lyrics_translation, learning.translation, actions.onTranslationChange, Modifier.weight(1f)) {
            AppIcon(R.drawable.ic_translate, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
    if (script == Script.JAPANESE && (learning.romanized || learning.showsAs == WordsAs.LATIN)) {
        ReadingSwitch(R.string.lyrics_romaji_typed, learning.romajiTyped, actions.onRomajiTypedChange)
    }
    SizeSlider(R.string.lyrics_words_size, learning.wordsScale, MIN_WORDS_SCALE..MAX_WORDS_SCALE, actions.onWordsScaleChange)
    if (centered != null) AlignPicker(centered, actions.onWordsCenteredChange)
    if (readings && learning.readings) {
        SizeSlider(R.string.lyrics_reading_size, learning.readingScale, MIN_READING_SCALE..MAX_READING_SCALE, actions.onReadingScaleChange)
    }
    if (learning.translation) {
        SizeSlider(R.string.lyrics_translation_size, learning.translationScale, MIN_WORDS_SCALE..MAX_WORDS_SCALE, actions.onTranslationScaleChange)
    }
}

/** Whether the words sit at the start of the line or in its middle, an icon each so there's nothing to read. */
@Composable
private fun AlignPicker(centered: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        listOf(false to (R.drawable.ic_format_align_left to R.string.lyrics_align_start), true to (R.drawable.ic_format_align_center to R.string.lyrics_align_center))
            .forEachIndexed { index, (middle, look) ->
                SegmentedButton(
                    selected = centered == middle,
                    onClick = { onChange(middle) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    modifier = Modifier.heightIn(min = 48.dp),
                    icon = {}
                ) {
                    AppIcon(look.first, contentDescription = stringResource(look.second), modifier = Modifier.size(20.dp))
                }
            }
    }
}

/** A layer over the words, switched by tapping it and lit while it's on; [name] is what a screen reader calls it. */
@Composable
private fun LayerChip(@StringRes label: Int, @StringRes name: Int, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier, glyph: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val description = stringResource(name)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.heightIn(min = 56.dp).clip(shape)
            .then(if (on) Modifier.background(colors.secondaryContainer) else Modifier.border(1.dp, colors.outlineVariant, shape))
            .toggleable(on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides if (on) colors.onSecondaryContainer else colors.onSurfaceVariant) {
            Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) { glyph() }
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A slider's usable range, labeled from 1% to 100% without shrinking its actual minimum. */
internal fun sizeShare(scale: Float, range: ClosedFloatingPointRange<Float>): Float {
    if (range.endInclusive <= range.start) return 0.01f
    val position = ((scale - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    return 0.01f + position * 0.99f
}

/** A setting that takes effect as soon as its switch changes. */
@Composable
private fun ReadingSwitch(@StringRes label: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Switch, onValueChange = onChange)
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = on, onCheckedChange = null)
    }
}

/** A few words over what follows in a menu, small and quiet. */
@Composable
private fun MenuCaption(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/**
 * How big something is, slid within its readable [range], labeled from 1% to 100%.
 */
@Composable
internal fun SizeSlider(@StringRes label: Int, scale: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float, Boolean) -> Unit, @DrawableRes icon: Int = R.drawable.ic_format_size, description: String? = null) {
    val size = stringResource(label)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
            Text(size, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(NumberFormat.getPercentInstance().format(sizeShare(scale, range)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        var latest by remember { mutableFloatStateOf(scale) }
        AppSlider(
            value = scale,
            onValueChange = {
                latest = it
                onChange(it, false)
            },
            onValueChangeFinished = { onChange(latest, true) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth().slidesSideways().semantics { contentDescription = description ?: size }
        )
    }
}

/**
 * The song's lines, to choose which to learn, with how many are kept, once any are, and the song to
 * practise, with how many are due; with none kept, practising it chooses them first. Then its chords.
 */
@Composable
private fun StudyMenu(open: Boolean, state: UiState, actions: ScreenActions, onChooseLines: (Boolean) -> Unit, onClose: () -> Unit) {
    if (!open) return
    MenuCard("lyrics-study-options") {
        val song = state.lyricsFor
        val here = if (song == null) emptyList() else state.savedLines.filter { it.isOf(song) }
        if (here.isNotEmpty()) {
            MenuAction(R.drawable.ic_playlist_add, R.string.choose_lines, trailing = here.size.toString()) {
                onClose()
                onChooseLines(false)
            }
        }
        // Only lines with their meaning, or that can be heard, can be practised; with none, they're chosen first.
        val practisable = here.filter { it.practisable }
        val due = practisable.cardsDueCount(System.currentTimeMillis(), state.cardTypes)
        MenuAction(
            R.drawable.ic_play, R.string.practice_song,
            trailing = if (due > 0) stringResource(R.string.practice_due, due) else null,
            accent = true,
        ) {
            onClose()
            if (practisable.isEmpty()) onChooseLines(true) else actions.onPractiseSong()
        }
        // Chords are learned like lines are, so they're switched on here.
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        MenuSwitch(R.string.lyrics_chords, state.chords.on, R.drawable.ic_chords, actions.onChordsChange)
    }
}

/** The lines of the song whose words show, to choose which to learn; practised after when [practise]. */
@Composable
private fun ChooseLinesSheet(state: UiState, actions: ScreenActions, practise: Boolean, onDismiss: () -> Unit) {
    val song = state.lyricsFor ?: return
    val texts = state.lyricLines.map { it.text }.ifEmpty { state.lyrics.lines() }
    val words = state.learning.wordsToKnow?.value.orEmpty().map { it.word }
    ChooseLinesSheet(
        song, texts, words, state.savedLines.filter { it.isOf(song) }, actions.loadArtwork, practise,
        onSave = { add, remove -> actions.onChooseLines(add, remove, practise) }, onDismiss = onDismiss
    )
}

/**
 * [song]'s lines, each once, to choose which to learn: those [kept] checked, and after them the rest
 * kept that aren't among them, words or lines sung otherwise now, to let go. With none kept yet,
 * those worth it are checked to start with. Each says how many times it's sung, and whether it has
 * words worth knowing. Saved, or practised when [practise], what changed is given to [onSave].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChooseLinesSheet(
    song: MusicMetadata,
    texts: List<String>,
    words: List<String>,
    kept: List<SavedLine>,
    loadArtwork: suspend (String) -> ImageBitmap?,
    practise: Boolean,
    onSave: (add: List<Int>, remove: List<SavedLine>) -> Unit,
    onDismiss: () -> Unit
) {
    val lines = remember(texts, words) { songLines(texts, words) }
    val keptLines = kept.filter { it.line == null }
    val keptOf = { line: SongLine -> keptLines.firstOrNull { it.text.trim() == line.text } }
    // Kept, but not a line of the words shown: a word, or a line sung otherwise now.
    val others = kept.filter { saved -> saved.line != null || lines.none { it.text == saved.text.trim() } }
    val picked = remember(lines) { mutableStateListOf<Int>().apply { if (kept.isEmpty()) addAll(lines.filter { !it.filler }.map { it.index }) } }
    val dropped = remember(song) { mutableStateListOf<SavedLine>() }
    ScreenSheet(onDismiss) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                CoverArt(song.artworkUrl, loadArtwork, size = 40.dp)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(lines, key = { "line|${it.index}" }) { line ->
                    val saved = keptOf(line)
                    val on = if (saved != null) saved !in dropped else line.index in picked
                    ChoiceRow(line.text, on, onChange = { check ->
                        when {
                            saved == null -> if (check) picked.add(line.index) else picked.remove(line.index)
                            check -> dropped.remove(saved)
                            else -> dropped.add(saved)
                        }
                    }) {
                        if (line.newWords) {
                            AppIcon(R.drawable.ic_star, contentDescription = stringResource(R.string.learn_song_new_words), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                        if (line.times > 1) {
                            val times = stringResource(R.string.learn_song_times, line.times)
                            Text(
                                "×${line.times}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = times }
                            )
                        }
                    }
                }
                items(others, key = { "kept|${it.text}|${it.line}" }) { saved ->
                    ChoiceRow(saved.text, saved !in dropped, below = saved.line, onChange = { check -> if (check) dropped.remove(saved) else dropped.add(saved) })
                }
            }
            // Practised, it needs a line to practise; saved, something to change.
            val left = kept.size - dropped.size + picked.size
            Button(
                onClick = {
                    onSave(picked.toList(), dropped.toList())
                    onDismiss()
                },
                enabled = if (practise) left > 0 else picked.isNotEmpty() || dropped.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Text(stringResource(if (practise) R.string.saved_practice else R.string.save_button))
            }
        }
    }
}

/** A line to choose, checked or not, with the line a word is sung in [below] it, and what more there is to say about it after. */
@Composable
private fun ChoiceRow(text: String, on: Boolean, onChange: (Boolean) -> Unit, below: String? = null, after: @Composable RowScope.() -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(on, role = Role.Checkbox, onValueChange = onChange)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Checkbox(checked = on, onCheckedChange = null)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge.writing(text))
            below?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        after()
    }
}

/**
 * How the words show, picked from written (文), kana (あ, Japanese only) and Latin letters (Aa); the
 * menu stays open, to switch on more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WordsAsPicker(script: Script?, selected: WordsAs, onChange: (WordsAs) -> Unit, fromRomaji: Boolean = false) {
    val choices = buildList {
        add(Triple(WordsAs.WRITTEN, script?.glyph ?: "文", R.string.lyrics_as_written))
        if (script == Script.JAPANESE) add(Triple(WordsAs.KANA, "あ", R.string.lyrics_only_kana))
        add(Triple(WordsAs.LATIN, "Aa", R.string.lyrics_only_latin))
    }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 8.dp)) {
        choices.forEachIndexed { index, (wordsAs, glyph, label) ->
            val name = stringResource(label)
            SegmentedButton(
                selected = selected == wordsAs,
                onClick = { onChange(wordsAs) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {},
                modifier = Modifier.fillMaxHeight().semantics { contentDescription = name }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(glyph)
                    Text(stringResource(when (wordsAs) {
                        WordsAs.WRITTEN -> R.string.lyrics_display_original
                        WordsAs.KANA -> R.string.lyrics_display_kana
                        WordsAs.LATIN -> R.string.lyrics_display_latin
                    }), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Found in Latin letters, the words as written are ChatGPT's.
                if (wordsAs == WordsAs.WRITTEN && fromRomaji) {
                    val from = if (script == Script.KOREAN) R.string.lyrics_from_romanized_korean else R.string.lyrics_from_romaji
                    AppIcon(R.drawable.ic_translator_chatgpt, contentDescription = stringResource(from), modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
            }
        }
    }
}

/** While the words are being translated, or why they couldn't be. */
@Composable
private fun TranslationStatus(learning: Learning, actions: ScreenActions) {
    // While it's on its way the translate button shows it; only failing needs saying.
    AnimatedVisibility(visible = learning.translation && learning.translationFailed, enter = Motion.appear, exit = Motion.disappear) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = actions.onRetryTranslation) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.lyrics_translation_failed), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The fret the chords shown are played with a capo on, when they need one. */
@Composable
private fun CapoStatus(chords: ChordsState) {
    val capo = chords.sheet?.capo?.takeIf { chords.on && it > 0 }
    AnimatedVisibility(visible = capo != null, enter = Motion.appear, exit = Motion.disappear) {
        // Kept as it goes, so it doesn't read "Capo 0" on its way out.
        val fret = remember { intArrayOf(0) }
        capo?.let { fret[0] = it }
        Text(
            stringResource(R.string.lyrics_capo, fret[0]),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
        )
    }
}

/** The microphone turned down, e.g. to listen along, said here where it was asked for, with a way to allow it. */
@Composable
private fun MicrophoneStatus(denied: Boolean, actions: ScreenActions) {
    AnimatedVisibility(visible = denied, enter = Motion.appear, exit = Motion.disappear) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                stringResource(AppError.MICROPHONE.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 12.dp).padding(vertical = 12.dp)
            )
            TextButton(onClick = actions.onOpenMicrophoneSettings) { Text(stringResource(R.string.open_settings_button)) }
        }
    }
}

/**
 * Where the words' timing comes from, in the header's corner rather than over the words: the music
 * app playing the song, by its icon, or else the microphone, lit while it listens along to what
 * plays nearby and there to tap when it doesn't.
 */
@Composable
private fun SyncSource(state: UiState, actions: ScreenActions) {
    val app = state.following?.takeIf { it.app != null }
    AnimatedContent(targetState = app?.app to app?.appPackage, transitionSpec = { swap() }, label = "sync source") { (name, packageName) ->
        if (name != null) {
            AppSource(stringResource(R.string.lyrics_following_app, name), packageName, playing = state.following?.clock?.playing == true, onClick = actions.onPlayPause)
        } else {
            ListenAlongButton(state.listeningAlong, state.microphone == MicAccess.ALLOWED, actions)
        }
    }
    // Not playing anywhere yet, the song's app starts it from here, last, as Play on its card does, and the words then follow it.
    val start = (state.resultDestination as? Destination.Service)?.takeIf { state.canPlayFromLyrics }
    if (start != null) {
        AppSource(
            stringResource(R.string.play_in_background, start.label()),
            start.service.packageName,
            playing = false,
            enabled = !state.playingResult,
            onClick = actions.onPlayResult
        )
    }
}

/**
 * A music app by its icon, or a note when it has none to show: the one the words follow, or the
 * one to play them in. Tapped, it plays or pauses the song there, which a small badge on it shows.
 */
@Composable
private fun AppSource(description: String, packageName: String?, playing: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        packageName?.let { runCatching { context.packageManager.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    // Not an IconButton, which clips to a circle and would cut the badge off at its corner.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
    ) {
        // The badge sits on the icon's corner, inside the button.
        Box(modifier = Modifier.size(34.dp)) {
            if (icon != null) Image(icon, null, Modifier.size(28.dp).align(Alignment.Center).clip(CircleShape)) else Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(22.dp).align(Alignment.Center))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (playing) R.string.lyrics_pause else R.string.lyrics_play),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/** The microphone: lit and beating while it listens along, plain while it doesn't, crossed out while it isn't [allowed]. */
@Composable
private fun ListenAlongButton(listening: Boolean, allowed: Boolean, actions: ScreenActions) {
    val transition = rememberInfiniteTransition(label = "listening")
    val pulse by transition.animateFloat(1f, 1.12f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    val background by animateColorAsState(if (listening) MaterialTheme.colorScheme.primary else Color.Transparent, label = "mic background")
    val tint by animateColorAsState(if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, label = "mic tint")
    IconButton(
        onClick = if (listening) actions.onStopListeningAlong else actions.onListenAlong,
        modifier = Modifier.graphicsLayer {
            val beat = if (listening) pulse else 1f
            scaleX = beat
            scaleY = beat
        }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(background, CircleShape)) {
            Icon(
                painterResource(if (allowed) R.drawable.ic_recognize else R.drawable.ic_recognize_off),
                contentDescription = stringResource(if (listening) R.string.lyrics_stop_listening else R.string.lyrics_listen_along),
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** A tool of the bar, its icon breathing while what it brings is on its way: a translation, or chords. */
@Composable
private fun BreathingButton(working: Boolean, @DrawableRes icon: Int, @StringRes description: Int, selected: Boolean = false, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "working")
    val breath by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "breath")
    IconButton(onClick = onClick, modifier = Modifier.semantics { this.selected = selected }) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)) {
            Icon(painterResource(icon), contentDescription = stringResource(description), tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp).graphicsLayer { alpha = if (working) breath else 1f })
        }
    }
}

/**
 * MilkDrop visuals behind the words, lit while on: off, it turns them on; on, it opens what can be
 * done with them. On but not let [hears] the music, its dot is red, as things to fix are.
 */
@Composable
private fun VisualsButton(on: Boolean, selected: Boolean = on, hears: Boolean = true, onClick: () -> Unit) {
    Box {
        LitButton(selected, R.drawable.ic_visuals, stringResource(if (on) R.string.visuals_options else R.string.visuals_show), onClick)
        val dot = if (hears) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        if (on && (!selected || !hears)) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).background(dot, CircleShape))
    }
}

/** A button in the bar, lit while what it does is on. */
@Composable
private fun LitButton(on: Boolean, icon: Int, description: String, onClick: () -> Unit) {
    val background by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, label = "button background")
    val tint by animateColorAsState(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, label = "button tint")
    IconButton(onClick = onClick, modifier = Modifier.semantics { selected = on }) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(background, CircleShape)) {
            Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * What can be done with the visuals while they're on: how bright they are under the words, how long
 * each shows, from swapping fast to staying on one, the next, never this one again, them alone, or off.
 */
@Composable
private fun VisualsMenu(open: Boolean, state: UiState, actions: ScreenActions, onOnlyVisuals: () -> Unit, onClose: () -> Unit) {
    if (!open) return
    MenuCard("lyrics-visuals-options") {
        // Without the microphone they can't hear the music, so they don't move to it.
        if (!state.visualsPlay.hears) {
            MenuAction(R.drawable.ic_recognize_off, R.string.fix_mic_title) {
                onClose()
                actions.onVisualsChange(true)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
        // How bright they show, counted down from all the way to nearly black, as phones count their screens'.
        val brightness = stringResource(R.string.visuals_brightness)
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_shade, contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(22.dp))
                Text(brightness, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    NumberFormat.getPercentInstance().format(sizeShare(MAX_VISUALS_SHADE - state.visualsShade, 0f..MAX_VISUALS_SHADE)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // Where it was let go is kept, even before the menu has caught up with it.
            var latest by remember { mutableFloatStateOf(state.visualsShade) }
            AppSlider(
                value = MAX_VISUALS_SHADE - state.visualsShade,
                onValueChange = {
                    latest = MAX_VISUALS_SHADE - it
                    actions.onVisualsShadeChange(latest, false)
                },
                onValueChangeFinished = { actions.onVisualsShadeChange(latest, true) },
                valueRange = 0f..MAX_VISUALS_SHADE,
                modifier = Modifier.fillMaxWidth().slidesSideways().semantics { contentDescription = brightness }
            )
        }
        VisualsChangeRow(state.visualsSettings.changeMs, actions.onVisualsChangeEveryChange, modifier = Modifier.fillMaxWidth(), inset = 0.dp, icon = R.drawable.ic_timer)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        MenuAction(R.drawable.ic_skip_next, R.string.visuals_next) {
            onClose()
            actions.onNextVisual()
        }
        MenuAction(R.drawable.ic_visibility_off, R.string.visuals_drop) {
            onClose()
            actions.onDropVisual()
        }
        MenuAction(R.drawable.ic_visuals, R.string.visuals_only) {
            onClose()
            onOnlyVisuals()
        }
        MenuAction(R.drawable.ic_close, R.string.visuals_hide) {
            onClose()
            actions.onVisualsChange(false)
        }
    }
}

/** A card above the bar, the same shape as the readings one, tall enough to scroll on a short screen. */
@Composable
private fun MenuCard(tag: String, content: @Composable () -> Unit) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.55f
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth().testTag(tag)
    ) {
        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            content()
        }
    }
}

/** One thing a card can do, with what it leads to on the right when there is any. */
@Composable
private fun MenuAction(@DrawableRes icon: Int, @StringRes label: Int, trailing: String? = null, accent: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)
    ) {
        AppIcon(icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(22.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A setting in a card, switched as soon as it changes, and the card stays open. */
@Composable
internal fun MenuSwitch(@StringRes label: Int, on: Boolean, @DrawableRes icon: Int? = null, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Switch, onValueChange = onChange)
    ) {
        if (icon != null) AppIcon(icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(22.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = on, onCheckedChange = null)
    }
}

/** Where the words go while they're on their way, or when there are none. */
@Composable
private fun LyricsMessage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize().padding(32.dp)) { content() }
}

/** Three bars breathing where the words will be, while they're on their way. */
@Composable
private fun LoadingLines() {
    val transition = rememberInfiniteTransition(label = "loading lines")
    val breath by transition.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf(0.8f, 0.6f, 0.7f).forEach { width ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(width)
                    .height(18.dp)
                    .alpha(breath)
                    .background(MaterialTheme.colorScheme.onSurface, CircleShape)
            )
        }
    }
}

/** Words with no timings, read as a page. */
@Composable
private fun PlainLyrics(words: String, scale: Float, centered: Boolean) {
    Text(
        text = words,
        style = MaterialTheme.typography.titleLarge.run { copy(fontSize = fontSize * scale, lineHeight = 34.sp * scale, textAlign = alignment(centered)) },
        modifier = Modifier
            .fillMaxSize()
            .fadedEdges()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = BAR_ROOM)
            // The words are the point, so they read as one block rather than by line.
            .semantics(mergeDescendants = true) {}
    )
}

/**
 * The line of [lines] being sung, or -1 before the first or while nothing says where the song is.
 * Checked every frame while the song plays, so a line lights as it's sung; it only changes when the
 * line does. A paused song stays where it is.
 */
@Composable
internal fun rememberSungLine(lines: List<LyricLine>, following: Following?): Int {
    fun sung() = following?.takeIf { it.timingKnown }?.let { SyncedLyrics.indexAt(lines, it.clock.positionAt(SystemClock.elapsedRealtime())) } ?: -1
    val active by produceState(sung(), following, lines) {
        value = sung()
        while (following?.timingKnown == true && following.clock.playing) value = withInfiniteAnimationFrameMillis { sung() }
    }
    return active
}

/**
 * Timed words, one line at a time. While the song is followed the line being sung is lit, those
 * sung dim behind it, and the list glides to keep it a third of the way down; tapping a line moves
 * the music app there when it can.
 */
@Composable
private fun TimedLyrics(
    state: UiState,
    actions: ScreenActions,
    list: LazyListState,
    syncing: Boolean,
    onStudy: (Int) -> Unit
) {
    val lines = state.lyricLines
    val following = state.following
    val active = rememberSungLine(lines, following)
    val chords = rememberChords(state.chords, lines.map { it.text })
    val third = with(LocalDensity.current) { 160.dp.roundToPx() }
    val look = WordsLook.on(state.phone)
    LaunchedEffect(active) {
        if (active >= 0) list.animateScrollToItem(active, scrollOffset = -third)
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(look.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(lines) { index, line ->
            Box(Modifier.widthIn(max = look.width).fadedAtEdges(list, index)) {
                LyricLineText(
                    line.text,
                    words = line.words,
                    timeMs = line.timeMs,
                    nextMs = lines.getOrNull(index + 1)?.timeMs,
                    chords = chords.getOrNull(index),
                    following = following,
                    place = following?.let { index.compareTo(active) },
                    // Before the first line, e.g. in an intro, none is lit, so none is dimmed either.
                    waiting = active < 0,
                    // Tapping a line puts the words in time with it while syncing; otherwise it moves the music app there, when it can.
                    onSeek = when {
                        syncing -> ({ actions.onSyncLine(index) })
                        following?.canSeek == true && following.timingKnown -> ({ actions.onSeekLyrics(line.timeMs) })
                        else -> null
                    },
                    seekLabel = if (syncing) R.string.lyrics_sync_line else R.string.lyrics_jump,
                    onStudy = { onStudy(index) },
                    help = state.learning.helpFor(index),
                    textScale = state.learning.wordsScale,
                    centered = state.wordsCentered,
                    look = look
                )
            }
        }
    }
}

/** Words with no timings, line by line, when each has something under it to help read it. */
@Composable
private fun LyricLines(state: UiState, onStudy: (Int) -> Unit) {
    val lines = state.lyrics.lines()
    val chords = rememberChords(state.chords, lines)
    val list = rememberLazyListState()
    val look = WordsLook.on(state.phone)
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = BAR_ROOM),
        verticalArrangement = Arrangement.spacedBy(look.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(lines) { index, line ->
            Box(Modifier.widthIn(max = look.width).fadedAtEdges(list, index)) {
                LyricLineText(
                    line, place = null, chords = chords.getOrNull(index), onSeek = null, onStudy = { onStudy(index) }, help = state.learning.helpFor(index),
                    textScale = state.learning.wordsScale, centered = state.wordsCentered, look = look
                )
            }
        }
    }
}

/** Chords already placed over the lines they were for, so words opened again have theirs on the first frame. */
private val placedChords = LruCache<Pair<ChordSheet, List<String>>, List<LineChords>>(PLACED_CHORD_SHEETS)

private const val PLACED_CHORD_SHEETS = 8

/** Each of [texts]' chords while they show, worked out away from the screen, again whenever the lines change. */
@Composable
private fun rememberChords(chords: ChordsState, texts: List<String>): List<LineChords> {
    val placing = chords.sheet?.takeIf { chords.on }?.let { it to texts }
    val placed by produceState(remember(placing) { placing?.let { placedChords[it] } }.orEmpty(), placing) {
        value = placing?.let { key ->
            placedChords[key] ?: withContext(Dispatchers.Default) { ChordSheets.place(key.first, key.second) }?.also { placedChords.put(key, it) }
        }.orEmpty()
    }
    return placed
}

/** What helps read a line, as switched on. */
internal class LineHelp(
    val reading: LineReading?,
    val readings: Boolean,
    val romanized: Boolean,
    val translation: String?,
    /** What shows instead of the line: its kana, or its Latin letters. */
    val instead: String? = null,
    /** How big [readings] are beside the words they're over. */
    val readingScale: Float = READING_SCALE,
    /** Whether romaji is spelled as typed rather than as said. */
    val romajiTyped: Boolean = false,
    /** How big [translation] is, beside how it starts. */
    val translationScale: Float = 1f
) {
    /** The line in Latin letters, spelled as picked. */
    val latin get() = reading?.latin(romajiTyped)
}

internal fun Learning.helpFor(index: Int): LineHelp? {
    if (!shows) return null
    return LineHelp(lines.getOrNull(index), readings, romanized, translations.getOrNull(index).takeIf { translation }, insteadOf(index), readingScale, romajiTyped, translationScale)
}


/** How far the arrows on the strip move all the words. */
private const val NUDGE_MS = 500L

/**
 * A strip over the words while they're put in time: tap the line being sung, as often as it takes.
 * Reset puts them back as they were found, once any line was moved.
 */
@Composable
private fun SyncStrip(synced: Boolean, actions: ScreenActions, onDone: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.lyrics_sync_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { actions.onNudgeSync(-NUDGE_MS) }) {
                AppIcon(R.drawable.ic_chevron_right, contentDescription = stringResource(R.string.lyrics_sync_earlier), modifier = Modifier.rotate(180f))
            }
            IconButton(onClick = { actions.onNudgeSync(NUDGE_MS) }) { AppIcon(R.drawable.ic_chevron_right, contentDescription = stringResource(R.string.lyrics_sync_later)) }
            if (synced) TextButton(onClick = actions.onResetSync) { Text(stringResource(R.string.lyrics_sync_reset)) }
            IconButton(onClick = onDone) { AppIcon(R.drawable.ic_check, contentDescription = stringResource(R.string.lyrics_sync_done)) }
        }
    }
}

/** A strip over the words: they can follow the music app playing the song, once it's allowed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FollowOffer(actions: ScreenActions, fixing: Boolean, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        val stacked = LocalDensity.current.fontScale > 1.3f
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.lyrics_follow_allow), style = MaterialTheme.typography.bodyMedium,
                modifier = if (stacked) Modifier.fillMaxWidth() else Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OneTapButton(stringResource(R.string.follow_turn_on), actions.onAllowFollowing, fixing = fixing)
                IconButton(onClick = onDismiss) { AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.dismiss_button)) }
            }
        }
    }
}

/**
 * One line, by its [place]: the one being sung (0), one already sung (below 0), or one to come; or
 * null while nothing says where the song is, when every line reads alike, calmer, as a page.
 */
@Composable
private fun LyricLineText(
    text: String,
    place: Int?,
    words: List<TimedWord> = emptyList(),
    timeMs: Long = 0,
    nextMs: Long? = null,
    chords: LineChords? = null,
    following: Following? = null,
    onSeek: (() -> Unit)?,
    onStudy: () -> Unit,
    help: LineHelp? = null,
    waiting: Boolean = false,
    seekLabel: Int = R.string.lyrics_jump,
    textScale: Float,
    centered: Boolean,
    look: WordsLook
) {
    val lit = place == 0
    // Shown in kana or Latin letters instead, as set.
    val instead = help?.instead
    val alpha by animateFloatAsState(
        if (place == null || waiting) 0.9f else if (lit) 1f else if (place < 0) 0.4f else 0.6f,
        spring(stiffness = Spring.StiffnessLow),
        label = "line alpha"
    )
    val scale by animateFloatAsState(if (lit || place == null) 1f else 0.94f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow), label = "line scale")
    // Words with no timings, a page to read rather than lines to follow, are a little smaller.
    val size = look.size * (if (place == null) 22f / 26f else 1f) * textScale
    val style = MaterialTheme.typography.headlineSmall.copy(
        fontSize = size,
        // Rows of a line close together and lines further apart, so a line that wraps still reads as one.
        lineHeight = size * WORDS_ROWS,
        fontWeight = if (place == null) FontWeight.SemiBold else FontWeight.Bold,
        // Rows of about even length, broken between words, so no word or kana is left alone on the last.
        lineBreak = LineBreak.Heading,
        textAlign = alignment(centered)
    ).writing(text)
    Column(
        // The rows of a line are centered by their style; this centers the line where it's shorter than the screen.
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LYRIC_LINE_TAG)
            .semantics(mergeDescendants = true) { selected = lit }
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(if (centered) 0.5f else 0f, 0.5f)
                // Dimmed as each word is drawn, rather than drawing the line aside first to dim it, which a TV is too slow for line after line.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            // Tapped, the music app goes there when it can; held, or tapped when it can't, the line opens to study.
            // A gap, e.g. a solo, has no words to study, so it only moves the app there.
            .then(
                if (text.isNotBlank()) {
                    Modifier.combinedClickable(
                        onClickLabel = stringResource(if (onSeek != null) seekLabel else R.string.lyrics_study_line),
                        onClick = onSeek ?: onStudy,
                        onLongClickLabel = stringResource(R.string.lyrics_study_line),
                        onLongClick = onStudy
                    )
                } else {
                    onSeek?.let { Modifier.clickable(onClickLabel = stringResource(seekLabel), onClick = it) } ?: Modifier
                }
            )
            .padding(vertical = 6.dp)
    ) {
        val reading = help?.reading
        if (instead != null) {
            Text(instead, style = style, color = MaterialTheme.colorScheme.onSurface)
        } else if (chords != null && !chords.isEmpty) {
            // Chords sit over the letters they're played on, above the readings when those show too.
            val ruby = reading?.parts?.takeIf { help.readings && it.any { part -> part.reading != null } && it.joinToString("") { part -> part.text } == text }
            ChordedLine(LyricLine(timeMs, text, words), nextMs, chords, following.takeIf { lit }, style, ruby, help?.readingScale ?: READING_SCALE)
        } else if (help?.readings == true && reading != null && reading.parts.any { it.reading != null }) {
            RubyLine(reading.parts, style, help.readingScale)
        } else if (lit && following != null && words.isNotEmpty()) {
            SungWords(words, following, style)
        } else {
            // A gap with no words, e.g. a solo, is a note rather than an empty line.
            Text(text = text.ifBlank { "♪" }, style = style, color = MaterialTheme.colorScheme.onSurface)
        }
        val small = MaterialTheme.typography.bodyLarge.copy(fontSize = style.fontSize * 0.62f, lineHeight = style.lineHeight * 0.62f, textAlign = style.textAlign)
        // Shown in them already, they'd say it twice.
        if (help?.romanized == true) help.latin?.takeIf { it.isNotBlank() && it != instead }?.let {
            Text(it, style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        help?.translation?.let {
            val translated = MaterialTheme.typography.bodyLarge.copy(fontSize = TRANSLATION_SIZE * help.translationScale * look.translation, lineHeight = TRANSLATION_LINE * help.translationScale * look.translation, textAlign = style.textAlign)
            Text(it, style = translated, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun alignment(centered: Boolean) = if (centered) TextAlign.Center else TextAlign.Unspecified

/**
 * What [at] makes of where the song is while [following] it, checked every frame while it plays,
 * but only told, and drawn again, when it changes; null while not following.
 */
@Composable
internal fun <T> rememberWhilePlaying(following: Following?, at: (Long) -> T): T? {
    if (following == null) return null
    val latest by rememberUpdatedState(at)
    fun now() = latest(following.clock.positionAt(SystemClock.elapsedRealtime()))
    val value by produceState(now(), following) {
        value = now()
        while (following.clock.playing) value = withInfiniteAnimationFrameMillis { now() }
    }
    return value
}

/** Where the song is while [following] it, to the [POSITION_STEP_MS], which is as fine as a bar or clock shows; null while not. */
@Composable
internal fun rememberPosition(following: Following?): Long? = rememberWhilePlaying(following) { it - it % POSITION_STEP_MS }

/** How often a song's place moves on screen: more often would only draw the screen again for nothing. */
internal const val POSITION_STEP_MS = 100L

/** [words], the first [sung] lit and the rest dim. */
@Composable
private fun sungText(words: List<TimedWord>, sung: Int): AnnotatedString {
    val color = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        words.forEachIndexed { index, word -> withStyle(SpanStyle(color = if (index < sung) color else color.copy(alpha = UNSUNG_ALPHA))) { append(word.text) } }
    }
}

/** The line being sung, timed word by word: each word lights as it's sung. */
@Composable
private fun SungWords(words: List<TimedWord>, following: Following, style: TextStyle) {
    Text(sungText(words, rememberWhilePlaying(following) { SyncedLyrics.wordsSungBy(words, it) } ?: 0), style = style)
}

/** How bright the words of the line being sung are before they're sung. */
private const val UNSUNG_ALPHA = 0.45f

/** The chords shown over a line, by name, for tests to see what's drawn. */
internal val ChordsShown = SemanticsPropertyKey<List<String>>("ChordsShown")

/** Which of a line's chords is lit as it's played, by its place among them, -1 for none, for tests to see what's drawn. */
internal val ChordLit = SemanticsPropertyKey<Int>("ChordLit")

/**
 * A line with its chords: those played before it on a row of their own, then its words with each
 * chord over the letter it's played on. While it's sung, a line timed word by word lights its
 * words as they're sung, and the chord being played.
 */
@Composable
private fun ChordedLine(line: LyricLine, nextMs: Long?, chords: LineChords, following: Following?, style: TextStyle, ruby: List<Ruby>?, readingScale: Float) {
    val chordStyle = style.copy(fontSize = style.fontSize * CHORD_SCALE, lineHeight = style.lineHeight * CHORD_SCALE, color = MaterialTheme.colorScheme.primary)
    if (chords.before.isNotEmpty()) Text(chords.before.joinToString("   "), style = chordStyle)
    val text = line.text.ifBlank { "♪" }
    if (chords.over.isEmpty()) {
        if (ruby != null) RubyLine(ruby, style, readingScale) else Text(text, style = style, color = MaterialTheme.colorScheme.onSurface)
        return
    }
    // Only words timed one by one say when a chord's played closely enough to light it.
    val now = rememberWhilePlaying(following.takeIf { line.words.isNotEmpty() }) {
        ChordSheets.playing(line, chords.over, nextMs, it) to SyncedLyrics.wordsSungBy(line.words, it)
    }
    // Read only where it's drawn, so a new chord played just draws it again.
    val playing = remember { mutableIntStateOf(-1) }
    playing.intValue = now?.first ?: -1
    val over = readingStyle(style, readingScale).copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val written = remember(ruby, readingScale, text) { ruby?.let { rubyText(it, readingScale) } ?: AnnotatedString(text) }
    val shown = when {
        now == null -> written
        // Words lit as sung, with the readings' spacing kept when the words are the same letters.
        ruby != null && line.words.joinToString("") { it.text } == text -> {
            val litTo = line.words.take(now.second).sumOf { it.text.length }
            buildAnnotatedString {
                append(written)
                addStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = UNSUNG_ALPHA)), litTo, written.length)
            }
        }
        // Words that don't spell the line's own letters would put the chords and readings over the wrong ones.
        ruby != null -> written
        else -> sungText(line.words, now.second)
    }
    val measurer = rememberTextMeasurer()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        shown,
        // Room over each row of the words for the chords played on it, and the readings.
        style = style.copy(lineHeight = (style.lineHeight.value + chordStyle.lineHeight.value + if (ruby != null) over.lineHeight.value else 0f).sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None)),
        color = MaterialTheme.colorScheme.onSurface,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this[ChordsShown] = chords.over.map { it.name }
                this[ChordLit] = playing.intValue
                if (ruby != null) this[ReadingsShown] = ruby.mapNotNull { it.reading }
            }
            // Laid out once for the words as laid out, not again each frame the line's sung.
            .drawWithCache {
                val labels = chords.over.map { measurer.measure(it.name, chordStyle) }
                val places = layout?.let { chordPlaces(chords.over, labels.map { label -> label.size.width.toFloat() }, it, size.width, chordStyle.fontSize.toPx() / 3) }.orEmpty()
                val readings = if (ruby != null) layout?.let { readingPlaces(ruby, it) }.orEmpty() else emptyList()
                val readingLabels = readings.map { measurer.measure(it.reading, over) }
                // The readings sit just over the words, under the chords.
                val below = chordStyle.lineHeight.toPx()
                onDrawBehind {
                    val now = playing.intValue
                    places.forEachIndexed { index, place -> drawText(labels[index], color = if (now >= 0 && index != now) dim else chordStyle.color, topLeft = place) }
                    readings.forEachIndexed { index, place ->
                        val width = readingLabels[index].size.width.toFloat()
                        val x = (place.center - width / 2).coerceIn(0f, (size.width - width).coerceAtLeast(0f))
                        drawText(readingLabels[index], topLeft = Offset(x, place.top + below))
                    }
                }
            }
    )
}

/**
 * Where each chord's name goes over [words]: over its letter, on the row it's on, moved right past
 * the one before so none overlap, and back left from the edge, the ones before it with it, to fit.
 */
private fun chordPlaces(chords: List<Chord>, widths: List<Float>, words: TextLayoutResult, width: Float, gap: Float): List<Offset> {
    val rows = chords.map { words.getLineForOffset(it.at.coerceIn(0, words.layoutInput.text.length)) }
    val xs = chords.mapIndexed { index, chord -> words.getHorizontalPosition(chord.at.coerceIn(0, words.layoutInput.text.length), usePrimaryDirection = true) }.toMutableList()
    for (index in 1 until xs.size) if (rows[index] == rows[index - 1]) xs[index] = maxOf(xs[index], xs[index - 1] + widths[index - 1] + gap)
    for (index in xs.indices.reversed()) {
        val limit = if (index + 1 < xs.size && rows[index + 1] == rows[index]) xs[index + 1] - gap else width
        xs[index] = minOf(xs[index], limit - widths[index]).coerceAtLeast(0f)
    }
    return xs.mapIndexed { index, x -> Offset(x, words.getLineTop(rows[index])) }
}

/** How big chords are beside the words. */
private const val CHORD_SCALE = 0.55f

/** The readings drawn over a line's kanji, for tests to see; screen readers read the words as written. */
internal val ReadingsShown = SemanticsPropertyKey<List<String>>("ReadingsShown")

/**
 * A line with each piece's reading over it, in small type, the way furigana sit over kanji. The
 * line is laid out once, as a whole, rather than a piece at a time, so a screen of them shows at
 * once, and each reading is drawn over where its piece landed. Its rows are moved apart to make
 * room for the readings, only over those that have some.
 */
@Composable
internal fun RubyLine(
    parts: List<Ruby>,
    style: TextStyle,
    scale: Float = READING_SCALE,
    color: Color = MaterialTheme.colorScheme.onSurface,
    readingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val over = readingStyle(style, scale).copy(color = readingColor)
    val text = remember(parts, scale) { rubyText(parts, scale) }
    val measurer = rememberTextMeasurer()
    val room = with(LocalDensity.current) { over.lineHeight.toPx() }
    var words by remember { mutableStateOf<TextLayoutResult?>(null) }
    val shifts = words?.let { rowShifts(parts, it, room) }
    Text(
        text,
        style = style,
        color = color,
        onTextLayout = { words = it },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this[ReadingsShown] = parts.mapNotNull { it.reading } }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height + (shifts?.lastOrNull() ?: room).roundToInt()) { placeable.place(0, 0) }
            }
            .drawWithCache {
                val laidOut = words
                val places = laidOut?.let { readingPlaces(parts, it) }.orEmpty()
                val labels = places.map { measurer.measure(it.reading, over) }
                onDrawWithContent {
                    if (laidOut == null || shifts == null || shifts.size != laidOut.lineCount) return@onDrawWithContent drawContent()
                    // Each row of the words where it's moved to, under its readings.
                    for (row in 0 until laidOut.lineCount) {
                        translate(top = shifts[row]) { clipRect(top = laidOut.getLineTop(row), bottom = laidOut.getLineBottom(row)) { this@onDrawWithContent.drawContent() } }
                    }
                    places.forEachIndexed { index, place ->
                        val width = labels[index].size.width.toFloat()
                        val x = (place.center - width / 2).coerceIn(0f, (size.width - width).coerceAtLeast(0f))
                        drawText(labels[index], topLeft = Offset(x, place.top + shifts[place.row] - room))
                    }
                }
            }
    )
}

/**
 * How far down each row of [words] is moved, [room] over each for its readings: always over the
 * first, so a line stays where it is as its readings come; over any other only when it has some.
 */
internal fun rowShifts(parts: List<Ruby>, words: TextLayoutResult, room: Float): List<Float> {
    val read = readingPlaces(parts, words).mapTo(mutableSetOf()) { it.row }
    var shift = 0f
    return List(words.lineCount) { row ->
        if (row == 0 || row in read) shift += room
        shift
    }
}

/**
 * This style for [text]: Japanese told to be Japanese, which Android then breaks between phrases,
 * at their spaces, rather than inside a word, and draws with Japan's shapes of kanji.
 */
internal fun TextStyle.writing(text: String): TextStyle = if (text.any(Readings::isKana)) copy(localeList = LocaleList("ja"), lineBreak = LineBreak.Heading) else this

/** The small type of readings over words in [style], [scale] times as big. */
private fun readingStyle(style: TextStyle, scale: Float) =
    style.copy(fontSize = style.fontSize * scale, lineHeight = style.fontSize * (scale + READING_GAP), fontWeight = FontWeight.Medium)

/**
 * [parts] as one line, a piece whose reading is wider than it spaced out to make room for it. Kana
 * and kanji are about as wide as they're tall, so their widths are told by how many there are.
 */
internal fun rubyText(parts: List<Ruby>, scale: Float = READING_SCALE): AnnotatedString = buildAnnotatedString {
    parts.forEach { part ->
        val reading = part.reading
        val spare = if (reading == null) 0f else reading.length * scale - part.text.length
        if (spare > 0f) withStyle(SpanStyle(letterSpacing = (spare / part.text.length).em)) { append(part.text) } else append(part.text)
    }
}

/** A [reading] to draw centred on [center], at [top], the top of its piece's [row]. */
internal data class ReadingPlace(val reading: String, val center: Float, val top: Float, val row: Int = 0)

/** Where each reading goes over [words]: over its piece, or over what of it fits on the row it starts on. */
internal fun readingPlaces(parts: List<Ruby>, words: TextLayoutResult): List<ReadingPlace> {
    val length = words.layoutInput.text.length
    var start = 0
    return parts.mapNotNull { part ->
        val from = start
        start += part.text.length
        val reading = part.reading ?: return@mapNotNull null
        if (part.text.isEmpty() || start > length) return@mapNotNull null
        val row = words.getLineForOffset(from)
        val left = words.getBoundingBox(from).left
        val right = if (words.getLineForOffset(start - 1) == row) words.getBoundingBox(start - 1).right else words.getLineRight(row)
        ReadingPlace(reading, (left + right) / 2, words.getLineTop(row), row)
    }
}

/** How big readings are beside the words they're over, to start with; and as big as they can be made. */
internal const val READING_SCALE = 0.45f
internal const val MAX_READING_SCALE = 0.8f

/** Keep the smallest readings at their usual readable size. */
internal const val MIN_READING_SCALE = READING_SCALE

/** The largest the words can be, beside how they start. The slider reads 100% here. */
internal const val MAX_WORDS_SCALE = 3f

/** The smallest words stay at three fifths of their starting size, labeled 1% on the slider. */
internal const val MIN_WORDS_SCALE = 0.6f

/** How big a line's translation starts under the words in the lyrics, and the room it takes. */
/** How big the words are, how far apart their lines, how wide they run and how much bigger their translation. */
internal data class WordsLook(val size: TextUnit, val gap: Dp, val width: Dp, val translation: Float = 1f) {
    companion object {
        // No wider than about as many letters to a row as on a phone held upright, on its side or on a tablet too.
        private val PHONE = WordsLook(26.sp, 16.dp, 600.dp)
        // Read from across a room, but still with the lines before and after the one being sung.
        private val TV = WordsLook(34.sp, 20.dp, 700.dp, translation = 1.3f)

        fun on(phone: Phone) = if (phone == Phone.TV) TV else PHONE
    }
}

private const val WORDS_ROWS = 1.2f

private val TRANSLATION_SIZE = 14.sp
private val TRANSLATION_LINE = 20.sp

/** The room between a row's readings and the words under them, beside the words' size. */
private const val READING_GAP = 0.05f

/**
 * What a line can wrap between: a word in Latin letters with the space after it, or, since
 * Japanese and Chinese wrap anywhere, a single character (Korean, spaced, wraps by word), keeping the marks that can't start a
 * line, like 。 or ー or small kana, with the one before.
 */
private val WORDS = Regex("""[\p{IsLatin}\p{IsHangul}\p{N}'’.,!?:;&()\-]+\s*|\S[ーぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ・、。，．！？」』）〕】]*\s*|\s+""")

/**
 * One line, open to study: what helps read it, its words to tap for their meaning and, signed in, examples, and what can be
 * done with it: signed in, explained by ChatGPT; while the words follow a music
 * app, played over and over; and kept for later.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LineSheet(index: Int, state: UiState, actions: ScreenActions, onDismiss: () -> Unit) {
    val text = state.lyricLines.getOrNull(index)?.text ?: state.lyrics.lines().getOrNull(index) ?: return
    val learning = state.learning
    val reading = learning.lines.getOrNull(index)
    // Japanese loads its dictionary the first time, a moment better spent away from the screen.
    val words by produceState(emptyList<Word>(), text, learning.script) { value = withContext(Dispatchers.Default) { Readings.words(text, learning.script) } }
    val saved = state.savedLines.any { it.text == text && it.title == state.lyricsFor?.title && it.artist == state.lyricsFor?.artist }
    // Open all the way, so one back closes it rather than lowering it halfway first.
    ScreenSheet(onDismiss) {
        // Scrolled, so a long explanation is read to its end.
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
        ) {
            // The line, once: each of its words underlined, to tap for its meaning, with its readings
            // over the kanji, as in the lyrics.
            val parts = reading?.parts?.takeIf { parts -> parts.any { it.reading != null } }
            if (parts != null) {
                TappableRubyLine(parts, words, state.word?.word, learning.readingScale) { actions.onLookUpWord(it, index) }
            } else {
                TappableLine(text.ifBlank { "♪" }, words, state.word?.word) { actions.onLookUpWord(it, index) }
            }
            reading?.latin(learning.romajiTyped)?.takeIf { it.isNotBlank() && it != reading.reading }?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            learning.translations.getOrNull(index)?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
            val signedIn = state.chatGpt.email != null
            AnimatedContent(targetState = state.word, transitionSpec = { fade() }, label = "word") { meaning ->
                if (meaning != null) {
                    val examples = learning.examples?.takeIf { it.word == meaning.word && it.index == index }?.asking
                    WordCard(meaning, examples, canFindExamples = signedIn, romajiTyped = learning.romajiTyped) { actions.onFindExamples(index) }
                }
            }
            val explanation = learning.explanation?.takeIf { it.index == index }
            // What can be done with the line, side by side, wrapping as they need: signed in, explained
            // by ChatGPT, until it is; played again; and kept.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (signedIn && (explanation == null || explanation.failed || explanation.limitReached)) {
                    LineAction(R.string.lyrics_explain_line, R.drawable.ic_info) { actions.onExplainLine(index) }
                }
                // Only a music app the words follow, and that can be moved, can play a line again.
                if (state.following?.let { it.canSeek && it.timingKnown } == true && index < state.lyricLines.size) {
                    val repeating = state.repeating == index
                    LineAction(
                        if (repeating) R.string.lyrics_stop_repeating else R.string.lyrics_repeat_line, R.drawable.ic_repeat_one, selected = repeating,
                        onClick = if (repeating) actions.onStopRepeating else ({ actions.onRepeatLine(index) })
                    )
                }
                LineAction(
                    if (saved) R.string.lyrics_line_saved else R.string.lyrics_save_line, if (saved) R.drawable.ic_bookmark_added else R.drawable.ic_bookmark,
                    selected = saved
                ) { actions.onToggleSavedLine(index) }
            }
            if (explanation != null) {
                // Used up for now, it says so; failed, it can be asked again from its button.
                if (!explanation.failed) ExplanationCard(explanation)
                if (explanation.failed) Text(stringResource(R.string.lyrics_explain_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                // Explained, more can be asked about it.
                if (explanation.done) LineQuestions(index, learning, actions)
            }
        }
    }
}

/** Something to do with a line, as a chip; [selected] while it's done, like a line kept. */
@Composable
private fun LineAction(@StringRes label: Int, @DrawableRes icon: Int, selected: Boolean = false, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(label)) },
        leadingIcon = { AppIcon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
    )
}

/**
 * [text] with each of [words] a link, found in it in order, so it reads as written, marks and all,
 * and wraps as text does. The word looked up stands out.
 */
@Composable
private fun TappableLine(text: String, words: List<Word>, picked: Word?, onPick: (Word) -> Unit) {
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val onHighlight = MaterialTheme.colorScheme.onPrimaryContainer
    val line = buildAnnotatedString {
        append(text)
        var from = 0
        words.forEach { word ->
            val at = text.indexOf(word.text, from).takeIf { it >= 0 } ?: return@forEach
            addLink(wordLink(word, word == picked, SpanStyle(background = highlight, color = onHighlight), onPick), at, at + word.text.length)
            from = at + word.text.length
        }
    }
    Text(line, style = MaterialTheme.typography.headlineSmall.writing(line.text), fontWeight = FontWeight.Bold, modifier = Modifier.testTag(STUDY_LINE_TAG))
}

/** [word] as a link to its meaning: underlined, or standing out in [highlight] once [picked]. */
private fun wordLink(word: Word, picked: Boolean, highlight: SpanStyle, onPick: (Word) -> Unit) =
    LinkAnnotation.Clickable(word.text, TextLinkStyles(if (picked) highlight else SpanStyle(textDecoration = TextDecoration.Underline))) { onPick(word) }

/**
 * [parts] of a line with their readings over them, wrapping as the lyrics do, each piece of one of
 * [words] a link to it. The word looked up stands out.
 */
@Composable
private fun TappableRubyLine(parts: List<Ruby>, words: List<Word>, picked: Word?, scale: Float, onPick: (Word) -> Unit) {
    val text = parts.joinToString("") { it.text }
    // Where in the line each word is.
    val spans = buildList {
        var from = 0
        words.forEach { word ->
            val at = text.indexOf(word.text, from).takeIf { it >= 0 } ?: return@forEach
            add(at until at + word.text.length to word)
            from = at + word.text.length
        }
    }
    val style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    val over = readingStyle(style, scale)
    val highlight = SpanStyle(background = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer)
    FlowRow(modifier = Modifier.testTag(STUDY_LINE_TAG)) {
        var at = 0
        parts.forEach { part ->
            // A part with a reading stays whole; one without wraps as the lyrics do.
            val pieces = if (part.reading == null) WORDS.findAll(part.text).map { it.value }.toList() else listOf(part.text)
            pieces.forEach { piece ->
                val start = at
                at += piece.length
                val word = spans.firstOrNull { start in it.first }?.second
                val shown = buildAnnotatedString {
                    append(piece)
                    if (word != null) addLink(wordLink(word, word == picked, highlight, onPick), 0, piece.trimEnd().length)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Bottom)) {
                    if (part.reading != null) Text(part.reading, style = over, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(shown, style = style)
                }
            }
        }
    }
}

/**
 * A word tapped: how it reads, and what it means once found; then, signed in, sentences using it
 * the same way, once asked for.
 */
@Composable
internal fun WordCard(meaning: WordMeaning, examples: Asking<List<Example>>?, canFindExamples: Boolean, romajiTyped: Boolean = false, onFindExamples: () -> Unit) {
    val word = meaning.word
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // What doesn't fit goes on the next line, rather than squeezed a letter a line.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(word.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
                // Romaji as said and as typed, where they're spelled otherwise: sensee and sensei.
                (listOfNotNull(word.reading) + bothRomaji(word.romanized, word.typed, romajiTyped)).distinct().forEach {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                }
            }
            if (meaning.looking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } else if (meaning.failed) {
                Text(stringResource(R.string.lyrics_word_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } else if (meaning.meaning != null) {
                meaning.type?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(meaning.meaning, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                if (canFindExamples && (examples == null || examples.failed)) {
                    Row(modifier = Modifier.padding(top = 4.dp)) { LineAction(R.string.lyrics_examples, R.drawable.ic_language, onClick = onFindExamples) }
                }
                examples?.let { Examples(it) }
            } else {
                Text(stringResource(R.string.lyrics_word_unknown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Sentences using a word: on their way, each with its meaning, or that they couldn't be had. */
@Composable
private fun Examples(asking: Asking<List<Example>>) {
    if (asking.failed) {
        Text(stringResource(R.string.lyrics_examples_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        return
    }
    Column(modifier = Modifier.padding(top = 8.dp).animateContentSize(Motion.size), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.lyrics_examples), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        val found = asking.value
        if (found == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        found?.forEach { example ->
            Column {
                Text(example.sentence, style = MaterialTheme.typography.bodyLarge)
                Text(example.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * All about the song, as ChatGPT finds it, a [tab] at a time: what it means, with how hard its words
 * are and what it covers or samples, and questions about it; its words most worth learning; and its
 * artist. Songs like it are a tap away from any. Without ChatGPT, the way to sign in for it. A
 * problem with its words is reported from its corner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongSheet(state: UiState, actions: ScreenActions, tab: SongTab, onTab: (SongTab) -> Unit, onReport: () -> Unit, onDismiss: () -> Unit, opener: FocusRequester, reportButton: FocusRequester) {
    ScreenSheet(onDismiss, opener) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SongNames(state, Modifier.weight(1f))
                // Wrong words, or out of time, said where they can be put right.
                IconButton(onClick = onReport, modifier = Modifier.focusRequester(reportButton)) { AppIcon(R.drawable.ic_feedback, contentDescription = stringResource(R.string.report_problem)) }
            }
            PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
                SongTab.entries.forEach { shown ->
                    Tab(selected = shown == tab, onClick = { onTab(shown) }, text = { Text(stringResource(shown.label)) })
                }
            }
            if (state.chatGpt.email == null) {
                ContinueWithChatGpt(state.chatGpt, actions, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp))
                return@Column
            }
            if (tab == SongTab.MEANING) SongMeaning(state, actions)
            if (tab == SongTab.WORDS) WordsToKnow(state, actions)
            if (tab == SongTab.ARTIST) AboutArtist(state, actions)
            OutlinedButton(onClick = { state.lyricsFor?.let(actions.onSongsLike) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                AppIcon(R.drawable.ic_queue_music, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.lyrics_songs_like), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * The song's title and its artist, read as its words are: with readings over their kanji, and in
 * Latin letters, as those are switched on, to know how to say them; and what the title means,
 * translated as the words are.
 */
@Composable
private fun SongNames(state: UiState, modifier: Modifier) {
    val song = state.lyricsFor
    val learning = state.learning
    Column(modifier) {
        SongName(song?.title.orEmpty(), learning.names.getOrNull(0), learning, MaterialTheme.typography.titleLarge)
        learning.titleMeaning?.takeIf { learning.translation }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        song?.artist?.takeIf { it.isNotBlank() }?.let { SongName(it, learning.names.getOrNull(1), learning, MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun SongName(name: String, reading: LineReading?, learning: Learning, style: TextStyle) {
    val parts = reading?.parts?.takeIf { learning.readings && it.any { part -> part.reading != null } && it.joinToString("") { part -> part.text } == name }
    if (parts != null) RubyLine(parts, style, learning.readingScale) else Text(name, style = style)
    reading?.latin(learning.romajiTyped)?.takeIf { learning.romanized && it.isNotBlank() && it != name }?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * What ChatGPT says the whole song is about, as it writes it; or that the plan's used up, or it
 * couldn't, with a way to ask again. Then what it covers or samples, and questions about it.
 */
@Composable
private fun SongMeaning(state: UiState, actions: ScreenActions) {
    val explanation = state.learning.songExplanation
    val facts = state.learning.songFacts
    if (explanation == null || explanation.writing || explanation.done || explanation.limitReached) {
        ExplanationCard(explanation ?: LineExplanation(MainViewModel.SONG), SONG_PARTS)
    }
    if (explanation?.failed == true) {
        Text(stringResource(R.string.lyrics_song_meaning_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    if (explanation?.failed == true || explanation?.limitReached == true) {
        OutlinedButton(onClick = actions.onExplainSong, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry_button)) }
    }
    // The song it covers and those it samples, each a tap from opening.
    facts?.original?.let { SongsNamed(R.string.song_original, listOf(it), actions) }
    facts?.samples?.takeIf { it.isNotEmpty() }?.let { SongsNamed(R.string.song_samples, it, actions) }
    // Explained, more can be asked about it.
    if (explanation?.done == true) {
        Questions(state.learning.songQuestions, R.string.lyrics_ask_song, key = state.lyricsFor, onAsk = actions.onAskAboutSong)
    }
}

/**
 * The song's words most worth learning before listening, as ChatGPT picks them, each with how it
 * reads and what it means there, to save and practise like a line.
 */
@Composable
private fun WordsToKnow(state: UiState, actions: ScreenActions) {
    val asking = state.learning.wordsToKnow
    val song = state.lyricsFor
    when {
        asking == null || asking.loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        asking.failed -> {
            Text(stringResource(R.string.lyrics_words_to_know_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = actions.onFindWordsToKnow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry_button)) }
        }
        else -> asking.value.orEmpty().forEach { word ->
            val saved = state.savedLines.any { it.text == word.word && it.title == song?.title && it.artist == song?.artist }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(word.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        word.reading?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text(word.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { actions.onToggleSavedWord(word) }) {
                    AppIcon(
                        if (saved) R.drawable.ic_bookmark_added else R.drawable.ic_bookmark,
                        contentDescription = stringResource(if (saved) R.string.lyrics_word_saved else R.string.lyrics_save_word, word.word)
                    )
                }
            }
        }
    }
}

/** Who the song's artist is, as ChatGPT writes it from the web; or that it couldn't, with a way to ask again. */
@Composable
private fun AboutArtist(state: UiState, actions: ScreenActions) {
    val about = state.learning.artist
    Text(state.lyricsFor?.artist.orEmpty(), style = MaterialTheme.typography.titleMedium)
    if (about == null || about.writing || about.done || about.limitReached) {
        ExplanationCard(about ?: LineExplanation(MainViewModel.SONG), ARTIST_PARTS)
    }
    if (about?.failed == true) {
        Text(stringResource(R.string.lyrics_about_artist_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    if (about?.failed == true || about?.limitReached == true) {
        OutlinedButton(onClick = actions.onAboutArtist, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry_button)) }
    }
}

/** Songs named by ChatGPT under [label], each a tap from opening it. */
@Composable
private fun SongsNamed(@StringRes label: Int, songs: List<Pair<String, String>>, actions: ScreenActions) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        songs.forEach { (title, artist) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { actions.onOpenNamedSong(title, artist) }.padding(vertical = 8.dp)
            ) {
                AppIcon(R.drawable.ic_music_note, contentDescription = null, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The questions asked about line [index] with their answers, and a field to ask another. */
@Composable
private fun LineQuestions(index: Int, learning: Learning, actions: ScreenActions) {
    Questions(learning.questions.takeIf { learning.questionsAbout == index }.orEmpty(), R.string.lyrics_ask_line, key = index) { actions.onAskAboutLine(index, it) }
}

/** [questions] asked, with their answers, and a field, [hint], to ask another about what [key] is. */
@Composable
private fun Questions(questions: List<LineQuestion>, @StringRes hint: Int, key: Any?, onAsk: (String) -> Unit) {
    questions.forEach { asked ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 8.dp)) {
            Text(asked.question, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when {
                asked.limitReached -> Text(stringResource(R.string.chatgpt_limit_reached), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                asked.failed -> Text(stringResource(R.string.lyrics_ask_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                asked.answer.isEmpty() -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                else -> Text(plainText(asked.answer), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    var question by rememberSaveable(key) { mutableStateOf("") }
    val send = {
        onAsk(question)
        question = ""
    }
    val asking = questions.lastOrNull()?.writing == true
    OutlinedTextField(
        value = question,
        onValueChange = { question = it },
        placeholder = { Text(stringResource(hint)) },
        trailingIcon = {
            IconButton(onClick = send, enabled = question.isNotBlank() && !asking) {
                AppIcon(R.drawable.ic_send, contentDescription = stringResource(R.string.lyrics_ask_send))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (question.isNotBlank() && !asking) send() }),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
}

/** What ChatGPT says a line means, as it writes it, or that the plan's used up for now. */
@Composable
private fun ExplanationCard(explanation: LineExplanation, labels: List<Int> = LINE_PARTS) {
    val openWebPage = rememberWebOpener()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp).animateContentSize(Motion.size), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                explanation.limitReached -> {
                    Text(stringResource(R.string.chatgpt_limit_reached), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { openWebPage(ChatGpt.USAGE_URL) }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.chatgpt_manage_usage))
                    }
                }
                explanation.text.isEmpty() -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                else -> ExplanationParts(explanation.text, labels)
            }
        }
    }
}

/** An explanation's parts, each under its label, in [labels]' order; a part left empty isn't shown. */
@Composable
private fun ExplanationParts(text: String, labels: List<Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        explanationParts(text).forEachIndexed { index, part ->
            if (part.isEmpty()) return@forEachIndexed
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                labels.getOrNull(index)?.let { Text(stringResource(it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                Text(part, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** What a line's explanation says, in order: what it means in the song, then about its language. */
private val LINE_PARTS = listOf(R.string.explanation_in_song, R.string.explanation_language)

/** What's said about an artist, in order. */
private val ARTIST_PARTS = listOf(R.string.artist_who, R.string.artist_style, R.string.artist_known_for)

/** What a song's explanation says, in order. */
private val SONG_PARTS = listOf(R.string.song_about, R.string.song_story, R.string.song_mood, R.string.song_key_lines)

