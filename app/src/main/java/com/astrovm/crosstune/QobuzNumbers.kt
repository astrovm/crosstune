package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Qobuz's app shows a loading page, for good, for a song's number that its account can't play, and which number that is
 * can't be told from the phone's country: on one account, of seven songs its UK and US stores number differently, the
 * UK's number played three. So a song opened there by its number is watched, and if it hasn't started in a while, it's
 * opened on the number of the other store. Asking for it by name, tried after that, doesn't get Qobuz off that page.
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
        // Asked here, as a link can open before the screen's look at what's installed is done. Not installed, it opens on
        // the web, where nothing's seen to play.
        if (!playback.hasAccess() || !installed(app)) return
        val over = playback.nowPlaying()?.takeIf { sameApp(app, it.app, installed) }?.metadata
        // Already on it, it's brought forward, and there's no start to see.
        if (over != null && SongNames.same(over.title, song.title) && SongNames.sameArtist(over.artist, song.artist)) return
        job = scope.launch { tryOther(song, app, link, over) }
    }

    private suspend fun tryOther(song: MusicMetadata, app: String, link: String, over: MusicMetadata?) {
        coroutineScope {
            // Asked now, not once the wait is over: 8 s and then a store that took 2 s came just past what Android allows [WAIT_MS].
            val other = async { otherLink(song, link) }
            val started = withTimeoutOrNull(WAIT_MS) {
                playback.nowPlayingUpdates().first { it != null && sameApp(app, it.app, installed) && it.metadata.title != over?.title && isTheSong(it.metadata, song) }
            }
            val next = when {
                started != null -> null
                over == null && playsElse(app) -> link
                else -> other.await()
            }
            other.cancel()
            next?.let { playback.openSong(app, it) }
        }
    }

    /**
     * Woken from closed and playing what it was left on, which it does now and then, Qobuz lost the link rather than got stuck
     * on its number: the same number sent again played, where the other store's didn't.
     */
    private fun playsElse(app: String) = playback.nowPlaying()?.let { sameApp(app, it.app, installed) } == true

    /** Whatever else Qobuz calls it, as "(Remastered)". Not a cover, nor what it was left on as it wakes. */
    private fun isTheSong(playing: MusicMetadata, song: MusicMetadata) =
        SongNames.sameArtist(playing.artist, song.artist) || SongNames.same(playing.title, song.title)

    fun off() {
        job?.cancel()
    }

    companion object {
        /** Android lets Crosstune open an app only for 10 s after one of its screens started or closed, see [OpenSongActivity] (measured: blocked at 10.0 s, allowed at 9.9 s). */
        const val WAIT_MS = 8_000L
    }
}
