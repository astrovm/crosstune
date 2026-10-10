package com.astrovm.crosstune

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import kotlinx.coroutines.delay
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis

internal const val LYRIC_LINE_TAG = "lyric-line"
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
    // The line open to study, and whether the lines kept are shown, over the words.
    var studying by rememberSaveable(song) { mutableStateOf<Int?>(null) }
    var savedShown by rememberSaveable { mutableStateOf(false) }
    if (savedShown) {
        SavedLines(state.savedLines, actions, onBack = { savedShown = false })
        return
    }
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
    LaunchedEffect(scrolledAt) {
        if (scrolledAt == 0L) return@LaunchedEffect
        barShown = false
        delay(BAR_BACK_MS)
        barShown = true
    }
    val scrolling = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) scrolledAt = SystemClock.uptimeMillis()
                return Offset.Zero
            }
        }
    }
    WithVisuals(state, onlyVisuals, onShowWords = { visualsOnly = false }) { ground ->
    Surface(color = ground, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrolling)
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
            LyricsHeader(song.copy(artworkUrl = cover), actions)
            TranslationStatus(state.learning, actions)
            // Timed words with nothing saying where the song is: on top, how they can follow a music app.
            var followOffered by rememberSaveable { mutableStateOf(true) }
            AnimatedVisibility(
                visible = followOffered && state.lyricLines.isNotEmpty() && !state.canFollowApps && state.following == null && !state.listeningAlong
            ) {
                FollowOffer(actions, onDismiss = { followOffered = false })
            }
            AnimatedVisibility(visible = syncing) {
                SyncStrip(state.lyricsSynced, actions, onDone = { syncingAsked = false })
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Each state swaps in for the last, so the words arrive rather than appear.
                AnimatedContent(targetState = lyricsShown(state), transitionSpec = { fade() }, label = "lyrics") { shown ->
                    if (shown == LyricsShown.LOADING) {
                        LyricsMessage(stringResource(R.string.lyrics_loading), loading = true)
                    } else if (shown == LyricsShown.FAILED) {
                        LyricsMessage(stringResource(R.string.lyrics_failed)) {
                            FilledTonalButton(onClick = actions.onShowLyrics, modifier = Modifier.padding(top = 16.dp)) {
                                Text(stringResource(R.string.retry_button))
                            }
                        }
                    } else if (shown == LyricsShown.NONE) {
                        LyricsMessage(stringResource(R.string.lyrics_none))
                    } else if (shown == LyricsShown.TIMED) {
                        TimedLyrics(state, actions, timedList, syncing, onStudy = { studying = it })
                    } else if (state.learning.shows) {
                        // Read line by line, each with what helps read it.
                        LyricLines(state, onStudy = { studying = it })
                    } else {
                        PlainLyrics(state.lyrics)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = barShown,
            enter = slideInVertically { it * 2 } + fadeIn(),
            exit = slideOutVertically { it * 2 } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)
        ) {
            LyricsActions(
                state, actions, syncing, onOpenSaved = { savedShown = true }, onOnlyVisuals = { visualsOnly = true },
                onSync = { syncingAsked = !syncing }
            )
        }
    }
    }
    }
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
            MilkdropVisuals(state.visualsSkips, Modifier.fillMaxSize())
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

/** The song the words are of, the way back, and where their timing comes from. */
@Composable
private fun LyricsHeader(song: MusicMetadata, actions: ScreenActions) {
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
        AnimatedContent(targetState = song, transitionSpec = { swap() }, label = "lyrics song", modifier = Modifier.weight(1f)) { shown ->
            Row(verticalAlignment = Alignment.CenterVertically) {
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
    onOpenSaved: () -> Unit,
    onOnlyVisuals: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menu by remember { mutableStateOf<BarMenu?>(null) }
    Box(modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            shape = CircleShape,
            shadowElevation = 6.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                if (state.lyrics.isNotEmpty()) {
                    IconButton(onClick = { menu = BarMenu.LEARN }) { AppIcon(R.drawable.ic_translate, contentDescription = stringResource(R.string.lyrics_learn)) }
                }
                VisualsButton(state.visuals, onClick = { if (state.visuals) menu = BarMenu.VISUALS else actions.onVisualsChange(true) })
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
        // The menus open from the bar rather than from their button, so they line up with it and
        // keep off the screen's edge.
        val close = { menu = null }
        LearnMenu(menu == BarMenu.LEARN && state.lyrics.isNotEmpty(), state.learning, state.savedLines.isNotEmpty(), actions, onOpenSaved, close)
        VisualsMenu(menu == BarMenu.VISUALS && state.visuals, state.visualsShade, actions, onOnlyVisuals, close)
    }
}

private enum class BarMenu { LEARN, VISUALS }

/**
 * The words fade out at the top and bottom, rather than being cut off mid-line under the song or
 * the bar. At the bottom they're gone by the time they reach the bar, so they never clash with it.
 */
private fun Modifier.fadedEdges(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    val top = 32.dp.toPx() / size.height
    val bottom = (BAR_FADE.toPx() / size.height).coerceAtMost(1f - top)
    drawRect(Brush.verticalGradient(0f to Color.Transparent, top to Color.Black, 1f - bottom to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
}

/** How far down the darker shade behind the song reaches, below the phone's bar: just past the song. */
private val HEADER_SHADE = 72.dp

/** From just over the bar, 16dp up from the bottom and about 56dp tall, down to the bottom. */
private val BAR_FADE = 88.dp

/** The darkest the ground over the visuals goes, so they never vanish altogether. */
private const val MAX_VISUALS_SHADE = 0.9f

/** How long after the words stop being scrolled the bar comes back. */
private const val BAR_BACK_MS = 1_200L

/** Room under the words, so the last of them can be scrolled clear of the bar. */
private val BAR_ROOM = 112.dp

/** Whether anything that helps read the words is switched on, and so shown under them. */
private val Learning.shows get() = translation || (script != null && (readings || romanized))

/**
 * What helps read the words in another language, switched on and off here: readings over them for
 * Japanese and Chinese, which Korean, read as written, doesn't need; Latin letters for all three;
 * and a translation, for any.
 */
@Composable
private fun LearnMenu(open: Boolean, learning: Learning, anySaved: Boolean, actions: ScreenActions, onOpenSaved: () -> Unit, onClose: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onClose) {
        if (learning.script == Script.JAPANESE || learning.script == Script.CHINESE) {
            LearnItem(R.string.lyrics_readings, learning.readings, actions.onReadingsChange)
        }
        if (learning.script != null) LearnItem(R.string.lyrics_romanized, learning.romanized, actions.onRomanizedChange)
        LearnItem(R.string.lyrics_translation, learning.translation, actions.onTranslationChange)
        if (anySaved) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lyrics_saved_lines)) },
                leadingIcon = { AppIcon(R.drawable.ic_bookmark, contentDescription = null) },
                onClick = {
                    onClose()
                    onOpenSaved()
                }
            )
        }
    }
}

/** One of what can help, ticked while it's on; the menu stays open, to switch on more. */
@Composable
private fun LearnItem(@StringRes label: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        trailingIcon = { Checkbox(checked = on, onCheckedChange = null) },
        onClick = { onChange(!on) },
        modifier = Modifier.semantics { toggleableState = ToggleableState(on) }
    )
}

/** While the words are being translated, or why they couldn't be. */
@Composable
private fun TranslationStatus(learning: Learning, actions: ScreenActions) {
    val shown = learning.translation && (learning.translating || learning.translationFailed)
    AnimatedVisibility(visible = shown, enter = Motion.appear, exit = Motion.disappear) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(
                stringResource(
                    if (learning.translationFailed) R.string.lyrics_translation_failed else R.string.lyrics_translating
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
            )
            if (learning.translationFailed) TextButton(onClick = actions.onRetryTranslation) { Text(stringResource(R.string.retry_button)) }
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

/** MilkDrop visuals behind the words, lit while on: off, it turns them on; on, it offers them alone, or off. */
@Composable
private fun VisualsButton(on: Boolean, onClick: () -> Unit) =
    LitButton(on, R.drawable.ic_visuals, stringResource(if (on) R.string.visuals_options else R.string.visuals_show), onClick)

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

/** What can be done with the visuals while they're on: how dark the ground over them is, the next, them alone, or off. */
@Composable
private fun VisualsMenu(open: Boolean, shade: Float, actions: ScreenActions, onOnlyVisuals: () -> Unit, onClose: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onClose) {
        // How dark the ground over them is, from none to nearly black.
        val darken = stringResource(R.string.visuals_shade)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 16.dp).width(240.dp)) {
            AppIcon(R.drawable.ic_shade, contentDescription = null)
            // Where it was let go is kept, even before the menu has caught up with it.
            var latest by remember { mutableFloatStateOf(shade) }
            Slider(
                value = shade,
                onValueChange = {
                    latest = it
                    actions.onVisualsShadeChange(it, false)
                },
                onValueChangeFinished = { actions.onVisualsShadeChange(latest, true) },
                valueRange = 0f..MAX_VISUALS_SHADE,
                modifier = Modifier.padding(start = 12.dp).semantics { contentDescription = darken }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.visuals_next)) },
            leadingIcon = { AppIcon(R.drawable.ic_skip_next, contentDescription = null) },
            onClick = {
                onClose()
                actions.onNextVisual()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.visuals_only)) },
            leadingIcon = { AppIcon(R.drawable.ic_visuals, contentDescription = null) },
            onClick = {
                onClose()
                onOnlyVisuals()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.visuals_hide)) },
            leadingIcon = { AppIcon(R.drawable.ic_close, contentDescription = null) },
            onClick = {
                onClose()
                actions.onVisualsChange(false)
            }
        )
    }
}

/** Why there are no words to read yet, or at all. */
@Composable
private fun LyricsMessage(text: String, loading: Boolean = false, action: @Composable () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        if (loading) LoadingLines()
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 20.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        action()
    }
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
private fun PlainLyrics(words: String) {
    Text(
        text = words,
        style = MaterialTheme.typography.titleLarge.copy(lineHeight = 34.sp),
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
private fun TimedLyrics(state: UiState, actions: ScreenActions, list: LazyListState, syncing: Boolean, onStudy: (Int) -> Unit) {
    val lines = state.lyricLines
    val following = state.following
    val active = rememberSungLine(lines, following)
    val third = with(LocalDensity.current) { 160.dp.roundToPx() }
    LaunchedEffect(active) {
        if (active >= 0) list.animateScrollToItem(active, scrollOffset = -third)
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 240.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize().fadedEdges()
    ) {
        itemsIndexed(lines) { index, line ->
            LyricLineText(
                line.text,
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
                help = state.learning.helpFor(index)
            )
        }
    }
}

/** Words with no timings, line by line, when each has something under it to help read it. */
@Composable
private fun LyricLines(state: UiState, onStudy: (Int) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = BAR_ROOM),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize().fadedEdges()
    ) {
        itemsIndexed(state.lyrics.lines()) { index, line ->
            LyricLineText(line, place = null, onSeek = null, onStudy = { onStudy(index) }, help = state.learning.helpFor(index))
        }
    }
}

/** What helps read a line, as switched on. */
internal class LineHelp(val reading: LineReading?, val readings: Boolean, val romanized: Boolean, val translation: String?)

internal fun Learning.helpFor(index: Int): LineHelp? {
    if (!shows) return null
    return LineHelp(lines.getOrNull(index), readings, romanized, translations.getOrNull(index).takeIf { translation })
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
private fun FollowOffer(actions: ScreenActions, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.lyrics_follow_allow), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.onAllowFollowing) { Text(stringResource(R.string.follow_turn_on)) }
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
    onSeek: (() -> Unit)?,
    onStudy: () -> Unit,
    help: LineHelp? = null,
    waiting: Boolean = false,
    seekLabel: Int = R.string.lyrics_jump
) {
    val lit = place == 0
    val alpha by animateFloatAsState(
        if (place == null || waiting) 0.9f else if (lit) 1f else if (place < 0) 0.4f else 0.6f,
        spring(stiffness = Spring.StiffnessLow),
        label = "line alpha"
    )
    val scale by animateFloatAsState(if (lit || place == null) 1f else 0.94f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow), label = "line scale")
    val style = MaterialTheme.typography.headlineSmall.copy(
        fontSize = if (place == null) 22.sp else 26.sp,
        lineHeight = if (place == null) 30.sp else 34.sp,
        fontWeight = if (place == null) FontWeight.SemiBold else FontWeight.Bold
    )
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
        if (help?.readings == true && reading != null && reading.parts.any { it.reading != null }) {
            RubyLine(reading.parts, style)
        } else {
            // A gap with no words, e.g. a solo, is a note rather than an empty line.
            Text(text = text.ifBlank { "♪" }, style = style, color = MaterialTheme.colorScheme.onSurface)
        }
        val small = MaterialTheme.typography.bodyLarge.copy(fontSize = style.fontSize * 0.62f, lineHeight = style.lineHeight * 0.62f)
        if (help?.romanized == true) reading?.romanized?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        help?.translation?.let { Text(it, style = small, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/**
 * A line with each piece's reading over it, in small type, the way furigana sit over kanji. It
 * wraps between pieces, and between the words of a piece read as written.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RubyLine(parts: List<Ruby>, style: TextStyle) {
    val over = style.copy(fontSize = style.fontSize * 0.45f, lineHeight = style.fontSize * 0.5f, fontWeight = FontWeight.Medium)
    FlowRow {
        parts.forEach { part ->
            val reading = part.reading
            if (reading == null) {
                WORDS.findAll(part.text).forEach { word ->
                    Text(word.value, style = style, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.align(Alignment.Bottom))
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.Bottom)) {
                    Text(reading, style = over, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(part.text, style = style, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

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
            // Phones vary, which is said once, under the first step's screenshots.
            images.forEachIndexed { index, image ->
                val first = number == null || number == 1
                GuideShot(image, Modifier.padding(top = 12.dp), caption = if (first && index == images.lastIndex) stringResource(R.string.guide_may_differ) else null)
            }
        }
    }
}

/**
 * One line, open to study: what helps read it, its words to tap for their meaning, and, while the
 * words follow a music app, playing it over and over. It can be kept for later too.
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            // The line, once: each of its words underlined, to tap for its meaning, with its readings
            // over the kanji, as in the lyrics.
            val parts = reading?.parts?.takeIf { parts -> parts.any { it.reading != null } }
            if (parts != null) {
                TappableRubyLine(parts, words, state.word?.word, actions.onLookUpWord)
            } else {
                TappableLine(text.ifBlank { "♪" }, words, state.word?.word, actions.onLookUpWord)
            }
            reading?.romanized?.takeIf { it.isNotBlank() && it != reading.reading }?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            learning.translations.getOrNull(index)?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
            AnimatedContent(targetState = state.word, transitionSpec = { fade() }, label = "word") { meaning ->
                if (meaning != null) WordCard(meaning)
            }
            // Side by side, as wide and as tall as each other, however long their words are.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp).height(IntrinsicSize.Min)) {
                val button = Modifier.weight(1f).fillMaxHeight()
                // Only a music app the words follow, and that can be moved, can play a line again.
                if (state.following?.canSeek == true && index < state.lyricLines.size) {
                    val repeating = state.repeating == index
                    val content: @Composable RowScope.() -> Unit = {
                        AppIcon(R.drawable.ic_repeat_one, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(if (repeating) R.string.lyrics_stop_repeating else R.string.lyrics_repeat_line), modifier = Modifier.padding(start = 8.dp))
                    }
                    // Filled only while it repeats, like the Save button once saved looks done.
                    if (repeating) {
                        FilledTonalButton(onClick = actions.onStopRepeating, modifier = button, content = content)
                    } else {
                        OutlinedButton(onClick = { actions.onRepeatLine(index) }, modifier = button, content = content)
                    }
                }
                OutlinedButton(onClick = { actions.onToggleSavedLine(index) }, modifier = button) {
                    AppIcon(if (saved) R.drawable.ic_bookmark_added else R.drawable.ic_bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(if (saved) R.string.lyrics_line_saved else R.string.lyrics_save_line), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
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
    Text(line, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(STUDY_LINE_TAG))
}

/** [word] as a link to its meaning: underlined, or standing out in [highlight] once [picked]. */
private fun wordLink(word: Word, picked: Boolean, highlight: SpanStyle, onPick: (Word) -> Unit) =
    LinkAnnotation.Clickable(word.text, TextLinkStyles(if (picked) highlight else SpanStyle(textDecoration = TextDecoration.Underline))) { onPick(word) }

/**
 * [parts] of a line with their readings over them, wrapping as the lyrics do, each piece of one of
 * [words] a link to it. The word looked up stands out.
 */
@Composable
private fun TappableRubyLine(parts: List<Ruby>, words: List<Word>, picked: Word?, onPick: (Word) -> Unit) {
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
    val over = style.copy(fontSize = style.fontSize * 0.45f, lineHeight = style.fontSize * 0.5f, fontWeight = FontWeight.Medium)
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

/** A word tapped: how it reads, and what it means once found. */
@Composable
private fun WordCard(meaning: WordMeaning) {
    val word = meaning.word
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(word.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                listOfNotNull(word.reading, word.romanized?.takeIf { it != word.reading }).forEach {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (meaning.looking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } else if (meaning.failed) {
                Text(stringResource(R.string.lyrics_word_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } else if (meaning.meaning != null) {
                meaning.type?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(meaning.meaning, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            } else {
                Text(stringResource(R.string.lyrics_word_unknown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The lines kept, newest first, each with what helped read it and the song it's from. */
@Composable
private fun SavedLines(lines: List<SavedLine>, actions: ScreenActions, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // The last one removed, there's nothing left to show.
    LaunchedEffect(lines.isEmpty()) { if (lines.isEmpty()) onBack() }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)) {
                IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button)) }
                Text(stringResource(R.string.lyrics_saved_lines), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(lines, key = { "${it.title}|${it.artist}|${it.text}" }) { line ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 4.dp)) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                line.reading?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(line.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                line.romanized?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                line.translation?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                                Text(
                                    line.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Text(line.artist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { actions.onRemoveSavedLine(line) }) {
                                AppIcon(R.drawable.ic_delete, contentDescription = stringResource(R.string.remove_button))
                            }
                        }
                    }
                }
            }
        }
    }
}
