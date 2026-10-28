package com.astrovm.crosstune

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** When the sleep timer pauses the music: at [atMs], on the clock that counts since the phone started, or once the song ends, without one. */
internal data class Sleep(val atMs: Long? = null)

/**
 * Pauses the music app after a while, or once its song ends, to fall asleep to. Kept by the app
 * rather than a screen, so it pauses with Crosstune closed too.
 */
internal class SleepTimer(
    private val playback: PlaybackSource,
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    private val sleep = MutableStateFlow<Sleep?>(null)
    private var job: Job? = null

    /** When it pauses, while it's set. */
    val set: StateFlow<Sleep?> = sleep.asStateFlow()

    /** Pauses what plays in [minutes]. */
    fun after(minutes: Int) {
        val at = now() + minutes * MINUTE_MS
        start(Sleep(at)) {
            delay(at - now())
            playback.nowPlaying()?.let { playback.playPause(it.metadata, false) }
            off()
        }
    }

    /** Pauses [song] as it ends, or what plays once it's no longer playing, e.g. skipped. */
    fun afterSong(song: MusicMetadata) = start(Sleep()) {
        playback.follow(song).collectLatest { following ->
            if (following == null) {
                playback.nowPlaying()?.let { playback.playPause(it.metadata, false) }
                return@collectLatest off()
            }
            // Paused, or of no known length, it waits to hear more.
            val clock = following.clock.takeIf { it.playing } ?: return@collectLatest
            val duration = following.durationMs ?: return@collectLatest
            // A moment before its end, as the app may already be on to the next one at it.
            delay(((duration - clock.positionAt(now())) / clock.speed).toLong() - SONG_END_EARLY_MS)
            playback.playPause(song, false)
            off()
        }
    }

    fun off() {
        sleep.value = null
        job?.cancel()
        job = null
    }

    private fun start(set: Sleep, waits: suspend () -> Unit) {
        off()
        sleep.value = set
        job = scope.launch { waits() }
    }

    companion object {
        const val MINUTE_MS = 60_000L

        /** How long before a song's end it's paused at. */
        const val SONG_END_EARLY_MS = 500L
    }
}
