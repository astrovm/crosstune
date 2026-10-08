package com.astrovm.crosstune

import android.os.SystemClock
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
            LyricsHeader(song, state, actions)
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

/** The song the words are of, the way back, and where their timing comes from. */
@Composable
private fun LyricsHeader(song: MusicMetadata, state: UiState, actions: ScreenActions) {
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
        SyncSource(state, actions)
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
            AppSource(name, packageName)
        } else {
            ListenAlongButton(state.listeningAlong, actions)
        }
    }
}

/** The music app the words follow, by its icon, or a note when it has none to show. */
@Composable
private fun AppSource(name: String, packageName: String?) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        packageName?.let { runCatching { context.packageManager.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    val description = stringResource(R.string.lyrics_following_app, name)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
        if (icon != null) Image(icon, null, Modifier.size(28.dp).clip(CircleShape)) else Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(22.dp))
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
    fun sung() = following?.let { SyncedLyrics.indexAt(lines, it.clock.positionAt(SystemClock.elapsedRealtime())) } ?: -1
    // Checked every frame while the song plays, so a line lights as it's sung; the list only changes
    // when the line does. A paused song stays where it is.
    val active by produceState(sung(), following, lines) {
        value = sung()
        while (following?.clock?.playing == true) value = withInfiniteAnimationFrameMillis { sung() }
    }
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
                place = following?.let { index.compareTo(active) },
                canSeek = following?.canSeek == true,
                onClick = { actions.onSeekLyrics(line.timeMs) }
            )
        }
        // Nothing says where the song is: after the words, how they can follow a music app.
        if (following == null && !state.listeningAlong && !state.canFollowApps) {
            item { FollowOffer(actions) }
        }
    }
}

/** A quiet line after the words, not over them: they can follow the music app playing the song. */
@Composable
private fun FollowOffer(actions: ScreenActions) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Text(
            stringResource(R.string.lyrics_follow_allow),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = actions.onAllowFollowing) { Text(stringResource(R.string.allow_button)) }
    }
}

/**
 * One line, by its [place]: the one being sung (0), one already sung (below 0), or one to come; or
 * null while nothing says where the song is, when every line reads alike, calmer, as a page.
 */
@Composable
private fun LyricLineText(text: String, place: Int?, canSeek: Boolean, onClick: () -> Unit) {
    val lit = place == 0
    val alpha by animateFloatAsState(
        if (place == null) 0.9f else if (lit) 1f else if (place < 0) 0.4f else 0.55f,
        spring(stiffness = Spring.StiffnessLow),
        label = "line alpha"
    )
    val scale by animateFloatAsState(if (lit || place == null) 1f else 0.94f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow), label = "line scale")
    Text(
        // A gap with no words, e.g. a solo, is a note rather than an empty line.
        text = text.ifBlank { "♪" },
        style = MaterialTheme.typography.headlineSmall.copy(
            fontSize = if (place == null) 22.sp else 26.sp,
            lineHeight = if (place == null) 30.sp else 34.sp,
            fontWeight = if (place == null) FontWeight.SemiBold else FontWeight.Bold
        ),
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
