package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
class SongSearcherTest {

    private val fake = FakeSpotify()

    /** Deezer answers unless a request is for Apple, so one handler covers a whole search. */
    private fun respond(deezer: String, itunes: String = """{"results":[]}""") {
        fake.handler = { request: Request ->
            if (request.url.host == "itunes.apple.com") FakeSpotify.html(request, itunes) else FakeSpotify.html(request, deezer)
        }
    }

    private fun found(query: String) = runBlocking { SongSearcher(fake.client(), country = "AR").search(query) }

    private val cover = "https://cdn-images.dzcdn.net/images/cover/abc/500x500-80-0-0.jpg"

    @Test
    fun aSongIsFoundByItsNameAndKeepsItsOwnLink() {
        respond(
            """{"data":[{"id":608098722,"title":"Dakare Ni Kita Onna","link":"https://www.deezer.com/track/608098722",
                "artist":{"name":"Kingo Hamada"},"album":{"cover_big":"$cover"}}]}"""
        )
        val songs = found("Dakare Ni Kita Onna")
        assertEquals(
            listOf(MusicMetadata("Dakare Ni Kita Onna", "Kingo Hamada", url = "https://www.deezer.com/track/608098722", artworkUrl = cover)),
            songs
        )
        val asked = fake.requestedUrls.first()
        assertTrue(asked, asked.startsWith("https://api.deezer.com/search/track?q="))
        assertTrue(asked, asked.contains("Dakare%20Ni%20Kita%20Onna"))
    }

    @Test
    fun appleAnswersWhenDeezerHasNothing() {
        respond(
            """{"data":[]}""",
            """{"results":[{"trackName":"Love Me Do","artistName":"The Beatles","trackViewUrl":"https://music.apple.com/us/song/1","artworkUrl100":"https://is1/cover.jpg"}]}"""
        )
        assertEquals(
            listOf(MusicMetadata("Love Me Do", "The Beatles", url = "https://music.apple.com/us/song/1", artworkUrl = "https://is1/cover.jpg")),
            found("Love Me Do")
        )
        assertTrue(fake.requestedUrls.any { it.startsWith("https://itunes.apple.com/search?") })
        // Apple's own country is asked, so it answers with what that country carries.
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.any { it.contains("country=AR") })
    }

    @Test
    fun deezersAnswersLeadAndARepeatedSongIsOne() {
        respond(
            """{"data":[{"id":1,"title":"Love Me Do","link":"https://www.deezer.com/track/1","artist":{"name":"The Beatles"}}]}""",
            """{"results":[{"trackName":"Love Me Do","artistName":"The Beatles","trackViewUrl":"https://music.apple.com/us/song/1"},
                       {"trackName":"Yesterday","artistName":"The Beatles","trackViewUrl":"https://music.apple.com/us/song/2"}]}"""
        )
        // The same song from both services is one song, and Deezer's comes first.
        assertEquals(
            listOf(
                MusicMetadata("Love Me Do", "The Beatles", url = "https://www.deezer.com/track/1"),
                MusicMetadata("Yesterday", "The Beatles", url = "https://music.apple.com/us/song/2")
            ),
            found("Love Me Do")
        )
    }

    @Test
    fun nothingToSearchAndNothingFoundBothFindNothing() {
        respond("""{"data":[]}""")
        assertEquals(emptyList<MusicMetadata>(), found("   "))
        assertEquals(emptyList<MusicMetadata>(), found("qqqqzzzznotasong"))

        // Rows that name no song, or no artist to go with the name, are left out.
        respond("""{"data":[{"id":1,"title":"","link":"https://www.deezer.com/track/1","artist":{"name":"Band"}}]}""")
        assertEquals(emptyList<MusicMetadata>(), found("Song"))
        respond("""{"data":[{"id":1,"title":"Song","link":"https://www.deezer.com/track/1","artist":{"name":""}}]}""")
        assertEquals(emptyList<MusicMetadata>(), found("Song"))
        respond("""{"data":[null,7,"nope"]}""")
        assertEquals(emptyList<MusicMetadata>(), found("Song"))

        // A row with no link or cover is still a song; the words are what matter.
        respond("""{"data":[{"id":1,"title":"Song","artist":{"name":"Band"}}]}""")
        val bare = found("Song").single()
        assertEquals("Song", bare.title)
        assertNull(bare.url)
        assertNull(bare.artworkUrl)
    }

    @Test
    fun oneServiceBeingOfflineCostsOnlyItsOwnAnswers() {
        fake.handler = { request: Request ->
            if (request.url.host == "api.deezer.com") throw IOException("offline")
            FakeSpotify.html(
                request,
                """{"results":[{"trackName":"Yesterday","artistName":"The Beatles",
                    "trackViewUrl":"https://music.apple.com/us/song/2","artworkUrl100":"https://is1/cover.jpg"}]}"""
            )
        }
        // Deezer refusing must not cost the search Apple's answer to the same words.
        assertEquals(
            listOf(MusicMetadata("Yesterday", "The Beatles", url = "https://music.apple.com/us/song/2", artworkUrl = "https://is1/cover.jpg")),
            found("Yesterday")
        )
        assertTrue(fake.requestedUrls.any { it.startsWith("https://itunes.apple.com/search?") })
    }

    @Test
    fun noServiceAndNoTimeBothFindNothing() {
        fake.handler = { throw IOException("offline") }
        assertEquals(emptyList<MusicMetadata>(), found("Song"))

        respond("""{"data":[{"id":1,"title":"Song","link":"https://www.deezer.com/track/1","artist":{"name":"Band"}}]}""")
        fake.delayMillis = 300
        // A search that runs past the time the list waits gives up rather than hang there empty.
        assertEquals(
            emptyList<MusicMetadata>(),
            runBlocking { SongSearcher(fake.client()).search("Song", timeoutMs = 20L) }
        )
        fake.delayMillis = 0
    }
}