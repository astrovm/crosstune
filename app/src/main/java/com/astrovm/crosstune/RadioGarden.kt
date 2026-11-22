package com.astrovm.crosstune

import android.media.session.MediaSession
import androidx.core.util.PatternsCompat
import java.text.Normalizer
import java.util.Locale

/** Radio Garden puts the station in the queue title and the stream's text or the city in its subtitle. */
internal object RadioGarden {
    const val PACKAGE = "com.jonathanpuckey.radiogarden"

    private const val IHEART_TITLE = "text=\""
    private const val PHONE_DIGITS = 10

    // Match whole broadcast phrases. Words such as "Live" can be artist names or song titles.
    private val broadcastLabels = listOf(
        "on air", "on the air", "on air now", "now playing", "live stream",
        "auf sendung", "jetzt läuft", "live auf sendung",
        "en vivo", "en directo", "al aire", "sonando ahora", "transmisión en vivo",
        "en direct", "à l'antenne", "en cours de lecture", "diffusion en direct",
        "प्रसारण जारी", "अभी चल रहा है", "सीधा प्रसारण",
        "siaran langsung", "sedang mengudara", "sedang diputar",
        "in diretta", "in onda", "in riproduzione",
        "放送中", "再生中", "ライブ配信中",
        "방송 중", "재생 중", "실시간 방송",
        "in de ether", "wordt nu afgespeeld", "live uitzending",
        "na żywo", "na antenie", "teraz odtwarzane", "transmisja na żywo",
        "ao vivo", "no ar", "tocando agora", "transmissão ao vivo",
        "прямой эфир", "в эфире", "сейчас играет",
        "canlı yayın", "şimdi çalıyor",
        "播出中", "正在播放", "現正播放", "正在直播", "直播中",
    ).mapTo(HashSet(), ::labelKey)

    private val newsTitles = listOf(
        "news", "the news", "nachrichten", "die nachrichten", "noticias", "actualités", "समाचार", "berita",
        "notizie", "ニュース", "뉴스", "nieuws", "wiadomości", "notícias", "новости", "haberler", "新闻", "新聞",
    ).mapTo(HashSet(), ::labelKey)

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
        if (artist.equals(station, ignoreCase = true) || invalidField(artist)) return null
        val title = readTitle(subtitle.substring(dash + 1).trimStart(), station) ?: return null
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

    private fun readTitle(streamTitle: String, station: String): String? =
        (if (streamTitle.startsWith("text=")) iheartTitle(streamTitle) else streamTitle)
            ?.takeUnless { it.equals(station, ignoreCase = true) || invalidField(it, title = true) }

    private fun invalidField(text: String, title: Boolean = false): Boolean {
        if (text.isBlank() || hasKeyValue(text)) return true
        val key = labelKey(text)
        val broadcast = key in broadcastLabels || (title && key in newsTitles)
        return broadcast || hasAddress(text) || hasPhoneNumber(text)
    }

    private fun labelKey(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ı', 'i')
        .filterNot { it.isWhitespace() || it in TitleDelimiters.DASHES || it in "'’!?.,:…\"\u0307" }

    private fun hasAddress(text: String): Boolean {
        if ("://" in text) return true
        if ('@' in text && PatternsCompat.EMAIL_ADDRESS.matcher(text).find()) return true
        return '.' in text && PatternsCompat.WEB_URL.matcher(text).find()
    }

    private fun hasPhoneNumber(text: String): Boolean {
        var digits = 0
        var leadingPlus = false
        for ((index, char) in text.withIndex()) {
            when {
                char.isDigit() -> if (leadingPlus || ++digits >= PHONE_DIGITS) return true
                char.isWhitespace() || char == '.' || Character.getType(char) == Character.DASH_PUNCTUATION.toInt() -> Unit
                else -> {
                    digits = 0
                    leadingPlus = char == '+' && text.getOrNull(index - 1)?.isLetterOrDigit() != true
                }
            }
        }
        return false
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

    fun sameSong(one: MusicMetadata, another: MusicMetadata): Boolean =
        sameTitle(one.title, another.title) && SongNames.sameArtist(one.artist, another.artist)

    private fun titleKey(title: String): String {
        // NFKC turns a full-width tilde into an ASCII tilde. Keep it with the Japanese wave dash.
        val waveDashes = SongNames.withoutEditionTag(title).replace('～', '〜')
        val fullWidth = Normalizer.normalize(waveDashes, Normalizer.Form.NFKC)
        val decomposed = Normalizer.normalize(fullWidth, Normalizer.Form.NFD).lowercase(Locale.ROOT)
        return buildString(decomposed.length) {
            var afterKana = false
            decomposed.codePoints().forEach { code ->
                if (code == '\u3099'.code || code == '\u309a'.code) {
                    appendCodePoint(code)
                } else {
                    val prolonged = afterKana && code == '〜'.code
                    if (prolonged) append('ー') else if (Character.isLetterOrDigit(code)) appendCodePoint(code)
                    afterKana = prolonged || isKana(code)
                }
            }
        }
    }

    private fun isKana(code: Int): Boolean =
        code <= Char.MAX_VALUE.code && Character.isLetter(code) && Readings.isKana(code.toChar())
}
