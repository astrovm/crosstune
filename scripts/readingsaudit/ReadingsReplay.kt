package com.astrovm.crosstune

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// Copy to app/src/test/java/com/astrovm/crosstune/ and run with --tests '*ReadingsReplay*'.
// Put UTF-8 inputs.txt and label.txt in app/build/probe; the test writes a TSV beside them.
// It reads each input in both modes. Tabs and newlines in an input are not supported.
@RunWith(RobolectricTestRunner::class)
class ReadingsReplay {
    private fun row(mode: String, line: String): String {
        val reading = Readings.of(listOf(line), Script.JAPANESE).single()
        val words = Readings.words(line, Script.JAPANESE).joinToString("|") { "${it.text}=${it.lookup}[${it.romanized}/${it.reading ?: "-"}]" }
        val ruby = reading.parts.joinToString("|") { "${it.text}=${it.reading ?: "-"}" }
        val typedWords = Readings.words(line, Script.JAPANESE).joinToString("|") { "${it.typed ?: "-"}/${it.modern ?: "-"}" }
        return listOf(mode, line, reading.romanized, reading.typed, reading.reading, ruby, words, typedWords).joinToString("\t")
    }

    @Test
    fun probe() {
        val dir = File("build/probe")
        val label = File(dir, "label.txt").readText().trim()
        val lines = File(dir, "inputs.txt").readLines().filter { it.isNotBlank() }
        val out = lines.map { row("ipadic", it) } + TestUniDic.reading { lines.map { row("unidic", it) } }
        File(dir, "out-$label.tsv").writeText((listOf("mode\tinput\tromaji\ttyped\tkana\truby\twords\tword typed/modern") + out).joinToString("\n") + "\n")
    }
}
