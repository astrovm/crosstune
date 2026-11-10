package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Furigana over every Japanese line the tests keep (the fixtures, and the lyrics the tests quote):
 * no reading may sit over a kana that is said as it's written. A kana said another way, ひ said
 * い, has what it's said as over it on its own, and that's the only reading over kana. It lists the words and lines that
 * still have one, so a change to how readings are laid over a word shows in numbers, not in one
 * example. Real lyrics, one line each, in `app/build/sweep/lines.txt` are swept too when it's there:
 * they're people's songs, so they stay out of git.
 */
@RunWith(RobolectricTestRunner::class)
class ReadingsSweepTest {

    /** Runs of Japanese writing in the test sources and data: kanji and kana, up to the next mark, space or other script. */
    private val kept: List<String> by lazy {
        val japanese = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー々]+")
        File("src/test").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "tsv", "json", "txt") && it.name != "ReadingsSweepTest.kt" }
            .flatMap { japanese.findAll(it.readText()).map { match -> match.value } }
            .toSortedSet()
            .toList()
    }

    private val lyrics: List<String> by lazy {
        File("build/sweep/lines.txt").takeIf { it.isFile }?.readLines().orEmpty().map(String::trim).distinct()
    }

    private fun japanese(lines: List<String>) = lines.filter { line -> line.any(Readings::isHan) && line.any(Readings::isKana) }

    /** The words of [lines] with a reading over kana said as written, by line. */
    private fun covering(lines: List<String>): Map<String, List<Ruby>> = lines.associateWith { line ->
        Readings.of(listOf(line), Script.JAPANESE)[0].parts.filter { part ->
            val reading = part.reading
            reading != null && part.text.any(Readings::isKana) && (part.text.length > 1 || Readings.hiragana(part.text) == Readings.hiragana(reading))
        }
    }.filterValues { it.isNotEmpty() }

    private fun sweep(name: String, lines: List<String>): Map<String, List<Ruby>> {
        val covered = covering(lines)
        val words = covered.values.sumOf { it.size }
        println("SWEEP $name: ${lines.size} lines, ${covered.size} with a reading over kana, $words such words")
        covered.forEach { (line, parts) -> println("SWEEP $name: $line -> ${parts.joinToString { "${it.text}=${it.reading}" }}") }
        return covered
    }

    @Test
    fun noReadingSitsOverKanaWrittenInItsWord() {
        assertTrue("the tests keep Japanese lines to sweep", japanese(kept).size > 100)
        val covered = sweep("IPADIC", japanese(kept)) + sweep("IPADIC lyrics", japanese(lyrics))
        assertEquals(emptyMap<String, List<Ruby>>(), covered)
    }

    @Test
    fun noReadingSitsOverKanaWrittenInItsWordWithUniDicToo() = TestUniDic.reading {
        val covered = sweep("UniDic", japanese(kept)) + sweep("UniDic lyrics", japanese(lyrics))
        assertEquals(emptyMap<String, List<Ruby>>(), covered)
    }
}
