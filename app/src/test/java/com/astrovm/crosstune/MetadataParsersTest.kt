package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs under Robolectric for the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
class MetadataParsersTest {

    @Test
    fun readsTagsInAnyAttributeOrderAndQuoteStyle() {
        val html = """
            <meta content='Carly Rae Jepsen · Cut To The Feeling · Song · 2017' property='og:description'>
            <META CONTENT="Cut To The Feeling" PROPERTY="og:title" />
        """
        assertEquals(MusicMetadata("Cut To The Feeling", "Carly Rae Jepsen"), MetadataParsers.spotify(html, ItemType.TRACK))
    }

    @Test
    fun firstTagWinsAndIncompleteTagsAreSkipped() {
        val html = """
            <meta name="viewport" content="width=device-width">
            <meta property="og:title">
            <meta property="og:title" content="First">
            <meta property="og:title" content="Second">
        """
        assertEquals(MusicMetadata("First", ""), MetadataParsers.spotify(html, ItemType.TRACK))
    }

    @Test
    fun missingOrBlankTitleIsNotATrack() {
        assertNull(MetadataParsers.spotify("<html></html>", ItemType.TRACK))
        assertNull(MetadataParsers.spotify("""<meta property="og:title" content="  ">""", ItemType.TRACK))
    }

    @Test
    fun decodesNamedDecimalAndHexEntities() {
        assertEquals(
            "Rock & Roll <AC/DC> \"live\" it's Beyoncé 🎵 x",
            MetadataParsers.decodeEntities("Rock &amp; Roll &lt;AC&#47;DC&gt; &quot;live&quot; it&apos;s Beyonc&#xE9; &#x1F3B5;&nbsp;x")
        )
    }

    @Test
    fun leavesUnknownOrInvalidEntitiesUntouched() {
        assertEquals(
            "&bogus; &#1114112; &#99999999999;",
            MetadataParsers.decodeEntities("&bogus; &#1114112; &#99999999999;")
        )
    }

    @Test
    fun albumTitleDropsTheAlbumByArtistSuffix() {
        val html = """
            <meta property="og:title" content="After Hours - Deluxe - Album by The Weeknd | Spotify">
            <meta property="og:description" content="The Weeknd · album · 2020 · 14 songs">
        """
        assertEquals(
            MusicMetadata("After Hours - Deluxe", "The Weeknd", ItemType.ALBUM),
            MetadataParsers.spotify(html, ItemType.ALBUM)
        )
        // Tracks keep their full title even when it looks like the album pattern.
        assertEquals(
            "After Hours - Deluxe - Album by The Weeknd",
            MetadataParsers.spotify(html, ItemType.TRACK)?.title
        )
        val plain = """<meta property="og:title" content="Starboy"><meta property="og:description" content="The Weeknd · album">"""
        assertEquals("Starboy", MetadataParsers.spotify(plain, ItemType.ALBUM)?.title)
    }

    @Test
    fun artistsAndPlaylistsIgnoreTheirFreeTextDescription() {
        val html = """<meta property="og:title" content="Today&#8217;s Top Hits"><meta property="og:description" content="The hottest 50 · Cover: ADÉLA">"""
        assertEquals(
            MusicMetadata("Today\u2019s Top Hits", "", ItemType.PLAYLIST),
            MetadataParsers.spotify(html, ItemType.PLAYLIST)
        )
        assertEquals("", MetadataParsers.spotify(html, ItemType.ARTIST)?.artist)
    }

    private fun json(text: String) = JSONObject(text)

    @Test
    fun youtubeTitlesAreSplitAndCleanedOfVideoNoise() {
        assertEquals(
            MusicMetadata("Blinding Lights", "The Weeknd"),
            MetadataParsers.youtube(json("""{"title":"The Weeknd - Blinding Lights (Official Video) [4K]","author_name":"TheWeekndVEVO"}"""))
        )
        assertEquals(
            MusicMetadata("Blinding Lights", "The Weeknd"),
            MetadataParsers.youtube(json("""{"title":"Blinding Lights","author_name":"The Weeknd - Topic"}"""))
        )
        // Without "Artist - Title", the channel name (minus "VEVO") is the best artist available.
        assertEquals(
            MusicMetadata("Blinding Lights (Remix)", "TheWeeknd"),
            MetadataParsers.youtube(json("""{"title":"Blinding Lights (Remix) (Lyrics)","author_name":"TheWeekndVEVO"}"""))
        )
        assertNull(MetadataParsers.youtube(json("""{"title":"(Official Video)"}""")))
    }

    @Test
    fun appleMusicLookupResultsForEachType() {
        val lookup = json(
            """{"results":[{"trackName":"Cut To The Feeling","collectionName":"Cut To The Feeling - Single","artistName":"Carly Rae Jepsen"}]}"""
        )
        assertEquals(MusicMetadata("Cut To The Feeling", "Carly Rae Jepsen"), MetadataParsers.appleMusic(lookup, ItemType.TRACK))
        assertEquals(
            MusicMetadata("Cut To The Feeling", "Carly Rae Jepsen", ItemType.ALBUM),
            MetadataParsers.appleMusic(lookup, ItemType.ALBUM)
        )
        assertEquals(
            "Dedicated",
            MetadataParsers.appleMusic(json("""{"results":[{"collectionName":"Dedicated - EP","artistName":"C"}]}"""), ItemType.ALBUM)?.title
        )
        assertEquals(MusicMetadata("Carly Rae Jepsen", "", ItemType.ARTIST), MetadataParsers.appleMusic(lookup, ItemType.ARTIST))
        assertNull(MetadataParsers.appleMusic(json("""{"results":[]}"""), ItemType.TRACK))
        assertNull(MetadataParsers.appleMusic(json("""{"results":[{"artistName":"A"}]}"""), ItemType.TRACK))
    }

    @Test
    fun applePlaylistsUseThePageTitle() {
        assertEquals(
            MusicMetadata("Today\u2019s Hits", "", ItemType.PLAYLIST),
            MetadataParsers.applePlaylist("""<meta property="og:title" content="Today&#8217;s Hits on Apple Music">""")
        )
        assertNull(MetadataParsers.applePlaylist("<html></html>"))
    }

    @Test
    fun deezerObjectsAndErrors() {
        val track = json("""{"title":"Blinding Lights","artist":{"name":"The Weeknd"}}""")
        assertEquals(MusicMetadata("Blinding Lights", "The Weeknd"), MetadataParsers.deezer(track, ItemType.TRACK))
        assertEquals(
            MusicMetadata("The Weeknd", "", ItemType.ARTIST),
            MetadataParsers.deezer(json("""{"name":"The Weeknd"}"""), ItemType.ARTIST)
        )
        assertEquals(
            MusicMetadata("Top Worldwide", "", ItemType.PLAYLIST),
            MetadataParsers.deezer(json("""{"title":"Top Worldwide","creator":{"name":"Deezer"}}"""), ItemType.PLAYLIST)
        )
        assertEquals("", MetadataParsers.deezer(json("""{"title":"No Artist"}"""), ItemType.ALBUM)?.artist)
        assertNull(MetadataParsers.deezer(json("""{"error":{"type":"DataException"}}"""), ItemType.TRACK))
        assertNull(MetadataParsers.deezer(json("""{}"""), ItemType.TRACK))
    }

    @Test
    fun tidalPagesUseTheirTitleTag() {
        assertEquals(
            MusicMetadata("Blinding Lights", "The Weeknd"),
            MetadataParsers.tidal("<title>Blinding Lights by The Weeknd on TIDAL</title>", ItemType.TRACK)
        )
        assertEquals(
            MusicMetadata("The Weeknd", "", ItemType.ARTIST),
            MetadataParsers.tidal("<TITLE lang='en'>The Weeknd on TIDAL</TITLE>", ItemType.ARTIST)
        )
        assertNull(MetadataParsers.tidal("<title> on TIDAL</title>", ItemType.TRACK))
        assertNull(MetadataParsers.tidal("<html></html>", ItemType.TRACK))
    }

    @Test
    fun soundCloudOEmbedForTracksSetsAndArtists() {
        assertEquals(
            MusicMetadata("Blinding Lights", "The Weeknd"),
            MetadataParsers.soundCloud(json("""{"title":"Blinding Lights by The Weeknd","author_name":"The Weeknd"}"""), ItemType.TRACK)
        )
        assertEquals(
            MusicMetadata("Mix", "DJ", ItemType.PLAYLIST),
            MetadataParsers.soundCloud(json("""{"title":"Mix","author_name":"DJ"}"""), ItemType.PLAYLIST)
        )
        assertEquals(
            MusicMetadata("The Weeknd", "", ItemType.ARTIST),
            MetadataParsers.soundCloud(json("""{"title":"The Weeknd","author_name":"The Weeknd"}"""), ItemType.ARTIST)
        )
        assertEquals(
            "Fallback",
            MetadataParsers.soundCloud(json("""{"title":"Fallback"}"""), ItemType.ARTIST)?.title
        )
        assertNull(MetadataParsers.soundCloud(json("""{"author_name":"X"}"""), ItemType.TRACK))
    }

    @Test
    fun bandcampPagesSplitNameAndArtist() {
        assertEquals(
            MusicMetadata("Blinding Lights", "The Weeknd"),
            MetadataParsers.bandcamp("""<meta property="og:title" content="Blinding Lights, by The Weeknd">""", ItemType.TRACK)
        )
        assertEquals(
            MusicMetadata("Untitled", "", ItemType.ALBUM),
            MetadataParsers.bandcamp("""<meta property="og:title" content="Untitled">""", ItemType.ALBUM)
        )
        assertNull(MetadataParsers.bandcamp("<html></html>", ItemType.TRACK))
    }

    @Test
    fun picksUpArtworkFromEveryService() {
        val image = """<meta property="og:image" content="https://img.example/cover.jpg">"""
        assertEquals(
            "https://img.example/cover.jpg",
            MetadataParsers.spotify("""<meta property="og:title" content="Song">$image""", ItemType.TRACK)?.artworkUrl
        )
        assertEquals(
            "https://img.example/cover.jpg",
            MetadataParsers.applePlaylist("""<meta property="og:title" content="Mix on Apple Music">$image""")?.artworkUrl
        )
        assertEquals(
            "https://img.example/cover.jpg",
            MetadataParsers.tidal("<title>Song by Artist on TIDAL</title>$image", ItemType.TRACK)?.artworkUrl
        )
        assertEquals(
            "https://img.example/cover.jpg",
            MetadataParsers.bandcamp("""<meta property="og:title" content="Record, by Band">$image""", ItemType.ALBUM)?.artworkUrl
        )
        // YouTube's default thumbnail is letterboxed, so the unpadded frame is used instead.
        assertEquals(
            "https://i.ytimg.com/vi/qizghQs4K6E/mqdefault.jpg",
            MetadataParsers.youtube(
                JSONObject("""{"title":"Gee","author_name":"Girls' Generation - Topic","thumbnail_url":"https://i.ytimg.com/vi/qizghQs4K6E/hqdefault.jpg"}""")
            )?.artworkUrl
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/x.jpg/600x600bb.jpg",
            MetadataParsers.appleMusic(
                JSONObject("""{"results":[{"trackName":"Song","artistName":"Artist","artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/x.jpg/100x100bb.jpg"}]}"""),
                ItemType.TRACK
            )?.artworkUrl
        )
        assertEquals(
            "https://cdn-images.dzcdn.net/images/cover/a/500x500.jpg",
            MetadataParsers.deezer(
                JSONObject("""{"title":"Song","artist":{"name":"Artist"},"album":{"cover_big":"https://cdn-images.dzcdn.net/images/cover/a/500x500.jpg"}}"""),
                ItemType.TRACK
            )?.artworkUrl
        )
        assertEquals(
            "https://cdn-images.dzcdn.net/images/artist/b/500x500.jpg",
            MetadataParsers.deezer(
                JSONObject("""{"name":"Artist","picture_big":"https://cdn-images.dzcdn.net/images/artist/b/500x500.jpg"}"""),
                ItemType.ARTIST
            )?.artworkUrl
        )
        assertEquals(
            "https://i1.sndcdn.com/artworks-x-t500x500.jpg",
            MetadataParsers.soundCloud(
                JSONObject("""{"title":"Song by Artist","author_name":"Artist","thumbnail_url":"https://i1.sndcdn.com/artworks-x-t500x500.jpg"}"""),
                ItemType.TRACK
            )?.artworkUrl
        )
    }

    @Test
    fun artworkIsOptional() {
        assertNull(MetadataParsers.spotify("""<meta property="og:title" content="Song">""", ItemType.TRACK)?.artworkUrl)
        assertNull(MetadataParsers.youtube(JSONObject("""{"title":"Song","author_name":"Artist"}"""))?.artworkUrl)
        assertNull(MetadataParsers.deezer(JSONObject("""{"title":"Song","artist":{"name":"Artist"}}"""), ItemType.TRACK)?.artworkUrl)
        // iTunes has no artist pictures.
        assertNull(
            MetadataParsers.appleMusic(
                JSONObject("""{"results":[{"artistName":"Artist","artworkUrl100":"https://x/100x100bb.jpg"}]}"""),
                ItemType.ARTIST
            )?.artworkUrl
        )
    }

    @Test
    fun youtubeTopicTitlesKeepTheirDash() {
        assertEquals(
            MusicMetadata("Bohemian Rhapsody - Remastered 2011", "Queen"),
            MetadataParsers.youtube(json("""{"title":"Bohemian Rhapsody - Remastered 2011","author_name":"Queen - Topic"}"""))
        )
    }

    @Test
    fun tidalArtistsAndPlaylistsAreNotSplitOnBy() {
        assertEquals(
            MusicMetadata("Death by Stereo", "", ItemType.ARTIST),
            MetadataParsers.tidal("<title>Death by Stereo on TIDAL</title>", ItemType.ARTIST)
        )
        assertEquals(
            MusicMetadata("Chill by Someone", "", ItemType.PLAYLIST),
            MetadataParsers.tidal("<title>Chill by Someone on TIDAL</title>", ItemType.PLAYLIST)
        )
    }

    @Test
    fun deezerErrorCodesMapToAppErrors() {
        fun error(code: Int) = MetadataParsers.deezerError(json("""{"error":{"code":$code}}"""))
        assertEquals(AppError.RATE_LIMITED, error(4))
        assertEquals(AppError.SERVICE_UNAVAILABLE, error(700))
        assertEquals(AppError.NOT_FOUND, error(800))
        assertEquals(AppError.METADATA_UNAVAILABLE, error(300))
        assertNull(MetadataParsers.deezerError(json("""{"title":"Song"}""")))
    }

    @Test
    fun playlistsListTheirSongsWhereTheServiceShowsThem() {
        val nextData = """{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[
            {"title":"First","subtitle":"Artist A, Artist B"},{"title":""},{"title":"Second","subtitle":"Artist C"},"skip"]}}}}}}"""
        assertEquals(
            listOf(MusicMetadata("First", "Artist A, Artist B"), MusicMetadata("Second", "Artist C")),
            MetadataParsers.spotifyEmbedTracks("""<script id="__NEXT_DATA__" type="application/json">$nextData</script>""")
        )
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.spotifyEmbedTracks("<html></html>"))
        assertEquals(
            emptyList<MusicMetadata>(),
            MetadataParsers.spotifyEmbedTracks("""<script id="__NEXT_DATA__" type="application/json">{"props":{}}</script>""")
        )

        // Apple Music: songs anywhere in the page data, but not the playlist itself or untitled ones.
        val pageData = """{"data":[{"contentDescriptor":{"kind":"playlist"},"title":"Hits","sections":[{"items":[
            {"title":"Uno","artistName":"Artist","contentDescriptor":{"kind":"song"}},
            {"title":"","contentDescriptor":{"kind":"song"}},
            {"title":"Dos","artistName":"Other","contentDescriptor":{"kind":"song"}},1]}]}]}"""
        val html = """<meta property="og:title" content="Hits on Apple Music"><script type="application/json" id="serialized-server-data">$pageData</script>"""
        assertEquals(listOf(MusicMetadata("Uno", "Artist"), MusicMetadata("Dos", "Other")), MetadataParsers.applePlaylist(html)!!.tracks)
        val broken = """<meta property="og:title" content="Hits on Apple Music"><script type="application/json" id="serialized-server-data">{nope</script>"""
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.applePlaylist(broken)!!.tracks)

        val deezer = json("""{"title":"Top","tracks":{"data":[{"title":"Boston","artist":{"name":"Stella"}},{"title":""},{"title":"Solo"},"x"]}}""")
        assertEquals(listOf(MusicMetadata("Boston", "Stella"), MusicMetadata("Solo", "")), MetadataParsers.deezer(deezer, ItemType.PLAYLIST)!!.tracks)
        // Albums list theirs the same way.
        assertEquals(listOf(MusicMetadata("Boston", "Stella"), MusicMetadata("Solo", "")), MetadataParsers.deezer(deezer, ItemType.ALBUM)!!.tracks)
    }

    @Test
    fun albumsListTheirSongsOnAppleMusicAndBandcamp() {
        val lookup = json("""{"results":[
            {"wrapperType":"collection","collectionName":"After Hours","artistName":"The Weeknd"},
            {"wrapperType":"track","trackName":"Alone Again","artistName":"The Weeknd"},
            {"wrapperType":"track","trackName":""},
            {"wrapperType":"artist","artistName":"Not a song"}]}""")
        assertEquals(listOf(MusicMetadata("Alone Again", "The Weeknd")), MetadataParsers.appleMusic(lookup, ItemType.ALBUM)!!.tracks)

        val album = """{"name":"Volume Alpha","byArtist":{"name":"C418"},"track":{"itemListElement":[{"item":{"name":"Key"}},{"item":{"name":""}},{}]}}"""
        fun page(data: String) = """<meta property="og:title" content="Volume Alpha, by C418"><script type="application/ld+json" id="tralbum-jsonld">$data</script>"""
        assertEquals(listOf(MusicMetadata("Key", "C418")), MetadataParsers.bandcamp(page(album), ItemType.ALBUM)!!.tracks)
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.bandcamp(page("{nope"), ItemType.ALBUM)!!.tracks)
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.bandcamp(page("""{"name":"x"}"""), ItemType.ALBUM)!!.tracks)
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.bandcamp("""<meta property="og:title" content="Volume Alpha, by C418">""", ItemType.ALBUM)!!.tracks)
        // A single song's page doesn't list songs.
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.bandcamp(page(album), ItemType.TRACK)!!.tracks)
    }

    @Test
    fun youtubePlaylistsReadTheirVideosAsSongs() {
        fun lockup(title: String, channel: String, type: String = "LOCKUP_CONTENT_TYPE_VIDEO") =
            """{"lockupViewModel":{"contentType":"$type","metadata":{"lockupMetadataViewModel":{"title":{"content":"$title"},
            "metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"$channel"}}]}]}}}}}}"""
        val data = """{"contents":[${lockup("Artist - Song (Official Video)", "ArtistVEVO")},${lockup("Other Song", "Band - Topic")},
            ${lockup("A mix", "Someone", "LOCKUP_CONTENT_TYPE_PLAYLIST")},${lockup("", "Nobody")},2]}"""
        val html = """<meta property="og:title" content="Road Trip"><meta property="og:image" content="https://i.ytimg.com/x.jpg">
            <script>var ytInitialData = $data;</script>"""
        assertEquals(
            MusicMetadata("Road Trip", "", ItemType.PLAYLIST, "https://i.ytimg.com/x.jpg", listOf(MusicMetadata("Song", "Artist"), MusicMetadata("Other Song", "Band"))),
            MetadataParsers.youtubePlaylist(html)
        )
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.youtubePlaylist("""<meta property="og:title" content="X"><script>var ytInitialData = {nope;</script>""")!!.tracks)
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.youtubePlaylist("""<meta property="og:title" content="X">""")!!.tracks)
        assertNull(MetadataParsers.youtubePlaylist("<html></html>"))
    }

    @Test
    fun audiomackOEmbedGivesTheNameArtistAndCover() {
        assertEquals(
            MusicMetadata("Last Last", "Burna Boy", ItemType.TRACK, "https://i.audiomack.com/x.webp"),
            MetadataParsers.audiomack(json("""{"title":"Last Last","author_name":"Burna Boy","thumbnail_url":"https://i.audiomack.com/x.webp"}"""), ItemType.TRACK)
        )
        // A playlist's author is whoever made it, not an artist.
        assertEquals(MusicMetadata("Mix", "", ItemType.PLAYLIST), MetadataParsers.audiomack(json("""{"title":"Mix","author_name":"Someone"}"""), ItemType.PLAYLIST))
        assertNull(MetadataParsers.audiomack(json("""{"title":""}"""), ItemType.TRACK))
    }

    @Test
    fun playlistSongsComeWithTheirCoversLinksAndTheTotal() {
        // Spotify: the embed's songs by link; the page's first ones with covers, in base64 data; and how many there are.
        val embed = """{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[{"title":"First","subtitle":"Band","uri":"spotify:track:one"},
            {"title":"Second","subtitle":"Band","uri":"spotify:episode:x"}]}}}}}}"""
        assertEquals(
            listOf(MusicMetadata("First", "Band", url = "https://open.spotify.com/track/one"), MusicMetadata("Second", "Band")),
            MetadataParsers.spotifyEmbedTracks("""<script id="__NEXT_DATA__" type="application/json">$embed</script>""")
        )
        val state = """{"entities":{"items":{"spotify:playlist:x":{"content":{"items":[
            {"itemV2":{"data":{"uri":"spotify:track:one","albumOfTrack":{"coverArt":{"sources":[{"width":64,"url":"small"},{"width":300,"url":"medium"},{"width":640,"url":"big"}]}}}}},
            {"itemV2":{"data":{"uri":"spotify:track:two"}}},{"itemV2":{"data":{"uri":"spotify:track:"}}},
            {"itemV2":{"data":{"uri":"spotify:track:three","albumOfTrack":{"coverArt":{"sources":[{"width":300,"url":""}]}}}}}]}},
            "spotify:user:x":{"name":"Someone"}}}}"""
        fun page(data: String) = """<script id="initialState" type="text/plain">${java.util.Base64.getEncoder().encodeToString(data.toByteArray())}</script>"""
        assertEquals(mapOf("https://open.spotify.com/track/one" to "medium"), MetadataParsers.spotifyCovers(page(state)))
        assertEquals(emptyMap<String, String>(), MetadataParsers.spotifyCovers(page("{nope")))
        assertEquals(emptyMap<String, String>(), MetadataParsers.spotifyCovers(page("{}")))
        assertEquals(emptyMap<String, String>(), MetadataParsers.spotifyCovers("<html></html>"))
        val playlist = """<meta property="og:title" content="Road Trip"/><meta name="music:song_count" content="150"/>"""
        assertEquals(150, MetadataParsers.spotify(playlist, ItemType.PLAYLIST)!!.trackCount)

        // Apple Music: each song's cover, at a size for a list.
        val pageData = """{"x":[{"title":"Uno","artistName":"Artist","contentDescriptor":{"kind":"song"},
            "artwork":{"dictionary":{"url":"https://is1-ssl.mzstatic.com/image/thumb/a.jpg/{w}x{h}bb.{f}"}}}]}"""
        assertEquals(
            listOf(MusicMetadata("Uno", "Artist", artworkUrl = "https://is1-ssl.mzstatic.com/image/thumb/a.jpg/300x300bb.jpg")),
            MetadataParsers.applePlaylist("""<meta property="og:title" content="Hits"><script type="application/json" id="serialized-server-data">$pageData</script>""")!!.tracks
        )

        // Deezer: each song's album cover, and how many there are past the ones listed.
        val deezer = json("""{"title":"Top","nb_tracks":500,"tracks":{"data":[{"title":"Boston","artist":{"name":"Stella"},"album":{"cover_medium":"https://cdn/boston.jpg"}}]}}""")
        assertEquals(
            MusicMetadata("Top", "", ItemType.PLAYLIST, tracks = listOf(MusicMetadata("Boston", "Stella", artworkUrl = "https://cdn/boston.jpg")), trackCount = 500),
            MetadataParsers.deezer(deezer, ItemType.PLAYLIST)
        )

        // YouTube: each video's frame and link.
        val lockup = """{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"abcdefghijk","metadata":{"lockupMetadataViewModel":{"title":{"content":"Song"},
            "metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Band - Topic"}}]}]}}}}}}"""
        assertEquals(
            listOf(MusicMetadata("Song", "Band", artworkUrl = "https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", url = "https://www.youtube.com/watch?v=abcdefghijk")),
            MetadataParsers.youtubePlaylist("""<meta property="og:title" content="X"><script>var ytInitialData = {"a":[$lockup]};</script>""")!!.tracks
        )
    }
}
