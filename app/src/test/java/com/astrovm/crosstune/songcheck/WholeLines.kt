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
 * For `readingsaudit.py lines`: each line of `lines-job.json` read whole, as the app reads it, with the built-in
 * dictionary and with UniDic. Not part of the unit tests: the script writes the job, runs this through Gradle's
 * `songCheck` task on main's checkout and on this one, then reads `lines-answer.json` and compares the two.
 * Both go in the audit folder, as they hold the songs' lines.
 *
 * The script copies this file into main's checkout when main has none, so it uses only what ReadingsAudit uses.
 */
@RunWith(RobolectricTestRunner::class)
class WholeLines {

    @Test
    fun lines() {
        val dir = File(System.getProperty("songcheck.dir") ?: "build/songcheck")
        val job = JSONArray(File(dir, "lines-job.json").readText())
        val lines = (0 until job.length()).map(job::getString)
        Readings.uniDic = null
        val ipadic = read(lines)
        Readings.uniDic = TestUniDic.tokenizer
        val unidic = try {
            read(lines)
        } finally {
            Readings.uniDic = null
        }
        File(dir, "lines-answer.json").writeText(JSONObject().put("ipadic", ipadic).put("unidic", unidic).toString())
        println("whole lines: ${lines.size} lines read")
    }

    /** Each line alone, as the app reads every line of a song: its parts, as [text, reading or null]. */
    private fun read(lines: List<String>) = JSONArray(
        lines.map { line ->
            JSONArray(Readings.of(listOf(line), Script.JAPANESE).single().parts.map { JSONArray().put(it.text).put(it.reading ?: JSONObject.NULL) })
        }
    )
}
