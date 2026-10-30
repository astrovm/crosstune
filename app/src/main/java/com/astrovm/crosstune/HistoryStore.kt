package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A prepared destination URL, including whether matching was enabled when it was produced. */
internal data class PreparedLink(val url: String, val exact: Boolean, val matchingEnabled: Boolean)

internal data class HistoryEntry(
    val link: MusicLink,
    val metadata: MusicMetadata,
    val destinationLinks: Map<String, PreparedLink> = emptyMap()
)

/** Keeps the most recent resolved items, newest first, in SharedPreferences. */
internal class HistoryStore(private val preferences: SharedPreferences) {

    /** The entries last read or saved, with the text they're kept as, so the same text isn't read again. */
    private var known: Pair<String, List<HistoryEntry>>? = null

    fun load(): List<HistoryEntry> {
        val stored = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        known?.takeIf { it.first == stored }?.let { return it.second }
        val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
        val entries = (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toEntry() }
        // Kept by a version that listed a song once per app it came from: only its latest stays.
        return entries.filterIndexed { index, entry -> entries.take(index).none { it.isSameAs(entry) } }.also { known = stored to it }
    }

    fun add(entry: HistoryEntry): List<HistoryEntry> {
        val updated = (listOf(entry) + load().filterNot { it.isSameAs(entry) }).take(MAX_ENTRIES)
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
                    .put("service", entry.link.service.let { if (it == null) "RECOGNIZED_SONG" else it.name })
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
                    .putOpt("length", entry.metadata.lengthMs)
                    .put("destinations", JSONObject().apply {
                        entry.destinationLinks.forEach { (key, prepared) ->
                            put(key, JSONObject().put("url", prepared.url).put("exact", prepared.exact)
                                .put("matchingEnabled", prepared.matchingEnabled))
                        }
                    })
            )
        }
        val stored = array.toString()
        known = stored to entries
        preferences.edit { putString(KEY_HISTORY, stored) }
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
        val length = optLong("length").takeIf { it > 0 }
        return HistoryEntry(link, MusicMetadata(title, optString("artist"), type, optString("artwork").ifEmpty { null }, tracks, trackCount = count, lengthMs = length), prepared)
    }

    private companion object {
        const val KEY_HISTORY = "history"
        const val MAX_ENTRIES = 20
    }
}

/**
 * The same thing, however it was linked: one link, or the same item on the same service, e.g. a song
 * shared once from its album's page and once on its own, or one song from two apps, or searched for.
 * Two of a name on one service are two versions of it, as an upload and a release can be, and so are
 * two of a name as long as two versions are.
 */
private fun HistoryEntry.isSameAs(other: HistoryEntry): Boolean =
    link.url == other.link.url ||
        (link.service != null && link.service == other.link.service && link.type == other.link.type && link.id == other.link.id) ||
        (link.type == ItemType.TRACK && other.link.type == ItemType.TRACK && (link.service == null || link.service != other.link.service) &&
            metadata.isNamedAs(other.metadata) && mayBeAsLongAs(other))

/** The same name, by the same artist, as Recent tells a song's versions apart by. */
internal fun MusicMetadata.isNamedAs(other: MusicMetadata) =
    SongNames.normalize(title).let { it.isNotEmpty() && it == SongNames.normalize(other.title) } &&
        SongNames.normalize(artist) == SongNames.normalize(other.artist)

/** Not known to be of different lengths, a second or so apart being the same, as apps don't always agree on one. */
private fun HistoryEntry.mayBeAsLongAs(other: HistoryEntry): Boolean {
    val length = metadata.lengthMs ?: return true
    val otherLength = other.metadata.lengthMs ?: return true
    return abs(length - otherLength) <= SAME_RECORDING_LENGTH_MS
}
