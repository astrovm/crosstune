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
        /**
         * [card] read by the dictionary now, with each cached fix made where it was kept for and nowhere else. Where the
         * dictionary splits or joins the words so that a fix cuts through the middle of one of its parts, the card keeps
         * what it had there. Not guessed.
         */
        fun refresh(card: SavedLine): SavedLine {
            val dictionary = Readings.of(listOf(card.text), Script.JAPANESE).single()
            val (sungRead, sungCut) = Readings.correctedWhereKept(dictionary, sung)
            val (current, checkedCut) = Readings.correctedWhereKept(sungRead, checked)
            val cut = sungCut + checkedCut
            val kept = card.ruby ?: listOf(Ruby(card.text))
            val keptAt = kept.runningFold(0) { at, part -> at + part.text.length }
            val parts = keptOver(kept, current.parts, over = true) { index -> cut.any { (keptAt[index] until keptAt[index + 1]).intersect(it.start until it.end).isNotEmpty() } } ?: current.parts
            return card.readBy(Readings.readAs(named(parts, cut), Script.JAPANESE))
        }

        /** What a card kept where a fix stood is that fix's, whoever put it there, when it reads the same. */
        private fun named(parts: List<Ruby>, cut: List<Readings.Kept>): List<Ruby> {
            var at = 0
            return parts.map { part ->
                val start = at
                at += part.text.length
                val fix = cut.firstOrNull {
                    it.start == start && it.end == at && it.fix.kanji == part.text && part.reading?.let(Readings::hiragana) == Readings.hiragana(it.fix.reading)
                }
                if (part.by == null && fix != null) part.copy(by = fix.fix.by) else part
            }
        }
    }
}
