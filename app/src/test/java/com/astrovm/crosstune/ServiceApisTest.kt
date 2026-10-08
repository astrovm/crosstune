package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Runs under Robolectric for the real org.json and Base64. */
@RunWith(RobolectricTestRunner::class)
class ServiceApisTest {

    private val fake = FakeSpotify()

    private fun apis(country: String = "AR") = ServiceApis(fake.client(), country, Dispatchers.Unconfined, nowSeconds = { 1_700_000_000 }, nonce = { "abc" })

    private fun url(request: Request) = request.url

    @Test
    fun tidalAsksWithItsWebTokenInTheCountrysCatalogue() {
        fake.handler = { request -> FakeSpotify.html(request, """{"items":[{"id":1,"title":"Song"}]}""") }
        assertEquals(1, runBlocking { apis().tidalSearch("tracks", "Song Band") }.size)
        val asked = fake.requestedUrls.single().toHttpUrl()
        assertEquals("/v1/search/tracks", asked.encodedPath)
        assertEquals("Song Band", asked.queryParameter("query"))
        assertEquals("AR", asked.queryParameter("countryCode"))
        assertEquals(ServiceApis.TIDAL_TOKEN, fake.requestHeaders.single { it.first == "x-tidal-token" }.second)
        // A phone with no country asks the US one.
        runBlocking { apis(country = "").tidalSearch("albums", "Album") }
        assertEquals("US", fake.requestedUrls.last().toHttpUrl().queryParameter("countryCode"))
    }

    @Test
    fun aTidalListsSongsComePageByPageWithVersionsAndCovers() {
        fun track(id: Int) = JSONObject().put("id", id).put("title", "Song $id").put("artists", JSONArray().put(JSONObject().put("name", "Band")))
            .put("album", JSONObject().put("cover", "aa-bb-cc"))
        fake.handler = { request ->
            val offset = request.url.queryParameter("offset")!!.toInt()
            val items = JSONArray((offset until minOf(offset + 100, 150)).map(::track))
            FakeSpotify.html(request, JSONObject().put("items", items).put("totalNumberOfItems", 150).toString())
        }
        val tracks = runBlocking { apis().tidalTracks(ItemType.PLAYLIST, "0f-uuid") }
        assertEquals(150, tracks.size)
        assertEquals(MusicMetadata("Song 0", "Band", artworkUrl = "https://resources.tidal.com/images/aa/bb/cc/320x320.jpg", url = "https://tidal.com/browse/track/0"), tracks.first())
        assertEquals(listOf("/v1/playlists/0f-uuid/tracks", "/v1/playlists/0f-uuid/tracks"), fake.requestedUrls.map { it.toHttpUrl().encodedPath })
        // An album's, with a song in another version and one with no name, which isn't a song.
        fake.handler = { request ->
            FakeSpotify.html(request, """{"items":[{"id":7,"title":"Song","version":"Remix","artist":{"name":"Solo"}},{"id":8,"title":""}],"totalNumberOfItems":2}""")
        }
        assertEquals(listOf(MusicMetadata("Song (Remix)", "Solo", url = "https://tidal.com/browse/track/7")), runBlocking { apis().tidalTracks(ItemType.ALBUM, "42") })
        assertTrue(fake.requestedUrls.last().contains("/v1/albums/42/tracks"))
        assertNull(ServiceApis.tidalCover("null"))
    }

    /** SoundCloud's home page, whose last script holds the key [key]. */
    private fun soundCloud(key: String = "a".repeat(32), api: (Request) -> okhttp3.Response) {
        fake.handler = { request ->
            when {
                request.url.toString() == ServiceApis.SOUNDCLOUD_URL -> FakeSpotify.html(
                    request,
                    """<script crossorigin src="https://a-v2.sndcdn.com/assets/1.js"></script><script crossorigin src="https://a-v2.sndcdn.com/assets/2.js"></script>"""
                )
                request.url.encodedPath == "/assets/2.js" -> FakeSpotify.html(request, """x={client_id:"$key"}""")
                request.url.host == "a-v2.sndcdn.com" -> FakeSpotify.html(request, "no key here")
                else -> api(request)
            }
        }
    }

    @Test
    fun soundCloudsKeyIsReadFromItsWebsiteOnceAndAgainWhenTurnedAway() {
        var refusals = 1
        soundCloud { request ->
            if (refusals > 0) {
                refusals--
                FakeSpotify.html(request, "", code = 401)
            } else {
                FakeSpotify.html(request, """{"collection":[{"title":"Song"}]}""")
            }
        }
        val apis = apis()
        assertEquals(1, runBlocking { apis.soundCloudSearch("tracks", "Song") }.size)
        // Turned away once, so the website was read twice; its last script first, the key then found.
        assertEquals(2, fake.requestedUrls.count { it == ServiceApis.SOUNDCLOUD_URL })
        assertTrue(fake.requestedUrls.none { it.endsWith("/1.js") })
        val search = fake.requestedUrls.last().toHttpUrl()
        assertEquals("a".repeat(32), search.queryParameter("client_id"))
        assertEquals("Song", search.queryParameter("q"))
        // Asked again, the key is kept.
        runBlocking { apis.soundCloudSearch("users", "Band") }
        assertEquals(2, fake.requestedUrls.count { it == ServiceApis.SOUNDCLOUD_URL })

        // Other refusals aren't the key's fault.
        soundCloud { request -> FakeSpotify.html(request, "", code = 500) }
        assertEquals(500, runCatching { runBlocking { apis().soundCloudSearch("tracks", "Song") } }.exceptionOrNull().let { (it as ServiceApis.ServiceException).code })
        // No key to be found at all.
        fake.handler = { request -> FakeSpotify.html(request, "<html></html>") }
        assertTrue(runCatching { runBlocking { apis().soundCloudSearch("tracks", "Song") } }.exceptionOrNull() is IOException)
    }

    @Test
    fun aSoundCloudSetsSongsAreAllReadInItsOrder() {
        fun track(id: Int, artist: String? = null) = JSONObject().put("id", id).put("title", "Song $id")
            .put("user", JSONObject().put("username", "uploader"))
            .put("permalink_url", "https://soundcloud.com/uploader/song-$id")
            .apply { artist?.let { put("publisher_metadata", JSONObject().put("artist", it)) } }
        val ids = (1..60).toList()
        soundCloud { request ->
            when (request.url.encodedPath) {
                // The first two in full, the rest by number.
                "/resolve" -> FakeSpotify.html(
                    request,
                    JSONObject().put("tracks", JSONArray(ids.map { if (it <= 2) track(it, "Band").put("artwork_url", "https://i1.sndcdn.com/a-large.jpg") else JSONObject().put("id", it) })).toString()
                )
                // In any order.
                else -> FakeSpotify.html(request, JSONArray(request.url.queryParameter("ids")!!.split(",").map { track(it.toInt()) }.reversed()).toString())
            }
        }
        val tracks = runBlocking { apis().soundCloudTracks("https://soundcloud.com/uploader/sets/party") }
        assertEquals(ids.map { "Song $it" }, tracks.map { it.title })
        assertEquals(MusicMetadata("Song 1", "Band", artworkUrl = "https://i1.sndcdn.com/a-t500x500.jpg", url = "https://soundcloud.com/uploader/song-1"), tracks.first())
        // Credited to no one, a song is by whoever uploaded it.
        assertEquals("uploader", tracks.last().artist)
        val batches = fake.requestedUrls.filter { it.contains("/tracks?") }.map { it.toHttpUrl().queryParameter("ids")!!.split(",").size }
        assertEquals(listOf(50, 8), batches)
        assertEquals("https://soundcloud.com/uploader/sets/party", fake.requestedUrls.first { it.contains("/resolve") }.toHttpUrl().queryParameter("url"))
        // A set that isn't one has no songs; a batch that isn't a list adds none.
        soundCloud { request ->
            if (request.url.encodedPath == "/resolve") FakeSpotify.html(request, """{"tracks":[{"id":1}]}""") else FakeSpotify.html(request, "{}")
        }
        assertEquals(emptyList<MusicMetadata>(), runBlocking { apis().soundCloudTracks("https://soundcloud.com/uploader/sets/party") })
        soundCloud { request -> FakeSpotify.html(request, "[]") }
        assertEquals(emptyList<MusicMetadata>(), runBlocking { apis().soundCloudTracks("https://soundcloud.com/uploader/sets/party") })
    }

    @Test
    fun audiomackRequestsAreSignedAsItsWebsiteSignsThem() {
        val signed = apis().signed("GET", "https://api.audiomack.com/v1/search", mapOf("q" to "lose yourself", "show" to "songs", "limit" to "10")).toMap()
        // The same signature any OAuth 1.0 library gives for these fields.
        assertEquals("dfreO/7OGDNPsk6qznX8JJ1+4P0=", signed["oauth_signature"])
        assertEquals(ServiceApis.AUDIOMACK_KEY, signed["oauth_consumer_key"])
        assertEquals("1700000000", signed["oauth_timestamp"])

        fake.handler = { request -> FakeSpotify.html(request, """{"results":[{"title":"Song"}]}""") }
        assertEquals(1, runBlocking { apis().audiomackSearch("songs", "Song") }.size)
        val asked = fake.requestedUrls.single().toHttpUrl()
        assertEquals("songs", asked.queryParameter("show"))
        assertTrue(asked.queryParameter("oauth_signature")!!.isNotEmpty())
    }

    @Test
    fun anAudiomackAlbumsSongsShareItsCoverAndUploader() {
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                """{"results":{"image":"https://i.audiomack.com/a.webp","uploader":{"url_slug":"band"},"tracks":[
                    {"title":"One","artist":"Band","url_slug":"one"},
                    {"title":"Two","artist":"Band","url_slug":"two","uploader_url_slug":"label","image":"https://i.audiomack.com/two.webp"},
                    {"title":""}
                ]}}"""
            )
        }
        assertEquals(
            listOf(
                MusicMetadata("One", "Band", artworkUrl = "https://i.audiomack.com/a.webp", url = "https://audiomack.com/band/song/one"),
                MusicMetadata("Two", "Band", artworkUrl = "https://i.audiomack.com/two.webp", url = "https://audiomack.com/label/song/two")
            ),
            runBlocking { apis().audiomackTracks(ItemType.ALBUM, "band/record") }
        )
        assertEquals("/v1/music/album/band/record", fake.requestedUrls.single().toHttpUrl().encodedPath)
        // A playlist's songs each name their own uploader; a song with no name to link to has no link.
        fake.handler = { request -> FakeSpotify.html(request, """{"results":{"tracks":[{"title":"Three","artist":"Other","uploader":{"url_slug":"other"},"url_slug":"three"},{"title":"Four","artist":"X"}]}}""") }
        val playlist = runBlocking { apis().audiomackTracks(ItemType.PLAYLIST, "dj/mix") }
        assertEquals(listOf("https://audiomack.com/other/song/three", null), playlist.map { it.url })
        assertEquals("/v1/playlist/dj/mix", fake.requestedUrls.last().toHttpUrl().encodedPath)
        // Something else than a list's answer.
        fake.handler = { request -> FakeSpotify.html(request, "[]") }
        assertTrue(runCatching { runBlocking { apis().audiomackTracks(ItemType.ALBUM, "band/record") } }.exceptionOrNull() is org.json.JSONException)
        // Nothing listed.
        fake.handler = { request -> FakeSpotify.html(request, """{"errorcode":1003}""") }
        assertEquals(emptyList<MusicMetadata>(), runBlocking { apis().audiomackTracks(ItemType.PLAYLIST, "dj/mix") })
    }
}
