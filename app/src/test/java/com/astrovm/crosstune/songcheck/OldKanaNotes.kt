package com.astrovm.crosstune.songcheck

import com.astrovm.crosstune.Readings
import com.astrovm.crosstune.Script
import com.astrovm.crosstune.TestUniDic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Which words of a line get an "Old spelling of" note, and what they are looked up as, with the built-in
 * dictionary alone and with UniDic: the measure of PR #835 ("2b") and Issue #854, kept so a change to
 * old kana is measured the same way each time. Not part of the unit tests: run it through Gradle's
 * `songCheck` task, with `-PsongcheckTest=com.astrovm.crosstune.songcheck.OldKanaNotes`.
 *
 * Its lines are the Japanese lines of the test files, which are made up, and the lines of the songs
 * scripts/readingsaudit keeps in `found/`, which are people's and so stay in the agent cache. Every line
 * that gets a note goes to `oldkana/<lines>.json` in the songcheck folder; a note on a line in modern
 * spelling is a false note. Compare two runs with `diff`, and read each line that changed.
 */
@RunWith(RobolectricTestRunner::class)
class OldKanaNotes {

    private val dir = File(System.getProperty("songcheck.dir") ?: "build/songcheck")

    @Test
    fun notes() {
        val out = File(dir, "oldkana").apply { mkdirs() }
        mapOf("tests" to testLines(), "songs" to songLines()).forEach { (name, lines) ->
            val noted = JSONObject()
            var builtIn = 0
            var uniDic = 0
            lines.forEach { line ->
                val ours = notes(line)
                val theirs = TestUniDic.reading { notes(line) }
                if (ours.isNotEmpty()) builtIn++
                if (theirs.isNotEmpty()) uniDic++
                if (ours.isNotEmpty() || theirs.isNotEmpty()) noted.put(line, JSONObject().put("builtIn", JSONArray(ours)).put("uniDic", JSONArray(theirs)))
            }
            File(out, "$name.json").writeText(noted.toString(1))
            println("oldkana $name: ${lines.size} lines, noted builtIn $builtIn, uniDic $uniDic")
        }
    }

    /** Each word of [line] with a modern spelling, as "text → modern, looked up as lookup". */
    private fun notes(line: String) = Readings.words(line, Script.JAPANESE).filter { it.modern != null }.map { "${it.text} → ${it.modern}, ${it.lookup}" }

    /** Every line of a string in the test files that has kana in it. */
    private fun testLines(): List<String> = File("src/test").walk().filter { it.extension == "kt" }.sortedBy { it.path }.flatMap { file ->
        STRING.findAll(file.readText()).flatMap { it.groupValues[1].split("\\n") }
    }.filter { line -> line.any { it in '぀'..'ヿ' } }.distinct().toList()

    /** Every line with kana of the songs readingsaudit keeps. */
    private fun songLines(): List<String> = File(dir, "found").listFiles().orEmpty().sortedBy { it.name }.flatMap { file ->
        JSONObject(file.readText()).optJSONArray("lines")?.let { lines -> (0 until lines.length()).map(lines::getString) }.orEmpty()
    }.filter { line -> line.any { it in '぀'..'ヿ' } }.distinct()

    private companion object {
        val STRING = Regex(""""((?:[^"\\\n]|\\.)*)${'"'}""")
    }
}
