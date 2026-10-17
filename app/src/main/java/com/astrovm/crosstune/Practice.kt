package com.astrovm.crosstune

/**
 * The lines due at [now], at most [max], each song's together, in the order they're sung, so the
 * music app goes from one song to the next once, and only forward in each. The song due longest
 * comes first.
 */
internal fun sessionOf(lines: List<SavedLine>, now: Long, max: Int = SESSION_SIZE): List<SavedLine> =
    lines.filter { it.practisable && it.due <= now }
        .sortedBy { it.due }
        .take(max)
        .groupBy { it.title to it.artist }
        .values
        .flatMap { song -> song.sortedWith(compareBy(nullsLast()) { it.startMs }) }

/** How many cards a session has at most: enough to learn something, little enough to finish. */
internal const val SESSION_SIZE = 20

/**
 * The cards left once the first was missed: it comes back after the rest of its song, before the
 * next one, while the music app still has it.
 */
internal fun missedAgain(queue: List<SavedLine>): List<SavedLine> {
    val missed = queue.firstOrNull() ?: return queue
    val rest = queue.drop(1)
    val sameSong = rest.takeWhile { it.title == missed.title && it.artist == missed.artist }
    return sameSong + missed + rest.drop(sameSong.size)
}

/** Whether [one] and [other] are lines of the same song. */
internal fun sameSongOf(one: SavedLine, other: SavedLine?) = other != null && one.title == other.title && one.artist == other.artist

/**
 * Whether [text] is no more than sounds, e.g. "la la la", "Oh, oh" or "ラララ", which aren't worth
 * learning: nothing but letters that sing a sound, or no letters at all.
 */
internal fun isFiller(text: String): Boolean {
    val letters = text.filter { it.isLetter() }
    if (letters.isEmpty()) return true
    if (letters.all { it in FILLER_KANA }) return true
    val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    return words.all { word -> FILLER_WORD.matches(word) }
}

private const val FILLER_KANA = "ラらナなアあオおウうーぁッっ"

private val FILLER_WORD = Regex("(la|na|da|oh|ah|uh|eh|ooh|oo|hey|yeah|yeh|ya|woah|whoa|wo|mm+|hm+|ha|doo|ba|sha)+")

/** A line of a song, as it's offered to learn: once, however many times it's sung, from where it's first sung. */
internal data class SongLine(
    val index: Int,
    val text: String,
    /** How many times it's sung. */
    val times: Int,
    /** Only sounds, not worth learning, so not picked to start with. */
    val filler: Boolean,
    /** Has a word worth knowing, as ChatGPT picked them. */
    val newWords: Boolean
)

/** [lines] to pick from to learn, each once, with which are worth it, and which have [words] worth knowing. */
internal fun songLines(lines: List<String>, words: List<String>): List<SongLine> {
    val seen = LinkedHashMap<String, SongLine>()
    lines.forEachIndexed { index, line ->
        val text = line.trim()
        if (text.isEmpty()) return@forEachIndexed
        val key = text.lowercase()
        val known = seen[key]
        seen[key] = known?.copy(times = known.times + 1) ?: SongLine(index, text, 1, isFiller(text), words.any { it.isNotBlank() && text.contains(it, ignoreCase = true) })
    }
    return seen.values.toList()
}
