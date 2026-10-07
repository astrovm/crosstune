package com.astrovm.crosstune

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.delay

internal const val LYRIC_LINE_TAG = "lyric-line"

/**
 * A song's words, filling the screen in the color of its cover. Timed words follow the song while
 * something says where it is, the line being sung lit and kept in view, and a line tapped moves the
 * music app there; otherwise they read as a page.
 */
@Composable
internal fun LyricsScreen(state: UiState, actions: ScreenActions) {
    val song = state.lyricsFor ?: return
    BackHandler(onBack = actions.onDismissLyrics)
    val tint by animateColorAsState(
        coverColor(song.artworkUrl, actions.loadArtwork) ?: MaterialTheme.colorScheme.primary,
        tween(durationMillis = 700),
        label = "lyrics tint"
    )
    val surface = MaterialTheme.colorScheme.surface
    Surface(color = surface, modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to tint.copy(alpha = 0.5f), 0.55f to tint.copy(alpha = 0.12f), 1f to surface))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .align(Alignment.TopCenter)
                .widthIn(max = ContentMaxWidth)
        ) {
            LyricsHeader(song, actions)
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
                        TimedLyrics(state, actions)
                    } else {
                        PlainLyrics(state.lyrics)
                    }
                }
            }
            FollowBar(state, actions)
        }
    }
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

/** The song the words are of, and the way back to it. */
@Composable
private fun LyricsHeader(song: MusicMetadata, actions: ScreenActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
    ) {
        IconButton(onClick = actions.onDismissLyrics) {
            AppIcon(R.drawable.ic_expand_more, contentDescription = stringResource(R.string.dismiss_button))
        }
        CoverArt(song.artworkUrl, actions.loadArtwork, size = 48.dp, modifier = Modifier.padding(start = 4.dp))
        Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
            // The words are the point, so they read as one block rather than by line.
            .semantics(mergeDescendants = true) {}
    )
}

/**
 * Timed words, one line at a time. While the song is followed the line being sung is lit, those
 * sung dim behind it, and the list glides to keep it a third of the way down; tapping a line moves
 * the music app there when it can.
 */
@Composable
private fun TimedLyrics(state: UiState, actions: ScreenActions) {
    val lines = state.lyricLines
    val following = state.following
    val now by produceState(SystemClock.elapsedRealtime(), following) {
        // Only a song that's playing moves on, so a paused one isn't checked over and over.
        while (following?.clock?.playing == true) {
            value = SystemClock.elapsedRealtime()
            delay(TICK_MS)
        }
        value = SystemClock.elapsedRealtime()
    }
    val active = following?.let { SyncedLyrics.indexAt(lines, it.clock.positionAt(now)) } ?: -1
    val list = rememberLazyListState()
    val third = with(LocalDensity.current) { 160.dp.roundToPx() }
    LaunchedEffect(active) {
        if (active >= 0) list.animateScrollToItem(active, scrollOffset = -third)
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 240.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(lines) { index, line ->
            LyricLineText(
                line.text,
                place = if (following == null) 0 else index.compareTo(active),
                canSeek = following?.canSeek == true,
                onClick = { actions.onSeekLyrics(line.timeMs) }
            )
        }
    }
}

/**
 * One line, by its [place]: the one being sung (0, or every line while nothing is followed), one
 * already sung (below 0), or one to come.
 */
@Composable
private fun LyricLineText(text: String, place: Int, canSeek: Boolean, onClick: () -> Unit) {
    val lit = place == 0
    val alpha by animateFloatAsState(if (lit) 1f else if (place < 0) 0.4f else 0.55f, spring(stiffness = Spring.StiffnessLow), label = "line alpha")
    val scale by animateFloatAsState(if (lit) 1f else 0.94f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow), label = "line scale")
    Text(
        // A gap with no words, e.g. a solo, is a note rather than an empty line.
        text = text.ifBlank { "♪" },
        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LYRIC_LINE_TAG)
            .semantics { selected = lit }
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .then(if (canSeek) Modifier.clickable(onClickLabel = stringResource(R.string.lyrics_jump), onClick = onClick) else Modifier)
            .padding(vertical = 6.dp)
    )
}

/**
 * Under timed words, only while there's something to say: what they follow, for a moment once
 * they start to, with a dot that beats while it plays; how to have them follow the song, until it's
 * done or put away; or, briefly, to play it in a music app.
 */
@Composable
private fun FollowBar(state: UiState, actions: ScreenActions) {
    val timed = state.lyricLines.isNotEmpty() && !state.isLoadingLyrics && !state.lyricsFailed
    val following = state.following
    val shown = when {
        following != null -> FollowShown.FOLLOWING
        !state.canFollowApps -> FollowShown.ALLOW
        else -> FollowShown.PLAY
    }
    // Saying what they follow, or to play the song, is news once; after that the words say it.
    var fresh by remember(shown, following?.app) { mutableStateOf(true) }
    LaunchedEffect(shown, following?.app) {
        delay(ANNOUNCE_MS)
        fresh = false
    }
    var putAway by rememberSaveable { mutableStateOf(false) }
    val visible = timed && if (shown == FollowShown.ALLOW) !putAway else fresh
    AnimatedVisibility(visible = visible, enter = Motion.appear, exit = Motion.disappear) {
        AnimatedContent(targetState = shown to following?.app, transitionSpec = { fade() }, label = "follow bar") { (now, app) ->
            if (now == FollowShown.FOLLOWING) {
                // A small tag, centered, rather than a bar: it's only saying so.
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Beat(playing = following?.clock?.playing != false)
                            Spacer(Modifier.size(10.dp))
                            Text(
                                if (app != null) stringResource(R.string.lyrics_following_app, app) else stringResource(R.string.lyrics_following_heard),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 48.dp)) {
                        if (now == FollowShown.ALLOW) {
                            Text(stringResource(R.string.lyrics_follow_allow), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = actions.onAllowFollowing) { Text(stringResource(R.string.allow_button)) }
                            IconButton(onClick = { putAway = true }) {
                                AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.not_now_button), modifier = Modifier.size(20.dp))
                            }
                        } else {
                            Text(stringResource(R.string.lyrics_follow_play), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

private enum class FollowShown { FOLLOWING, ALLOW, PLAY }

/**
 * The steps to letting Crosstune see what music apps play, each with the page it's done on. For an
 * app installed from a file, Android turns the first try down, and only then offers, in App info,
 * to allow restricted settings, after which the access turns on.
 */
@Composable
internal fun FollowHelp(actions: ScreenActions) {
    val access = stringResource(R.string.follow_help_access_button)
    AlertDialog(
        onDismissRequest = actions.onDismissFollowHelp,
        title = { Text(stringResource(R.string.follow_help_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                FollowStep(1, stringResource(R.string.follow_help_try), access, actions.onOpenFollowAccess)
                FollowStep(2, stringResource(R.string.follow_help_restricted), stringResource(R.string.follow_help_app_info), actions.onOpenAppInfo)
                FollowStep(3, stringResource(R.string.follow_help_access), access, actions.onOpenFollowAccess)
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onDismissFollowHelp) { Text(stringResource(R.string.dismiss_button)) }
        }
    )
}

@Composable
private fun FollowStep(number: Int, text: String, button: String, onClick: () -> Unit) {
    Row {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 10.dp)) { Text(button) }
        }
    }
}

/** A dot in the accent color, beating while the song plays and still while it's paused. */
@Composable
private fun Beat(playing: Boolean) {
    val transition = rememberInfiniteTransition(label = "beat")
    val pulse by transition.animateFloat(0.7f, 1.15f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "pulse")
    Box(
        modifier = Modifier
            .size(10.dp)
            .graphicsLayer {
                val beat = if (playing) pulse else 1f
                scaleX = beat
                scaleY = beat
            }
            .background(if (playing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
    )
}

/** How long saying what the words follow, or to play the song, stays up. */
private const val ANNOUNCE_MS = 4_000L

/** How often a playing song's line is checked: well under a beat, so a line lights as it's sung. */
private const val TICK_MS = 80L
