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
}
