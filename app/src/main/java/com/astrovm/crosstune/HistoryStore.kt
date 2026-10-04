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

    /** Updates what's known about an item, e.g. covers found for its songs, without changing recency. */
    fun update(sourceUrl: String, metadata: MusicMetadata): List<HistoryEntry> {
        val updated = load().map { entry -> if (entry.link.url == sourceUrl) entry.copy(metadata = metadata) else entry }
        save(updated)
        return updated
    }

    /** Drops the links saved for [destinationKey], e.g. once they point at a site that's no longer used. */
    fun forget(destinationKey: String): List<HistoryEntry> {
        val updated = load().map { entry -> entry.copy(destinationLinks = entry.destinationLinks - destinationKey) }
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
                    .put("service", entry.link.service?.name ?: "RECOGNIZED_SONG")
                    .put("type", entry.link.type.name)
                    .put("id", entry.link.id)
                    .put("url", entry.link.url)
                    .putOpt("region", entry.link.region)
                    .put("title", entry.metadata.title)
                    .put("artist", entry.metadata.artist)
                    .putOpt("artwork", entry.metadata.artworkUrl)
                    // A playlist's songs as [title, artist, cover, link] lists, kept short.
                    .put("tracks", JSONArray().apply {
                        entry.metadata.tracks.forEach { put(JSONArray().put(it.title).put(it.artist).put(it.artworkUrl.orEmpty()).put(it.url.orEmpty())) }
                    })
                    .putOpt("trackCount", entry.metadata.trackCount)
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
        val source = optString("service", MusicService.SPOTIFY.name)
        val service = if (source == "RECOGNIZED_SONG") null else MusicService.fromName(source) ?: return null
        val url = optString("url").ifEmpty { "https://open.spotify.com/${type.name.lowercase()}/$id" }
        val link = MusicLink(service, type, id, url, optString("region").ifEmpty { null })
        val destinations = optJSONObject("destinations") ?: JSONObject()
        val prepared = destinations.keys().asSequence().mapNotNull { key ->
            val value = destinations.optJSONObject(key) ?: return@mapNotNull null
            val destinationUrl = value.optString("url").ifBlank { return@mapNotNull null }
            key to PreparedLink(destinationUrl, value.optBoolean("exact"), value.optBoolean("matchingEnabled"))
        }.toMap()
        val tracks = optJSONArray("tracks")?.let { list ->
            (0 until list.length()).mapNotNull { index ->
                val song = list.optJSONArray(index) ?: return@mapNotNull null
                MusicMetadata(
                    song.optString(0).ifEmpty { return@mapNotNull null }, song.optString(1),
                    artworkUrl = song.optString(2).ifEmpty { null }, url = song.optString(3).ifEmpty { null }
                )
            }
        }.orEmpty()
        val count = optInt("trackCount").takeIf { it > 0 }
        return HistoryEntry(link, MusicMetadata(title, optString("artist"), type, optString("artwork").ifEmpty { null }, tracks, trackCount = count), prepared)
    }

    private companion object {
        const val KEY_HISTORY = "history"
        const val MAX_ENTRIES = 20
    }
}
