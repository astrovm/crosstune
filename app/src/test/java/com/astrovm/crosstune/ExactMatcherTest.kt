package com.astrovm.crosstune

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

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
    fun prestoFindsAnAlbumByItsTitleAndArtist() = runBlocking {
        respond(prestoSearchFixture("jazz-shorter"))
        val album = MusicMetadata("Speak No Evil", "Wayne Shorter", ItemType.ALBUM)
        assertEquals("https://streaming.prestomusic.com/album/8038751", matcher().find(MusicService.PRESTO_MUSIC, album))
        val request = fake.requestedUrls.single().toHttpUrl()
        assertEquals("ajax-www.prestomusic.com", request.host)
        assertEquals("/api/app/6/explore/search", request.encodedPath)
        assertEquals("Speak No Evil Wayne Shorter", request.queryParameter("search"))
        assertEquals("50", request.queryParameter("limit"))
        assertEquals("0", request.queryParameter("offset"))
        assertEquals("recommended", request.queryParameter("sort"))
        assertEquals("album", request.queryParameter("type"))

        respond(prestoSearchFixture("classical"))
        val classical = MusicMetadata("Glenn Gould, Piano: Goldberg Variations", "Glenn Gould", ItemType.ALBUM)
        assertEquals("https://streaming.prestomusic.com/album/9590596", matcher().find(MusicService.PRESTO_MUSIC, classical))
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, classical.copy(artist = "Gould")))

        val row = """{"title":"Speak No Evil","artists":"Wayne Shorter (tenor saxophone)","prestoCode":8038751}"""
        val other = """{"title":"Speak No Evil","artists":"Flora Purim"}"""
        respond(prestoPage(row, row, other))
        assertEquals("https://streaming.prestomusic.com/album/8038751", matcher().find(MusicService.PRESTO_MUSIC, album))
    }

    private fun prestoPage(vararg rows: String): String =
        """{"status":"ok","payload":{"totalCount":${rows.size},"results":[${rows.joinToString(",")}]}}"""

    @Test
    fun prestoDoesNotTakeTheOnlyMatchOnAnIncompletePage() = runBlocking {
        respond(prestoShorterFirstPage())
        val album = MusicMetadata("Speak No Evil", "Wayne Shorter", ItemType.ALBUM)
        assertEquals(ExactMatcher.Match.Missing, matcher().lookFor(MusicService.PRESTO_MUSIC, album))
    }

    @Test
    fun prestoChecksEditionsAfterTheOldFirstTenResults() = runBlocking {
        fake.handler = { request ->
            val answer = JSONObject(prestoShorterFirstPage())
            val payload = answer.getJSONObject("payload").put("totalCount", 11)
            if (checkNotNull(request.url.queryParameter("limit")).toInt() > 10) {
                payload.getJSONArray("results").put(JSONObject("""{"title":"Speak No Evil","artists":"Wayne Shorter","prestoCode":42}"""))
            }
            FakeSpotify.html(request, answer.toString())
        }
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, MusicMetadata("Speak No Evil", "Wayne Shorter", ItemType.ALBUM)))
    }

    @Test
    fun prestoDoesNotTrustAMissingInvalidOrInconsistentTotal() = runBlocking {
        val row = """{"title":"Speak No Evil","artists":"Wayne Shorter","prestoCode":8038751}"""
        val album = MusicMetadata("Speak No Evil", "Wayne Shorter", ItemType.ALBUM)
        for (total in listOf(null, "invalid", -1, 0, 2)) {
            val payload = JSONObject().putOpt("totalCount", total).put("results", JSONArray("[$row]"))
            respond(JSONObject().put("status", "ok").put("payload", payload).toString())
            assertNull("Total $total", matcher().find(MusicService.PRESTO_MUSIC, album))
        }
        // A non-object row may be a release we can't read.
        respond(prestoPage(row, "null"))
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, album))
    }

    @Test
    fun prestoKeepsMonoAndStereoEditionsDistinct() = runBlocking {
        respond(prestoPage("""{"title":"Kind of Blue (Mono)","artists":"Miles Davis (trumpet), Bill Evans (piano)","prestoCode":8471365}"""))
        val album = MusicMetadata("Kind of Blue (Stereo)", "Miles Davis", ItemType.ALBUM)
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, album))
        val mono = album.copy(title = "KÍND OF BLUE – MONO", artist = "Miles Davis, Bill Evans")
        assertEquals("https://streaming.prestomusic.com/album/8471365", matcher().find(MusicService.PRESTO_MUSIC, mono))
    }

    @Test
    fun prestoKeepsRemasteredEditionsDistinct() = runBlocking {
        respond(prestoPage("""{"title":"Speak No Evil (1998 Remaster)","artists":"Wayne Shorter","prestoCode":8038751}"""))
        val album = MusicMetadata("Speak No Evil (2024 Remaster)", "Wayne Shorter", ItemType.ALBUM)
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, album))
    }

    @Test
    fun prestoRequiresTheLeadArtistEvenWhenConductorsShareAnEnsemble() = runBlocking {
        val album = MusicMetadata("Goldberg Variations", "Conductor A, Ensemble", ItemType.ALBUM)
        respond(prestoPage("""{"title":"Goldberg Variations","artists":"Ensemble, Conductor B (conductor)","prestoCode":1}"""))
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, album))
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, album.copy(artist = "Ensemble, Conductor A")))
        respond(prestoPage("""{"title":"Goldberg Variations","artists":"Ensemble, Conductor A (conductor)","prestoCode":1}"""))
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, album))
        respond(prestoPage("""{"title":"Goldberg Variations","artists":"Conductor A (conductor), Ensemble","prestoCode":2}"""))
        assertEquals("https://streaming.prestomusic.com/album/2", matcher().find(MusicService.PRESTO_MUSIC, album))
    }

    @Test
    fun prestoDoesNotTreatPartOfABandsNameAsItsLeadArtist() = runBlocking {
        respond(prestoPage("""{"title":"Live","artists":"Simon","prestoCode":1}"""))
        val album = MusicMetadata("Live", "Simon & Garfunkel", ItemType.ALBUM)
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, album))
    }

    @Test
    fun prestoDoesNotOpenAlbumsWithGenericArtistCredits() = runBlocking {
        for (credit in listOf("Various Artists", "VARIOUS–ARTISTS", "V/A", "Artistes variés", "ヴァリアス・アーティスト", "<unknown>", "", "♪", "\u0301")) {
            respond(prestoPage("""{"title":"Best of Jazz","artists":"$credit","prestoCode":1}"""))
            val album = MusicMetadata("Best of Jazz", credit, ItemType.ALBUM)
            assertNull(credit, matcher().find(MusicService.PRESTO_MUSIC, album))
        }
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun prestoDoesNotOpenAlbumsWithAGenericPrestoLead() = runBlocking {
        respond(prestoPage("""{"title":"Best of Jazz","artists":"Artistes variés","prestoCode":1}"""))
        val album = MusicMetadata("Best of Jazz", "Artistes varies", ItemType.ALBUM)
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, album))
    }

    @Test
    fun prestoDoesNotMatchATitleWithNoLettersOrDigits() = runBlocking {
        respond(prestoPage("""{"title":".","artists":"Wayne Shorter","prestoCode":1}"""))
        val album = MusicMetadata("!", "Wayne Shorter", ItemType.ALBUM)
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, album))
    }

    @Test
    fun prestoDoesNotDiscardMatchingRowsWithUnusableCodes() = runBlocking {
        val valid = """{"title":"Speak No Evil","artists":"Wayne Shorter","prestoCode":8038751}"""
        val album = MusicMetadata("Speak No Evil", "Wayne Shorter", ItemType.ALBUM)
        for (code in listOf(null, JSONObject.NULL, "", "not-an-id", 0)) {
            val matching = JSONObject("""{"title":"Speak No Evil","artists":"Wayne Shorter"}""").putOpt("prestoCode", code)
            respond(prestoPage(valid, matching.toString()))
            assertNull("Code $code", matcher().find(MusicService.PRESTO_MUSIC, album))
        }
    }

    @Test
    fun prestoLeavesAmbiguousOrMissingAlbumsToSearch() = runBlocking {
        respond(prestoSearchFixture("jazz"))
        val album = MusicMetadata("Kind of Blue", "Miles Davis", ItemType.ALBUM)
        assertEquals(ExactMatcher.Match.Missing, matcher().lookFor(MusicService.PRESTO_MUSIC, album))
        assertEquals(1, fake.requestedUrls.size)
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, album.copy(artist = "John Coltrane")))

        respond(prestoSearchFixture("pop"))
        val pop = MusicMetadata("1989", "Taylor Swift", ItemType.ALBUM)
        assertEquals(ExactMatcher.Match.Missing, matcher().lookFor(MusicService.PRESTO_MUSIC, pop))
        assertFalse(canBeMissing(MusicService.PRESTO_MUSIC, album))

        respond(prestoPage(
            """{"title":"1989","artists":"Taylor Swift","prestoCode":"not-an-id"}""",
            """{"title":"1989","artists":"Taylor Swift","prestoCode":0}""",
            """{"title":null,"artists":"Taylor Swift","prestoCode":1}""",
            """{"title":"1989","artists":null,"prestoCode":2}"""
        ))
        assertEquals(ExactMatcher.Match.Missing, matcher().lookFor(MusicService.PRESTO_MUSIC, pop))

        // Beside a row that can't be read, one that matches may not be the only release.
        respond(prestoPage(
            """{"title":"1989","artists":"Taylor Swift","prestoCode":7}""",
            """{"title":null,"artists":"Taylor Swift","prestoCode":8}"""
        ))
        assertEquals(ExactMatcher.Match.Missing, matcher().lookFor(MusicService.PRESTO_MUSIC, pop))
        respond(prestoPage(
            """{"title":"1989","artists":"Taylor Swift","prestoCode":7}""",
            """{"title":"1989","artists":null,"prestoCode":8}"""
        ))
        assertEquals(ExactMatcher.Match.Missing, matcher().lookFor(MusicService.PRESTO_MUSIC, pop))
        respond(prestoPage("""{"title":"1989","artists":"Taylor Swift","prestoCode":7}"""))
        assertNotEquals(ExactMatcher.Match.Missing, matcher().lookFor(MusicService.PRESTO_MUSIC, pop))

        // A code of 0 is no album, and credits that are only what someone plays name no lead.
        respond(prestoPage("""{"title":"1989","artists":"Taylor Swift","prestoCode":0}"""))
        assertEquals(ExactMatcher.Match.Missing, matcher().lookFor(MusicService.PRESTO_MUSIC, pop))
        respond(prestoPage("""{"title":"1989","artists":"(vocals)","prestoCode":7}"""))
        assertEquals(ExactMatcher.Match.Missing, matcher().lookFor(MusicService.PRESTO_MUSIC, pop))
    }

    @Test
    fun prestoDistinguishesAnOutageFromNoAlbum() = runBlocking {
        val album = MusicMetadata("1989", "Taylor Swift", ItemType.ALBUM)
        respond("""{"status":"error","code":54}""")
        assertEquals(ExactMatcher.Match.Failed, matcher().lookFor(MusicService.PRESTO_MUSIC, album))
        respond("""{"status":"ok","payload":{}}""")
        assertEquals(ExactMatcher.Match.Failed, matcher().lookFor(MusicService.PRESTO_MUSIC, album))
        respond("not JSON")
        assertEquals(ExactMatcher.Match.Failed, matcher().lookFor(MusicService.PRESTO_MUSIC, album))
    }

    @Test
    fun prestoDoesNotLookUpSongsEvenWhenTheirAlbumIsKnown() = runBlocking {
        val track = MusicMetadata("Witch Hunt", "Wayne Shorter", album = "Speak No Evil")
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, track))
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, track.copy(type = ItemType.ARTIST)))
        assertNull(matcher().find(MusicService.PRESTO_MUSIC, track.copy(type = ItemType.PLAYLIST)))
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun radioCatalogReportsOnlyPositiveLengthsWithinIntRange() = runBlocking {
        val track = MusicMetadata("Radio Song", "Band")
        val matcher = matcher()
        val longest = Int.MAX_VALUE.toLong()
        for ((length, expected) in listOf(0L to null, 1L to 1L, longest to longest, (longest + 1) to null)) {
            respond("""{"results":[{"trackName":"Radio Song","artistName":"Band",
                "trackViewUrl":"https://music.apple.com/us/album/fixture/123?i=456","trackTimeMillis":$length}]}""")
            assertEquals("Catalog length $length", ExactMatcher.AppleMusicMatch("https://music.apple.com/us/album/fixture/123?i=456", expected),
                matcher.findRadioSong(track))
        }
    }

    @Test
    fun youTubeMusicTellsSongsAndMusicVideosFromOtherVideos() {
        fun type(type: String) = """{"contents":{"x":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_$type"}}}}"""
        respond(type("OMV"))
        assertEquals(true, runBlocking { matcher().isMusicVideo("4NRXx6U8ABQ") })
        assertTrue(fake.requestedUrls.single().startsWith("https://music.youtube.com/youtubei/v1/next"))
        assertTrue(fake.requestBodies.single().contains("\"videoId\":\"4NRXx6U8ABQ\""))
        respond(type("ATV"))
        assertEquals(true, runBlocking { matcher().isMusicVideo("4NRXx6U8ABQ") })
        // Someone's own upload is music when they put it under Music, like a concert they filmed.
        var category: String? = "Music"
        fake.handler = { request ->
            if (request.url.host == "www.youtube.com") {
                FakeSpotify.html(request, category?.let { """{"microformat":{"playerMicroformatRenderer":{"category":"$it"}}}""" } ?: throw IOException("offline"))
            } else {
                FakeSpotify.html(request, type("UGC"))
            }
        }
        assertEquals(true, runBlocking { matcher().isMusicVideo("b0T7GPQjJ7w") })
        assertTrue(fake.requestedUrls.last().startsWith("https://www.youtube.com/youtubei/v1/player"))
        assertTrue(fake.requestBodies.last().contains("\"videoId\":\"b0T7GPQjJ7w\""))
        // A tutorial, or a vlog, isn't.
        category = "Howto & Style"
        assertEquals(false, runBlocking { matcher().isMusicVideo("HXJx8j7JpKY") })
        category = null
        assertNull(runBlocking { matcher().isMusicVideo("HXJx8j7JpKY") })
        respond(type("UGC"))
        assertEquals(false, runBlocking { matcher().isMusicVideo("jNQXAC9IVRw") })

        // Nothing to tell from, or no answer: unknown.
        respond("{}")
        assertNull(runBlocking { matcher().isMusicVideo("jNQXAC9IVRw") })
        respond("not json")
        assertNull(runBlocking { matcher().isMusicVideo("jNQXAC9IVRw") })
        fake.handler = { throw IOException("offline") }
        assertNull(runBlocking { matcher().isMusicVideo("jNQXAC9IVRw") })
    }

    @Test
    fun aCoverIsNeverAnotherArtistsWhoseNameStartsTheSame() {
        // Deezer's answer for Hello by Adele has this before any of hers.
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                if (request.url.host == "itunes.apple.com") {
                    """{"results":[{"trackName":"Hello (Dub up Mix)","artistName":"Adele Harley","artworkUrl100":"https://harley/100x100bb.jpg"},{"trackName":"Hello","artistName":"Adele","artworkUrl100":"https://adele/100x100bb.jpg"}]}"""
                } else {
                    """{"data":[{"title":"Hello (Dub up Mix)","artist":{"name":"Adele Harley"},"album":{"cover_big":"https://harley.jpg"}}]}"""
                }
            )
        }
        assertEquals("https://adele/600x600bb.jpg", runBlocking { matcher().cover(MusicMetadata("Hello", "Adele")) })
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                """{"data":[{"title":"Hello (Dub up Mix)","artist":{"name":"Adele Harley"},"album":{"cover_big":"https://harley.jpg"}},{"title":"Hello","artist":{"name":"Adele"},"album":{"cover_big":"https://adele.jpg"}}]}"""
            )
        }
        assertEquals("https://adele.jpg", runBlocking { matcher().cover(MusicMetadata("Hello", "Adele")) })
    }

    @Test
    fun aTitleOfNoLettersOrDigitsGetsNoCover() {
        // Such a title starts any name, so any song of the artist's would do, with the wrong cover.
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                if (request.url.host == "itunes.apple.com") {
                    """{"results":[{"trackName":"Hello","artistName":"Adele","artworkUrl100":"https://adele/100x100bb.jpg"}]}"""
                } else {
                    """{"data":[{"title":"Hello","artist":{"name":"Adele"},"album":{"cover_big":"https://adele.jpg"}}]}"""
                }
            )
        }
        assertNull(runBlocking { matcher().cover(MusicMetadata("!\u0021!", "Adele")) })
    }

    @Test
    fun aSongDeezerDoesntHaveGetsApplesCover() {
        val cover = "https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg"
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                if (request.url.host == "itunes.apple.com") {
                    """{"results":[{"trackName":"Owaranai Uta","artistName":"Kukki","artworkUrl100":"https://other.jpg"},{"trackName":"Owaranai Uta","artistName":"THE BLUE HEARTS","artworkUrl100":"$cover"}]}"""
                } else {
                    """{"data":[{"title":"終わらない歌","artist":{"name":"Kukki"},"album":{"cover_big":"https://kukki.jpg"}}]}"""
                }
            )
        }
        // As large as Apple makes them.
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/600x600bb.jpg", runBlocking { matcher().cover(MusicMetadata("Owaranai Uta", "The Blue Hearts")) })
        assertTrue(fake.requestedUrls.last(), fake.requestedUrls.last().contains("country=AR"))
        // Neither having it, or a cover with no picture, is none.
        assertNull(runBlocking { matcher().cover(MusicMetadata("Linda Linda", "The Blue Hearts")) })
        fake.handler = { request -> FakeSpotify.html(request, if (request.url.host == "itunes.apple.com") """{"results":[{"trackName":"Linda Linda","artistName":"THE BLUE HEARTS","artworkUrl100":""}]}""" else "{}") }
        assertNull(runBlocking { matcher().cover(MusicMetadata("Linda Linda", "The Blue Hearts")) })
        assertNull(runBlocking { matcher("").cover(MusicMetadata("Linda Linda", "The Blue Hearts")) })
    }

    @Test
    fun aNowPlayingSongGetsTheCoverOfTheClosestDeezerSong() {
        val cover = "https://cdn-images.dzcdn.net/images/cover/abc/500x500-000000-80-0-0.jpg"
        respond(
            """{"data":[
                {"title":"Other Song","artist":{"name":"The Hollies"},"album":{"cover_big":"https://cdn/other.jpg"}},
                {"title":"Long Cool Woman in a Black Dress","artist":{"name":"A Cover Band"},"album":{"cover_big":"https://cdn/cover-band.jpg"}},
                {"title":"Long Cool Woman (In a Black Dress) (2003 Remaster)","artist":{"name":"The Hollies"},"album":{"cover_big":"$cover"}}
            ]}"""
        )
        val song = MusicMetadata("Long Cool Woman in a Black Dress", "Hollies")
        assertEquals(cover, runBlocking { matcher().cover(song) })
        assertTrue(fake.requestedUrls.single().startsWith("https://api.deezer.com/search/track?q=Long%20Cool%20Woman"))

        // No close song, no answer, or offline: no cover.
        respond("""{"data":[{"title":"Long Cool Woman","artist":{"name":"The Hollies"},"album":{"cover_big":"$cover"}}]}""")
        assertNull(runBlocking { matcher().cover(song) })
        respond("""{"data":[{"title":"Long Cool Woman in a Black Dress","artist":{"name":"The Hollies"},"album":{"cover_big":""}}]}""")
        assertNull(runBlocking { matcher().cover(song) })
        respond("not json")
        assertNull(runBlocking { matcher().cover(song) })
        fake.handler = { throw IOException("offline") }
        assertNull(runBlocking { matcher().cover(song) })
        assertNull(runBlocking { matcher().cover(MusicMetadata("!\u0021!", "Hollies")) })
    }

    @Test
    fun aRemasterGetsTheCoverOfTheSongDeezerKnowsWithoutTheTag() {
        val cover = "https://cdn-images.dzcdn.net/images/cover/9e87/500x500.jpg"
        // Deezer finds nothing when the tag is asked for, as YouTube Music names the song.
        fake.handler = { request ->
            val query = request.url.queryParameter("q").orEmpty()
            FakeSpotify.html(
                request,
                if ("Remaster" in query) """{"data":[]}"""
                else """{"data":[{"title":"Asphalt Lady (2018 Remix)","artist":{"name":"S.Kiyotaka & Omega Tribe"},"album":{"cover_big":"$cover"}}]}"""
            )
        }
        assertEquals(cover, runBlocking { matcher().cover(MusicMetadata("ASPHALT LADY (2018 Remix) (2024 Remaster)", "S.Kiyotaka & Omega Tribe")) })
        assertEquals("ASPHALT LADY (2018 Remix) S.Kiyotaka & Omega Tribe", fake.requestedUrls.single().toHttpUrl().queryParameter("q"))
    }

    @Test
    fun aTitleThatsTwoNamesGetsTheCoverOfEither() {
        val cover = "https://cdn-images.dzcdn.net/images/cover/mystery/500x500.jpg"
        // Deezer only knows it by its English name.
        fake.handler = { request ->
            val query = request.url.queryParameter("q").orEmpty()
            FakeSpotify.html(
                request,
                if (query.startsWith("Mystery Girl")) """{"data":[{"title":"Mystery Girl","artist":{"name":"I REIN FOR REIN"},"album":{"cover_big":"$cover"}}]}""" else """{"data":[]}"""
            )
        }
        assertEquals(cover, runBlocking { matcher().cover(MusicMetadata("ミステリー・ガール - Mystery Girl", "I Re'in For Re'in")) })
        // The whole title first, then each name in turn.
        assertEquals(3, fake.requestedUrls.size)
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
    fun anEditNamedJustLikeTheSongOpensOnlyWhenNoneIsAsLong() {
        // An edit of 2:54 first, then the song itself, 4:22, as each app has them, by name alone.
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                when (request.url.host) {
                    "itunes.apple.com" -> """{"results":[
                        {"trackName":"Love Triangle","artistName":"Band","trackViewUrl":"https://music.apple.com/edit","trackTimeMillis":174000},
                        {"trackName":"Love Triangle","artistName":"Band","trackViewUrl":"https://music.apple.com/album"},
                        {"trackName":"Love Triangle","artistName":"Band","trackViewUrl":"https://music.apple.com/song","trackTimeMillis":261987}]}"""
                    "api.deezer.com" -> """{"data":[
                        {"title":"Love Triangle","artist":{"name":"Band"},"link":"https://www.deezer.com/track/1","duration":174},
                        {"title":"Love Triangle","artist":{"name":"Band"},"link":"https://www.deezer.com/track/2","duration":263}]}"""
                    "api.tidal.com" -> """{"items":[{"id":1,"title":"Love Triangle","artists":[{"name":"Band"}],"duration":174},{"id":2,"title":"Love Triangle","artists":[{"name":"Band"}],"duration":270}]}"""
                    else -> youTubeMusicPage(
                        youTubeMusicRow("Love Triangle", "Band • Singles • 2:54", videoId = "edit0000000"),
                        youTubeMusicRow("Love Triangle", "Band • Album • 4:22", videoId = "song0000000")
                    )
                }
            )
        }
        val song = MusicMetadata("Love Triangle", "Band", lengthMs = 262_000)
        assertEquals("https://music.apple.com/song", find(MusicService.APPLE_MUSIC, song))
        assertEquals("https://www.deezer.com/track/2", find(MusicService.DEEZER, song))
        // Up to 8 seconds apart is the same, e.g. remastered.
        assertEquals("https://tidal.com/browse/track/2", find(MusicService.TIDAL, song))
        assertEquals("https://music.youtube.com/watch?v=song0000000", find(MusicService.YOUTUBE_MUSIC, song))
        // In a queue too, which takes a song by a looser name.
        assertEquals("https://music.youtube.com/watch?v=song0000000", runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(song.copy(title = "Love Triangle (Remastered)"))) {} }?.url)
        // Its length not known, or none as long, the first is as good as any.
        listOf(song.copy(lengthMs = null), song.copy(lengthMs = 360_000), song.copy(lengthMs = 250_000)).forEach { other ->
            assertEquals("https://music.apple.com/edit", find(MusicService.APPLE_MUSIC, other))
            assertEquals("https://www.deezer.com/track/1", find(MusicService.DEEZER, other))
            assertEquals("https://tidal.com/browse/track/1", find(MusicService.TIDAL, other))
            assertEquals("https://music.youtube.com/watch?v=edit0000000", find(MusicService.YOUTUBE_MUSIC, other))
        }
        assertEquals("https://music.youtube.com/watch?v=edit0000000", find(MusicService.YOUTUBE_MUSIC, song.copy(lengthMs = 174_000)))
    }

    @Test
    fun whatWasFoundForOneEditIsntRememberedForAnother() = runBlocking {
        val file = kotlin.io.path.createTempDirectory("lookups").toFile().resolve("lookups.json")
        val matcher = ExactMatcher(fake.client(), "AR", kotlinx.coroutines.Dispatchers.Unconfined, LookupCache(file, kotlinx.coroutines.Dispatchers.Unconfined))
        respond("""{"data":[{"title":"Song","artist":{"name":"Band"},"link":"https://www.deezer.com/track/1","duration":174},{"title":"Song","artist":{"name":"Band"},"link":"https://www.deezer.com/track/2","duration":263}]}""")
        assertEquals("https://www.deezer.com/track/1", matcher.find(MusicService.DEEZER, MusicMetadata("Song", "Band", lengthMs = 174_000)))
        assertEquals("https://www.deezer.com/track/2", matcher.find(MusicService.DEEZER, MusicMetadata("Song", "Band", lengthMs = 263_000)))
        // Each is remembered as it was found.
        fake.handler = { throw IOException("offline") }
        assertEquals("https://www.deezer.com/track/1", matcher.find(MusicService.DEEZER, MusicMetadata("Song", "Band", lengthMs = 174_000)))
        assertEquals("https://www.deezer.com/track/2", matcher.find(MusicService.DEEZER, MusicMetadata("Song", "Band", lengthMs = 263_000)))
    }

    @Test
    fun whatWasFoundBeforeTheMatchingChangedIsLookedForAgain() = runBlocking {
        val file = kotlin.io.path.createTempDirectory("lookups").toFile().resolve("lookups.json")
        val cache = LookupCache(file, kotlinx.coroutines.Dispatchers.Unconfined)
        // Found the way songs were matched before, and kept under the key it had then.
        cache.put("find|DEEZER|TRACK|Song|Band|", "https://www.deezer.com/track/old")
        respond("""{"data":[{"title":"Song","artist":{"name":"Band"},"link":"https://www.deezer.com/track/1"}]}""")
        val matcher = ExactMatcher(fake.client(), "AR", kotlinx.coroutines.Dispatchers.Unconfined, cache)
        assertEquals("https://www.deezer.com/track/1", matcher.find(MusicService.DEEZER, MusicMetadata("Song", "Band")))
    }

    @Test
    fun aWrongAccentedLatinMatchKeptByVersionFiveIsLookedForAgain() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("lookups").toFile()
        try {
            val cache = LookupCache(directory.resolve("lookups.json"), Dispatchers.Unconfined)
            val track = MusicMetadata("Fake Song", "Fake Singer", lengthMs = 245_000)
            val wrong = "wrong000000"
            cache.put("find@5|YOUTUBE_MUSIC|TRACK|Fake Song|Fake Singer||245000", "https://music.youtube.com/watch?v=$wrong")
            cache.put("find@7|YOUTUBE_MUSIC|TRACK|Fake Song|Fake Singer||245000", "https://music.youtube.com/watch?v=$wrong")
            cache.save()
            namesAndTabs(
                songs = listOf(youTubeMusicRow("Canción Falsa", "Fake Singer • Otro Álbum • 4:06", videoId = wrong)),
                apple = mapOf("AR" to appleSong(track.title, track.artist, lengthMs = 245_000))
            )
            val matcher = ExactMatcher(fake.client(), "AR", Dispatchers.Unconfined, LookupCache(directory.resolve("lookups.json"), Dispatchers.Unconfined))
            assertNull(matcher.find(MusicService.YOUTUBE_MUSIC, track))
            assertTrue(fake.requestedUrls.any { it.contains("music.youtube.com/youtubei/v1/search") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aRerecordingKeptByVersionNineIsLookedForAgain() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("lookups").toFile()
        try {
            val cache = LookupCache(directory.resolve("lookups.json"), Dispatchers.Unconfined)
            cache.put("find@9|YOUTUBE_MUSIC|TRACK|Song|Band|", "https://music.youtube.com/watch?v=rerecorded0")
            cache.save()
            namesAndTabs(
                songs = listOf(youTubeMusicRow("Song", "Band • Album • 4:00", videoId = "original000")),
                apple = mapOf("AR" to appleSong("Song", "Band", 240_000))
            )
            val matcher = ExactMatcher(fake.client(), "AR", Dispatchers.Unconfined, LookupCache(directory.resolve("lookups.json"), Dispatchers.Unconfined))
            assertEquals("https://music.youtube.com/watch?v=original000", matcher.find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Song", "Band")))
        } finally {
            directory.deleteRecursively()
        }
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

        // A title alone, e.g. from a Google link that names only the song, could be anyone's song of
        // that name, so it's searched for instead.
        respond("""{"data":[{"title":"Beyoncé Song","artist":{"name":"Anyone"},"link":"https://www.deezer.com/track/9"}]}""")
        assertNull(runBlocking { matcher().find(MusicService.DEEZER, song.copy(artist = "")) })
        // An artist is still found by name alone.
        respond("""{"data":[{"name":"Beyoncé Song","link":"https://www.deezer.com/artist/9"}]}""")
        assertEquals("https://www.deezer.com/artist/9", runBlocking { matcher().find(MusicService.DEEZER, MusicMetadata("Beyoncé Song", "", ItemType.ARTIST)) })
    }

    @Test
    fun failuresAndUnsupportedCasesReturnNullWithoutCrashing() {
        respond("not json")
        assertNull(runBlocking { matcher().find(MusicService.DEEZER, song) })

        fake.handler = { throw IOException("offline") }
        assertNull(runBlocking { matcher().find(MusicService.APPLE_MUSIC, song) })

        fake.requestedUrls.clear()
        // Spotify's songs are found, but not its albums, which nothing lists without an account.
        assertNull(runBlocking { matcher().find(MusicService.SPOTIFY, song.copy(type = ItemType.ALBUM)) })
        // A playlist with its songs known is matched song by song, never as itself.
        assertNull(runBlocking { matcher().find(MusicService.APPLE_MUSIC, song.copy(type = ItemType.PLAYLIST, tracks = listOf(song))) })
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

    @Test
    fun bandcampMatchesTracksAndAlbums() {
        respond(
            """{"auto":{"results":[
                {"type":"t","name":"Beyoncé Song (Cover)","band_name":"Other","item_url_root":"https://other.bandcamp.com","item_url_path":"https://other.bandcamp.com/track/cover"},
                {"type":"t","name":"Beyonce Song","band_name":"Carly Rae Jepsen","item_url_root":"https://carlyraejepsen.bandcamp.com","item_url_path":"https://carlyraejepsen.bandcamp.com/track/beyonce-song"}
            ]}}"""
        )
        assertEquals("https://carlyraejepsen.bandcamp.com/track/beyonce-song", runBlocking { matcher().find(MusicService.BANDCAMP, song) })
        assertEquals("https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic", fake.requestedUrls.last())
        assertTrue(fake.requestBodies.last(), fake.requestBodies.last().contains("\"search_filter\":\"t\""))

        respond("""{"auto":{"results":[{"type":"a","name":"After Hours","band_name":"The Weeknd","item_url_root":"https://theweeknd.bandcamp.com","item_url_path":"https://theweeknd.bandcamp.com/album/after-hours"}]}}""")
        val album = MusicMetadata("After Hours", "The Weeknd", ItemType.ALBUM)
        assertEquals("https://theweeknd.bandcamp.com/album/after-hours", runBlocking { matcher().find(MusicService.BANDCAMP, album) })
        assertTrue(fake.requestBodies.last().contains("\"search_filter\":\"a\""))

        // Artists would only be matched by name, which anyone can register a page for: always a search.
        fake.requestedUrls.clear()
        val artist = MusicMetadata("The Weeknd", "", ItemType.ARTIST)
        assertNull(runBlocking { matcher().find(MusicService.BANDCAMP, artist) })
        assertTrue(fake.requestedUrls.isEmpty())

        respond("""{"auto":{"results":[{"type":"t","name":"Beyoncé Song","band_name":"Someone Else","item_url_root":"https://someoneelse.bandcamp.com","item_url_path":"https://x"}]}}""")
        assertNull(runBlocking { matcher().find(MusicService.BANDCAMP, song) })
    }

    @Test
    fun bandcampFallsBackToSearchWhenTheSourceHasNoArtist() {
        respond(
            """{"auto":{"results":[{"type":"t","name":"Beyoncé Song","band_name":"Fan","item_url_root":"https://fan.bandcamp.com","item_url_path":"https://fan.bandcamp.com/track/beyonce-song"}]}}"""
        )
        assertNull(runBlocking { matcher().find(MusicService.BANDCAMP, song.copy(artist = "")) })
    }

    @Test
    fun bandcampPageNamesMustBeTheWholeArtistName() {
        val chandelier = MusicMetadata("Chandelier", "Sia")
        fun page(host: String) =
            """{"auto":{"results":[{"type":"t","name":"Chandelier","band_name":"Sia","item_url_root":"https://$host.bandcamp.com","item_url_path":"https://$host.bandcamp.com/track/chandelier"}]}}"""

        respond(page("asia"))
        assertNull(runBlocking { matcher().find(MusicService.BANDCAMP, chandelier) })
        respond(page("siamusic"))
        assertEquals("https://siamusic.bandcamp.com/track/chandelier", runBlocking { matcher().find(MusicService.BANDCAMP, chandelier) })
        respond(page("sia"))
        assertEquals("https://sia.bandcamp.com/track/chandelier", runBlocking { matcher().find(MusicService.BANDCAMP, chandelier) })

        // A leading "The" is often left out of the page name.
        fun weeknd(host: String) =
            """{"auto":{"results":[{"type":"t","name":"Starboy","band_name":"The Weeknd","item_url_root":"https://$host.bandcamp.com","item_url_path":"https://$host.bandcamp.com/track/starboy"}]}}"""
        respond(weeknd("weeknd"))
        assertEquals("https://weeknd.bandcamp.com/track/starboy", runBlocking { matcher().find(MusicService.BANDCAMP, MusicMetadata("Starboy", "The Weeknd")) })

        // ...but only as a word of its own: "Thelonious" doesn't start with an article.
        respond(
            """{"auto":{"results":[{"type":"t","name":"Round Midnight","band_name":"Thelonious Monk","item_url_root":"https://loniousmonk.bandcamp.com","item_url_path":"https://loniousmonk.bandcamp.com/track/round-midnight"}]}}"""
        )
        assertNull(runBlocking { matcher().find(MusicService.BANDCAMP, MusicMetadata("Round Midnight", "Thelonious Monk")) })
    }

    @Test
    fun bandcampIgnoresUploadsCreditedToTheArtistFromSomeoneElsesPage() {
        respond(
            """{"auto":{"results":[
                {"type":"t","name":"Beyoncé Song","band_name":"Carly Rae Jepsen","item_url_root":"https://fanuploader.bandcamp.com","item_url_path":"https://fanuploader.bandcamp.com/track/beyonce-song"}
            ]}}"""
        )
        assertNull(runBlocking { matcher().find(MusicService.BANDCAMP, song) })
    }

    private fun youTubeMusicRow(name: String, details: String, videoId: String? = null, browseId: String? = null): String {
        fun column(text: String) =
            """{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$text"}]}}}"""
        val identity = when {
            videoId != null -> """"playlistItemData":{"videoId":"$videoId"}"""
            else -> """"navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId"}}"""
        }
        return """{"musicResponsiveListItemRenderer":{"flexColumns":[${column(name)},${column(details)}],$identity}}"""
    }

    /** The rows are nested a few levels deep, in sections. */
    private fun youTubeMusicPage(vararg rows: String) =
        """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"contents":[${rows.joinToString(",")}]}}]}}}}]}}}"""

    @Test
    fun aLocalizedCreditOnlyProvesTheSameSongId() {
        val song = MusicMetadata("Lemon", "米津玄師", lengthMs = 256_000)
        val original = localizedRow("Kenshi Yonezu", "original000")
        val right = localizedRow(song.artist, "original000")
        val wrongId = localizedRow(song.artist, "other000000")
        val unplayable = localizedRow(song.artist, null)
        val wrongCredit = localizedRow("Other Artist", "original000")
        val wrongArtistId = localizedRow(song.artist, "original000", artistId = "UCother")
        val wrongTitle = localizedRow(song.artist, "original000", title = "Another Song")
        val karaoke = localizedRow(song.artist, "original000", album = "Lemon (Karaoke)")
        val rerecorded = localizedRow(song.artist, "original000", album = "From THE FIRST TAKE")
        val wrongLength = localizedRow(song.artist, "original000", time = "4:20")
        val unproved = listOf(wrongId, wrongCredit, wrongArtistId, wrongTitle, karaoke, rerecorded, wrongLength, unplayable)
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, listOf(original), mapOf("ja" to unproved)))
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, listOf(unplayable), mapOf("ja" to listOf(right))))
        // Japanese can miss the credit when an artist's name is Han. Try Traditional Chinese too.
        assertEquals(localizedFound("original000"), localizedMatch(song, listOf(original), mapOf("ja" to listOf(wrongId), "zh-TW" to listOf(right))))
        // When both IDs are credited, the search order cannot decide between two recordings.
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, listOf(original, localizedRow("Kenshi Yonezu", "other000000")), mapOf("ja" to listOf(right, wrongId))))
    }

    @Test
    fun localizedCreditsCanKeepBothNames() {
        val original = listOf(localizedRow("Band", "original000"))
        for ((language, artist, credit) in listOf(
            Triple("ja", "バンド", "バンド (Band)"),
            Triple("ko", "밴드", "Band (밴드)")
        )) {
            val localized = mapOf(language to listOf(localizedRow(credit, "original000")))
            assertEquals(language, localizedFound("original000"), localizedMatch(MusicMetadata("Lemon", artist), original, localized))
        }
    }

    @Test
    fun aLocalizedCreditDoesNotMakeOneHalfOfARemixTitleTheSong() {
        val song = MusicMetadata("First Love", "宇多田ヒカル", lengthMs = 260_000)
        fun mix(credit: String) = localizedRow(credit, "t21dyImwINM", "SCIENCE FICTION", "4:23", title = "First Love - 2022 Mix")
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, listOf(mix("Hikaru Utada")), mapOf("ja" to listOf(mix(song.artist)))))
    }

    @Test
    fun aSecondLocalizedArtistIsNotAnAliasForTheFirst() {
        val song = MusicMetadata("Lemon", "米津玄師", lengthMs = 257_000)
        val original = localizedRow("洛天依", "xTri2AikFEw", time = "4:20")
        val separate = localizedRow("洛天依", "xTri2AikFEw", time = "4:20", alsoBy = song.artist)
        val flattened = localizedRow("洛天依、米津玄師", "xTri2AikFEw", time = "4:20")
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, listOf(original), mapOf("ja" to listOf(separate, flattened))))
        val native = localizedRow(song.artist, "xTri2AikFEw", time = "4:20")
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, listOf(separate), mapOf("ja" to listOf(native))))
    }

    @Test
    fun aMissingArtistLinkCannotProveALocalizedCredit() {
        val song = MusicMetadata("Lemon", "米津玄師")
        val original = localizedRow("Kenshi Yonezu", "original000")
        val linked = localizedRow(song.artist, "original000")
        val withoutCreditColumn = JSONObject(linked).also {
            it.getJSONObject("musicResponsiveListItemRenderer").getJSONArray("flexColumns").remove(1)
        }.toString()
        // Names in incomplete service replies are not evidence of one linked artist.
        val incomplete = listOf(
            withoutCreditColumn,
            linked.replace("\"flexColumns\"", "\"missingColumns\""),
            linked.replace("\"musicResponsiveListItemFlexColumnRenderer\"", "\"missingColumn\""),
            linked.replace("\"text\":{", "\"missingText\":{"),
            linked.replace("\"navigationEndpoint\"", "\"missingNavigation\""),
            linked.replace("\"browseEndpoint\"", "\"missingBrowse\""),
            localizedRow(song.artist, "original000", artistId = "")
        )
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, listOf(original), mapOf("ja" to incomplete)))
    }

    @Test
    fun aLocalizedCreditDoesNotOverrideALengthAppleStillKnows() {
        val song = MusicMetadata("Lemon", "米津玄師")
        val original = listOf(localizedRow("Kenshi Yonezu", "original000"))
        val local = mapOf("ja" to listOf(localizedRow(song.artist, "original000")))
        val different = appleSong(song.title, song.artist, 280_000)
        val matching = appleSong(song.title, song.artist, 257_000)
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, original, local, apple = different))
        assertEquals(localizedFound("original000"), localizedMatch(song, original, local, apple = matching))
    }

    @Test
    fun aStalledLocalizedSearchDoesNotLoseThePicker() {
        val release = CountDownLatch(1)
        val started = AtomicBoolean(false)
        val original = listOf(localizedRow("Kenshi Yonezu", "original000"))
        val withPicker = ExactMatcher(fake.client(), "AR", pick = { _, _, _ -> 0 }, timeoutMs = 2_000)
        try {
            assertEquals(localizedFound("original000"), localizedMatch(MusicMetadata("Lemon", "米津玄師"), original, emptyMap(), withPicker) {
                started.set(true)
                release.await(10, SECONDS)
            })
            assertTrue(started.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun localizationOnlyUsesTheTimeLeftAfterTheFirstSearches() {
        val clock = TestTimeSource()
        val matcher = ExactMatcher(fake.client(), "AR", lookupTime = clock)
        val song = MusicMetadata("Lemon", "米津玄師")
        val original = listOf(localizedRow("Kenshi Yonezu", "original000"))
        val local = mapOf("ja" to listOf(localizedRow(song.artist, "original000")))
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, original, local, matcher, onVideos = {
            clock += ExactMatcher.TIMEOUT_MS.milliseconds
        }))
    }

    @Test
    fun localizedSongsNeedNoAlbumAndStillRejectAnUnrequestedLiveTake() {
        val song = MusicMetadata("Love Live", "米津玄師")
        val original = localizedRow("Kenshi Yonezu", "original000", album = null, title = song.title)
        val right = localizedRow(song.artist, "original000", album = null, title = song.title)
        val live = localizedRow(song.artist, "original000", album = null, title = "Love (Live)")
        assertEquals(localizedFound("original000"), localizedMatch(song, listOf(original), mapOf("ja" to listOf(right))))
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, listOf(original), mapOf("ja" to listOf(live))))
    }

    private fun localizedRow(
        artist: String, id: String?, album: String? = "Lemon", time: String = "4:17",
        title: String = "Lemon", artistId: String = "UCartist", alsoBy: String? = null
    ): String {
        fun artistRun(name: String, id: String) = JSONObject().put("text", name)
            .put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", id)))
        fun textRun(text: String) = JSONObject().put("text", text)
        val runs = JSONArray().put(artistRun(artist, artistId))
        if (alsoBy != null) runs.put(textRun("、")).put(artistRun(alsoBy, "UCcollaborator"))
        if (album != null) runs.put(textRun(" • ")).put(textRun(album)).put(textRun(" • ")).put(textRun(time))
        val item = JSONObject(youTubeMusicRow(title, "", videoId = id))
        item.getJSONObject("musicResponsiveListItemRenderer").getJSONArray("flexColumns").getJSONObject(1)
            .getJSONObject("musicResponsiveListItemFlexColumnRenderer").getJSONObject("text").put("runs", runs)
        return item.toString()
    }

    private fun localizedFound(id: String) = ExactMatcher.Match.Found("https://music.youtube.com/watch?v=$id", lasting = false)

    private fun localizedMatch(
        song: MusicMetadata, original: List<String>, localized: Map<String, List<String>>,
        matcher: ExactMatcher = matcher(), apple: String? = null, onVideos: () -> Unit = {}, onLocalized: () -> Unit = {}
    ): ExactMatcher.Match {
        fake.handler = { request ->
            when (request.url.host) {
                "itunes.apple.com" -> {
                    if (apple == null || request.url.queryParameter("country") != "JP" || request.url.encodedPath != "/search") {
                        throw IOException("Apple is down")
                    }
                    FakeSpotify.html(request, """{"results":[$apple]}""")
                }
                "api.deezer.com" -> FakeSpotify.html(request, """{"data":[{"title":"Lemon","artist":{"name":"Kenshi Yonezu"},"duration":256}]}""")
                else -> {
                    val body = JSONObject(FakeSpotify.bodyOf(request))
                    val language = body.getJSONObject("context").getJSONObject("client").getString("hl")
                    val rows = when {
                        body.optString("params") == VIDEOS_TAB -> emptyList<String>().also { onVideos() }
                        language == "en" -> original
                        else -> localized[language].orEmpty().also { onLocalized() }
                    }
                    FakeSpotify.html(request, youTubeMusicPage(*rows.toTypedArray()))
                }
            }
        }
        return runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) }
    }

    @Test
    fun aSongNamedOtherwiseOnYouTubeMusicIsPickedFromItsResultsLast() {
        val asked = mutableListOf<Pair<List<String>, Boolean>>()
        var songAnswer: Int? = 1
        var videoAnswer: Int? = null
        val matcher = ExactMatcher(fake.client(), "AR", pick = { metadata, rows, videos ->
            assertEquals("Miki Matsubara", metadata.artist)
            asked += rows to videos
            if (videos) videoAnswer else songAnswer
        })
        val rows = (0..7).map { youTubeMusicRow("真夜中のドア$it", "松原みき • Pocket Park • 5:26", videoId = "door00000$it") }
        val video = youTubeMusicRow("松原みき - 真夜中のドア (歌詞付き)", "Fan • 1M views • 5:26", videoId = "video000000")
        youTubeMusicTabs(songs = listOf(youTubeMusicRow("Album", "Album • 松原みき", browseId = "MPREb_x")) + rows, videos = listOf(video))
        val stayWithMe = MusicMetadata("Stay With Me", "Miki Matsubara")
        assertEquals("https://music.youtube.com/watch?v=door000001", runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, stayWithMe) })
        // Picked among the first six songs, as "title | details", and the videos aren't asked about.
        assertEquals(listOf((0..5).map { "真夜中のドア$it | 松原みき • Pocket Park • 5:26" } to false), asked)
        // None of the songs, a video put up by someone else is picked, asked apart, for a song only there that way.
        asked.clear()
        songAnswer = null
        videoAnswer = 0
        assertEquals("https://music.youtube.com/watch?v=video000000", runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, stayWithMe.copy(title = "Mayonaka no Door")) })
        assertEquals(listOf(false, true), asked.map { it.second })
        assertEquals(listOf("松原みき - 真夜中のドア (歌詞付き) | Fan • 1M views • 5:26"), asked.last().first)
        // No answer, or one past the songs or videos, is no match.
        listOf(-1 to -1, null to null, 8 to 1).forEach { (song, video) ->
            songAnswer = song
            videoAnswer = video
            assertNull("$song $video", runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, stayWithMe) })
        }
        // Not asked when there's no song or video to pick from, or a song matches by name.
        asked.clear()
        youTubeMusicTabs(songs = listOf(youTubeMusicRow("Album", "Album • 松原みき", browseId = "MPREb_x")), videos = emptyList())
        assertNull(runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, stayWithMe) })
        respond(youTubeMusicPage(youTubeMusicRow("Stay With Me", "Miki Matsubara • Album • 5:26", videoId = "named000000")))
        assertEquals("https://music.youtube.com/watch?v=named000000", runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, stayWithMe) })
        assertEquals(emptyList<Pair<List<String>, Boolean>>(), asked)
        // With videos but no songs, only the videos are asked about.
        youTubeMusicTabs(songs = emptyList(), videos = listOf(video))
        videoAnswer = 0
        assertEquals("https://music.youtube.com/watch?v=video000000", runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, stayWithMe) })
        assertEquals(listOf(true), asked.map { it.second })
        // Nor for an album.
        asked.clear()
        respond(youTubeMusicPage(*rows.toTypedArray()))
        runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Pocket Park", "Miki Matsubara", type = ItemType.ALBUM)) }
        assertEquals(emptyList<Pair<List<String>, Boolean>>(), asked)
        // By default nothing is picked.
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, stayWithMe) })
    }

    @Test
    fun aSongNamedInAnotherScriptIsFoundOnYouTubeMusicByItsArtistAndLength() {
        val angel = MusicMetadata("A Cruel Angel's Thesis", "Yoko Takahashi")
        val apple = mapOf("AR" to appleSong("A Cruel Angel's Thesis (Director's Edit Version)", "Yoko Takahashi", 245_093))
        val named = "残酷な天使のテーゼ - Zankoku na Tenshi no Thesis"
        fun find(songs: List<String>) = runBlocking {
            namesAndTabs(songs = songs, apple = apple)
            matcher().find(MusicService.YOUTUBE_MUSIC, angel)
        }
        // Its artist and its length, to the second, are the song's, whatever it's called.
        assertEquals(
            "https://music.youtube.com/watch?v=xyVatKhd4s4",
            find(listOf(youTubeMusicRow(named, "Yoko Takahashi • Zankoku na Tenshi no Thesis • 4:06", videoId = "xyVatKhd4s4")))
        )
        // Not another artist's, another length, or a title in the same script, which is compared by its words.
        assertNull(find(listOf(youTubeMusicRow(named, "Someone Else • Zankoku na Tenshi no Thesis • 4:06", videoId = "other000000"))))
        assertNull(find(listOf(youTubeMusicRow(named, "Yoko Takahashi • Zankoku na Tenshi no Thesis • 4:20", videoId = "longer00000"))))
        assertNull(find(listOf(youTubeMusicRow("Another Song", "Yoko Takahashi • Another Album • 4:06", videoId = "latin000000"))))
    }

    @Test
    fun aRowInAnotherScriptThatSaysItIsAnotherTakeIsNotTheSong() {
        val angel = MusicMetadata("A Cruel Angel's Thesis", "Yoko Takahashi")
        val studio = appleSong("A Cruel Angel's Thesis", "Yoko Takahashi", 245_000, album = "残酷な天使のテーゼ")
        fun row(name: String, id: String, album: String = "残酷な天使のテーゼ", artist: String = "Yoko Takahashi", length: String = "4:06") =
            youTubeMusicRow(name, "$artist • $album • $length", videoId = id)
        fun find(vararg songs: String, apple: String = studio, song: MusicMetadata = angel, other: Map<String, String> = emptyMap()) = runBlocking {
            namesAndTabs(songs = songs.toList(), apple = mapOf("AR" to apple), elsewhere = other)
            matcher().find(MusicService.YOUTUBE_MUSIC, song)
        }
        fun url(id: String) = "https://music.youtube.com/watch?v=$id"
        // Issue #945: the karaoke, as long as the song, and the other takes Issues #983 and #992 name, by their tags, open nothing.
        for ((at, name) in listOf(
            "残酷な天使のテーゼ (オフ・ヴォーカル)", "残酷な天使のテーゼ (TV Size)", "残酷な天使のテーゼ (TV Edit)", "残酷な天使のテーゼ (OP Size)",
            "残酷な天使のテーゼ (ショートバージョン)", "残酷な天使のテーゼ (Short)", "残酷な天使のテーゼ (ライブ)", "残酷な天使のテーゼ (ライヴ)",
            "残酷な天使のテーゼ (Live from Budokan)", "残酷な天使のテーゼ (カヴァー)", "残酷な天使のテーゼ (カラオケ)"
        ).withIndex()) {
            assertNull(name, find(row(name, "take%07d".format(at))))
        }
        // Nor does the album that's live, though the row's name is plain (Issues #983, #992).
        assertNull(find(row("残酷な天使のテーゼ", "album000000", album = "Live at Budokan")))
        // The plain row beside a take is the song, wherever the take is. A compilation named Live Best is no live album.
        assertEquals(url("plain000000"), find(row("残酷な天使のテーゼ (オフ・ヴォーカル)", "karaoke0000"), row("残酷な天使のテーゼ", "plain000000")))
        assertEquals(url("best0000000"), find(row("残酷な天使のテーゼ", "best0000000", album = "Live Best")))
        // Beside a take, a row only known by its artist and length opens as it does alone, as the song may be written otherwise;
        // but not beside a take the song's name in Japan's store names, as that is the song, and the row is not its studio recording.
        assertEquals(url("other000000"), find(row("残酷な天使のテーゼ (ライブ)", "live0000000"), row("魂のルフラン", "other000000")))
        val named = mapOf("JP" to appleSong("残酷な天使のテーゼ", "高橋洋子", 245_000))
        assertNull(find(row("残酷な天使のテーゼ - Live", "live0000000"), row("魂のルフラン", "other000000"), other = named))
        // A show's name or a credit in a tag is no take.
        assertEquals(url("show0000000"), find(row("残酷な天使のテーゼ (TVアニメ「ライブ」オープニングテーマ)", "show0000000")))
        assertEquals(url("credit00000"), find(row("残酷な天使のテーゼ (Live Crew)", "credit00000", artist = "Yoko Takahashi & Live Crew")))
        // The song's own name, or Apple's, may say the take: a live title, or a live album Apple has it on, or editions of different
        // lengths, one of which a live row may be.
        assertEquals(url("live0000000"), find(row("残酷な天使のテーゼ (ライブ)", "live0000000"), song = MusicMetadata("A Cruel Angel's Thesis (Live)", "Yoko Takahashi"),
            apple = appleSong("A Cruel Angel's Thesis (Live)", "Yoko Takahashi", 245_000)))
        val onLiveAlbum = appleSong("A Cruel Angel's Thesis", "Yoko Takahashi", 245_000, album = "Live at Budokan")
        assertEquals(url("live0000000"), find(row("残酷な天使のテーゼ (ライブ)", "live0000000"), apple = onLiveAlbum))
        assertEquals(url("album000000"), find(row("残酷な天使のテーゼ", "album000000", album = "Live at Budokan"), apple = onLiveAlbum))
        val twoLengths = studio + "," + appleSong("A Cruel Angel's Thesis", "Yoko Takahashi", 251_000, id = 8, album = "Another Album")
        assertEquals(url("live0000000"), find(row("残酷な天使のテーゼ (ライブ)", "live0000000", length = "4:11"), apple = twoLengths))
        // But a karaoke row stays out where Apple knows two lengths, as it's as long as the studio song, whichever.
        assertNull(find(row("残酷な天使のテーゼ (オフ・ヴォーカル)", "karaoke0000", length = "4:11"), apple = twoLengths))
        // Recordings of one length, or within three seconds of each other, are one recording: a live row isn't the song then.
        fun editions(vararg lengthsMs: Long) = lengthsMs.withIndex().joinToString(",") { (at, ms) -> appleSong("A Cruel Angel's Thesis", "Yoko Takahashi", ms, id = 7L + at, album = "Album $at") }
        assertNull(find(row("残酷な天使のテーゼ (ライブ)", "live0000000"), apple = editions(245_000, 245_000)))
        assertNull(find(row("残酷な天使のテーゼ (ライブ)", "live0000000"), apple = editions(245_000, 248_000)))
        assertEquals(url("live0000000"), find(row("残酷な天使のテーゼ (ライブ)", "live0000000"), apple = editions(245_000, 248_001)))
        // A title's other letters, or marks, don't make a Latin spelling another script's.
        assertNull(find(row("Café 〇", "mark0000000")))
        // Another song by the artist, as long, with nothing to say it isn't, opens: no evidence takes it away (Issues #945, #983).
        assertEquals(url("other000000"), find(row("魂のルフラン", "other000000", length = "4:04")))
    }

    @Test
    fun aSongInAnotherScriptMayBeAsFarFromTheKnownLengthAsTheSameSongIsAndNoFarther() {
        val angel = MusicMetadata("A Cruel Angel's Thesis", "Yoko Takahashi")
        val named = "残酷な天使のテーゼ - Zankoku na Tenshi no Thesis"
        fun find(length: String) = runBlocking {
            namesAndTabs(songs = listOf(youTubeMusicRow(named, "Yoko Takahashi • Zankoku na Tenshi no Thesis • $length", videoId = "xyVatKhd4s4")), apple = mapOf("AR" to appleSong("A Cruel Angel's Thesis", "Yoko Takahashi", 245_000)))
            matcher().find(MusicService.YOUTUBE_MUSIC, angel)
        }
        // 3 s past the known 4:05 is the same song; a second more is not, and nor is 3 s short of it, or a second less.
        assertEquals("https://music.youtube.com/watch?v=xyVatKhd4s4", find("4:08"))
        assertNull(find("4:09"))
        assertEquals("https://music.youtube.com/watch?v=xyVatKhd4s4", find("4:02"))
        assertNull(find("4:01"))
    }

    @Test
    fun aRerecordingCannotReplaceASongEvenWhenItsLengthMatches() {
        val song = MusicMetadata("Song", "Band")
        val watch = "https://music.youtube.com/watch?v="
        for (label in listOf("From THE FIRST TAKE", "G.E.M.重生版")) {
            val title = "Song ($label)"
            val rerecorded = youTubeMusicRow(title, "Band • New Album • 3:59", videoId = "rerecorded0")
            val original = youTubeMusicRow("Song", "Band • Original Album • 4:00", videoId = "original000")
            namesAndTabs(songs = listOf(rerecorded, original))
            for (length in listOf(null, 239_000L, 241_000L)) {
                assertEquals(label, watch + "original000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song.copy(lengthMs = length)) })
            }
            // The requested title can name the re-recording, including a plain row whose album alone says so.
            assertEquals(watch + "rerecorded0", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song.copy(title = title)) })
            namesAndTabs(songs = listOf(youTubeMusicRow("Song", "Band • $title • 3:59", videoId = "rerecorded0"), original))
            assertEquals(watch + "original000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
            assertEquals(watch + "rerecorded0", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song.copy(title = title)) })
            // The linked length must not bring the excluded row back through the live-album fallback.
            namesAndTabs(songs = listOf(original, youTubeMusicRow("Song (Live)", "Band • Live at Venue ($label) • 4:30", videoId = "rerecorded0")))
            assertEquals(watch + "original000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song.copy(lengthMs = 270_000)) })
        }
    }

    @Test
    fun aRerecordingInAnotherScriptCannotBeInferredFromItsLength() {
        // Said in its name, or only on its album.
        for ((name, details) in listOf("別の歌 (G.E.M.重生版)" to "Band • New Album • 4:00", "別の歌" to "Band • 別の歌 (G.E.M.重生版) • 4:00")) {
            namesAndTabs(
                songs = listOf(youTubeMusicRow(name, details, videoId = "rerecorded0")),
                apple = mapOf("AR" to appleSong("Song", "Band", 240_000))
            )
            assertNull(details, runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Song", "Band")) })
        }
        // The same row with no label is the song.
        namesAndTabs(
            songs = listOf(youTubeMusicRow("別の歌", "Band • New Album • 4:00", videoId = "original0")),
            apple = mapOf("AR" to appleSong("Song", "Band", 240_000))
        )
        assertEquals("https://music.youtube.com/watch?v=original0", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Song", "Band")) })
    }

    @Test
    fun aRerecordingCannotBePickedAsASongOrVideo() {
        val song = MusicMetadata("Song", "Band")
        val picker = ExactMatcher(fake.client(), "AR", pick = { _, _, _ -> 0 })
        for (title in listOf("別の歌 - From THE FIRST TAKE", "別の歌 (G.E.M.重生版) - Another Name")) {
            val row = youTubeMusicRow(title, "Band • New Album • 4:00", videoId = "rerecorded0")
            namesAndTabs(songs = listOf(row))
            assertNull(title, runBlocking { picker.find(MusicService.YOUTUBE_MUSIC, song) })
            namesAndTabs(videos = listOf(row))
            assertNull(title, runBlocking { picker.find(MusicService.YOUTUBE_MUSIC, song) })
            // A request for this recording can still pick it when its name is not known locally.
            assertEquals("https://music.youtube.com/watch?v=rerecorded0", runBlocking {
                picker.find(MusicService.YOUTUBE_MUSIC, song.copy(title = "Song ($title)"))
            })
        }
    }

    @Test
    fun aRerecordingLabelDoesNotFilterAlbumOrArtistNames() {
        for (label in listOf("From THE FIRST TAKE", "G.E.M.重生版")) {
            val title = "Collection ($label)"
            respond(youTubeMusicPage(youTubeMusicRow(title, "Album • Band • 2025", browseId = "collection0")))
            for (type in listOf(ItemType.ALBUM, ItemType.ARTIST)) {
                val path = if (type == ItemType.ALBUM) "browse" else "channel"
                assertEquals("https://music.youtube.com/$path/collection0", runBlocking {
                    matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata(title, "Band", type))
                })
            }
        }
    }

    @Test
    fun anArtistCanBeCreditedByTwoKnownNamesTogether() {
        val song = MusicMetadata("Song", "Fake Band")
        val known = listOf("Fake Band", "偽バンド", "Fake Band 偽バンド", "偽バンド Fake Band")
        for (title in listOf("Song", "偽の歌", "別の歌")) {
            for (credit in known + listOf("Fake Band 偽のバンド", "Unknown 偽バンド", "Fake Band 偽バンド Extra", "Fake Band偽バンド", "Fake Bandx偽バンド")) {
                namesAndTabs(
                    songs = listOf(youTubeMusicRow(title, "$credit • Album • 4:00", videoId = "original000")),
                    apple = mapOf("AR" to appleSong("Song", "Fake Band", 240_000)),
                    elsewhere = mapOf("JP" to appleSong("偽の歌", "偽バンド", 240_000))
                )
                val expected = "https://music.youtube.com/watch?v=original000".takeIf { credit in known }
                assertEquals("$title | $credit", expected, runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
            }
        }
    }

    @Test
    fun aRemixIsFoundOnYouTubeMusicByItsAlbumWhenItsNameLeavesItOut() {
        respond(
            youTubeMusicPage(
                // The original first, and the remix named the same, on its remix album.
                youTubeMusicRow("Twilight Bay City", "S.Kiyotaka & Omega Tribe • NEVER ENDING SUMMER • 4:11", videoId = "original000"),
                youTubeMusicRow("Twilight Bay City", "S.Kiyotaka & Omega Tribe • NEVER ENDING SUMMER REMIX • 4:19", videoId = "remix000000")
            )
        )
        val remix = MusicMetadata("Twilight Bay City (Remix)", "S.Kiyotaka & Omega Tribe")
        assertEquals("https://music.youtube.com/watch?v=remix000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, remix) })
        // Without the remix there, the original doesn't stand in for it.
        respond(youTubeMusicPage(youTubeMusicRow("Twilight Bay City", "S.Kiyotaka & Omega Tribe • NEVER ENDING SUMMER • 4:11", videoId = "original000")))
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, remix) })
    }

    @Test
    fun aSongNamedWithTheFilmItsOfIsFoundOnYouTubeMusicHoweverItSaysSo() {
        respond(youTubeMusicPage(youTubeMusicRow("小幸運(我的少女時代電影主題曲)", "田馥甄 • 小幸運 • 4:26", videoId = "xiaoxingyun")))
        assertEquals("https://music.youtube.com/watch?v=xiaoxingyun", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("小幸運 (電影《我的少女時代》主題曲)", "田馥甄")) })
        // It's searched for without the film, which YouTube Music finds nothing for when it names it otherwise.
        assertTrue(fake.requestBodies.first(), fake.requestBodies.first().contains("\"query\":\"小幸運 田馥甄\""))
    }

    @Test
    fun youTubeMusicMatchesSongsAlbumsAndArtists() {
        respond(
            youTubeMusicPage(
                youTubeMusicRow("Beyoncé Song (Remix)", "Carly Rae Jepsen • Album • 4:14", videoId = "remix000000"),
                youTubeMusicRow("Beyonce Song", "Carly Rae Jepsen & Guest • Album • 3:20", videoId = "exact000000")
            )
        )
        assertEquals("https://music.youtube.com/watch?v=exact000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
        assertEquals("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", fake.requestedUrls.last())
        assertTrue(fake.requestBodies.last(), fake.requestBodies.last().contains("EgWKAQIIAWoKEAoQAxAEEAkQBQ=="))

        respond(youTubeMusicPage(youTubeMusicRow("After Hours", "Album • The Weeknd • 2020", browseId = "MPREb_abc")))
        val album = MusicMetadata("After Hours", "The Weeknd", ItemType.ALBUM)
        assertEquals("https://music.youtube.com/browse/MPREb_abc", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, album) })

        respond(youTubeMusicPage(youTubeMusicRow("The Weeknd", "Artist • 1M monthly audience", browseId = "UC123")))
        val artist = MusicMetadata("The Weeknd", "", ItemType.ARTIST)
        assertEquals("https://music.youtube.com/channel/UC123", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, artist) })

        respond(youTubeMusicPage(youTubeMusicRow("Beyoncé Song", "Someone Else • Album • 3:20", videoId = "other000000")))
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
        // Rows without their columns, like a header or an ad, are skipped rather than crashing the search.
        respond(
            youTubeMusicPage(
                """{"musicResponsiveListItemRenderer":{}}""",
                youTubeMusicRow("Beyonce Song", "Carly Rae Jepsen • Album • 3:20", videoId = "after0000000")
            )
        )
        assertEquals("https://music.youtube.com/watch?v=after0000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
        respond("""{"contents":{}}""")
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
    }

    /** YouTube Music answers its songs tab with [songs] and its videos tab with [videos]. */
    private fun youTubeMusicTabs(songs: List<String>, videos: List<String>) {
        fake.handler = { request ->
            val asked = FakeSpotify.bodyOf(request)
            FakeSpotify.html(request, youTubeMusicPage(*(if (asked.contains(VIDEOS_TAB)) videos else songs).toTypedArray()))
        }
    }

    /**
     * YouTube Music answers with [songs] and [videos]; Apple's search, by store, with [apple]; its
     * lookup of song 7, by store, with [elsewhere]; and Deezer with [deezer].
     */
    private fun namesAndTabs(
        songs: List<String> = emptyList(),
        videos: List<String> = emptyList(),
        apple: Map<String, String> = emptyMap(),
        elsewhere: Map<String, String> = emptyMap(),
        deezer: String = "[]",
        /** The stores whose searches and lookups fail, as offline; the rest that don't have the song answer with none. */
        failing: Set<String> = emptySet(),
        /** The stores Apple says don't exist, with a 400, as Iran's. */
        noStore: Set<String> = emptySet(),
        /** The stores Apple says are busy, with a 503. */
        busy: Set<String> = emptySet(),
        deezerDown: Boolean = false
    ) {
        fake.handler = { request ->
            val store = request.url.queryParameter("country").orEmpty()
            when {
                request.url.host == "itunes.apple.com" && store in failing -> throw IOException("offline")
                request.url.host == "itunes.apple.com" && store in noStore ->
                    FakeSpotify.html(request, """{"errorMessage":"Invalid value(s) for key(s): [country]"}""", code = 400)
                request.url.host == "itunes.apple.com" && store in busy -> FakeSpotify.html(request, "{}", code = 503)
                request.url.host == "itunes.apple.com" && request.url.encodedPath == "/search" ->
                    FakeSpotify.html(request, """{"results":[${apple[store].orEmpty()}]}""")
                request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[${elsewhere[store].orEmpty()}]}""")
                request.url.host == "api.deezer.com" && deezerDown -> throw IOException("Deezer is down")
                request.url.host == "api.deezer.com" -> FakeSpotify.html(request, """{"data":$deezer}""")
                else -> FakeSpotify.html(request, youTubeMusicPage(*(if (FakeSpotify.bodyOf(request).contains(VIDEOS_TAB)) videos else songs).toTypedArray()))
            }
        }
    }

    private fun appleSong(title: String, artist: String, lengthMs: Long = 312_000, id: Long = 7, album: String? = null) =
        """{"trackId":$id,"trackName":"$title","artistName":"$artist","trackTimeMillis":$lengthMs${album?.let { ""","collectionName":"$it${'"'}""" }.orEmpty()}}"""

    @Test
    fun aBusyOrFailingServiceIsNoAnswerRatherThanNotThere() {
        val song = MusicMetadata("Song", "Band")
        fake.handler = { request -> FakeSpotify.html(request, """{"data":[]}""", code = 429) }
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher().lookFor(MusicService.DEEZER, song) })
        fake.handler = { request -> FakeSpotify.html(request, """{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}""") }
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher().lookFor(MusicService.DEEZER, song) })
        // However the error's put.
        fake.handler = { request -> FakeSpotify.html(request, """{"error":"Quota limit exceeded"}""") }
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher().lookFor(MusicService.DEEZER, song) })
        fake.handler = { throw IOException("offline") }
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher().lookFor(MusicService.APPLE_MUSIC, song) })
        respond("""{"data":[]}""")
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher().lookFor(MusicService.DEEZER, song) })
        respond("""{"data":[{"title":"Song","link":"https://www.deezer.com/track/1","artist":{"name":"Band"}}]}""")
        assertEquals(ExactMatcher.Match.Found("https://www.deezer.com/track/1"), runBlocking { matcher().lookFor(MusicService.DEEZER, song) })
        // An app that can't be searched without an account has nothing to say either way.
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher().lookFor(MusicService.AMAZON_MUSIC, song) })
        // Nor one that's only searched, as a song is on many of its albums.
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher().lookFor(MusicService.PRESTO_MUSIC, song) })
    }

    /** A Spotify song's own page, as much of it as says what the song is. */
    private fun spotifyPage(title: String, description: String, seconds: Int) =
        """<meta property="og:title" content="$title"/><meta property="og:description" content="$description"/><meta name="music:duration" content="$seconds"/>"""

    /** ListenBrainz knowing [ids] by a name, and Spotify's pages for some of them; any other is gone. */
    private fun spotifyKnows(ids: List<String>, pages: Map<String, String>) {
        fake.handler = { request ->
            if (request.url.host == "labs.api.listenbrainz.org") {
                FakeSpotify.html(request, """[{"spotify_track_ids":[${ids.joinToString(",") { "\"$it\"" }}]}]""")
            } else {
                pages[request.url.pathSegments.last()]?.let { FakeSpotify.html(request, it) } ?: FakeSpotify.html(request, "", code = 404)
            }
        }
    }

    @Test
    fun aSpotifySongIsTheOneListenBrainzKnowsByItsNameThatsTheArtistsAndAsLong() {
        val cover = "1coverAAAAAAAAAAAAAAAA"
        val edit = "2editBBBBBBBBBBBBBBBBB"
        val album = "3albumCCCCCCCCCCCCCCCC"
        spotifyKnows(
            listOf(cover, "4goneDDDDDDDDDDDDDDDDD", "", edit, album),
            mapOf(
                cover to spotifyPage("Bizarre Love Triangle", "Frente! · Marvin the Album · Song · 1994", 120),
                edit to spotifyPage("Bizarre Love Triangle", "New Order · Substance · Song · 1987", 222),
                album to spotifyPage("Bizarre Love Triangle", "New Order · Brotherhood · Song · 1986", 261)
            )
        )
        val song = MusicMetadata("Bizarre Love Triangle", "New Order", lengthMs = 261_500)
        assertEquals("https://open.spotify.com/track/$album", runBlocking { matcher().find(MusicService.SPOTIFY, song) })
        // Asked by the song's name and artist.
        val asked = fake.requestBodies.single { "track_name" in it }
        assertTrue("\"track_name\":\"Bizarre Love Triangle\"" in asked && "\"artist_name\":\"New Order\"" in asked)
        // A blank id isn't a song to look up.
        assertTrue(fake.requestedUrls.none { it.endsWith("/track/") })
        // How long it is unknown, the artist's first.
        assertEquals("https://open.spotify.com/track/$edit", runBlocking { matcher().find(MusicService.SPOTIFY, song.copy(lengthMs = null)) })
    }

    @Test
    fun spotifyFindsNothingWhenListenBrainzKnowsNoneOrNoneIsTheArtists() {
        val song = MusicMetadata("Song", "Band")
        spotifyKnows(emptyList(), emptyMap())
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher().lookFor(MusicService.SPOTIFY, song) })
        val other = "1otherAAAAAAAAAAAAAAAA"
        spotifyKnows(listOf(other), mapOf(other to spotifyPage("Song", "Someone Else · Album · Song · 2001", 200)))
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher().lookFor(MusicService.SPOTIFY, song.copy(title = "Other Song")) })
        // ListenBrainz down says nothing about whether Spotify has it.
        fake.handler = { request -> FakeSpotify.html(request, "", code = 503) }
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher().lookFor(MusicService.SPOTIFY, song.copy(title = "Third Song")) })
    }

    /** An artist's most played songs, as Spotify's embed page for them lists them: name, artists, id and length. */
    @Test
    fun aNewSpotifySongListenBrainzDoesntKnowYetIsFoundAmongItsArtistsMostPlayed() {
        val single = "1singleAAAAAAAAAAAAAAA"
        val acoustic = "2acousticBBBBBBBBBBBBB"
        fake.handler = { request ->
            when (request.url.host) {
                "labs.api.listenbrainz.org" -> FakeSpotify.html(request, """[{"spotify_track_ids":[]}]""")
                else -> FakeSpotify.html(request, FakeSpotify.artistPage("Stella Lefty, Someone", Triple("Other", "3otherCCCCCCCCCCCCCCCC", 200), Triple("Boston", acoustic, 150), Triple("Boston", single, 171)))
            }
        }
        val asked = mutableListOf<String>()
        val matcher = ExactMatcher(fake.client(), "AR", spotifyArtist = { name -> asked += name; "6hp2uD84OrQ3u3ukmTjLz2".takeIf { name == "Stella Lefty" } })
        // The first of the credited artists, and the one of their songs as long as it.
        val song = MusicMetadata("Boston", "Stella Lefty, Someone", lengthMs = 170_000)
        assertEquals("https://open.spotify.com/track/$single", runBlocking { matcher.find(MusicService.SPOTIFY, song) })
        assertEquals(listOf("Stella Lefty"), asked)
        assertTrue("https://open.spotify.com/embed/artist/6hp2uD84OrQ3u3ukmTjLz2" in fake.requestedUrls)
        // Not among them, or an artist Spotify's unknown for, it isn't found.
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher.lookFor(MusicService.SPOTIFY, song.copy(title = "Jaded")) })
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher.lookFor(MusicService.SPOTIFY, song.copy(artist = "Nobody")) })
        // With no artist named, there's no one's songs to look among.
        val before = asked.size
        assertNull(runBlocking { matcher.find(MusicService.SPOTIFY, song.copy(artist = "")) })
        assertEquals(before, asked.size)
    }

    @Test
    fun aSongNotFoundStaysNotFoundForAWhileButNoAnswerIsAskedAgain() {
        var clock = 4_000L
        var asked = 0
        val matcher = ExactMatcher(fake.client(), "AR", missingForMs = 1_000, now = { clock })
        val song = MusicMetadata("Song", "Band")
        fake.handler = { request -> asked++; FakeSpotify.html(request, """{"data":[]}""") }
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher.lookFor(MusicService.DEEZER, song) })
        clock += 999
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher.lookFor(MusicService.DEEZER, song) })
        assertEquals(1, asked)
        // A while later, it's looked for again, and found.
        clock++
        respond("""{"data":[{"title":"Song","link":"https://www.deezer.com/track/1","artist":{"name":"Band"}}]}""")
        assertEquals(ExactMatcher.Match.Found("https://www.deezer.com/track/1"), runBlocking { matcher.lookFor(MusicService.DEEZER, song) })
        // No answer isn't kept: the next time asks again.
        asked = 0
        val other = MusicMetadata("Other", "Band")
        fake.handler = { request -> asked++; FakeSpotify.html(request, "", code = 503) }
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher.lookFor(MusicService.DEEZER, other) })
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher.lookFor(MusicService.DEEZER, other) })
        assertEquals(2, asked)
        // By default nothing not found is kept.
        asked = 0
        fake.handler = { request -> asked++; FakeSpotify.html(request, """{"data":[]}""") }
        runBlocking { matcher().lookFor(MusicService.DEEZER, song) }
        val again = matcher()
        runBlocking { again.lookFor(MusicService.DEEZER, song); again.lookFor(MusicService.DEEZER, song) }
        assertEquals(3, asked)
    }

    @Test
    fun aPickFarFromTheSongsLengthIsntTaken() {
        val song = MusicMetadata("Stay With Me", "Miki Matsubara")
        var answer = 0
        val matcher = ExactMatcher(fake.client(), "AR", pick = { _, _, _ -> answer })
        fun picks(songs: List<String> = emptyList(), videos: List<String> = emptyList()): String? {
            namesAndTabs(songs = songs, videos = videos, apple = mapOf("AR" to appleSong("Stay With Me", "Miki Matsubara", 312_000)))
            return runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, song) }
        }
        // A song a few seconds off is it; one far off, whatever its title says, isn't.
        assertEquals("https://music.youtube.com/watch?v=door0000000", picks(songs = listOf(youTubeMusicRow("真夜中のドア", "松原みき • Pocket Park • 5:18", videoId = "door0000000"))))
        assertNull(picks(songs = listOf(youTubeMusicRow("真夜中のドア", "松原みき • Pocket Park • 3:00", videoId = "door0000000"))))
        // A video may run longer, but not much.
        assertEquals("https://music.youtube.com/watch?v=video000000", picks(videos = listOf(youTubeMusicRow("真夜中のドア MV", "Fan • 1K views • 5:50", videoId = "video000000"))))
        assertNull(picks(videos = listOf(youTubeMusicRow("真夜中のドア 1 hour loop", "Fan • 1K views • 1:00:00", videoId = "video000000"))))
        // Without a length to go by, it's taken.
        namesAndTabs(songs = listOf(youTubeMusicRow("真夜中のドア", "松原みき • Pocket Park • 3:00", videoId = "door0000000")))
        assertEquals("https://music.youtube.com/watch?v=door0000000", runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, song) })
        answer = 3
        assertNull(runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun aPickNearAnyOfTheSongsEditionsIsTakenButNotNearAnotherSongApplesLists() {
        val song = MusicMetadata("Stay With Me", "Miki Matsubara")
        val matcher = ExactMatcher(fake.client(), "AR", pick = { _, _, _ -> 0 })
        fun picks(length: String, store: String): String? {
            namesAndTabs(songs = listOf(youTubeMusicRow("真夜中のドア", "松原みき • Pocket Park • $length", videoId = "door0000000")), apple = mapOf("AR" to store))
            return runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, song) }
        }
        val editions = appleSong("Stay With Me", "Miki Matsubara", 312_000) + "," + appleSong("Stay With Me", "Miki Matsubara", 250_000, id = 8)
        // The single's length, not the album's that Apple lists first, is still the song.
        assertEquals("https://music.youtube.com/watch?v=door0000000", picks("4:12", editions))
        assertNull(picks("3:30", editions))
        // Up to eight seconds off, give or take.
        assertEquals("https://music.youtube.com/watch?v=door0000000", picks("4:18", editions))
        assertNull(picks("4:19", editions))
        // Another song by the same artist in the same search lends it no length.
        assertNull(picks("3:30", appleSong("Stay With Me", "Miki Matsubara", 312_000) + "," + appleSong("Mayonaka no Door", "Miki Matsubara", 210_000, id = 8)))
    }

    @Test
    fun aSongNamedInJapaneseAndEnglishApartByAWideSpaceIsFoundOverAFansUpload() {
        // Recorded from YouTube Music's searches for I Can't Stop The Loneliness, which neither Apple nor Deezer names so.
        namesAndTabs(
            songs = listOf(
                youTubeMusicRow("悲しみがとまらない　I CAN'T STOP THE LONELINESS - Kanashimi ga Tomaranai I CAN'T STOP THE LONELINESS", "Anri • The Anri • 4:27", videoId = "6q6GtPdVHL4"),
                youTubeMusicRow("I CAN'T STOP THE LONELINESS (Spanish Version)", "Dianilis • I CAN'T STOP THE LONELINESS (Spanish Version) • 4:27", videoId = "Xv8YtduJodo")
            ),
            videos = listOf(youTubeMusicRow("ANRI - I Can't Stop The Loneliness || Sub español", "Sweet Honey Pie • 1.7M views • 4:25", videoId = "gLfnq0TSmdg")),
            apple = mapOf("AR" to appleSong("I Can't Stop the Loneliness", "Jupiter", 253_837)),
            deezer = """[{"title":"Kanashimi ga Tomaranai I CAN'T STOP THE LONELINESS","artist":{"name":"Anri"},"duration":263}]"""
        )
        assertEquals("https://music.youtube.com/watch?v=6q6GtPdVHL4", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("I Can't Stop The Loneliness", "ANRI")) })
    }

    @Test
    fun aSongNamedOtherwiseAtHomeIsItUpToTheSameLengthGiveOrTakeEightSeconds() {
        val song = MusicMetadata("Stay With Me", "Miki Matsubara")
        fun finds(length: String): String? {
            namesAndTabs(
                songs = listOf(youTubeMusicRow("真夜中のドア", "松原みき • Pocket Park • $length", videoId = "stay0000000")),
                apple = mapOf("AR" to appleSong("Stay With Me", "Miki Matsubara", 250_000)), elsewhere = mapOf("JP" to appleSong("真夜中のドア", "松原みき", 250_000))
            )
            return runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) }
        }
        assertEquals("https://music.youtube.com/watch?v=stay0000000", finds("4:18"))
        assertNull(finds("4:19"))
        // Its length known nowhere, Deezer's 0 for a song it hasn't timed isn't one, so it's found at any length.
        namesAndTabs(
            songs = listOf(youTubeMusicRow("真夜中のドア", "松原みき • Pocket Park • 4:19", videoId = "stay0000000")),
            apple = mapOf("AR" to appleSong("Stay With Me", "Miki Matsubara", 0)), elsewhere = mapOf("JP" to appleSong("真夜中のドア", "松原みき", 0)),
            deezer = """[{"title":"Stay With Me","artist":{"name":"Miki Matsubara"},"duration":0}]"""
        )
        assertEquals("https://music.youtube.com/watch?v=stay0000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun chatGptHasItsOwnWhileToPickAfterTheSearches() {
        val stayWithMe = MusicMetadata("Stay With Me", "Miki Matsubara")
        youTubeMusicTabs(songs = listOf(youTubeMusicRow("真夜中のドア", "松原みき • Pocket Park • 5:26", videoId = "door0000000")), videos = emptyList())
        // Slower than a search may take, but in its own time, it's still heard.
        var pickMs = 700L
        val matcher = ExactMatcher(fake.client(), "AR", pick = { _, _, _ -> kotlinx.coroutines.delay(pickMs); 0 }, timeoutMs = 500, pickTimeoutMs = 1_500)
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=door0000000"), runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, stayWithMe) })
        // Past it, there's no answer, which isn't taken as not being there.
        pickMs = 2_000
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, stayWithMe.copy(title = "Mayonaka no Door")) })
    }

    @Test
    fun aSongIsFoundOnYouTubeMusicByTheNamesAppleGivesItInOtherStores() {
        val stayWithMe = MusicMetadata("Stay With Me", "Miki Matsubara")
        val apple = mapOf("AR" to appleSong("Stay With Me", "Miki Matsubara"))
        val japanese = mapOf("JP" to appleSong("真夜中のドア〜stay with me", "松原みき"))
        // Under its Japanese name and artist, as long as the song, give or take a remaster's seconds.
        namesAndTabs(
            songs = listOf(
                youTubeMusicRow("真夜中のドア〜stay with me", "Someone Else • Covers • 5:12", videoId = "cover000000"),
                youTubeMusicRow("真夜中のドア〜stay with me", "松原みき • Pocket Park • 5:17", videoId = "door0000000")
            ),
            apple = apple, elsewhere = japanese
        )
        assertEquals("https://music.youtube.com/watch?v=door0000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, stayWithMe) })
        // Its name both ways, "真夜中のドア〜stay with me - Mayonaka no Door", is each of them.
        namesAndTabs(songs = listOf(youTubeMusicRow("真夜中のドア〜stay with me - Mayonaka no Door", "松原みき • Pocket Park • 5:12", videoId = "door0000000")), apple = apple, elsewhere = japanese)
        assertEquals("https://music.youtube.com/watch?v=door0000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, stayWithMe) })
        // Much longer, it's another recording.
        namesAndTabs(songs = listOf(youTubeMusicRow("真夜中のドア〜stay with me", "松原みき • Club Mixes • 5:43", videoId = "club0000000")), apple = apple, elsewhere = japanese)
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, stayWithMe) })
        // Without Apple's other names, the Japanese one isn't known.
        fake.requestedUrls.clear()
        namesAndTabs(songs = listOf(youTubeMusicRow("真夜中のドア〜stay with me", "松原みき • Pocket Park • 5:12", videoId = "door0000000")), apple = apple)
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, stayWithMe) })
        // Asked of every store that names it otherwise, by the song Apple has; not of the US store, as Argentina's found it
        // and names it in Latin letters too.
        val lookups = fake.requestedUrls.filter { "itunes.apple.com/lookup" in it }.map { it.toHttpUrl() }
        assertEquals(listOf("CN", "HK", "JP", "KR", "TW"), lookups.mapNotNull { it.queryParameter("country") }.sorted())
        assertTrue(lookups.all { it.queryParameter("id") == "7" })
        // But of the US store too where Argentina's has it only under its Japanese name, as the US store names it in Latin
        // letters, as YouTube Music may.
        fake.requestedUrls.clear()
        namesAndTabs(apple = mapOf("AR" to appleSong("真夜中のドア〜stay with me", "松原みき")))
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("真夜中のドア〜stay with me", "松原みき")) })
        assertEquals(
            listOf("CN", "HK", "JP", "KR", "TW", "US"),
            fake.requestedUrls.filter { "itunes.apple.com/lookup" in it }.mapNotNull { it.toHttpUrl().queryParameter("country") }.sorted()
        )
    }

    @Test
    fun theOfficialSongBeatsAnUploadWhenAppleKnowsALongerEditionOfIt() = runTest {
        // Recorded: Apple's US store names Full Moon's take (4:41) first, but YouTube Music has the single's (4:11),
        // while a fan's upload with the lyrics runs 4:42.
        val song = MusicMetadata("tasogare no bay city", "JUNKO YAGAMI")
        val us = appleSong("tasogare no bay city", "JUNKO YAGAMI", 281_200, id = 74904069) + "," +
            appleSong("Tasogare No Bay City", "JUNKO YAGAMI", 250_373, id = 260770646) + "," +
            appleSong("Lady-Ready", "JUNKO YAGAMI", 254_760, id = 260770751)
        val songs = listOf(
            youTubeMusicRow("黄昏のBAY CITY", "八神純子 • 黄昏のBAY CITY • 4:11", videoId = "QLHMhVonF-s"),
            youTubeMusicRow("黄昏のBay City (DISCOバージョン) (ボーナストラック)", "八神純子 • FULL MOON • 7:27", videoId = "Wey2yM_dNfc"),
            youTubeMusicRow("Lady-Ready", "八神純子 • 黄昏のBAY CITY • 4:15", videoId = "5_YotH_2o-M")
        )
        val videos = listOf(youTubeMusicRow("Junko Yagami (八神純子) - 黄昏のBay City [Lyrics Eng/Rom/Kan]", "Reidhaha • 1.5M views • 4:42", videoId = "TwSWeSQAm-g"))
        val finder = ExactMatcher(fake.inlineClient(), "US", ioDispatcher = StandardTestDispatcher(testScheduler))
        namesAndTabs(songs = songs, videos = videos, apple = mapOf("US" to us), elsewhere = mapOf("JP" to appleSong("黄昏のBay City", "八神純子", 281_200, id = 74904069)))
        assertEquals("https://music.youtube.com/watch?v=QLHMhVonF-s", finder.find(MusicService.YOUTUBE_MUSIC, song))
        // Not an edition Apple has, it's still another recording, and the upload that matches is what plays.
        namesAndTabs(songs = songs.drop(1), videos = videos, apple = mapOf("US" to us), elsewhere = mapOf("JP" to appleSong("黄昏のBay City", "八神純子", 281_200, id = 74904069)))
        assertEquals("https://music.youtube.com/watch?v=TwSWeSQAm-g", finder.find(MusicService.YOUTUBE_MUSIC, song))
    }

    @Test
    fun aSongCreditedOtherwiseEverywhereButItsOwnCountryIsFoundByThatCountrysStore() {
        val song = MusicMetadata("光年之外 (電影《Passengers》中國區主題曲)", "鄧紫棋")
        val songs = listOf(youTubeMusicRow("光年之外 (電影 《Passengers》 中文主題曲)", "G.E.M. • 光年之外 • 3:56", videoId = "lightyears0"))
        // Only karaoke of it is credited to the name the link gives.
        val videos = listOf(youTubeMusicRow("鄧紫棋 - 光年之外(KTV伴唱)", "米米KTV • 1M views • 3:56", videoId = "karaoke0000"))
        // Argentina's and the US store credit it to G.E.M., Taiwan's to 鄧紫棋, and Japan's, looked up by the song, to G.E.M.
        val japan = mapOf("JP" to appleSong("光年之外 (電影《Passengers》中國區主題曲)", "G.E.M.", 235_505))
        namesAndTabs(songs = songs, videos = videos, apple = mapOf("TW" to appleSong("光年之外 (電影《Passengers》中國區主題曲)", "鄧紫棋", 235_505)), elsewhere = japan)
        assertEquals("https://music.youtube.com/watch?v=lightyears0", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
        // Not known by G.E.M. anywhere, it isn't found, and the karaoke doesn't stand in for it.
        namesAndTabs(songs = songs, videos = videos, elsewhere = japan)
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun anArtistGivenInItsOwnScriptIsFoundByItsNameInLatinLetters() {
        // Korea's store credits Magnetic to 아일릿, the US store and YouTube Music to ILLIT.
        val song = MusicMetadata("Magnetic", "아일릿", lengthMs = 160_688)
        val songs = listOf(youTubeMusicRow("Magnetic", "ILLIT • SUPER REAL ME • 2:41", videoId = "magnetic000"))
        val us = appleSong("Magnetic", "Someone Else", 200_000, id = 1) + "," + appleSong("Magnetic", "ILLIT", 163_688)
        namesAndTabs(songs = songs, apple = mapOf("US" to us))
        assertEquals("https://music.youtube.com/watch?v=magnetic000", runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song) })
        // Only a song as long, give or take 3 seconds, is theirs; and an artist given in Latin letters is that one.
        assertNull(runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song.copy(lengthMs = 160_687)) })
        assertNull(runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song.copy(lengthMs = null)) })
        assertNull(runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song.copy(artist = "Some Band")) })
    }

    @Test
    fun aSongTheUsStoreTranslatesIsFoundWhenItsHomeStoreNamesItAsGiven() {
        // Korea's store has 체포하겠어 by 아일릿; the US store and YouTube Music, You're Under Arrest by ILLIT.
        // As long, give or take 3 seconds.
        val song = MusicMetadata("체포하겠어", "아일릿", lengthMs = 181_912 - SAME_RECORDING_LENGTH_MS)
        val songs = listOf(youTubeMusicRow("You're Under Arrest", "ILLIT • You're Under Arrest • 3:02", videoId = "arrest00000"))
        val us = appleSong("Rush", "ILLIT", 150_000, id = 1) + "," + appleSong("You're Under Arrest", "ILLIT", 181_912, id = 42)
        namesAndTabs(songs = songs, apple = mapOf("US" to us), elsewhere = mapOf("KR" to appleSong("체포하겠어", "아일릿", 181_912, id = 42)))
        assertEquals("https://music.youtube.com/watch?v=arrest00000", runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song) })
        assertTrue(fake.requestedUrls.any { "/lookup" in it && "id=42" in it })
        // Under other names at home, it's another song.
        namesAndTabs(songs = songs, apple = mapOf("US" to us), elsewhere = mapOf("KR" to appleSong("Magnetic", "아일릿", 181_912, id = 42)))
        assertNull(runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song) })
        // Nor is one that isn't as long.
        namesAndTabs(songs = songs, apple = mapOf("US" to us), elsewhere = mapOf("KR" to appleSong("체포하겠어", "아일릿", 181_912, id = 42)))
        assertNull(runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song.copy(lengthMs = 181_912 - SAME_RECORDING_LENGTH_MS - 1)) })
        // A song given in Latin letters has no home store to ask.
        fake.requestedUrls.clear()
        assertNull(runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Arrest", "Some Band", lengthMs = 181_912)) })
        assertTrue(fake.requestedUrls.none { "/lookup" in it })
    }

    @Test
    fun appleIsAskedInTheUsStoreWhenTheLocalOneOnlyNamesItInAnotherScript() {
        val song = MusicMetadata("Yoru ni Kakeru", "YOASOBI")
        namesAndTabs(
            songs = listOf(youTubeMusicRow("夜に駆ける", "YOASOBI • THE BOOK • 4:21", videoId = "yoru0000000")),
            apple = mapOf("AR" to appleSong("夜に駆ける", "YOASOBI", id = 1), "US" to appleSong("Yoru ni kakeru", "YOASOBI", 261_000)),
            elsewhere = mapOf("JP" to appleSong("夜に駆ける", "YOASOBI", 261_000))
        )
        assertEquals("https://music.youtube.com/watch?v=yoru0000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
        // Only the song it found is looked up elsewhere, not the other one.
        assertTrue(fake.requestedUrls.filter { "/lookup" in it }.all { it.toHttpUrl().queryParameter("id") == "7" })
        // From the US, it's asked once.
        fake.requestedUrls.clear()
        assertEquals("https://music.youtube.com/watch?v=yoru0000000", runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song) })
        assertEquals(1, fake.requestedUrls.count { "/search" in it && "itunes" in it })
        // Apple's one with a tag counts, but not another version of it.
        namesAndTabs(
            songs = listOf(youTubeMusicRow("夜に駆ける", "YOASOBI • THE BOOK • 4:21", videoId = "yoru0000000")),
            apple = mapOf("US" to appleSong("Yoru ni Kakeru (Live)", "YOASOBI", 261_000) + "," + appleSong("Yoru ni Kakeru (2020 Edition)", "YOASOBI", 261_000)),
            elsewhere = mapOf("JP" to appleSong("夜に駆ける", "YOASOBI", 261_000))
        )
        assertEquals("https://music.youtube.com/watch?v=yoru0000000", runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song) })
        namesAndTabs(
            songs = listOf(youTubeMusicRow("夜に駆ける", "YOASOBI • THE BOOK • 4:21", videoId = "yoru0000000")),
            apple = mapOf("US" to appleSong("Yoru ni Kakeru (Live)", "YOASOBI", 261_000)),
            elsewhere = mapOf("JP" to appleSong("夜に駆ける", "YOASOBI", 261_000))
        )
        assertNull(runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun applesUsSearchRunsAfterAMissingOrFailedRegionalSearch() {
        val song = MusicMetadata("Paper Lantern Sky", "The Fixture Owls")
        for (regional in listOf("missing", "timeout", "bad JSON", "server error", "match")) {
            namesAndTabs(
                songs = listOf(youTubeMusicRow("灯りの空", "The Fixture Owls • Lanterns • 3:04", videoId = "fixture0000")),
                apple = mapOf("US" to appleSong(song.title, song.artist, 184_000)),
                elsewhere = mapOf("JP" to appleSong("灯りの空", song.artist, 184_000))
            )
            val otherwise = fake.handler
            fake.handler = { request ->
                if (request.url.host == "itunes.apple.com" && request.url.encodedPath == "/search" && request.url.queryParameter("country") == "AR") {
                    when (regional) {
                        "timeout" -> throw java.net.SocketTimeoutException("Invented regional timeout")
                        "bad JSON" -> FakeSpotify.html(request, "not JSON")
                        "server error" -> FakeSpotify.html(request, """{"results":[${appleSong(song.title, song.artist, 184_000)}]}""", code = 503)
                        "match" -> FakeSpotify.html(request, """{"results":[${appleSong(song.title, song.artist, 184_000)}]}""")
                        else -> otherwise(request)
                    }
                } else otherwise(request)
            }
            fake.requestedUrls.clear()
            assertEquals(regional, "https://music.youtube.com/watch?v=fixture0000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
            val stores = fake.requestedUrls.map { it.toHttpUrl() }.filter { it.host == "itunes.apple.com" && it.encodedPath == "/search" }
                .map { it.queryParameter("country") }
            assertEquals(regional, if (regional == "match") listOf("AR") else listOf("AR", "US"), stores)
        }
    }

    @Test
    fun aThirdPartyVideoNeedsKnownMatchingLengths() {
        val song = MusicMetadata("A Cruel Angel's Thesis", "高橋洋子")
        // Recorded search row for the piano upload, with a fake channel and video ID.
        val title = "高橋洋子「残酷な天使のテーゼ」/ A  Cruel Angel’s Thesis"
        fun find(details: String, apple: Map<String, String>): String? {
            namesAndTabs(videos = listOf(youTubeMusicRow(title, details, videoId = "piano000000")), apple = apple)
            return runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) }
        }
        val known = mapOf("AR" to appleSong("A Cruel Angel's Thesis", "高橋洋子", 239_000))
        assertNull(find("Fan • 1.4K views • 3:59", emptyMap()))
        assertNull(find("Fan • 1.4K views", known))
        assertNull(find("Fan • 1.4K views • 4:30", known))
        assertEquals("https://music.youtube.com/watch?v=piano000000", find("Fan • 1.4K views • 3:59", known))
    }

    @Test
    fun aPianoUploadWithAnUnconfirmedTitleWaitsForJapansLookup() {
        val song = MusicMetadata("A Cruel Angel's Thesis", "高橋洋子")
        val piano = youTubeMusicRow(
            "高橋洋子「残酷な天使のテーゼ」/ A  Cruel Angel’s Thesis", "Fan • 1.4K views • 3:59", videoId = "piano000000"
        )
        namesAndTabs(videos = listOf(piano), apple = mapOf("AR" to appleSong(song.title, song.artist, 239_000)), failing = setOf("JP"))
        var clock = 0L
        val finder = ExactMatcher(fake.client(), "AR", missingForMs = 60 * 60_000L, now = { clock })
        assertEquals(ExactMatcher.Match.Failed, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        clock += ExactMatcher.UNSURE_FOR_MS
        assertEquals(ExactMatcher.Match.Failed, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        val withPicker = ExactMatcher(fake.client(), "AR", pick = { _, _, _ -> error("The unconfirmed upload should not reach the picker") })
        assertEquals(ExactMatcher.Match.Failed, runBlocking { withPicker.lookFor(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun anUnconfirmedLatinTitleWaitsForTheUsLookup() {
        val song = MusicMetadata("終わらない歌", "ザ・ブルーハーツ")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val fan = youTubeMusicRow(
            "ザ・ブルーハーツ - 終わらない歌 (Owaranai Uta)", "Fan • 1K views • 3:04", videoId = "fan00000000"
        )
        namesAndTabs(videos = listOf(fan), apple = apple, failing = setOf("US"))
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher().lookFor(MusicService.YOUTUBE_MUSIC, song) })
        namesAndTabs(videos = listOf(fan), apple = apple, failing = setOf("JP"))
        assertEquals("https://music.youtube.com/watch?v=fan00000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
    }

    // As Apple names Lemon (Music Box), track 1501298975 in MatcherRealAnswersTest's Lemon: every store in Latin letters but
    // Japan's, and YouTube Music only as Japan's does. With Japan's lookup failing, a fan's video is all there is to open. It
    // needs another lookup. Once Japan answers, its name finds the song.
    @Test
    fun aSavedOutageUploadIsTriedAgainWhenAStoreThatNamesItRecovers() {
        val song = MusicMetadata("Lemon (Music Box)", "Orgel Sound J-Pop", lengthMs = 223_760)
        val latin = appleSong("Lemon (Music Box)", "Orgel Sound J-Pop", 223_760, id = 1501298975)
        val japanese = appleSong("Lemon ~ドラマ「アンナチュラル」主題歌~ (オルゴール)", "オルゴールサウンド J-POP", 223_760, id = 1501298975)
        val elsewhere = listOf("JP", "KR", "TW", "HK", "CN").associateWith { if (it == "JP") japanese else latin }
        val songs = listOf(youTubeMusicRow("Lemon ~ドラマ「アンナチュラル」主題歌~ (オルゴール)", "オルゴールサウンド J-POP • オルゴール作品集 米津玄師 • 3:44", videoId = "musicbox000"))
        val videos = listOf(youTubeMusicRow("Orgel Sound J-Pop - Lemon (Music Box)", "Fan • 1K views • 3:44", videoId = "fan00000000"))
        val file = kotlin.io.path.createTempDirectory("lookups").toFile().resolve("lookups.json")
        var clock = 0L
        val matcher = ExactMatcher(
            fake.client(), "US", Dispatchers.Unconfined, LookupCache(file, Dispatchers.Unconfined), missingForMs = 60 * 60_000L, now = { clock }
        )
        namesAndTabs(songs = songs, videos = videos, apple = mapOf("US" to latin), elsewhere = elsewhere, failing = setOf("JP"))
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false), runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        // Asked again at once, as a card does, it's held, and nothing's asked.
        fake.requestedUrls.clear()
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false), runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        assertEquals(emptyList<String>(), fake.requestedUrls)
        namesAndTabs(songs = songs, videos = videos, apple = mapOf("US" to latin), elsewhere = elsewhere)
        clock += ExactMatcher.UNSURE_FOR_MS
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=musicbox000"), runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        // That one's kept.
        clock += ExactMatcher.UNSURE_FOR_MS
        namesAndTabs(songs = songs, videos = videos, apple = mapOf("US" to latin), elsewhere = elsewhere, failing = setOf("JP"))
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=musicbox000"), runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun aSongOnlyPutUpByOthersIsFoundAsTheUploadThatPlaysIt() {
        val song = MusicMetadata("Owaranai Uta", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong("Owaranai Uta", "THE BLUE HEARTS", 184_000))
        val japanese = mapOf("JP" to appleSong("終わらない歌", "THE BLUE HEARTS", 184_000))
        fun finds(vararg videos: String, deezer: String = "[]", names: Map<String, String> = apple): String? {
            namesAndTabs(videos = videos.toList(), apple = names, elsewhere = japanese, deezer = deezer)
            return runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) }
        }
        val lyrics = youTubeMusicRow("The Blue Hearts - Owaranai Uta - (終わらない歌 An Endless Song) with lyrics", "Fan • 195K views • 3:04", videoId = "lyrics00000")
        // Covers, karaoke and live takes are passed over, in any script, for the upload that plays the song.
        assertEquals(
            "https://music.youtube.com/watch?v=lyrics00000",
            finds(
                youTubeMusicRow("【カラオケ】終わらない歌/THE BLUE HEARTS", "Karaoke Ch • 39K views • 3:04", videoId = "karaoke0000"),
                youTubeMusicRow("THE BLUE HEARTS - Owaranai Uta (Cover)", "Someone • 1K views • 3:04", videoId = "cover000000"),
                youTubeMusicRow("THE BLUE HEARTS Owaranai Uta Live 1987", "Fan • 30K views • 3:04", videoId = "live0000000"),
                youTubeMusicRow("Owaranai Uta - Kukki", "Kukki • 1K views • 3:04", videoId = "kukki000000"),
                lyrics
            )
        )
        // In Japanese only, with the artist, "Sub Español" and the year.
        assertEquals(
            "https://music.youtube.com/watch?v=japan000000",
            finds(youTubeMusicRow("THE BLUE HEARTS - 終わらない歌 1987 // Sub Español", "Fan • 1K views • 3:02", videoId = "japan000000"))
        )
        // Someone else's of another length is another recording; the artist's own may be a longer video.
        assertNull(finds(youTubeMusicRow("The Blue Hearts - Owaranai Uta", "Fan • 1K views • 4:30", videoId = "long0000000")))
        assertEquals("https://music.youtube.com/watch?v=own00000000", finds(youTubeMusicRow("終わらない歌 (Official Video)", "THE BLUE HEARTS Official • 1M views • 4:30", videoId = "own00000000")))
        // What's in brackets may be in brackets of its own.
        assertEquals("https://music.youtube.com/watch?v=nested00000", finds(youTubeMusicRow("The Blue Hearts - Owaranai Uta (An Endless Song (1987))", "Fan • 1K views • 3:04", videoId = "nested00000")))
        assertEquals("https://music.youtube.com/watch?v=unclosed000", finds(youTubeMusicRow("The Blue Hearts - Owaranai Uta (An Endless Song (1987)", "Fan • 1K views • 3:04", videoId = "unclosed000")))
        // Not naming the artist, or saying more than the song outside brackets, is no match.
        assertNull(finds(youTubeMusicRow("Owaranai Uta with lyrics", "Fan • 1K views • 3:04", videoId = "noartist000")))
        assertNull(finds(youTubeMusicRow("THE BLUE HEARTS - Owaranai Uta Guitar Lesson Part 2", "Fan • 1K views • 3:04", videoId = "lesson00000")))
        // Not known to Apple, Deezer says how long it is; unknown anywhere, another channel's video isn't taken.
        assertNull(finds(youTubeMusicRow("The Blue Hearts - Owaranai Uta", "Fan • 1K views • 4:30", videoId = "long0000000"), deezer = """[{"title":"Owaranai Uta","artist":{"name":"THE BLUE HEARTS"},"duration":184}]""", names = emptyMap()))
        assertNull(finds(youTubeMusicRow("The Blue Hearts - Owaranai Uta", "Fan • 1K views • 4:30", videoId = "long0000000"), names = emptyMap()))
        // Apple without its length leaves it to Deezer; an hour-long video is far from it too.
        assertEquals("https://music.youtube.com/watch?v=lyrics00000", finds(lyrics, deezer = """[{"title":"Owaranai Uta","artist":{"name":"THE BLUE HEARTS"},"duration":184}]""", names = mapOf("AR" to """{"trackId":7,"trackName":"Owaranai Uta","artistName":"THE BLUE HEARTS"}""")))
        assertNull(finds(youTubeMusicRow("The Blue Hearts - Owaranai Uta", "Fan • 1K views • 1:03:04", videoId = "hour0000000")))
        // A failed lookup in one store doesn't lose the others' names.
        namesAndTabs(songs = listOf(youTubeMusicRow("終わらない歌", "THE BLUE HEARTS • THE BLUE HEARTS • 3:04", videoId = "song0000000")), apple = apple, elsewhere = japanese, failing = setOf("KR"))
        assertEquals("https://music.youtube.com/watch?v=song0000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
        // Nor does it keep someone else's video from being taken, as Korea's store names no Japanese song as YouTube Music would.
        val fans = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "japan000000")
        namesAndTabs(videos = listOf(fans), apple = apple, elsewhere = japanese, failing = setOf("KR"))
        assertEquals("https://music.youtube.com/watch?v=japan000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
        // Nor does a failed search where another store found the song, or the user's country having no store, as Iran.
        namesAndTabs(videos = listOf(fans), apple = mapOf("US" to appleSong("Owaranai Uta", "THE BLUE HEARTS", 184_000)), elsewhere = japanese, failing = setOf("AR"))
        assertEquals("https://music.youtube.com/watch?v=japan000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
        namesAndTabs(videos = listOf(fans), apple = mapOf("US" to appleSong("Owaranai Uta", "THE BLUE HEARTS", 184_000)), elsewhere = japanese, noStore = setOf("IR"))
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=japan000000"), runBlocking { matcher("IR").lookFor(MusicService.YOUTUBE_MUSIC, song) })
        // Even where no other store has the song, as Iran's having none is an answer: Deezer's length proves the upload.
        val deezer = """[{"title":"Owaranai Uta","artist":{"name":"THE BLUE HEARTS"},"duration":184}]"""
        namesAndTabs(videos = listOf(lyrics), deezer = deezer, noStore = setOf("IR"))
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=lyrics00000"), runBlocking { matcher("IR").lookFor(MusicService.YOUTUBE_MUSIC, song) })
        // But a store that's busy is no answer: it may have named the artist's own row.
        namesAndTabs(videos = listOf(lyrics), deezer = deezer, busy = setOf("AR", "US"))
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher().lookFor(MusicService.YOUTUBE_MUSIC, song) })
        // Nor does the US store failing, as Argentina's found the song in Latin letters, so it isn't asked. Where a store
        // found it in another script, the US store's failing does matter: MatcherRealAnswersTest, for Lemon.
        namesAndTabs(videos = listOf(fans), apple = apple, elsewhere = japanese, failing = setOf("US"))
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=japan000000"), runBlocking { matcher().lookFor(MusicService.YOUTUBE_MUSIC, song) })
        // Nor while every store's search failed, any of which could have found it.
        namesAndTabs(videos = listOf(fans), failing = setOf("AR", "US"))
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher().lookFor(MusicService.YOUTUBE_MUSIC, song) })
        // The artist's own still is.
        namesAndTabs(
            videos = listOf(youTubeMusicRow("終わらない歌", "THE BLUE HEARTS • 1M views • 3:04", videoId = "own00000000")),
            apple = apple, elsewhere = mapOf("TW" to japanese.getValue("JP")), failing = setOf("JP")
        )
        assertEquals("https://music.youtube.com/watch?v=own00000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun aFansUploadOpensWhileANameLookupFailsWhenNoSongRowCanMatch() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val fans = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val anotherFan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌 Lyrics", "Another Fan • 1K views • 3:04", videoId = "lyrics00000")
        val expected = ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false)
        for (store in listOf("JP", "US")) {
            namesAndTabs(videos = listOf(fans, anotherFan), apple = apple, failing = setOf(store))
            assertEquals(store, expected, runBlocking { matcher().lookFor(MusicService.YOUTUBE_MUSIC, song) })
        }
        val tooLong = youTubeMusicRow("Owaranai Uta", "THE BLUE HEARTS • Album • 3:13", videoId = "long0000000")
        val noId = youTubeMusicRow("Owaranai Uta", "THE BLUE HEARTS • Album • 3:04")
        namesAndTabs(songs = listOf(tooLong, noId), videos = listOf(fans), apple = apple, failing = setOf("US"))
        assertEquals(expected, runBlocking { matcher().lookFor(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun anUnrelatedPlainVideoHoldsAFanUploadOnlyOnce() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val unrelated = youTubeMusicRow("Another Song", "Random Person • 1K views • 3:04", videoId = "other000000")
        val expected = ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false)
        var clock = 0L
        val finder = ExactMatcher(fake.client(), "AR", now = { clock })
        namesAndTabs(videos = listOf(unrelated, fan), apple = apple, failing = setOf("US"))
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })

        fake.requestedUrls.clear()
        clock += ExactMatcher.UNSURE_FOR_MS
        assertEquals(expected, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        assertTrue(fake.requestedUrls.any { "itunes.apple.com/lookup?id=7&country=US" in it })
    }

    @Test
    fun aCompetingVideoCanBeCloseOrHaveAKnownName() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val upload = ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false)
        fun look(title: String, channel: String, length: String? = null, store: String = "US"): ExactMatcher.Match {
            val details = "$channel • 1K views" + length?.let { " • $it" }.orEmpty()
            val video = youTubeMusicRow(title, details, videoId = "other000000")
            namesAndTabs(videos = listOf(video, fan), apple = apple, failing = setOf(store))
            return runBlocking { matcher().lookFor(MusicService.YOUTUBE_MUSIC, song) }
        }

        for ((title, channel) in listOf(song.title to "Blue Hearts Japan", "Owaranai Uta" to song.artist)) {
            assertEquals("$title by $channel at 4:30", ExactMatcher.Match.WaitingForNames, look(title, channel, "4:30"))
            assertEquals("$title by $channel without a length", ExactMatcher.Match.WaitingForNames, look(title, channel))
        }
        assertEquals(ExactMatcher.Match.WaitingForNames, look("An Endless Song", "Buru Hatsu - Topic", "3:12"))
        assertEquals(upload, look("An Endless Song", "Buru Hatsu - Topic", "3:13"))
        assertEquals(upload, look("An Endless Song", "Buru Hatsu - Topic"))
        assertEquals(ExactMatcher.Match.WaitingForNames, look(song.title, "Buru Hatsu - Topic", "4:30"))
        assertEquals(ExactMatcher.Match.WaitingForNames, look("別の歌", "別の人", "3:04", store = "JP"))
    }

    @Test
    fun aFansUploadWaitsWhenAnotherNameCouldProveASongRow() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val fans = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        // A row with a matching or unknown length can take the upload's place when its store answers.
        for (details in listOf("THE BLUE HEARTS • Album • 3:12", "THE BLUE HEARTS • Album")) {
            val row = youTubeMusicRow("Owaranai Uta", details, videoId = "song0000000")
            namesAndTabs(songs = listOf(row), videos = listOf(fans), apple = apple, failing = setOf("US"))
            assertEquals(details, ExactMatcher.Match.WaitingForNames, runBlocking { matcher().lookFor(MusicService.YOUTUBE_MUSIC, song) })
            namesAndTabs(
                songs = listOf(row), videos = listOf(fans), apple = apple,
                elsewhere = mapOf("US" to appleSong("Owaranai Uta", song.artist, 184_000))
            )
            assertEquals("https://music.youtube.com/watch?v=song0000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
        }
    }

    @Test
    fun aFansUploadWaitsWhenAnotherNameCouldProveTheArtistsVideo() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val japanese = mapOf("JP" to appleSong(song.title, "ザ・ブルーハーツ", 184_000))
        val own = youTubeMusicRow(song.title, "ザ・ブルーハーツ - Topic • 1M views • 3:04", videoId = "own00000000")
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        var clock = 0L
        val finder = ExactMatcher(fake.client(), "AR", missingForMs = 60 * 60_000L, now = { clock })
        namesAndTabs(videos = listOf(own, fan), apple = apple, elsewhere = japanese, failing = setOf("JP"))
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })

        fake.requestedUrls.clear()
        namesAndTabs(videos = listOf(own, fan), apple = apple, elsewhere = japanese)
        clock = ExactMatcher.UNSURE_FOR_MS - 1
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        assertTrue(fake.requestedUrls.isEmpty())
        clock++
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=own00000000"), runBlocking {
            finder.lookFor(MusicService.YOUTUBE_MUSIC, song)
        })

        val unplayable = youTubeMusicRow(song.title, "ザ・ブルーハーツ - Topic • 1M views • 3:04")
        val cover = youTubeMusicRow("${song.title} (Cover)", "ザ・ブルーハーツ - Topic • 1M views • 3:04", videoId = "cover000000")
        val other = youTubeMusicRow(song.title, "더 블루 하츠 - Topic • 1M views • 3:04", videoId = "other000000")
        namesAndTabs(videos = listOf(unplayable, cover, other, fan), apple = apple, failing = setOf("JP"))
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false), runBlocking {
            matcher().lookFor(MusicService.YOUTUBE_MUSIC, song)
        })
    }

    @Test
    fun aCloseVideoCanNeedBothUsNames() {
        val song = MusicMetadata("馬と鹿", "米津玄師")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val us = mapOf("US" to appleSong("Uma to Shika", "Kenshi Yonezu", 184_000))
        val own = youTubeMusicRow("Uma to Shika", "Kenshi Yonezu • 1M views • 3:04", videoId = "own00000000")
        val fan = youTubeMusicRow("米津玄師 - 馬と鹿", "Fan • 1K views • 3:04", videoId = "fan00000000")
        var clock = 0L
        val finder = ExactMatcher(fake.client(), "AR", missingForMs = 60 * 60_000L, now = { clock })
        namesAndTabs(videos = listOf(fan, own), apple = apple, elsewhere = us, failing = setOf("US"))
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        namesAndTabs(videos = listOf(fan, own), apple = apple, elsewhere = us)
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000"), runBlocking {
            matcher().lookFor(MusicService.YOUTUBE_MUSIC, song)
        })
        clock += ExactMatcher.UNSURE_FOR_MS
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=own00000000"), runBlocking {
            finder.lookFor(MusicService.YOUTUBE_MUSIC, song)
        })
        namesAndTabs(videos = listOf(fan, own), apple = apple, elsewhere = us, failing = setOf("US"))
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun aLongArtistVideoCanNeedJapansName() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val own = youTubeMusicRow(song.title, "ザ・ブルーハーツ • 1M views • 4:30", videoId = "own00000000")
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        namesAndTabs(videos = listOf(fan, own), apple = apple, failing = setOf("JP"))
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { matcher().lookFor(MusicService.YOUTUBE_MUSIC, song) })

        val unrelated = youTubeMusicRow("別のうた", "ザ・ブルーハーツ • 1M views • 4:30", videoId = "other000000")
        namesAndTabs(videos = listOf(fan, unrelated), apple = apple, failing = setOf("JP"))
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false), runBlocking {
            matcher().lookFor(MusicService.YOUTUBE_MUSIC, song)
        })
    }

    @Test
    fun anUnrelatedTopicVideoHoldsAFanUploadOnlyOnce() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val other = youTubeMusicRow("Another Song", "Random Person - Topic • 1K views • 3:04", videoId = "other000000")
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val upload = ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false)
        val finder = ExactMatcher(fake.client(), "AR", missingForMs = 60 * 60_000L)
        namesAndTabs(videos = listOf(other, fan), apple = apple, failing = setOf("US"))
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        fake.requestedUrls.clear()
        ShadowSystemClock.advanceBy(Duration.ofMillis(ExactMatcher.UNSURE_FOR_MS - 1))
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        assertTrue(fake.requestedUrls.isEmpty())
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        assertEquals(upload, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        ShadowSystemClock.advanceBy(Duration.ofMillis(ExactMatcher.UNSURE_FOR_MS))
        assertEquals(upload, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun restartingDuringAnOutageDoesNotStartAnotherHold() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val own = youTubeMusicRow(song.title, "ザ・ブルーハーツ - Topic • 1K views • 3:04", videoId = "own00000000")
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val file = kotlin.io.path.createTempDirectory("restart-hold").toFile().resolve("lookups.json")
        var clock = 0L
        fun restart() = ExactMatcher(
            fake.client(), "AR", Dispatchers.Unconfined, LookupCache(file, Dispatchers.Unconfined),
            now = { clock }, wallNow = { clock }
        )
        namesAndTabs(videos = listOf(fan, own), apple = apple, failing = setOf("JP"))
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { restart().lookFor(MusicService.YOUTUBE_MUSIC, song) })
        clock += ExactMatcher.UNSURE_FOR_MS - 1
        fake.requestedUrls.clear()
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { restart().lookFor(MusicService.YOUTUBE_MUSIC, song) })
        assertTrue(fake.requestedUrls.isEmpty())

        clock++
        val upload = ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false)
        assertEquals(upload, runBlocking { restart().lookFor(MusicService.YOUTUBE_MUSIC, song) })
        val otherFan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Another Fan • 1K views • 3:04", videoId = "other000000")
        namesAndTabs(videos = listOf(otherFan, own), apple = apple, failing = setOf("JP"))
        clock = 60 * 60_000L + 1
        fake.requestedUrls.clear()
        assertEquals(upload, runBlocking { restart().lookFor(MusicService.YOUTUBE_MUSIC, song) })
        assertTrue(fake.requestedUrls.any { "country=JP" in it })
    }

    @Test
    fun aFailedYouTubeLookupKeepsTheSavedProvisionalAnswer() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val file = kotlin.io.path.createTempDirectory("failed-provisional").toFile().resolve("lookups.json")
        fun cache() = LookupCache(file, Dispatchers.Unconfined)
        fun restart() = ExactMatcher(fake.client(), "AR", Dispatchers.Unconfined, cache(), now = { 4_000 }, wallNow = { 4_000 })
        val video = ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false)
        val provisional = ExactMatcher.Lookup(video, ExactMatcher.Retry())
        namesAndTabs(videos = listOf(fan), apple = apple, failing = setOf("JP"))
        assertEquals(provisional, runBlocking { restart().lookup(MusicService.YOUTUBE_MUSIC, song) })
        val saved = runBlocking { cache().entries() }

        fake.handler = { request -> FakeSpotify.html(request, "{}", code = 503) }
        // Recent still has a hold. The cache has the upload, so there is no wait.
        val retried = runBlocking { restart().lookup(MusicService.YOUTUBE_MUSIC, song, ExactMatcher.Retry(4_000)) }
        assertEquals(provisional, retried)
        assertEquals(false, (retried.match as ExactMatcher.Match.Found).lasting)
        assertEquals(saved, runBlocking { cache().entries() })
    }

    @Test
    fun aHoldFromRecentDoesNotNeedADiskWriteWhenItsClockIsRight() = runBlocking {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val file = kotlin.io.path.createTempDirectory("recent-hold").toFile().resolve("lookups.json")
        val cache = LookupCache(file, Dispatchers.Unconfined)
        val finder = ExactMatcher(fake.client(), "AR", cache = cache, now = { 5_000 }, wallNow = { 105_000 })
        val retry = ExactMatcher.Retry(100_000)

        assertEquals(ExactMatcher.Lookup(ExactMatcher.Match.WaitingForNames, retry), finder.lookup(MusicService.YOUTUBE_MUSIC, song, retry))
        assertTrue(fake.requestedUrls.isEmpty())
        assertTrue(cache.entries().isEmpty())
    }

    @Test
    fun restartingAfterTheClockMovesBackKeepsTheSavedHold() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val own = youTubeMusicRow(song.title, "ザ・ブルーハーツ - Topic • 1K views • 3:04", videoId = "own00000000")
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val file = kotlin.io.path.createTempDirectory("restart-clock").toFile().resolve("lookups.json")
        var elapsed = 4_000L
        var wall = 100_000L
        fun restart() = ExactMatcher(
            fake.client(), "AR", Dispatchers.Unconfined, LookupCache(file, Dispatchers.Unconfined),
            now = { elapsed }, wallNow = { wall }
        )
        namesAndTabs(videos = listOf(fan, own), apple = apple, failing = setOf("JP"))
        val pending = ExactMatcher.Lookup(ExactMatcher.Match.WaitingForNames, ExactMatcher.Retry(wall))
        assertEquals(pending, runBlocking { restart().lookup(MusicService.YOUTUBE_MUSIC, song) })

        elapsed += 10_000
        wall -= 60_000
        fake.requestedUrls.clear()
        val corrected = pending.copy(retry = ExactMatcher.Retry(wall))
        assertEquals(corrected, runBlocking { restart().lookup(MusicService.YOUTUBE_MUSIC, song, pending.retry) })
        repeat(2) {
            elapsed += 10_000
            wall += 10_000
            assertEquals(corrected, runBlocking { restart().lookup(MusicService.YOUTUBE_MUSIC, song, pending.retry) })
        }
        assertTrue(fake.requestedUrls.isEmpty())

        elapsed += 10_000
        wall += 10_000
        val upload = ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false)
        assertEquals(ExactMatcher.Lookup(upload, corrected.retry), runBlocking {
            restart().lookup(MusicService.YOUTUBE_MUSIC, song, pending.retry)
        })
    }

    @Test
    fun restartingAfterAFanUploadStillLetsTheArtistVideoReplaceIt() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val japan = mapOf("JP" to appleSong(song.title, "ザ・ブルーハーツ", 184_000))
        val own = youTubeMusicRow(song.title, "ザ・ブルーハーツ - Topic • 1K views • 3:04", videoId = "own00000000")
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val file = kotlin.io.path.createTempDirectory("restart-fan").toFile().resolve("lookups.json")
        var clock = 4_000L
        fun restart() = ExactMatcher(
            fake.client(), "AR", Dispatchers.Unconfined, LookupCache(file, Dispatchers.Unconfined),
            now = { clock }, wallNow = { clock }
        )
        val first = restart()
        namesAndTabs(videos = listOf(fan, own), apple = apple, elsewhere = japan, failing = setOf("JP"))
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { first.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        clock += ExactMatcher.UNSURE_FOR_MS
        val fanUrl = "https://music.youtube.com/watch?v=fan00000000"
        val stale = runBlocking { first.lookup(MusicService.YOUTUBE_MUSIC, song) }
        assertEquals(ExactMatcher.Match.Found(fanUrl, lasting = false), stale.match)
        namesAndTabs(videos = listOf(fan, own), apple = apple, elsewhere = japan)
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=own00000000"), runBlocking {
            restart().lookFor(MusicService.YOUTUBE_MUSIC, song)
        })
        namesAndTabs(videos = listOf(fan, own), apple = apple, elsewhere = japan, failing = setOf("JP"))
        fake.requestedUrls.clear()
        val ownUrl = "https://music.youtube.com/watch?v=own00000000"
        assertEquals(ExactMatcher.Lookup(ExactMatcher.Match.Found(ownUrl)), runBlocking {
            restart().lookup(MusicService.YOUTUBE_MUSIC, song, stale.retry, fanUrl)
        })
        assertEquals(ownUrl, runBlocking { restart().find(MusicService.YOUTUBE_MUSIC, song) })
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun aLateOutageAnswerKeepsTheConfirmedVideo() = runBlocking {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val japan = mapOf("JP" to appleSong(song.title, "ザ・ブルーハーツ", 184_000))
        val own = youTubeMusicRow(song.title, "ザ・ブルーハーツ - Topic • 1K views • 3:04", videoId = "own00000000")
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val file = kotlin.io.path.createTempDirectory("late-outage").toFile().resolve("lookups.json")
        val cache = LookupCache(file, Dispatchers.Unconfined)
        val finder = ExactMatcher(fake.client(), "AR", cache = cache, timeoutMs = 30_000)
        val fanUrl = "https://music.youtube.com/watch?v=fan00000000"
        namesAndTabs(videos = listOf(fan), apple = apple, failing = setOf("JP"))
        val provisional = ExactMatcher.Lookup(ExactMatcher.Match.Found(fanUrl, lasting = false), ExactMatcher.Retry())
        assertEquals(provisional, finder.lookup(MusicService.YOUTUBE_MUSIC, song))

        val started = CompletableDeferred<Unit>()
        val mayAnswer = CountDownLatch(1)
        val outage = fake.handler
        fake.handler = { request ->
            if (request.url.host == "itunes.apple.com" && request.url.queryParameter("country") == "JP") {
                started.complete(Unit)
                check(mayAnswer.await(30, SECONDS)) { "The late lookup never got its answer" }
            }
            outage(request)
        }
        // Recent still has an earlier hold, so saving the late answer would change the provisional entry.
        val late = async { finder.lookup(MusicService.YOUTUBE_MUSIC, song, ExactMatcher.Retry(4_000), fanUrl) }
        val ownUrl = "https://music.youtube.com/watch?v=own00000000"
        val confirmed = ExactMatcher.Lookup(ExactMatcher.Match.Found(ownUrl))
        try {
            withTimeout(10_000) { started.await() }
            namesAndTabs(videos = listOf(fan, own), apple = apple, elsewhere = japan)
            assertEquals(confirmed, finder.lookup(MusicService.YOUTUBE_MUSIC, song))
            assertEquals(ownUrl, cache.entries().single().second)
        } finally {
            mayAnswer.countDown()
        }

        val result = late.await()
        assertEquals(ownUrl, cache.entries().single().second)
        assertEquals(confirmed, result)
        assertEquals(true, (result.match as ExactMatcher.Match.Found).lasting)
    }

    @Test
    fun lateHoldAndFailureKeepTheProvisionalVideoSavedMeanwhile() = runBlocking {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val own = youTubeMusicRow(song.title, "ザ・ブルーハーツ - Topic • 1K views • 3:04", videoId = "own00000000")
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val file = kotlin.io.path.createTempDirectory("late-hold").toFile().resolve("lookups.json")
        val cache = LookupCache(file, Dispatchers.Unconfined)
        val finder = ExactMatcher(fake.client(), "AR", cache = cache, timeoutMs = 30_000)
        val storeStarted = CompletableDeferred<Unit>()
        val videosStarted = CompletableDeferred<Unit>()
        val failureStarted = CompletableDeferred<Unit>()
        val mayAnswer = CountDownLatch(1)
        namesAndTabs(videos = listOf(fan, own), apple = apple, failing = setOf("JP"))
        val outage = fake.handler
        fake.handler = { request ->
            if (request.url.host == "itunes.apple.com" && request.url.queryParameter("country") == "JP") {
                storeStarted.complete(Unit)
                check(mayAnswer.await(30, SECONDS))
            }
            if (FakeSpotify.bodyOf(request).contains(VIDEOS_TAB)) videosStarted.complete(Unit)
            outage(request)
        }
        val hold = async { finder.lookup(MusicService.YOUTUBE_MUSIC, song) }
        val provisional = ExactMatcher.Lookup(
            ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false), ExactMatcher.Retry()
        )
        val (failure, saved) = try {
            withTimeout(10_000) {
                storeStarted.await()
                videosStarted.await()
            }
            fake.handler = { request ->
                if (request.url.host == "music.youtube.com") {
                    failureStarted.complete(Unit)
                    check(mayAnswer.await(30, SECONDS))
                    FakeSpotify.html(request, "{}", code = 503)
                } else {
                    outage(request)
                }
            }
            val failed = async { finder.lookup(MusicService.YOUTUBE_MUSIC, song) }
            withTimeout(10_000) { failureStarted.await() }

            namesAndTabs(videos = listOf(fan), apple = apple, failing = setOf("JP"))
            assertEquals(provisional, finder.lookup(MusicService.YOUTUBE_MUSIC, song))
            failed to cache.entries()
        } finally {
            mayAnswer.countDown()
        }

        assertEquals(provisional, hold.await())
        assertEquals(provisional, failure.await())
        assertEquals(saved, cache.entries())
    }

    @Test
    fun overlappingLookupsShareTheHoldSavedMeanwhile() = runBlocking {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val own = youTubeMusicRow(song.title, "ザ・ブルーハーツ - Topic • 1K views • 3:04", videoId = "own00000000")
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val file = kotlin.io.path.createTempDirectory("shared-hold").toFile().resolve("lookups.json")
        val cache = LookupCache(file, Dispatchers.Unconfined)
        val finder = ExactMatcher(fake.client(), "AR", cache = cache, timeoutMs = 30_000, now = { 4_000 }, wallNow = { 100_000 })
        val firstStoreRequest = AtomicBoolean(true)
        val started = CompletableDeferred<Unit>()
        val mayAnswer = CountDownLatch(1)
        namesAndTabs(videos = listOf(fan, own), apple = apple, failing = setOf("JP"))
        val outage = fake.handler
        fake.handler = { request ->
            if (request.url.host == "itunes.apple.com" && request.url.queryParameter("country") == "JP" && firstStoreRequest.getAndSet(false)) {
                started.complete(Unit)
                check(mayAnswer.await(30, SECONDS))
            }
            outage(request)
        }
        val late = async { finder.lookup(MusicService.YOUTUBE_MUSIC, song) }
        val pending = ExactMatcher.Lookup(ExactMatcher.Match.WaitingForNames, ExactMatcher.Retry(100_000))
        val saved = try {
            withTimeout(10_000) { started.await() }
            assertEquals(pending, finder.lookup(MusicService.YOUTUBE_MUSIC, song))
            cache.entries()
        } finally {
            mayAnswer.countDown()
        }
        assertEquals(pending, late.await())
        assertEquals(saved, cache.entries())
    }

    @Test
    fun aLateFailureReturnsTheHoldSavedMeanwhile() = runBlocking {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val own = youTubeMusicRow(song.title, "ザ・ブルーハーツ - Topic • 1K views • 3:04", videoId = "own00000000")
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val file = kotlin.io.path.createTempDirectory("late-failed-hold").toFile().resolve("lookups.json")
        val cache = LookupCache(file, Dispatchers.Unconfined)
        val finder = ExactMatcher(fake.client(), "AR", cache = cache, timeoutMs = 30_000, now = { 4_000 }, wallNow = { 100_000 })
        val started = CompletableDeferred<Unit>()
        val mayAnswer = CountDownLatch(1)
        fake.handler = { request ->
            started.complete(Unit)
            check(mayAnswer.await(30, SECONDS))
            FakeSpotify.html(request, "{}", code = 503)
        }
        val late = async { finder.lookup(MusicService.YOUTUBE_MUSIC, song) }
        val pending = ExactMatcher.Lookup(ExactMatcher.Match.WaitingForNames, ExactMatcher.Retry(100_000))
        val saved = try {
            withTimeout(10_000) { started.await() }
            namesAndTabs(videos = listOf(fan, own), apple = apple, failing = setOf("JP"))
            assertEquals(pending, finder.lookup(MusicService.YOUTUBE_MUSIC, song))
            cache.entries()
        } finally {
            mayAnswer.countDown()
        }
        assertEquals(pending, late.await())
        assertEquals(saved, cache.entries())
    }

    @Test
    fun aSecondLookupWaitsForTheSavedRetryToReachDisk() = runBlocking {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val file = kotlin.io.path.createTempDirectory("retry-flush").toFile().resolve("lookups.json")
        val disk = PausedCacheDisk()
        val cache = LookupCache(file, disk)
        cache.entries()
        val finder = ExactMatcher(fake.client(), "AR", cache = cache, missingForMs = 60_000, now = { 4_000 })
        namesAndTabs(videos = listOf(fan), apple = apple, failing = setOf("JP"))
        val expected = ExactMatcher.Lookup(
            ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false), ExactMatcher.Retry()
        )
        disk.pause()
        val first = async(Dispatchers.Default) { finder.lookup(MusicService.YOUTUBE_MUSIC, song) }
        val second = try {
            disk.awaitWrite()
            fake.requestedUrls.clear()
            val retry = async(start = CoroutineStart.UNDISPATCHED) {
                finder.lookup(MusicService.YOUTUBE_MUSIC, song).also {
                    assertEquals(cache.entries(), LookupCache(file, Dispatchers.Unconfined).entries())
                }
            }
            assertTrue("The second lookup waits for the pending write", !retry.isCompleted)
            assertTrue("The provisional upload is still only in memory", !file.exists())
            retry
        } finally {
            disk.resume()
        }
        assertEquals(expected, second.await())
        assertEquals(expected, first.await())
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun anUpgradeWritesOverTheProvisionalAnswerBeforeReturning() = runBlocking {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val japan = mapOf("JP" to appleSong(song.title, "ザ・ブルーハーツ", 184_000))
        val own = youTubeMusicRow(song.title, "ザ・ブルーハーツ - Topic • 1K views • 3:04", videoId = "own00000000")
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val file = kotlin.io.path.createTempDirectory("upgrade-flush").toFile().resolve("lookups.json")
        val disk = PausedCacheDisk()
        val finder = ExactMatcher(fake.client(), "AR", cache = LookupCache(file, disk))
        namesAndTabs(videos = listOf(fan), apple = apple, failing = setOf("JP"))
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false), finder.lookFor(MusicService.YOUTUBE_MUSIC, song))
        val provisional = LookupCache(file, Dispatchers.Unconfined).entries()

        namesAndTabs(videos = listOf(fan, own), apple = apple, elsewhere = japan)
        val ownUrl = "https://music.youtube.com/watch?v=own00000000"
        disk.pause()
        val confirmed = async(Dispatchers.Default) {
            finder.lookFor(MusicService.YOUTUBE_MUSIC, song).also {
                assertEquals(ownUrl, LookupCache(file, Dispatchers.Unconfined).entries().single().second)
            }
        }
        try {
            disk.awaitWrite()
            assertTrue("The upgrade waits for the confirmed link to be written", !confirmed.isCompleted)
            assertEquals(provisional, LookupCache(file, Dispatchers.Unconfined).entries())
        } finally {
            disk.resume()
        }
        assertEquals(ExactMatcher.Match.Found(ownUrl), confirmed.await())
    }

    @Test
    fun aFreshConfirmedAnswerCanReturnWhileItsCacheWriteWaits() = runBlocking {
        val song = MusicMetadata("Different Song", "Different Artist")
        val url = "https://www.deezer.com/track/1"
        val file = kotlin.io.path.createTempDirectory("fresh-answer").toFile().resolve("lookups.json")
        val disk = PausedCacheDisk()
        val cache = LookupCache(file, disk)
        cache.entries()
        val finder = ExactMatcher(fake.client(), "AR", cache = cache)
        namesAndTabs(deezer = """[{"title":"${song.title}","link":"$url","artist":{"name":"${song.artist}"}}]""")

        disk.pause()
        try {
            val found = lookUpWhileDiskWaits { finder.lookFor(MusicService.DEEZER, song) }
            assertEquals(ExactMatcher.Match.Found(url), found)
            assertEquals(url, cache.entries().single().second)
            assertTrue(!file.exists())
        } finally {
            disk.resume()
        }
    }

    @Test
    fun anotherSongCanMatchWhileAnOutageAnswerFlushes() = runBlocking {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val other = MusicMetadata("Different Song", "Different Artist")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val file = kotlin.io.path.createTempDirectory("slow-flush").toFile().resolve("lookups.json")
        val disk = PausedCacheDisk()
        val cache = LookupCache(file, disk)
        cache.entries()
        val finder = ExactMatcher(fake.client(), "AR", cache = cache)
        namesAndTabs(
            videos = listOf(fan), apple = apple, failing = setOf("JP"),
            deezer = """[{"title":"Different Song","link":"https://www.deezer.com/track/1","artist":{"name":"Different Artist"}}]"""
        )
        disk.pause()
        val upload = async(Dispatchers.Default) { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) }
        try {
            disk.awaitWrite()
            val known = lookUpWhileDiskWaits { finder.lookFor(MusicService.DEEZER, other) }
            assertEquals(ExactMatcher.Match.Found("https://www.deezer.com/track/1"), known)
        } finally {
            disk.resume()
        }
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false), upload.await())
    }

    private suspend fun CoroutineScope.lookUpWhileDiskWaits(lookUp: suspend () -> ExactMatcher.Match): ExactMatcher.Match {
        val finished = CountDownLatch(1)
        val answer = async(Dispatchers.Default) { lookUp() }
        answer.invokeOnCompletion { finished.countDown() }
        assertTrue("The lookup should return before disk writes resume", finished.await(5, SECONDS))
        return answer.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class PausedCacheDisk : CoroutineDispatcher() {
        private val disk = StandardTestDispatcher()
        private val pauseLock = Any()
        private val writing = CountDownLatch(1)
        private var paused = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(pauseLock) {
                if (paused) {
                    disk.dispatch(context, block)
                    writing.countDown()
                    return
                }
            }
            Dispatchers.Default.dispatch(context, block)
        }

        fun pause() = synchronized(pauseLock) { paused = true }

        fun awaitWrite() {
            assertTrue("The lookup should reach the paused disk", writing.await(5, SECONDS))
        }

        fun resume() {
            synchronized(pauseLock) { paused = false }
            disk.scheduler.runCurrent()
        }
    }

    @Test
    fun aHealthyMissClearsTheSavedOutageRetry() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val fan = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val file = kotlin.io.path.createTempDirectory("restart-miss").toFile().resolve("lookups.json")
        fun cache() = LookupCache(file, Dispatchers.Unconfined)
        fun restart() = ExactMatcher(fake.client(), "AR", Dispatchers.Unconfined, cache())
        namesAndTabs(videos = listOf(fan), apple = apple, failing = setOf("JP"))
        assertEquals("https://music.youtube.com/watch?v=fan00000000", runBlocking { restart().find(MusicService.YOUTUBE_MUSIC, song) })
        namesAndTabs(videos = emptyList(), apple = apple)
        assertEquals(ExactMatcher.Match.Missing, runBlocking { restart().lookFor(MusicService.YOUTUBE_MUSIC, song) })
        assertTrue(runBlocking { cache().entries().isEmpty() })
    }

    @Test
    fun anArtistVideoCanNeedBothNamesFromTheFailedLookup() {
        val song = MusicMetadata("Owaranai Uta", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val japanese = mapOf("JP" to appleSong("終わらない歌", "ザ・ブルーハーツ", 184_000))
        val own = youTubeMusicRow("終わらない歌", "ザ・ブルーハーツ • 1M views • 4:30", videoId = "own00000000")
        val fan = youTubeMusicRow("THE BLUE HEARTS - Owaranai Uta", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val other = youTubeMusicRow("別の歌", "ザ・ブルーハーツ • 1M views • 4:30", videoId = "other000000")
        namesAndTabs(videos = listOf(other, fan), apple = apple, elsewhere = japanese, failing = setOf("JP"))
        assertEquals("https://music.youtube.com/watch?v=fan00000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })

        val finder = ExactMatcher(fake.client(), "AR", pick = { _, _, _ -> error("Wait for the store before picking") })
        namesAndTabs(videos = listOf(own, fan), apple = apple, elsewhere = japanese, failing = setOf("JP"))
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        namesAndTabs(videos = listOf(own, fan), apple = apple, elsewhere = japanese)
        assertEquals("https://music.youtube.com/watch?v=own00000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun aJapaneseSongWaitsForJapansNameEvenWhenKoreaWritesItsArtistInHangul() {
        val song = MusicMetadata("Owaranai Uta", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val fans = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val own = youTubeMusicRow("終わらない歌", "ザ・ブルーハーツ • Album • 3:04", videoId = "song0000000")
        val names = mapOf(
            "TW" to appleSong("終わらない歌", song.artist, 184_000),
            "KR" to appleSong(song.title, "더 블루 하츠", 184_000),
            "JP" to appleSong("終わらない歌", "ザ・ブルーハーツ", 184_000)
        )
        var clock = 0L
        val finder = ExactMatcher(fake.client(), "AR", missingForMs = 60 * 60_000L, now = { clock })
        namesAndTabs(songs = listOf(own), videos = listOf(fans), apple = apple, elsewhere = names, failing = setOf("JP"))
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })

        fake.requestedUrls.clear()
        namesAndTabs(songs = listOf(own), videos = listOf(fans), apple = apple, elsewhere = names)
        clock = ExactMatcher.UNSURE_FOR_MS - 1
        assertEquals(ExactMatcher.Match.WaitingForNames, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        assertTrue(fake.requestedUrls.isEmpty())
        clock++
        assertEquals("https://music.youtube.com/watch?v=song0000000", runBlocking { finder.find(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun aFansUploadFoundWhileANameLookupFailedIsAskedAgain() {
        val song = MusicMetadata("終わらない歌", "THE BLUE HEARTS")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val fans = youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val own = youTubeMusicRow("終わらない歌", "THE BLUE HEARTS • 1M views • 3:04", videoId = "own00000000")
        var clock = 0L
        val finder = ExactMatcher(fake.client(), "AR", missingForMs = 60 * 60_000L, now = { clock })
        val upload = ExactMatcher.Match.Found("https://music.youtube.com/watch?v=fan00000000", lasting = false)
        namesAndTabs(videos = listOf(fans), apple = apple, failing = setOf("JP"))
        assertEquals(upload, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })

        fake.requestedUrls.clear()
        namesAndTabs(videos = listOf(fans, own), apple = apple, failing = setOf("JP"))
        clock = ExactMatcher.UNSURE_FOR_MS - 1
        assertEquals(upload, runBlocking { finder.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        assertTrue(fake.requestedUrls.isEmpty())
        clock++
        assertEquals("https://music.youtube.com/watch?v=own00000000", runBlocking { finder.find(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun aFansUploadStillNeedsAnAnswerForItsLength() {
        val song = MusicMetadata("Owaranai Uta", "THE BLUE HEARTS")
        val fans = youTubeMusicRow("THE BLUE HEARTS - Owaranai Uta", "Fan • 1K views • 3:04", videoId = "fan00000000")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, lengthMs = 0))
        namesAndTabs(videos = listOf(fans), apple = apple, deezerDown = true)
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher().lookFor(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun aWordForAnotherVersionInTheSongsOwnNameDoesntCountAgainstIt() {
        val song = MusicMetadata("ギターと孤独と蒼い惑星", "Kessoku Band")
        // Its name elsewhere doesn't have the word, but this one does, so it counts.
        namesAndTabs(
            videos = listOf(
                youTubeMusicRow("Kessoku Band - ギターと孤独と蒼い惑星 弾いてみた", "Fan • 1K views • 3:48", videoId = "played00000"),
                youTubeMusicRow("Kessoku Band - ギターと孤独と蒼い惑星 (ライブ)", "Fan • 1K views • 3:48", videoId = "live0000000"),
                youTubeMusicRow("Kessoku Band - ギターと孤独と蒼い惑星", "Fan • 1K views • 3:48", videoId = "song0000000")
            ),
            apple = mapOf("AR" to appleSong("ギターと孤独と蒼い惑星", "Kessoku Band", 228_000)),
            elsewhere = mapOf("KR" to appleSong("Guitar, Loneliness and Blue Planet", "Kessoku Band", 228_000))
        )
        assertEquals("https://music.youtube.com/watch?v=song0000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun aWordForAnotherVersionInTheArtistsNameDoesntCountAgainstIt() {
        // "Mix" says a remix, but not in Little Mix's own video.
        val song = MusicMetadata("Shout Out to My Ex", "Little Mix")
        namesAndTabs(
            videos = listOf(youTubeMusicRow("Little Mix - Shout Out To My Ex (Official Video)", "Little Mix • 300M views • 4:06", videoId = "littlemix00")),
            apple = mapOf("AR" to appleSong("Shout Out to My Ex", "Little Mix", 246_000))
        )
        assertEquals("https://music.youtube.com/watch?v=littlemix00", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun peaceSignOpensItsOfficialVideoWithBothTitlesAndCredits() {
        val song = MusicMetadata("Peace Sign", "米津玄師", lengthMs = 238_525)
        val fan = youTubeMusicRow("米津玄師 (Kenshi Yonezu)「ピースサイン」(Peace Sign)", "IRyS Singing Archive • 4 views • 3:58", videoId = "LlqIAiDzgpM")
        val fanUrl = "https://music.youtube.com/watch?v=LlqIAiDzgpM"
        fun finds(vararg artistVideos: String): String? {
            namesAndTabs(
                videos = artistVideos.toList() + fan,
                apple = mapOf("US" to appleSong("Peace Sign", "Kenshi Yonezu", 238_525)),
                elsewhere = mapOf("JP" to appleSong("ピースサイン - Peace Sign", "米津玄師", 238_525))
            )
            return runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song) }
        }
        fun official(name: String) = youTubeMusicRow(name, "Kenshi Yonezu • 346M views • 4:25", videoId = "9aJVr5tTTWk")

        assertEquals(fanUrl, finds())
        assertEquals(fanUrl, finds(official("米津玄師 - ピースサイン別曲 , Kenshi Yonezu - Peace Sign")))
        assertEquals(fanUrl, finds(official("米津玄師 - ピースサイン , Kenshi Yonezu - Peace Sign (Live)")))
        assertEquals("https://music.youtube.com/watch?v=9aJVr5tTTWk", finds(official("米津玄師 - ピースサイン , Kenshi Yonezu - Peace Sign")))
    }

    @Test
    fun aShortBilingualAliasDoesNotEraseARepeatedTitle() {
        assertNull(findsLoveVideo("愛 - Love", "Band - 愛愛 (Love)", "Band"))
    }

    @Test
    fun aShortBilingualAliasDoesNotEraseARepeatedTitleOnAFanUpload() {
        assertNull(findsLoveVideo("愛 - Love", "Band - 愛愛 (Love)", "Fan"))
    }

    @Test
    fun aRemasterSuffixDoesNotNameAnotherSong() {
        assertNull(findsLoveVideo("Love - Remastered 2024", "Remastered 2024 - 別曲 (Love)", "Band"))
    }

    @Test
    fun aPlainDashSuffixDoesNotNameAnotherSong() {
        assertNull(findsLoveVideo("Love - Sunset", "Sunset - 別曲 (Love)", "Band"))
    }

    @Test
    fun repeatingBothCreditsCanNameAWholeBilingualTitleEitherWay() {
        val url = "https://music.youtube.com/watch?v=video000000"
        assertEquals(url, findsLoveVideo("愛 - Love", "Band - 愛 , Band - Love", "Band"))
        assertEquals(url, findsLoveVideo("愛 - Love", "Band - Love，Band - 愛", "Band"))
        assertEquals(url, findsLoveVideo("愛 - Love", "Band - 愛 , Band - Love", "Fan"))
    }

    @Test
    fun repeatingCreditsCanProveAnAsciiParenthesizedCatalogAlias() {
        val url = "https://music.youtube.com/watch?v=video000000"
        assertEquals(url, findsLoveVideo("愛 (Love)", "Band - 愛 , Band - Love", "Band"))
    }

    @Test
    fun repeatingCreditsCanProveAFullWidthParenthesizedCatalogAlias() {
        val url = "https://music.youtube.com/watch?v=video000000"
        assertEquals(url, findsLoveVideo("愛（Love）", "Band - 愛 , Band - Love", "Band"))
    }

    @Test
    fun repeatingCreditsCanProveAParenthesizedAliasWithANeutralTag() {
        val url = "https://music.youtube.com/watch?v=video000000"
        assertEquals(url, findsLoveVideo("愛 (Love (Original))", "Band - 愛 , Band - Love", "Band"))
    }

    @Test
    fun repeatingAPlainKnownNameStillOpensTheVideo() {
        val url = "https://music.youtube.com/watch?v=video000000"
        assertEquals(url, findsLoveVideo("愛 (Love)", "Band - Love , Band - Love", "Band"))
    }

    @Test
    fun aNameStartingWithAParenthesisHasNoBaseBeforeIt() {
        val url = "https://music.youtube.com/watch?v=video000000"
        assertEquals(url, findsLoveVideo("(Love)", "Band -  , Band - Love", "Band"))
    }

    @Test
    fun parenthesizedNotesAndPartialNamesAreNotProvenAliases() {
        assertNull(findsLoveVideo("愛 (Love)", "Band - 愛 , Other - Love", "Band"))
        listOf("Love (Live)", "Love (Remix)", "Love (Disco Version)", "Love (Instrumental)", "Love feat. Guest", "Love (feat. Guest)")
            .forEach { assertNull(it, findsLoveVideo("愛 ($it)", "Band - 愛 , Band - Love", "Band")) }
        assertNull(findsLoveVideo("愛 (Love (Sunset))", "Band - 愛 , Band - Love", "Band"))
    }

    @Test
    fun creditedDiscoVersionDoesNotBecomeAParenthesizedAlias() {
        assertNull(findsLoveVideo("愛 (Love Disco Version)", "Band - Love Disco Version , Band - 愛", "Band", "Love Disco Version", "7:00"))
    }

    @Test
    fun officialVideoNoteDoesNotHideAnExistingWholeTitleMatch() {
        val url = "https://music.youtube.com/watch?v=video000000"
        assertEquals(url, findsLoveVideo("愛 (Love)", "Band - 愛 , Band - Love (Official Video)", "Band", "愛"))
    }

    @Test
    fun recordingLabelsInsideNestedRemastersAreNotNeutral() {
        listOf("TV Size Remastered", "Disco Version Remastered").forEach {
            assertNull(it, findsLoveVideo("愛 (Love ($it))", "Band - 愛 , Band - Love", "Band"))
        }
    }

    @Test
    fun parenthesizedAliasesNeedBalancedMatchingBrackets() {
        val url = "https://music.youtube.com/watch?v=video000000"
        assertNull(findsLoveVideo("愛 (Love (Original] )", "Band - 愛 , Band - Love", "Band"))
        assertNull(findsLoveVideo("愛 (Love）", "Band - 愛 , Band - Love", "Band"))
        assertEquals(url, findsLoveVideo("愛（Love（Original））", "Band - 愛 , Band - Love", "Band"))
    }

    @Test
    fun catalogTakeWordsCanBePartOfTheNativeName() {
        val url = "https://music.youtube.com/watch?v=video000000"
        listOf("愛 TVサイズ", "愛 TV Size", "愛 ディスコ", "愛 Live", "愛 Remix", "愛 Instrumental", "愛 カラオケ").forEach { name ->
            assertEquals(name, url, findsLoveVideo("$name (Love)", "Band - $name , Band - Love", "Band"))
            assertEquals(name, url, findsLoveVideo("$name (Love)", "Band - Love , Band - $name", "Band"))
        }
    }

    @Test
    fun aBareDiscoWordCanBePartOfTheNativeName() {
        assertEquals("https://music.youtube.com/watch?v=video000000", findsLoveVideo("愛 Disco (Love)", "Band - 愛 Disco , Band - Love", "Band"))
    }

    @Test
    fun aFeaturingCreditFollowsThePlainVideoCheck() {
        assertNull(findsLoveVideo("愛 feat. Guest (Love)", "Band - 愛 feat. Guest , Band - Love", "Band"))
    }

    @Test
    fun anotherStoreNamingTheGuestDoesNotProveTheNativeName() {
        listOf("feat.", "ft.", "featuring").forEach { credit ->
            val name = "愛 $credit Guest"
            assertNull(credit, findsLoveVideo("$name (Love)", "Band - $name , Band - Love", "Band", "Guest"))
        }
    }

    @Test
    fun anEarlierNewAliasMatchDoesNotReplaceTheExistingOfficialVideo() {
        val fan = youTubeMusicRow("Band - 愛 , Band - Love", "Fan • 1K views • 4:00", videoId = "fan00000000")
        val official = youTubeMusicRow("Band - Love (Official Video)", "Band • 1K views • 4:00", videoId = "official000")
        namesAndTabs(
            videos = listOf(fan, official),
            apple = mapOf("US" to appleSong("Love", "Band", 240_000)),
            elsewhere = mapOf("JP" to appleSong("愛 (Love)", "Band", 240_000))
        )
        assertEquals("https://music.youtube.com/watch?v=official000", runBlocking {
            matcher("US").find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Love", "Band", lengthMs = 240_000))
        })
    }

    @Test
    fun compatibilityAndCjkClosingBracketsCannotHideInANativeName() {
        listOf("愛﹚", "愛」").forEach { name ->
            assertNull(name, findsLoveVideo("$name (Love)", "Band - $name , Band - Love", "Band"))
        }
    }

    @Test
    fun creditWordsFollowThePlainVideoCheck() {
        val accepted = setOf("with", "prod", "x", "×", "&", "and", "vs", "と", "와", "和")
        val rejected = setOf("feat.", "ft.", "featuring")
        (accepted + rejected).forEach { credit ->
            val expected = "https://music.youtube.com/watch?v=video000000".takeIf { credit in accepted }
            assertEquals(credit, expected, findsLoveVideo("Love $credit Guest", "Band - Love $credit Guest", "Band"))
            assertEquals(credit, expected, findsLoveVideo("愛 $credit Guest (Love)", "Band - 愛 $credit Guest , Band - Love", "Band"))
        }
    }

    @Test
    fun conjunctionsCanBePartOfTheNativeTitle() {
        val url = "https://music.youtube.com/watch?v=video000000"
        listOf("&", "and").forEach { word ->
            assertEquals(word, url, findsLoveVideo("Love $word Peace", "Band - Love $word Peace", "Band"))
            assertEquals(word, url, findsLoveVideo("愛 $word 平和 (Love)", "Band - 愛 $word 平和 , Band - Love", "Band"))
        }
    }

    @Test
    fun compatibilityEquivalentNamesDoNotProveTwoTitles() {
        assertNull(findsLoveVideo("Ｌｏｖｅ (Love)", "Band - Ｌｏｖｅ , Band - Love", "Band"))
    }

    @Test
    fun anEmptyNativeNameIsNotAnAliasEvenWithCompatibilityWidthCredits() {
        assertNull(findsLoveVideo("(Love)", "Ｂａｎｄ -  , Band - Love", "Band"))
    }

    @Test
    fun anEmptyNativeNameIsNotProvedByAWordInTheArtist() {
        namesAndTabs(
            videos = listOf(youTubeMusicRow("Ｌｏｖｅ Band -  , Love Band - Love", "Love Band • 1K views • 4:00", videoId = "video000000")),
            apple = mapOf("US" to appleSong("Love", "Love Band", 240_000)),
            elsewhere = mapOf("JP" to appleSong("(Love)", "Love Band", 240_000))
        )
        assertNull(runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Love", "Love Band", lengthMs = 240_000)) })
    }

    @Test
    fun plainRecordingWordsKeepTheirExistingVideoMatches() {
        val words = listOf("ライヴ", "アコースティック", "Off Vocal", "TV edit", "DiscoVersion", "Remastered 2020")
        words.forEach { word ->
            assertEquals(word, "https://music.youtube.com/watch?v=video000000", findsLoveVideo("Love $word", "Band - Love $word", "Band"))
        }
    }

    @Test
    fun anExplicitlyRequestedTakeCanAppearInBothNames() {
        val title = "Love TVサイズ"
        namesAndTabs(
            videos = listOf(youTubeMusicRow("Band - 愛 TVサイズ , Band - $title", "Band • 1K views • 4:00", videoId = "video000000")),
            apple = mapOf("US" to appleSong(title, "Band", 240_000)),
            elsewhere = mapOf("JP" to appleSong("愛 TVサイズ ($title)", "Band", 240_000))
        )
        assertEquals("https://music.youtube.com/watch?v=video000000", runBlocking {
            matcher("US").find(MusicService.YOUTUBE_MUSIC, MusicMetadata(title, "Band", lengthMs = 240_000))
        })
    }

    @Test
    fun mismatchedNestedAndStrayBracketsDoNotProveAliases() {
        listOf("愛 (Love (Original）)", "愛（Love（Original)）", "愛] (Love)").forEach {
            assertNull(it, findsLoveVideo(it, "Band - 愛 , Band - Love", "Band"))
        }
    }

    @Test
    fun anotherCatalogNameIsNotTheRequestedAlias() {
        assertNull(findsLoveVideo("愛 (Sunset)", "Band - 愛 , Band - Sunset", "Band", "Sunset"))
    }

    @Test
    fun extraCreditedNameMustBeProved() = assertNull(findsLoveVideo("愛 (Sunset)", "Band - 愛 , Band - Love , Band - Sunset", "Band", "愛"))

    @Test
    fun officialNoteDoesNotProveExtraCreditedName() =
        assertNull(findsLoveVideo("愛 (Sunset)", "Band - 愛 , Band - Love , Band - Sunset (Official Video)", "Band", "愛"))

    @Test
    fun repeatedCreditDoesNotProveExtraCreditedName() =
        assertNull(findsLoveVideo("愛 (Sunset)", "Band - 愛 , Band - Love , Band - Love , Band - Sunset", "Band", "愛"))

    @Test
    fun independentlyKnownCreditedNamesCanRepeat() {
        val url = "https://music.youtube.com/watch?v=video000000"
        assertEquals(url, findsLoveVideo("愛 (Sunset)", "Band - 愛 , Band - Love , Band - Sunset (Official Video)", "Band", "愛", thirdTitle = "Sunset"))
    }

    @Test
    fun aShortParenthesizedNameDoesNotEraseARepeatedTitle() {
        assertNull(findsLoveVideo("愛 (Love)", "Band - 愛愛 , Band - Love", "Band"))
        assertNull(findsLoveVideo("愛 (Love)", "Band - 愛愛 , Band - Love", "Band", "愛"))
    }

    private fun findsCreditedVideo(request: String, otherNames: List<String>, vararg titles: String): String? {
        namesAndTabs(
            videos = titles.mapIndexed { index, title -> youTubeMusicRow(title, "Band • 1K views • 4:00", videoId = "video${index.toString().padStart(6, '0')}") },
            apple = mapOf("US" to appleSong(request, "Band", 240_000)),
            elsewhere = otherNames.zip(listOf("JP", "KR", "TW")).associate { (name, store) -> store to appleSong(name, "Band", 240_000) }
        )
        return runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, MusicMetadata(request, "Band", lengthMs = 240_000)) }
    }

    @Test
    fun mixedCreditSeparatorsDoNotSkipUnprovedNames() {
        val second = "https://music.youtube.com/watch?v=video000001"
        val mixed = "Band - 愛, Band - Love, Band – Sunset"
        assertEquals(second, findsCreditedVideo("Love", listOf("愛 (Sunset)", "愛"), mixed, "Band - Love"))
        val otherArtist = "Band - Love, Other – Sunset"
        assertEquals(second, findsCreditedVideo("Love", listOf("愛 (Sunset)", "愛"), otherArtist, "Band - Love"))
        val tagAsTitle = "Band - Love, Band - Video"
        assertEquals(second, findsCreditedVideo("Love", listOf("愛"), tagAsTitle, "Band - Love"))
        for (dash in listOf(" - ", " – ", " — ")) {
            for (suffix in listOf("", " (Official Video)")) {
                val original = "Band${dash}愛, Band${dash}Love, Band${dash}Sunset$suffix"
                assertNull(original, findsCreditedVideo("Love", listOf("愛 (Sunset)", "愛"), original))
            }
            val repeated = "Band${dash}愛, Band${dash}Love, Band${dash}Love, Band${dash}Sunset"
            assertNull(repeated, findsCreditedVideo("Love", listOf("愛 (Sunset)", "愛"), repeated))
            for (separator in listOf(", ", " / ", " | ", " ・ ")) {
                val first = "Band${dash}愛${separator}Band${dash}Love${separator}Band${dash}Sunset"
                assertEquals(first, second, findsCreditedVideo("Love", listOf("愛 (Sunset)", "愛"), first, "Band - Love"))
            }
            val first = "Band${dash}愛平和 / Band${dash}Love"
            assertEquals(first, second, findsCreditedVideo("Love", listOf("愛", "平和"), first, "Band - Love"))
            val trailing = "Band${dash}愛, Band${dash}Love, Band${dash}Sunset,"
            assertEquals(trailing, second, findsCreditedVideo("Love", listOf("愛 (Sunset)", "愛"), trailing, "Band - Love"))
        }
    }

    @Test
    fun unbracketedVideoNotesDoNotHideKnownCreditedNames() {
        val url = "https://music.youtube.com/watch?v=video000000"
        for (dash in listOf(" - ", " – ", " — ")) for (note in listOf("Official Video", "MV", "Lyric Video", "公式", "2024")) {
            val title = "Band${dash}愛, Band${dash}Love $note"
            assertEquals(title, url, findsCreditedVideo("Love", listOf("愛"), title))
        }
    }

    @Test
    fun wholeCatalogMedleysProveJoinedCredits() {
        val url = "https://music.youtube.com/watch?v=video000000"
        for (dash in listOf(" - ", " – ", " — ")) for (name in listOf("Love, Peace (Medley)", "Love / Peace (Mashup)")) {
            val title = "Band${dash}Love, Band${dash}Peace ${name.substringAfter('(').let { "($it" }}"
            assertEquals(title, url, findsCreditedVideo(name, emptyList(), title))
        }
    }

    @Test
    fun fullBracketedCatalogNameProvesACredit() {
        val url = "https://music.youtube.com/watch?v=video000000"
        for (dash in listOf(" - ", " – ", " — ")) {
            val title = "Band${dash}Love (Amor), Band${dash}愛"
            assertEquals(title, url, findsCreditedVideo("Love (Amor)", listOf("愛"), title))
        }
    }

    @Test
    fun separatorsInsideAKnownTitleAreNotSeparateCredits() {
        val url = "https://music.youtube.com/watch?v=video000000"
        for (separator in listOf("・", "/", "|", ",")) {
            val name = "愛${separator}平和"
            val title = "Band - $name, Band - Love"
            assertEquals(title, url, findsCreditedVideo("Love", listOf(name), title))
        }
    }

    @Test
    fun catalogWhitespaceDoesNotHideAParenthesizedAlias() {
        val url = "https://music.youtube.com/watch?v=video000000"
        assertEquals(url, findsLoveVideo("愛 (Love) ", "Band - 愛 , Band - Love", "Band"))
    }

    @Test
    fun compatibilityWidthDoesNotHideTheRequestedAlias() {
        val url = "https://music.youtube.com/watch?v=video000000"
        assertEquals(url, findsLoveVideo("愛 (Ｌｏｖｅ)", "Band - 愛 , Band - Love", "Band"))
    }

    @Test
    fun knownNativeTitleKeepsAnOfficialVideoNoteOpen() {
        assertEquals("https://music.youtube.com/watch?v=video000000", findsLoveVideo("愛 (Love)", "Band - 愛 , Band - Love (Official Video)", "Band", "愛 (Original)"))
    }

    @Test
    fun existingDashPairsKeepRecordingWordsInTheRequestedTitle() {
        listOf("Tour de France", "Mix It Up").forEach { title ->
            namesAndTabs(
                videos = listOf(youTubeMusicRow("Band - 愛 , Band - $title", "Band • 1K views • 4:00", videoId = "video000000")),
                apple = mapOf("US" to appleSong(title, "Band", 240_000)),
                elsewhere = mapOf("JP" to appleSong("愛 - $title", "Band", 240_000))
            )
            assertEquals(title, "https://music.youtube.com/watch?v=video000000", runBlocking {
                matcher("US").find(MusicService.YOUTUBE_MUSIC, MusicMetadata(title, "Band", lengthMs = 240_000))
            })
        }
    }

    @Test
    fun commaInTitleAndDiscoInArtistKeepTheExistingVideoMatch() {
        namesAndTabs(
            videos = listOf(youTubeMusicRow("Disco Mix - Love, Again", "Disco Mix • 1K views • 4:00", videoId = "video000000")),
            apple = mapOf("US" to appleSong("Love, Again", "Disco Mix", 240_000))
        )
        assertEquals("https://music.youtube.com/watch?v=video000000", runBlocking {
            matcher("US").find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Love, Again", "Disco Mix"))
        })
    }

    @Test
    fun repeatingACreditNeedsExactlyTwoWholeKnownNamesAndCredits() {
        val wrong = listOf(
            "別人 - 愛 , Band - Love",
            "Band - 愛 , 別人 - Love",
            "Band - 愛 , Band Love",
            "Band - 愛 , Band - Love , Band - Love",
            "Band - 愛愛 , Band - Love"
        )
        wrong.forEach { assertNull(it, findsLoveVideo("愛 - Love", it, "Band")) }
    }

    @Test
    fun repeatingACreditDoesNotMakeTwoNamesInTheSameScriptOrANumberBilingual() {
        assertNull(findsLoveVideo("愛 (Love) - 別曲", "Band - 愛 (Love) , Band - 別曲", "Band"))
        assertNull(findsLoveVideo("愛 (Love) - 1984", "Band - 愛 (Love) , Band - 1984", "Band"))
    }

    @Test
    fun repeatingACreditDoesNotMakeAnEditionOrATakeAName() {
        assertNull(findsLoveVideo("愛 (Love) - Remastered 2024", "Band - 愛 (Love) , Band - Remastered 2024", "Band"))
        assertNull(findsLoveVideo("Live - 愛 (Love)", "Band - 愛 (Love) , Band - Live", "Band"))
    }

    private fun findsLoveVideo(
        storeTitle: String, videoTitle: String, channel: String, extraTitle: String? = null, length: String = "4:00", thirdTitle: String? = null
    ): String? {
        namesAndTabs(
            videos = listOf(youTubeMusicRow(videoTitle, "$channel • 1K views • $length", videoId = "video000000")),
            apple = mapOf("US" to appleSong("Love", "Band", 240_000)),
            elsewhere = mapOf("JP" to appleSong(storeTitle, "Band", 240_000)) + listOfNotNull(
                extraTitle?.let { "KR" to appleSong(it, "Band", 240_000) }, thirdTitle?.let { "TW" to appleSong(it, "Band", 240_000) }
            )
        )
        return runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Love", "Band", lengthMs = 240_000)) }
    }

    @Test
    fun bayCityDoesNotReplaceTheAlbumEditionWithTheShorterSingle() {
        val title = "tasogare no bay city"
        val metadata = MusicMetadata(title, "八神純子", lengthMs = 281_200)
        val single = youTubeMusicRow("黄昏のBAY CITY", "八神純子 • 黄昏のBAY CITY • 4:11", videoId = "QLHMhVonF-s")
        val fan = youTubeMusicRow("Junko Yagami - 黄昏のBay City", "Vladimir Putin (Jummkopf) • 16K views • 4:35", videoId = "HNfnjWbO4ds")
        namesAndTabs(
            songs = listOf(single),
            videos = listOf(fan),
            apple = mapOf("US" to appleSong(title, "JUNKO YAGAMI", 281_200, album = "full moon")),
            elsewhere = mapOf("JP" to appleSong("黄昏のBay City", "八神純子", 281_200, album = "Full Moon"))
        )
        assertEquals("https://music.youtube.com/watch?v=HNfnjWbO4ds", runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, metadata) })
    }

    @Test
    fun aRemasterTagDoesNotHideTheEarlierDiscoNote() = runBlocking {
        val song = MusicMetadata("Song", "Band")
        for (title in listOf("Song - Disco Version (2024 Remaster)", "Song (Disco Version [2024 Remaster])")) {
            namesAndTabs(songs = listOf(youTubeMusicRow(title, "Band • Album • 7:27", videoId = "mix00000000")))
            assertNull(title, matcher("US").find(MusicService.YOUTUBE_MUSIC, song))
        }
    }

    @Test
    fun aDiscoPhraseInTheSourceTitleOrAlbumCountsWithoutTagPunctuation() = runBlocking {
        val row = youTubeMusicRow("Song - Disco Version", "Band • Album • 4:41", videoId = "mix00000000")
        val song = MusicMetadata("Song: Disco Version", "Band")
        val url = "https://music.youtube.com/watch?v=mix00000000"
        namesAndTabs(songs = listOf(row))
        assertEquals(url, matcher("US").find(MusicService.YOUTUBE_MUSIC, song))

        namesAndTabs(songs = listOf(youTubeMusicRow("Song", "Band • Album (Disco Version) • 4:41", videoId = "mix00000000")))
        assertNull(matcher("US").find(MusicService.YOUTUBE_MUSIC, song.copy(title = "Song")))
        assertEquals(url, matcher("US").find(MusicService.YOUTUBE_MUSIC, song.copy(title = "Song", album = "Ａｌｂｕｍ：Ｄｉｓｃｏ・Ｍｉｘ Deluxe")))
    }

    @Test
    fun aDiscoRowThatCannotOpenStillRulesOutAnUnrelatedTitle() = runBlocking {
        val live = youTubeMusicRow("Song - Live", "Band • Album (Disco Mix) • 4:41", videoId = "live0000000")
        val unrelated = youTubeMusicRow("違う曲", "Band • Album • 4:41", videoId = "other000000")
        val apple = mapOf("US" to appleSong("Song", "Band", 281_000))
        val source = MusicMetadata("Song", "Band")
        namesAndTabs(songs = listOf(unrelated), apple = apple)
        assertEquals("https://music.youtube.com/watch?v=other000000", matcher("US").find(MusicService.YOUTUBE_MUSIC, source))
        namesAndTabs(songs = listOf(live, unrelated), apple = apple)
        assertNull(matcher("US").find(MusicService.YOUTUBE_MUSIC, source))
        val markedTitle = youTubeMusicRow("違う曲", "Band • Album (Disco Ver.) • 4:41", videoId = "mix00000000")
        namesAndTabs(songs = listOf(markedTitle), apple = apple)
        assertNull(matcher("US").find(MusicService.YOUTUBE_MUSIC, source))
    }

    @Test
    fun aDiscoAlbumRequestDoesNotShareThePlainAlbumsCachedAnswer() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("lookups").toFile()
        try {
            val cache = LookupCache(directory.resolve("lookups.json"), Dispatchers.Unconfined)
            val matcher = ExactMatcher(fake.client(), "US", Dispatchers.Unconfined, cache)
            val source = MusicMetadata("Song", "Band", album = "Album")
            val disco = source.copy(album = "Album (ディスコ版)")
            val mix = youTubeMusicRow("Song", "Band • Album (Disco Mix) • 7:27", videoId = "mix00000000")
            val original = youTubeMusicRow("Song", "Band • Album • 4:41", videoId = "original000")
            namesAndTabs(songs = listOf(mix, original))
            val mixUrl = "https://music.youtube.com/watch?v=mix00000000"
            val originalUrl = "https://music.youtube.com/watch?v=original000"
            assertEquals(originalUrl, matcher("US").find(MusicService.YOUTUBE_MUSIC, source))
            assertEquals(mixUrl, matcher.find(MusicService.YOUTUBE_MUSIC, disco))
            assertEquals(originalUrl, matcher.find(MusicService.YOUTUBE_MUSIC, source))
            // A plain song keeps the key the playlist widget reads before looking up a song again.
            assertEquals(originalUrl, cache.get("find@$MATCH_VERSION|YOUTUBE_MUSIC|TRACK|Song|Band|"))
            val linked = source.copy(url = "https://open.spotify.com/track/1234567890123456789012")
            val linkedUrl = "https://music.youtube.com/watch?v=linked00000"
            namesAndTabs(songs = listOf(youTubeMusicRow("Song", "Band • Album • 4:41", videoId = "linked00000")))
            assertEquals(linkedUrl, matcher.find(MusicService.YOUTUBE_MUSIC, linked))
            val otherLink = source.copy(url = "https://open.spotify.com/track/1234567890123456789013")
            namesAndTabs(songs = listOf(original))
            assertEquals(originalUrl, matcher.find(MusicService.YOUTUBE_MUSIC, otherLink))
            fake.handler = { error("The answers should be in the cache") }
            assertEquals(mixUrl, matcher.find(MusicService.YOUTUBE_MUSIC, disco.copy(album = "Album - Ｄｉｓｃｏ　Ｖｅｒ．")))
            assertEquals(originalUrl, matcher.find(MusicService.YOUTUBE_MUSIC, source))
            assertEquals(linkedUrl, matcher.find(MusicService.YOUTUBE_MUSIC, linked))
            assertEquals(originalUrl, matcher.find(MusicService.YOUTUBE_MUSIC, otherLink))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ordinaryMixLabelsInTitlesAndAlbumsKeepTheirExistingMatches() = runBlocking {
        val song = MusicMetadata("Song", "Band")
        val url = "https://music.youtube.com/watch?v=original000"
        val labels = listOf("2009 Stereo Mix", "Stereo Mix", "Mono Mix", "Single Mix", "Original Mix", "Radio Mix")
        for (label in labels) {
            namesAndTabs(songs = listOf(youTubeMusicRow("Song ($label)", "Band • Album • 4:41", videoId = "original000")))
            val picker = ExactMatcher(fake.client(), "US", pick = { _, _, _ -> 0 })
            assertEquals(label, url, picker.find(MusicService.YOUTUBE_MUSIC, song))
        }
        val albums = (labels + listOf("Extended Mix", "2023 Mix", "Dolby Atmos Mix")).map { "Album ($it)" } +
            listOf("Oldies (Summer Mix)", "Disco Mix")
        for (album in albums) {
            namesAndTabs(songs = listOf(youTubeMusicRow("Song", "Band • $album • 4:41", videoId = "original000")))
            assertEquals(album, url, matcher("US").find(MusicService.YOUTUBE_MUSIC, song))
        }
    }

    @Test
    fun anOrdinaryMixThatWasRequestedKeepsItsMatch() = runBlocking {
        val url = "https://music.youtube.com/watch?v=original000"
        namesAndTabs(songs = listOf(youTubeMusicRow("Song", "Band • Album (Album Mix) • 4:41", videoId = "original000")))
        assertEquals(url, matcher("US").find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Song", "Band", album = "Album Mix")))
        val remix = MusicMetadata("Song (DJ Remix)", "Band")
        namesAndTabs(songs = listOf(youTubeMusicRow(remix.title, "Band • Album (2023 Mix) • 4:41", videoId = "original000")))
        assertEquals(url, matcher("US").find(MusicService.YOUTUBE_MUSIC, remix))
    }

    @Test
    fun aMarkedBayCityVideoNeedsEvidenceFromTheLinkItself() = runBlocking {
        val title = "tasogare no bay city"
        val disco = "黄昏のBAY CITY (プロモーション用DISCOバージョン)"
        val song = MusicMetadata(title, "八神純子", lengthMs = 281_200)
        val id = "Wey2yM_dNfc"
        namesAndTabs(
            videos = listOf(youTubeMusicRow(disco, "Junko Yagami • 12K views • 7:27", videoId = id)),
            apple = mapOf("US" to appleSong(title, "JUNKO YAGAMI", 281_200, album = "Full Moon") + "," +
                appleSong(title, "JUNKO YAGAMI", 446_973, id = 8, album = "Full Moon")),
            elsewhere = mapOf("JP" to appleSong("黄昏のBAY CITY", "八神純子", 281_200, album = "Full Moon"))
        )
        val matcher = ExactMatcher(fake.client(), "US", pick = { _, _, _ -> 0 })
        assertNull(matcher.find(MusicService.YOUTUBE_MUSIC, song))
        assertNull(matcher.find(MusicService.YOUTUBE_MUSIC, song.copy(lengthMs = null)))
        namesAndTabs(songs = listOf(youTubeMusicRow(disco, "八神純子 • Full Moon • 7:27", videoId = id)))
        assertEquals("https://music.youtube.com/watch?v=$id", matcher("US").find(MusicService.YOUTUBE_MUSIC, song.copy(title = disco, lengthMs = null)))
    }

    @Test
    fun aMarkedVideoCannotOpenWhenAppleIsMissingAnAlias() = runBlocking {
        val song = MusicMetadata("終わらない歌", "ザ・ブルーハーツ")
        val apple = mapOf("AR" to appleSong(song.title, song.artist, 184_000))
        val title = "ザ・ブルーハーツ - 終わらない歌"
        val fan = youTubeMusicRow(title, "Fan • 1K views • 3:04", videoId = "fan00000000")
        for (channel in listOf("Another Fan", song.artist)) {
            val mix = youTubeMusicRow("$title (Disco Version)", "$channel • 1K views • 3:04", videoId = "mix00000000")
            namesAndTabs(videos = listOf(mix, fan), apple = apple, failing = setOf("JP"))
            assertEquals(channel, "https://music.youtube.com/watch?v=fan00000000", matcher().find(MusicService.YOUTUBE_MUSIC, song))
        }
    }

    @Test
    fun aMarkedAlbumNeedsTheSourceNoteOrALinkLengthWithinThreeSeconds() = runBlocking {
        val song = MusicMetadata("Song", "Band")
        val mix = youTubeMusicRow("Song", "Band • Album (Disco Mix) • 7:27", videoId = "mix00000000")
        val other = youTubeMusicRow("Song", "Band • Album • 7:25", videoId = "original000")
        namesAndTabs(songs = listOf(mix, other))
        suspend fun find(source: MusicMetadata) = matcher("US").find(MusicService.YOUTUBE_MUSIC, source)
        assertEquals("https://music.youtube.com/watch?v=original000", find(song.copy(lengthMs = 443_999)))
        assertEquals("https://music.youtube.com/watch?v=mix00000000", find(song.copy(lengthMs = 444_000)))
        assertEquals("https://music.youtube.com/watch?v=mix00000000", find(song.copy(album = "Album (ディスコ版)")))
        namesAndTabs(songs = listOf(mix))
        assertNull(find(song))
        assertNull(find(song.copy(lengthMs = 281_200)))
    }

    @Test
    fun aLocalizedRowNeedsItsOwnMixCheck() {
        val song = MusicMetadata("Lemon", "米津玄師")
        val original = listOf(localizedRow("Kenshi Yonezu", "original000", time = "7:27"))
        val localized = mapOf("ja" to listOf(localizedRow(song.artist, "original000", "Lemon (DISCOバージョン)", "7:27")))
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, original, localized))
        assertEquals(localizedFound("original000"), localizedMatch(song.copy(lengthMs = 447_000), original, localized))
        val markedOriginal = listOf(localizedRow("Kenshi Yonezu", "original000", "Lemon (DISCOバージョン)"))
        val plainReply = mapOf("ja" to listOf(localizedRow(song.artist, "original000")))
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, markedOriginal, plainReply))
        // The barred row still counts when the credit could identify two recordings.
        val plain = localizedRow(song.artist, "other000000")
        val marked = localizedRow(song.artist, "original000", "Lemon (DISCOバージョン)")
        val originals = listOf(localizedRow("Kenshi Yonezu", "original000"), localizedRow("Kenshi Yonezu", "other000000"))
        assertEquals(ExactMatcher.Match.Failed, localizedMatch(song, originals, mapOf("ja" to listOf(marked, plain))))
    }

    @Test
    fun aMixThePickerCannotProveDoesNotMoveTheOtherChoices() = runBlocking {
        val song = MusicMetadata("Song", "Band", lengthMs = 281_000)
        val mix = youTubeMusicRow("別名", "Band • Album (Disco Version) • 7:27", videoId = "mix00000000")
        val other = youTubeMusicRow("もう一つの名前", "Band • Album • 4:41", videoId = "other000000")
        namesAndTabs(songs = listOf(mix, other))
        val matcher = ExactMatcher(fake.client(), "US", pick = { _, choices, video ->
            assertFalse(video)
            assertEquals(listOf("", "もう一つの名前 | Band • Album • 4:41"), choices)
            1
        })
        assertEquals("https://music.youtube.com/watch?v=other000000", matcher.find(MusicService.YOUTUBE_MUSIC, song))
    }

    @Test
    fun thePickerStillReadsLiveAlbumNotesOnSongRows() = runBlocking {
        val plain = youTubeMusicRow("別の曲", "Unknown Artist • Album • 4:41", videoId = "other000000")
        val live = youTubeMusicRow("別名", "Unknown Artist • Live at Budokan • 4:41", videoId = "original000")
        namesAndTabs(songs = listOf(plain, live))
        val matcher = ExactMatcher(fake.client(), "US", pick = { _, choices, videos ->
            assertFalse(videos)
            assertEquals(listOf("", "別名 | Unknown Artist • Live at Budokan • 4:41"), choices)
            1
        })
        val song = MusicMetadata("Song: Live", "Band")
        assertEquals("https://music.youtube.com/watch?v=original000", matcher.find(MusicService.YOUTUBE_MUSIC, song))
    }

    @Test
    fun mixNoteReadingDoesNotNormalizeTheTitleUsedToMatch() = runBlocking {
        val titles = listOf("Suite Ⅱ" to "Suite II", "Ｓｕｉｔｅ" to "Suite", "Suﬃx" to "Suffix", "㈱ Records" to "(株) Records")
        for ((original, normalized) in titles) {
            val source = MusicMetadata("$original (Disco Mix)", "Band", lengthMs = 447_000)
            val wanted = youTubeMusicRow(original, "Band • Album (Disco Mix) • 7:27", videoId = "original000")
            val other = youTubeMusicRow("$normalized (Disco Version)", "Band • Album • 7:27", videoId = "variant0000")
            namesAndTabs(songs = listOf(wanted, other))
            assertEquals(original, "https://music.youtube.com/watch?v=original000", matcher("US").find(MusicService.YOUTUBE_MUSIC, source))
        }
    }

    @Test
    fun allowingAMixByLengthDoesNotEraseARadioEditNote() = runBlocking {
        val row = youTubeMusicRow("Song (Radio Edit) (Disco Version)", "Band • Album • 7:27", videoId = "edit0000000")
        namesAndTabs(songs = listOf(row))
        assertNull(matcher("US").find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Song", "Band", lengthMs = 447_000)))
    }

    @Test
    fun anUnrelatedMixDoesNotMakeAnExactSongWaitForVideos() = runBlocking {
        val song = youTubeMusicRow("Lemon", "Band • Album • 4:41", videoId = "original000")
        val mix = youTubeMusicRow("Sunset (Disco Version)", "Other Band • Album • 7:27", videoId = "mix00000000")
        namesAndTabs(songs = listOf(song, mix))
        val serve = fake.handler
        fake.handler = { request ->
            if (FakeSpotify.bodyOf(request).contains(VIDEOS_TAB)) throw IOException("Videos are down")
            serve(request)
        }
        assertEquals("https://music.youtube.com/watch?v=original000",
            matcher("US").find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Lemon", "Band", lengthMs = 281_200)))
    }

    @Test
    fun zeroIsNotMixTimingAndAnotherRowsZeroCannotVetoASong() = runBlocking {
        val song = MusicMetadata("Song", "Band")
        val zero = youTubeMusicRow("Song", "Band • Album (Disco Version) • 0:00", videoId = "mix00000000")
        namesAndTabs(songs = listOf(zero))
        for (length in listOf(0L, 1_000L)) {
            assertNull(matcher("US").find(MusicService.YOUTUBE_MUSIC, song.copy(lengthMs = length)))
        }
        val short = youTubeMusicRow("Song", "Band • Album (Disco Version) • 0:02", videoId = "mix00000000")
        namesAndTabs(songs = listOf(short))
        assertNull(matcher("US").find(MusicService.YOUTUBE_MUSIC, song.copy(lengthMs = 0L)))
        val plain = youTubeMusicRow("Song", "Band • Album • 4:41", videoId = "original000")
        val live = youTubeMusicRow("Song - Live", "Band • Live at Budokan (Disco Version) • 0:02", videoId = "mix00000000")
        namesAndTabs(songs = listOf(plain, live))
        assertEquals("https://music.youtube.com/watch?v=original000", matcher("US").find(MusicService.YOUTUBE_MUSIC, song.copy(lengthMs = 0L)))
        val timed = youTubeMusicRow("Song", "Band • Album (Disco Version) • 7:27", videoId = "mix00000000")
        val video = youTubeMusicRow("Song (Disco Version)", "Band • 1K views • 0:00", videoId = "mix00000000")
        namesAndTabs(songs = listOf(timed), videos = listOf(video))
        assertEquals("https://music.youtube.com/watch?v=mix00000000", matcher("US").find(MusicService.YOUTUBE_MUSIC, song.copy(lengthMs = 447_000)))
    }

    @Test
    fun aVideoSayingMoreInAnotherScriptIsAnotherSong() {
        val song = MusicMetadata("桜", "Band")
        fun finds(name: String, channel: String = "Band - Topic"): String? {
            namesAndTabs(videos = listOf(youTubeMusicRow(name, "$channel • 1K views • 4:00", videoId = "video000000")), apple = mapOf("AR" to appleSong("桜", "Band", 240_000)))
            return runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) }
        }
        assertNull(finds("桜の雨"))
        assertNull(finds("Band - 桜の雨", "Fan"))
        // The song's name with the artist's, and tags in either script, is still it.
        assertEquals("https://music.youtube.com/watch?v=video000000", finds("桜"))
        assertEquals("https://music.youtube.com/watch?v=video000000", finds("Band「桜」歌詞付き", "Fan"))
        assertEquals("https://music.youtube.com/watch?v=video000000", finds("Band - 桜【公式】 Official Video", "Fan"))
        // Named both ways, the other way is its name too, but only beside a name it has.
        val plastic = MusicMetadata("Plastic Love", "Mariya Takeuchi")
        namesAndTabs(videos = listOf(youTubeMusicRow("プラスティック・ラブ - Plastic Love", "Mariya Takeuchi • 79M views • 5:09", videoId = "plastic0000")))
        assertEquals("https://music.youtube.com/watch?v=plastic0000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, plastic) })
        namesAndTabs(videos = listOf(youTubeMusicRow("プラスティック・ラブ Plastic Love", "Mariya Takeuchi • 79M views • 5:09", videoId = "plastic0000")))
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, plastic) })
    }

    @Test
    fun wordsForAnotherVersionAreFoundInEveryScript() {
        val song = MusicMetadata("Spring Day", "BTS")
        fun finds(name: String): String? {
            namesAndTabs(
                videos = listOf(youTubeMusicRow(name, "Fan • 1K views • 4:34", videoId = "video000000")),
                apple = mapOf("AR" to appleSong("Spring Day", "BTS", 274_000)),
                elsewhere = mapOf("KR" to appleSong("봄날", "BTS", 274_000))
            )
            return runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) }
        }
        assertEquals("https://music.youtube.com/watch?v=video000000", finds("BTS - 봄날"))
        listOf("BTS - 봄날 커버", "BTS - 봄날 라이브", "BTS - 봄날 노래방", "BTS - 봄날 ピアノ", "BTS - 봄날 ライブ").forEach { assertNull(it, finds(it)) }
    }

    @Test
    fun oddResultsAreSkippedAndASongNotOnAppleIsAskedOnceFromTheUs() {
        val song = MusicMetadata("Owaranai Uta", "THE BLUE HEARTS")
        // A song result without its video plays nothing of it, nor does a video of a longer name with the same letter.
        namesAndTabs(songs = listOf(youTubeMusicRow("Owaranai Uta", "THE BLUE HEARTS • Album • 3:04", browseId = "MPREb_x")))
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
        namesAndTabs(videos = listOf(youTubeMusicRow("YOASOBI - 夜に駆ける", "Fan • 1K views • 4:21", videoId = "yoru0000000")))
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("夜", "YOASOBI")) })
        // A video from another channel must say how long it is.
        namesAndTabs(
            videos = listOf(youTubeMusicRow("The Blue Hearts - Owaranai Uta", "Fan • 1K views", videoId = "nolength000")),
            apple = mapOf("US" to appleSong("Owaranai Uta", "THE BLUE HEARTS", 184_000))
        )
        fake.requestedUrls.clear()
        assertNull(runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song) })
        // From the US, a song Apple doesn't have is asked about once there.
        namesAndTabs()
        fake.requestedUrls.clear()
        assertNull(runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song) })
        assertEquals(1, fake.requestedUrls.count { "itunes.apple.com/search" in it })
    }

    @Test
    fun aSongNeverReleasedIsFoundAsItsVideo() {
        val demo = MusicMetadata("Bedroom Demo", "Tiny Band")
        // Not among the songs, it's looked for among the videos, its artist's own upload taken.
        youTubeMusicTabs(
            songs = listOf(youTubeMusicRow("Bedroom Demo", "Someone Else • Album • 3:00", videoId = "other000000")),
            videos = listOf(
                youTubeMusicRow("Tiny Band - Bedroom Demo (Remix)", "Tiny Band • 1K views • 4:00", videoId = "remix000000"),
                youTubeMusicRow("Bedroom Demo", "Someone Else • 9K views • 3:00", videoId = "other000000"),
                youTubeMusicRow("Tiny Band - Bedroom Demo (Official Video)", "Tiny Band • 300 views • 3:00", videoId = "demo0000000")
            )
        )
        assertEquals("https://music.youtube.com/watch?v=demo0000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, demo) })
        assertTrue(fake.requestBodies.any { it.contains(VIDEOS_TAB) })
        // On YouTube it opens there.
        assertEquals("https://www.youtube.com/watch?v=demo0000000", runBlocking { matcher().find(MusicService.YOUTUBE, demo) })

        // Without a known length, someone else's upload isn't enough to identify the recording.
        youTubeMusicTabs(
            songs = emptyList(),
            videos = listOf(
                youTubeMusicRow("Tiny Band - Bedroom Demo (Live)", "Tiny Band • 1K views • 4:00", videoId = "live0000000"),
                youTubeMusicRow("Tiny Band - Bedroom Demo [Lyrics]", "Fan Channel • 50 views • 3:00", videoId = "upload00000")
            )
        )
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, demo) })
        // The artist's own channel by what YouTube calls it, though the title names only the song.
        youTubeMusicTabs(songs = emptyList(), videos = listOf(youTubeMusicRow("Bedroom Demo", "Tiny Band - Topic • 20 views • 3:00", videoId = "topic000000")))
        assertEquals("https://music.youtube.com/watch?v=topic000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, demo) })

        // Nothing but other recordings: not found.
        youTubeMusicTabs(songs = emptyList(), videos = listOf(youTubeMusicRow("Tiny Band - Bedroom Demo (Live)", "Tiny Band • 1K views • 4:00", videoId = "live0000000")))
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, demo) })
        // A video with no id, or a title with no words to compare, is no match either.
        youTubeMusicTabs(songs = emptyList(), videos = listOf("""{"musicResponsiveListItemRenderer":{}}""", youTubeMusicRow("Bedroom Demo", "Tiny Band • 1 view • 3:00")))
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, demo) })
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("夜", "Tiny Band")) })

        // An album is only ever an album; no video is looked for.
        youTubeMusicTabs(songs = emptyList(), videos = emptyList())
        val before = fake.requestBodies.size
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Demos", "Tiny Band", ItemType.ALBUM)) })
        assertEquals(before + 1, fake.requestBodies.size)
    }

    @Test
    fun aRemasterTagDoesNotStopASongFromMatching() {
        respond(
            youTubeMusicPage(
                youTubeMusicRow("Bullet With Butterfly Wings", "Band • Album • 4:18", videoId = "bullet00000"),
                youTubeMusicRow("Other", "Band • Album • 4:18", videoId = "other000000")
            )
        )
        for (title in listOf("Bullet With Butterfly Wings - Remastered 2012", "Bullet With Butterfly Wings (2012 Remaster)", "Bullet With Butterfly Wings - Mono")) {
            assertEquals(title, "https://music.youtube.com/watch?v=bullet00000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata(title, "Band")) })
        }
        // The same song credited to "Smashing Pumpkins", and a guest in the title, still match.
        assertEquals("https://music.youtube.com/watch?v=bullet00000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Bullet With Butterfly Wings (feat. Guest)", "The Band")) })
        // Only the tag is ignored: a different song, or a remix, still doesn't match.
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Bullet - Remastered", "Band")) })
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Bullet With Butterfly Wings - Skrillex Remix", "Band")) })
    }

    private fun findsVideo(title: String, artist: String, rowTitle: String, rowArtist: String): String? {
        respond(youTubeMusicPage(youTubeMusicRow(rowTitle, "$rowArtist • Album • 3:00", videoId = "video000000")))
        return runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata(title, artist)) }
    }

    private val video = "https://music.youtube.com/watch?v=video000000"

    @Test
    fun titleTagsAreIgnoredInEveryFormAndCase() {
        assertEquals(video, findsVideo("Song - REMASTERED 2012", "Band", "Song", "Band"))
        assertEquals(video, findsVideo("Song (Remastered)", "Band", "Song", "Band"))
        assertEquals(video, findsVideo("Song [2012 Remaster]", "Band", "Song", "Band"))
        assertEquals(video, findsVideo("Song", "Band", "Song - 2012 Remaster", "Band"))
        // Two tags, in either order.
        assertEquals(video, findsVideo("Song - 2012 Remaster (Mono)", "Band", "Song", "Band"))
        assertEquals(video, findsVideo("Song (feat. Guest) - Remastered 2012", "Band", "Song", "Band"))
        assertEquals(video, findsVideo("Song - Remastered 2012 (feat. Guest)", "Band", "Song", "Band"))
        assertEquals(video, findsVideo("Song (ft. Guest)", "Band", "Song [Featuring Guest]", "Band"))
        // Versions of the same recording.
        assertEquals(video, findsVideo("Teen Age Riot (Album Version)", "Band", "Teen Age Riot", "Band"))
        assertEquals(video, findsVideo("Never Say Never", "Band, Guest", "Never Say Never (Single Version) (feat. Guest)", "Band"))
        assertEquals(video, findsVideo("Stereo Love - Original", "Band", "Stereo Love", "Band"))
        assertEquals(video, findsVideo("Song - Radio Edit", "Band", "Song", "Band"))
        // Non-Latin titles keep their letters.
        assertEquals(video, findsVideo("ガラスのPALM TREE - Remastered", "Band", "ガラスのPALM TREE", "Band"))
    }

    @Test
    fun titleTagsNeverTurnADifferentSongIntoAMatch() {
        // A title that is only a tag keeps it, so two tag-only titles don't both become "".
        assertNull(findsVideo("(Remastered)", "Band", "(feat. Guest)", "Band"))
        assertEquals(video, findsVideo("(Remastered)", "Band", "(Remastered)", "Band"))
        // Remixes, live versions and other songs are still different songs.
        assertNull(findsVideo("Song", "Band", "Song (Live)", "Band"))
        assertNull(findsVideo("Song - Remastered", "Band", "Song (Acoustic)", "Band"))
        assertNull(findsVideo("Song (Remix)", "Band", "Song", "Band"))
        assertNull(findsVideo("Song - Live Version", "Band", "Song", "Band"))
        assertNull(findsVideo("Song (Acoustic Version)", "Band", "Song", "Band"))
        assertNull(findsVideo("Song - Original Sin", "Band", "Song", "Band"))
        // Words that merely contain a tag word are not tags.
        assertNull(findsVideo("Monologue", "Band", "Mono", "Band"))
        assertNull(findsVideo("Song - Stereotype", "Band", "Song", "Band"))
        // Unbalanced or nested brackets are left alone rather than mangled.
        assertEquals(video, findsVideo("Song (feat. A", "Band", "Song (feat. A", "Band"))
        assertNull(findsVideo("Song (feat. A (B))", "Band", "Song", "Band"))
    }

    @Test
    fun appleMusicTakesTheExactTaggedNameAndNeverAnotherBaseNameWithTheSameTag() {
        val wrong = mutableListOf<String>()
        fun apple(name: String, link: String) = """{"trackName":"$name","artistName":"${RecordingTagPairs.ARTIST}","trackViewUrl":"https://music.apple.com/$link"}"""
        for ((asked, other) in RecordingTagPairs.wrongPicks) {
            val song = MusicMetadata(asked, RecordingTagPairs.ARTIST)
            // Only the other song: none is taken for it.
            respond("""{"results":[${apple(other, "wrong")}]}""")
            runBlocking { matcher().find(MusicService.APPLE_MUSIC, song) }?.let { wrong += "only: $asked took $other: $it" }
            // The other song first, then its own: the own one.
            respond("""{"results":[${apple(other, "wrong")},${apple(asked, "exact")}]}""")
            val found = runBlocking { matcher().find(MusicService.APPLE_MUSIC, song) }
            if (found != "https://music.apple.com/exact") wrong += "first: $asked found $found, wanted its own"
        }
        for (name in RecordingTagPairs.exactNames) {
            respond("""{"results":[${apple(name, "exact")}]}""")
            val found = runBlocking { matcher().find(MusicService.APPLE_MUSIC, MusicMetadata(name, RecordingTagPairs.ARTIST)) }
            if (found != "https://music.apple.com/exact") wrong += "exact: $name found $found"
        }
        assertEquals("${wrong.size} wrong Apple Music picks", emptyList<String>(), wrong)
    }

    @Test
    fun youTubeMusicOpensTheExactTaggedNameAndNeverAnotherBaseNameWithTheSameTag() {
        val wrong = mutableListOf<String>()
        for ((asked, other) in RecordingTagPairs.wrongPicks) {
            findsVideo(asked, RecordingTagPairs.ARTIST, other, RecordingTagPairs.ARTIST)?.let { wrong += "$asked took $other: $it" }
            // The other song first, then its own: the own one, whatever its place.
            respond(
                youTubeMusicPage(
                    youTubeMusicRow(other, "${RecordingTagPairs.ARTIST} • Album • 3:00", videoId = "wrong000000"),
                    youTubeMusicRow(asked, "${RecordingTagPairs.ARTIST} • Album • 3:00", videoId = "video000000")
                )
            )
            val found = runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata(asked, RecordingTagPairs.ARTIST)) }
            if (found != video) wrong += "first: $asked found $found, wanted its own"
        }
        for (name in RecordingTagPairs.exactNames) {
            val found = findsVideo(name, RecordingTagPairs.ARTIST, name, RecordingTagPairs.ARTIST)
            if (found != video) wrong += "exact: $name found $found"
        }
        assertEquals("${wrong.size} wrong YouTube Music picks", emptyList<String>(), wrong)
    }

    @Test
    fun anotherSongsNameFromAppleCannotOpenItsVideoOnYouTubeMusic() {
        val wrong = mutableListOf<String>()
        val artist = RecordingTagPairs.ARTIST
        for ((asked, other) in RecordingTagPairs.wrongPicks) {
            // Apple, and YouTube Music, only know the other song: Apple's name for it is no name for this one.
            namesAndTabs(songs = listOf(youTubeMusicRow(other, "$artist • Album • 5:12", videoId = "wrong000000")), apple = mapOf("AR" to appleSong(other, artist)))
            runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata(asked, artist)) }?.let { wrong += "only: $asked opened $other: $it" }
            // Apple knows both, in either order: the video of the one asked for opens.
            for (apple in listOf(appleSong(other, artist) + "," + appleSong(asked, artist, id = 8), appleSong(asked, artist, id = 8) + "," + appleSong(other, artist))) {
                namesAndTabs(
                    songs = listOf(
                        youTubeMusicRow(other, "$artist • Album • 5:12", videoId = "wrong000000"),
                        youTubeMusicRow(asked, "$artist • Album • 5:12", videoId = "video000000")
                    ),
                    apple = mapOf("AR" to apple)
                )
                val found = runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata(asked, artist)) }
                if (found != video) wrong += "both: $asked opened $found, wanted its own"
            }
        }
        assertEquals("${wrong.size} wrong YouTube Music opens", emptyList<String>(), wrong)
    }

    @Test
    fun aQueueTakesTheExactTaggedNameAndNeverAnotherBaseNameWithTheSameTag() {
        val wrong = mutableListOf<String>()
        for ((asked, other) in RecordingTagPairs.wrongPicks) {
            queueFinds(asked, RecordingTagPairs.ARTIST, other, RecordingTagPairs.ARTIST)?.let { wrong += "$asked queued $other: $it" }
        }
        for (name in RecordingTagPairs.exactNames) {
            val found = queueFinds(name, RecordingTagPairs.ARTIST, name, RecordingTagPairs.ARTIST)
            if (found != video) wrong += "exact: $name queued $found"
        }
        for ((asked, found) in RecordingTagPairs.same) {
            val url = queueFinds(asked, RecordingTagPairs.ARTIST, found, RecordingTagPairs.ARTIST)
            if (url != video) wrong += "same: $asked queued $url, wanted $found"
        }
        assertEquals("${wrong.size} wrong queue picks", emptyList<String>(), wrong)
    }

    @Test
    fun aLeadingTheIsIgnoredInArtistsButOnlyAsAWord() {
        assertEquals(video, findsVideo("Song", "The Smashing Pumpkins", "Song", "Smashing Pumpkins"))
        assertEquals(video, findsVideo("Song", "Smashing Pumpkins", "Song", "THE SMASHING PUMPKINS"))
        // The band called "The The" and an artist named just "The" still have a name.
        assertEquals(video, findsVideo("Song", "The The", "Song", "The The"))
        assertEquals(video, findsVideo("Song", "The", "Song", "The"))
        assertNull(findsVideo("Song", "The", "Song", "Someone"))
        assertNull(findsVideo("Song", "Thelonious Monk", "Song", "Lonious Monk"))
        assertNull(findsVideo("Song", "The Band", "Song", "Another Band"))
    }

    @Test
    fun aQueueTakesEachVideoOnceAndStaysWithinWhatYouTubeTakes() {
        var asked = ""
        fake.handler = { request ->
            if (request.url.encodedPath == "/watch_videos") {
                asked = java.net.URLDecoder.decode(checkNotNull(request.url.queryParameter("video_ids")) { "Expected request.url.queryParameter(\"video_ids\") to be non-null" }, "UTF-8")
                FakeSpotify.html(request, "").newBuilder().code(303).header("Location", "https://www.youtube.com/watch?v=a&list=TLGGq").build()
            } else {
                // Every search answers with the same video, e.g. two titles for one recording.
                FakeSpotify.html(request, youTubeMusicPage(
                    youTubeMusicRow("One", "Band • Album • 3:00", videoId = "same0000000"),
                    youTubeMusicRow("Two", "Band • Album • 3:00", videoId = "same0000000")
                ))
            }
        }
        val tracks = listOf(MusicMetadata("One", "Band"), MusicMetadata("Two", "Band"), MusicMetadata("One", "Band"))
        val queue = runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, tracks) {} }
        assertEquals("same0000000", asked)
        assertEquals("https://music.youtube.com/watch?v=same0000000&list=TLGGq", queue?.url)
        // The songs it plays, as asked for: the one video, once.
        assertEquals(listOf(MusicMetadata("One", "Band")), queue?.songs)
        assertNull(runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, emptyList()) {} })
    }

    @Test
    fun aQueuePlaysTheFirstSongWhenYouTubeAnswersWithoutAPlaylist() {
        for (answer in listOf<(Request) -> okhttp3.Response>(
            { request -> FakeSpotify.html(request, "").newBuilder().code(303).header("Location", "https://www.youtube.com/watch?v=first000000").build() },
            { request -> FakeSpotify.html(request, "").newBuilder().code(303).header("Location", "not a url").build() },
            { request -> FakeSpotify.html(request, "").newBuilder().code(200).build() },
            { request -> FakeSpotify.html(request, "").newBuilder().code(429).build() }
        )) {
            fake.handler = { request ->
                if (request.url.encodedPath == "/watch_videos") answer(request)
                else FakeSpotify.html(request, youTubeMusicPage(youTubeMusicRow("First", "Band • Album • 3:00", videoId = "first000000")))
            }
            val queue = runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata("First", "Band"), MusicMetadata("Second", "Band"))) {} }
            assertEquals("https://music.youtube.com/watch?v=first000000", queue?.url)
            // Only the first plays, on its own.
            assertEquals(listOf(MusicMetadata("First", "Band")), queue?.songs)
        }
    }

    @Test
    fun aQueueSkipsSongsWhoseSearchFailsAndPlaylistsInsideIt() {
        fake.handler = { request ->
            when {
                request.url.encodedPath == "/watch_videos" -> FakeSpotify.html(request, "").newBuilder().code(303).header("Location", "https://www.youtube.com/watch?v=ok&list=TLGGq").build()
                request.requestBodyText().contains("Broken") -> throw IOException("offline")
                request.requestBodyText().contains("Garbled") -> FakeSpotify.html(request, "not json")
                else -> FakeSpotify.html(request, youTubeMusicPage(youTubeMusicRow("Fine", "Band • Album • 3:00", videoId = "fine0000000")))
            }
        }
        val tracks = listOf(
            MusicMetadata("Broken", "Band"), MusicMetadata("Garbled", "Band"), MusicMetadata("Fine", "Band"),
            MusicMetadata("Nested", "", ItemType.PLAYLIST)
        )
        val looked = mutableListOf<Int>()
        val queue = runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, tracks) { looked += it } }
        assertEquals("https://music.youtube.com/watch?v=fine0000000&list=TLGGq", queue?.url)
        assertEquals(listOf(MusicMetadata("Fine", "Band")), queue?.songs)
        assertEquals(listOf(1, 2, 3, 4), looked.sorted())
    }

    private fun Request.requestBodyText(): String =
        okio.Buffer().also { body?.writeTo(it) }.readUtf8()

    @Test
    fun aCoverIsFoundByNameWhenSpotifyAnswersWithNothingUsable() {
        val deezer = """{"data":[{"title":"Song","artist":{"name":"The Band"},"album":{"cover_big":"https://cdn/song.jpg"}}]}"""
        for (spotify in listOf("""{"thumbnail_url":""}""", """{"title":"no thumbnail"}""", "not json", "")) {
            fake.handler = { request ->
                if (request.url.host == "open.spotify.com") FakeSpotify.html(request, spotify) else FakeSpotify.html(request, deezer)
            }
            val track = MusicMetadata("Song - Remastered 2012", "Band", url = "https://open.spotify.com/track/abc")
            assertEquals(spotify, "https://cdn/song.jpg", runBlocking { matcher().cover(track) })
        }
        // Both fail, no cover and no crash.
        fake.handler = { throw IOException("offline") }
        assertNull(runBlocking { matcher().cover(MusicMetadata("Song", "Band", url = "https://open.spotify.com/track/abc")) })
        // A song with no artist at all has nothing to check Deezer's credit against.
        respond(deezer)
        assertNull(runBlocking { matcher().cover(MusicMetadata("Song", "")) })
    }

    private fun queueFinds(title: String, artist: String, rowTitle: String, rowArtist: String): String? {
        fake.handler = { request ->
            if (request.url.encodedPath == "/watch_videos") FakeSpotify.html(request, "").newBuilder().code(303).header("Location", "https://www.youtube.com/watch?v=x").build()
            else FakeSpotify.html(request, youTubeMusicPage(youTubeMusicRow(rowTitle, "$rowArtist • Album • 3:00", videoId = "video000000")))
        }
        return runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata(title, artist)), {}) }?.url
    }

    @Test
    fun aQueueTakesTheClosestSongOfTheArtistWhenNoTitleIsTheSame() {
        val played = "https://music.youtube.com/watch?v=video000000"
        // A title in another script, written out in letters by YouTube Music.
        assertEquals(played, queueFinds("ふたりの夏物語 NEVER ENDING SUMMER", "S. Kiyotaka & Omega Tribe", "FUTARI NO NATSU MONOGATARI NEVER ENDING SUMMER", "S.Kiyotaka & Omega Tribe"))
        assertEquals(played, queueFinds("ガラスのPALM TREE", "S. Kiyotaka & Omega Tribe", "GLASS NO PALM TREE", "S.Kiyotaka & Omega Tribe"))
        // A classical work's long name, which every service writes its own way.
        assertEquals(played, queueFinds(
            "Ellens Gesang III, Op. 52 No. 6, D. 839 \"Ave Maria\" (Hymne an die Jungfrau)", "Franz Schubert, Daniel Perret",
            "Ellens Gesang III, D. 839, Op. 52 No. 6, Ave Maria", "Daniel Perret & Franz Schubert"
        ))
        // The artist credited with a guest, a tag or a "y", on one service and not the other.
        assertEquals(played, queueFinds("La Locura Automática", "La Secta AllStar, Eddie Dee", "La Locura Automática (Reggaeton) (feat. Eddie Dee)", "La Secta"))
        assertEquals(played, queueFinds("Soy una Gargola", "Alex Gargolas, Randy Nota Loca", "Soy Una Gargola", "Randy"))
        assertEquals(played, queueFinds("Holdin' On - Skrillex & Nero Remix", "I See MONSTAS, NERO, Skrillex", "Holdin' On (Skrillex & Nero Remix)", "Monsta"))
        assertEquals(played, queueFinds("Yo Voy (feat. Daddy Yankee)", "Zion & Lennox", "Yo Voy (feat. Daddy Yankee)", "Zion Y Lennox"))
        // An artist name that holds a comma is one name, and not two to match on.
        assertEquals(played, queueFinds("Safari", "125 Rue Montmartre", "Safari", "125, Rue Montmartre"))
        assertEquals(played, queueFinds("Safari", "125, Rue Montmartre", "Safari", "125 Rue Montmartre"))
        // A short name inside another's is no match: "Sia" is not "Asia", nor "Ed" in "Eddie".
        assertEquals(null, queueFinds("Heat of the Moment", "Sia", "Heat of the Moment", "Asia"))
        assertEquals(null, queueFinds("Love Me Do", "Ed", "Love Me Do", "Eddie"))
        // The song of another artist, or one that shares too little, is not taken.
        assertEquals(null, queueFinds("Perfect", "The Smashing Pumpkins", "Perfect", "Simple Plan"))
        assertEquals(null, queueFinds("Perfect Day", "Band", "Another Song Entirely", "Band"))
        assertEquals(null, queueFinds("Love Me Do", "Band", "Love Me Tender", "Band"))
        assertEquals(null, queueFinds("Song 7", "Band", "Song 5", "Band"))
        assertEquals(null, queueFinds("Perfect", "Band", "Perfect Day", "Band"))
        assertEquals(null, queueFinds("", "Band", "Anything", "Band"))
    }

    @Test
    fun aSingleSongIsStillOnlyOpenedWhenItsTitleIsTheSame() {
        respond(youTubeMusicPage(youTubeMusicRow("GLASS NO PALM TREE", "S.Kiyotaka & Omega Tribe • Album • 3:00", videoId = "video000000")))
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("ガラスのPALM TREE", "S. Kiyotaka & Omega Tribe")) })
    }

    @Test
    fun aQueueDoesNotTakeAnAlbumOrArtistLoosely() {
        respond(youTubeMusicPage(youTubeMusicRow("Road Trip", "Album • Band • 2020", browseId = "MPREb_x")))
        assertNull(runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata("Road Trip", "Band", ItemType.ALBUM))) {} })
    }

    @Test
    fun aQueueTakesAnExactMatchLowerInTheResultsOverACloseOneAtTheTop() {
        fake.handler = { request ->
            if (request.url.encodedPath == "/watch_videos") FakeSpotify.html(request, "").newBuilder().code(303).header("Location", "https://www.youtube.com/watch?v=x").build()
            else FakeSpotify.html(request, youTubeMusicPage(
                youTubeMusicRow("Glass No Palm Tree Live", "Band • Album • 3:00", videoId = "close000000"),
                youTubeMusicRow("Glass No Palm Tree", "Band • Album • 3:00", videoId = "exact000000")
            ))
        }
        val tracks = listOf(MusicMetadata("Glass No Palm Tree", "Band"))
        assertEquals("https://music.youtube.com/watch?v=exact000000", runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, tracks) {} }?.url)
        // A song's close match is kept apart from its exact one, so opening it alone still wants the same title.
        respond(youTubeMusicPage(youTubeMusicRow("GLASS NO PALM TREE", "Band • Album • 3:00", videoId = "other000000")))
        val romaji = MusicMetadata("ガラスのPALM TREE", "Band")
        assertEquals("https://music.youtube.com/watch?v=other000000", runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(romaji)) {} }?.url)
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, romaji) })
        // The same on YouTube, which opens the song YouTube Music's search finds.
        assertEquals("https://www.youtube.com/watch?v=other000000", runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE, listOf(romaji)) {} }?.url)
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE, romaji) })
    }

    @Test
    fun aQueueAsksAgainOnceWhenASongsSearchFailsButNotWhenItFindsNothing() {
        // Searches go out at once, on OkHttp's threads, so they're counted atomically.
        val asked = AtomicInteger()
        fake.handler = { request ->
            if (request.url.encodedPath == "/watch_videos") FakeSpotify.html(request, "").newBuilder().code(303).header("Location", "https://www.youtube.com/watch?v=x").build()
            else if (asked.incrementAndGet() == 1) throw IOException("slow moment")
            else FakeSpotify.html(request, youTubeMusicPage(youTubeMusicRow("Song", "Band • Album • 3:00", videoId = "video000000")))
        }
        assertEquals("https://music.youtube.com/watch?v=video000000", runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata("Song", "Band"))) {} }?.url)
        assertEquals(2, asked.get())

        // Nothing found is an answer, so it is not asked twice: once among the songs, once among the
        // videos, and Apple, in two stores, and Deezer for its other names. Failing twice gives up.
        asked.set(0)
        fake.handler = { request -> asked.incrementAndGet(); FakeSpotify.html(request, """{"contents":{}}""") }
        assertNull(runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata("Missing", "Nobody"))) {} })
        assertEquals(5, asked.get())
        asked.set(0)
        fake.handler = { asked.incrementAndGet(); throw IOException("offline") }
        assertNull(runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata("Missing", "Nobody"))) {} })
        assertEquals(2, asked.get())

        // A single song is asked once, so opening it doesn't wait longer on a slow network.
        asked.set(0)
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Missing", "Nobody")) })
        assertEquals(1, asked.get())
    }

    @Test
    fun youTubeOnlyOpensSongsFromTheSameSearch() {
        respond(youTubeMusicPage(youTubeMusicRow("Beyonce Song", "Carly Rae Jepsen • Album • 3:20", videoId = "exact000000")))
        assertEquals("https://www.youtube.com/watch?v=exact000000", runBlocking { matcher().find(MusicService.YOUTUBE, song) })

        fake.requestedUrls.clear()
        val album = MusicMetadata("After Hours", "The Weeknd", ItemType.ALBUM)
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE, album) })
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun aQueuesAnswersAreClosedAwayFromWhereItWasAskedFor() {
        // Asked for from the main thread, where Android stops the app for any use of the network: closing
        // YouTube's redirect, which isn't read, tells it so.
        val closedOn = CopyOnWriteArrayList<Thread>()
        fake.handler = { request ->
            val answer = if (request.url.encodedPath == "/watch_videos") {
                FakeSpotify.html(request, "").newBuilder().code(303).header("Location", "https://www.youtube.com/watch?v=first000000&list=TLGGqueue").build()
            } else {
                FakeSpotify.html(request, youTubeMusicPage(
                    youTubeMusicRow("First", "Band • Album • 3:00", videoId = "first000000"),
                    youTubeMusicRow("Second", "Band • Album • 3:00", videoId = "second00000")
                ))
            }
            val closing = object : ForwardingSource(answer.body.source()) {
                override fun close() {
                    closedOn += Thread.currentThread()
                    super.close()
                }
            }
            answer.newBuilder().body(closing.buffer().asResponseBody(answer.body.contentType())).build()
        }
        var ioThread: Thread? = null
        val io = Executors.newSingleThreadExecutor { Thread(it).also { thread -> ioThread = thread } }
        val queue = try {
            runBlocking { ExactMatcher(fake.client(), "AR", io.asCoroutineDispatcher()).youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata("First", "Band"), MusicMetadata("Second", "Band"))) {} }
        } finally {
            io.shutdown()
        }
        assertEquals("https://music.youtube.com/watch?v=first000000&list=TLGGqueue", queue?.url)
        assertTrue(closedOn.isNotEmpty())
        assertEquals(setOf(ioThread), closedOn.toSet())
    }

    @Test
    fun aQueueMatchesEachSongThenAsksYouTubeForATemporaryPlaylistOfThem() {
        var playlistAnswer: (Request) -> okhttp3.Response = { request ->
            FakeSpotify.html(request, "").newBuilder().code(303)
                .header("Location", "https://www.youtube.com/watch?v=first000000&list=TLGGqueue").build()
        }
        fake.handler = { request ->
            when {
                request.url.encodedPath == "/watch_videos" -> playlistAnswer(request)
                else -> FakeSpotify.html(request, youTubeMusicPage(
                    youTubeMusicRow("First", "Band • Album • 3:00", videoId = "first000000"),
                    youTubeMusicRow("Second", "Band • Album • 3:00", videoId = "second00000")
                ))
            }
        }
        val tracks = listOf(MusicMetadata("First", "Band"), MusicMetadata("Missing", "Nobody"), MusicMetadata("Second", "Band"))
        val looked = mutableListOf<Int>()
        val queue = runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, tracks) { looked += it } }
        assertEquals("https://music.youtube.com/watch?v=first000000&list=TLGGqueue", queue?.url)
        // The songs it plays are those found, as asked for.
        assertEquals(listOf(tracks[0], tracks[2]), queue?.songs)
        assertEquals(listOf(1, 2, 3), looked.sorted())
        assertTrue(fake.requestedUrls.any { java.net.URLDecoder.decode(it, "UTF-8").endsWith("watch_videos?video_ids=first000000,second00000") })

        // Without the temporary playlist, the first song still plays, in YouTube here.
        playlistAnswer = { throw IOException("offline") }
        assertEquals("https://www.youtube.com/watch?v=first000000", runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE, tracks) {} }?.url)

        // Nothing matched, nothing to play.
        assertNull(runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata("Missing", "Nobody"))) {} })
    }

    @Test
    fun aPlaylistsSongsGetTheirOwnCoversAndWhatsFoundIsRemembered() = runBlocking {
        val file = kotlin.io.path.createTempDirectory("lookups").toFile().resolve("lookups.json")
        fake.handler = { request ->
            when (request.url.host) {
                // A Spotify song's cover comes from its own link.
                "open.spotify.com" -> FakeSpotify.html(request, """{"thumbnail_url":"https://i.scdn.co/image/own"}""")
                else -> FakeSpotify.html(request, """{"data":[{"title":"Named","artist":{"name":"Band"},"album":{"cover_big":"https://cdn/named.jpg"}}]}""")
            }
        }
        val tracks = listOf(
            MusicMetadata("Own", "Band", url = "https://open.spotify.com/track/own"),
            MusicMetadata("Named", "Band"),
            MusicMetadata("Unknown", "Nobody")
        )
        val found = mutableMapOf<Int, String>()
        val matcher = ExactMatcher(fake.client(), "AR", kotlinx.coroutines.Dispatchers.Unconfined, LookupCache(file, kotlinx.coroutines.Dispatchers.Unconfined))
        matcher.covers(tracks) { index, cover -> found[index] = cover }
        assertEquals(mapOf(0 to "https://i.scdn.co/image/own", 1 to "https://cdn/named.jpg"), found)
        assertTrue(fake.requestedUrls.first { "spotify" in it }.startsWith("https://open.spotify.com/oembed?url=https"))

        // Found again without asking, even after a restart; what wasn't found is asked again.
        fake.requestedUrls.clear()
        val restarted = ExactMatcher(fake.client(), "AR", kotlinx.coroutines.Dispatchers.Unconfined, LookupCache(file, kotlinx.coroutines.Dispatchers.Unconfined))
        found.clear()
        restarted.covers(tracks) { index, cover -> found[index] = cover }
        assertEquals(2, found.size)
        // Asked of Deezer, then of Apple.
        assertEquals(2, fake.requestedUrls.size)
        assertEquals("https://cdn/named.jpg", restarted.cover(tracks[1]))

        // A song matched once is found again the same way.
        respond("""{"data":[{"title":"Named","artist":{"name":"Band"},"link":"https://www.deezer.com/track/1"}]}""")
        assertEquals("https://www.deezer.com/track/1", restarted.find(MusicService.DEEZER, tracks[1]))
        fake.handler = { throw IOException("offline") }
        assertEquals("https://www.deezer.com/track/1", restarted.find(MusicService.DEEZER, tracks[1]))
        // Apps that need an account to search aren't asked.
        assertNull(restarted.find(MusicService.TIDAL, tracks[1]))
        assertNull(restarted.cover(tracks[2]))
    }

    @Test
    fun aSongWithSeveralArtistsStillGetsACoverWhenSpotifyDoesNotAnswer() = runBlocking {
        fake.handler = { request ->
            when (request.url.host) {
                "open.spotify.com" -> throw IOException("busy")
                // Deezer credits the song to the first artist only.
                else -> FakeSpotify.html(request, """{"data":[{"title":"Baby","artist":{"name":"Justin Bieber"},"album":{"cover_big":"https://cdn/baby.jpg"}}]}""")
            }
        }
        val baby = MusicMetadata("Baby", "Justin Bieber, Ludacris", url = "https://open.spotify.com/track/baby")
        assertEquals("https://cdn/baby.jpg", matcher().cover(baby))
        // An artist who isn't credited still doesn't get the cover.
        assertNull(matcher().cover(baby.copy(artist = "Someone, Else", url = null)))
    }

    private companion object {
        /** YouTube Music's videos tab, as its search encodes it. */
        const val VIDEOS_TAB = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="
    }
    private fun find(target: MusicService, item: MusicMetadata) = runBlocking { matcher().find(target, item) }

    @Test
    fun tidalFindsSongsAlbumsAndArtistsInItsCatalogue() {
        fake.handler = { request ->
            val body = when (request.url.encodedPath) {
                "/v1/search/tracks" -> """{"items":[
                    {"id":1,"title":"Song","version":"Remix","artists":[{"name":"Band"}]},
                    {"id":2,"title":"Song","artists":[{"name":"Band"},{"name":"Guest"}]}
                ]}"""
                "/v1/search/albums" -> """{"items":[{"id":3,"title":"Record","artists":[{"name":"Band"}]}]}"""
                else -> """{"items":[{"id":4,"name":"Other"},{"id":5,"name":"Band"}]}"""
            }
            FakeSpotify.html(request, body)
        }
        // The remix is another recording.
        assertEquals("https://tidal.com/browse/track/2", find(MusicService.TIDAL, MusicMetadata("Song", "Band")))
        assertEquals("https://tidal.com/browse/album/3", find(MusicService.TIDAL, MusicMetadata("Record", "Band", ItemType.ALBUM)))
        assertEquals("https://tidal.com/browse/artist/5", find(MusicService.TIDAL, MusicMetadata("Band", "", ItemType.ARTIST)))
        assertNull(find(MusicService.TIDAL, MusicMetadata("Record", "Someone Else", ItemType.ALBUM)))
        // Offline, or answered with something else, e.g. an error page, nothing's found, and a search stands in.
        fake.handler = { throw IOException("offline") }
        assertNull(find(MusicService.TIDAL, MusicMetadata("Other Song", "Band")))
        respond("<html>Busy</html>")
        assertNull(find(MusicService.TIDAL, MusicMetadata("Third Song", "Band")))
    }

    /** SoundCloud with its key already read, answering searches with [results]. */
    private fun soundCloud(results: (String) -> String) {
        fake.handler = { request ->
            when {
                request.url.toString() == ServiceApis.SOUNDCLOUD_URL ->
                    FakeSpotify.html(request, """<script crossorigin src="https://a-v2.sndcdn.com/assets/1.js"></script>""")
                request.url.host == "a-v2.sndcdn.com" -> FakeSpotify.html(request, """client_id:"${"k".repeat(32)}${'"'}""")
                else -> FakeSpotify.html(request, """{"collection":${results(request.url.encodedPath.substringAfterLast('/'))}}""")
            }
        }
    }

    @Test
    fun soundCloudOnlyCountsTheArtistsOwnOrAVerifiedUpload() {
        soundCloud { kind ->
            when (kind) {
                "tracks" -> """[
                    {"title":"Song","user":{"username":"A Fan"},"publisher_metadata":{"artist":"Band"},"permalink_url":"https://soundcloud.com/fan/song"},
                    {"title":"Song","user":{"username":"Band"},"permalink_url":"https://soundcloud.com/band/song"}
                ]"""
                "albums" -> """[{"title":"Record","user":{"username":"Label","verified":true},"permalink_url":"https://soundcloud.com/label/sets/record"}]"""
                else -> """[
                    {"username":"Band","verified":false,"permalink_url":"https://soundcloud.com/band-impostor"},
                    {"username":"Band","verified":true,"permalink_url":"https://soundcloud.com/band"}
                ]"""
            }
        }
        // A fan's upload credited to the band isn't the band's.
        assertEquals("https://soundcloud.com/band/song", find(MusicService.SOUNDCLOUD, MusicMetadata("Song", "Band")))
        // A verified label's album, credited to it, needs the album's artist to be the label's name.
        assertNull(find(MusicService.SOUNDCLOUD, MusicMetadata("Record", "Band", ItemType.ALBUM)))
        assertEquals("https://soundcloud.com/label/sets/record", find(MusicService.SOUNDCLOUD, MusicMetadata("Record", "Label", ItemType.ALBUM)))
        assertEquals("https://soundcloud.com/band", find(MusicService.SOUNDCLOUD, MusicMetadata("Band", "", ItemType.ARTIST)))
        soundCloud { "[]" }
        assertNull(find(MusicService.SOUNDCLOUD, MusicMetadata("Missing", "Band")))
        // Not the answer a search gives.
        fake.handler = { request ->
            when {
                request.url.toString() == ServiceApis.SOUNDCLOUD_URL -> FakeSpotify.html(request, """<script crossorigin src="https://a-v2.sndcdn.com/assets/1.js"></script>""")
                request.url.host == "a-v2.sndcdn.com" -> FakeSpotify.html(request, """client_id:"${"k".repeat(32)}${'"'}""")
                else -> FakeSpotify.html(request, "[1]")
            }
        }
        assertNull(find(MusicService.SOUNDCLOUD, MusicMetadata("Other", "Band")))
    }

    @Test
    fun audiomackOnlyCountsTheArtistsOwnOrAVerifiedUpload() {
        fake.handler = { request ->
            val body = when (request.url.queryParameter("show")) {
                "songs" -> """[
                    {"title":"Song","artist":"Band","url_slug":"song-hd","uploader":{"name":"Reuploads","url_slug":"reuploads","verified":"no"}},
                    {"title":"Song","artist":"Band","url_slug":"blocked","geo_restricted":true,"uploader":{"name":"Band","url_slug":"band"}},
                    {"title":"Song","artist":"Band","url_slug":"song","uploader":{"name":"Label","url_slug":"label","verified":"yes"}}
                ]"""
                "albums" -> """[
                    {"title":"Record","artist":"Band","url_slug":"record","uploader":{"name":"Band","url_slug":"band"}},
                    {"title":"Record","artist":"Band","url_slug":"nobody"}
                ]"""
                else -> """[{"name":"Band","url_slug":"band-fake","verified":"authenticated"},{"name":"Band","url_slug":"band","verified":"yes"}]"""
            }
            FakeSpotify.html(request, """{"results":$body}""")
        }
        // The band's own upload can't be played in this country, so the label's is the one.
        assertEquals("https://audiomack.com/label/song/song", find(MusicService.AUDIOMACK, MusicMetadata("Song", "Band")))
        assertEquals("https://audiomack.com/band/album/record", find(MusicService.AUDIOMACK, MusicMetadata("Record", "Band", ItemType.ALBUM)))
        assertEquals("https://audiomack.com/band", find(MusicService.AUDIOMACK, MusicMetadata("Band", "", ItemType.ARTIST)))
        // Found with nothing to link to, or not at all.
        fake.handler = { request -> FakeSpotify.html(request, """{"results":[{"title":"Song","artist":"Band","url_slug":"","uploader":{"name":"Band"}},{"title":"Two","artist":"Band","url_slug":"two","uploader":{"name":"Band"}}]}""") }
        assertNull(find(MusicService.AUDIOMACK, MusicMetadata("Song", "Band")))
        assertNull(find(MusicService.AUDIOMACK, MusicMetadata("Two", "Band")))
        assertNull(find(MusicService.AUDIOMACK, MusicMetadata("Not There", "Band")))
        fake.handler = { request -> FakeSpotify.html(request, """{"results":[{"name":"Band","verified":"yes","url_slug":""}]}""") }
        assertNull(find(MusicService.AUDIOMACK, MusicMetadata("Band", "", ItemType.ARTIST)))
    }

    @Test
    fun qobuzIsAskedInTheStoreWhoseNumbersOpenOnThisPhone() {
        // A song's number differs between Qobuz's stores, and the app shows only a loading page for one
        // that isn't its country's: Expresso Love is 45242130 in the US store and 625089 in the others (#673).
        fake.handler = { request -> FakeSpotify.html(request, "") }
        fun asks(country: String): String {
            runBlocking { matcher(country).find(MusicService.QOBUZ, MusicMetadata("Song", "Band")) }
            return fake.requestedUrls.last().substringAfter("qobuz.com/").substringBefore("/")
        }
        assertEquals("us-en", asks("US"))
        // The rest share the UK's numbers, but Japan's store names songs in Japanese, so they'd never match,
        // and Latin America's stores and Portugal's list no numbers.
        listOf("GB", "IE", "DE", "AU", "JP", "AR", "BR", "PT", "").forEach { assertEquals(it, "gb-en", asks(it)) }
    }

    @Test
    fun qobuzsOtherStoreGivesTheNumberThisPhonesStoreDoesnt() {
        fun item(track: String) = """
            <li><div class="ListItem">
                <a href="/x/album/slug/a1" class="ListItem__title" title="Song">Song</a>
                <p class="ListItem__artists">Band • Album</p>
                <button data-url="&#x2F;v4&#x2F;ajax&#x2F;popin-add-cart&#x2F;track&#x2F;$track"></button>
            </div></li>"""
        val song = MusicMetadata("Song", "Band")
        fake.handler = { request ->
            when {
                "/us-en/" in request.url.toString() -> FakeSpotify.html(request, item("45242130"))
                else -> FakeSpotify.html(request, item("625089"))
            }
        }
        fun other(country: String, link: String) = runBlocking { matcher(country).otherQobuzLink(song, link) }
        assertEquals("https://play.qobuz.com/track/625089", other("US", "https://play.qobuz.com/track/45242130"))
        assertTrue(fake.requestedUrls.last().startsWith("https://www.qobuz.com/gb-en/search/tracks/"))
        assertEquals("https://play.qobuz.com/track/45242130", other("IE", "https://play.qobuz.com/track/625089"))
        assertTrue(fake.requestedUrls.last().startsWith("https://www.qobuz.com/us-en/search/tracks/"))
        // The same number there, there's nothing else to try.
        assertNull(other("IE", "https://play.qobuz.com/track/45242130"))
        // No answer from the store is no other number, not a failure to show.
        var failures = 0
        fake.handler = { request -> failures++; FakeSpotify.html(request, "busy", code = 503) }
        assertNull(other("US", "https://play.qobuz.com/track/45242130"))
        assertEquals(2, failures)
        var attempts = 0
        fake.handler = { request ->
            attempts++
            if (attempts == 1) throw java.net.SocketTimeoutException("slow store")
            FakeSpotify.html(request, item("625089"))
        }
        assertEquals("https://play.qobuz.com/track/625089", other("US", "https://play.qobuz.com/track/45242130"))
        assertEquals(2, attempts)
    }

    private fun qobuzItem(title: String, credit: String, track: String, album: String = "a1") = """
        <li><div class="ListItem">
            <a href="/us-en/album/slug/$album" class="ListItem__title" title="$title">$title</a>
            <p class="ListItem__artists">$credit</p>
            <button data-url="&#x2F;v4&#x2F;ajax&#x2F;popin-add-cart&#x2F;track&#x2F;$track"></button>
        </div></li>"""

    @Test
    fun qobuzFindsASongWhoseNameHasASlash() {
        // Qobuz's store answers 404 for a "/" in the search, even written %2F, so these songs had no number (#774, measured
        // 2026-11-07: AC/DC "Back In Black", Tove Styrke "Me/You (always and forever)"). Spaced, it finds them.
        val sent = CopyOnWriteArrayList<String>()
        fake.handler = { request ->
            sent += request.url.encodedPath
            if (request.url.pathSegments.size != 4 || "%2F" in request.url.encodedPath.uppercase()) {
                FakeSpotify.html(request, "Not found", code = 404)
            } else {
                FakeSpotify.html(
                    request,
                    qobuzItem("Me/You (always and forever)", "Tove Styrke • Me/You (always and forever)", "423808056", "album1") +
                        qobuzItem("Quevedo: Bzrp Music Sessions, Vol. 52/66", "Bizarrap • Quevedo: Bzrp Music Sessions, Vol. 52/66", "164512766", "album2") +
                        qobuzItem("Back In Black", "AC/DC • Back In Black", "19906024", "album3")
                )
            }
        }
        val me = MusicMetadata("Me/You (always and forever)", "Tove Styrke")
        assertEquals("https://play.qobuz.com/track/423808056", find(MusicService.QOBUZ, me))
        assertEquals("/gb-en/search/tracks/Me%20You%20(always%20and%20forever)%20Tove%20Styrke", sent.last())
        assertEquals("https://play.qobuz.com/track/164512766", find(MusicService.QOBUZ, MusicMetadata("Quevedo: Bzrp Music Sessions, Vol. 52/66", "Bizarrap")))
        assertEquals("https://play.qobuz.com/track/19906024", find(MusicService.QOBUZ, MusicMetadata("Back In Black", "AC/DC")))
        assertEquals("/gb-en/search/tracks/Back%20In%20Black%20AC%20DC", sent.last())
        // An album too, and the other store's number, which the retry asks for.
        assertEquals("https://play.qobuz.com/album/album3", find(MusicService.QOBUZ, MusicMetadata("Back In Black", "AC/DC", ItemType.ALBUM)))
        assertEquals("https://play.qobuz.com/track/19906024", runBlocking { matcher("IE").otherQobuzLink(MusicMetadata("Back In Black", "AC/DC"), "https://play.qobuz.com/track/1") })
        assertEquals("/us-en/search/tracks/Back%20In%20Black%20AC%20DC", sent.last())
        // " / " and a slash at an end leave one space, not a gap.
        find(MusicService.QOBUZ, MusicMetadata("A / B", "/Band/"))
        assertEquals("/gb-en/search/tracks/A%20B%20Band", sent.last())
    }

    private fun slowQobuz(timeoutMs: Long, vararg attempts: (Request) -> okhttp3.Response): Pair<ExactMatcher, AtomicInteger> {
        val asked = AtomicInteger()
        fake.handler = { request -> attempts[minOf(asked.getAndIncrement(), attempts.size - 1)](request) }
        return ExactMatcher(fake.client(), "US", timeoutMs = timeoutMs) to asked
    }

    private fun qobuzAfter(ms: Long): (Request) -> okhttp3.Response = { request ->
        Thread.sleep(ms)
        FakeSpotify.html(request, qobuzItem("Song", "Band • Album", "625089"))
    }

    private fun qobuzFailsAfter(ms: Long): (Request) -> okhttp3.Response = { _ ->
        Thread.sleep(ms)
        throw IOException("reset")
    }

    private val usNumber = "https://play.qobuz.com/track/45242130"

    @Test
    fun qobuzsOtherStoreStillGetsItsTimeToAnswerAfterTheFirstTryIsSlow() {
        // Android lets Crosstune open an app again only 9.5 s after the first launch (#745), and the store had two tries of 5 s
        // one after the other: the first, cut off at 5 s, wasted what it had waited for, and the second had what was left. A
        // store that takes 7 s to answer got nothing from either. Here 300 ms stand for 5 s: the first try answers at 700 ms,
        // the second, which went out at 300 ms, would at 1000.
        val (matcher, asked) = slowQobuz(300, qobuzAfter(700), qobuzAfter(700))
        assertEquals("https://play.qobuz.com/track/625089", runBlocking { matcher.otherQobuzLink(MusicMetadata("Song", "Band"), usNumber) })
        assertEquals(2, asked.get())
    }

    @Test
    fun qobuzsOtherStoreIsAskedAgainAtOnceWhenTheFirstTryFailsAndAfterAWhileWhenItIsSilent() {
        val song = MusicMetadata("Song", "Band")
        // Failed outright: the second try goes out at once, not after the wait.
        slowQobuz(5_000, qobuzFailsAfter(0), qobuzAfter(0)).let { (matcher, asked) ->
            assertEquals("https://play.qobuz.com/track/625089", runBlocking { matcher.otherQobuzLink(song, usNumber) })
            assertEquals(2, asked.get())
        }
        // Silent: the second try answers while the first is still stuck.
        slowQobuz(200, qobuzAfter(4_000), qobuzAfter(0)).let { (matcher, asked) ->
            val started = System.nanoTime()
            assertEquals("https://play.qobuz.com/track/625089", runBlocking { matcher.otherQobuzLink(song, usNumber) })
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
            assertEquals(2, asked.get())
        }
        // The second fails while the first is still on its way, and the first fails after the second went out.
        slowQobuz(200, qobuzAfter(900), qobuzFailsAfter(0)).let { (matcher, _) ->
            assertEquals("https://play.qobuz.com/track/625089", runBlocking { matcher.otherQobuzLink(song, usNumber) })
        }
        slowQobuz(200, qobuzFailsAfter(500), qobuzAfter(600)).let { (matcher, _) ->
            assertEquals("https://play.qobuz.com/track/625089", runBlocking { matcher.otherQobuzLink(song, usNumber) })
        }
        // Both failed: no other number.
        slowQobuz(200, qobuzFailsAfter(0), qobuzFailsAfter(0)).let { (matcher, asked) ->
            assertNull(runBlocking { matcher.otherQobuzLink(song, usNumber) })
            assertEquals(2, asked.get())
        }
    }

    @Test
    fun qobuzsOtherStoreIsAskedOnceWhenTheFirstTryAnswersInTime() {
        // Answered a little after it was asked, not at once, so a second that went out the moment the first was waited on shows.
        val (matcher, asked) = slowQobuz(1_000, qobuzAfter(150), qobuzAfter(0))
        val started = System.nanoTime()
        assertEquals("https://play.qobuz.com/track/625089", runBlocking { matcher.otherQobuzLink(MusicMetadata("Song", "Band"), usNumber) })
        // Handed over when the first answers, not once the wait is over, which a lookup that only looks at the wait does too, with its second try called off before it asks.
        assertTrue((System.nanoTime() - started) / 1_000_000 < 900)
        // Past the wait for the first, a second would have gone out.
        Thread.sleep(1_300)
        assertEquals(1, asked.get())
    }

    @Test
    fun qobuzsOtherStoreSendsNoSecondTryTheMomentTheFirstAnswers() {
        // A second try that goes out is called off the moment the lookup is done, and whether it got to ask first depends on
        // which thread got there first, so one lookup may not show it. Many on threads of their own do.
        val lookups = 100
        // Together, so that lookups which each wait out the timer, as they would if the first's answer weren't taken, end at once.
        val (matcher, asked) = slowQobuz(5_000, qobuzAfter(0))
        val links = runBlocking(Dispatchers.Default) {
            (1..lookups).map { async { matcher.otherQobuzLink(MusicMetadata("Song", "Band"), usNumber) } }.awaitAll()
        }
        assertEquals(List(lookups) { "https://play.qobuz.com/track/625089" }, links)
        assertEquals(lookups, asked.get())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun qobuzsOtherStoreTakesTheFirstTrysAnswerWhenItComesBeforeTheSecondsAndDoesntWaitForTheSecond() = runTest {
        val firstStarted = CountDownLatch(1)
        val retryStarted = CountDownLatch(1)
        val firstMayAnswer = CountDownLatch(1)
        val retryMayAnswer = CountDownLatch(1)
        val asked = AtomicInteger()
        fake.handler = { request ->
            val first = asked.getAndIncrement() == 0
            (if (first) firstStarted else retryStarted).countDown()
            (if (first) firstMayAnswer else retryMayAnswer).await()
            FakeSpotify.html(request, qobuzItem("Song", "Band • Album", if (first) "625089" else "625090"))
        }
        val retryAfter = 200L
        val matcher = ExactMatcher(fake.client(), "US", timeoutMs = retryAfter, ioDispatcher = StandardTestDispatcher(testScheduler))
        val lookup = async { matcher.otherQobuzLink(MusicMetadata("Song", "Band"), usNumber) }
        try {
            testScheduler.runCurrent()
            assertTrue("The first request reached the store", firstStarted.await(30, SECONDS))
            testScheduler.advanceTimeBy(retryAfter)
            testScheduler.runCurrent()
            assertTrue("The retry reached the store", retryStarted.await(30, SECONDS))
            firstMayAnswer.countDown()
            // The retry cannot answer until cleanup. The first answer must finish the lookup.
            assertEquals("https://play.qobuz.com/track/625089", lookup.await())
        } finally {
            firstMayAnswer.countDown()
            retryMayAnswer.countDown()
        }
    }

    @Test
    fun qobuzsOtherStoreCallsOffTheTriesStillOnTheirWayWhenItIsDone() {
        // A try waiting on the store goes on reading unless it's called off, and cost the phone its data for nothing.
        val calledOff = CopyOnWriteArrayList<Boolean>()
        fake.handler = { request -> Thread.sleep(1_200); FakeSpotify.html(request, qobuzItem("Song", "Band • Album", "625089")) }
        // Ahead of the fake, which answers without passing the request on.
        val client = fake.client().newBuilder().apply {
            interceptors().add(0, Interceptor { chain ->
                // A call called off fails instead of answering, so this looks at it either way.
                try {
                    chain.proceed(chain.request())
                } finally {
                    calledOff += chain.call().isCanceled()
                }
            })
        }.build()
        val matcher = ExactMatcher(client, "US", timeoutMs = 100)
        assertNull(runBlocking { kotlinx.coroutines.withTimeoutOrNull(400) { matcher.otherQobuzLink(MusicMetadata("Song", "Band"), usNumber) } })
        val deadline = System.nanoTime() + 5_000_000_000L
        while (calledOff.size < 2 && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals(listOf(true, true), calledOff.toList())
    }

    @Test
    fun qobuzsOtherStoreStopsWhenItsCallerGivesUpWithoutWaitingForTheTriesInFlight() {
        // At 9.5 s the retry is cancelled, see QobuzNumbers: tries still waiting on the store mustn't hold that up.
        val (matcher, asked) = slowQobuz(100, qobuzAfter(3_000), qobuzAfter(3_000))
        val started = System.nanoTime()
        val answer = runBlocking { kotlinx.coroutines.withTimeoutOrNull(400) { matcher.otherQobuzLink(MusicMetadata("Song", "Band"), usNumber) } }
        assertNull(answer)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500)
        assertEquals(2, asked.get())
    }

    @Test
    fun qobuzFindsSongsAndAlbumsOnItsStoresSearchPage() {
        fun item(title: String, credit: String, track: String, album: String) = """
            <li><div class="ListItem">
                <a href="/us-en/album/some-slug/$album" title="More details"><img/></a>
                <div class="ListItem__titleWithArtists">
                    <a href="/us-en/album/some-slug/$album" class="ListItem__title" title="$title">
                        $title
                    </a>
                    <p class="ListItem__artists">
                        $credit
                    </p>
                </div>
                <button data-url="&#x2F;v4&#x2F;ajax&#x2F;popin-add-cart&#x2F;track&#x2F;$track"></button>
            </div></li>"""
        val page = "<ul class=\"ListContainer\">" + item("Song (Live)", "Band • Live Record", "11", "live1") +
            item("Song", "Band\n •\n Studio &amp; Co", "12", "studio2") + item("Broken", "", "", "") + "</ul>"
        // Qobuz's store answers only a phone's browser, as measured: a desktop browser's name, or OkHttp's, gets a 403 (#673).
        fake.handler = { request ->
            if (request.header("User-Agent").orEmpty().contains("Mobile")) FakeSpotify.html(request, page) else FakeSpotify.html(request, "Access Denied", code = 403)
        }
        assertEquals("https://play.qobuz.com/track/12", find(MusicService.QOBUZ, MusicMetadata("Song", "Band")))
        assertTrue(fake.requestedUrls.last().startsWith("https://www.qobuz.com/gb-en/search/tracks/Song%20Band"))
        assertEquals("https://play.qobuz.com/album/studio2", find(MusicService.QOBUZ, MusicMetadata("Studio & Co", "Band", ItemType.ALBUM)))
        // Qobuz's artists aren't told apart, so there's nothing to look up, and the search stands in.
        val asked = fake.requestedUrls.size
        assertNull(find(MusicService.QOBUZ, MusicMetadata("Band", "", ItemType.ARTIST)))
        assertEquals(asked, fake.requestedUrls.size)
        assertEquals(false, canMatchExactly(MusicService.QOBUZ, ItemType.ARTIST))
        assertEquals(true, canMatchExactly(MusicService.QOBUZ, ItemType.ALBUM))
        // Where only the artist's own page counts, a release on a label's isn't found, so it isn't said
        // not to be there, nor is a Spotify song ListenBrainz doesn't know; a playlist with no songs
        // known is someone's own, so it is, anywhere.
        val song = MusicMetadata("Song", "Band")
        assertEquals(true, canBeMissing(MusicService.DEEZER, song))
        listOf(MusicService.BANDCAMP, MusicService.SOUNDCLOUD, MusicService.AUDIOMACK, MusicService.SPOTIFY).forEach { assertEquals(it.name, false, canBeMissing(it, song)) }
        assertEquals(true, canBeMissing(MusicService.BANDCAMP, MusicMetadata("Mix", "", ItemType.PLAYLIST)))
        fake.handler = { request -> FakeSpotify.html(request, "busy", code = 503) }
        assertNull(find(MusicService.QOBUZ, MusicMetadata("Other", "Band")))
    }

    /** [value] with some of what's in it swapped for something of another kind, or left out. */
    private fun mangled(value: Any?, random: kotlin.random.Random): Any? {
        if (random.nextInt(6) == 0) {
            return listOf(org.json.JSONObject.NULL, 0, -1, Long.MAX_VALUE, 1.5, true, "", "x".repeat(300), "https://elsewhere.example/1", org.json.JSONArray(), org.json.JSONObject(), org.json.JSONArray("[1,\"a\",null]")).random(random)
        }
        return when (value) {
            is org.json.JSONObject -> org.json.JSONObject().also { copy ->
                value.keys().forEach { key -> if (random.nextInt(8) != 0) copy.put(key, mangled(value.get(key), random)) }
            }
            is org.json.JSONArray -> org.json.JSONArray().also { copy ->
                (0 until value.length()).forEach { if (random.nextInt(8) != 0) copy.put(mangled(value.get(it), random)) }
                if (random.nextInt(4) == 0 && value.length() > 0) copy.put(mangled(value.get(0), random))
            }
            is String -> if (random.nextInt(4) == 0) value.take(random.nextInt(value.length + 1)) else value
            else -> value
        }
    }

    /** [text], as JSON with parts of another kind or left out, or else, as a page, cut short or with letters lost. */
    private fun mangledAnswer(text: String, random: kotlin.random.Random): String {
        val json = runCatching { org.json.JSONTokener(text).nextValue() }.getOrNull()
        if (json is org.json.JSONObject || json is org.json.JSONArray) return mangled(json, random).toString()
        val chars = StringBuilder(text)
        repeat(random.nextInt(1, 20)) { if (chars.isNotEmpty()) chars.deleteCharAt(random.nextInt(chars.length)) }
        return if (random.nextBoolean()) chars.toString() else chars.take(random.nextInt(chars.length + 1)).toString()
    }

    @Test
    fun anyServiceAnsweringWithBrokenResultsFindsNothingOrItsOwnLinkAndNeverCrashes() {
        val song = MusicMetadata("Song", "Band")
        val qobuz = """<li><div class="ListItem"><a href="/us-en/album/slug/rec1" class="ListItem__title" title="Song">Song</a>
            <p class="ListItem__artists">Band • Record</p><button data-url="&#x2F;v4&#x2F;ajax&#x2F;popin-add-cart&#x2F;track&#x2F;12"></button></div></li>"""
        // Each service's answer when it has the song, and where what it finds is.
        val answers: List<Triple<MusicService, String, (Request) -> String>> = listOf(
            Triple(MusicService.APPLE_MUSIC, "music.apple.com", { _ -> """{"results":[{"trackName":"Song","artistName":"Band","trackViewUrl":"https://music.apple.com/us/song/1"}]}""" }),
            Triple(MusicService.DEEZER, "www.deezer.com", { _ -> """{"data":[{"title":"Song","artist":{"name":"Band"},"link":"https://www.deezer.com/track/1"}]}""" }),
            Triple(MusicService.BANDCAMP, "band.bandcamp.com", { _ -> """{"auto":{"results":[{"type":"t","name":"Song","band_name":"Band","item_url_root":"https://band.bandcamp.com","item_url_path":"https://band.bandcamp.com/track/song"}]}}""" }),
            Triple(MusicService.TIDAL, "tidal.com", { _ -> """{"items":[{"id":2,"title":"Song","artists":[{"name":"Band"}]}]}""" }),
            Triple(MusicService.SOUNDCLOUD, "soundcloud.com", { request ->
                when {
                    request.url.toString() == ServiceApis.SOUNDCLOUD_URL -> """<script crossorigin src="https://a-v2.sndcdn.com/assets/1.js"></script>"""
                    request.url.host == "a-v2.sndcdn.com" -> """client_id:"${"k".repeat(32)}${'"'}"""
                    else -> """{"collection":[{"title":"Song","user":{"username":"Band"},"permalink_url":"https://soundcloud.com/band/song"}]}"""
                }
            }),
            Triple(MusicService.AUDIOMACK, "audiomack.com", { _ -> """{"results":[{"title":"Song","artist":"Band","url_slug":"song","uploader":{"name":"Band","url_slug":"band"}}]}""" }),
            Triple(MusicService.QOBUZ, "play.qobuz.com", { _ -> "<ul class=\"ListContainer\">$qobuz</ul>" }),
            Triple(MusicService.YOUTUBE_MUSIC, "music.youtube.com", { _ -> youTubeMusicPage(youTubeMusicRow("Song", "Band • Record • 3:00", videoId = "abcdefghijk")) })
        )
        val random = kotlin.random.Random(230)
        val odd = mutableListOf<String>()
        for ((service, host, answer) in answers) {
            fake.handler = { request -> FakeSpotify.html(request, answer(request)) }
            // Whole, it's found where it is.
            val found = runBlocking { matcher().lookFor(service, song) }
            assertTrue("$service: $found", (found as? ExactMatcher.Match.Found)?.url?.toHttpUrl()?.host == host)
            repeat(150) {
                fake.handler = { request -> FakeSpotify.html(request, mangledAnswer(answer(request), random)) }
                when (val match = runBlocking { matcher().lookFor(service, song) }) {
                    is ExactMatcher.Match.Found -> if (match.url.toHttpUrlOrNull()?.host?.endsWith(host.substringAfter("www.")) != true) odd += "$service: ${match.url}"
                    else -> Unit
                }
            }
        }
        assertEquals(emptyList<String>(), odd)
    }
}
