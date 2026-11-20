package com.astrovm.crosstune

/** Whether [one] and [other] are the same recording of the same song, whichever way each service names it. */
internal fun sameSong(one: MusicMetadata, other: MusicMetadata) =
    SongNames.titleNames(one.title).any { title -> SongNames.titleNames(other.title).any { SongNames.same(title, it) } } && SongNames.sameArtist(one.artist, other.artist) &&
        (one.recordingId == null || other.recordingId == null || one.recordingId == other.recordingId)
