package com.astrovm.crosstune

/** Keeps different performances and video edits from sharing lyrics or corrections. */
internal object RecordingKey {
    fun of(song: MusicMetadata): String {
        val name = "${SongNames.normalize(song.title)}\u0000${SongNames.normalize(song.artist)}"
        val link = song.url?.let { MusicLinks.parse(it) as? LinkInput.Link }?.link
        val recording = song.recordingId ?: link?.takeIf { it.type == ItemType.TRACK }?.let { "${it.service}:${it.id}" }
        return name + (recording?.let { "\u0000$it" } ?: "") + (song.lengthMs?.takeIf { it > 0 }?.let { "\u0000$it" } ?: "")
    }

    /** YouTube's exact video, when the link is known. A search match isn't the playing video. */
    fun youTubeId(song: MusicMetadata): String? {
        val link = song.url?.let { MusicLinks.parse(it) as? LinkInput.Link }?.link ?: return null
        return link.takeIf { it.type == ItemType.TRACK && it.service in setOf(MusicService.YOUTUBE, MusicService.YOUTUBE_MUSIC) }?.id
    }

    /** Android players can omit the ID, as YouTube Music does; their duration still separates edits. */
    fun playing(app: String, id: String?, durationMs: Long?): String? =
        id?.takeIf { it.isNotBlank() }?.let { "$app:$it" }
            ?: durationMs?.takeIf { it > 0 }?.let { "$app:duration:$it" }
}
