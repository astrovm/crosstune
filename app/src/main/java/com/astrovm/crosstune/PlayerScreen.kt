package com.astrovm.crosstune

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
                    Spacer(Modifier.weight(1f))
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
                        CoverArt(cover, actions.loadArtwork, size = coverSize)
                        Text(
                            song.title,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
                        )
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 64.dp)) {
                            when {
                                !state.canFollowApps -> PlayerFollowOffer(actions, fixing = state.fixingInOneTap == OneTapFix.FOLLOW)
                                following != null -> PlayerProgress(following, actions.onPlayerSeek)
                                // Until the app plays it, once; stopped there after, there's nothing to wait for.
                                !player.played && app != null -> PlayerWaiting(app)
                            }
                        }
                        PlayerButtons(following, actions, hasLyrics = song.artist.isNotBlank(), service = MusicService.ofPackage(player.app), appName = app)
                    }
                }
            }
        }
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
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = description }
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(clockText(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (duration != null) Text(clockText(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Previous, play or pause, and next, as the app lets them; play asks the app again when it isn't playing the song. */
@Composable
private fun PlayerButtons(following: Following?, actions: ScreenActions, hasLyrics: Boolean, service: MusicService?, appName: String?) {
    val playing = following?.clock?.playing == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        // One whose artist is unknown can't be looked up safely, so its place is left empty.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            if (hasLyrics) IconButton(onClick = actions.onPlayerLyrics) {
                AppIcon(R.drawable.ic_lyrics, contentDescription = stringResource(R.string.lyrics_button))
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
        // Where it plays, by its icon, which brings that app forward.
        val playingIn = appName?.let { stringResource(R.string.player_playing_in, it) }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            if (service != null) IconButton(onClick = actions.onPlayerOpenApp, modifier = Modifier.then(if (playingIn != null) Modifier.semantics { contentDescription = playingIn } else Modifier)) {
                DestinationIcon(Destination.Service(service), setOf(service), size = 28.dp)
            }
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

/** What's under the cover: its title and artist, progress and buttons. */
private val PLAYER_CONTROLS_HEIGHT = 290.dp
private val PLAYER_COVER_MIN = 120.dp
private val PLAYER_COVER_MAX = 340.dp
