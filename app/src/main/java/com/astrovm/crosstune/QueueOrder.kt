package com.astrovm.crosstune

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The order the app's songs up next play in, as they're moved in the player. Music apps don't let
 * another app move what they have queued, but play any of it when asked, even one already behind
 * them, so Crosstune keeps the order: as each song ends, a moment early, as the app would be on to
 * its own next one by then, it has the app play the next one in it. Kept by the app rather than a
 * screen, so it plays on with Crosstune closed. Once it's played through, or the app plays something
 * else, the app's own order is back.
 */
internal class QueueOrder(
    private val playback: PlaybackSource,
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    /** The app's songs still to play, in the order they were moved to; none without one. */
    private val order = MutableStateFlow<List<Queued>>(emptyList())
    private var job: Job? = null

    /** [source], with what's up next in the order, and on to the order's next song when skipped. */
    fun ordering(source: PlaybackSource): PlaybackSource = object : PlaybackSource by source {
        override fun follow(song: MusicMetadata): Flow<Following?> = combine(source.follow(song), order) { following, order -> following?.inOrder(order) }

        override fun skip(song: MusicMetadata, next: Boolean) {
            val first = order.value.firstOrNull()?.id
            if (next && first != null) playback.playQueued(song, first) else source.skip(song, next)
        }
    }

    /** Moves the song up next at [from] to [to], both of the app's queue, as [following] shows them. */
    fun move(following: Following, from: Int, to: Int) {
        val queued = following.upNext.filter { it.id != null }
        val app = following.appPackage
        if (app == null || from !in queued.indices || to !in queued.indices) return
        order.value = queued.toMutableList().apply { add(to, removeAt(from)) }
        job?.cancel()
        job = scope.launch { keep(app) }
    }

    /** The app's own order again. */
    fun off() {
        order.value = emptyList()
        job?.cancel()
        job = null
    }

    /** Has [app] play the order's songs, each as the one before ends. */
    private suspend fun keep(app: String) {
        var on: Long? = null
        playback.plays(app).collectLatest { plays ->
            val id = plays?.queueId ?: return@collectLatest
            if (on != null && id != on) {
                // On to anything but one of its songs, the app went its own way, and the order is done with.
                if (order.value.none { it.id == id }) return@collectLatest off()
                order.update { left -> left.filter { it.id != id } }
            }
            on = id
            val next = order.value.firstOrNull()?.id ?: return@collectLatest off()
            playback.follow(plays.song).collectLatest following@{ following ->
                // Repeating the one song, the app plays it again rather than another; where it goes on to the
                // order's next song by itself, it needn't be asked.
                if (following == null || following.repeat == Repeat.ONE || following.upNext.firstOrNull()?.id == next) return@following
                // Paused, or of no known length, it waits to hear more.
                val clock = following.clock.takeIf { it.playing } ?: return@following
                val duration = following.durationMs ?: return@following
                delay(((duration - clock.positionAt(now())) / clock.speed).toLong() - SleepTimer.SONG_END_EARLY_MS)
                playback.playQueued(plays.song, next)
            }
        }
    }

    /** With the app's songs up next in [order], then those it has that came in after it was made, then a list's still to ask for. */
    private fun Following.inOrder(order: List<Queued>): Following {
        if (order.isEmpty()) return this
        val (apps, later) = upNext.partition { it.id != null }
        return copy(upNext = order + apps.filter { queued -> order.none { it.id == queued.id } } + later)
    }
}
