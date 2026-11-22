package com.astrovm.crosstune

import android.media.session.MediaSession

/** Radio Garden puts the station in the queue title and the stream's text or the city in its subtitle. */
internal object RadioGarden {
    const val PACKAGE = "com.jonathanpuckey.radiogarden"

    /** The stream's claim, to check in a catalog before showing it. */
    fun queueSong(station: String, queue: List<MediaSession.QueueItem>?): MusicMetadata? {
        val item = queue?.singleOrNull()?.description ?: return null
        // The queue and session metadata can arrive separately when the user changes stations.
        if (item.title?.toString() != station) return null
        val subtitle = item.subtitle?.toString()?.trim() ?: return null
        if (subtitle.equals(station, ignoreCase = true)) return null
        val dash = splitAt(subtitle) ?: return null
        val artist = subtitle.substring(0, dash).trimEnd()
        if (artist.equals(station, ignoreCase = true)) return null
        val title = readTitle(subtitle.substring(dash + 1).trimStart()) ?: return null
        return MusicMetadata(title, artist)
    }

    private fun splitAt(text: String): Int? = (1 until text.lastIndex).firstOrNull {
        text[it] in TitleDelimiters.DASHES && text[it - 1].isWhitespace() && text[it + 1].isWhitespace()
    }

    private fun readTitle(streamTitle: String): String? =
        (if (streamTitle.startsWith("text=")) iheartTitle(streamTitle) else streamTitle)
            ?.takeUnless { it.isBlank() || it.startsWith("Spot Block", ignoreCase = true) }

    private fun iheartTitle(streamTitle: String): String? {
        val fields = streamTitle.split('"', limit = 3)
        if (fields.size != 3 || fields[0] != "text=") return null
        val spot = fields[2].substringAfter(" song_spot=", "\"M\"")
        return fields[1].trim().takeIf { spot.startsWith("\"M\"") }
    }
}
