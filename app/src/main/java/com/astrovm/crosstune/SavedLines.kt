package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** A line kept to study later, with what helped read it then, and the song it's from. */
internal data class SavedLine(
    val text: String,
    val reading: String? = null,
    val romanized: String? = null,
    val translation: String? = null,
    val title: String,
    val artist: String,
    /** What ChatGPT said the line means, when asked. */
    val explanation: String? = null,
    /** How well it's known, from 0, after each time it's practised. */
    val box: Int = 0,
    /** When it's next practised, in milliseconds since 1970; 0, right away. */
    val due: Long = 0
) {
    fun isSame(other: SavedLine) = text == other.text && title == other.title && artist == other.artist
}

/** The lines kept, newest first, on the phone only. */
internal class SavedLinesStore(private val preferences: SharedPreferences) {

    fun load(): List<SavedLine> {
        val array = preferences.getString(KEY, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val text = json.optString("text").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SavedLine(
                text, json.text("reading"), json.text("romanized"), json.text("translation"), json.optString("title"), json.optString("artist"), json.text("explanation"),
                json.optInt("box"), json.optLong("due")
            )
        }
    }

    /**
     * Keeps [line], or lets it go when it's kept already. With [MAX] kept, the one learned best makes
     * room, the oldest of those, rather than one still being learned.
     */
    fun toggle(line: SavedLine): List<SavedLine> {
        val saved = load()
        if (saved.any { it.isSame(line) }) return save(saved.filterNot { it.isSame(line) })
        val room = if (saved.size < MAX) saved else saved.minus(saved.asReversed().maxBy { it.box })
        return save(listOf(line) + room.take(MAX - 1))
    }

    fun remove(line: SavedLine): List<SavedLine> = save(load().filterNot { it.isSame(line) })

    /** Keeps [explanation] with the line [text] of [title] by [artist], when that line's kept. */
    fun explain(text: String, title: String, artist: String, explanation: String): List<SavedLine> {
        val key = SavedLine(text, title = title, artist = artist)
        return save(load().map { if (it.isSame(key)) it.copy(explanation = explanation) else it })
    }

    /**
     * [line] practised at [now]: known, it comes back after longer each time, 1, 3, 7, 16 then 35
     * days; not known, in ten minutes, from the start again.
     */
    fun practised(line: SavedLine, knew: Boolean, now: Long): List<SavedLine> = save(
        load().map {
            if (!it.isSame(line)) it
            else if (knew) it.copy(box = minOf(it.box + 1, PRACTICE_DAYS.size), due = now + PRACTICE_DAYS[minOf(it.box, PRACTICE_DAYS.lastIndex)] * DAY_MS)
            else it.copy(box = 0, due = now + AGAIN_MS)
        }
    )

    private fun save(lines: List<SavedLine>): List<SavedLine> {
        val array = JSONArray()
        lines.forEach { line ->
            array.put(
                JSONObject().put("text", line.text).put("reading", line.reading).put("romanized", line.romanized)
                    .put("translation", line.translation).put("title", line.title).put("artist", line.artist)
                    .put("explanation", line.explanation).put("box", line.box).put("due", line.due)
            )
        }
        preferences.edit { putString(KEY, array.toString()) }
        return lines
    }

    private fun JSONObject.text(name: String): String? = optString(name).takeIf { has(name) && it.isNotEmpty() }

    companion object {
        private const val KEY = "saved_lines"

        /** How many lines are kept at most. */
        const val MAX = 500
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val AGAIN_MS = 10 * 60 * 1000L
    }
}

/** How many days a line known waits before it's practised again, the more times it's known in a row. */
private val PRACTICE_DAYS = listOf(1L, 3L, 7L, 16L, 35L)
