package com.astrovm.crosstune

import android.media.session.MediaSession
import java.text.Normalizer
import java.util.Locale

/** Radio Garden puts the station in the queue title and the stream's text or the city in its subtitle. */
internal object RadioGarden {
    const val PACKAGE = "com.jonathanpuckey.radiogarden"

    private val adMarker = Regex("""(?:^|[\s"=])spot\s++block\b""", RegexOption.IGNORE_CASE)
    private val newsTitle = Regex("""(?:die\s+)?nachrichten|(?:the\s+)?news""", RegexOption.IGNORE_CASE)
    private val keyValue = Regex("""(?<![\p{L}\p{N}_.:-])[\p{L}\p{N}_.:-]++\s*+=""")
    private val iHeartText = Regex("""text="([^"]*)"((?:\s++[\w-]++="[^"]*+")*+)""")
    private val spotType = Regex("""\ssong_spot="([^"]*)["]""")
    private val titleNoise = Regex("""[^\p{L}\p{N}\u3099\u309A]+""")

    /** The stream's claim, to check in a catalog before showing it. */
    fun queueSong(station: String, queue: List<MediaSession.QueueItem>?, activeId: Long? = null): MusicMetadata? {
        val item = activeItem(queue.orEmpty(), activeId)?.description ?: return null
        // The queue and session metadata can arrive separately when the user changes stations.
        if (item.title?.toString() != station) return null
        val subtitle = item.subtitle?.toString()?.trim() ?: return null
        if (subtitle.equals(station, ignoreCase = true)) return null
        // A separator can be part of an ad marker or markup.
        if (adMarker.containsMatchIn(subtitle) || '<' in subtitle || '>' in subtitle) return null
        val dash = splitAt(subtitle) ?: return null
        val artist = subtitle.substring(0, dash).trimEnd()
        if (artist.equals(station, ignoreCase = true) || keyValue.containsMatchIn(artist)) return null
        val title = readTitle(subtitle.substring(dash + 1).trimStart()) ?: return null
        return MusicMetadata(title, artist)
    }

    private fun activeItem(queue: List<MediaSession.QueueItem>, activeId: Long?): MediaSession.QueueItem? =
        if (activeId == null || activeId == MediaSession.QueueItem.UNKNOWN_ID.toLong()) {
            queue.singleOrNull()
        } else {
            queue.singleOrNull { it.queueId == activeId }
        }

    private fun splitAt(text: String): Int? = (1 until text.lastIndex).firstOrNull {
        text[it] in TitleDelimiters.DASHES && text[it - 1].isWhitespace() && text[it + 1].isWhitespace()
    }

    private fun readTitle(streamTitle: String): String? =
        (if (streamTitle.startsWith("text=")) iheartTitle(streamTitle) else streamTitle)
            ?.takeUnless { it.isBlank() || keyValue.containsMatchIn(it) || newsTitle.matches(it) }

    private fun iheartTitle(streamTitle: String): String? {
        val (title, fields) = iHeartText.matchEntire(streamTitle)?.destructured ?: return null
        return title.trim().takeIf { spotType.findAll(fields).all { it.groupValues[1] == "M" } }
    }

    /** Accent-insensitive for Latin names, but a voiced kana and its unvoiced form spell different words. */
    fun sameTitle(one: String, another: String): Boolean = titleKey(one) == titleKey(another)

    private fun titleKey(title: String): String {
        val fullWidth = Normalizer.normalize(SongNames.withoutEditionTag(title), Normalizer.Form.NFKC)
        return Normalizer.normalize(fullWidth, Normalizer.Form.NFD).lowercase(Locale.ROOT).replace(titleNoise, "")
    }
}
