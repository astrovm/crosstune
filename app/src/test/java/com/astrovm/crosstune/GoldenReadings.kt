package com.astrovm.crosstune

/**
 * readings-golden.tsv's rows, and the one way a row is checked: the parts the app reads over one
 * occurrence of a word in a stretch of a line, written `text=reading|text`. [ReadingsGoldenTest] checks
 * each row with it, and the songcheck `GoldenContexts` finds each row's stretch with it, so the two agree.
 */
internal object GoldenReadings {

    const val RESOURCE = "/com/astrovm/crosstune/readings-golden.tsv"

    /** The header line that says how many rows follow, so a cut or emptied file fails. */
    const val COUNT = "# rows: "

    val DICTIONARIES = setOf("ipadic", "unidic", "both")

    data class Row(val line: String, val word: String, val index: Int?, val kana: String, val ruby: String, val dictionary: String) {
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
        val dictionary = fields[FIELDS - 1]
        check(dictionary in DICTIONARIES) { "A golden row's dictionary is ipadic, unidic or both: $raw" }
        check(index.isEmpty() || index.all(Char::isDigit)) { "A golden row's index is empty or a number: $raw" }
        val parts = ruby.split('|').map { it.substringBefore('=') to it.substringAfter('=', "") }
        check(parts.joinToString("") { it.first } == word) { "A golden row's ruby has to spell its word: $raw" }
        check(parts.joinToString("") { it.second.ifEmpty { it.first } } == kana) { "A golden row's ruby has to read as its kana: $raw" }
        return Row(line, word, index.ifEmpty { null }?.toInt(), kana, ruby, dictionary)
    }

    /** The parts [parts] puts over the occurrence of [word] in [line] that [index] names; null when they don't start and end with it. */
    fun rubyAt(parts: List<Ruby>, line: String, word: String, index: Int?): String? {
        if (parts.joinToString("") { it.text } != line) return null
        val found = starts(line, word)
        val start = when {
            index == null && found.size == 1 -> found[0]
            index != null && found.size > 1 && index in found.indices -> found[index]
            else -> return null
        }
        val end = start + word.length
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
     * The shortest stretch of [line] around the occurrence of [word] at [start] that [read] still reads as [ruby],
     * and the index of that occurrence in it; null when not even the whole line does. Of two as short, the one that starts first.
     */
    fun shortest(line: String, word: String, start: Int, ruby: String, read: (String) -> List<Ruby>): Pair<String, Int?>? {
        for (length in word.length..line.length) {
            for (from in maxOf(0, start + word.length - length)..start) {
                val to = from + length
                if (to > line.length) break
                if (!fits(line, from, to)) continue
                val stretch = line.substring(from, to)
                val found = starts(stretch, word)
                val index = found.indexOf(start - from).takeIf { found.size > 1 }
                if (rubyAt(read(stretch), stretch, word, index) == ruby) return stretch to index
            }
        }
        return null
    }

    /** Whether line[from, to) can be a row's line: whole characters, not a comment, nothing that ends a line in Kotlin or Python. */
    fun fits(line: String, from: Int, to: Int): Boolean {
        if (from > 0 && line[from].isLowSurrogate()) return false
        if (to < line.length && line[to].isLowSurrogate()) return false
        return line[from] != '#' && (from until to).none { line[it] in LINE_ENDS }
    }

    /** Characters Python's splitlines or Kotlin's lineSequence take for the end of a line, and the field separator. */
    val LINE_ENDS = setOf('\t', '\n', '\r', '\u000B', '\u000C', '\u001C', '\u001D', '\u001E', '\u0085', ' ', ' ')

    private const val FIELDS = 6
}
