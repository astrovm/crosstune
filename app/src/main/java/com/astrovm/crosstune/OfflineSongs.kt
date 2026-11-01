package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Where what's kept for offline goes, in the app's files Android doesn't clear to make room. */
internal const val KEPT_OFFLINE_DIR = "kept"

/**
 * A song, album or playlist kept for offline, as [item], by [id]; its [songs], those of them
 * whose words and the rest are [saved] already, by [OfflineSongs.keyOf].
 */
internal data class KeptOffline(val id: String, val item: MusicMetadata, val songs: List<MusicMetadata>, val saved: Set<String> = emptySet()) {
    val done: Boolean get() = songs.all { OfflineSongs.keyOf(it) in saved }
}

/**
 * The songs, albums and playlists kept for offline, in [file], away from the app's cache Android may
 * clear. What each one's songs need is looked up inside [Pinning] by its id, so [caches] and [covers]
 * keep it until it's let go.
 */
internal class OfflineSongs(
    private val file: File,
    private val caches: List<LookupCache>,
    private val covers: ArtworkLoader?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val lock = Mutex()
    private var kept: List<KeptOffline>? = null

    suspend fun all(): List<KeptOffline> = lock.withLock { loaded() }

    /** Keeps [item] for offline: a song, or an album's or playlist's songs. Already kept, nothing changes. */
    suspend fun keep(item: MusicMetadata): KeptOffline = lock.withLock {
        val id = idOf(item)
        // Its songs are kept beside it, once.
        loaded().firstOrNull { it.id == id } ?: KeptOffline(id, item.copy(tracks = emptyList()), item.tracks.ifEmpty { listOf(item) }).also { save(loaded() + it) }
    }

    /** Whether [item] is kept for offline. */
    suspend fun has(item: MusicMetadata): KeptOffline? = lock.withLock { loaded().firstOrNull { it.id == idOf(item) } }

    /** [song] of the one kept as [id] has all it needs. */
    suspend fun saved(id: String, song: MusicMetadata) = lock.withLock {
        save(loaded().map { if (it.id == id) it.copy(saved = it.saved + keyOf(song)) else it })
    }

    /** Lets go of the one kept as [id]: what only it needed is then dropped as anything else would be. */
    suspend fun letGo(id: String) {
        val (gone, left) = lock.withLock {
            val all = loaded()
            all.partition { it.id == id }.also { (_, left) -> save(left) }
        }
        caches.forEach { it.unpin(id) }
        caches.forEach(LookupCache::save)
        val stillNeeded = left.flatMap { it.songs }.mapNotNull { it.artworkUrl }.toSet()
        gone.flatMap { it.songs }.mapNotNull { it.artworkUrl }.filterNot { it in stillNeeded }.forEach { covers?.letGo(it) }
    }

    /** How many bytes what's kept for offline takes, about. */
    suspend fun size(): Long = caches.sumOf { it.pinnedSize() } + (covers?.keptSize() ?: 0L)

    private suspend fun loaded(): List<KeptOffline> = kept ?: withContext(ioDispatcher) { read() }.also { kept = it }

    private suspend fun save(all: List<KeptOffline>) {
        kept = all
        val json = JSONArray().apply { all.forEach { put(json(it)) } }.toString()
        withContext(ioDispatcher) {
            try {
                file.parentFile?.mkdirs()
                // Written aside first, so a crash midway never leaves a broken file.
                val temporary = File(file.path + ".tmp")
                temporary.writeText(json)
                temporary.renameTo(file)
            } catch (_: IOException) {
                // Not written, it's still kept until the app closes.
            }
        }
    }

    private fun read(): List<KeptOffline> = try {
        if (!file.isFile) emptyList() else JSONArray(file.readText()).let { list -> (0 until list.length()).mapNotNull { kept(list.optJSONObject(it)) } }
    } catch (_: IOException) {
        emptyList()
    } catch (_: JSONException) {
        emptyList()
    }

    private fun json(kept: KeptOffline) = JSONObject()
        .put("id", kept.id)
        .put("item", song(kept.item))
        .put("songs", JSONArray().apply { kept.songs.forEach { put(song(it)) } })
        .put("saved", JSONArray(kept.saved.toList()))

    private fun kept(json: JSONObject?): KeptOffline? {
        val item = json?.optJSONObject("item")?.let(::song) ?: return null
        val songs = json.optJSONArray("songs")?.let { list -> (0 until list.length()).mapNotNull { song(list.optJSONObject(it)) } }.orEmpty()
        val saved = json.optJSONArray("saved")?.let { list -> (0 until list.length()).map(list::optString).toSet() }.orEmpty()
        return KeptOffline(json.optString("id").ifEmpty { idOf(item) }, item, songs, saved)
    }

    private fun song(song: MusicMetadata) = JSONObject()
        .put("title", song.title)
        .put("artist", song.artist)
        .put("type", song.type.name)
        .putOpt("artwork", song.artworkUrl)
        .putOpt("url", song.url)
        .putOpt("length", song.lengthMs)
        .putOpt("recording", song.recordingId)

    private fun song(json: JSONObject?): MusicMetadata? {
        val title = json?.optString("title")?.ifEmpty { null } ?: return null
        return MusicMetadata(
            title,
            json.optString("artist"),
            ItemType.entries.firstOrNull { it.name == json.optString("type") } ?: ItemType.TRACK,
            artworkUrl = json.optString("artwork").ifEmpty { null },
            url = json.optString("url").ifEmpty { null },
            lengthMs = json.optLong("length").takeIf { it > 0 },
            recordingId = json.optString("recording").ifEmpty { null }
        )
    }

    companion object {
        /** A song kept, as its recording; a list kept, by its link, or its name when it has none. */
        fun idOf(item: MusicMetadata): String =
            if (item.type == ItemType.TRACK) "song:${keyOf(item)}" else "${item.type.name.lowercase()}:${item.url ?: "${item.title}\u0000${item.artist}"}"

        fun keyOf(song: MusicMetadata): String = RecordingKey.of(song)
    }
}
