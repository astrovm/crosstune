package com.astrovm.crosstune

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Icon
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal const val PLAYER_COVER_TAG = "player-cover"

/**
 * Crosstune's player: the song playing in its music app, behind, with its cover, where it is, and
 * the app's buttons, as long as Crosstune can follow it there; its words open over it.
 */
@Composable
internal fun PlayerScreen(state: UiState, actions: ScreenActions) {
    val player = state.player ?: return
    val song = player.song
    // A song the app went on to often comes without a cover, which is then the result's, when it's that song.
    val cover = song.artworkUrl ?: state.result?.takeIf { SongNames.same(it.title, song.title) && SongNames.sameArtist(it.artist, song.artist) }?.artworkUrl
    val following = player.following
    val app = following?.app ?: MusicService.ofPackage(player.app)?.let { stringResource(it.labelRes) }
    BackHandler(onBack = actions.onClosePlayer)
    // The list it plays from, up top, which opens what plays next over the player.
    val listed = following?.takeIf { it.from != null || it.upNext.isNotEmpty() }
    var upNext by rememberSaveable { mutableStateOf(false) }
    val opener = remember { FocusRequester() }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(upNext) {
        if (upNext) opened = true
        // Put away, the remote is back where it opened it, rather than anywhere, as the song it was on went.
        else if (opened && listed != null) opener.requestFocus()
    }
    val ground = MaterialTheme.colorScheme.surface
    val tint by animateColorAsState(
        coverColor(cover, actions.loadArtwork) ?: MaterialTheme.colorScheme.primary,
        tween(durationMillis = 700),
        label = "player tint"
    )
    Surface(color = ground, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to tint.copy(alpha = 0.5f), 0.55f to tint.copy(alpha = 0.12f), 1f to ground))
                // Up for its words, down from the top to put it away.
                .swipeLayers(downFromTop = true)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .align(Alignment.TopCenter)
                    .widthIn(max = ContentMaxWidth)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
                    IconButton(onClick = actions.onClosePlayer) {
                        AppIcon(R.drawable.ic_expand_more, contentDescription = stringResource(R.string.dismiss_button))
                    }
                    // The list it plays from, up top as music apps keep it, which opens what plays next.
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f)) {
                        listed?.let { PlayingFrom(it, Modifier.focusRequester(opener)) { upNext = true } }
                    }
                    SleepButton(state.sleep, songEnds = following?.durationMs != null, actions)
                    // Where it plays, by its icon, which brings that app forward, up top as music apps keep where they play.
                    // The app it's followed in, as its name says, over the one it was asked of.
                    val service = MusicService.ofPackage(following?.appPackage ?: player.app)
                    val playingIn = app?.let { stringResource(R.string.player_playing_in, it) }
                    if (service != null) IconButton(onClick = actions.onPlayerOpenApp, modifier = Modifier.then(if (playingIn != null) Modifier.semantics { contentDescription = playingIn } else Modifier)) {
                        DestinationIcon(Destination.Service(service), setOf(service), size = 28.dp)
                    }
                    // Its card is under the player, so what it has is a menu away, not another screen.
                    val card = state.result?.let { SongNames.same(it.title, song.title) && SongNames.sameArtist(it.artist, song.artist) } == true
                    if (card) PlayerMenu(ready = !state.isLoading && !state.isMatching, actions)
                }
                // Cover, words and buttons stay together in the middle, the cover as big as the rest leaves room for.
                BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
                    // Bigger text takes more of the room, so the cover gives way, which also leaves the swipes the whole screen.
                    val coverSize = (maxHeight - PLAYER_CONTROLS_HEIGHT * LocalDensity.current.fontScale.coerceAtLeast(1f)).coerceIn(PLAYER_COVER_MIN, minOf(maxWidth, PLAYER_COVER_MAX))
                    // As wide as the cover can be, so its edges, the progress bar's and the times' line up.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.widthIn(max = PLAYER_COVER_MAX).fillMaxWidth()
                    ) {
                        SwipedCover(following, actions.onPlayerSkip) { CoverArt(cover, actions.loadArtwork, size = coverSize, modifier = it) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            // Kept as wide as the like on the other side, so the title stays in the middle.
                            Spacer(Modifier.size(48.dp))
                            // A tap on its name opens its card, with its links, as music apps open a song's page.
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(onClickLabel = stringResource(R.string.player_song_card), onClick = actions.onPlayerCard)
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    song.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    song.artist,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                                following?.like?.let { LikeButton(it, following.appPackage, actions.onPlayerLike) }
                            }
                        }
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 64.dp)) {
                            when {
                                !state.canFollowApps -> PlayerFollowOffer(actions, fixing = state.fixingInOneTap == OneTapFix.FOLLOW)
                                following != null -> PlayerProgress(following, actions.onPlayerSeek)
                                // Until the app plays it, once; stopped there after, there's nothing to wait for.
                                !player.played && app != null -> PlayerWaiting(app)
                            }
                        }
                        PlayerButtons(following, actions)
                        // One whose artist is unknown can't be looked up safely, so it has no words to show.
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                            if (song.artist.isNotBlank()) LyricsPeek(player, actions.onPlayerLyrics)
                        }
                    }
                }
            }
        }
        if (upNext && listed != null) UpNext(listed, actions, onDismiss = { upNext = false })
    }
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
            Slider(
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
private fun PlayerButtons(following: Following?, actions: ScreenActions) {
    val playing = following?.clock?.playing == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        // Whether the app shuffles, left of previous as music apps have it, boxed in when it does, as a
        // color alone is too faint. Its place is kept without it, so the rest doesn't move once the app says.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            following?.shuffle?.let { shuffles ->
                IconToggleButton(
                    checked = shuffles,
                    onCheckedChange = { actions.onPlayerShuffle() },
                    colors = IconButtonDefaults.iconToggleButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    AppIcon(if (shuffles) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle, contentDescription = stringResource(R.string.player_shuffle))
                }
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
        // How the app repeats, right of next as music apps have it, boxed in as shuffle is when it does.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            following?.repeat?.let { repeats ->
                val (icon, way) = when (repeats) {
                    Repeat.OFF -> R.drawable.ic_repeat to null
                    Repeat.ALL -> R.drawable.ic_repeat_on to R.string.player_repeat_all
                    Repeat.ONE -> R.drawable.ic_repeat_one_on to R.string.player_repeat_one
                }
                val how = way?.let { stringResource(it) }
                IconToggleButton(
                    checked = repeats != Repeat.OFF,
                    onCheckedChange = { actions.onPlayerRepeat() },
                    colors = IconButtonDefaults.iconToggleButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.then(if (how != null) Modifier.semantics { stateDescription = how } else Modifier)
                ) {
                    AppIcon(icon, contentDescription = stringResource(R.string.player_repeat))
                }
            }
        }
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

/** The list the song plays from, by name when Crosstune played it, which opens what plays next. */
@Composable
private fun PlayingFrom(following: Following, modifier: Modifier, onOpen: () -> Unit) {
    val upNext = stringResource(R.string.player_up_next)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = upNext, onClick = onOpen)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = following.from?.let { "$upNext, $it" } ?: upNext }
    ) {
        AppIcon(R.drawable.ic_queue_music, contentDescription = null, modifier = Modifier.size(20.dp))
        following.from?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * What the app plays next, then what Crosstune asks it for after; one the app has plays at a tap. A
 * sheet over the player rather than a window of its own: on a TV the player is itself over another
 * app, where Crosstune can open no other window.
 */
@Composable
private fun UpNext(following: Following, actions: ScreenActions, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    // With a remote, it starts on the next song, so OK plays it.
    val first = remember { FocusRequester() }
    if (following.upNext.isNotEmpty()) LaunchedEffect(Unit) {
        // Once the list has laid it out.
        withFrameNanos {}
        first.requestFocus()
    }
    Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize()) {
        // A tap beside it puts it away, as a sheet's does; the remote has Back for that, so it takes no focus.
        Spacer(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
        )
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 48.dp)
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                // The remote stays in it, as the player's under it, until Back.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
        ) {
            Column(modifier = Modifier.navigationBarsPadding().padding(top = 24.dp)) {
                Text(
                    following.from ?: stringResource(R.string.player_up_next),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(following.upNext) { index, next ->
                        val id = next.id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                // Still to be asked for, it can't be played yet, but the remote still goes through it.
                                .then(if (id != null) Modifier.clickable { onDismiss(); actions.onPlayerPlayQueued(id) } else Modifier.semantics(mergeDescendants = true) {}.focusable())
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                        ) {
                            Text(next.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (next.artist.isNotBlank()) {
                                Text(next.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
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
private fun LikeButton(like: AppAction, app: String?, onLike: () -> Unit) {
    val context = LocalContext.current
    val size = with(LocalDensity.current) { 24.dp.roundToPx() }
    val drawn = remember(app, like.icon, size) {
        app?.let { runCatching { context.packageManager.getResourcesForApplication(it).getDrawable(like.icon, null).toBitmap(size, size).asImageBitmap() }.getOrNull() }
    }
    IconButton(onClick = onLike) {
        if (drawn != null) Icon(drawn, contentDescription = like.name)
        else AppIcon(R.drawable.ic_thumb_up, contentDescription = like.name)
    }
}

/**
 * The line being sung, under the buttons, which opens all the words, as does a swipe up. Before the
 * singing, the first; a video's clock may be the video's, so it shows none, nor do words not found.
 */
@Composable
private fun LyricsPeek(player: PlayerState, onLyrics: () -> Unit) {
    val lines = player.lines.takeIf { player.following?.video != true }.orEmpty()
    val at = rememberWhilePlaying(player.following?.takeIf { lines.size > 1 }) { position -> lines.indexOfLast { it.timeMs <= position }.coerceAtLeast(0) } ?: 0
    val line = lines.getOrNull(at)?.text?.takeIf { it.isNotBlank() }
    val name = stringResource(R.string.lyrics_button)
    if (line == null) {
        IconButton(onClick = onLyrics) { AppIcon(R.drawable.ic_lyrics, contentDescription = name) }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = name, onClick = onLyrics)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Named as the button it stands in for: the line changes as it's sung, which a screen reader would read out each time.
        AppIcon(R.drawable.ic_lyrics, contentDescription = name, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Crossfade(line, label = "sung line", modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/** What's under the cover: its title and artist, progress, buttons and the line being sung. */
private val PLAYER_CONTROLS_HEIGHT = 360.dp
private val PLAYER_COVER_MIN = 120.dp
private val PLAYER_COVER_MAX = 340.dp
