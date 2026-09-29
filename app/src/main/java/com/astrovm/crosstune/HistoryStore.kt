package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

internal data class HistoryEntry(val item: SpotifyItem, val metadata: SpotifyMetadata)

/** Keeps the most recent resolved items, newest first, in SharedPreferences. */
internal class HistoryStore(private val preferences: SharedPreferences) {

    fun load(): List<HistoryEntry> {
        val stored = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toEntry() }
    }

    fun add(entry: HistoryEntry): List<HistoryEntry> {
        val updated = (listOf(entry) + load().filterNot { it.item == entry.item }).take(MAX_ENTRIES)
        save(updated)
        return updated
    }

    fun clear() {
        preferences.edit { remove(KEY_HISTORY) }
    }

    private fun save(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("type", entry.item.type.name)
                    .put("id", entry.item.id)
                    .put("title", entry.metadata.title)
                    .put("artist", entry.metadata.artist)
            )
        }
        preferences.edit { putString(KEY_HISTORY, array.toString()) }
    }

    private fun JSONObject.toEntry(): HistoryEntry? {
        val type = SpotifyType.entries.firstOrNull { it.name == optString("type") } ?: return null
        val id = optString("id").ifEmpty { return null }
        val title = optString("title").ifEmpty { return null }
        return HistoryEntry(SpotifyItem(type, id), SpotifyMetadata(title, optString("artist"), type))
    }

    private companion object {
        const val KEY_HISTORY = "history"
        const val MAX_ENTRIES = 20
    }
}
