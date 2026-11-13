package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
        assertNull(runBlocking { matcher().cover(MusicMetadata("!!!", "Adele")) })
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
        assertNull(runBlocking { matcher().cover(MusicMetadata("!!!", "Hollies")) })
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

    /** A video put up by a fan that names the song both ways, as its Latin name and as [own]. */
    private fun bothWays(own: String = "残酷な天使のテーゼ", latin: String = "A Cruel Angel's Thesis") =
        youTubeMusicRow("$own ($latin) - Yoko Takahashi", "Fan • 1M views • 4:06", videoId = "video000000")

    /**
     * What [angel]'s search on YouTube Music finds among [rows], each "name" to "artist • album • length", with
     * Apple's lengths and names, or else Deezer's [deezerSeconds], and [videos]. When nothing is found, the songs
     * shown to pick from go to [shown], and [picked] is the answer: none by default, null when it can't say.
     */
    private fun findAngel(
        rows: List<Pair<String, String>>,
        lengthMs: Long? = 245_093,
        japan: String? = null,
        korea: String? = null,
        videos: List<String> = emptyList(),
        deezerSeconds: Long? = null,
        shown: MutableList<List<String>> = mutableListOf(),
        picked: Int? = SAYS_NONE
    ): String? = runBlocking {
        namesAndTabs(
            songs = rows.mapIndexed { index, (name, details) -> youTubeMusicRow(name, details, videoId = "row${index}0000000") },
            videos = videos,
            apple = lengthMs?.let { mapOf("AR" to appleSong("A Cruel Angel's Thesis (Director's Edit Version)", "Yoko Takahashi", it)) }.orEmpty(),
            elsewhere = listOfNotNull(japan?.let { "JP" to appleSong(it, "高橋洋子", lengthMs ?: 0) }, korea?.let { "KR" to appleSong(it, "Yoko Takahashi", lengthMs ?: 0) }).toMap(),
            deezer = deezerSeconds?.let { """[{"title":"A Cruel Angel's Thesis","artist":{"name":"Yoko Takahashi"},"duration":$it}]""" } ?: "[]"
        )
        ExactMatcher(fake.client(), "AR", pick = { _, songs, videos -> if (videos) null else picked.also { shown += songs } }).find(MusicService.YOUTUBE_MUSIC, angel)
    }

    private val angel = MusicMetadata("A Cruel Angel's Thesis", "Yoko Takahashi")

    private val named = "残酷な天使のテーゼ - Zankoku na Tenshi no Thesis"

    @Test
    fun aSongInAnotherScriptByTheArtistAndAsLongOpensAsOnMain() {
        // Its artist and its length are the song's: main opened it, without asking, whatever the videos say.
        val row = listOf(named to "Yoko Takahashi • Zankoku na Tenshi no Thesis • 4:06")
        for (video in listOf(bothWays(), bothWays(latin = "Evangelion"), bothWays(own = "Opening"), bothWays(own = "魂のルフラン"))) {
            assertEquals(video, "https://music.youtube.com/watch?v=row00000000", findAngel(row, videos = listOf(video)))
        }
        // Not another artist's, another length, or a title in Latin letters, which is compared by its words.
        assertNull(findAngel(listOf(named to "Someone Else • Zankoku na Tenshi no Thesis • 4:06")))
        assertNull(findAngel(listOf(named to "Yoko Takahashi • Zankoku na Tenshi no Thesis • 4:20")))
        assertNull(findAngel(listOf("Another Song" to "Yoko Takahashi • Another Album • 4:06"), videos = listOf(bothWays("Another Song"))))
        // A store's name with no letters is in no script, so it says nothing of the row.
        assertEquals("https://music.youtube.com/watch?v=row00000000", findAngel(row, korea = "…"))
    }

    @Test
    fun aLengthOfNothingFromDeezerIsNoLength() {
        // So the song picked is taken, as with no length known, not turned down as far from it.
        val row = listOf(named to "Yoko Takahashi • Zankoku na Tenshi no Thesis • 4:06")
        assertEquals("https://music.youtube.com/watch?v=row00000000", findAngel(row, lengthMs = null, deezerSeconds = 0, picked = 0))
    }

    @Test
    fun theSongInAnotherScriptMainOpenedOpensWithoutAskingWhenNothingSaysItsAnother() {
        // PR #797's song, as main opened it: ChatGPT isn't asked, so what it would say changes nothing.
        val rows = listOf("Fake Song" to "Yoko Takahashi • Fake Album • 3:00", named to "Yoko Takahashi • Zankoku na Tenshi no Thesis • 4:06")
        for (picked in listOf(null, SAYS_NONE, 0)) {
            val shown = mutableListOf<List<String>>()
            assertEquals("$picked", "https://music.youtube.com/watch?v=row10000000", findAngel(rows, shown = shown, picked = picked))
            assertEquals(emptyList<List<String>>(), shown)
        }
        assertEquals("https://music.youtube.com/watch?v=row10000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, angel) })
        // On its single and its album, the first opens.
        val twice = listOf("残酷な天使のテーゼ" to "Yoko Takahashi • A • 4:05", named to "Yoko Takahashi • B • 4:06")
        assertEquals("https://music.youtube.com/watch?v=row00000000", findAngel(twice))
        // With no store name in Japanese to tell them apart, another song as long, first, opens as on main: Issue #983.
        // So does one that names itself in Latin letters, which may be how the song reads.
        assertEquals("https://music.youtube.com/watch?v=row00000000", findAngel(listOf("魂のルフラン" to "Yoko Takahashi • B • 4:05") + twice))
        assertEquals("https://music.youtube.com/watch?v=row00000000", findAngel(listOf("グッドバイ・サマーブリーズ - Good Bye Summer Breeze" to "Yoko Takahashi • B • 4:06")))
        // A sign after a dash, or Latin words among its own letters, are no name of its own.
        for (name in listOf("残酷な天使のテーゼ - ♪", "Fake 残酷な天使のテーゼ")) {
            assertEquals(name, "https://music.youtube.com/watch?v=row00000000", findAngel(listOf(name to "Yoko Takahashi • B • 4:06")))
        }
        // ChatGPT, slow, isn't waited for, as it isn't asked.
        val slow = runBlocking {
            namesAndTabs(songs = rows.mapIndexed { index, (name, details) -> youTubeMusicRow(name, details, videoId = "row${index}0000000") },
                apple = mapOf("AR" to appleSong("A Cruel Angel's Thesis (Director's Edit Version)", "Yoko Takahashi", 245_093)))
            ExactMatcher(fake.client(), "AR", pick = { _, _, _ -> kotlinx.coroutines.delay(5_000); 0 }, pickTimeoutMs = 100).lookFor(MusicService.YOUTUBE_MUSIC, angel)
        }
        assertEquals(ExactMatcher.Match.Found("https://music.youtube.com/watch?v=row10000000"), slow)
    }

    @Test
    fun aSongWrittenAnotherWayInItsOwnScriptIsTheSong() {
        // Taiwan's 小幸運, as China writes it on YouTube Music, with only Deezer's length: main opened it, as is.
        fun find(song: MusicMetadata, row: String, deezer: String) = runBlocking {
            namesAndTabs(
                songs = listOf(youTubeMusicRow("Fake Song", "${song.artist} • Fake Album • 3:00", videoId = "other000000"), youTubeMusicRow(row, "${song.artist} • $row • 4:26", videoId = "row00000000")),
                deezer = """[{"title":"$deezer","artist":{"name":"${song.artist}"},"duration":266}]"""
            )
            matcher().find(MusicService.YOUTUBE_MUSIC, song)
        }
        assertEquals("https://music.youtube.com/watch?v=row00000000", find(MusicMetadata("小幸運", "田馥甄"), "小幸运", "小幸運"))
        // In kana or in kanji, read alike.
        assertEquals("https://music.youtube.com/watch?v=row00000000", find(MusicMetadata("さくら", "Fake Band"), "桜", "さくら"))
        assertEquals("https://music.youtube.com/watch?v=row00000000", find(MusicMetadata("桜", "Fake Band"), "さくら", "桜"))
        // Kana against kanji can't be told apart, read otherwise or not, so main's row stays: only what says it's
        // another takes it away.
        assertEquals("https://music.youtube.com/watch?v=row00000000", find(MusicMetadata("さくら", "Fake Band"), "桜の雨", "さくら"))
        // Nor whatever their sound marks, nor two kanji that read alike: no reading tells 雨 from 飴.
        for ((song, row) in listOf("ばら" to "原", "きんか" to "銀河", "かっこう" to "学校", "雨" to "飴")) {
            assertEquals(row, "https://music.youtube.com/watch?v=row00000000", find(MusicMetadata(song, "Fake Band"), row, song))
        }
        // Kanji against kanji that read otherwise are two songs.
        assertNull(find(MusicMetadata("小幸運", "田馥甄"), "小日子", "小幸運"))
    }

    @Test
    fun aVideoOrSongThatSaysItsAnotherVersionWithAWordRunOnIsntTheSong() {
        // A fan's karaoke, as long as the song and naming it and its singer, as main kept out.
        val karaoke = youTubeMusicRow("残酷な天使のテーゼ / 高橋洋子 (カラオケバージョン)", "Fan • 1M views • 4:06", videoId = "karaoke0000")
        assertNull(findAngel(emptyList(), japan = "残酷な天使のテーゼ", videos = listOf(karaoke)))
        val video = youTubeMusicRow("残酷な天使のテーゼ / 高橋洋子", "Fan • 1M views • 4:06", videoId = "video000000")
        assertEquals("https://music.youtube.com/watch?v=video000000", findAngel(emptyList(), japan = "残酷な天使のテーゼ", videos = listOf(video)))
        // A piano cover and a live clip of Spring Day.
        val springDay = MusicMetadata("Spring Day", "BTS")
        fun find(name: String) = runBlocking {
            namesAndTabs(
                videos = listOf(youTubeMusicRow(name, "Fan • 1M views • 4:34", videoId = "video000000")),
                apple = mapOf("AR" to appleSong("Spring Day", "BTS", 274_000)), elsewhere = mapOf("KR" to appleSong("봄날", "방탄소년단", 274_000))
            )
            matcher().find(MusicService.YOUTUBE_MUSIC, springDay)
        }
        assertNull(find("봄날 - BTS (피아노커버)"))
        assertNull(find("봄날 - BTS (라이브영상)"))
        assertEquals("https://music.youtube.com/watch?v=video000000", find("봄날 - BTS"))
    }

    @Test
    fun aSongInAnotherScriptOnItsOwnAlbumOrSayingWhatItsOwnTitleSaysIsTheSong() {
        // "Nagoriyuki (Live)" is live, so "なごり雪 (ライブ)" is it; a song on its own tribute album, as main opened them.
        fun find(title: String, row: String, album: String) = runBlocking {
            namesAndTabs(
                songs = listOf(youTubeMusicRow(row, "Fake Band • $album • 4:06", videoId = "row00000000")),
                apple = mapOf("AR" to appleSong(title, "Fake Band", 245_093).replace("}", ""","collectionName":"$album"}"""))
            )
            matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata(title, "Fake Band"))
        }
        assertEquals("https://music.youtube.com/watch?v=row00000000", find("Nagoriyuki (Live)", "なごり雪 (ライブ)", "Folk Festival"))
        assertEquals("https://music.youtube.com/watch?v=row00000000", find("Zettai Zetsumei", "絶体絶命", "Momoe Yamaguchi Tribute Selection"))
        // Saying what its title doesn't, it's another version.
        assertNull(find("Nagoriyuki", "なごり雪 (ライブ)", "Folk Festival"))
        assertNull(find("Nagoriyuki (Live)", "なごり雪 (カラオケ)", "Folk Festival"))
        // Its album is the song's, so it says nothing of what the row is: the instrumental on "& Instrumental" isn't the song.
        assertNull(find("Fake Song", "偽の歌 (Instrumental)", "Naughty Boys & Instrumental"))
        assertEquals("https://music.youtube.com/watch?v=row00000000", find("Fake Song", "偽の歌", "Naughty Boys & Instrumental"))
    }


    @Test
    fun aHalfThatSaysTheSongsOwnVersionIsTheSongAsOnMain() {
        // Sixth review, rule F: "Live" after the dash is no other version when the song is the live one.
        fun find(title: String, japan: String?, row: String, album: String = "Fake Album") = runBlocking {
            namesAndTabs(
                songs = listOf(youTubeMusicRow(row, "Fake Band • $album • 4:06", videoId = "row00000000")),
                apple = mapOf("AR" to appleSong(title, "Fake Band", 245_093)),
                elsewhere = japan?.let { mapOf("JP" to appleSong(it, "Fake Band", 245_093)) }.orEmpty()
            )
            matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata(title, "Fake Band"))
        }
        val row = "https://music.youtube.com/watch?v=row00000000"
        assertEquals(row, find("Fake Song (Live)", "偽の歌 (Live)", "偽の歌 - Live"))
        assertEquals(row, find("Fake Song (Remix)", "偽の歌 (Remix)", "偽の歌 - Remix"))
        assertEquals(row, find("Fake Song (Live)", "偽の歌 (Live)", "偽の歌 (Live) - Fake Song"))
        assertEquals(row, find("Fake Song (Live)", "偽の歌 (Live)", "Fake Song - ライブ"))
        // Saying a version the song isn't, it's still another.
        assertNull(find("Fake Song", "偽の歌", "偽の歌 - Live"))
        assertNull(find("Fake Song (Live)", "偽の歌 (Live)", "偽の歌 - Acoustic", album = "Live at Fake Hall"))
        assertNull(find("Fake Song", null, "Fake Song - ライブ"))
        assertNull(find("Live Forever", "曲", "曲 - Live"))
    }

    /** A video on Fake Band's channel, 1:30, for [title], also called [japan] in Japan's store, with no song row. */
    private fun findVideo(title: String, video: String, japan: String? = null) = runBlocking {
        namesAndTabs(
            videos = listOf(youTubeMusicRow(video, "Fake Band • 1M views • 1:30", videoId = "video000000")),
            apple = mapOf("AR" to appleSong(title, "Fake Band", 90_000)),
            elsewhere = japan?.let { mapOf("JP" to appleSong(it, "Fake Band", 90_000)) }.orEmpty()
        )
        matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata(title, "Fake Band"))
    }

    @Test
    fun theSongMainOpenedGoesBeforeAnyVideoAsOnMain() {
        fun find(songs: List<String>, picked: Int?, video: String = "Fake Band - Fake Song", channel: String = "Fan", length: String = "1:30") = runBlocking {
            namesAndTabs(
                songs = songs.map { youTubeMusicRow(it, "Fake Band • Fake Album • 1:30", videoId = "row00000000") },
                videos = listOf(youTubeMusicRow(video, "$channel • 1M views • $length", videoId = "video000000")),
                apple = mapOf("AR" to appleSong("Fake Song", "Fake Band", 90_000))
            )
            ExactMatcher(fake.client(), "AR", pick = { _, _, _ -> picked }).find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Fake Song", "Fake Band"))
        }
        val row = "https://music.youtube.com/watch?v=row00000000"
        val video = "https://music.youtube.com/watch?v=video000000"
        // Someone's upload that names the song: the artist's own song, as main opened it, without asking ChatGPT.
        for (picked in listOf(null, 0, SAYS_NONE)) assertEquals("$picked", row, find(listOf("偽の歌"), picked))
        // With no song main opened, the video opens before ChatGPT is asked, as on main.
        assertEquals(video, find(emptyList(), 0))
        // Main's song, with another after it, opens too: nothing says it's another.
        assertEquals(row, find(listOf("偽の歌", "別の歌"), null))
        // The artist's own video, whatever it says, opens only where main's song doesn't.
        for ((name, length) in listOf(
            "Fake Song (Official Video)" to "1:30", "Fake Song (Official Video) [2019]" to "1:30", "Fake Song (Teaser)" to "0:30",
            "Fake Song (English Version)" to "1:30", "Fake Song (Official Video)" to "0:30", "Fake Song (公式 ライブ)" to "1:30"
        )) {
            assertEquals(name, row, find(listOf("偽の歌"), null, video = name, channel = "Fake Band", length = length))
        }
    }

    @Test
    fun aVideoSayingVerIsAnotherVersionAsOnMain() {
        assertEquals("https://music.youtube.com/watch?v=video000000", findVideo("Fake Song (Single Version)", "Fake Song"))
        for (tag in listOf("TV", "English", "Short", "Dance", "Live")) {
            assertNull(tag, findVideo("Fake Song (Single Version)", "Fake Song ($tag ver.)"))
        }
    }

    @Test
    fun aVideoOfABandLiveIsAnotherVersionAsOnMain() {
        assertEquals("https://music.youtube.com/watch?v=video000000", findVideo("Fake Song", "Fake Song"))
        for (live in listOf("バンドライブ", "サウンドライブ", "ワールドライブ", "밴드라이브")) {
            assertNull(live, findVideo("Fake Song", "Fake Song ($live)"))
        }
    }

    @Test
    fun aVideoSayingInItsScriptWhatTheSongsTitleSaysIsAnotherVersionAsOnMain() {
        // "Live Forever" is called "曲" in Japan: "曲 (ライブ)" is a live one, as main had it.
        assertEquals("https://music.youtube.com/watch?v=video000000", findVideo("Live Forever", "曲", japan = "曲"))
        assertNull(findVideo("Live Forever", "曲 (ライブ)", japan = "曲"))
    }

    @Test
    fun aNameIsReadOnTheThreadForReading() {
        // Where each name is really read, as the first read loads a dictionary: never on the thread that asks. Debug runs
        // add the coroutine to the thread's name.
        val readOn = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reader = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "reading") }
        val read = { name: String, album: String, titles: Collection<String>, artists: Collection<String> ->
            readOn += Thread.currentThread().name.substringBefore(" @coroutine#")
            SongNames.saysAnother(name, album, titles, artists)
        }
        val found = try {
            runBlocking {
                namesAndTabs(
                    songs = listOf(youTubeMusicRow("桜", "Fake Band • 桜 • 4:26", videoId = "row00000000")),
                    deezer = """[{"title":"さくら","artist":{"name":"Fake Band"},"duration":266}]"""
                )
                ExactMatcher(fake.client(), "AR", readingDispatcher = reader.asCoroutineDispatcher(), saysAnother = read)
                    .find(MusicService.YOUTUBE_MUSIC, MusicMetadata("さくら", "Fake Band"))
            }
        } finally {
            reader.shutdown()
        }
        assertEquals("https://music.youtube.com/watch?v=row00000000", found)
        assertEquals(setOf("reading"), readOn.toSet())
    }

    @Test
    fun whatChatGptPicksForADoubtedRowPassesTheSameCheckOnTheThreadForReading() {
        // Main's row is on an album that says it's another recording. What ChatGPT picks in its place opens only where
        // nothing says it's another too, checked where names are read.
        fun find(picked: Int) = run {
            val readOn = java.util.Collections.synchronizedList(mutableListOf<String>())
            val reader = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "reading") }
            val doubts = { _: String, album: String, _: Collection<String>, _: Collection<String> ->
                readOn += Thread.currentThread().name.substringBefore(" @coroutine#")
                "Other" in album
            }
            val found = try {
                runBlocking {
                    namesAndTabs(
                        // In kanji, where the song is in kana, so main's own steps don't open the first by its name.
                        songs = listOf(
                            youTubeMusicRow("桜", "Fake Band • Other Album • 4:26", videoId = "row00000000"),
                            youTubeMusicRow("桜", "Fake Band • Other Picks • 4:26", videoId = "row00000001"),
                            youTubeMusicRow("桜", "Fake Band • Fake Album • 4:26", videoId = "row00000002")
                        ),
                        deezer = """[{"title":"さくら","artist":{"name":"Fake Band"},"duration":266}]"""
                    )
                    ExactMatcher(fake.client(), "AR", pick = { _, _, videos -> picked.takeUnless { videos } }, readingDispatcher = reader.asCoroutineDispatcher(), saysAnother = doubts)
                        .find(MusicService.YOUTUBE_MUSIC, MusicMetadata("さくら", "Fake Band"))
                }
            } finally {
                reader.shutdown()
            }
            assertEquals(setOf("reading"), readOn.toSet())
            found
        }
        assertEquals("https://music.youtube.com/watch?v=row00000002", find(2))
        assertNull(find(1))
        assertNull(find(0))
    }

    @Test
    fun aSongInAnotherScriptIsNotAnAccentedTitleOrItsKaraoke() {
        fun find(song: MusicMetadata, row: String, details: String, appleTitle: String) = runBlocking {
            namesAndTabs(
                songs = listOf(youTubeMusicRow(row, details, videoId = "wrong000000")), videos = listOf(bothWays(row, song.title)),
                apple = mapOf("AR" to appleSong(appleTitle, song.artist, 245_000))
            )
            matcher().find(MusicService.YOUTUBE_MUSIC, song)
        }
        assertNull(find(MusicMetadata("Fake Song", "Fake Singer"), "Canción Falsa", "Fake Singer • Otro Álbum • 4:06", "Fake Song"))
        assertNull(find(angel, "残酷な天使のテーゼ (オフ・ヴォーカル)", "Yoko Takahashi • 残酷な天使のテーゼ • 4:06", "A Cruel Angel's Thesis"))
    }

    @Test
    fun accentedLettersAreNoOtherScript() {
        // As long as the song, by its artist, alone and named both ways: only its letters, Latin ones, keep it out.
        assertNull(findAngel(listOf("Canción Cruel" to "Yoko Takahashi • Otro Álbum • 4:05"), videos = listOf(bothWays("Canción Cruel"))))
        assertEquals("https://music.youtube.com/watch?v=row00000000", findAngel(listOf("残酷な天使のテーゼ" to "Yoko Takahashi • Otro Álbum • 4:05"), videos = listOf(bothWays())))
        // "〇", a circle drawn as a kanji numeral, is no letter: the name is still in Latin letters, as the song's own is,
        // so it isn't held up against its Japanese one.
        val circled = listOf("A Cruel Angel's Thesıs 〇" to "Yoko Takahashi • Otro Álbum • 4:05")
        assertEquals("https://music.youtube.com/watch?v=row00000000", findAngel(circled, japan = "残酷な天使のテーゼ"))
    }

    @Test
    fun aSongInAnotherScriptThatSaysItsAnotherVersionIsNotTheSong() {
        listOf(
            "残酷な天使のテーゼ (オフ・ヴォーカル)" to "Yoko Takahashi • 残酷な天使のテーゼ • 4:05",
            "残酷な天使のテーゼ (Off Vocal)" to "Yoko Takahashi • 残酷な天使のテーゼ • 4:05",
            "残酷な天使のテーゼ (TV Size)" to "Yoko Takahashi • 残酷な天使のテーゼ • 4:05",
            "残酷な天使のテーゼ (インスト)" to "Yoko Takahashi • 残酷な天使のテーゼ • 4:05",
            "残酷な天使のテーゼ (リミックス)" to "Yoko Takahashi • 残酷な天使のテーゼ • 4:05",
            "残酷的天使的命题 (纯音乐)" to "Yoko Takahashi • 残酷的天使的命题 • 4:05",
            "잔혹한 천사의 테제 (반주)" to "Yoko Takahashi • 잔혹한 천사의 테제 • 4:05"
        ).forEach { row ->
            val shown = mutableListOf<List<String>>()
            assertNull(row.first, findAngel(listOf(row), videos = listOf(bothWays(SongNames.bareTitle(row.first))), shown = shown))
            // Nor is it shown first to pick from.
            assertEquals(row.first, listOf("${row.first} | ${row.second}"), shown.single())
        }
        // Only its album says so, which a compilation's says as often: it opens as main opened it, without asking.
        val shown = mutableListOf<List<String>>()
        val karaoke = "残酷な天使のテーゼ" to "Yoko Takahashi • Karaoke Picks • 4:05"
        assertEquals("https://music.youtube.com/watch?v=row00000000", findAngel(listOf(karaoke), videos = listOf(bothWays()), shown = shown, picked = SAYS_NONE))
        assertEquals(emptyList<List<String>>(), shown)
    }

    @Test
    fun aSongInAnotherScriptIsWithinThreeSecondsOfAKnownLength() {
        // 4:06 is 246 s: another release of one recording, as main had it, from Apple's milliseconds or Deezer's seconds.
        val row = listOf("残酷な天使のテーゼ" to "Yoko Takahashi • 残酷な天使のテーゼ • 4:06")
        val videos = listOf(bothWays())
        for (lengthMs in listOf(243_000L, 249_000L)) assertEquals("$lengthMs", "https://music.youtube.com/watch?v=row00000000", findAngel(row, lengthMs, videos = videos))
        for (lengthMs in listOf(242_999L, 249_001L)) assertNull("$lengthMs", findAngel(row, lengthMs, videos = videos))
        for (seconds in listOf(243L, 249L)) assertEquals("$seconds", "https://music.youtube.com/watch?v=row00000000", findAngel(row, lengthMs = null, videos = videos, deezerSeconds = seconds))
        for (seconds in listOf(242L, 250L)) assertNull("$seconds", findAngel(row, lengthMs = null, videos = videos, deezerSeconds = seconds))
    }

    @Test
    fun twoSongsInAnotherScriptAsLongAreToldApartOnlyByAStoresName() {
        val rows = listOf("Fake Song" to "Yoko Takahashi • A • 3:00", "魂のルフラン" to "Yoko Takahashi • B • 4:05", "残酷な天使のテーゼ" to "Yoko Takahashi • A • 4:05")
        // With no store name in Japanese, main's, the first as long, opens, whatever a video names: Issue #983.
        assertEquals("https://music.youtube.com/watch?v=row10000000", findAngel(rows))
        assertEquals("https://music.youtube.com/watch?v=row10000000", findAngel(rows, videos = listOf(bothWays())))
        // Japan's store names the other, so main opens it by that name.
        assertEquals("https://music.youtube.com/watch?v=row20000000", findAngel(rows, japan = "残酷な天使のテーゼ"))
        // One song given twice is one.
        val twice = listOf("残酷な天使のテーゼ" to "Yoko Takahashi • A • 4:05", "残酷な天使のテーゼ" to "Yoko Takahashi • A • 4:05")
        assertEquals("https://music.youtube.com/watch?v=xyVatKhd4s4", runBlocking {
            namesAndTabs(
                songs = twice.map { (name, details) -> youTubeMusicRow(name, details, videoId = "xyVatKhd4s4") }, videos = listOf(bothWays()),
                apple = mapOf("AR" to appleSong("A Cruel Angel's Thesis (Director's Edit Version)", "Yoko Takahashi", 245_093))
            )
            matcher().find(MusicService.YOUTUBE_MUSIC, angel)
        })
    }

    @Test
    fun aStoresNameInThatScriptTellsTheSongFromAnotherAsLong() {
        val japan = "残酷な天使のテーゼ (Director's Edit Version)"
        val rows = listOf("魂のルフラン" to "Yoko Takahashi • B • 4:05", named to "Yoko Takahashi • A • 4:05")
        // Named as Japan's store names it, but for what it adds in brackets, main's row opens.
        assertEquals("https://music.youtube.com/watch?v=row00000000", findAngel(rows.reversed(), japan = japan))
        // Named otherwise in Japanese, main's is another song, however alone or named both ways: nothing opens but
        // what ChatGPT picks, from the rows in main's order.
        val shown = mutableListOf<List<String>>()
        assertNull(findAngel(rows, japan = japan, shown = shown))
        assertEquals(rows.map { it.first }, shown.single().map { it.substringBefore(" | ") })
        assertEquals("https://music.youtube.com/watch?v=row10000000", findAngel(rows, japan = japan, picked = 1))
        assertNull(findAngel(listOf("Fake Song" to "Yoko Takahashi • C • 3:00") + rows.take(1), japan = japan, videos = listOf(bothWays("魂のルフラン"))))
        // Its name in Latin letters is how the rest reads, or a word of the song's: "Live" is neither, nor a version.
        assertNull(findAngel(listOf(rows[0], "残酷な天使のテーゼ - Live" to "Yoko Takahashi • A • 4:05"), japan = japan))
    }

    @Test
    fun kanjiAndKanaAreOneScriptAndLatinLettersNone() {
        // Known as 群青 in Japan, たぶん is in its script, in kana as its words are. Kana against kanji can't be told
        // apart, so main's row stays.
        val song = MusicMetadata("Gunjou", "YOASOBI")
        fun find(rows: List<String>, videos: List<String>, title: String = "Gunjou", japan: String? = "群青", shown: MutableList<List<String>> = mutableListOf()) = runBlocking {
            namesAndTabs(
                songs = rows.mapIndexed { index, name -> youTubeMusicRow(name, "YOASOBI • THE BOOK • ${if (name == "Fake Song") "3:00" else "4:09"}", videoId = "row${index}0000000") },
                videos = videos,
                apple = mapOf("AR" to appleSong(title, "YOASOBI", 248_500)),
                elsewhere = japan?.let { mapOf("JP" to appleSong(it, "YOASOBI", 248_500)) }.orEmpty()
            )
            ExactMatcher(fake.client(), "AR", pick = { _, songs, videos -> if (!videos) shown += songs; null }).find(MusicService.YOUTUBE_MUSIC, song.copy(title = title))
        }
        val shown = mutableListOf<List<String>>()
        assertEquals(
            "https://music.youtube.com/watch?v=row10000000",
            find(listOf("Fake Song", "たぶん"), listOf(youTubeMusicRow("たぶん (Gunjou)", "Fan • 1M views • 4:09", videoId = "video000000")), shown = shown)
        )
        assertEquals(emptyList<List<String>>(), shown)
        // Known only in Japanese, a song in Latin letters isn't one in another script.
        assertNull(find(listOf("Fake Song", "Blue"), emptyList(), title = "群青", japan = null, shown = shown))
        assertEquals(listOf("Fake Song", "Blue"), shown.single().map { it.substringBefore(" | ") })
        // Nor is one with a digit of another script, as Thai's two: only letters say a script.
        shown.clear()
        assertNull(find(listOf("Fake Song", "Blue \u0e52"), emptyList(), title = "群青", japan = null, shown = shown))
        assertEquals(listOf("Fake Song", "Blue \u0e52"), shown.single().map { it.substringBefore(" | ") })
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
        deezer: String = "[]"
    ) {
        fake.handler = { request ->
            val store = request.url.queryParameter("country").orEmpty()
            when {
                request.url.host == "itunes.apple.com" && request.url.encodedPath == "/search" ->
                    FakeSpotify.html(request, """{"results":[${apple[store].orEmpty()}]}""")
                request.url.host == "itunes.apple.com" -> elsewhere[store]?.let { FakeSpotify.html(request, """{"results":[$it]}""") }
                    ?: throw IOException("no such store")
                request.url.host == "api.deezer.com" -> FakeSpotify.html(request, """{"data":$deezer}""")
                else -> FakeSpotify.html(request, youTubeMusicPage(*(if (FakeSpotify.bodyOf(request).contains(VIDEOS_TAB)) videos else songs).toTypedArray()))
            }
        }
    }


    private fun appleSong(title: String, artist: String, lengthMs: Long = 312_000, id: Long = 7) =
        """{"trackId":$id,"trackName":"$title","artistName":"$artist","trackTimeMillis":$lengthMs}"""

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
        var clock = 0L
        var asked = 0
        val matcher = ExactMatcher(fake.client(), "AR", missingForMs = 1_000, now = { clock })
        val song = MusicMetadata("Song", "Band")
        fake.handler = { request -> asked++; FakeSpotify.html(request, """{"data":[]}""") }
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher.lookFor(MusicService.DEEZER, song) })
        clock = 999
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher.lookFor(MusicService.DEEZER, song) })
        assertEquals(1, asked)
        // A while later, it's looked for again, and found.
        clock = 1_000
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
        // Asked of every store that names it otherwise, by the song Apple has.
        val lookups = fake.requestedUrls.filter { "itunes.apple.com/lookup" in it }.map { it.toHttpUrl() }
        assertEquals(setOf("JP", "KR", "TW", "HK", "CN"), lookups.map { it.queryParameter("country") }.toSet())
        assertTrue(lookups.all { it.queryParameter("id") == "7" })
    }

    @Test
    fun theOfficialSongBeatsAnUploadWhenAppleKnowsALongerEditionOfIt() {
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
        namesAndTabs(songs = songs, videos = videos, apple = mapOf("US" to us), elsewhere = mapOf("JP" to appleSong("黄昏のBay City", "八神純子", 281_200, id = 74904069)))
        assertEquals("https://music.youtube.com/watch?v=QLHMhVonF-s", runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song) })
        // Not an edition Apple has, it's still another recording, and the upload that matches is what plays.
        namesAndTabs(songs = songs.drop(1), videos = videos, apple = mapOf("US" to us), elsewhere = mapOf("JP" to appleSong("黄昏のBay City", "八神純子", 281_200, id = 74904069)))
        assertEquals("https://music.youtube.com/watch?v=TwSWeSQAm-g", runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song) })
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
        namesAndTabs(videos = listOf(youTubeMusicRow("THE BLUE HEARTS - 終わらない歌", "Fan • 1K views • 3:04", videoId = "japan000000")), apple = apple, elsewhere = japanese)
        assertEquals("https://music.youtube.com/watch?v=japan000000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
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
                asked = java.net.URLDecoder.decode(request.url.queryParameter("video_ids")!!, "UTF-8")
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

        /** What ChatGPT's pick comes to when it answers none: no row. */
        const val SAYS_NONE = -1
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
                request.url.host == "a-v2.sndcdn.com" -> FakeSpotify.html(request, """client_id:"${"k".repeat(32)}"""")
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
                request.url.host == "a-v2.sndcdn.com" -> FakeSpotify.html(request, """client_id:"${"k".repeat(32)}"""")
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
    fun qobuzsOtherStoreTakesTheFirstTrysAnswerWhenItComesBeforeTheSecondsAndDoesntWaitForTheSecond() {
        val (matcher, _) = slowQobuz(200, qobuzAfter(600), qobuzAfter(4_000))
        val started = System.nanoTime()
        assertEquals("https://play.qobuz.com/track/625089", runBlocking { matcher.otherQobuzLink(MusicMetadata("Song", "Band"), usNumber) })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_500)
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
                    request.url.host == "a-v2.sndcdn.com" -> """client_id:"${"k".repeat(32)}""""
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
