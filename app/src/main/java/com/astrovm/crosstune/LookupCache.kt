package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Remembers what was found for songs, such as each one's video or cover, so a playlist shown or
 * played again needs no lookups. Keeps the newest [MAX_ENTRIES] in [file]. Only what was found is
 * kept: a song that wasn't, maybe just offline, is looked up again next time.
 */
internal class LookupCache(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** How many to keep; fewer where each one is big, such as a song's words. */
    private val maxEntries: Int = MAX_ENTRIES
) {
    private val loading = Mutex()
    private var entries: LinkedHashMap<String, String>? = null
    private var changed = false

    /** Writes happen away from whoever saved, so nothing waits for the disk, one at a time. */
    private val writes = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writing = Mutex()

    /** The newest list not written yet; a write that finds none has been overtaken by a later one. */
    private val pending = AtomicReference<String?>()

    suspend fun get(key: String): String? = loaded().let { synchronized(it) { it[key] } }

    suspend fun put(key: String, value: String) = loaded().let { entries ->
        synchronized(entries) {
            // Moved to the end, so the oldest are dropped first.
            entries.remove(key)
            entries[key] = value
            entries.keys.take(maxOf(0, entries.size - maxEntries)).forEach(entries::remove)
            changed = true
        }
    }

    /** Writes what changed since the last save, all at once rather than after every song. */
    fun save() {
        val entries = entries ?: return
        val json = synchronized(entries) {
            if (!changed) return
            changed = false
            JSONArray().apply { entries.forEach { (key, value) -> put(JSONArray().put(key).put(value)) } }.toString()
        }
        pending.set(json)
        writes.launch { writing.withLock { pending.getAndSet(null)?.let(::write) } }
    }

    private fun write(json: String) {
        try {
            file.parentFile?.mkdirs()
            // Written aside first, so a crash midway never leaves a broken file.
            val temporary = File(file.path + ".tmp")
            temporary.writeText(json)
            temporary.renameTo(file)
        } catch (_: IOException) {
            // Not kept, it's just looked up again.
        }
    }

    private suspend fun loaded(): LinkedHashMap<String, String> =
        entries ?: loading.withLock { entries ?: withContext(ioDispatcher) { read() }.also { entries = it } }

    private fun read(): LinkedHashMap<String, String> {
        val entries = LinkedHashMap<String, String>()
        try {
            if (!file.isFile) return entries
            val list = JSONArray(file.readText())
            (0 until list.length()).mapNotNull(list::optJSONArray).forEach { entries[it.optString(0)] = it.optString(1) }
        } catch (_: IOException) {
            // Unreadable, it starts over.
        } catch (_: JSONException) {
            // Unreadable, it starts over.
        }
        return entries
    }

    private companion object {
        /** About twenty long playlists' worth of songs, each found in an app or two. */
        const val MAX_ENTRIES = 4000
    }
}
