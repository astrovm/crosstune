package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

internal data class HistoryEntry(val link: MusicLink, val metadata: MusicMetadata)

/** Keeps the most recent resolved items, newest first, in SharedPreferences. */
internal class HistoryStore(private val preferences: SharedPreferences) {

    fun load(): List<HistoryEntry> {
        val stored = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toEntry() }
    }

    fun add(entry: HistoryEntry): List<HistoryEntry> {
        val updated = (listOf(entry) + load().filterNot { it.link.url == entry.link.url }).take(MAX_ENTRIES)
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
                    .put("service", entry.link.service.name)
                    .put("type", entry.link.type.name)
                    .put("id", entry.link.id)
                    .put("url", entry.link.url)
                    .putOpt("region", entry.link.region)
                    .put("title", entry.metadata.title)
                    .put("artist", entry.metadata.artist)
            )
        }
        preferences.edit { putString(KEY_HISTORY, array.toString()) }
    }

    private fun JSONObject.toEntry(): HistoryEntry? {
        val type = ItemType.entries.firstOrNull { it.name == optString("type") } ?: return null
        val id = optString("id").ifEmpty { return null }
        val title = optString("title").ifEmpty { return null }
        // Entries saved before multi-service support only stored Spotify items.
        val service = MusicService.fromName(optString("service", MusicService.SPOTIFY.name)) ?: return null
        val url = optString("url").ifEmpty { "https://open.spotify.com/${type.name.lowercase()}/$id" }
        val link = MusicLink(service, type, id, url, optString("region").ifEmpty { null })
        return HistoryEntry(link, MusicMetadata(title, optString("artist"), type))
    }

    private companion object {
        const val KEY_HISTORY = "history"
        const val MAX_ENTRIES = 20
    }
}
