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
 * played again needs no lookups. Keeps the newest [MAX_ENTRIES] in [file], and past them, as many
 * again of those used on [OFTEN_DAYS] days or more, so a song played often stays even after many
 * new ones. Only what was found is kept: a song that wasn't, maybe just offline, is looked up again
 * next time.
 */
internal class LookupCache(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** How many to keep; fewer where each one is big, such as a song's words. */
    private val maxEntries: Int = MAX_ENTRIES,
    /** Which day it is, counted since 1970. */
    private val today: () -> Long = { System.currentTimeMillis() / DAY_MS }
) {
    /** What was found, and on how many days it was used, the last of them [lastDay]. */
    private class Entry(val value: String, val days: Int, val lastDay: Long)

    private val loading = Mutex()
    private var entries: LinkedHashMap<String, Entry>? = null
    private var changed = false

    /** Writes happen away from whoever saved, so nothing waits for the disk, one at a time. */
    private val writes = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writing = Mutex()

    /** The newest list not written yet; a write that finds none has been overtaken by a later one. */
    private val pending = AtomicReference<String?>()

    /**
     * What's kept for [key], which then counts as just used, so what's used often stays longest;
     * used again on another day, it counts toward staying past the newest.
     */
    suspend fun get(key: String): String? = loaded().let { entries ->
        synchronized(entries) {
            val entry = entries.remove(key) ?: return null
            val now = today()
            entries[key] = if (entry.lastDay == now) entry else Entry(entry.value, entry.days + 1, now)
            changed = true
            entry.value
        }
    }

    /** All that's kept, the longest unused first. */
    suspend fun entries(): List<Pair<String, String>> = loaded().let { entries -> synchronized(entries) { entries.map { (key, entry) -> key to entry.value } } }

    suspend fun put(key: String, value: String) = loaded().let { entries ->
        synchronized(entries) {
            // Moved to the end, so the longest unused are dropped first. Found again, its days still count.
            val old = entries.remove(key)
            entries[key] = Entry(value, old?.days ?: 1, old?.lastDay ?: today())
            trim(entries)
            changed = true
        }
    }

    /** Drops what's older than the newest [maxEntries], but for the most used of it, used on [OFTEN_DAYS] days or more. */
    private fun trim(entries: LinkedHashMap<String, Entry>) {
        val (often, rarely) = entries.entries.take(maxOf(0, entries.size - maxEntries)).partition { it.value.days >= OFTEN_DAYS }
        rarely.map { it.key }.forEach(entries::remove)
        // Only as many again are kept that way, the least used of them going first.
        often.sortedBy { it.value.days }.take(maxOf(0, often.size - maxEntries)).map { it.key }.forEach(entries::remove)
    }

    /** Writes what changed since the last save, all at once rather than after every song. */
    fun save() {
        val entries = entries ?: return
        val json = synchronized(entries) {
            if (!changed) return
            changed = false
            JSONArray().apply { entries.forEach { (key, entry) -> put(JSONArray().put(key).put(entry.value).put(entry.days).put(entry.lastDay)) } }.toString()
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

    private suspend fun loaded(): LinkedHashMap<String, Entry> =
        entries ?: loading.withLock { entries ?: withContext(ioDispatcher) { read() }.also { entries = it } }

    private fun read(): LinkedHashMap<String, Entry> {
        val entries = LinkedHashMap<String, Entry>()
        try {
            if (!file.isFile) return entries
            val list = JSONArray(file.readText())
            // Saved before days were counted, each was used on one, today.
            (0 until list.length()).mapNotNull(list::optJSONArray).forEach { entries[it.optString(0)] = Entry(it.optString(1), it.optInt(2, 1), it.optLong(3, today())) }
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

        /** Used on this many days, it's played often. */
        const val OFTEN_DAYS = 3
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
