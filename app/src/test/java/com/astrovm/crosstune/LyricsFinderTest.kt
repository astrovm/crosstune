package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Runs under Robolectric for the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
class LyricsFinderTest {

    private val fake = FakeSpotify()

    private fun respond(json: String) {
        fake.handler = { request: Request -> FakeSpotify.html(request, json) }
    }

    private fun answer(title: String, artist: String, lyrics: String, synced: String = "") =
        """{"trackName":${quote(title)},"artistName":${quote(artist)},"plainLyrics":${quote(lyrics)},"syncedLyrics":${quote(synced)}}"""

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun found(song: MusicMetadata) = runBlocking { LyricsFinder(fake.client()).lyricsOf(song) }

    @Test
    fun aSongGetsItsOwnWords() {
        respond("""{"data":[${answer("Dakare Ni Kita Onna", "Kingo Hamada", "夜が灯りを\n投げるBedで")}]}""")
        assertEquals("夜が灯りを\n投げるBedで", found(MusicMetadata("Dakare Ni Kita Onna", "Kingo Hamada")))
        val asked = fake.requestedUrls.single()
        assertTrue(asked, asked.startsWith("https://lrclib.net/api/search"))
        assertTrue(asked, asked.contains("track_name=Dakare%20Ni%20Kita%20Onna"))
        assertTrue(asked, asked.contains("artist_name=Kingo%20Hamada"))
    }

    @Test
    fun theWordsOfAnotherSongAreNeverTaken() {
        // Someone else's song of the same name is no use at all.
        respond(
            """{"data":[${answer("Downer", "Whitearmor", "Not this one")},${answer("Downer", "The Cavemen", "Not this either")}]}"""
        )
        assertNull(found(MusicMetadata("Downer", "Someone Else Entirely")))

        // Nor is a different artist's song whose name is close to this one's.
        respond("""{"data":[${answer("Love Me Tender", "Elvis Presley", "Wrong song")}]}""")
        assertNull(found(MusicMetadata("Love Me Do", "The Beatles")))

        // The right artist but the wrong song among several is still left out.
        val otherSongs = listOf(
            answer("Perfect Day", "The Cure", "Wrong"),
            answer("Perfect Day", "Brian Eno", "Wrong too")
        ).joinToString(",")
        respond("""{"data":[$otherSongs]}""")
        assertNull(found(MusicMetadata("Perfect World", "Van Halen")))
    }

    @Test
    fun aNameWrittenAnotherWayStillCountsAsTheSameSong() {
        // A title in another script, written out in letters, and an artist credited with a tag.
        respond("""{"data":[${answer("FUTARI NO NATSU MONOGATARI NEVER ENDING SUMMER", "Omega Tribe", "夏物語")}]}""")
        assertEquals("夏物語", found(MusicMetadata("ふたりの夏物語 NEVER ENDING SUMMER", "S. Kiyotaka & Omega Tribe")))

        // "Randy" is inside "Randy Nota Loca", the same artist as the app already accepts.
        respond("""{"data":[${answer("Soy Una Gargola", "Randy Nota Loca", "Vengoñando")}]}""")
        assertEquals("Vengoñando", found(MusicMetadata("Soy una Gargola", "Alex Gargolas, Randy Nota Loca")))
    }

    @Test
    fun theServiceOwnAnswerWinsOverACloseOne() {
        val candidates = listOf(
            answer("Love Me Do - 2003 Remaster", "The Beatles", "Close one"),
            answer("Love Me Do", "The Beatles", "The right one")
        ).joinToString(",")
        respond("""{"data":[$candidates]}""")
        assertEquals("The right one", found(MusicMetadata("Love Me Do", "The Beatles")))
    }

    @Test
    fun nothingToShowAndNoAnswerBothShowNothing() {
        // Only a song has words, and without an artist there's nothing to check an answer against.
        assertNull(found(MusicMetadata("Album", "Artist", ItemType.ALBUM)))
        assertNull(found(MusicMetadata("Song", "")))
        assertNull(found(MusicMetadata("", "Artist")))

        // An answer with no words in it, or none at all.
        respond("""{"data":[${answer("Song", "Band", "")}]}""")
        assertNull(found(MusicMetadata("Song", "Band")))
        respond("""{"data":[]}""")
        assertNull(found(MusicMetadata("Song", "Band")))
        respond("{}")
        assertNull(found(MusicMetadata("Song", "Band")))

        // Entries that name no song are skipped rather than taken for one.
        respond("""{"data":[{"artistName":"Band","plainLyrics":"Words"},${answer("Song", "Band", "Words")}]}""")
        assertEquals("Words", found(MusicMetadata("Song", "Band")))
        respond("""{"data":[null,"nope",7]}""")
        assertNull(found(MusicMetadata("Song", "Band")))

        // Synced words carry their timings inline, so they're not what a song's lyrics are.
        respond("""{"data":[{"trackName":"Song","artistName":"Band","syncedLyrics":"[00:01.00] Timed"}]}""")
        assertNull(found(MusicMetadata("Song", "Band")))
    }

    @Test
    fun noServiceAndNoTimeBothShowNothing() {
        fake.handler = { throw IOException("offline") }
        assertNull(found(MusicMetadata("Song", "Band")))

        // A lookup that takes longer than it's given gives up rather than hold the screen.
        respond("""{"data":[${answer("Song", "Band", "Words")}]}""")
        fake.delayMillis = 300
        assertNull(runBlocking { LyricsFinder(fake.client()).lyricsOf(MusicMetadata("Song", "Band"), timeoutMs = 20L) })
        fake.delayMillis = 0

        respond("not json at all")
        assertNull(found(MusicMetadata("Song", "Band")))
    }
}