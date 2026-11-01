package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

/** Runs under Robolectric for the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
class OfflineSongsTest {
    private val dir = createTempDirectory("kept").toFile()
    private val file = File(dir, "songs.json")
    private val words = LookupCache(File(dir, "lyrics.json"), Dispatchers.Unconfined, maxEntries = 1)
    private val fake = FakeSpotify().apply { handler = { request -> FakeSpotify.image(request, FakeSpotify.png()) } }
    private val covers = ArtworkLoader(fake.client(), Dispatchers.Unconfined, keptDir = File(dir, "covers"))

    private fun songs() = OfflineSongs(file, listOf(words), covers, Dispatchers.Unconfined)

    private val song = MusicMetadata("Song", "Band", artworkUrl = "https://img.example/song.jpg", url = "https://open.spotify.com/track/1", lengthMs = 200_000, recordingId = "spotify:1")
    private val other = MusicMetadata("Other", "Band", artworkUrl = "https://img.example/other.jpg")
    private val list = MusicMetadata("Mix", "", ItemType.PLAYLIST, url = "https://open.spotify.com/playlist/9", tracks = listOf(song, other))

    @Test
    fun aSongOrAListIsKeptWithItsSongsUntilLetGo() = runBlocking {
        val songs = songs()
        val kept = songs.keep(song)
        assertEquals(listOf(song), kept.songs)
        assertFalse(kept.done)
        // Kept again, it's the same one.
        assertEquals(kept, songs.keep(song))
        val mix = songs.keep(list)
        assertEquals(listOf(song, other), mix.songs)
        assertEquals(mix, songs.has(list))
        assertNull(songs.has(other))

        // What a song needs is kept for each one that keeps it, and so is its cover.
        withContext(Pinning(kept.id)) { words.put("song", "words") }
        withContext(Pinning(mix.id)) { words.get("song"); words.put("other", "more words") }
        covers.keep(song.artworkUrl!!)
        covers.keep(other.artworkUrl!!)
        songs.saved(mix.id, song)
        songs.saved(mix.id, other)
        assertTrue(songs.has(list)!!.done)
        assertEquals(("song" + "words" + "other" + "more words").length + covers.keptSize(), songs.size())

        // Across restarts, songs and all.
        val restarted = songs()
        assertEquals(listOf(kept.copy(), mix.copy(saved = setOf(OfflineSongs.keyOf(song), OfflineSongs.keyOf(other)))), restarted.all())

        // Let go, the list's song the other still keeps stays, with its cover.
        restarted.letGo(mix.id)
        assertEquals(listOf(kept), restarted.all())
        assertEquals(("song" + "words").length.toLong() + File(dir, "covers").listFiles()!!.sumOf { it.length() }, restarted.size())
        assertEquals(1, File(dir, "covers").listFiles()!!.size)
        restarted.letGo(kept.id)
        assertEquals(0L, restarted.size())
        assertEquals(emptyList<KeptOffline>(), songs().all())
    }

    @Test
    fun aBrokenOrUnwritableFileKeepsNothingForLater() = runBlocking {
        file.writeText("not json")
        assertEquals(emptyList<KeptOffline>(), songs().all())
        // A file can disappear after isFile. Unlike permission bits, this fails even in a root container.
        val vanished = object : File(dir, "gone.json") {
            override fun isFile() = true
        }
        assertEquals(emptyList<KeptOffline>(), OfflineSongs(vanished, emptyList(), null, Dispatchers.Unconfined).all())
        // A list with no link is known by its name; one kept by a version that left out its id, too.
        file.writeText("""[{"item":{"title":"Mix","artist":"","type":"ALBUM"}},{"item":{}}]""")
        assertEquals(listOf("album:Mix\u0000"), songs().all().map { it.id })
        val unwritable = OfflineSongs(File(File(dir, "file").apply { writeText("x") }, "songs.json"), emptyList(), null, Dispatchers.Unconfined)
        unwritable.keep(song)
        assertEquals(1, unwritable.all().size)
        assertEquals(0L, unwritable.size())
    }
}
