package com.astrovm.crosstune

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every word in readings-golden.tsv still reads the way it did when it was locked.
 * The file is what `readingsaudit.py golden` wrote from words the audit read right.
 * A row that fails is printed with the line, the word, the expected kana and the kana the app gave.
 */
@RunWith(RobolectricTestRunner::class)
class ReadingsGoldenTest {

    @Test
    fun everyLockedReadingStillReadsSo() {
        val rows = rows()
        val started = System.nanoTime()
        // IPADIC alone first. UniDic is on only inside the block, as it is once the user has downloaded it.
        Readings.uniDic = null
        val broken = mutableListOf<String>()
        checkDictionary(rows.filter { it.dictionary == "ipadic" || it.dictionary == "both" }, "ipadic", broken)
        TestUniDic.reading {
            checkDictionary(rows.filter { it.dictionary == "unidic" || it.dictionary == "both" }, "unidic", broken)
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        // One line is read once per dictionary, so the lock stays under half a minute.
        println("ReadingsGoldenTest $ms ms, ${rows.size} rows, ${broken.size} broken")
        if (broken.isNotEmpty()) {
            broken.forEach(::println)
            error(broken.joinToString("\n", prefix = "${broken.size} locked readings changed\n"))
        }
        check(ms < 30_000) { "ReadingsGoldenTest took $ms ms" }
    }

    /** Each [line] is tokenized once, then every row on it is checked. */
    private fun checkDictionary(rows: List<GoldenRow>, via: String, broken: MutableList<String>) {
        for ((line, lineRows) in rows.groupBy { it.line }) {
            val parts = Readings.of(listOf(line), Script.JAPANESE).single().parts
            val written = parts.joinToString("") { it.text }
            for (row in lineRows) {
                if (written != line) {
                    broken += report(row, null, via)
                    continue
                }
                val got = kanaAt(parts, line, row.word, row.index)
                if (got != row.kana) broken += report(row, got, via)
                else if (row.romaji.isNotEmpty() && romajiAt(line, row) != row.romaji) broken += report(row, got, via)
            }
        }
    }

    private fun report(row: GoldenRow, got: String?, via: String) =
        "line=${row.line} word=${row.word} index=${row.index?.toString() ?: ""} dictionary=${row.dictionary} via=$via expected=${row.kana} got=${got ?: ""}" +
            if (row.romaji.isNotEmpty()) " expectedRomaji=${row.romaji} gotRomaji=${romajiAt(row.line, row)}" else ""

    /**
     * The kana over the occurrence of [word], when the occurrence lands on whole parts.
     * Null when the split no longer matches the locked word.
     */
    private fun kanaAt(parts: List<Ruby>, line: String, word: String, index: Int?): String? {
        val found = starts(line, word)
        val start = when {
            index == null && found.size == 1 -> found[0]
            index != null && index in found.indices -> found[index]
            else -> return null
        }
        val end = start + word.length
        val out = StringBuilder()
        var at = 0
        var covered = start
        for (part in parts) {
            val stop = at + part.text.length
            if (stop > start && at < end) {
                // A part that runs past the word means the dictionary split the line differently.
                if (at < start || stop > end) return null
                out.append(part.reading ?: part.text)
                covered = stop
            }
            at = stop
        }
        return out.toString().takeIf { covered == end }
    }

    /** Where [word] starts in [line]. The same search as readingsaudit.find_starts, one character at a time. */
    private fun starts(line: String, word: String): List<Int> {
        val found = mutableListOf<Int>()
        var at = 0
        while (true) {
            val next = line.indexOf(word, at)
            if (next < 0) return found
            found += next
            at = next + 1
        }
    }

    /** The romaji of the same occurrence, when the audit recorded one. */
    private fun romajiAt(line: String, row: GoldenRow): String {
        val words = Readings.words(line, Script.JAPANESE).filter { it.text == row.word }
        val word = if (row.index == null) words.singleOrNull() else words.getOrNull(row.index)
        return word?.romanized ?: ""
    }

    private fun rows(): List<GoldenRow> {
        val text = javaClass.getResourceAsStream("/com/astrovm/crosstune/readings-golden.tsv")?.bufferedReader()?.use { it.readText() }
            ?: error("readings-golden.tsv is not in the test resources")
        return text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }.map { raw ->
            val fields = raw.split('\t')
            check(fields.size == 6) { "A golden row needs 6 fields: $raw" }
            val index = fields[2].ifEmpty { null }?.toInt()
            GoldenRow(fields[0], fields[1], index, fields[3], fields[4], fields[5])
        }.toList()
    }

    private data class GoldenRow(val line: String, val word: String, val index: Int?, val kana: String, val romaji: String, val dictionary: String)
}
