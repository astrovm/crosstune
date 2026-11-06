package com.astrovm.crosstune

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Qobuz can leave a store's unplayable number loading forever. Resolve the other store before opening, while Crosstune
 * is still foreground: a slow lookup after leaving can outlast Android's permission to open the app again.
 * Kept by the app rather than a screen, as the screen closes as it opens a song.
 */
internal class QobuzNumbers(
    private val playback: PlaybackSource,
    private val otherLink: suspend (MusicMetadata, String) -> String?,
    private val installed: (String) -> Boolean,
    private val scope: CoroutineScope
) {
    private var job: Job? = null

    /** An open waits for its store lookup, then starts the playback wait only after the first launch was sent. */
    class Opening internal constructor(
        private val ready: Deferred<Unit>,
        private val launched: CompletableDeferred<Unit>?,
        private val work: Job?
    ) {
        suspend fun ready(): Boolean = try {
            ready.await()
            work?.isActive != false
        } catch (_: CancellationException) {
            // Superseding this open must not cancel the activity's collector of later opens.
            val caller = currentCoroutineContext()
            if (!caller.isActive) work?.cancel()
            caller.ensureActive()
            false
        }

        fun started(opened: Boolean) {
            if (opened) launched?.complete(Unit) else work?.cancel()
        }
    }

    fun prepare(song: MusicMetadata, app: String, link: String): Opening {
        off()
        if (!playback.hasAccess() || !installed(app)) return Opening(CompletableDeferred(Unit), null, null)
        val over = playback.nowPlaying()?.takeIf { sameApp(app, it.app, installed) }?.metadata
        if (over != null && SongNames.same(over.title, song.title) && SongNames.sameArtist(over.artist, song.artist)) {
            return Opening(CompletableDeferred(Unit), null, null)
        }
        val ready = CompletableDeferred<Unit>()
        val launched = CompletableDeferred<Unit>()
        val work = scope.launch(start = CoroutineStart.LAZY) {
            var before = playback.nowPlaying()
            var sent = false
            val moved = launch {
                playback.nowPlayingUpdates().drop(1).first { playing ->
                    val changed = playing != null && !sameApp(app, playing.app, installed) &&
                        (playing.app != before?.app || playing.metadata.title != before?.metadata?.title)
                    val alreadyPlaying = !sent && playing != null && sameApp(app, playing.app, installed) && isTheSong(playing.metadata, song)
                    before = playing
                    changed || alreadyPlaying
                }
                off()
            }
            try {
                val other = otherLink(song, link)
                ready.complete(Unit)
                launched.await()
                sent = true
                tryOther(song, app, link, over, other)
            } finally {
                moved.cancel()
            }
        }
        work.invokeOnCompletion { ready.cancel(); launched.cancel() }
        job = work
        work.start()
        return Opening(ready, launched, work)
    }

    private suspend fun tryOther(song: MusicMetadata, app: String, link: String, over: MusicMetadata?, other: String?) {
        val started = withTimeoutOrNull(WAIT_MS) {
            playback.nowPlayingUpdates().first { it != null && sameApp(app, it.app, installed) && it.metadata.title != over?.title && isTheSong(it.metadata, song) }
        }
        val next = when {
            started != null -> null
            over == null && playsElse(app) -> link
            else -> other
        }
        next?.let { playback.openSong(app, it) }
    }

    /** Waking on its old queue loses the link; sending the same number again gets it off that queue. */
    private fun playsElse(app: String) = playback.nowPlaying()?.let { sameApp(app, it.app, installed) } == true

    /** A title or an explicit translated name, including edition tags. An artist alone also names its old queue. */
    private fun isTheSong(playing: MusicMetadata, song: MusicMetadata) =
        SongNames.titleNames(song.title).any { wanted ->
            SongNames.titleNames(playing.title).any { SongNames.same(it, wanted) }
        }

    fun off() {
        job?.cancel()
    }

    companion object {
        /** Playback has this long after the first launch, with no remaining store lookup to delay its retry. */
        const val WAIT_MS = 8_000L
    }
}
