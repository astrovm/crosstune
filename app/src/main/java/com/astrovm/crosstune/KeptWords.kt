package com.astrovm.crosstune

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What was worked out for a song's words, kept so they show again at once, as they were left; on
 * disk too, as [json], so they still do after the app's been closed.
 */
internal data class KeptWords(
    val lyrics: String,
    val lines: List<LyricLine>,
    val synced: Boolean,
    val found: LyricsFinder.Lyrics.Found?,
    val foundLines: List<LyricLine>,
    val japanese: List<String>?,
    val syncPoints: List<SyncPoint>,
    val learning: Learning,
    /** What the words were worked out with, see [MainViewModel]'s madeWith. */
    val madeWith: String,
    val chords: ChordsState
) {
    /** Only what shows them again: what's being asked of ChatGPT about the song is asked again. */
    fun json(): String = JSONObject()
        .put("lyrics", lyrics)
        .put("lines", lines.json(::lineJson))
        .put("synced", synced)
        .put("found", found?.let { JSONObject().put("words", it.words).put("lines", it.lines.json(::lineJson)).put("lengthMs", it.lengthMs).put("source", it.source) })
        .put("foundLines", foundLines.json(::lineJson))
        .put("japanese", japanese?.json { it })
        .put("syncPoints", syncPoints.json { JSONArray().put(it.lineMs).put(it.songMs) })
        .put("script", learning.script?.name)
        .put("readings", learning.lines.json(::readingJson))
        .put("translations", learning.translations.json { it ?: JSONObject.NULL })
        .put("fromRomaji", learning.fromRomaji)
        .put("madeWith", madeWith)
        .put("chordsTried", chords.tried)
        .put("chords", chords.sheet?.let(::sheetJson))
        .toString()

    companion object {
        /** The words kept as [json] wrote them; null when they can't be read, so they're worked out again. */
        fun from(json: String): KeptWords? = try {
            val kept = JSONObject(json)
            KeptWords(
                kept.getString("lyrics"),
                kept.getJSONArray("lines").list(::line),
                kept.getBoolean("synced"),
                kept.optJSONObject("found")?.let {
                    LyricsFinder.Lyrics.Found(it.getString("words"), it.getJSONArray("lines").list(::line), if (it.has("lengthMs")) it.getLong("lengthMs") else null, it.optString("source").ifEmpty { null })
                },
                kept.getJSONArray("foundLines").list(::line),
                kept.optJSONArray("japanese")?.list { array, index -> array.getString(index) },
                kept.getJSONArray("syncPoints").list { array, index -> array.getJSONArray(index).let { SyncPoint(it.getLong(0), it.getLong(1)) } },
                Learning(
                    script = if (kept.has("script")) Script.valueOf(kept.getString("script")) else null,
                    lines = kept.getJSONArray("readings").list(::reading),
                    translations = kept.getJSONArray("translations").list { array, index -> if (array.isNull(index)) null else array.getString(index) },
                    fromRomaji = kept.getBoolean("fromRomaji")
                ),
                kept.getString("madeWith"),
                ChordsState(tried = kept.getBoolean("chordsTried"), sheet = kept.optJSONObject("chords")?.let(::sheet))
            )
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            // A script or chord site this version no longer has.
            null
        }

        private fun lineJson(line: LyricLine) = JSONObject()
            .put("time", line.timeMs)
            .put("text", line.text)
            .put("words", line.words.json { JSONArray().put(it.timeMs).put(it.text) })
            .put("end", line.endMs)

        private fun line(array: JSONArray, index: Int) = array.getJSONObject(index).let { line ->
            LyricLine(
                line.getLong("time"),
                line.getString("text"),
                line.getJSONArray("words").list { words, at -> words.getJSONArray(at).let { TimedWord(it.getLong(0), it.getString(1)) } },
                if (line.has("end")) line.getLong("end") else null
            )
        }

        private fun readingJson(reading: LineReading) = JSONObject()
            .put("parts", reading.parts.json { JSONArray().put(it.text).put(it.reading ?: JSONObject.NULL) })
            .put("reading", reading.reading)
            .put("romanized", reading.romanized)
            .put("typed", reading.typed)

        private fun reading(array: JSONArray, index: Int) = array.getJSONObject(index).let { reading ->
            LineReading(
                reading.getJSONArray("parts").list { parts, at -> parts.getJSONArray(at).let { Ruby(it.getString(0), if (it.isNull(1)) null else it.getString(1)) } },
                reading.getString("reading"),
                reading.getString("romanized"),
                reading.getString("typed")
            )
        }

        private fun sheetJson(sheet: ChordSheet) = JSONObject()
            .put("lines", sheet.lines.json { line -> JSONObject().put("text", line.text).put("chords", line.chords.json { JSONArray().put(it.at).put(it.name) }) })
            .put("capo", sheet.capo)
            .put("site", sheet.site.name)
            .put("url", sheet.url)

        private fun sheet(sheet: JSONObject) = ChordSheet(
            sheet.getJSONArray("lines").list { lines, index ->
                lines.getJSONObject(index).let { line ->
                    SheetLine(line.getString("text"), line.getJSONArray("chords").list { chords, at -> chords.getJSONArray(at).let { Chord(it.getInt(0), it.getString(1)) } })
                }
            },
            sheet.getInt("capo"),
            ChordSite.valueOf(sheet.getString("site")),
            sheet.getString("url")
        )

        private fun <T> List<T>.json(each: (T) -> Any): JSONArray = JSONArray().also { array -> forEach { array.put(each(it)) } }

        private fun <T> JSONArray.list(each: (JSONArray, Int) -> T): List<T> = (0 until length()).map { each(this, it) }
    }
}
