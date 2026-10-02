package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** A prepared destination URL, including whether matching was enabled when it was produced. */
internal data class PreparedLink(val url: String, val exact: Boolean, val matchingEnabled: Boolean)

internal data class HistoryEntry(
    val link: MusicLink,
    val metadata: MusicMetadata,
    val destinationLinks: Map<String, PreparedLink> = emptyMap()
)

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

    /** Updates cached links without changing recency or resurrecting cleared history. */
    fun remember(sourceUrl: String, destinationKey: String, prepared: PreparedLink): List<HistoryEntry> {
        val updated = load().map { entry ->
            if (entry.link.url == sourceUrl) entry.copy(destinationLinks = entry.destinationLinks + (destinationKey to prepared)) else entry
        }
        save(updated)
        return updated
    }

    fun clear() {
        preferences.edit { remove(KEY_HISTORY) }
    }

    /** Saves [entries] in place of whatever is stored, e.g. to undo clearing. */
    fun replace(entries: List<HistoryEntry>): List<HistoryEntry> =
        entries.take(MAX_ENTRIES).also(::save)

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
                    .putOpt("artwork", entry.metadata.artworkUrl)
                    .put("destinations", JSONObject().apply {
                        entry.destinationLinks.forEach { (key, prepared) ->
                            put(key, JSONObject().put("url", prepared.url).put("exact", prepared.exact)
                                .put("matchingEnabled", prepared.matchingEnabled))
                        }
                    })
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
        val destinations = optJSONObject("destinations") ?: JSONObject()
        val prepared = destinations.keys().asSequence().mapNotNull { key ->
            val value = destinations.optJSONObject(key) ?: return@mapNotNull null
            val destinationUrl = value.optString("url").ifBlank { return@mapNotNull null }
            key to PreparedLink(destinationUrl, value.optBoolean("exact"), value.optBoolean("matchingEnabled"))
        }.toMap()
        return HistoryEntry(link, MusicMetadata(title, optString("artist"), type, optString("artwork").ifEmpty { null }), prepared)
    }

    private companion object {
        const val KEY_HISTORY = "history"
        const val MAX_ENTRIES = 20
    }
}
