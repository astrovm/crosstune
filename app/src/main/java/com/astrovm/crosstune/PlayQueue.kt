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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * A playlist longer than YouTube takes in one queue, played through by Crosstune: YouTube or YouTube
 * Music is asked for [MainViewModel.MAX_QUEUE] songs at a time, and for the next ones as the last of
 * those ends. An app that takes no list, [MusicService.playsSongBySong], is asked for one song at a
 * time instead, or opened on it, by its own link, where it plays none asked for. Kept on disk, so it carries on after Android stops Crosstune, which it starts again
 * to show it what plays. A list as short as one part is followed too, for the player to show it.
 */
internal class PlayQueue(
    private val playback: PlaybackSource,
    /** [tracks] as one queue in a [MusicService], see [ExactMatcher.youtubeQueue]. */
    private val queueOf: suspend (MusicService, List<MusicMetadata>) -> YouTubeQueue?,
    /** A song's own link in a [MusicService], see [ExactMatcher.find]; null where it isn't found. */
    private val linkOf: suspend (MusicService, MusicMetadata) -> String?,
    private val file: File,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Whether an app is installed; see [sameApp]. */
    private val installed: (String) -> Boolean = { false }
) {
    private var playing: Playing? = null
        set(value) {
            field = value
            shown.value = value
        }

    /** [playing], for the player to show what plays from it. */
    private val shown = MutableStateFlow<Playing?>(null)
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
     * Plays on through [tracks], the list called [name], in [app], after the [part] of them it was
     * just asked for, which ends at [to]; any playlist played through before stops.
     */
    suspend fun play(tracks: List<MusicMetadata>, service: MusicService, app: String, to: Int, part: List<MusicMetadata>, name: String? = null) {
        stop()
        before = playback.plays(app).first()
        // An app numbering songs by what they are, as TIDAL does, numbers nothing in order.
        floor = before?.queueId?.takeUnless { service.playsSongBySong }
        start(Playing(tracks, service, app, minOf(to, tracks.size), part, name = name))
    }

    /**
     * [playback], where skipping on from the song that ends the part plays the next part at once,
     * even where the app can't skip on by itself, as YouTube can't from a list's last song. Back
     * stays the app's, as it can't be asked to start a part at its last song. A song of the list
     * shows it's from it, with the list's songs still to come up next.
     */
    fun skipping(playback: PlaybackSource): PlaybackSource = object : PlaybackSource by playback {
        override fun follow(song: MusicMetadata): Flow<Following?> = combine(playback.follow(song), onLast, shown) { following, last, list ->
            if (following == null) return@combine null
            val skips = if (last != null && sameEntry(last, song)) following.copy(canSkipNext = true) else following
            // In the app it's played through, not another that happens to play one of its songs.
            list?.takeIf { sameApp(it.app, following.appPackage, installed) && it.tracks.any { track -> sameEntry(track, song) } }?.let { skips.playedFrom(it) } ?: skips
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
        job = null
        forgetNext()
        playing = null
        file.delete()
    }

    /** Moves the list's song still to ask for at [from], among those, to [to], so it plays then. */
    suspend fun move(from: Int, to: Int) {
        val queue = playing ?: return
        val later = queue.tracks.drop(queue.to).toMutableList()
        if (from !in later.indices || to !in later.indices) return
        later.add(to, later.removeAt(from))
        // The next part was looked up in the order before.
        forgetNext()
        save(queue.copy(tracks = queue.tracks.take(queue.to) + later))
    }

    /** Plays the list on from its song still to ask for at [index], among those, past the ones before it. */
    suspend fun playFrom(index: Int) {
        val queue = playing ?: return
        if (index !in 0 until queue.tracks.size - queue.to) return
        forgetNext()
        save(queue.copy(to = queue.to + index))
        playNext()
    }

    private fun forgetNext() {
        next?.cancel()
        next = null
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
        // The song it was on, by another number, was numbered anew, as YouTube Music may as it fills a
        // list it had stopped on with another, first saying the song by its old one: they count from there.
        val kept = queue.first?.takeUnless { now.queueId != null && !queue.bySong && named >= 0 && named == at && now.queueId - named != it }
        val first = kept ?: now.queueId?.takeIf { named >= 0 }?.let { it - named }?.also { save(queue.copy(first = it)) }
        val numbered = first != null && now.queueId != null
        val index = if (numbered && !queue.bySong) (now.queueId - first).toInt() else named
        // Named as another of the part's songs than its number says, the numbers are another queue's: the app
        // started over, as YouTube Music numbers from 1 again once it was stopped, and plays something else.
        if (numbered && !queue.bySong && named >= 0 && index in part.indices && !sameEntry(part[index], now.song)) return stop()
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
                if (next == null && !queue.bySong) lookAhead()
            }
            // Still what played before the part.
            at < 0 -> Unit
            // On from the part's last song, or shuffled, from all of them, to what the app plays after it;
            // one by one, what comes right after the song in the app's queue, as its radio does, or, as
            // Spotify keeps nothing before the song in its queue, what it numbers next, as its album.
            (at == part.lastIndex || queue.heard.size == part.size) && when {
                numbered && queue.bySong -> now.previousId == first || now.queueId == first + 1
                numbered -> index == part.size
                else -> index < 0
            } -> playNext()
            // Nothing before it in the app's queue yet: TIDAL goes on to its radio a moment before it
            // puts it there, after the song.
            numbered && queue.bySong && now.previousId == null -> Unit
            // Something else was picked to play; without numbers, it may only be the app's own name for one of the part's songs.
            numbered -> stop()
        }
    }

    /**
     * [following], a song of [list], played from it: next, what the app has of the part, before
     * anything it plays on with after it, e.g. its radio, then the list's songs still to ask for.
     * An app that takes no list has only its radio after the song.
     */
    private fun Following.playedFrom(list: Playing): Following {
        val queued = if (list.bySong) emptyList() else upNext.takeWhile { next -> list.part.any { sameEntry(it, MusicMetadata(next.title, next.artist)) } }
        return copy(from = list.name, upNext = queued + list.tracks.drop(list.to).map { Queued(it.title, it.artist, art = it.artworkUrl) })
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
            if (queue.bySong) return playSong(queue)
            val list = (next ?: lookAhead()).await() ?: return stop()
            next = null
            at = -1
            save(queue.copy(to = minOf(queue.to + MainViewModel.MAX_QUEUE, queue.tracks.size), part = list.songs, first = null, heard = emptySet()))
            if (!playback.playList(queue.app, list.url)) stop()
        } finally {
            moving = false
        }
    }

    /**
     * The next of [queue]'s songs, asked of its app by name, as it takes no list, or opened there on
     * its own link where it plays none asked for; one that isn't found there is left out.
     */
    private suspend fun playSong(queue: Playing) {
        val song = queue.tracks.getOrNull(queue.to) ?: return stop()
        at = -1
        save(queue.copy(to = queue.to + 1, part = listOf(song), first = null, heard = emptySet()))
        if (queue.service.playsAskedSongs) {
            if (!playback.playSong(song, queue.app)) stop()
            return
        }
        val link = linkOf(queue.service, song) ?: return playSong(checkNotNull(playing))
        // Spotify plays one opened with the screen off or locked only once it's in front, as the phone's
        // unlocked, and plays its album on meanwhile: what it went on to is paused, so the list waits.
        val locked = !playback.unlocked()
        if (locked) playback.plays(queue.app).first()?.let { playback.playPause(it.song, play = false) }
        if (!playback.openSong(queue.app, link)) return stop()
        if (locked) return
        // Back over the app once it plays the song, as the words are Crosstune's.
        if (withTimeoutOrNull(MainViewModel.LOAD_SONG_MS) { playback.plays(queue.app).first { it != null && sameEntry(song, it.song) } } != null) playback.comesBack()
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
     * shuffled there, it plays them out of order. [name] is the list's, when it has one.
     */
    private data class Playing(
        val tracks: List<MusicMetadata>,
        val service: MusicService,
        val app: String,
        val to: Int,
        val part: List<MusicMetadata>,
        val first: Long? = null,
        val heard: Set<Int> = emptySet(),
        val name: String? = null
    ) {
        val bySong: Boolean get() = service.playsSongBySong

        fun json(): String = JSONObject()
            .put("tracks", songs(tracks))
            .put("service", service.name)
            .put("app", app)
            .put("to", to)
            .put("part", songs(part))
            .putOpt("first", first)
            .put("heard", JSONArray(heard.sorted()))
            .putOpt("name", name)
            .toString()

        companion object {
            fun from(json: JSONObject): Playing? = Playing(
                songs(json.getJSONArray("tracks")),
                MusicService.fromName(json.getString("service")) ?: return null,
                json.getString("app"),
                json.getInt("to"),
                songs(json.getJSONArray("part")),
                if (json.has("first")) json.getLong("first") else null,
                json.optJSONArray("heard")?.let { heard -> (0 until heard.length()).mapTo(mutableSetOf(), heard::getInt) }.orEmpty(),
                json.optString("name").ifEmpty { null }
            )

            /** Each song as its name, its artist's, its link, as Recent keeps a playlist's, and its cover's, to show up next. */
            private fun songs(songs: List<MusicMetadata>) = JSONArray().apply {
                songs.forEach { put(JSONArray().put(it.title).put(it.artist).put(it.url.orEmpty()).put(it.artworkUrl.orEmpty())) }
            }

            private fun songs(json: JSONArray) = (0 until json.length()).map { index ->
                val song = json.getJSONArray(index)
                // Kept before covers were, a song has none.
                MusicMetadata(song.getString(0), song.getString(1), artworkUrl = song.optString(3).ifEmpty { null }, url = song.getString(2).ifEmpty { null })
            }
        }
    }
}
