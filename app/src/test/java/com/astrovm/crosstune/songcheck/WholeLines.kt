package com.astrovm.crosstune.songcheck

import com.astrovm.crosstune.LineReading
import com.astrovm.crosstune.Readings
import com.astrovm.crosstune.Ruby
import com.astrovm.crosstune.Script
import com.astrovm.crosstune.SungReadings
import com.astrovm.crosstune.TestUniDic
import com.astrovm.crosstune.Word
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * For `readingsaudit.py lines`: each line of `lines-job.json` read whole, as the app shows it, with the built-in
 * dictionary and with UniDic. Not part of the unit tests: the script writes the job, runs this through Gradle's
 * `songCheck` task on main's checkout and on this one, then reads each one's answer and compares the two.
 * Both go in the audit folder, as they hold the songs' lines.
 *
 * For each line: its parts and romaji as `Readings.of` gives them; the same after UtaTen's readings for it are put
 * in, as `SungReadings.matched` and `Readings.corrected` do in the app; and its words for the word sheet, as
 * `Readings.words` gives them and as `Readings.asShown` shows them over those parts. For each song, the script
 * `Readings.scriptOf` finds, as the app reads a song only when it is Japanese.
 *
 * The script copies this file into main's checkout, so both read with the same harness. It uses only what the app
 * has had since readings were first locked.
 */
@RunWith(RobolectricTestRunner::class)
class WholeLines {

    @Test
    fun lines() {
        val dir = File(System.getProperty("songcheck.dir") ?: "build/songcheck")
        val job = JSONObject(File(dir, "lines-job.json").readText())
        // Each checkout writes its own answer, as both run with the same job: the job names the file for each.
        // Gradle runs the test in the app's folder, so the checkout is the folder above it.
        val checkout = checkNotNull(File(checkNotNull(System.getProperty("user.dir"))).canonicalFile.parentFile) { "the app's folder has no parent" }.path
        val name = job.getJSONObject("answers").optString(checkout).ifEmpty { error("lines-job.json names no answer for $checkout") }
        val entries = job.getJSONArray("lines").let { array -> (0 until array.length()).map(array::getJSONObject) }
        val songs = job.getJSONArray("songs").let { array -> (0 until array.length()).map { strings(array.getJSONArray(it)) } }
        File(dir, name).writeText(answer(entries, songs).toString())
        println("whole lines: ${entries.size} lines and ${songs.size} songs read in $checkout")
    }

    internal companion object {

        /** Every entry read with each dictionary, and each song's script. */
        fun answer(entries: List<JSONObject>, songs: List<List<String>>): JSONObject {
            Readings.uniDic = null
            val ipadic = JSONArray(entries.map(::read))
            Readings.uniDic = TestUniDic.tokenizer
            val unidic = try {
                JSONArray(entries.map(::read))
            } finally {
                Readings.uniDic = null
            }
            val scripts = JSONArray(songs.map { lines -> Readings.scriptOf(lines)?.name ?: JSONObject.NULL })
            return JSONObject().put("ipadic", ipadic).put("unidic", unidic).put("scripts", scripts)
        }

        /** One line alone, as the app reads every line of a song, and as it shows it once UtaTen's readings are in. */
        private fun read(entry: JSONObject): JSONObject {
            val text = entry.getString("text")
            val read = Readings.of(listOf(text), Script.JAPANESE).single()
            val theirs = entry.optJSONArray("truth")?.let { truth ->
                (0 until truth.length()).map { truth.getJSONArray(it) }.map { part -> Ruby(part.getString(0), if (part.isNull(1)) null else part.getString(1)) }
            }
            val fixes = theirs?.let { SungReadings.matched(listOf(text), listOf(read), listOf(it)) }.orEmpty()
            val shown = Readings.corrected(listOf(read), fixes).single()
            val words = Readings.words(text, Script.JAPANESE)
            return JSONObject()
                .put("parts", parts(read.parts)).put("romaji", romaji(read))
                .put("shown", parts(shown.parts)).put("shownRomaji", romaji(shown))
                .put("words", words(words)).put("shownWords", words(Readings.asShown(words, shown.parts)))
        }

        private fun parts(parts: List<Ruby>) = JSONArray(parts.map { JSONArray().put(it.text).put(it.reading ?: JSONObject.NULL) })

        private fun romaji(line: LineReading) = JSONArray().put(line.romanized).put(line.typed)

        private fun words(words: List<Word>) = JSONArray(
            words.map { JSONArray().put(it.text).put(it.reading ?: JSONObject.NULL).put(it.romanized ?: JSONObject.NULL).put(it.typed ?: JSONObject.NULL) }
        )

        private fun strings(array: JSONArray) = (0 until array.length()).map(array::getString)
    }
}
