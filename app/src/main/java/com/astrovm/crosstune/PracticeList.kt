package com.astrovm.crosstune

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What there is to practise: every song's lines due at once, then each song with its own, to
 * practise on its own or to choose which of its lines to keep, or, swiped away, to let them all go,
 * which can be undone. Gone back from once none are left.
 */
@Composable
internal fun PracticeList(state: UiState, actions: ScreenActions, onBack: () -> Unit) {
    SwipedBack(onBack = onBack)
    val now = System.currentTimeMillis()
    val songs = state.savedLines.groupBy { it.title to it.artist }
        .map { (song, lines) -> PracticeSong(MusicMetadata(song.first, song.second, artworkUrl = lines.firstNotNullOfOrNull { it.artworkUrl }), lines, lines.cardsDueCount(now, state.cardTypes)) }
        .sortedWith(compareByDescending<PracticeSong> { it.due }.thenBy { it.song.title.lowercase() })
    // A song's lines let go, until the offer to keep them again is gone.
    var removed by remember { mutableStateOf<List<SavedLine>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val removedMessage = stringResource(R.string.history_removed)
    val undoLabel = stringResource(R.string.undo_button)
    // Each removal gets its own offer, replacing one still showing.
    LaunchedEffect(removed) {
        val lines = removed ?: return@LaunchedEffect
        if (snackbar.showSnackbar(removedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) actions.onKeepAgain(lines)
        removed = null
    }
    // The last song removed, it's gone back from once that can't be undone.
    LaunchedEffect(songs.isEmpty(), removed) { if (songs.isEmpty() && removed == null) onBack() }
    // The song whose lines are being chosen, by its name and singer, so it stays chosen on a turn.
    var editing by rememberSaveable { mutableStateOf<List<String>?>(null) }
    Page(
        title = stringResource(R.string.saved_practice),
        snackbarHost = { SnackbarHost(snackbar) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button))
            }
        }
    ) {
        // All of them practises what's due; with none, it says so, and the kinds of card can still be set there.
        PracticeRow(stringResource(R.string.practice_all_songs), songs.sumOf { it.due }, onClick = actions.onStartPractice) {
            AppIcon(R.drawable.ic_school, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp).size(24.dp))
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        songs.forEach { song ->
            key(song.song.title, song.song.artist) {
                val remove = {
                    actions.onChooseLines(emptyList(), song.lines, false)
                    removed = song.lines
                }
                val swipe = rememberSwipeToDismissBoxState()
                val label = stringResource(R.string.remove_button)
                SwipeToDismissBox(
                    state = swipe,
                    onDismiss = { remove() },
                    // Only while it's swiped, so its color never shows at a resting row's rounded edge.
                    backgroundContent = { if (swipe.dismissDirection != SwipeToDismissBoxValue.Settled) RemoveBackground(swipe.dismissDirection) },
                    modifier = Modifier.clip(MaterialTheme.shapes.medium)
                ) {
                    PracticeRow(
                        song.song.title, song.due, below = song.song.artist, onClick = { actions.onPractiseSongOf(song.song) }, edit = { editing = listOf(song.song.title, song.song.artist) },
                        // Swiping isn't something TalkBack can do, so removing is an action of its own there.
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface).semantics { customActions = listOf(CustomAccessibilityAction(label) { remove(); true }) }
                    ) {
                        CoverArt(song.song.artworkUrl, actions.loadArtwork, size = 40.dp)
                    }
                }
            }
        }
    }
    val chosen = editing?.let { (title, artist) -> songs.firstOrNull { it.song.title == title && it.song.artist == artist } }
    chosen?.let { song ->
        ChooseLinesSheet(
            song.song, texts = emptyList(), words = emptyList(), kept = song.lines, actions.loadArtwork, practise = false,
            onSave = { _, remove -> actions.onChooseLines(emptyList(), remove, false) }, onDismiss = { editing = null }
        )
    }
}

private data class PracticeSong(val song: MusicMetadata, val lines: List<SavedLine>, val due: Int)

/** What a tap practises, with how many of its lines are due, and a way to choose them when it can [edit]. */
@Composable
private fun PracticeRow(
    title: String,
    due: Int,
    onClick: () -> Unit,
    below: String? = null,
    edit: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        leading()
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            below?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (due > 0) Text(stringResource(R.string.practice_due, due), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        edit?.let {
            IconButton(onClick = it) {
                AppIcon(R.drawable.ic_edit, contentDescription = stringResource(R.string.choose_lines))
            }
        }
    }
}
