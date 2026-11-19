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
                refreshed[card] = answer.refresh(card)
            }
        }
        return kept.map { refreshed[it] ?: it }.takeIf { it != kept }
    }

    private suspend fun answersFor(song: MusicMetadata, texts: Set<String>): Map<String, Set<Answer>> {
        val answers = mutableMapOf<String, MutableSet<Answer>>()
        for (sheet in lyrics.cachedLineSets(song)) {
            val matches = sheet.withIndex().filter { it.value in texts }
            if (matches.isEmpty()) continue
            val sungByLine = sung.cachedForSavedLines(song, sheet).map { fixes -> fixes.groupBy { it.line } }
            if (sungByLine.isEmpty()) continue
            // ChatGPT may have put a line right and lost the answer since: then nothing can tell its fix from the old dictionary's.
            val kept = if (tutor == null) emptyList() else tutor.cachedReadingFixes(song, sheet) ?: continue
            val checked = kept.map { fixes -> fixes.groupBy { it.line } }.ifEmpty { listOf(emptyMap()) }
            for ((index, text) in matches) {
                // Two occurrences or old answers can disagree. Don't guess which reading the card kept.
                val possible = answers.getOrPut(text) { mutableSetOf() }
                possible += sungByLine.flatMap { site -> checked.map { model -> Answer(site.forLine(index), model.forLine(index)) } }
            }
        }
        return answers
    }

    private fun Map<Int, List<ReadingFix>>.forLine(index: Int) = get(index).orEmpty().map { it.copy(line = 0) }

    private data class Answer(private val sung: List<ReadingFix>, private val checked: List<ReadingFix>) {
        fun refresh(card: SavedLine): SavedLine {
            val dictionary = Readings.of(listOf(card.text), Script.JAPANESE)
            val current = Readings.corrected(Readings.corrected(dictionary, sung), checked).single()
            return markCachedParts(card).rereadAs(card.readBy(current), Script.JAPANESE)
        }

        /** An old fix may fit the card's original parts but not the dictionary's new split. Keep the one we can identify. */
        private fun markCachedParts(card: SavedLine): SavedLine {
            val fixes = (sung + checked).associateBy { it.kanji to Readings.hiragana(it.reading) }
            val parts = card.ruby?.map { part ->
                val fix = part.reading?.let { fixes[part.text to Readings.hiragana(it)] }
                if (part.by == null && fix != null) part.copy(by = fix.by) else part
            }
            return card.copy(ruby = parts)
        }
    }
}
