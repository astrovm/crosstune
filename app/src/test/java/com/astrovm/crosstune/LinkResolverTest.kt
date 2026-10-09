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
    fun olderShazamLinksAreLookedUpOnShazamThenAppleMusic() {
        val api = "https://cdn.shazam.com/discovery/v5/en-US/US/web/-/track/20066955"
        val lookup = "https://itunes.apple.com/lookup?id=1444027955&country=us"
        fake.handler = { request ->
            when (request.url.host) {
                "cdn.shazam.com" -> FakeSpotify.html(request, """{"key":"20066955","title":"Kiss the Rain","subtitle":"Billie Myers","trackadamid":"1444027955"}""")
                else -> FakeSpotify.html(request, """{"results":[{"trackName":"Kiss the Rain","artistName":"Billie Myers"}]}""")
            }
        }
        val result = resolve("https://www.shazam.com/track/20066955/kiss-the-rain") as Resolution.Resolved
        assertEquals(MusicLink(MusicService.APPLE_MUSIC, ItemType.TRACK, "1444027955", "https://music.apple.com/us/song/1444027955", "us"), result.link)
        assertEquals(MusicMetadata("Kiss the Rain", "Billie Myers"), result.metadata)
        assertEquals(listOf(api, lookup), fake.requestedUrls)

        // A song Apple Music doesn't have is known by its name, like one Now Playing heard.
        respond("""{"title":"Demo","subtitle":"Band","images":{"coverart":"https://example.com/cover.jpg"}}""")
        val named = resolve("https://www.shazam.com/track/1/demo") as Resolution.Resolved
        assertEquals(MusicMetadata("Demo", "Band", artworkUrl = "https://example.com/cover.jpg"), named.metadata)
        assertEquals(null, named.link.service)
        assertEquals("https://www.google.com/search?q=Demo%20by%20Band", named.link.url)

        // Shazam answers an unknown key with nothing, and one with no title isn't usable.
        respond("", code = 204)
        assertEquals(Resolution.Failed(AppError.NOT_FOUND), resolve("https://www.shazam.com/track/2/x"))
        respond("""{"images":{}}""")
        assertEquals(Resolution.Failed(AppError.METADATA_UNAVAILABLE), resolve("https://www.shazam.com/track/3/x"))
        respond("", code = 503)
        assertEquals(Resolution.Failed(AppError.SERVICE_UNAVAILABLE), resolve("https://www.shazam.com/track/4/x"))
    }

    @Test
    fun googleSharedResultsAreFollowedToTheSongsTitle() {
        // It redirects to a search for the result.
        fake.handler = { request ->
            FakeSpotify.html(request, "<html>Google Search</html>", finalUrl = "https://www.google.com/search?kgmid=/g/11c2p4p6vv&hl=en&q=Iris&shem=dlvs1")
        }
        val result = resolve("Iris https://share.google/09OHXUIfQTRCqXUCJ") as Resolution.Resolved
        assertEquals(MusicMetadata("Iris", ""), result.metadata)
        assertEquals(null, result.link.service)
        assertEquals("https://www.google.com/search?q=Iris&kgmid=%2Fg%2F11c2p4p6vv", result.link.url)
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
    @Test
    fun tidalSoundCloudAndAudiomackListsComeWithTheirSongs() {
        fake.handler = { request ->
            val body = when {
                request.url.host == "tidal.com" -> "<title>Mix by DJ on TIDAL</title>"
                request.url.host == "api.tidal.com" -> """{"items":[{"id":1,"title":"Song","artists":[{"name":"Band"}]}],"totalNumberOfItems":1}"""
                request.url.encodedPath == "/oembed" -> """{"title":"Party by Friend","author_name":"Friend"}"""
                request.url.toString() == ServiceApis.SOUNDCLOUD_URL -> """<script crossorigin src="https://a-v2.sndcdn.com/assets/1.js"></script>"""
                request.url.host == "a-v2.sndcdn.com" -> """client_id:"${"k".repeat(32)}""""
                request.url.host == "api-v2.soundcloud.com" -> """{"tracks":[{"id":5,"title":"Tune","user":{"username":"Friend"}}]}"""
                else -> """{"results":{"tracks":[{"title":"Track","artist":"Rapper","url_slug":"track"}],"uploader":{"url_slug":"rapper"}}}"""
            }
            FakeSpotify.html(request, body)
        }
        val tidal = resolve("https://tidal.com/browse/playlist/36ea71a8-445e-41a4-82ab-6628c581535d") as Resolution.Resolved
        assertEquals(listOf(MusicMetadata("Song", "Band", url = "https://tidal.com/browse/track/1")), tidal.metadata.tracks)
        val set = resolve("https://soundcloud.com/friend/sets/party") as Resolution.Resolved
        assertEquals(listOf("Tune"), set.metadata.tracks.map { it.title })
        val album = resolve("https://audiomack.com/rapper/album/record") as Resolution.Resolved
        assertEquals(listOf(MusicMetadata("Track", "Rapper", url = "https://audiomack.com/rapper/song/track")), album.metadata.tracks)

        // A list whose songs can't be read still shows, without them: offline, or another answer than expected.
        fake.handler = { request ->
            when (request.url.host) {
                "tidal.com" -> FakeSpotify.html(request, "<title>Record by Band on TIDAL</title>")
                "api.tidal.com" -> FakeSpotify.html(request, "[]")
                else -> throw java.io.IOException("offline")
            }
        }
        val record = resolve("https://tidal.com/browse/album/42") as Resolution.Resolved
        assertEquals(MusicMetadata("Record", "Band", ItemType.ALBUM), record.metadata)
        fake.handler = { request ->
            if (request.url.encodedPath == "/oembed") FakeSpotify.html(request, """{"title":"Party by Friend","author_name":"Friend"}""") else throw java.io.IOException("offline")
        }
        assertEquals(emptyList<MusicMetadata>(), (resolve("https://soundcloud.com/friend/sets/party") as Resolution.Resolved).metadata.tracks)
    }
}
