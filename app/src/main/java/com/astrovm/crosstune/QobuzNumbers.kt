package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Qobuz's app shows a loading page, for good, for a song's number that its account's catalog doesn't have, and its
 * stores number about one song in four differently. Asked by name it plays whatever comes first, covers too, and which
 * catalog an account follows can't be told. So a song opened there by its number is watched, and if it hasn't started in
 * a while, it's opened on the number of the other store.
 * Kept by the app rather than a screen, as the screen closes as it opens a song.
 */
internal class QobuzNumbers(
    private val playback: PlaybackSource,
    private val otherLink: suspend (MusicMetadata, String) -> String?,
    private val installed: (String) -> Boolean,
    private val scope: CoroutineScope
) {
    private var job: Job? = null

    /** Watches [app], about to open on [song] at [link]. */
    fun opened(song: MusicMetadata, app: String, link: String) {
        off()
        if (!playback.hasAccess()) return
        val over = playback.nowPlaying()?.takeIf { sameApp(app, it.app, installed) }?.metadata
        // Already on it, it's brought forward, and there's no start to see.
        if (over != null && SongNames.same(over.title, song.title) && SongNames.sameArtist(over.artist, song.artist)) return
        job = scope.launch {
            // Whatever Qobuz calls the song: anything new playing there means it took the number.
            val started = withTimeoutOrNull(WAIT_MS) {
                playback.nowPlayingUpdates().first { it != null && sameApp(app, it.app, installed) && it.metadata.title != over?.title }
            }
            if (started == null) otherLink(song, link)?.let { playback.openSong(app, it) }
        }
    }

    fun off() {
        job?.cancel()
    }

    companion object {
        /** Android lets Crosstune open an app only for some 10 s after it left the screen, the second lookup included. */
        const val WAIT_MS = 8_000L
    }
}
