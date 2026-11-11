package com.astrovm.crosstune.songcheck

import com.astrovm.crosstune.GoldenReadings
import com.astrovm.crosstune.Readings
import com.astrovm.crosstune.Ruby
import com.astrovm.crosstune.Script
import com.astrovm.crosstune.TestUniDic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * For `readingsaudit.py golden` and `hole`: whether each locked row still reads so, and for each reading the audit
 * found right, how the app reads it on the stretches of its line around it. Not part of the unit tests: the script
 * writes `golden-job.json` and runs it through Gradle's `songCheck` task, then reads `golden-answer.json`.
 * Both go in the audit folder, as they hold the songs' lines.
 *
 * A case is the line, the word, its occurrence from 0, its ruby, the dictionary, and stretches to try as [from, to],
 * in UTF-16 units. Its answer: `stretch`, the shortest stable stretch a row can hold (see [GoldenReadings.Around]),
 * with up to the job's `width` more letters of the line on each side; `whole`, whether the whole line reads it so;
 * `fallback`, when the stretch is the whole line, the longest stretch short of it that is no line of `lines`, stable
 * when one is; and `spans`, for each stretch tried, whether it is good and whether it is stable.
 */
@RunWith(RobolectricTestRunner::class)
class GoldenContexts {

    @Test
    fun contexts() {
        val dir = File(System.getProperty("songcheck.dir") ?: "build/songcheck")
        val job = JSONObject(File(dir, "golden-job.json").readText())
        val rows = job.getJSONArray("rows").let { array -> (0 until array.length()).map(array::getJSONArray) }
        val cases = job.getJSONArray("cases").let { array -> (0 until array.length()).map(array::getJSONArray) }
        val lines = job.optJSONArray("lines")?.let { array -> (0 until array.length()).map(array::getString).toSet() }.orEmpty()
        val width = job.optInt("width", 0)
        val holds = Array(rows.size) { arrayOfNulls<Boolean>(2) }
        val answers = arrayOfNulls<JSONObject>(cases.size)
        val started = System.nanoTime()
        Readings.uniDic = null
        answer(Job(rows, cases, lines, width), "ipadic", 0, holds, answers)
        TestUniDic.reading { answer(Job(rows, cases, lines, width), "unidic", 1, holds, answers) }
        val answer = JSONObject()
            .put("rows", JSONArray(holds.map { JSONArray().put(it[0] ?: JSONObject.NULL).put(it[1] ?: JSONObject.NULL) }))
            .put("cases", JSONArray(answers.map { it ?: JSONObject.NULL }))
        File(dir, "golden-answer.json").writeText(answer.toString())
        val ms = (System.nanoTime() - started) / 1_000_000
        val unlocked = answers.count { it == null || it.isNull("stretch") }
        println("golden contexts: ${rows.size} rows, ${cases.size} cases, $unlocked not locked, $ms ms")
    }

    private class Job(val rows: List<JSONArray>, val cases: List<JSONArray>, val lines: Set<String>, val width: Int)

    private fun answer(job: Job, dictionary: String, column: Int, holds: Array<Array<Boolean?>>, answers: Array<JSONObject?>) {
        // Each stretch is read once for each dictionary, as many cases try the same ones.
        val read = HashMap<String, List<Ruby>>()
        val reader = { stretch: String -> read.getOrPut(stretch) { Readings.of(listOf(stretch), Script.JAPANESE).single().parts } }
        job.rows.forEachIndexed { i, row ->
            if (row.getString(ROW_DICTIONARY) != dictionary && row.getString(ROW_DICTIONARY) != "both") return@forEachIndexed
            val index = if (row.isNull(ROW_INDEX)) null else row.getInt(ROW_INDEX)
            val line = row.getString(0)
            holds[i][column] = GoldenReadings.rubyAt(reader(line), line, row.getString(1), index) == row.getString(ROW_RUBY)
        }
        job.cases.forEachIndexed { i, case ->
            if (case.getString(CASE_DICTIONARY) != dictionary) return@forEachIndexed
            val line = case.getString(0)
            val word = case.getString(1)
            val start = GoldenReadings.starts(line, word)[case.getInt(CASE_OCCURRENCE)]
            val around = GoldenReadings.Around(line, word, start, case.getString(CASE_RUBY), reader)
            val stretch = around.shortestStable()?.let { around.widened(it, job.width) }
            val fallback = if (stretch == 0 to line.length) around.longestShortOfLine(job.lines) else null
            val tried = case.optJSONArray(CASE_SPANS) ?: JSONArray()
            val spans = (0 until tried.length()).map { at ->
                val span = tried.getJSONArray(at)
                JSONArray().put(around.good(span.getInt(0), span.getInt(1))).put(around.stable(span.getInt(0), span.getInt(1)))
            }
            answers[i] = JSONObject()
                .put("stretch", stretch?.let(::pair) ?: JSONObject.NULL)
                .put("whole", around.good(0, line.length))
                .put("fallback", fallback?.let(::pair) ?: JSONObject.NULL)
                .put("spans", JSONArray(spans))
        }
    }

    private fun pair(span: Pair<Int, Int>) = JSONArray().put(span.first).put(span.second)

    private companion object {
        const val ROW_INDEX = 2
        const val ROW_RUBY = 3
        const val ROW_DICTIONARY = 4
        const val CASE_OCCURRENCE = 2
        const val CASE_RUBY = 3
        const val CASE_DICTIONARY = 4
        const val CASE_SPANS = 5
    }
}
