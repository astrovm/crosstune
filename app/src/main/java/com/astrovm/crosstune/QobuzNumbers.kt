package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Qobuz's app shows a loading page, for good, for a song's number that its account can't play, and which number that is
 * can't be told from the phone's country: on one account, of seven songs its UK and US stores number differently, the
 * UK's number played three. So a song opened there by its number is watched. If it hasn't started in a while, it's opened
 * on the number of the other store, then asked for by name, which plays what comes first in Qobuz's search, so someone
 * else's song is stopped.
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
        job = scope.launch { tryAll(song, app, link, over) }
    }

    private suspend fun tryAll(song: MusicMetadata, app: String, link: String, over: MusicMetadata?) {
        if (started(song, app, over) != null) return
        val other = otherLink(song, link)
        if (other != null && playback.openSong(app, other) && started(song, app, over) != null) return
        playback.playSong(song, app)
        val named = playing(app, over) ?: return
        if (!SongNames.sameArtist(named.artist, song.artist)) playback.playPause(named, false)
    }

    private suspend fun started(song: MusicMetadata, app: String, over: MusicMetadata?) = playing(app, over) { isTheSong(it, song) }

    /** What [app] goes on to play that it didn't before, and is [wanted], if it does within [WAIT_MS]. */
    private suspend fun playing(app: String, over: MusicMetadata?, wanted: (MusicMetadata) -> Boolean = { true }): MusicMetadata? =
        withTimeoutOrNull(WAIT_MS) {
            playback.nowPlayingUpdates().first { it != null && sameApp(app, it.app, installed) && it.metadata.title != over?.title && wanted(it.metadata) }
        }?.metadata

    /** Whatever else Qobuz calls it, as "(Remastered)". Not a cover, nor what it was left on as it wakes. */
    private fun isTheSong(playing: MusicMetadata, song: MusicMetadata) =
        SongNames.sameArtist(playing.artist, song.artist) || SongNames.same(playing.title, song.title)

    fun off() {
        job?.cancel()
    }

    companion object {
        /** Android lets Crosstune open an app only for some 10 s after it left the screen, the second lookup included. */
        const val WAIT_MS = 8_000L
    }
}
