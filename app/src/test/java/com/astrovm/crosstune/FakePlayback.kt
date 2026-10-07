package com.astrovm.crosstune

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A music app the test plays from: whether Crosstune may see it, what it plays, and where it was moved to. */
internal class FakePlayback : PlaybackSource {
    var access = false
    val playing = MutableStateFlow<Following?>(null)
    val seeks = mutableListOf<Long>()

    override fun hasAccess() = access

    override fun follow(song: MusicMetadata): Flow<Following?> = playing

    override fun seekTo(song: MusicMetadata, positionMs: Long) {
        seeks += positionMs
    }
}
