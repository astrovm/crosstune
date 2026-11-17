package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

/** What was heard sung of songs with untimed words, kept for next time. Runs under Robolectric for the real org.json. */
@RunWith(RobolectricTestRunner::class)
class HeardSongsTest {

    private val file = File(createTempDirectory("heard").toFile(), "heard_untimed.json")
    private val song = MusicMetadata("Demo", "Band", lengthMs = 60_000)
    private val words = "Plain one\nPlain two"

    private fun store() = HeardSongs(LookupCache(file, Dispatchers.Unconfined))

    private fun stretch(fromMs: Long) = HeardStretch(fromMs, fromMs + 20_000, listOf("Plain" to fromMs + 600, "one" to fromMs + 900))

    @Test
    fun whatsHeardIsThereNextTime() = runBlocking {
        assertEquals(emptyList<HeardStretch>(), store().load(song, words))
        val heard = listOf(stretch(0), HeardStretch(20_000, 40_000, emptyList()))
        store().save(song, words, heard)
        assertEquals(heard, store().load(song, words))
        // Other words for the song are other lines, heard again; and another recording is another song.
        assertEquals(emptyList<HeardStretch>(), store().load(song, "Other words"))
        assertEquals(emptyList<HeardStretch>(), store().load(song.copy(lengthMs = 200_000), words))
    }

    @Test
    fun theOldestGoFirst() = runBlocking {
        val heard = (0..HeardSongs.MAX_STRETCHES).map { stretch(it * 20_000L) }
        store().save(song, words, heard)
        assertEquals(heard.drop(1), store().load(song, words))
    }

    @Test
    fun keptSomeOtherWayItsHeardAgain() = runBlocking {
        val cache = LookupCache(file, Dispatchers.Unconfined)
        HeardSongs(cache).save(song, words, listOf(stretch(0)))
        val key = cache.entries().single().first
        cache.put(key, """{"not":"a list"}""")
        assertEquals(emptyList<HeardStretch>(), HeardSongs(cache).load(song, words))
    }
}
