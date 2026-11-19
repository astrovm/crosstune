package com.astrovm.crosstune

/** What the phone can still tell about a line kept before the dictionary's version was saved with it. */
internal class CachedSavedReadings(
    private val lyrics: LyricsFinder,
    private val sung: SungReadings,
    private val tutor: ChatGptTutor?
) {
    /** [kept] read again using only matching cached answers; null when nothing can be refreshed. */
    suspend fun refresh(kept: List<SavedLine>): List<SavedLine>? {
        val bySong = kept.filter { it.needsCachedReading }.groupBy { MusicMetadata(it.title, it.artist, lengthMs = it.lengthMs) }
        val refreshed = mutableMapOf<SavedLine, SavedLine>()
        for ((song, cards) in bySong) {
            val answers = answersFor(song, cards.mapTo(mutableSetOf()) { it.text })
            for (card in cards) {
                val answer = answers[card.text]?.singleOrNull() ?: continue
                refreshed[card] = card.rereadAs(card.readBy(answer.read(card.text)), Script.JAPANESE)
            }
        }
        return kept.map { refreshed[it] ?: it }.takeIf { it != kept }
    }

    private suspend fun answersFor(song: MusicMetadata, texts: Set<String>): Map<String, Set<Answer>> {
        val answers = mutableMapOf<String, MutableSet<Answer>>()
        for (sheet in lyrics.cachedLineSets(song)) {
            val matches = sheet.withIndex().filter { it.value in texts }
            if (matches.isEmpty()) continue
            val sungByLine = sung.cachedForSavedLines(song, sheet)?.groupBy { it.line } ?: continue
            val checked = tutor?.cachedReadingFixes(song, sheet).orEmpty().map { fixes -> fixes.groupBy { it.line } }.ifEmpty { listOf(emptyMap()) }
            for ((index, text) in matches) {
                // The same text may have been sung in two ways. Don't guess which occurrence the card kept.
                val possible = answers.getOrPut(text) { mutableSetOf() }
                for (model in checked) possible += Answer(sungByLine.forLine(index), model.forLine(index))
            }
        }
        return answers
    }

    private fun Map<Int, List<ReadingFix>>.forLine(index: Int) = get(index).orEmpty().map { it.copy(line = 0) }

    private data class Answer(val sung: List<ReadingFix>, val checked: List<ReadingFix>) {
        fun read(text: String): LineReading {
            val dictionary = Readings.of(listOf(text), Script.JAPANESE)
            return Readings.corrected(Readings.corrected(dictionary, sung), checked).single()
        }
    }
}
