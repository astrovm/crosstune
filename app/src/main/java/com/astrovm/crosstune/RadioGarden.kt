package com.astrovm.crosstune

import android.media.session.MediaSession
import java.text.Normalizer
import java.util.Locale

/** Radio Garden puts the station in the queue title and the stream's text or the city in its subtitle. */
internal object RadioGarden {
    const val PACKAGE = "com.jonathanpuckey.radiogarden"

    private const val IHEART_TITLE = "text=\""

    /** The stream's claim, to check in a catalog before showing it. */
    fun queueSong(station: String, queue: List<MediaSession.QueueItem>?, activeId: Long? = null): MusicMetadata? {
        val item = activeItem(queue.orEmpty(), activeId)?.description ?: return null
        // The queue and session metadata can arrive separately when the user changes stations.
        if (item.title?.toString() != station) return null
        val subtitle = item.subtitle?.toString()?.trim() ?: return null
        if (subtitle.equals(station, ignoreCase = true)) return null
        // A separator can be part of an ad marker or markup.
        if (hasAdMarker(subtitle) || '<' in subtitle || '>' in subtitle) return null
        val dash = splitAt(subtitle) ?: return null
        val artist = subtitle.substring(0, dash).trimEnd()
        if (artist.equals(station, ignoreCase = true) || hasKeyValue(artist)) return null
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

    private fun hasAdMarker(text: String): Boolean {
        val spot = "spot"
        val block = "block"
        var at = text.indexOf(spot, ignoreCase = true)
        while (at >= 0) {
            val afterSpot = at + spot.length
            val blockStart = text.afterSpaces(afterSpot)
            val blockEnd = blockStart + block.length
            val startsWord = text.getOrNull(at - 1)?.isLetterOrDigit() != true
            val endsWord = text.getOrNull(blockEnd)?.isLetterOrDigit() != true
            val separated = startsWord && blockStart > afterSpot && endsWord
            if (separated && text.startsWith(block, blockStart, ignoreCase = true)) return true
            at = text.indexOf(spot, afterSpot, ignoreCase = true)
        }
        return false
    }

    private fun hasKeyValue(text: String): Boolean = text.indices.any { at ->
        if (text[at] != '=') return@any false
        var before = at - 1
        while (before >= 0 && text[before].isWhitespace()) before--
        val last = text.getOrNull(before) ?: return@any false
        last in "_.:-" || Character.isLetterOrDigit(text.codePointBefore(before + 1))
    }

    private fun readTitle(streamTitle: String): String? =
        (if (streamTitle.startsWith("text=")) iheartTitle(streamTitle) else streamTitle)
            ?.takeUnless { it.isBlank() || hasKeyValue(it) || isNews(it) }

    private fun isNews(text: String): Boolean =
        isNewsName(text, "the", "news") || isNewsName(text, "die", "nachrichten")

    private fun isNewsName(text: String, article: String, noun: String): Boolean {
        if (text.equals(noun, ignoreCase = true)) return true
        return text.startsWith(article, ignoreCase = true) &&
            text.getOrNull(article.length)?.isWhitespace() == true &&
            text.substring(article.length).trimStart().equals(noun, ignoreCase = true)
    }

    private fun iheartTitle(text: String): String? {
        if (!text.startsWith(IHEART_TITLE)) return null
        val end = text.indexOf('"', IHEART_TITLE.length)
        if (end < 0 || !iheartMusicFields(text, end + 1)) return null
        return text.substring(IHEART_TITLE.length, end).trim()
    }

    /** The remaining text must be quoted fields. A spot type other than music makes the whole item unusable. */
    private fun iheartMusicFields(text: String, start: Int): Boolean {
        var at = start
        while (at < text.length) {
            if (!text[at].isWhitespace()) return false
            val keyStart = text.afterSpaces(at)
            val equals = text.indexOf('=', keyStart)
            if (equals <= keyStart || text.getOrNull(equals + 1) != '"') return false
            val key = text.substring(keyStart, equals)
            if (!key.all { it.isLetterOrDigit() || it in "_-" }) return false
            val valueStart = equals + 2
            val end = text.indexOf('"', valueStart)
            if (end < 0) return false
            if (key.equals("song_spot", ignoreCase = true) && text.substring(valueStart, end) != "M") return false
            at = end + 1
        }
        return true
    }

    private fun String.afterSpaces(from: Int): Int {
        var at = from
        while (at < length && this[at].isWhitespace()) at++
        return at
    }

    /** Accent-insensitive for Latin names, but a voiced kana and its unvoiced form spell different words. */
    fun sameTitle(one: String, another: String): Boolean = titleKey(one) == titleKey(another)

    private fun titleKey(title: String): String {
        val fullWidth = Normalizer.normalize(SongNames.withoutEditionTag(title), Normalizer.Form.NFKC)
        val decomposed = Normalizer.normalize(fullWidth, Normalizer.Form.NFD).lowercase(Locale.ROOT)
        return buildString(decomposed.length) {
            decomposed.codePoints().forEach { code ->
                if (Character.isLetterOrDigit(code) || code == '\u3099'.code || code == '\u309a'.code) appendCodePoint(code)
            }
        }
    }
}
