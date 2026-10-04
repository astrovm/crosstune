package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
        file.writeText("[]")
        file.setReadable(false)
        assertNull(cache().get("song"))
        file.setReadable(true)

        // Somewhere it can't write, it still remembers for now.
        val notADirectory = File(dir, "file").apply { writeText("x") }
        val unwritable = cache(File(notADirectory, "lookups.json"))
        unwritable.put("song", "found")
        unwritable.save()
        assertEquals("found", unwritable.get("song"))
    }
}
