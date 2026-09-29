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
class ExactMatcherTest {

    private val fake = FakeSpotify()
    private val song = MusicMetadata("Beyoncé Song", "Carly Rae Jepsen")

    private fun matcher(country: String = "AR") = ExactMatcher(fake.client(), country)

    private fun respond(json: String) {
        fake.handler = { request: Request -> FakeSpotify.html(request, json) }
    }

    @Test
    fun appleMusicSkipsRemixesAndIgnoresAccentsAndPunctuation() {
        respond(
            """{"results":[
                {"trackName":"Beyoncé Song (Remix)","artistName":"Carly Rae Jepsen","trackViewUrl":"https://music.apple.com/remix"},
                {"trackName":"Beyonce song","artistName":"Carly Rae Jepsen & Friends","trackViewUrl":"https://music.apple.com/exact"}
            ]}"""
        )

        assertEquals("https://music.apple.com/exact", runBlocking { matcher().find(MusicService.APPLE_MUSIC, song) })
        val url = fake.requestedUrls.single()
        assertTrue(url, url.startsWith("https://itunes.apple.com/search?term=Beyonc%C3%A9%20Song%20Carly%20Rae%20Jepsen"))
        assertTrue(url, url.endsWith("&entity=song&limit=10&country=AR"))
    }

    @Test
    fun appleMusicLooksUpAlbumsAndArtistsWithTheirOwnEntities() {
        respond("""{"results":[{"collectionName":"After Hours","artistName":"The Weeknd","collectionViewUrl":"https://music.apple.com/album"}]}""")
        val album = MusicMetadata("After Hours", "The Weeknd", ItemType.ALBUM)
        assertEquals("https://music.apple.com/album", runBlocking { matcher("").find(MusicService.APPLE_MUSIC, album) })
        assertTrue(fake.requestedUrls.last().endsWith("&entity=album&limit=10"))

        respond("""{"results":[{"artistName":"The Weeknd","artistLinkUrl":"https://music.apple.com/artist"}]}""")
        val artist = MusicMetadata("The Weeknd", "", ItemType.ARTIST)
        assertEquals("https://music.apple.com/artist", runBlocking { matcher().find(MusicService.APPLE_MUSIC, artist) })
        assertTrue(fake.requestedUrls.last().contains("&entity=musicArtist&"))
    }

    @Test
    fun deezerMatchesTracksAlbumsAndArtists() {
        respond("""{"data":[{"title":"Beyoncé Song","artist":{"name":"Carly Rae Jepsen"},"link":"https://www.deezer.com/track/1"}]}""")
        assertEquals("https://www.deezer.com/track/1", runBlocking { matcher().find(MusicService.DEEZER, song) })
        assertTrue(fake.requestedUrls.last().startsWith("https://api.deezer.com/search/track?q="))

        respond("""{"data":[{"title":"After Hours","artist":{"name":"The Weeknd"},"link":"https://www.deezer.com/album/2"}]}""")
        val album = MusicMetadata("After Hours", "The Weeknd", ItemType.ALBUM)
        assertEquals("https://www.deezer.com/album/2", runBlocking { matcher().find(MusicService.DEEZER, album) })
        assertTrue(fake.requestedUrls.last().startsWith("https://api.deezer.com/search/album?q="))

        respond("""{"data":[{"name":"The Weeknd","link":"https://www.deezer.com/artist/3"}]}""")
        val artist = MusicMetadata("The Weeknd", "", ItemType.ARTIST)
        assertEquals("https://www.deezer.com/artist/3", runBlocking { matcher().find(MusicService.DEEZER, artist) })
        assertTrue(fake.requestedUrls.last().startsWith("https://api.deezer.com/search/artist?q="))
    }

    @Test
    fun uncertainOrMissingMatchesFallBackToSearch() {
        respond("""{"results":[{"trackName":"Beyoncé Song","artistName":"Someone Else","trackViewUrl":"https://x"}]}""")
        assertNull(runBlocking { matcher().find(MusicService.APPLE_MUSIC, song) })

        respond("""{"results":[{"trackName":"Beyoncé Song","artistName":"Carly Rae Jepsen","trackViewUrl":""}]}""")
        assertNull(runBlocking { matcher().find(MusicService.APPLE_MUSIC, song) })

        respond("""{"unexpected":true}""")
        assertNull(runBlocking { matcher().find(MusicService.APPLE_MUSIC, song) })
        assertNull(runBlocking { matcher().find(MusicService.DEEZER, song) })

        // Titles alone are enough when Spotify gave no artist.
        respond("""{"data":[{"title":"Beyoncé Song","artist":{"name":"Anyone"},"link":"https://www.deezer.com/track/9"}]}""")
        assertEquals(
            "https://www.deezer.com/track/9",
            runBlocking { matcher().find(MusicService.DEEZER, song.copy(artist = "")) }
        )
    }

    @Test
    fun failuresAndUnsupportedCasesReturnNullWithoutCrashing() {
        respond("not json")
        assertNull(runBlocking { matcher().find(MusicService.DEEZER, song) })

        fake.handler = { throw IOException("offline") }
        assertNull(runBlocking { matcher().find(MusicService.APPLE_MUSIC, song) })

        fake.requestedUrls.clear()
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
        assertNull(runBlocking { matcher().find(MusicService.APPLE_MUSIC, song.copy(type = ItemType.PLAYLIST)) })
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun artistsAreComparedByWholeName() {
        // Credited to just the main artist on Deezer.
        respond("""{"data":[{"title":"Duet","artist":{"name":"Main Artist"},"link":"https://www.deezer.com/track/5"}]}""")
        assertEquals(
            "https://www.deezer.com/track/5",
            runBlocking { matcher().find(MusicService.DEEZER, MusicMetadata("Duet", "Main Artist & Guest feat. Other")) }
        )

        respond("""{"data":[{"title":"Chandelier","artist":{"name":"Karaoke Asia"},"link":"https://www.deezer.com/track/6"}]}""")
        assertNull(runBlocking { matcher().find(MusicService.DEEZER, MusicMetadata("Chandelier", "Sia")) })
    }

    @Test
    fun appleSinglesAndEpsMatchTheirPlainAlbumName() {
        respond("""{"results":[{"collectionName":"Hit - Single","artistName":"Artist","collectionViewUrl":"https://music.apple.com/single"}]}""")
        assertEquals(
            "https://music.apple.com/single",
            runBlocking { matcher().find(MusicService.APPLE_MUSIC, MusicMetadata("Hit", "Artist", ItemType.ALBUM)) }
        )
    }
}
