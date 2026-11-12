package com.astrovm.crosstune

/**
 * readings-golden.tsv's rows, and the one way a row is checked: the parts the app reads over one
 * occurrence of a word in a stretch of a line, written `text=reading|text`; the word sheet's romaji for it;
 * and the parts it shows there once the song's words put the word's reading right. [ReadingsGoldenTest]
 * checks each row with [broken], and the songcheck `GoldenContexts` finds each row's stretch and what it
 * locks with the same functions, so the two agree.
 */
internal object GoldenReadings {

    const val RESOURCE = "/com/astrovm/crosstune/readings-golden.tsv"

    /** The header line that says how many rows follow, so a cut or emptied file fails. */
    const val COUNT = "# rows: "

    val DICTIONARIES = setOf("ipadic", "unidic", "both")

    /**
     * A locked reading. [sheet] is the word sheet's `romanized|typed` for the word, and [shown] the parts shown over it once a
     * fix reads it [kana]; each is empty when the row doesn't lock it.
     */
    data class Row(
        val line: String,
        val word: String,
        val index: Int?,
        val kana: String,
        val ruby: String,
        val dictionary: String,
        val sheet: String = "",
        val shown: String = ""
    ) {
        fun readBy(dictionary: String) = this.dictionary == "both" || this.dictionary == dictionary
    }

    /** The rows of [text]. Fails on a row that is not whole, and when the count in the header is not the number of rows. */
    fun rows(text: String): List<Row> {
        val lines = text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toList()
        val counts = lines.filter { it.startsWith(COUNT) }
        check(counts.size == 1) { "readings-golden.tsv needs one line that starts with \"$COUNT\"" }
        val count = counts.single().removePrefix(COUNT).toIntOrNull()
        val rows = lines.filterNot { it.startsWith("#") }.map(::row)
        check(count != null && count > 0 && count == rows.size) { "readings-golden.tsv says ${counts.single()} and has ${rows.size} rows" }
        return rows
    }

    private fun row(raw: String): Row {
        val fields = raw.split('\t')
        check(fields.size == FIELDS) { "A golden row needs $FIELDS fields: $raw" }
        val (line, word, index, kana, ruby) = fields
        val sheet = fields[SHEET]
        val shown = fields[SHOWN]
        val dictionary = fields[FIELDS - 1]
        check(dictionary in DICTIONARIES) { "A golden row's dictionary is ipadic, unidic or both: $raw" }
        check(index.isEmpty() || index.all(Char::isDigit)) { "A golden row's index is empty or a number: $raw" }
        check(spelled(ruby) == word) { "A golden row's ruby has to spell its word: $raw" }
        check(read(ruby) == kana) { "A golden row's ruby has to read as its kana: $raw" }
        check(sheet.isEmpty() || sheet.split('|').let { it.size == 2 && it.all(String::isNotEmpty) }) {
            "A golden row's sheet is empty or romanized|typed: $raw"
        }
        // The parts shown after the fix may split the word otherwise, but they spell it and read it the same.
        check(shown.isEmpty() || (spelled(shown) == word && Readings.hiragana(read(shown)) == Readings.hiragana(kana))) {
            "A golden row's shown parts are empty or spell its word as its kana: $raw"
        }
        return Row(line, word, index.ifEmpty { null }?.toInt(), kana, ruby, dictionary, sheet, shown)
    }

    private fun parts(ruby: String) = ruby.split('|').map { it.substringBefore('=') to it.substringAfter('=', "") }

    /** What parts written `text=reading|text` spell, and how they read. */
    private fun spelled(ruby: String) = parts(ruby).joinToString("") { it.first }

    private fun read(ruby: String) = parts(ruby).joinToString("") { it.second.ifEmpty { it.first } }

    /**
     * What [row] gets wrong with [parts], the parts read over its line, and [words], the word sheet of its line, for the
     * dictionary that read them; null when the row holds. Empty locks ask nothing.
     */
    fun broken(row: Row, parts: List<Ruby>, words: List<Word>): String? {
        val ruby = rubyAt(parts, row.line, row.word, row.index)
        val sheet = sheetAt(words, row.line, row.word, row.index, row.kana)
        val shown = shownAt(parts, row.line, row.word, row.index, row.kana)
        return listOfNotNull(
            "ruby=${row.ruby} got=${ruby ?: parts.joinToString("|") { it.text }}".takeIf { ruby != row.ruby },
            "sheet=${row.sheet} got=$sheet".takeIf { row.sheet.isNotEmpty() && sheet != row.sheet },
            "shown=${row.shown} got=$shown".takeIf { row.shown.isNotEmpty() && shown != row.shown }
        ).joinToString(" ").ifEmpty { null }
    }

    /**
     * The word sheet's romaji for the occurrence of [word] in [line] that [index] names, `romanized|typed`, when the sheet
     * has a word just there that reads [kana]; empty when it hasn't. Its words are found in the line in turn, as
     * [Readings.asShown] finds them.
     */
    fun sheetAt(words: List<Word>, line: String, word: String, index: Int?, kana: String): String {
        val start = startOf(line, word, index) ?: return ""
        var from = 0
        for (each in words) {
            val at = line.indexOf(each.text, from).takeIf { it >= 0 } ?: continue
            from = at + each.text.length
            if (at == start && each.text == word && each.reading?.let(Readings::hiragana) == Readings.hiragana(kana)) {
                return listOfNotNull(each.romanized, each.typed).takeIf { it.size == 2 }?.joinToString("|").orEmpty()
            }
        }
        return ""
    }

    /**
     * The parts the app shows over the occurrence of [word] in [line] that [index] names, written as [rubyAt] writes them,
     * when the song's words read it [kana] and the dictionary had it otherwise: its parts lose their readings, then
     * [Readings.corrected] makes the fix. Empty when what it shows doesn't read [kana].
     */
    fun shownAt(parts: List<Ruby>, line: String, word: String, index: Int?, kana: String): String {
        if (parts.joinToString("") { it.text } != line) return ""
        val start = startOf(line, word, index) ?: return ""
        val end = start + word.length
        var at = 0
        val blank = parts.map { part ->
            val from = at
            at += part.text.length
            if (from >= start && at <= end) Ruby(part.text) else part
        }
        val fix = ReadingFix(0, word, Readings.hiragana(kana), Reader.SUNG)
        val shown = over(Readings.corrected(listOf(LineReading(blank, "", "")), listOf(fix)).single().parts, start, end)
        return shown?.takeIf { Readings.hiragana(read(it)) == Readings.hiragana(kana) }.orEmpty()
    }

    /** The parts [parts] puts over the occurrence of [word] in [line] that [index] names; null when they don't start and end with it. */
    fun rubyAt(parts: List<Ruby>, line: String, word: String, index: Int?): String? {
        if (parts.joinToString("") { it.text } != line) return null
        val start = startOf(line, word, index) ?: return null
        return over(parts, start, start + word.length)
    }

    /** The parts of [parts] from letter [start] to [end], as [rubyAt] writes them; null when none starts or ends there. */
    private fun over(parts: List<Ruby>, start: Int, end: Int): String? {
        val over = mutableListOf<String>()
        var at = 0
        for (part in parts) {
            val stop = at + part.text.length
            if (stop > start && at < end) {
                // A part that runs past the word means the dictionary split the line differently.
                if (at < start || stop > end) return null
                over += part.reading?.let { "${part.text}=$it" } ?: part.text
            }
            at = stop
        }
        return over.joinToString("|")
    }

    /** Where the occurrence of [word] in [line] that [index] names starts: null when [index] isn't there, or is needed and missing. */
    private fun startOf(line: String, word: String, index: Int?): Int? {
        val found = starts(line, word)
        return when {
            index == null && found.size == 1 -> found[0]
            index != null && found.size > 1 && index in found.indices -> found[index]
            else -> null
        }
    }

    /** Where [word] starts in [line], a word that overlaps itself too. The same search as readingsaudit.find_starts. */
    fun starts(line: String, word: String): List<Int> {
        val found = mutableListOf<Int>()
        var at = 0
        while (true) {
            val next = line.indexOf(word, at)
            if (next < 0) return found
            found += next
            at = next + 1
        }
    }

    /**
     * The stretches of [line] around the occurrence of [word] at [start], line[from, to), and how [read] reads the word in each.
     * A stretch is good when it reads the word as [ruby], and stable when it and every wider stretch of the line around it,
     * up to the whole line, is good. A row on a stable stretch holds, whatever the rest of the line around it is.
     */
    class Around(
        private val line: String,
        private val word: String,
        private val start: Int,
        private val ruby: String,
        private val read: (String) -> List<Ruby>
    ) {
        private val end = start + word.length
        private val good = HashMap<Long, Boolean>()
        private val stable = HashMap<Long, Boolean>()

        /** Whether line[from, to) can be read at all: it splits no letter in two. */
        private fun whole(from: Int, to: Int) =
            !(from > 0 && line[from].isLowSurrogate()) && !(to < line.length && line[to].isLowSurrogate())

        /** Whether line[from, to) reads the word as [ruby]. A stretch that splits a letter can't be read, and asks nothing. */
        fun good(from: Int, to: Int): Boolean = !whole(from, to) || good.getOrPut(key(from, to)) {
            val (stretch, index) = stretch(from, to)
            rubyAt(read(stretch), stretch, word, index) == ruby
        }

        /** Whether line[from, to) and every wider stretch of the line around it reads the word as [ruby]. */
        fun stable(from: Int, to: Int): Boolean = stable.getOrPut(key(from, to)) {
            // The wider ones first: a stretch one letter wider on either side covers them all, and is read first.
            (from == 0 || stable(from - 1, to)) && (to == line.length || stable(from, to + 1)) && good(from, to)
        }

        /** The shortest stable stretch a row can hold, as from and to; null when not even the whole line is. Of two as short, the one that starts first. */
        fun shortestStable(): Pair<Int, Int>? = spans((word.length..line.length)).firstOrNull { (from, to) -> stable(from, to) }

        /**
         * [span] with up to [width] more letters of the line on each side, as far as a row can hold them: a change that
         * breaks the reading only with a little more of the line fails the row too. A stretch wider than a stable one is stable.
         */
        fun widened(span: Pair<Int, Int>, width: Int): Pair<Int, Int> {
            var (from, to) = span
            repeat(width) {
                val left = if (from >= 2 && line[from - 1].isLowSurrogate()) from - 2 else from - 1
                if (left >= 0 && fits(line, left, to)) from = left
                val right = if (to + 1 < line.length && line[to].isHighSurrogate()) to + 2 else to + 1
                if (right <= line.length && fits(line, from, right)) to = right
            }
            return from to to
        }

        /**
         * The longest stretch a row can hold that is shorter than the line and is none of [lines]: a stable one when there
         * is one, else a good one; null when there is none.
         */
        fun longestShortOfLine(lines: Set<String>): Pair<Int, Int>? {
            val shorter = spans((line.length - 1 downTo word.length)).filter { (from, to) -> line.substring(from, to) !in lines }
            return shorter.firstOrNull { (from, to) -> stable(from, to) } ?: shorter.firstOrNull { (from, to) -> good(from, to) }
        }

        /** The stretches around the word a row can hold, by [lengths], then from the first start. */
        private fun spans(lengths: IntProgression) = lengths.asSequence().flatMap { length ->
            (maxOf(0, end - length)..start).asSequence().map { it to it + length }
                .filter { (from, to) -> to <= line.length && fits(line, from, to) }
        }

        /** line[from, to), and the index of the word's occurrence in it, null when it holds the word once. */
        fun stretch(from: Int, to: Int): Pair<String, Int?> {
            val stretch = line.substring(from, to)
            val found = starts(stretch, word)
            return stretch to found.indexOf(start - from).takeIf { found.size > 1 }
        }

        private fun key(from: Int, to: Int) = from.toLong() shl Int.SIZE_BITS or to.toLong()
    }

    /** Whether line[from, to) can be a row's line: whole characters, not a comment, nothing that ends a line in Kotlin or Python. */
    fun fits(line: String, from: Int, to: Int): Boolean {
        if (from > 0 && line[from].isLowSurrogate()) return false
        if (to < line.length && line[to].isLowSurrogate()) return false
        return line[from] != '#' && (from until to).none { line[it] in LINE_ENDS }
    }

    /** Characters Python's splitlines or Kotlin's lineSequence take for the end of a line, and the field separator. */
    val LINE_ENDS = setOf('\t', '\n', '\r', '\u000B', '\u000C', '\u001C', '\u001D', '\u001E', '\u0085', ' ', ' ')

    private const val SHEET = 5
    private const val SHOWN = 6
    private const val FIELDS = 8
}
