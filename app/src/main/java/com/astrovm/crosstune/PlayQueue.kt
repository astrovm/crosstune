package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * A playlist longer than YouTube takes in one queue, played through by Crosstune: YouTube or YouTube
 * Music is asked for [MainViewModel.MAX_QUEUE] songs at a time, and for the next ones as the last of
 * those ends. Kept on disk, so it carries on after Android stops Crosstune, which it starts again to
 * show it what plays.
 */
internal class PlayQueue(
    private val playback: PlaybackSource,
    /** [tracks] as one queue in a [MusicService], see [ExactMatcher.youtubeQueue]. */
    private val queueOf: suspend (MusicService, List<MusicMetadata>) -> YouTubeQueue?,
    private val file: File,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private var playing: Playing? = null
    private var job: Job? = null
    private var next: Deferred<YouTubeQueue?>? = null

    /** Where in the part the app is, once it plays one of its songs. */
    private var at = -1

    /** What the app played as the part was asked of it, still from before the part until it plays something else. */
    private var before: AppPlays? = null

    /** The app's number for [before]: all it numbered no higher it queued before the part. */
    private var floor: Long? = null

    /** The song that ends the part, as the app names it, while it plays and more of the list comes after it. */
    private val onLast = MutableStateFlow<MusicMetadata?>(null)

    /** Going on to the next part, which skipping and the app moving on may both ask for at once. */
    private var moving = false

    /**
     * Plays on through [tracks] in [app], after the [part] of them it was just asked for, which ends
     * at [to]; any playlist played through before stops.
     */
    suspend fun play(tracks: List<MusicMetadata>, service: MusicService, app: String, to: Int, part: List<MusicMetadata>) {
        stop()
        if (to >= tracks.size) return
        before = playback.plays(app).first()
        floor = before?.queueId
        start(Playing(tracks, service, app, to, part))
    }

    /**
     * [playback], where skipping on from the song that ends the part plays the next part at once,
     * even where the app can't skip on by itself, as YouTube can't from a list's last song. Back
     * stays the app's, as it can't be asked to start a part at its last song.
     */
    fun skipping(playback: PlaybackSource): PlaybackSource = object : PlaybackSource by playback {
        override fun follow(song: MusicMetadata): Flow<Following?> = combine(playback.follow(song), onLast) { following, last ->
            if (following != null && last != null && sameEntry(last, song)) following.copy(canSkipNext = true) else following
        }

        override fun skip(song: MusicMetadata, next: Boolean) {
            val last = onLast.value
            if (!next || last == null || !sameEntry(last, song)) return playback.skip(song, next)
            onLast.value = null
            scope.launch { playNext() }
        }
    }

    /** Stops playing through the playlist; the app plays on as it would. */
    fun stop() {
        onLast.value = null
        job?.cancel()
        next?.cancel()
        job = null
        next = null
        playing = null
        file.delete()
    }

    /** Carries on with the playlist being played through when Crosstune was stopped, if there was one. */
    fun resume() {
        if (playing != null) return
        scope.launch {
            val kept = withContext(ioDispatcher) { read() } ?: return@launch
            if (playing == null) start(kept)
        }
    }

    private suspend fun start(queue: Playing) {
        at = -1
        save(queue)
        job = scope.launch { playback.plays(queue.app).collect { now -> now?.let { heard(it) } } }
    }

    private suspend fun heard(now: AppPlays) {
        val queue = playing ?: return
        onLast.value = null
        if (now == before) return
        before = null
        if (floor.let { it != null && now.queueId != null && now.queueId <= it }) return
        val part = queue.part
        val named = part.indexOfFirst { sameEntry(it, now.song) }
        // Once one of the part's songs is seen with the app's number for it, the app's numbers say
        // where it is, whatever it calls the songs.
        val first = queue.first ?: now.queueId?.takeIf { named >= 0 }?.let { it - named }?.also { save(queue.copy(first = it)) }
        val numbered = first != null && now.queueId != null
        val index = if (numbered) (now.queueId - first).toInt() else named
        when {
            // The part's last song played to its end, and the app stopped there, as YouTube does.
            index == part.lastIndex && now.ended -> playNext()
            index in part.indices -> {
                at = index
                val was = checkNotNull(playing)
                if (index !in was.heard) save(was.copy(heard = was.heard + index))
                // The last of the part played in order, or, shuffled, the last of its songs to play.
                val ends = checkNotNull(playing).heard.size == part.size && (index == part.lastIndex || index !in was.heard)
                if (ends && was.to < was.tracks.size) onLast.value = now.song
                if (next == null) lookAhead()
            }
            // Still what played before the part.
            at < 0 -> Unit
            // On from the part's last song, or shuffled, from all of them, to what the app plays after it.
            (at == part.lastIndex || queue.heard.size == part.size) && if (numbered) index == part.size else index < 0 -> playNext()
            // Something else was picked to play; without numbers, it may only be the app's own name for one of the part's songs.
            numbered -> stop()
        }
    }

    /** The same song of the list, in whatever version the app found it: live, remastered, its video. */
    private fun sameEntry(song: MusicMetadata, plays: MusicMetadata) = SongNames.sameArtist(song.artist, plays.artist) &&
        SongNames.titleNames(song.title).any { name -> SongNames.titleNames(plays.title).any { SongNames.same(SongNames.bareTitle(name), SongNames.bareTitle(it)) } }

    /** The next part as one queue, looked up while this one plays, so it's there as this one ends. */
    private fun lookAhead(): Deferred<YouTubeQueue?> {
        val queue = checkNotNull(playing)
        val rest = queue.tracks.subList(queue.to, minOf(queue.to + MainViewModel.MAX_QUEUE, queue.tracks.size))
        return scope.async { if (rest.isEmpty()) null else queueOf(queue.service, rest) }.also { next = it }
    }

    private suspend fun playNext() {
        if (moving) return
        moving = true
        try {
            val queue = checkNotNull(playing)
            val list = (next ?: lookAhead()).await() ?: return stop()
            next = null
            at = -1
            save(queue.copy(to = minOf(queue.to + MainViewModel.MAX_QUEUE, queue.tracks.size), part = list.songs, first = null, heard = emptySet()))
            if (!playback.playList(queue.app, list.url)) stop()
        } finally {
            moving = false
        }
    }

    private suspend fun save(queue: Playing) {
        playing = queue
        val json = queue.json()
        withContext(ioDispatcher) {
            try {
                // Written aside first, so one cut short never leaves a broken file.
                val temporary = File(file.path + ".tmp")
                temporary.writeText(json)
                temporary.renameTo(file)
            } catch (_: IOException) {
                // Not kept, it only won't carry on after Crosstune is stopped.
            }
        }
    }

    // Unreadable, or from another version, there's nothing to carry on.
    private fun read(): Playing? = runCatching { if (file.isFile) Playing.from(JSONObject(file.readText())) else null }.getOrNull()

    /**
     * The playlist's [tracks], played through in [app], of [service]: the part playing ends at [to],
     * holding the songs found of those, its [part]; [first] is the app's number for the first of
     * them in its queue, once seen, and [heard] where in the part each song it played is, as
     * shuffled there, it plays them out of order.
     */
    private data class Playing(
        val tracks: List<MusicMetadata>,
        val service: MusicService,
        val app: String,
        val to: Int,
        val part: List<MusicMetadata>,
        val first: Long? = null,
        val heard: Set<Int> = emptySet()
    ) {
        fun json(): String = JSONObject()
            .put("tracks", songs(tracks))
            .put("service", service.name)
            .put("app", app)
            .put("to", to)
            .put("part", songs(part))
            .putOpt("first", first)
            .put("heard", JSONArray(heard.sorted()))
            .toString()

        companion object {
            fun from(json: JSONObject): Playing? = Playing(
                songs(json.getJSONArray("tracks")),
                MusicService.fromName(json.getString("service")) ?: return null,
                json.getString("app"),
                json.getInt("to"),
                songs(json.getJSONArray("part")),
                if (json.has("first")) json.getLong("first") else null,
                json.optJSONArray("heard")?.let { heard -> (0 until heard.length()).mapTo(mutableSetOf(), heard::getInt) }.orEmpty()
            )

            /** Each song as its name, its artist's, and its link, as Recent keeps a playlist's. */
            private fun songs(songs: List<MusicMetadata>) = JSONArray().apply { songs.forEach { put(JSONArray().put(it.title).put(it.artist).put(it.url.orEmpty())) } }

            private fun songs(json: JSONArray) = (0 until json.length()).map { index ->
                val song = json.getJSONArray(index)
                MusicMetadata(song.getString(0), song.getString(1), url = song.getString(2).ifEmpty { null })
            }
        }
    }
}
