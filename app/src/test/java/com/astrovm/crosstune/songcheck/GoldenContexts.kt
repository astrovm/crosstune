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
 * For `readingsaudit.py golden`: whether each locked row still reads so, and for each reading the audit
 * found right, the shortest stretch of its line the app reads the same way. Not part of the unit tests:
 * the script writes `golden-job.json` and runs it through Gradle's `songCheck` task, then reads `golden-answer.json`.
 * Both go in the audit folder, as they hold the songs' lines.
 */
@RunWith(RobolectricTestRunner::class)
class GoldenContexts {

    @Test
    fun contexts() {
        val dir = File(System.getProperty("songcheck.dir") ?: "build/songcheck")
        val job = JSONObject(File(dir, "golden-job.json").readText())
        val rows = job.getJSONArray("rows").let { array -> (0 until array.length()).map(array::getJSONArray) }
        val cases = job.getJSONArray("cases").let { array -> (0 until array.length()).map(array::getJSONArray) }
        val holds = Array(rows.size) { arrayOfNulls<Boolean>(2) }
        val stretches = arrayOfNulls<JSONArray>(cases.size)
        val started = System.nanoTime()
        Readings.uniDic = null
        answer(rows, cases, "ipadic", 0, holds, stretches)
        TestUniDic.reading { answer(rows, cases, "unidic", 1, holds, stretches) }
        val answer = JSONObject()
            .put("rows", JSONArray(holds.map { JSONArray().put(it[0] ?: JSONObject.NULL).put(it[1] ?: JSONObject.NULL) }))
            .put("cases", JSONArray(stretches.map { it ?: JSONObject.NULL }))
        File(dir, "golden-answer.json").writeText(answer.toString())
        val ms = (System.nanoTime() - started) / 1_000_000
        println("golden contexts: ${rows.size} rows, ${cases.size} cases, ${stretches.count { it == null }} not locked, $ms ms")
    }

    private fun answer(
        rows: List<JSONArray>,
        cases: List<JSONArray>,
        dictionary: String,
        column: Int,
        holds: Array<Array<Boolean?>>,
        stretches: Array<JSONArray?>
    ) {
        // Each stretch is read once for each dictionary, as many cases try the same few.
        val read = HashMap<String, List<Ruby>>()
        val reader = { stretch: String -> read.getOrPut(stretch) { Readings.of(listOf(stretch), Script.JAPANESE).single().parts } }
        rows.forEachIndexed { i, row ->
            if (row.getString(ROW_DICTIONARY) != dictionary && row.getString(ROW_DICTIONARY) != "both") return@forEachIndexed
            val index = if (row.isNull(ROW_INDEX)) null else row.getInt(ROW_INDEX)
            val line = row.getString(0)
            holds[i][column] = GoldenReadings.rubyAt(reader(line), line, row.getString(1), index) == row.getString(ROW_RUBY)
        }
        cases.forEachIndexed { i, case ->
            if (case.getString(CASE_DICTIONARY) != dictionary) return@forEachIndexed
            val line = case.getString(0)
            val word = case.getString(1)
            val start = GoldenReadings.starts(line, word)[case.getInt(CASE_OCCURRENCE)]
            val found = GoldenReadings.shortest(line, word, start, case.getString(CASE_RUBY), reader) ?: return@forEachIndexed
            stretches[i] = JSONArray().put(found.first).put(found.second ?: JSONObject.NULL)
        }
    }

    private companion object {
        const val ROW_INDEX = 2
        const val ROW_RUBY = 3
        const val ROW_DICTIONARY = 4
        const val CASE_OCCURRENCE = 2
        const val CASE_RUBY = 3
        const val CASE_DICTIONARY = 4
    }
}
