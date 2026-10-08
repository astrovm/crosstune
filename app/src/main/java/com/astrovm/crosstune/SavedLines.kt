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
    val artist: String
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
            SavedLine(text, json.text("reading"), json.text("romanized"), json.text("translation"), json.optString("title"), json.optString("artist"))
        }
    }

    /** Keeps [line], or lets it go when it's kept already. */
    fun toggle(line: SavedLine): List<SavedLine> {
        val saved = load()
        return save(if (saved.any { it.isSame(line) }) saved.filterNot { it.isSame(line) } else (listOf(line) + saved).take(MAX))
    }

    fun remove(line: SavedLine): List<SavedLine> = save(load().filterNot { it.isSame(line) })

    private fun save(lines: List<SavedLine>): List<SavedLine> {
        val array = JSONArray()
        lines.forEach { line ->
            array.put(
                JSONObject().put("text", line.text).put("reading", line.reading).put("romanized", line.romanized)
                    .put("translation", line.translation).put("title", line.title).put("artist", line.artist)
            )
        }
        preferences.edit { putString(KEY, array.toString()) }
        return lines
    }

    private fun JSONObject.text(name: String): String? = optString(name).takeIf { has(name) && it.isNotEmpty() }

    private companion object {
        const val KEY = "saved_lines"
        const val MAX = 500
    }
}
