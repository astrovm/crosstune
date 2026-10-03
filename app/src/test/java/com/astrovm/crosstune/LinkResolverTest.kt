package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Checks each service is read from the right endpoint. Runs under Robolectric for org.json. */
@RunWith(RobolectricTestRunner::class)
class LinkResolverTest {

    private val fake = FakeSpotify()
    private val resolver = LinkResolver(fake.client())

    private fun resolve(text: String): Resolution = runBlocking { resolver.resolve(MusicLinks.parse(text)!!) }

    private fun respond(body: String, code: Int = 200) {
        fake.handler = { request: Request -> FakeSpotify.html(request, body, code = code) }
    }

    private fun assertResolved(text: String, metadataUrl: String, expected: MusicMetadata) {
        fake.requestedUrls.clear()
        val resolution = resolve(text) as Resolution.Resolved
        assertEquals(expected, resolution.metadata)
        assertEquals(listOf(metadataUrl), fake.requestedUrls)
    }

    @Test
    fun recognizedSongNeedsNoMetadataNetworkRequest() {
        val result = resolve("A Song by Example Band https://www.google.com/search?q=A+Song+by+Example+Band") as Resolution.Resolved
        assertEquals(MusicMetadata("A Song", "Example Band"), result.metadata)
        assertEquals(null, result.link.service)
        assertEquals(ItemType.TRACK, result.link.type)
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun youtubeAndYoutubeMusicUseOEmbed() {
        respond("""{"title":"Blinding Lights","author_name":"The Weeknd - Topic"}""")
        val oEmbed = "https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fmusic.youtube.com%2Fwatch%3Fv%3D4NRXx6U8ABQ"
        assertResolved("https://music.youtube.com/watch?v=4NRXx6U8ABQ", oEmbed, MusicMetadata("Blinding Lights", "The Weeknd"))
    }

    @Test
    fun appleMusicUsesLookupForItemsAndThePageForPlaylists() {
        respond("""{"results":[{"trackName":"Song","artistName":"Artist"}]}""")
        assertResolved(
            "https://music.apple.com/ar/song/song/123",
            "https://itunes.apple.com/lookup?id=123&country=ar",
            MusicMetadata("Song", "Artist")
        )

        respond("""<meta property="og:title" content="Chill on Apple Music">""")
        assertResolved(
            "https://music.apple.com/us/playlist/chill/pl.abc",
            "https://music.apple.com/us/playlist/chill/pl.abc",
            MusicMetadata("Chill", "", ItemType.PLAYLIST)
        )

        respond("""{"resultCount":0,"results":[]}""")
        assertEquals(AppError.NOT_FOUND, (resolve("https://music.apple.com/us/album/x/999") as Resolution.Failed).error)
        respond("<html></html>")
        assertEquals(
            AppError.METADATA_UNAVAILABLE,
            (resolve("https://music.apple.com/us/playlist/x/pl.abc") as Resolution.Failed).error
        )
    }

    @Test
    fun deezerUsesItsApiAndReportsMissingItems() {
        respond("""{"title":"After Hours","artist":{"name":"The Weeknd"}}""")
        assertResolved(
            "https://www.deezer.com/en/album/137272602",
            "https://api.deezer.com/album/137272602",
            MusicMetadata("After Hours", "The Weeknd", ItemType.ALBUM)
        )

        respond("""{"error":{"type":"DataException","message":"no data","code":800}}""")
        val failed = resolve("https://www.deezer.com/track/1") as Resolution.Failed
        assertEquals(AppError.NOT_FOUND, failed.error)
        assertEquals(MusicService.DEEZER, failed.link?.service)
    }

    @Test
    fun tidalSoundCloudAndBandcampPages() {
        respond("<title>Blinding Lights by The Weeknd on TIDAL</title>")
        assertResolved(
            "https://listen.tidal.com/track/134858527",
            "https://tidal.com/track/134858527",
            MusicMetadata("Blinding Lights", "The Weeknd")
        )

        respond("""{"title":"Blinding Lights by The Weeknd","author_name":"The Weeknd"}""")
        assertResolved(
            "https://soundcloud.com/theweeknd/blinding-lights",
            "https://soundcloud.com/oembed?format=json&url=https%3A%2F%2Fsoundcloud.com%2Ftheweeknd%2Fblinding-lights",
            MusicMetadata("Blinding Lights", "The Weeknd")
        )

        respond("""<meta property="og:title" content="Record, by Band">""")
        assertResolved(
            "https://band.bandcamp.com/album/record",
            "https://band.bandcamp.com/album/record",
            MusicMetadata("Record", "Band", ItemType.ALBUM)
        )
    }

    @Test
    fun unreadableJsonAndPrivateVideosFailWithTheLinkAttached() {
        respond("<html>not json</html>")
        val broken = resolve("https://youtu.be/4NRXx6U8ABQ") as Resolution.Failed
        assertEquals(AppError.METADATA_UNAVAILABLE, broken.error)
        assertEquals("https://www.youtube.com/watch?v=4NRXx6U8ABQ", broken.link?.url)

        respond("Unauthorized", code = 401)
        assertEquals(AppError.NOT_FOUND, (resolve("https://youtu.be/4NRXx6U8ABQ") as Resolution.Failed).error)
    }

    @Test
    fun shortLinksFromOtherServicesFollowRedirects() {
        fake.handler = { request ->
            when (request.url.host) {
                "link.deezer.com" -> FakeSpotify.html(request, "", finalUrl = "https://www.deezer.com/track/908604612?utm=x")
                else -> FakeSpotify.html(request, """{"title":"Blinding Lights","artist":{"name":"The Weeknd"}}""")
            }
        }
        val resolution = resolve("https://link.deezer.com/s/abc") as Resolution.Resolved
        assertEquals("https://www.deezer.com/track/908604612", resolution.link.url)
        assertEquals(
            listOf("https://link.deezer.com/s/abc", "https://api.deezer.com/track/908604612"),
            fake.requestedUrls
        )
    }

    @Test
    fun deezerRateLimitsCanBeRetried() {
        respond("""{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}""")
        assertEquals(AppError.RATE_LIMITED, (resolve("https://www.deezer.com/track/1") as Resolution.Failed).error)

        respond("""{"title":"","artist":{"name":"Someone"}}""")
        assertEquals(AppError.METADATA_UNAVAILABLE, (resolve("https://www.deezer.com/track/1") as Resolution.Failed).error)
    }

    @Test
    fun spotifyPlaylistsReadTheirSongsFromTheEmbedPageWhenItCanBeRead() {
        val page = """<meta property="og:title" content="Mix | Spotify">"""
        val embed = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[{"title":"One","subtitle":"Band"}]}}}}}}</script>"""
        fun serve(embedResponse: (Request) -> okhttp3.Response) {
            fake.handler = { request -> if (request.url.encodedPath.startsWith("/embed/")) embedResponse(request) else FakeSpotify.html(request, page) }
        }
        val link = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"

        serve { FakeSpotify.html(it, embed) }
        assertEquals(listOf(MusicMetadata("One", "Band")), (resolve(link) as Resolution.Resolved).metadata.tracks)
        assertTrue(fake.requestedUrls.contains("https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M"))

        // Without the songs, the playlist still resolves.
        serve { FakeSpotify.html(it, "", code = 404) }
        assertEquals(MusicMetadata("Mix", "", ItemType.PLAYLIST), (resolve(link) as Resolution.Resolved).metadata)
        serve { FakeSpotify.brokenBody(it) }
        assertEquals(emptyList<MusicMetadata>(), (resolve(link) as Resolution.Resolved).metadata.tracks)
        serve { FakeSpotify.html(it, """<script id="__NEXT_DATA__" type="application/json">{nope</script>""") }
        assertEquals(emptyList<MusicMetadata>(), (resolve(link) as Resolution.Resolved).metadata.tracks)
    }

    @Test
    fun newSourcesAndAlbumSongsAreReadFromTheRightPlaces() {
        respond("""{"resultCount":1,"results":[{"wrapperType":"collection","collectionName":"After Hours","artistName":"The Weeknd"}]}""")
        assertResolved(
            "https://music.apple.com/us/album/after-hours/1499378108",
            "https://itunes.apple.com/lookup?id=1499378108&country=us&entity=song",
            MusicMetadata("After Hours", "The Weeknd", ItemType.ALBUM)
        )
        respond("""<meta property="og:title" content="Road Trip">""")
        assertResolved(
            "https://music.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
            "https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
            MusicMetadata("Road Trip", "", ItemType.PLAYLIST)
        )
        respond("""{"title":"Last Last","author_name":"Burna Boy"}""")
        assertResolved(
            "https://audiomack.com/burna-boy/song/last-last",
            "https://audiomack.com/oembed?format=json&url=https%3A%2F%2Faudiomack.com%2Fburna-boy%2Fsong%2Flast-last",
            MusicMetadata("Last Last", "Burna Boy")
        )
    }
}
