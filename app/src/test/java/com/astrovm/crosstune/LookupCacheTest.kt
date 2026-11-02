package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

/** Runs under Robolectric for the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
class LookupCacheTest {

    private val dir = createTempDirectory("lookups").toFile()
    private val file = File(dir, "lookups.json")

    private fun cache(file: File = this.file) = LookupCache(file, Dispatchers.Unconfined)

    @Test
    fun whatsUsedOftenStaysLongest() = runBlocking {
        val cache = LookupCache(file, Dispatchers.Unconfined, maxEntries = 2)
        cache.put("a", "1")
        cache.put("b", "2")
        // Used again, "a" counts as newer than "b", which goes to make room.
        assertEquals("1", cache.get("a"))
        cache.put("c", "3")
        assertEquals("1", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("3", cache.get("c"))
        assertNull(cache.get("missing"))
        // Kept in that order too.
        cache.save()
        val restarted = LookupCache(file, Dispatchers.Unconfined, maxEntries = 2)
        restarted.put("d", "4")
        assertNull(restarted.get("a"))
        assertEquals("3", restarted.get("c"))
    }

    @Test
    fun whatsUsedOnThreeDaysStaysPastTheNewest() = runBlocking {
        var day = 20_000L
        fun cache() = LookupCache(file, Dispatchers.Unconfined, maxEntries = 2, today = { day })
        val cache = cache()
        /** Found, then used again on each of [days] more days, [times] each day. */
        suspend fun used(key: String, days: Int, times: Int = 1) {
            cache.put(key, key)
            repeat(days) { day++; repeat(times) { cache.get(key) } }
        }
        used("often", days = 2)
        // Twice a day isn't twice as often.
        used("twice", days = 1, times = 2)
        cache.put("new", "new")
        cache.put("newer", "newer")
        assertEquals(listOf("often", "new", "newer"), cache.entries().map { it.first })
        // Found again, its days still count.
        cache.put("often", "found again")
        cache.put("newest", "newest")
        cache.put("newest 2", "newest 2")
        assertEquals(listOf("often", "newest", "newest 2"), cache.entries().map { it.first })

        // As many again as the newest are kept that way, the most used: and across restarts.
        used("most", days = 4)
        used("more", days = 3)
        cache.put("a", "a")
        cache.put("b", "b")
        cache.save()
        val restarted = cache()
        restarted.put("c", "c")
        // "often", used on fewer days than the other two, went to make room.
        assertEquals(listOf("most", "more", "b", "c"), restarted.entries().map { it.first })
    }

    @Test
    fun theDayItWasLastUsedIsToday() = runBlocking {
        val cache = cache()
        cache.put("song", "found")
        cache.save()
        val today = System.currentTimeMillis() / (24 * 60 * 60 * 1000L)
        assertEquals(today, JSONArray(file.readText()).getJSONArray(0).getLong(3))
    }

    @Test
    fun aFileSavedBeforeDaysWereCountedStillReads() = runBlocking {
        file.writeText("""[["song","found"]]""")
        assertEquals("found", cache().get("song"))
    }

    @Test
    fun whatsFoundIsKeptAcrossRestartsUpToTheNewest() = runBlocking {
        val cache = cache()
        // Nothing to save yet.
        cache.save()
        assertEquals(false, file.exists())
        repeat(4000) { cache.put("song $it", "found $it") }
        // One found again counts as new, so the oldest after it goes to make room.
        cache.put("song 0", "found again")
        cache.put("song 4000", "found 4000")
        cache.save()
        // Saving with nothing new writes nothing.
        file.delete()
        cache.save()
        assertEquals(false, file.exists())
        cache.put("song 4000", "found 4000")
        cache.save()

        val restarted = cache()
        assertNull(restarted.get("song 1"))
        assertEquals("found again", restarted.get("song 0"))
        assertEquals("found 2", restarted.get("song 2"))
        assertEquals("found 4000", restarted.get("song 4000"))
    }

    @Test
    fun aBrokenOrUnwritableFileJustStartsOver() = runBlocking {
        file.writeText("not json")
        assertNull(cache().get("song"))
        // A file can disappear after isFile. Unlike permission bits, this fails even in a root container.
        val vanished = object : File(dir, "gone.json") {
            override fun isFile() = true
        }
        assertNull(cache(vanished).get("song"))

        // Somewhere it can't write, it still remembers for now.
        val notADirectory = File(dir, "file").apply { writeText("x") }
        val unwritable = cache(File(notADirectory, "lookups.json"))
        unwritable.put("song", "found")
        unwritable.save()
        assertEquals("found", unwritable.get("song"))
    }
}
