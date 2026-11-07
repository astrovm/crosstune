package com.astrovm.crosstune

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Qobuz's app shows a loading page, for good, for a song's number that its account can't play, and which number that is
 * can't be told from the phone's country: on one account, of seven songs its UK and US stores number differently, the
 * UK's number played three. So a song opened there by its number is watched, and if it hasn't started in a while, it's
 * opened on the number of the other store. Asking for it by name, tried after that, doesn't get Qobuz off that page.
 * That store is asked as the song opens, while Crosstune's still in front, and the song doesn't wait for it: holding
 * each song for its answer made every one start 1.1 s later (80 of 80 tries), to help only those that stall on a slow store.
 * Kept by the app rather than a screen, as the screen closes as it opens a song.
 */
internal class QobuzNumbers(
    private val playback: PlaybackSource,
    private val otherLink: suspend (MusicMetadata, String) -> String?,
    private val installed: (String) -> Boolean,
    private val scope: CoroutineScope
) {
    private var job: Job? = null

    /** A song about to open, with the time it's given to start counted from its first launch. */
    class Opening internal constructor(private val launched: CompletableDeferred<Unit>? = null, private val watch: Job? = null) {
        /** Something newer came first, as can happen when the song waited for Crosstune to come back: it's no longer what's asked for. */
        val superseded get() = watch?.isCancelled == true

        fun started(opened: Boolean) {
            if (opened) launched?.complete(Unit) else watch?.cancel()
        }
    }

    /** Watches [app], about to open on [song] at [link]. */
    fun prepare(song: MusicMetadata, app: String, link: String): Opening {
        off()
        // Asked here, as a link can open before the screen's look at what's installed is done. Not installed, it opens on
        // the web, where nothing's seen to play.
        if (!playback.hasAccess() || !installed(app)) return Opening()
        val over = playback.nowPlaying()?.takeIf { sameApp(app, it.app, installed) }?.metadata
        // Already on it, it's brought forward, and there's no start to see.
        if (over != null && SongNames.same(over.title, song.title) && SongNames.sameArtist(over.artist, song.artist)) return Opening()
        val launched = CompletableDeferred<Unit>()
        val watch = scope.launch { tryOther(song, app, link, over, launched) }
        job = watch
        return Opening(launched, watch)
    }

    private suspend fun tryOther(song: MusicMetadata, app: String, link: String, over: MusicMetadata?, launched: CompletableDeferred<Unit>) = coroutineScope {
        val other = async { otherLink(song, link) }
        val watching = launch {
            playback.nowPlayingUpdates().first(endsTheWatch(song, app, over))
            this@coroutineScope.cancel()
        }
        launched.await()
        delay(WAIT_MS)
        val next = linkToTry(app, link, over, other)
        other.cancel()
        watching.cancel()
        next?.let { playback.openSong(app, it) }
    }

    /** Whether what plays leaves nothing to try: the song started, whenever that is, or the user went on to another app's. */
    private fun endsTheWatch(song: MusicMetadata, app: String, over: MusicMetadata?): suspend (Heard.Song?) -> Boolean {
        var before = playback.nowPlaying()
        return { playing ->
            val inTheApp = playing != null && sameApp(app, playing.app, installed)
            val elsewhere = playing != null && !inTheApp && (playing.app != before?.app || playing.metadata.title != before?.metadata?.title)
            before = playing
            elsewhere || inTheApp && playing.metadata.title != over?.title && isTheSong(playing.metadata, song)
        }
    }

    /** What to send once the wait is over, with a store yet to answer given only what's left of Android's window. */
    private suspend fun linkToTry(app: String, link: String, over: MusicMetadata?, other: Deferred<String?>): String? {
        val woke = { over == null && playsElse(app) }
        val alternate = if (woke()) null else withTimeoutOrNull(LAST_RETRY_MS - WAIT_MS) { other.await() }
        return when {
            woke() -> link
            alternate != null -> alternate
            over == null && playback.plays(app).first() != null -> link
            else -> null
        }
    }

    /**
     * Woken from closed and playing what it was left on, which it does now and then, Qobuz lost the link rather than got stuck
     * on its number: the same number sent again played, where the other store's didn't.
     */
    private fun playsElse(app: String) = playback.nowPlaying()?.let { sameApp(app, it.app, installed) } == true

    /** Its title, whatever else Qobuz calls it, as "(Remastered)", or a name it's also written under. An artist alone also names what it was left on. */
    private fun isTheSong(playing: MusicMetadata, song: MusicMetadata) =
        SongNames.titleNames(song.title).any { wanted ->
            SongNames.titleNames(playing.title).any { SongNames.same(it, wanted) }
        }

    fun off() {
        job?.cancel()
    }

    companion object {
        /** How long a song has to start, from its first launch. */
        const val WAIT_MS = 8_000L

        /** The latest a retry goes out, from that launch: Android lets Crosstune open an app only for 10 s after one of its screens started or closed, see [OpenSongActivity] (measured: blocked at 10.0 s, allowed at 9.9 s). */
        const val LAST_RETRY_MS = 9_500L
    }
}
