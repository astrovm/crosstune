package com.astrovm.crosstune

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A music app the test plays from: whether Crosstune may see it, or Android holds that back for now,
 * what it plays, and where it was moved to.
 */
internal class FakePlayback : PlaybackSource {
    var access = false
    var restricted = false
    val playing = MutableStateFlow<Following?>(null)
    val seeks: MutableList<Long> = CopyOnWriteArrayList()
    /** Each ask to play (true) or pause (false). */
    val plays: MutableList<Boolean> = CopyOnWriteArrayList()
    /** Each ask to go on to the next song (true) or back to the one before (false). */
    val skips: MutableList<Boolean> = CopyOnWriteArrayList()
    /** Each ask to shuffle (true) or play in order (false). */
    val shuffled: MutableList<Boolean> = CopyOnWriteArrayList()
    /** Each way it was asked to repeat. */
    val repeated: MutableList<Repeat> = CopyOnWriteArrayList()
    /** How many times its like was pressed. */
    var liked = 0
    /** Each song up next it was asked to play now, by its number. */
    val queuedPlayed: MutableList<Long> = CopyOnWriteArrayList()
    /** Each song asked for by name, and the app asked. */
    val songsAsked: MutableList<Pair<MusicMetadata, String?>> = CopyOnWriteArrayList()
    /** Each song's link given with it, when there was one. */
    val linksAsked: MutableList<String?> = CopyOnWriteArrayList()
    /** Whether there's an app to ask for a song. */
    var canPlaySongs = true
    /** The apps that can be asked for songs in the background, running or woken; any, when null. */
    var running: Set<String>? = null
    /** Each app opened on a song's link, and the link. */
    val opened: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
    /** How many times Crosstune came back to the front. */
    var cameBack = 0
    private val songs = MutableStateFlow<Heard.Song?>(null)
    var onThePhone: Heard.Song?
        get() = songs.value
        set(value) { songs.value = value }

    override fun hasAccess() = access

    override fun restricted() = restricted

    override fun follow(song: MusicMetadata): Flow<Following?> = playing

    override fun nowPlaying() = onThePhone

    override fun recording(song: MusicMetadata) = onThePhone?.metadata

    override fun nowPlayingUpdates(): Flow<Heard.Song?> = songs

    override fun seekTo(song: MusicMetadata, positionMs: Long) {
        seeks += positionMs
    }

    override fun playPause(song: MusicMetadata, play: Boolean) {
        plays += play
    }

    override suspend fun playSong(song: MusicMetadata, app: String?, link: String?): Boolean {
        // An app that can't be, with a link only it has, leaves no other to ask.
        if (!canPlaySongs || (app != null && link != null && running?.contains(app) == false)) return false
        songsAsked += song to app
        linksAsked += link
        return true
    }

    /** Whether an app can be opened on a song; false as if it couldn't, e.g. gone since. */
    var opens = true

    override fun openSong(app: String, link: String): Boolean {
        if (!opens) return false
        opened += app to link
        return true
    }

    override fun comesBack() {
        cameBack++
    }

    override fun skip(song: MusicMetadata, next: Boolean) {
        skips += next
    }

    override fun shuffle(song: MusicMetadata, on: Boolean) {
        shuffled += on
    }

    override fun repeat(song: MusicMetadata, how: Repeat) {
        repeated += how
    }

    override fun like(song: MusicMetadata) {
        liked++
    }

    override fun playQueued(song: MusicMetadata, id: Long) {
        queuedPlayed += id
    }

    private val inApps = mutableMapOf<String, MutableStateFlow<AppPlays?>>()

    /** What [app] plays, as a test has it. */
    fun inApp(app: String): MutableStateFlow<AppPlays?> = inApps.getOrPut(app) { MutableStateFlow(null) }

    override fun plays(app: String): Flow<AppPlays?> = inApp(app)

    /** Each list an app was asked to play, and its link. */
    val listsAsked: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    override suspend fun playList(app: String, link: String): Boolean {
        if (!canPlaySongs) return false
        listsAsked += app to link
        return true
    }
}
