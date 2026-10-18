package com.astrovm.crosstune

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
    val seeks = mutableListOf<Long>()
    /** Each ask to play (true) or pause (false). */
    val plays = mutableListOf<Boolean>()
    /** Each ask to go on to the next song (true) or back to the one before (false). */
    val skips = mutableListOf<Boolean>()
    /** Each song asked for by name, and the app asked. */
    val songsAsked = mutableListOf<Pair<MusicMetadata, String?>>()
    /** Each song's link given with it, when there was one. */
    val linksAsked = mutableListOf<String?>()
    /** Whether there's an app to ask for a song. */
    var canPlaySongs = true
    /** The apps running, with songs to be asked for; any, when null. */
    var running: Set<String>? = null
    /** Each app opened on a song's link, and the link. */
    val opened = mutableListOf<Pair<String, String>>()
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

    override fun nowPlayingUpdates(): Flow<Heard.Song?> = songs

    override fun seekTo(song: MusicMetadata, positionMs: Long) {
        seeks += positionMs
    }

    override fun playPause(song: MusicMetadata, play: Boolean) {
        plays += play
    }

    override fun playSong(song: MusicMetadata, app: String?, link: String?): Boolean {
        if (!canPlaySongs) return false
        songsAsked += song to app
        linksAsked += link
        return true
    }

    override fun runs(app: String) = running?.contains(app) ?: true

    override fun openSong(app: String, link: String): Boolean {
        opened += app to link
        return true
    }

    override fun comesBack() {
        cameBack++
    }

    override fun skip(song: MusicMetadata, next: Boolean) {
        skips += next
    }
}
