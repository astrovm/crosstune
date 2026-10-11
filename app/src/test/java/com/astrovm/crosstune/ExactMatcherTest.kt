package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    fun youTubeMusicTellsSongsAndMusicVideosFromOtherVideos() {
        fun type(type: String) = """{"contents":{"x":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_$type"}}}}"""
        respond(type("OMV"))
        assertEquals(true, runBlocking { matcher().isMusicVideo("4NRXx6U8ABQ") })
        assertTrue(fake.requestedUrls.single().startsWith("https://music.youtube.com/youtubei/v1/next"))
        assertTrue(fake.requestBodies.single().contains("\"videoId\":\"4NRXx6U8ABQ\""))
        respond(type("ATV"))
        assertEquals(true, runBlocking { matcher().isMusicVideo("4NRXx6U8ABQ") })
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
        assertNull(runBlocking { matcher().find(MusicService.SPOTIFY, song) })
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
            val asked = fake.requestBodies.last()
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
                else -> FakeSpotify.html(request, youTubeMusicPage(*(if (fake.requestBodies.last().contains(VIDEOS_TAB)) videos else songs).toTypedArray()))
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
        fake.handler = { throw IOException("offline") }
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher().lookFor(MusicService.APPLE_MUSIC, song) })
        respond("""{"data":[]}""")
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher().lookFor(MusicService.DEEZER, song) })
        respond("""{"data":[{"title":"Song","link":"https://www.deezer.com/track/1","artist":{"name":"Band"}}]}""")
        assertEquals(ExactMatcher.Match.Found("https://www.deezer.com/track/1"), runBlocking { matcher().lookFor(MusicService.DEEZER, song) })
        // An app that can't be searched without an account has nothing to say either way.
        assertEquals(ExactMatcher.Match.Missing, runBlocking { matcher().lookFor(MusicService.SPOTIFY, song) })
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
        // Not known to Apple, Deezer says how long it is; and unknown anywhere, the length isn't held against it.
        assertNull(finds(youTubeMusicRow("The Blue Hearts - Owaranai Uta", "Fan • 1K views • 4:30", videoId = "long0000000"), deezer = """[{"title":"Owaranai Uta","artist":{"name":"THE BLUE HEARTS"},"duration":184}]""", names = emptyMap()))
        assertEquals("https://music.youtube.com/watch?v=long0000000", finds(youTubeMusicRow("The Blue Hearts - Owaranai Uta", "Fan • 1K views • 4:30", videoId = "long0000000"), names = emptyMap()))
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
        // A song result without its video, or a video of one letter of the name, plays nothing of it.
        namesAndTabs(songs = listOf(youTubeMusicRow("Owaranai Uta", "THE BLUE HEARTS • Album • 3:04", browseId = "MPREb_x")))
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, song) })
        namesAndTabs(videos = listOf(youTubeMusicRow("YOASOBI - 夜に駆ける", "Fan • 1K views • 4:21", videoId = "yoru0000000")))
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("夜", "YOASOBI")) })
        // A video that doesn't say how long it is isn't held to the song's length.
        namesAndTabs(
            videos = listOf(youTubeMusicRow("The Blue Hearts - Owaranai Uta", "Fan • 1K views", videoId = "nolength000")),
            apple = mapOf("US" to appleSong("Owaranai Uta", "THE BLUE HEARTS", 184_000))
        )
        fake.requestedUrls.clear()
        assertEquals("https://music.youtube.com/watch?v=nolength000", runBlocking { matcher("US").find(MusicService.YOUTUBE_MUSIC, song) })
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

        // Someone else's upload counts when the title names the artist; a live take is another recording.
        youTubeMusicTabs(
            songs = emptyList(),
            videos = listOf(
                youTubeMusicRow("Tiny Band - Bedroom Demo (Live)", "Tiny Band • 1K views • 4:00", videoId = "live0000000"),
                youTubeMusicRow("Tiny Band - Bedroom Demo [Lyrics]", "Fan Channel • 50 views • 3:00", videoId = "upload00000")
            )
        )
        assertEquals("https://music.youtube.com/watch?v=upload00000", runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, demo) })
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
        val url = runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, tracks) {} }
        assertEquals("same0000000", asked)
        assertEquals("https://music.youtube.com/watch?v=same0000000&list=TLGGq", url)
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
            assertEquals("https://music.youtube.com/watch?v=first000000", runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata("First", "Band"))) {} })
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
        val url = runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, tracks) { looked += it } }
        assertEquals("https://music.youtube.com/watch?v=fine0000000&list=TLGGq", url)
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
        return runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata(title, artist)), {}) }
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
        assertEquals("https://music.youtube.com/watch?v=exact000000", runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, tracks) {} })
        // A song's close match is kept apart from its exact one, so opening it alone still wants the same title.
        respond(youTubeMusicPage(youTubeMusicRow("GLASS NO PALM TREE", "Band • Album • 3:00", videoId = "other000000")))
        val romaji = MusicMetadata("ガラスのPALM TREE", "Band")
        assertEquals("https://music.youtube.com/watch?v=other000000", runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(romaji)) {} })
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, romaji) })
    }

    @Test
    fun aQueueAsksAgainOnceWhenASongsSearchFailsButNotWhenItFindsNothing() {
        var asked = 0
        fake.handler = { request ->
            if (request.url.encodedPath == "/watch_videos") FakeSpotify.html(request, "").newBuilder().code(303).header("Location", "https://www.youtube.com/watch?v=x").build()
            else if (++asked == 1) throw IOException("slow moment")
            else FakeSpotify.html(request, youTubeMusicPage(youTubeMusicRow("Song", "Band • Album • 3:00", videoId = "video000000")))
        }
        assertEquals("https://music.youtube.com/watch?v=video000000", runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata("Song", "Band"))) {} })
        assertEquals(2, asked)

        // Nothing found is an answer, so it is not asked twice: once among the songs, once among the
        // videos, and Apple, in two stores, and Deezer for its other names. Failing twice gives up.
        asked = 0
        fake.handler = { request -> asked++; FakeSpotify.html(request, """{"contents":{}}""") }
        assertNull(runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata("Missing", "Nobody"))) {} })
        assertEquals(5, asked)
        asked = 0
        fake.handler = { asked++; throw IOException("offline") }
        assertNull(runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(MusicMetadata("Missing", "Nobody"))) {} })
        assertEquals(2, asked)

        // A single song is asked once, so opening it doesn't wait longer on a slow network.
        asked = 0
        assertNull(runBlocking { matcher().find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Missing", "Nobody")) })
        assertEquals(1, asked)
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
        val url = runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, tracks) { looked += it } }
        assertEquals("https://music.youtube.com/watch?v=first000000&list=TLGGqueue", url)
        assertEquals(listOf(1, 2, 3), looked.sorted())
        assertTrue(fake.requestedUrls.any { java.net.URLDecoder.decode(it, "UTF-8").endsWith("watch_videos?video_ids=first000000,second00000") })

        // Without the temporary playlist, the first song still plays, in YouTube here.
        playlistAnswer = { throw IOException("offline") }
        assertEquals("https://www.youtube.com/watch?v=first000000", runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE, tracks) {} })

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
        fake.handler = { request -> FakeSpotify.html(request, page) }
        assertEquals("https://play.qobuz.com/track/12", find(MusicService.QOBUZ, MusicMetadata("Song", "Band")))
        assertTrue(fake.requestedUrls.last().startsWith("https://www.qobuz.com/us-en/search/tracks/Song%20Band"))
        assertEquals("https://play.qobuz.com/album/studio2", find(MusicService.QOBUZ, MusicMetadata("Studio & Co", "Band", ItemType.ALBUM)))
        // Qobuz's artists aren't told apart, so there's nothing to look up, and the search stands in.
        val asked = fake.requestedUrls.size
        assertNull(find(MusicService.QOBUZ, MusicMetadata("Band", "", ItemType.ARTIST)))
        assertEquals(asked, fake.requestedUrls.size)
        assertEquals(false, canMatchExactly(MusicService.QOBUZ, ItemType.ARTIST))
        assertEquals(true, canMatchExactly(MusicService.QOBUZ, ItemType.ALBUM))
        fake.handler = { request -> FakeSpotify.html(request, "busy", code = 503) }
        assertNull(find(MusicService.QOBUZ, MusicMetadata("Other", "Band")))
    }
}
