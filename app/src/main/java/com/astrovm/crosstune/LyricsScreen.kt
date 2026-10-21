package com.astrovm.crosstune

import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import java.text.NumberFormat
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.activity.compose.BackHandler
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
import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis

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
    val openTab = { tab: SongTab ->
        songTab = tab
        if (tab == SongTab.MEANING) actions.onExplainSong()
        if (tab == SongTab.WORDS) actions.onFindWordsToKnow()
        if (tab == SongTab.ARTIST) actions.onAboutArtist()
    }
    if (state.practice != null) {
        PracticeScreen(state, actions)
        return
    }
    // The song's lines to choose which to learn, open over the words; true when it's practised after.
    var choosing by rememberSaveable(song) { mutableStateOf<Boolean?>(null) }
    // Saved lines take the place of the words, so only the words hold the screen, and only while a song plays.
    KeepScreenOn(state.following?.clock?.playing == true)
    BackHandler(onBack = actions.onDismissLyrics)
    // Where the timed words are scrolled to, kept out here so they're right where they were when they
    // come back, e.g. once the visuals come or go, with nothing to glide back to.
    val timedList = rememberSaveable(song, saver = LazyListState.Saver) { LazyListState() }
    // Only the visuals, full screen, until a tap or Back brings the words back.
    var visualsOnly by rememberSaveable { mutableStateOf(false) }
    // Putting the words in time: a line tapped is the one being sung. Only while something says where the song is.
    var syncingAsked by rememberSaveable(song) { mutableStateOf(false) }
    val syncing = syncingAsked && state.lyricLines.isNotEmpty() && state.following != null
    BackHandler(enabled = syncing) { syncingAsked = false }
    val onlyVisuals = visualsOnly && state.visuals
    BackHandler(enabled = onlyVisuals) { visualsOnly = false }
    val tint by animateColorAsState(
        coverColor(cover, actions.loadArtwork) ?: MaterialTheme.colorScheme.primary,
        tween(durationMillis = 700),
        label = "lyrics tint"
    )
    // The bar makes way while the words are scrolled, and comes back once they rest.
    var scrolledAt by remember { mutableLongStateOf(0L) }
    var barShown by remember { mutableStateOf(true) }
    var barMenu by remember(song) { mutableStateOf<BarMenu?>(null) }
    var actionsHeight by remember { mutableStateOf(0.dp) }
    LaunchedEffect(scrolledAt) {
        if (scrolledAt == 0L) return@LaunchedEffect
        barShown = false
        delay(BAR_BACK_MS)
        barShown = true
    }
    val layers = LocalLayerSwipe.current
    val scrolling = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) scrolledAt = SystemClock.uptimeMillis()
                // Pulled back up, the words stay put until the pull is undone.
                return if (layers.swiping && available.y < 0f) Offset(0f, layers.drag(available.y)) else Offset.Zero
            }

            // Pulled down past the first line, the words follow the finger back to what's under them.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, layers.drag(available.y)) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity = Velocity(0f, layers.flung(available.y))
        }
    }
    WithVisuals(state, onlyVisuals, onShowWords = { visualsOnly = false }) { ground ->
    Surface(color = ground, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrolling)
            // Where there's no list to pull down, e.g. while the words are found, a swipe down does the same.
            .swipeLayers()
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
            // Signed in with ChatGPT, the song opens to what it's about; otherwise it holds only where to report a problem.
            LyricsHeader(
                song.copy(artworkUrl = cover), actions, state.canSkipNext, state.canSkipPrevious,
                onAbout = { if (state.chatGpt.email != null) openTab(SongTab.MEANING) else songTab = SongTab.MEANING }
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
            AnimatedVisibility(visible = syncing) {
                SyncStrip(state.lyricsSynced, actions, onDone = { syncingAsked = false })
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = if (barMenu != null) actionsHeight + 32.dp else BAR_SPACE)) {
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
                        PlainLyrics(state.lyrics, state.learning.wordsScale)
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
            visible = barShown || barMenu != null,
            enter = slideInVertically { it * 2 } + fadeIn(),
            exit = slideOutVertically { it * 2 } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)
        ) {
            LyricsActions(
                state, actions, syncing, onOnlyVisuals = { visualsOnly = true },
                onSync = { syncingAsked = !syncing },
                onChooseLines = { practise -> choosing = practise },
                menu = barMenu, onMenuChange = { barMenu = it }, onHeightChange = { actionsHeight = it },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
    }
    }
    songTab?.let { tab -> SongSheet(state, actions, tab, onTab = openTab, onDismiss = { songTab = null }) }
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
 * a ground that lets the visuals through; otherwise on the usual one. With [only], the visuals
 * alone fill the screen, the phone's bars too, until a tap [onShowWords]. The visuals stay as they
 * are either way, so the words coming and going don't start them over.
 */
@Composable
private fun WithVisuals(state: UiState, only: Boolean, onShowWords: () -> Unit, content: @Composable (ground: Color) -> Unit) {
    if (!state.visuals) return content(MaterialTheme.colorScheme.surface)
    CrosstuneTheme(darkTheme = true, palette = state.palette, pureBlack = true) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            LocalVisuals.current(state.visualsPlay, Modifier.fillMaxSize())
            if (only) {
                HiddenSystemBars()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = null, indication = null, onClickLabel = stringResource(R.string.visuals_show_words), onClick = onShowWords)
                )
            } else {
                content(Color.Black.copy(alpha = state.visualsShade))
            }
        }
    }
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
private fun LyricsHeader(song: MusicMetadata, actions: ScreenActions, canSkipNext: Boolean, canSkipPrevious: Boolean, onAbout: () -> Unit) {
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
                // The touch area stays put while the song moves, so a short swipe isn't a tap on its info.
                .clickable(onClickLabel = about, onClick = onAbout)
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
        IconButton(onClick = onAbout) { AppIcon(R.drawable.ic_info, contentDescription = about) }
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
    onHeightChange: (Dp) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    // The readings page handles back first, while it's open, and returns to its card.
    BackHandler(enabled = menu != null) { onMenuChange(null) }
    Box(modifier = modifier.widthIn(max = 392.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.onSizeChanged { onHeightChange(with(density) { it.height.toDp() }) }) {
        val close = { onMenuChange(null) }
        LearnMenu(menu == BarMenu.LEARN && state.lyrics.isNotEmpty(), state.learning, actions)
        StudyMenu(menu == BarMenu.STUDY && state.lyrics.isNotEmpty(), state, actions, onChooseLines, close)
        VisualsMenu(menu == BarMenu.VISUALS && state.visuals, state, actions, onOnlyVisuals, close)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            shape = CircleShape,
            shadowElevation = 6.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                if (state.lyrics.isNotEmpty()) {
                    BreathingButton(state.learning.translating, R.drawable.ic_translate, R.string.lyrics_learn, selected = menu == BarMenu.LEARN, onClick = { onMenuChange(if (menu == BarMenu.LEARN) null else BarMenu.LEARN) })
                }
                // The song's lines are learned, and lines kept are studied.
                if (state.lyrics.isNotEmpty()) {
                    BreathingButton(state.chords.loading, R.drawable.ic_school, R.string.lyrics_study, selected = menu == BarMenu.STUDY, onClick = { onMenuChange(if (menu == BarMenu.STUDY) null else BarMenu.STUDY) })
                }
                VisualsButton(state.visuals, selected = menu == BarMenu.VISUALS, onClick = { if (state.visuals) onMenuChange(if (menu == BarMenu.VISUALS) null else BarMenu.VISUALS) else actions.onVisualsChange(true) })
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

/** What the sheet about the whole song shows: what it means, its words to know, or its artist. */
internal enum class SongTab(@StringRes val label: Int) {
    MEANING(R.string.song_tab_meaning),
    WORDS(R.string.song_tab_words),
    ARTIST(R.string.song_tab_artist)
}

/**
 * The words fade out at the top and bottom, rather than being cut off mid-line under the song or
 * where they end above the bar.
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

/** The bar's height and the 16dp under it, plus a gap: where the words end, so the bar never covers them. */
private val BAR_SPACE = 80.dp

/** The darkest the ground over the visuals goes, so they never vanish altogether. */
internal const val MAX_VISUALS_SHADE = 0.9f

/** How long after the words stop being scrolled the bar comes back. */
private const val BAR_BACK_MS = 1_200L

/** How far across the song is swiped, of its width, to go on to the next song or back. */
private const val SKIP_SWIPE_FRACTION = 0.25f

/** How much less the song moves when swiped where there's no song to go to. */
private const val SKIP_RESISTANCE = 4f

private const val SKIP_OUT_MS = 180

/** How long a song swiped away waits for the next one before it comes back. */
private const val SKIP_WAIT_MS = 1_500L

/** Room under the words, so the last of them can be scrolled clear of their edge. */
private val BAR_ROOM = 24.dp

/** Whether anything that helps read the words is switched on, and so shown under them. */
private val Learning.shows get() = translation || (script != null && (readings || romanized || wordsAs != WordsAs.WRITTEN))

/**
 * How the words show, set here: how big they are, whether a script that reads otherwise shows as
 * written, in kana or in Latin letters, and what else helps read them, which is readings over them
 * for Japanese and Chinese, which Korean, read as written, doesn't need; Latin letters for all
 * three; and a translation, for any.
 */
@Composable
private fun LearnMenu(open: Boolean, learning: Learning, actions: ScreenActions) {
    if (!open) return
    var details by remember { mutableStateOf(false) }
    BackHandler(enabled = details) { details = false }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.55f
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth().testTag("lyrics-display-options")
    ) {
        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(16.dp)) {
            WordsOptions(learning, actions, details, { details = it })
        }
    }
}

/** The same display controls in lyrics and on either side of a practice card. */
@Composable
internal fun WordsOptions(learning: Learning, actions: ScreenActions, details: Boolean, onDetails: (Boolean) -> Unit, words: Boolean = true) {
    if (details) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDetails(false) }) {
                AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button))
            }
            Text(stringResource(R.string.lyrics_reading_options), style = MaterialTheme.typography.titleSmall)
        }
        val script = learning.script
        if (words && (script == Script.JAPANESE || script == Script.CHINESE) && learning.showsAs == WordsAs.WRITTEN) {
            ReadingSwitch(R.string.lyrics_readings, learning.readings, actions.onReadingsChange)
            if (learning.readings) {
                SizeSlider(R.string.lyrics_reading_size, learning.readingScale, MIN_READING_SCALE..MAX_READING_SCALE, actions.onReadingScaleChange)
            }
        }
        if (learning.showsAs != WordsAs.LATIN) ReadingSwitch(R.string.lyrics_romanized, learning.romanized, actions.onRomanizedChange)
        if (script == Script.JAPANESE && (learning.romanized || learning.showsAs == WordsAs.LATIN)) {
            ReadingSwitch(R.string.lyrics_romaji_typed, learning.romajiTyped, actions.onRomajiTypedChange)
        }
    } else {
        val script = learning.script
        if (words && script != null) {
            MenuCaption(R.string.lyrics_show_as)
            WordsAsPicker(script, learning.showsAs, actions.onWordsAsChange, learning.fromRomaji)
        }
        SizeSlider(R.string.lyrics_words_size, learning.wordsScale, MIN_WORDS_SCALE..MAX_WORDS_SCALE, actions.onWordsScaleChange)
        if (script != null && (learning.showsAs != WordsAs.LATIN || script == Script.JAPANESE)) {
            val readingLabel = stringResource(R.string.lyrics_reading_options)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onDetails(true) }
                    .semantics { contentDescription = readingLabel }
            ) {
                Text(stringResource(R.string.lyrics_reading_options), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(learning.readingSummary(stringResource(R.string.lyrics_readings_off)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppIcon(R.drawable.ic_chevron_right, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ReadingSwitch(R.string.lyrics_translation, learning.translation, actions.onTranslationChange)
    }
}

/** What is switched on, kept short enough to fit beside the way into its settings. */
internal fun Learning.readingSummary(off: String): String = buildList {
    if (readings && showsAs == WordsAs.WRITTEN && (script == Script.JAPANESE || script == Script.CHINESE)) {
        add(if (script == Script.JAPANESE) "あ" else "拼")
        add(NumberFormat.getPercentInstance().format(sizeShare(readingScale, MIN_READING_SCALE..MAX_READING_SCALE)))
    }
    if (romanized || showsAs == WordsAs.LATIN) add("Aa")
}.joinToString(" · ").ifEmpty { off }

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
internal fun SizeSlider(@StringRes label: Int, scale: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float, Boolean) -> Unit, @DrawableRes icon: Int = R.drawable.ic_format_size) {
    val size = stringResource(label)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
            Text(size, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(NumberFormat.getPercentInstance().format(sizeShare(scale, range)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        var latest by remember { mutableFloatStateOf(scale) }
        Slider(
            value = scale,
            onValueChange = {
                latest = it
                onChange(it, false)
            },
            onValueChangeFinished = { onChange(latest, true) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = size }
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
        MenuSwitch(R.string.lyrics_chords, state.chords.on, actions.onChordsChange)
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
 * A sheet kept clear of the status bar. Material's own pads its top by however much of the status
 * bar it's under, so one about as tall as the screen grew as it was dragged up, which moved it
 * again, and it shook.
 */
private val ClearOfStatusBar = Modifier.statusBarsPadding()

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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = ClearOfStatusBar) {
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
                // Found in romaji, the words as written are ChatGPT's.
                if (wordsAs == WordsAs.WRITTEN && fromRomaji) {
                    AppIcon(R.drawable.ic_translator_chatgpt, contentDescription = stringResource(R.string.lyrics_from_romaji), modifier = Modifier.padding(start = 4.dp).size(14.dp))
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
            AppSource(name, packageName, playing = state.following?.clock?.playing == true, onPlayPause = actions.onPlayPause)
        } else {
            ListenAlongButton(state.listeningAlong, actions)
        }
    }
}

/**
 * The music app the words follow, by its icon, or a note when it has none to show. Tapped, it plays
 * or pauses the song there, which a small badge on it shows.
 */
@Composable
private fun AppSource(name: String, packageName: String?, playing: Boolean, onPlayPause: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        packageName?.let { runCatching { context.packageManager.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    val description = stringResource(R.string.lyrics_following_app, name)
    // Not an IconButton, which clips to a circle and would cut the badge off at its corner.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onPlayPause)
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

/** The microphone: lit and beating while it listens along, plain while it doesn't. */
@Composable
private fun ListenAlongButton(listening: Boolean, actions: ScreenActions) {
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
                painterResource(R.drawable.ic_recognize),
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

/** MilkDrop visuals behind the words, lit while on: off, it turns them on; on, it opens what can be done with them. */
@Composable
private fun VisualsButton(on: Boolean, selected: Boolean = on, onClick: () -> Unit) {
    Box {
        LitButton(selected, R.drawable.ic_visuals, stringResource(if (on) R.string.visuals_options else R.string.visuals_show), onClick)
        if (on && !selected) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
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
 * What can be done with the visuals while they're on: how dark the ground over them is, how long
 * each shows, from swapping fast to staying on one, the next, never this one again, them alone, or off.
 */
@Composable
private fun VisualsMenu(open: Boolean, state: UiState, actions: ScreenActions, onOnlyVisuals: () -> Unit, onClose: () -> Unit) {
    if (!open) return
    MenuCard("lyrics-visuals-options") {
        // How dark the ground over them is, from none to nearly black.
        val darken = stringResource(R.string.visuals_shade)
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_shade, contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(22.dp))
                Text(darken, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    NumberFormat.getPercentInstance().format(sizeShare(state.visualsShade, 0f..MAX_VISUALS_SHADE)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // Where it was let go is kept, even before the menu has caught up with it.
            var latest by remember { mutableFloatStateOf(state.visualsShade) }
            Slider(
                value = state.visualsShade,
                onValueChange = {
                    latest = it
                    actions.onVisualsShadeChange(it, false)
                },
                onValueChangeFinished = { actions.onVisualsShadeChange(latest, true) },
                valueRange = 0f..MAX_VISUALS_SHADE,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = darken }
            )
        }
        VisualsChangeRow(state.visualsSettings.changeMs, actions.onVisualsChangeEveryChange, modifier = Modifier.fillMaxWidth(), inset = 0.dp)
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
internal fun MenuSwitch(@StringRes label: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Switch, onValueChange = onChange)
    ) {
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
private fun PlainLyrics(words: String, scale: Float) {
    Text(
        text = words,
        style = MaterialTheme.typography.titleLarge.run { copy(fontSize = fontSize * scale, lineHeight = 34.sp * scale) },
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
    fun sung() = following?.let { SyncedLyrics.indexAt(lines, it.clock.positionAt(SystemClock.elapsedRealtime())) } ?: -1
    val active by produceState(sung(), following, lines) {
        value = sung()
        while (following?.clock?.playing == true) value = withInfiniteAnimationFrameMillis { sung() }
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
    LaunchedEffect(active) {
        if (active >= 0) list.animateScrollToItem(active, scrollOffset = -third)
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize().fadedEdges()
    ) {
        itemsIndexed(lines) { index, line ->
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
                    following?.canSeek == true -> ({ actions.onSeekLyrics(line.timeMs) })
                    else -> null
                },
                seekLabel = if (syncing) R.string.lyrics_sync_line else R.string.lyrics_jump,
                onStudy = { onStudy(index) },
                help = state.learning.helpFor(index),
                textScale = state.learning.wordsScale
            )
        }
    }
}

/** Words with no timings, line by line, when each has something under it to help read it. */
@Composable
private fun LyricLines(state: UiState, onStudy: (Int) -> Unit) {
    val lines = state.lyrics.lines()
    val chords = rememberChords(state.chords, lines)
    LazyColumn(
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = BAR_ROOM),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize().fadedEdges()
    ) {
        itemsIndexed(lines) { index, line ->
            LyricLineText(
                line, place = null, chords = chords.getOrNull(index), onSeek = null, onStudy = { onStudy(index) }, help = state.learning.helpFor(index),
                textScale = state.learning.wordsScale
            )
        }
    }
}

/** Each of [texts]' chords while they show, worked out away from the screen, again whenever the lines change. */
@Composable
private fun rememberChords(chords: ChordsState, texts: List<String>): List<LineChords> {
    val sheet = chords.sheet?.takeIf { chords.on }
    val placed by produceState(emptyList<LineChords>(), sheet, texts) {
        value = sheet?.let { withContext(Dispatchers.Default) { ChordSheets.place(it, texts) } }.orEmpty()
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
    val romajiTyped: Boolean = false
) {
    /** The line in Latin letters, spelled as picked. */
    val latin get() = reading?.latin(romajiTyped)
}

internal fun Learning.helpFor(index: Int): LineHelp? {
    if (!shows) return null
    return LineHelp(lines.getOrNull(index), readings, romanized, translations.getOrNull(index).takeIf { translation }, insteadOf(index), readingScale, romajiTyped)
}


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
            if (synced) TextButton(onClick = actions.onResetSync) { Text(stringResource(R.string.lyrics_sync_reset)) }
            IconButton(onClick = onDone) { AppIcon(R.drawable.ic_check, contentDescription = stringResource(R.string.lyrics_sync_done)) }
        }
    }
}

/** A strip over the words: they can follow the music app playing the song, once it's allowed. */
@Composable
private fun FollowOffer(actions: ScreenActions, fixing: Boolean, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.lyrics_follow_allow), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OneTapButton(stringResource(R.string.follow_turn_on), actions.onAllowFollowing, fixing = fixing)
            IconButton(onClick = onDismiss) { AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.dismiss_button)) }
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
    textScale: Float
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
    val style = MaterialTheme.typography.headlineSmall.copy(
        fontSize = (if (place == null) 22.sp else 26.sp) * textScale,
        lineHeight = (if (place == null) 30.sp else 34.sp) * textScale,
        fontWeight = if (place == null) FontWeight.SemiBold else FontWeight.Bold,
        // Rows of about even length, broken between words, so no word or kana is left alone on the last.
        lineBreak = LineBreak.Heading
    ).writing(text)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LYRIC_LINE_TAG)
            .semantics(mergeDescendants = true) { selected = lit }
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
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
        val small = MaterialTheme.typography.bodyLarge.copy(fontSize = style.fontSize * 0.62f, lineHeight = style.lineHeight * 0.62f)
        // Shown in them already, they'd say it twice.
        if (help?.romanized == true) help.latin?.takeIf { it.isNotBlank() && it != instead }?.let {
            Text(it, style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        help?.translation?.let { Text(it, style = small, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** Where the song is while [following] it, checked every frame while it plays; null while not. */
@Composable
internal fun rememberPosition(following: Following?): Long? {
    if (following == null) return null
    fun now() = following.clock.positionAt(SystemClock.elapsedRealtime())
    val position by produceState(now(), following) {
        value = now()
        while (following.clock.playing) value = withInfiniteAnimationFrameMillis { now() }
    }
    return position
}

/** [words], those sung by [positionMs] lit and the rest dim. */
@Composable
private fun sungText(words: List<TimedWord>, positionMs: Long): AnnotatedString {
    val sung = SyncedLyrics.wordsSungBy(words, positionMs)
    val color = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        words.forEachIndexed { index, word -> withStyle(SpanStyle(color = if (index < sung) color else color.copy(alpha = UNSUNG_ALPHA))) { append(word.text) } }
    }
}

/** The line being sung, timed word by word: each word lights as it's sung. */
@Composable
private fun SungWords(words: List<TimedWord>, following: Following, style: TextStyle) {
    Text(sungText(words, rememberPosition(following) ?: 0), style = style)
}

/** How bright the words of the line being sung are before they're sung. */
private const val UNSUNG_ALPHA = 0.45f

/** The chords shown over a line, by name, for tests to see what's drawn. */
internal val ChordsShown = SemanticsPropertyKey<List<String>>("ChordsShown")

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
    val position = rememberPosition(following.takeIf { line.words.isNotEmpty() })
    // Read only where it's drawn, so a new chord played just draws it again.
    val playing = remember { mutableIntStateOf(-1) }
    playing.intValue = position?.let { ChordSheets.playing(line, chords.over, nextMs, it) } ?: -1
    val over = readingStyle(style, readingScale).copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val written = remember(ruby, readingScale, text) { ruby?.let { rubyText(it, readingScale) } ?: AnnotatedString(text) }
    val shown = when {
        position == null -> written
        // Words lit as sung, with the readings' spacing kept when the words are the same letters.
        ruby != null && line.words.joinToString("") { it.text } == text -> {
            val sung = SyncedLyrics.wordsSungBy(line.words, position)
            val litTo = line.words.take(sung).sumOf { it.text.length }
            buildAnnotatedString {
                append(written)
                addStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = UNSUNG_ALPHA)), litTo, written.length)
            }
        }
        // Words that don't spell the line's own letters would put the chords and readings over the wrong ones.
        ruby != null -> written
        else -> sungText(line.words, position)
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
internal fun RubyLine(parts: List<Ruby>, style: TextStyle, scale: Float = READING_SCALE) {
    val over = readingStyle(style, scale).copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val text = remember(parts, scale) { rubyText(parts, scale) }
    val measurer = rememberTextMeasurer()
    val room = with(LocalDensity.current) { over.lineHeight.toPx() }
    var words by remember { mutableStateOf<TextLayoutResult?>(null) }
    val shifts = words?.let { rowShifts(parts, it, room) }
    Text(
        text,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
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

/** The smallest words stay at three quarters of their starting size, labeled 1% on the slider. */
internal const val MIN_WORDS_SCALE = 0.75f

/** The room between a row's readings and the words under them, beside the words' size. */
private const val READING_GAP = 0.05f

/**
 * What a line can wrap between: a word in Latin letters with the space after it, or, since
 * Japanese and Chinese wrap anywhere, a single character (Korean, spaced, wraps by word), keeping the marks that can't start a
 * line, like 。 or ー or small kana, with the one before.
 */
private val WORDS = Regex("""[\p{IsLatin}\p{IsHangul}\p{N}'’.,!?:;&()\-]+\s*|\S[ーぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ・、。，．！？」』）〕】]*\s*|\s+""")

/**
 * The steps to letting Crosstune see what music apps play, each with the page it's done on. For an
 * app installed from a file, Android turns the first try down, and only then offers, in App info,
 * to allow restricted settings, after which the access turns on.
 */
@Composable
internal fun FollowHelp(state: UiState, actions: ScreenActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissFollowHelp,
        title = { Text(stringResource(R.string.follow_help_title)) },
        text = { FollowSteps(state.followRestricted, actions, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = actions.onDismissFollowHelp) { Text(stringResource(R.string.dismiss_button)) }
        }
    )
}

/**
 * The steps to the access, each with the button to the page it's done on: one, to turn it on, for
 * an app from a store; three where Android holds it back, as for an app installed from a file.
 */
@Composable
internal fun FollowSteps(restricted: Boolean, actions: ScreenActions, modifier: Modifier = Modifier) {
    val access = stringResource(R.string.follow_help_access_button)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = modifier) {
        if (restricted) {
            FollowStep(1, stringResource(R.string.follow_help_try), access, actions.onOpenFollowAccess, R.drawable.guide_access_restricted, R.drawable.guide_restricted_close)
            FollowStep(2, stringResource(R.string.follow_help_restricted), stringResource(R.string.follow_help_app_info), actions.onOpenAppInfo, R.drawable.guide_allow_restricted)
            FollowStep(3, stringResource(R.string.follow_help_access), access, actions.onOpenFollowAccess, R.drawable.guide_access_switch)
        } else {
            FollowStep(null, stringResource(R.string.follow_help_simple), access, actions.onOpenFollowAccess, R.drawable.guide_access_switch)
        }
    }
}

/** What to do, the button to the page it's done on, and a screenshot of that page with what to tap circled. */
@Composable
private fun FollowStep(number: Int?, text: String, button: String, onClick: () -> Unit, @DrawableRes vararg images: Int) {
    Row {
        // A step on its own needs no number.
        if (number != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Column(modifier = Modifier.padding(start = if (number != null) 14.dp else 0.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 10.dp)) { Text(button) }
            images.forEach { image -> GuideShot(image, Modifier.padding(top = 12.dp)) }
        }
    }
}

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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = ClearOfStatusBar) {
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
                if (state.following?.canSeek == true && index < state.lyricLines.size) {
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
 * artist. Songs like it are a tap away from any. Signed out of ChatGPT, it holds only where to
 * report a problem with the song's words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongSheet(state: UiState, actions: ScreenActions, tab: SongTab, onTab: (SongTab) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = ClearOfStatusBar) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
        ) {
            Text(state.lyricsFor?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
            // What ChatGPT says needs signing in with it; the way to report a problem doesn't.
            if (state.chatGpt.email != null) {
                PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
                    SongTab.entries.forEach { shown ->
                        Tab(selected = shown == tab, onClick = { onTab(shown) }, text = { Text(stringResource(shown.label)) })
                    }
                }
                if (tab == SongTab.MEANING) SongMeaning(state, actions)
                if (tab == SongTab.WORDS) WordsToKnow(state, actions)
                if (tab == SongTab.ARTIST) AboutArtist(state, actions)
                OutlinedButton(onClick = { state.lyricsFor?.let(actions.onSongsLike) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    AppIcon(R.drawable.ic_queue_music, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.lyrics_songs_like), modifier = Modifier.padding(start = 8.dp))
                }
            }
            // Wrong words, or out of time, said where they can be put right.
            var reporting by rememberSaveable { mutableStateOf(false) }
            TextButton(
                onClick = { reporting = true },
                modifier = Modifier.align(Alignment.Start).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)
            ) {
                AppIcon(R.drawable.ic_feedback, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.report_problem), modifier = Modifier.padding(start = 8.dp))
            }
            if (reporting) {
                ReportDialog(
                    Report(appVersion(), state.lyricsFor, timed = state.lyricLines.isNotEmpty(), synced = state.lyricsSynced, app = state.following?.app),
                    onDismiss = { reporting = false }
                )
            }
        }
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

