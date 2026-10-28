package com.astrovm.crosstune

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal const val PLAYER_COVER_TAG = "player-cover"

/**
 * Crosstune's player: the song playing in its music app, behind, with its cover, where it is, and
 * the app's buttons, as long as Crosstune can follow it there; its words open over it. What plays
 * next opens under the buttons, the cover moving up beside the song's name to make room for it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PlayerScreen(state: UiState, actions: ScreenActions) {
    val player = state.player ?: return
    val song = player.song
    // A song the app went on to often comes without a cover, which is then the result's, when it's that song.
    val cover = song.artworkUrl ?: state.result?.takeIf { SongNames.same(it.title, song.title) && SongNames.sameArtist(it.artist, song.artist) }?.artworkUrl
    val following = player.following
    val app = following?.app ?: MusicService.ofPackage(player.app)?.let { stringResource(it.labelRes) }
    BackHandler(onBack = actions.onClosePlayer)
    // What plays next, opened from the list it plays from up top or its button under the buttons.
    val listed = following?.takeIf { it.from != null || it.upNext.isNotEmpty() }
    var upNext by rememberSaveable { mutableStateOf(false) }
    // Gone with the list, it stays gone, rather than open again over the next one.
    LaunchedEffect(listed == null) { if (listed == null) upNext = false }
    val open = upNext && listed != null
    // Pulled down, or swiped back from, the player follows toward it put away, then carries on or goes back once let go.
    val pull = remember { UpNextPull() }
    // Open, Back puts it away before the player.
    PredictiveBackHandler(enabled = open) { swipe ->
        try {
            swipe.collect { pull.fraction = it.progress }
            pull.fraction = null
            upNext = false
        } catch (e: CancellationException) {
            pull.fraction = null
            throw e
        }
    }
    val fromTop = remember { FocusRequester() }
    val fromTools = remember { FocusRequester() }
    // Which of them opened it, null till one did, so the remote is back there once it's put away,
    // rather than anywhere, as the song it was on went.
    var openedUpTop by remember { mutableStateOf<Boolean?>(null) }
    val openUpNext = { upTop: Boolean -> openedUpTop = upTop; upNext = true }
    val morphing = remember { SeekableTransitionState(open) }
    val morph = rememberTransition(morphing, label = "up next")
    LaunchedEffect(open, pull.fraction) {
        val far = pull.fraction
        when {
            far != null -> morphing.seekTo(far, targetState = false)
            // Let go short of put away, it goes back the way it came.
            morphing.currentState == open && morphing.targetState != open -> {
                animate(morphing.fraction, 0f, animationSpec = tween((morphing.fraction * MORPH_MS).toInt())) { value, _ -> launch { morphing.seekTo(value) } }
                morphing.snapTo(open)
            }
            else -> morphing.animateTo(open)
        }
    }
    LaunchedEffect(morph.currentState) {
        val upTop = openedUpTop ?: return@LaunchedEffect
        // Once it's settled, as till then the parts it's on are still on their way.
        if (morph.currentState || listed == null) return@LaunchedEffect
        (if (upTop && listed.from != null) fromTop else fromTools).requestFocus()
    }
    // The words, but a video's, as its clock may be the video's; blank ones, between verses, leave the last one up.
    val words = remember(player.lines, following?.video) { player.lines.takeIf { following?.video != true }.orEmpty().filter { it.text.isNotBlank() } }
    // One whose artist is unknown can't be looked up safely, so it has no words to show.
    val hasWords = song.artist.isNotBlank()
    // Its card is under the player, so what it has is a menu away, not another screen.
    val card = state.result?.let { SongNames.same(it.title, song.title) && SongNames.sameArtist(it.artist, song.artist) } == true
    val ground = MaterialTheme.colorScheme.surface
    val tint by animateColorAsState(
        coverColor(cover, actions.loadArtwork) ?: MaterialTheme.colorScheme.primary,
        tween(durationMillis = 700),
        label = "player tint"
    )
    val topBar: @Composable (Morph, Boolean) -> Unit = { moving, opened ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
            IconButton(onClick = actions.onClosePlayer, modifier = Modifier.moves("close", moving)) {
                AppIcon(R.drawable.ic_expand_more, contentDescription = stringResource(R.string.dismiss_button))
            }
            // The list it plays from, up top as music apps keep it, which opens what plays next too; open,
            // the list is named over its songs instead.
            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f)) {
                if (!opened) listed?.from?.let { PlayingFrom(it, Modifier.focusRequester(fromTop)) { openUpNext(true) } }
            }
            // Its place is kept without it, so the list's name stays in the middle.
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).moves("menu", moving)) {
                if (card) PlayerMenu(ready = !state.isLoading && !state.isMatching, actions)
            }
        }
    }
    val coverArt: @Composable (Modifier, Morph) -> Unit = { modifier, moving ->
        BoxWithConstraints(contentAlignment = Alignment.Center, modifier = modifier) {
            // As big as the rest leaves room for: bigger text takes more of it, so the cover gives way,
            // all of it once too little is left to make it out, which also leaves the swipes the whole screen.
            val size = minOf(maxWidth, maxHeight, PLAYER_COVER_MAX)
            if (size >= PLAYER_COVER_MIN) SwipedCover(following, actions.onPlayerSkip) { CoverArt(cover, actions.loadArtwork, size = size, modifier = it.moves("cover", moving)) }
        }
    }
    val titleRow: @Composable (Morph, (@Composable () -> Unit)?) -> Unit = { moving, lead ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            lead?.invoke()
            // A tap on its name opens its card, with its links, as music apps open a song's page. One line
            // each, so the cover keeps its size from song to song; a longer name scrolls by.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .moves("names", moving)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = stringResource(R.string.player_song_card), onClick = actions.onPlayerCard)
                    .padding(vertical = 8.dp)
            ) {
                Text(song.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.basicMarquee())
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            following?.like?.let { LikeButton(it, following.appPackage, actions.onPlayerLike, Modifier.moves("like", moving)) }
        }
    }
    val transport: @Composable (Morph) -> Unit = { moving ->
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().moves("progress", moving).padding(top = 8.dp).heightIn(min = 64.dp)) {
            when {
                !state.canFollowApps -> PlayerFollowOffer(actions, fixing = state.fixingInOneTap == OneTapFix.FOLLOW)
                following != null -> PlayerProgress(following, actions.onPlayerSeek)
                // Until the app plays it, once; stopped there after, there's nothing to wait for.
                !player.played && app != null -> PlayerWaiting(app)
            }
        }
        PlayerButtons(following, actions, Modifier.moves("buttons", moving))
    }
    // The lesser tools, small, under the buttons: where it plays on one side, the rest on the other.
    val tools: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // Where it plays, by its icon, which brings that app forward. The app it's followed in, as its name says, over the one it was asked of.
            val service = MusicService.ofPackage(following?.appPackage ?: player.app)
            val playingIn = app?.let { stringResource(R.string.player_playing_in, it) }
            if (service != null) IconButton(onClick = actions.onPlayerOpenApp, modifier = Modifier.then(if (playingIn != null) Modifier.semantics { contentDescription = playingIn } else Modifier)) {
                DestinationIcon(Destination.Service(service), setOf(service), size = 24.dp)
            }
            Spacer(Modifier.weight(1f))
            // With no line to show, the words are a button here instead.
            if (hasWords && words.isEmpty()) IconButton(onClick = actions.onPlayerLyrics) {
                AppIcon(R.drawable.ic_lyrics, contentDescription = stringResource(R.string.lyrics_button))
            }
            SleepButton(state.sleep, songEnds = following?.durationMs != null, actions)
            if (listed != null) IconButton(onClick = { openUpNext(false) }, modifier = Modifier.focusRequester(fromTools)) {
                AppIcon(R.drawable.ic_queue_music, contentDescription = stringResource(R.string.player_up_next))
            }
        }
        if (hasWords && words.isNotEmpty()) LyricsPeek(words, following, tint, actions.onPlayerLyrics, Modifier.padding(top = 8.dp))
    }
    val queue: @Composable (Modifier, Morph) -> Unit = { modifier, moving ->
        // Only while it's open, which its list being there says.
        if (listed != null) {
            UpNextList(
                player, cover, listed, actions, pull, onClose = { upNext = false },
                // All the way, so pulled down, it goes with the finger.
                modifier = with(moving.content) { modifier.animateEnterExit(enter = slideInVertically { it }, exit = slideOutVertically { it }) }
            )
        }
    }
    Surface(color = ground, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to tint.copy(alpha = 0.5f), 0.55f to tint.copy(alpha = 0.12f), 1f to ground))
                // Up for its words, down from the top to put it away.
                .swipeLayers(downFromTop = true)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Wider than tall, as a TV or a phone on its side, the cover sits beside the rest, with no room under it.
            val beside = maxWidth > maxHeight
            SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                morph.AnimatedContent(
                    transitionSpec = { fadeIn(tween(MORPH_MS, delayMillis = MORPH_MS / 3)) togetherWith fadeOut(tween(MORPH_MS / 3)) },
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                ) { opened ->
                    val moving = Morph(this@SharedTransitionLayout, this)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize().widthIn(max = if (beside) PLAYER_BESIDE_MAX_WIDTH else ContentMaxWidth)
                    ) {
                        topBar(moving, opened)
                        when {
                            beside -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(horizontal = 24.dp)) {
                                coverArt(Modifier.weight(1f).fillMaxHeight(), moving)
                                Column(modifier = Modifier.weight(1f).padding(start = 24.dp).then(if (opened) Modifier.fillMaxHeight() else Modifier)) {
                                    titleRow(moving, null)
                                    transport(moving)
                                    if (opened) queue(Modifier.weight(1f), moving) else tools()
                                }
                            }
                            // The cover beside the song's name, small, with what plays next under the buttons; a tap on it puts that away.
                            opened -> Column(modifier = Modifier.weight(1f).padding(horizontal = 24.dp).widthIn(max = PLAYER_COVER_MAX)) {
                                titleRow(moving) {
                                    CoverArt(
                                        cover, actions.loadArtwork, size = PLAYER_COVER_SMALL,
                                        modifier = Modifier
                                            .moves("cover", moving)
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable(onClickLabel = stringResource(R.string.dismiss_button)) { upNext = false }
                                    )
                                    Spacer(Modifier.width(16.dp))
                                }
                                transport(moving)
                                queue(Modifier.weight(1f), moving)
                            }
                            else -> {
                                coverArt(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), moving)
                                // As wide as the cover can be, so its edges, the progress bar's and the times' line up.
                                Column(modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = PLAYER_COVER_MAX)) {
                                    titleRow(moving, null)
                                    transport(moving)
                                    tools()
                                    Spacer(Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The scopes the player's parts move in, from where they were to where they go, as what plays next opens or closes. */
@OptIn(ExperimentalSharedTransitionApi::class)
private class Morph(val layout: SharedTransitionScope, val content: AnimatedVisibilityScope)

/** What plays next pulled toward put away, by a finger or a swipe back: how far, 0 to 1, while one has it. */
@Stable
private class UpNextPull {
    var fraction by mutableStateOf<Float?>(null)
    /** How far a finger pulls it all the way, in pixels: all of it, so the finger and it go together. */
    var height = 1f

    /** Pulls it [px] further down, or back up, and returns how much of that it took. */
    fun by(px: Float): Float {
        val from = fraction ?: 0f
        val to = (from + px / height).coerceIn(0f, 1f)
        fraction = to
        return (to - from) * height
    }

    /** Let go at [velocity], down positive: whether it carries on to put away, far enough along or flicked down. */
    fun letGo(velocity: Float, flick: Float): Boolean {
        val far = fraction ?: return false
        fraction = null
        return velocity >= flick || (velocity > -flick && far >= PULL_COMMIT)
    }
}

/** The part of the player called [key], moving from where it was, as what plays next opens or closes. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.moves(key: String, morph: Morph): Modifier = with(morph.layout) {
    this@moves.sharedElement(rememberSharedContentState(key), morph.content)
}

/** Copy, share or open the song's link elsewhere, from the song's card under the player. */
@Composable
private fun PlayerMenu(ready: Boolean, actions: ScreenActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            AppIcon(R.drawable.ic_more_vert, contentDescription = stringResource(R.string.more_button))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy_link_button)) },
                leadingIcon = { AppIcon(R.drawable.ic_content_copy, contentDescription = null) },
                enabled = ready,
                onClick = { expanded = false; actions.onCopyLink() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_link_button)) },
                leadingIcon = { AppIcon(R.drawable.ic_share, contentDescription = null) },
                enabled = ready,
                onClick = { expanded = false; actions.onShareSearch() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.picker_title)) },
                leadingIcon = { AppIcon(R.drawable.ic_open_in_new, contentDescription = null) },
                onClick = { expanded = false; actions.onPlayerOpenWith() }
            )
        }
    }
}

/** Where the song is, to drag elsewhere when the app lets it, with how long it is when the app says. */
@Composable
private fun PlayerProgress(following: Following, onSeek: (Long) -> Unit) {
    val position = rememberPosition(following) ?: 0L
    val duration = following.durationMs
    // Where it's dragged to, held there until the app says it moved, or a moment passes without it saying.
    var dragged by remember { mutableStateOf<Float?>(null) }
    var seeking by remember { mutableStateOf(false) }
    LaunchedEffect(following) {
        if (seeking) {
            seeking = false
            dragged = null
        }
    }
    LaunchedEffect(seeking) {
        if (!seeking) return@LaunchedEffect
        delay(SEEK_HOLD_MS)
        seeking = false
        dragged = null
    }
    val shown = dragged?.toLong() ?: position
    Column(modifier = Modifier.fillMaxWidth()) {
        if (duration != null) {
            val description = stringResource(R.string.player_position, clockText(shown), clockText(duration))
            AppSlider(
                value = shown.coerceIn(0L, duration).toFloat(),
                onValueChange = { dragged = it },
                onValueChangeFinished = {
                    dragged?.let { onSeek(it.toLong()) }
                    seeking = true
                },
                valueRange = 0f..duration.toFloat(),
                enabled = following.canSeek,
                modifier = Modifier.fillMaxWidth().slidesSideways().semantics { stateDescription = description }
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(clockText(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                duration?.let(::clockText).orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Shuffle, previous, play or pause, next, and repeat, as the app lets them; play asks the app again when it isn't playing the song. */
@Composable
private fun PlayerButtons(following: Following?, actions: ScreenActions, modifier: Modifier) {
    val playing = following?.clock?.playing == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        // Whether the app shuffles, left of previous as music apps have it. Its place is kept without it,
        // so the rest doesn't move once the app says.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            following?.shuffle?.let { shuffles ->
                ModeButton(shuffles, R.drawable.ic_shuffle, stringResource(R.string.player_shuffle), how = null, actions.onPlayerShuffle)
            }
        }
        IconButton(onClick = { actions.onPlayerSkip(false) }, enabled = following?.canSkipPrevious == true, modifier = Modifier.size(56.dp)) {
            AppIcon(R.drawable.ic_skip_previous, contentDescription = stringResource(R.string.lyrics_previous_song), modifier = Modifier.size(32.dp))
        }
        FilledIconButton(onClick = actions.onPlayerPlayPause, modifier = Modifier.size(72.dp)) {
            AppIcon(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                contentDescription = stringResource(if (playing) R.string.lyrics_pause else R.string.lyrics_play),
                modifier = Modifier.size(36.dp)
            )
        }
        IconButton(onClick = { actions.onPlayerSkip(true) }, enabled = following?.canSkipNext == true, modifier = Modifier.size(56.dp)) {
            AppIcon(R.drawable.ic_skip_next, contentDescription = stringResource(R.string.lyrics_next_song), modifier = Modifier.size(32.dp))
        }
        // How the app repeats, right of next as music apps have it.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            following?.repeat?.let { repeats ->
                val way = when (repeats) {
                    Repeat.OFF -> null
                    Repeat.ALL -> R.string.player_repeat_all
                    Repeat.ONE -> R.string.player_repeat_one
                }
                ModeButton(
                    repeats != Repeat.OFF, if (repeats == Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                    stringResource(R.string.player_repeat), how = way?.let { stringResource(it) }, actions.onPlayerRepeat
                )
            }
        }
    }
}

/**
 * Shuffle or repeat: lit in the app's color with a dot under it while on, as music apps show it, the
 * dot there as a color alone is easy to miss; [how] says which way it's on.
 */
@Composable
private fun ModeButton(on: Boolean, @DrawableRes icon: Int, name: String, how: String?, onToggle: () -> Unit) {
    val tint by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "mode")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics {
                contentDescription = name
                if (how != null) stateDescription = how
            }
    ) {
        AppIcon(icon, contentDescription = null, tint = tint)
        if (on) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(4.dp).background(tint, CircleShape))
    }
}

/**
 * The cover, which a swipe sideways skips from, as music apps have it: left for the next song,
 * right for the one before. It follows the finger, held back where the app can't go.
 */
@Composable
private fun SwipedCover(following: Following?, onSkip: (next: Boolean) -> Unit, cover: @Composable (Modifier) -> Unit) {
    val moved = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val canNext = following?.canSkipNext == true
    val canPrevious = following?.canSkipPrevious == true
    cover(
        Modifier
            .testTag(PLAYER_COVER_TAG)
            .graphicsLayer {
                translationX = moved.value
                alpha = 1f - (abs(moved.value) / size.width).coerceAtMost(1f) / 2
            }
            .pointerInput(canNext, canPrevious) {
                val skipAt = size.width * COVER_SKIP_AT
                // Back in place once let go, or once another gesture takes the finger.
                val settle: () -> Unit = { scope.launch { moved.animateTo(0f) } }
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val next = moved.value < 0
                        if (abs(moved.value) >= skipAt && (if (next) canNext else canPrevious)) onSkip(next)
                        settle()
                    },
                    onDragCancel = settle
                ) { change, amount ->
                    change.consume()
                    val can = if (moved.value + amount < 0) canNext else canPrevious
                    scope.launch { moved.snapTo(moved.value + if (can) amount else amount * COVER_HELD_BACK) }
                }
            }
    )
}

/** The list the song plays from, when Crosstune played it, by name, which opens what plays next. */
@Composable
private fun PlayingFrom(from: String, modifier: Modifier, onOpen: () -> Unit) {
    val upNext = stringResource(R.string.player_up_next)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = upNext, onClick = onOpen)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$upNext, $from" }
    ) {
        AppIcon(R.drawable.ic_queue_music, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(from, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * What plays next, under the player's buttons: the song playing, then the app's queue, then what
 * Crosstune asks it for after, each with its cover. A tap plays one, the list staying open to show it
 * start; a song is dragged by its handle, or held, into another place. Pulled down by its handle or
 * name, or past the top of its songs, the player follows the finger toward it put away; tapped on
 * its handle, it's put away.
 */
@Composable
private fun UpNextList(player: PlayerState, cover: String?, following: Following, actions: ScreenActions, pull: UpNextPull, onClose: () -> Unit, modifier: Modifier) {
    // Moved here at once, so a song dragged follows the finger; the order kept behind catches up after.
    var rows by remember(following.upNext) { mutableStateOf(following.upNext) }
    val keys = remember(rows) { keysOf(rows) }
    // The song tapped, till it plays, or a while passes without it.
    var asked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(asked) {
        if (asked == null) return@LaunchedEffect
        delay(ASKED_FOR_MS)
        asked = null
    }
    val haptics = LocalHapticFeedback.current
    // The app's songs move among themselves, and a list's still to ask for among theirs.
    fun move(from: Int, to: Int): Boolean {
        if (to !in rows.indices || (rows[from].id == null) != (rows[to].id == null)) return false
        rows = rows.toMutableList().apply { add(to, removeAt(from)) }
        actions.onPlayerMoveQueued(from, to)
        return true
    }
    val list = rememberLazyListState()
    // The song playing comes first, so the rest are one further down.
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        if (move(from.index - 1, to.index - 1)) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    // With a remote, it starts on the next song, so OK plays it.
    val first = remember { FocusRequester() }
    if (rows.isNotEmpty()) LaunchedEffect(Unit) {
        // Once the list has laid it out.
        withFrameNanos {}
        first.requestFocus()
    }
    val dismiss = stringResource(R.string.dismiss_button)
    val moveUp = stringResource(R.string.player_move_up)
    val moveDown = stringResource(R.string.player_move_down)
    val flick = with(LocalDensity.current) { PULL_FLICK.toPx() }
    val letGo = { velocity: Float -> if (pull.letGo(velocity, flick)) onClose() }
    // Down past the top of its songs, it's pulled, and back up, it's let back before they scroll again.
    val pastTop = remember(pull, flick) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource) =
                if (available.y < 0f && pull.fraction != null) Offset(0f, pull.by(available.y)) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
                if (available.y > 0f && source == NestedScrollSource.UserInput) Offset(0f, pull.by(available.y)) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity {
                val far = pull.fraction ?: return Velocity.Zero
                letGo(available.y)
                // Pushed all the way back, the songs fling on as ever.
                return if (far > 0f) available else Velocity.Zero
            }
        }
    }
    Column(modifier = modifier.fillMaxWidth().onSizeChanged { pull.height = it.height.toFloat().coerceAtLeast(1f) }.nestedScroll(pastTop)) {
        Column(
            modifier = Modifier.draggable(
                rememberDraggableState { pull.by(it) },
                Orientation.Vertical,
                onDragStopped = { letGo(it) }
            )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clickable(onClickLabel = dismiss, onClick = onClose)
                    .semantics { contentDescription = dismiss }
            ) {
                Box(Modifier.size(width = 32.dp, height = 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
            }
            val from = following.from
            if (from != null) {
                Text(stringResource(R.string.player_playing_from), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(from, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(stringResource(R.string.player_up_next), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        LazyColumn(state = list, contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp), modifier = Modifier.weight(1f)) {
            item(key = PLAYING_KEY) { PlayingRow(player, cover, actions.loadArtwork) }
            itemsIndexed(rows, key = { index, _ -> keys[index] }) { index, next ->
                ReorderableItem(reorder, key = keys[index]) { dragging ->
                    val id = next.id
                    QueuedRow(
                        next, dragging, asked = asked == keys[index], actions.loadArtwork,
                        moves = listOfNotNull(
                            CustomAccessibilityAction(moveUp) { move(index, index - 1) }.takeIf { index > 0 && (rows[index - 1].id == null) == (id == null) },
                            CustomAccessibilityAction(moveDown) { move(index, index + 1) }.takeIf { index < rows.lastIndex && (rows[index + 1].id == null) == (id == null) }
                        ),
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier
                    ) {
                        asked = keys[index]
                        // One the app has plays at once; a list's still to ask for, from there on.
                        if (id != null) actions.onPlayerPlayQueued(id) else actions.onPlayerPlayLater(index)
                    }
                }
            }
        }
    }
}

/** The song playing, first in what plays next, its cover with bars moving to it, still while it's paused. */
@Composable
private fun PlayingRow(player: PlayerState, cover: String?, loadArtwork: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(contentAlignment = Alignment.Center) {
            CoverArt(cover, loadArtwork, size = QUEUED_COVER)
            Box(Modifier.size(QUEUED_COVER).clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.45f)))
            SoundBars(playing = player.following?.clock?.playing == true)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(player.song.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(player.song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A song up next: its cover, while [asked] with a spinner over it till it plays, and its handle to
 * drag it by; held, the whole row drags. Lifted while [dragging]. A screen reader moves it with [moves].
 */
@Composable
private fun ReorderableCollectionItemScope.QueuedRow(
    next: Queued,
    dragging: Boolean,
    asked: Boolean,
    loadArtwork: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    moves: List<CustomAccessibilityAction>,
    modifier: Modifier,
    onPlay: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val lift by animateDpAsState(if (dragging) 6.dp else 0.dp, label = "lift")
    val picked = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
    val dropped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) }
    Surface(
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = lift
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .longPressDraggableHandle(onDragStarted = { picked() }, onDragStopped = { dropped() })
                .clickable(onClick = onPlay)
                .semantics { customActions = moves }
                .padding(vertical = 6.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                CoverArt(next.art, loadArtwork, size = QUEUED_COVER)
                if (asked) {
                    Box(Modifier.size(QUEUED_COVER).clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.45f)))
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(next.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (next.artist.isNotBlank()) {
                    Text(next.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).draggableHandle(onDragStarted = { picked() }, onDragStopped = { dropped() })) {
                AppIcon(R.drawable.ic_drag_handle, contentDescription = stringResource(R.string.player_move), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Bars moving to the music, as music apps mark the song playing; low and still while it's paused. */
@Composable
private fun SoundBars(playing: Boolean) {
    val beat = rememberInfiniteTransition(label = "sound bars")
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(16.dp)) {
        SOUND_BAR_MS.forEach { ms ->
            val high by beat.animateFloat(0.3f, 1f, infiniteRepeatable(tween(ms), RepeatMode.Reverse), label = "sound bar")
            Box(Modifier.width(3.dp).fillMaxHeight(if (playing) high else 0.3f).background(Color.White, RoundedCornerShape(1.dp)))
        }
    }
}

/** A key for each song up next that stays with it as it moves: the app's number for it, or a list's song's names, counted where they repeat. */
private fun keysOf(rows: List<Queued>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return rows.map { next ->
        next.id?.let { "app $it" } ?: "list ${next.title}\u0000${next.artist}".let { name -> "$name ${seen.merge(name, 1, Int::plus)}" }
    }
}

/**
 * The sleep timer, which pauses the music after a while, or once the song ends, when the app says
 * how long it is. Set, it's filled in, with the minutes left beside it.
 */
@Composable
private fun SleepButton(sleep: Sleep?, songEnds: Boolean, actions: ScreenActions) {
    var open by remember { mutableStateOf(false) }
    val left by produceState<Long?>(null, sleep) {
        val at = sleep?.atMs ?: return@produceState run { value = null }
        while (true) {
            value = at - SystemClock.elapsedRealtime()
            delay(SLEEP_TICK_MS)
        }
    }
    val name = stringResource(R.string.player_sleep_timer)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = name) { open = true }
                .padding(horizontal = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = name }
        ) {
            AppIcon(if (sleep != null) R.drawable.ic_bedtime_on else R.drawable.ic_bedtime, contentDescription = null)
            left?.let {
                // Up to the minute it's in, as a clock counting down shows it.
                Text(
                    stringResource(R.string.player_sleep_minutes, ((it + SleepTimer.MINUTE_MS - 1) / SleepTimer.MINUTE_MS).coerceAtLeast(1)),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SLEEP_MINUTES.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_sleep_minutes, minutes)) },
                    onClick = { open = false; actions.onPlayerSleep(minutes) }
                )
            }
            if (songEnds) DropdownMenuItem(
                text = { Text(stringResource(R.string.player_sleep_song_end)) },
                onClick = { open = false; actions.onPlayerSleep(null) }
            )
            if (sleep != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.player_sleep_off)) },
                leadingIcon = { AppIcon(R.drawable.ic_close, contentDescription = null) },
                onClick = { open = false; actions.onPlayerSleepOff() }
            )
        }
    }
}

/**
 * The app's own like, drawn as the app draws it, which shows whether the song's liked, and named as
 * the app names it, in its language; drawn as a thumb up when the app's drawing can't be had.
 */
@Composable
private fun LikeButton(like: AppAction, app: String?, onLike: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val size = with(LocalDensity.current) { 24.dp.roundToPx() }
    val drawn = remember(app, like.icon, size) {
        app?.let { runCatching { context.packageManager.getResourcesForApplication(it).getDrawable(like.icon, null).toBitmap(size, size).asImageBitmap() }.getOrNull() }
    }
    IconButton(onClick = onLike, modifier = modifier) {
        if (drawn != null) Icon(drawn, contentDescription = like.name)
        else AppIcon(R.drawable.ic_thumb_up, contentDescription = like.name)
    }
}

/**
 * The line being sung, in a card at the bottom tinted as the cover is, which opens all the words, as
 * does a swipe up. Before the singing, the first. Two lines tall whatever the line, so the cover above
 * doesn't move as it's sung.
 */
@Composable
private fun LyricsPeek(lines: List<LyricLine>, following: Following?, tint: Color, onLyrics: () -> Unit, modifier: Modifier) {
    val at = rememberWhilePlaying(following?.takeIf { lines.size > 1 }) { position -> lines.indexOfLast { it.timeMs <= position }.coerceAtLeast(0) } ?: 0
    val line = (lines.getOrNull(at) ?: lines.first()).text
    val name = stringResource(R.string.lyrics_button)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(tint.copy(alpha = 0.24f))
            .clickable(onClickLabel = name, onClick = onLyrics)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Named as the button it stands in for: the line changes as it's sung, which a screen reader would read out each time.
        AppIcon(R.drawable.ic_lyrics, contentDescription = name, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Crossfade(line, label = "sung line", modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(it, style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlayerWaiting(app: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.player_waiting, app),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Without access to follow music apps, the player can't see or move the song: how to allow it. */
@Composable
private fun PlayerFollowOffer(actions: ScreenActions, fixing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.player_follow_allow), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OneTapButton(stringResource(R.string.follow_turn_on), actions.onAllowFollowing, fixing = fixing)
    }
}

/** [ms] as a clock reads it, e.g. 3:07, or 1:02:07 past an hour. */
internal fun clockText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000
    val (hours, minutes) = seconds / 3600 to seconds / 60 % 60
    val rest = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
}

private const val SEEK_HOLD_MS = 1_500L

/** How far across a swipe takes the cover before it skips, of its width. */
private const val COVER_SKIP_AT = 0.3f

/** How much of a swipe the cover follows toward a song the app can't go to. */
private const val COVER_HELD_BACK = 0.25f

/** The sleep timer's choices, in minutes. */
private val SLEEP_MINUTES = listOf(15, 30, 45, 60)

private const val SLEEP_TICK_MS = 1_000L

/** How long what plays next takes to open or close. */
private const val MORPH_MS = 400

/** How quick a flick down puts what plays next away, wherever it's let go, as a swipe between screens. */
private val PULL_FLICK = 500.dp

/** Let go this far along, what plays next carries on to put away, as a swipe between screens does. */
private const val PULL_COMMIT = 0.3f

/** How long a song tapped up next shows it's on its way, at most, as the app may never play it. */
private const val ASKED_FOR_MS = 15_000L

private const val PLAYING_KEY = "playing"

/** How long each of the bars over the song playing takes to rise. */
private val SOUND_BAR_MS = listOf(420, 300, 520)

private val QUEUED_COVER = 48.dp

/** The cover's size beside the song's name, with what plays next open. */
private val PLAYER_COVER_SMALL = 64.dp

/** The cover's least size, below which it gives way altogether. */
private val PLAYER_COVER_MIN = 120.dp
private val PLAYER_COVER_MAX = 340.dp

/** How wide the cover and the rest beside it get, together. */
private val PLAYER_BESIDE_MAX_WIDTH = 840.dp
