package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Remembers what was found for songs, such as each one's video or cover, so a playlist shown or
 * played again needs no lookups. Keeps the newest [MAX_ENTRIES] in [file], and past them, as many
 * again of those used on [OFTEN_DAYS] days or more, so a song played often stays even after many
 * new ones. What's looked up for a song kept offline, inside [Pinning], is never dropped until the
 * song is let go. Only what was found is kept: a song that wasn't, maybe just offline, is looked up
 * again next time.
 */
internal class LookupCache(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** How many to keep; fewer where each one is big, such as a song's words. */
    private val maxEntries: Int = MAX_ENTRIES,
    /** Which day it is, counted since 1970. */
    private val today: () -> Long = { System.currentTimeMillis() / DAY_MS },
    /**
     * Where what songs kept offline need goes, away from [file] when that's in the app's cache, which
     * Android may clear when the phone runs out of room.
     */
    private val pinnedFile: File = file
) {
    /** What was found, and on how many days it was used, the last of them [lastDay]; [pins] are the songs kept offline that need it. */
    private class Entry(val value: String, val days: Int, val lastDay: Long, val pins: Set<String> = emptySet())

    private val loading = Mutex()
    private var entries: LinkedHashMap<String, Entry>? = null
    private var changed = false

    /** Writes happen away from whoever saved, so nothing waits for the disk, one at a time. */
    private val writes = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writing = Mutex()

    /** The newest lists not written yet, by file; a write that finds none has been overtaken by a later one. */
    private val pending = AtomicReference<Map<File, String>?>()

    /**
     * What's kept for [key], which then counts as just used, so what's used often stays longest;
     * used again on another day, it counts toward staying past the newest.
     */
    suspend fun get(key: String): String? {
        val pin = currentCoroutineContext()[Pinning]?.id
        return loaded().let { entries ->
            synchronized(entries) {
                val entry = entries.remove(key) ?: return null
                val now = today()
                entries[key] = Entry(entry.value, if (entry.lastDay == now) entry.days else entry.days + 1, now, entry.pins + listOfNotNull(pin))
                changed = true
                entry.value
            }
        }
    }

    /** All that's kept, the longest unused first. */
    suspend fun entries(): List<Pair<String, String>> = loaded().let { entries -> synchronized(entries) { entries.map { (key, entry) -> key to entry.value } } }

    suspend fun put(key: String, value: String) {
        val pin = currentCoroutineContext()[Pinning]?.id
        loaded().let { entries ->
            synchronized(entries) {
                // Moved to the end, so the longest unused are dropped first. Found again, its days and songs kept offline still count.
                val old = entries.remove(key)
                entries[key] = Entry(value, old?.days ?: 1, old?.lastDay ?: today(), old?.pins.orEmpty() + listOfNotNull(pin))
                trim(entries)
                changed = true
            }
        }
    }

    /** How much what songs kept offline need takes, in characters, about as many bytes. */
    suspend fun pinnedSize(): Long = loaded().let { entries ->
        synchronized(entries) { entries.entries.filter { it.value.pins.isNotEmpty() }.sumOf { (it.key.length + it.value.value.length).toLong() } }
    }

    /** Lets go of what the song kept offline as [pin] needed: it's then dropped as anything else would be. */
    suspend fun unpin(pin: String) = loaded().let { entries ->
        synchronized(entries) {
            entries.replaceAll { _, entry -> if (pin in entry.pins) Entry(entry.value, entry.days, entry.lastDay, entry.pins - pin) else entry }
            trim(entries)
            changed = true
        }
    }

    /**
     * Drops what's older than the newest [maxEntries], but for the most used of it, used on [OFTEN_DAYS]
     * days or more, and what songs kept offline need, which doesn't count.
     */
    private fun trim(entries: LinkedHashMap<String, Entry>) {
        val loose = entries.entries.filter { it.value.pins.isEmpty() }
        val (often, rarely) = loose.take(maxOf(0, loose.size - maxEntries)).partition { it.value.days >= OFTEN_DAYS }
        rarely.map { it.key }.forEach(entries::remove)
        // Only as many again are kept that way, the least used of them going first.
        often.sortedBy { it.value.days }.take(maxOf(0, often.size - maxEntries)).map { it.key }.forEach(entries::remove)
    }

    /** Writes what changed since the last save, all at once rather than after every song. */
    fun save() {
        val entries = entries ?: return
        val lists = synchronized(entries) {
            if (!changed) return
            changed = false
            fun json(of: List<Map.Entry<String, Entry>>) =
                JSONArray().apply { of.forEach { (key, entry) -> put(JSONArray().put(key).put(entry.value).put(entry.days).put(entry.lastDay).put(JSONArray(entry.pins.toList()))) } }.toString()
            if (pinnedFile == file) {
                mapOf(file to json(entries.entries.toList()))
            } else {
                val (pinned, loose) = entries.entries.partition { it.value.pins.isNotEmpty() }
                mapOf(file to json(loose), pinnedFile to json(pinned))
            }
        }
        pending.set(lists)
        writes.launch { writing.withLock { pending.getAndSet(null)?.forEach { (file, json) -> write(file, json) } } }
    }

    private fun write(file: File, json: String) {
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

    /** What's kept in [file], then what songs kept offline need, in [pinnedFile]. */
    private fun read(): LinkedHashMap<String, Entry> = LinkedHashMap<String, Entry>().also { entries ->
        read(file, entries)
        if (pinnedFile != file) read(pinnedFile, entries)
    }

    private fun read(file: File, entries: LinkedHashMap<String, Entry>) {
        try {
            if (!file.isFile) return
            val list = JSONArray(file.readText())
            // Saved before days were counted, each was used on one, today.
            (0 until list.length()).mapNotNull(list::optJSONArray).forEach { entry ->
                val pins = entry.optJSONArray(4)?.let { ids -> (0 until ids.length()).map(ids::optString).toSet() }.orEmpty()
                entries[entry.optString(0)] = Entry(entry.optString(1), entry.optInt(2, 1), entry.optLong(3, today()), pins)
            }
        } catch (_: IOException) {
            // Unreadable, it starts over.
        } catch (_: JSONException) {
            // Unreadable, it starts over.
        }
    }

    private companion object {
        /** About twenty long playlists' worth of songs, each found in an app or two. */
        const val MAX_ENTRIES = 4000

        /** Used on this many days, it's played often. */
        const val OFTEN_DAYS = 3
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * Lookups made for a song kept offline, as [id]: what they find, or find already kept, is kept for it
 * until it's let go, and counts toward the space songs kept offline may take.
 */
internal class Pinning(val id: String) : AbstractCoroutineContextElement(Pinning) {
    companion object Key : CoroutineContext.Key<Pinning>
}
