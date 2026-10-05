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
        assertNull(runBlocking { matcher().find(MusicService.SOUNDCLOUD, song) })
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
        assertEquals(1, fake.requestedUrls.size)
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
}
