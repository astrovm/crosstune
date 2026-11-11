package com.astrovm.crosstune.songcheck

import com.astrovm.crosstune.Readings
import com.astrovm.crosstune.Script
import com.astrovm.crosstune.TestUniDic
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Makes the app's `jmdict-readings.txt`: of the words JMdict reads one way only, in `listed.txt`,
 * those Crosstune reads otherwise on their own, with IPADIC or with UniDic, so it can read them as
 * JMdict does. Not part of the unit tests: scripts/readingsaudit/readingsaudit.py `table` writes
 * `listed.txt` and runs it through Gradle's `songCheck` task.
 */
@RunWith(RobolectricTestRunner::class)
class JmdictReadings {

    @Test
    fun table() {
        val dir = File(System.getProperty("songcheck.dir") ?: "build/songcheck")
        val (header, words) = File(dir, "listed.txt").readLines().partition { it.startsWith('#') }
        val listed = words.map { it.substringBefore('\t') to it.substringAfter('\t') }
        // Read as the app does without the table, to keep only what it changes.
        Readings.listed = emptyMap()
        val kept = try {
            val alone = readings(listed.map { it.first })
            val withUniDic = TestUniDic.reading { readings(listed.map { it.first }) }
            listed.filterIndexed { index, (_, kana) -> alone[index] != kana || withUniDic[index] != kana }
        } finally {
            Readings.listed = null
        }
        File("src/main/resources/com/astrovm/crosstune/jmdict-readings.txt")
            .writeText((header + kept.map { (form, kana) -> "$form\t$kana" }).joinToString("\n", postfix = "\n"))
        println("jmdict-readings: ${kept.size} of ${listed.size} words kept")
    }

    private fun readings(forms: List<String>) = forms.map { Readings.hiragana(Readings.of(listOf(it), Script.JAPANESE).single().reading) }
}
