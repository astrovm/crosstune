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
    fun onlyAVideoThatSaysItsMusicIsASong() {
        assertNull(MetadataParsers.musicVideo("A New Approach To An Ancient Linux Terminal Problem", "Brodie Robertson"))
        assertNull(MetadataParsers.musicVideo("My Morning Routine (4K)", "Someone"))
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.musicVideo("Band - Song", "Label"))
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.musicVideo("Song (Official Video)", "Band"))
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.musicVideo("Song", "Band - Topic"))
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.musicVideo("Song", "BandVEVO"))
        assertEquals(MusicMetadata("曲名", "アーティスト"), MetadataParsers.musicVideo("アーティスト「曲名」", "Label"))
        assertEquals(MusicMetadata("Song - Remastered 2011", "Band"), MetadataParsers.musicVideo("Song - Remastered 2011", "Band"))
        assertEquals(MusicMetadata("Song (feat. A)", "Band"), MetadataParsers.musicVideo("Song (feat. A)", "Band"))
        assertNull(MetadataParsers.musicVideo("(Official Video)", "Band"))
    }

    @Test
    fun musicOnTheArtistsOwnChannelAndLocalizedTagsAreRecognized() {
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.musicVideo("Band - Song", "Band"))
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.musicVideo("Song - Band", "Band"))
        for (tag in listOf("Video Oficial", "Letra", "Offizielles Video", "公式", "官方", "歌詞", "가사", "كليب رسمي")) {
            assertEquals(tag, MusicMetadata("Song", "Band"), MetadataParsers.musicVideo("Song ($tag)", "Band"))
        }
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.musicVideo("Song Video Oficial", "Band"))
        for (tag in listOf("HD", "Full HD", "4K", "HQ", "Video", "Subtitulado", "Sub Español")) {
            assertNull(MetadataParsers.musicVideo("Morning Routine ($tag)", "Someone"))
        }
    }

    @Test
    fun stackedVideoSuffixesLeaveOnlyTheSong() {
        val random = kotlin.random.Random(216)
        val suffixes = listOf("Official Video", "Official Audio", "Video Oficial", "HD", "4K", "(Lyrics)", "[HQ]", "- Lyrics")
        repeat(100) {
            val title = "Band - River Road " + List(random.nextInt(1, 6)) { suffixes.random(random) }.joinToString(" ")
            assertEquals(title, MusicMetadata("River Road", "Band"), MetadataParsers.youtubeVideo(title, "Band"))
        }
        assertEquals(MusicMetadata("Music Video Killed the Radio Star", "Band"), MetadataParsers.youtubeVideo("Band - Music Video Killed the Radio Star HD", "Band"))
    }

    @Test
    fun malformedVideoMetadataNeverCrashesTheLookup() {
        val random = kotlin.random.Random(221)
        val alphabet = "abcXYZ0123 -|/[]()「」『』\"'\n\t\u0000ñé日本가사رسمي"
        val titles = List(500) { List(random.nextInt(256)) { alphabet.random(random) }.joinToString("") } +
            listOf("[".repeat(500), ") ( - ".repeat(100), "Song " + "HD ".repeat(100))
        for (title in titles) {
            MetadataParsers.youtubeVideo(title, "Channel")
            MetadataParsers.musicVideo(title, "Channel")
        }
    }

    @Test
    fun aJapaneseSongByTwoNamesIsOneSongByTheChannel() {
        // As YouTube Music has an official video: the katakana says the English name.
        assertEquals(MusicMetadata("プラスティック・ラブ - Plastic Love", "Mariya Takeuchi"), MetadataParsers.youtubeVideo("プラスティック・ラブ - Plastic Love", "Mariya Takeuchi"))
        assertEquals(MusicMetadata("Plastic Love - プラスティック・ラブ", "Mariya Takeuchi"), MetadataParsers.youtubeVideo("Plastic Love - プラスティック・ラブ", "Mariya Takeuchi"))
        // A short name too, said with two sounds, as YouTube Music titles YOASOBI's video.
        assertEquals(MusicMetadata("アイドル - Idol", "YOASOBI"), MetadataParsers.youtubeVideo("アイドル - Idol", "YOASOBI"))
        assertEquals(MusicMetadata("ラブ - Love", "Artist"), MetadataParsers.youtubeVideo("ラブ - Love", "Artist"))
        // One sound says too little: an artist in katakana before it is still the artist.
        assertEquals(MusicMetadata("Yo", "ヨ"), MetadataParsers.youtubeVideo("ヨ - Yo", "Uploader"))
        // An artist in Japanese before an English song is still the artist: the two don't sound alike.
        assertEquals(MusicMetadata("First Love", "宇多田ヒカル"), MetadataParsers.youtubeVideo("宇多田ヒカル - First Love", "Uploader"))
        assertEquals(MusicMetadata("新宝島", "サカナクション"), MetadataParsers.youtubeVideo("サカナクション - 新宝島", "Uploader"))
        assertEquals(MusicMetadata("Bohemian Rhapsody", "Queen"), MetadataParsers.youtubeVideo("Queen - Bohemian Rhapsody [Lyrics English - Español Subtitulado]", "Luis Mihajlow"))
    }

    @Test
    fun aSongSaidAgainInJapaneseAfterItIsTheFirst() {
        assertEquals(
            MusicMetadata("Plastic Love", "Mariya Takeuchi"),
            MetadataParsers.youtubeVideo("Mariya Takeuchi - Plastic Love  竹内 まりや - プラスティックラブ 1984", "SOULCITYWALK")
        )
        assertEquals(MusicMetadata("Mayonaka no Door", "Miki Matsubara"), MetadataParsers.youtubeVideo("Miki Matsubara - Mayonaka no Door 松原みき - 真夜中のドア", "Uploader"))
        // Not that: a song whose name goes on after a dash, or nothing in Japanese after it.
        assertEquals(MusicMetadata("Song 日本 - Remix", "Band"), MetadataParsers.youtubeVideo("Band - Song 日本 - Remix", "Uploader"))
        assertEquals(MusicMetadata("Song - 日本", "Band"), MetadataParsers.youtubeVideo("Band - Song - 日本", "Uploader"))
        assertEquals(MusicMetadata("日本 - 真夜中", "Band"), MetadataParsers.youtubeVideo("Band - 日本 - 真夜中", "Uploader"))
        assertEquals(MusicMetadata("Song 日本 Live - 真夜中", "Band"), MetadataParsers.youtubeVideo("Band - Song 日本 Live - 真夜中", "Uploader"))
        assertEquals(MusicMetadata("1984 日本 - 真夜中", "Band"), MetadataParsers.youtubeVideo("Band - 1984 日本 - 真夜中", "Uploader"))
    }

    @Test
    fun aDashInsideBracketsDoesNotSplitTheTitle() {
        assertEquals(
            MusicMetadata("NOFX [Reggae & Ska - 2014 Mixtape]", "YoDubMixes Prod"),
            MetadataParsers.youtubeVideo("NOFX [Reggae & Ska - 2014 Mixtape]", "YoDubMixes Prod")
        )
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.youtubeVideo("Band - Song", "Chan"))
        // Written with an en or em dash, as NewPipe names a video, it's the same.
        assertEquals(MusicMetadata("Bohemian Rhapsody", "Queen"), MetadataParsers.musicVideo("Queen – Bohemian Rhapsody (Official Video Remastered)", "Queen Official"))
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.youtubeVideo("Band — Song", "Chan"))
        assertEquals(MusicMetadata("Band–Song", "Chan"), MetadataParsers.youtubeVideo("Band–Song", "Chan"))
        assertEquals(MusicMetadata("Song", "Band ) [Mix - Series]"), MetadataParsers.youtubeVideo("Band ) [Mix - Series] - Song", "Chan"))
    }

    @Test
    fun weirdRealVideoTitlesAreCleaned() {
        fun song(title: String, channel: String) = MetadataParsers.youtubeVideo(title, channel)?.let { it.title to it.artist }
        assertEquals("Outerspace" to "Nightcore", song("Nightcore - Outerspace - (Lyrics)", "Syrex"))
        assertEquals("Reyes de la noche" to "Guasones", song("Guasones - Reyes de la noche (video oficial) HD", "Guasones Oficial"))
        assertEquals("MONEY ON THE DASH" to "Elley Duhe", song("Elley Duhe - MONEY ON THE DASH | Animated Video", "MrMoMMusic"))
        assertEquals("The Strokes :: Dine N'Dash" to "Luis", song("The Strokes :: Dine N'Dash // Sub Español - Lyrics |HD|", "Luis"))
        assertEquals("Friends Will Be Friends Will Be Friends... - Queen Remastered 2011" to "Gold", song("07 - Friends Will Be Friends Will Be Friends... - Queen Remastered 2011", "Gold"))
        assertEquals("余波 (Vinyl Exclusive Bonus Track)" to "2814", song("2814 - 余波 (Vinyl Exclusive Bonus Track)", "Vapor Memory"))
        assertEquals("What's Up" to "4 Non Blondes", song("4 Non Blondes - What's Up (Official Music Video)", "4 Non Blondes"))
    }

    @Test
    fun argentineRockTitlesAreCleaned() {
        fun song(title: String, channel: String) = MetadataParsers.youtubeVideo(title, channel)?.let { it.title to it.artist }
        assertEquals("Días de verano" to "Amaral", song("Amaral - Días de verano (Videoclip Oficial)", "Warner"))
        assertEquals("Y Qué" to "Babasónicos", song("Babasónicos - Y Qué (Letra)", "ces3tx"))
        assertEquals("Una Nueva Noche Fría" to "Callejeros", song("Callejeros - Una Nueva Noche Fría (Video Oficial)", "Pelo"))
        assertEquals("Luz" to "Ciro y Los Persas", song("Luz - Ciro y los Persas - Video Oficial", "Ciro y Los Persas"))
        assertEquals("Actua" to "Owin y Jack", song("Owin y Jack - Actua - Video Clip Oficial", "Cumbia"))
        assertEquals("Una señal (Otro día en el planeta Tierra)" to "Intoxicados", song("Intoxicados - 06 Una señal (Otro día en el planeta Tierra)", "PopArt"))
        assertEquals("Corsario Negro" to "Los Natas", song("Los Natas - \"Corsario Negro\"", "Small Stone"))
        assertEquals("99 Luftballons" to "Nena", song("Nena - 99 Luftballons", "Nena"))
        assertEquals("Ala Delta" to "Divididos", song("Divididos - Ala Delta", "DIVIDIDOS"))
    }

    @Test
    fun japaneseVideoTitlesNameTheSongInBrackets() {
        assertEquals(MusicMetadata("曲名", "アーティスト"), MetadataParsers.youtubeVideo("アーティスト「曲名」 Official Music Video", "アーティスト"))
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.youtubeVideo("Band『Song』MV", "BandOfficial"))
        // Brackets alone leave the artist to the channel.
        assertEquals(MusicMetadata("曲名", "アーティスト"), MetadataParsers.youtubeVideo("「曲名」", "アーティスト - Topic"))
        // A dash before only an edition's tag is part of the song's name, on any channel.
        assertEquals(MusicMetadata("Song - Remastered 2011", "Band"), MetadataParsers.youtubeVideo("Song - Remastered 2011", "Band"))
        assertEquals(MusicMetadata("Song - Remastered 2011", "Band"), MetadataParsers.youtubeVideo("Band - Song - Remastered 2011", "Label"))
        // What a title says of the video without brackets is left off too.
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.youtubeVideo("Band - Song Official Music Video", "BandVEVO"))
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.youtubeVideo("Band - Song | Official Audio", "Label"))
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.youtubeVideo("Band - Song - Lyric Video", "Label"))
        // But not a song named so in the middle.
        assertEquals(MusicMetadata("Music Video Killed the Radio Star", "Band"), MetadataParsers.youtubeVideo("Band - Music Video Killed the Radio Star", "Label"))
    }

    @Test
    fun appleMusicLookupResultsForEachType() {
        val lookup = json(
            """{"results":[{"trackName":"Cut To The Feeling","collectionName":"Cut To The Feeling - Single","artistName":"Carly Rae Jepsen"}]}"""
        )
        assertEquals(MusicMetadata("Cut To The Feeling", "Carly Rae Jepsen"), MetadataParsers.appleMusic(lookup, ItemType.TRACK))
        val timed = json("""{"results":[{"trackName":"Cut To The Feeling","artistName":"Carly Rae Jepsen","trackTimeMillis":207959}]}""")
        assertEquals(207_959L, MetadataParsers.appleMusic(timed, ItemType.TRACK)?.lengthMs)
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
        // A song says how long it is, in seconds; an album's length isn't a song's.
        assertEquals(200_000L, MetadataParsers.deezer(json("""{"title":"Blinding Lights","duration":200,"artist":{"name":"The Weeknd"}}"""), ItemType.TRACK)?.lengthMs)
        assertNull(MetadataParsers.deezer(json("""{"title":"After Hours","duration":3360,"artist":{"name":"The Weeknd"}}"""), ItemType.ALBUM)?.lengthMs)
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
    fun deezerSaysASongsVersionInBracketsAsOtherServicesDo() {
        fun title(song: String) = MetadataParsers.deezer(json(song), ItemType.TRACK)?.title
        // Deezer joins them with a space, which reads as one name.
        assertEquals(
            "Matador (Remasterizado 2008)",
            title("""{"title":"Matador Remasterizado 2008","title_short":"Matador","title_version":"Remasterizado 2008","artist":{"name":"Los Fabulosos Cadillacs"}}""")
        )
        // Already in brackets, or with no version, it's left as it is.
        assertEquals(
            "Piel Morena (30th Anniversary/ Remasterizado 2025)",
            title("""{"title":"Piel Morena (30th Anniversary/ Remasterizado 2025)","title_short":"Piel Morena","title_version":"(30th Anniversary/ Remasterizado 2025)"}""")
        )
        assertEquals(
            "Dime Cuánto - Remasterizado",
            title("""{"title":"Dime Cuánto - Remasterizado","title_short":"Dime Cuánto - Remasterizado","title_version":""}""")
        )
        // A version that isn't what the name ends with isn't moved.
        assertEquals("Song Live", title("""{"title":"Song Live","title_short":"Song","title_version":"Remastered"}"""))
        // An album's songs too.
        val album = MetadataParsers.deezer(
            json("""{"title":"Vasos Vacíos","tracks":{"data":[{"title":"Matador Remasterizado 2008","title_short":"Matador","title_version":"Remasterizado 2008"}]}}"""),
            ItemType.ALBUM
        )
        assertEquals(listOf("Matador (Remasterizado 2008)"), album?.tracks?.map { it.title })
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

    @Test
    fun titlesInOtherLanguagesAreCleaned() {
        fun song(title: String, channel: String) = MetadataParsers.youtubeVideo(title, channel)?.let { it.title to it.artist }
        assertEquals("Dancing Queen" to "Abba", song("Abba - Dancing Queen // Sub Español + Lyrics // Video Oficial", "BrunoTraductor"))
        assertEquals("Save Your Tears (Remix)" to "The Weeknd & Ariana Grande", song("The Weeknd & Ariana Grande - Save Your Tears (Remix) (Official Video) || Sub. Español + Lyrics", "tivs14"))
        assertEquals("Du Hast (Tú tienes)" to "Rammstein", song("Rammstein - Du Hast (Sub Esp) (Tú tienes)", "RammsteinHaifisch3"))
        assertEquals("La vie en rose" to "Edith Piaf", song("Edith Piaf - La vie en rose (Audio officiel)", "Edith Piaf Officiel"))
        assertEquals("Tempo perdido" to "Legião Urbana", song("Legião Urbana - Tempo perdido (Videoclipe)", "Brasilidades"))
        assertEquals("Water No Get Enemy" to "Fela Kuti", song("Fela Kuti - Water No Get Enemy (HQ)", "atomikn00b"))
        assertEquals("Simarik" to "Tarkan", song("Tarkan - Simarik (Official Music Video) HQ", "ExclusiveMusic2share"))
        assertEquals("Давай за..." to "ЛЮБЭ", song("ЛЮБЭ - Давай за... (клип)", "Matvey Music"))
        assertEquals("泡沫" to "G.E.M. 鄧紫棋", song("G.E.M. 鄧紫棋 - 泡沫 (官方MV)", "GEM"))
        assertEquals("봄날" to "BTS", song("BTS - 봄날 (가사)", "x"))
        // A real song name that merely looks like a tag stays.
        assertEquals("Sub" to "Band", song("Band - Sub", "x"))
    }

    private fun og(title: String?, description: String? = null) = buildString {
        title?.let { append("""<meta property="og:title" content="$it">""") }
        description?.let { append("""<meta property="og:description" content="$it">""") }
    }

    @Test
    fun spotifyPagesMissingPartsGiveWhatIsThere() {
        assertNull(MetadataParsers.spotify(og(" | Spotify"), ItemType.TRACK))
        // No description, no artist.
        assertEquals(MusicMetadata("Song", ""), MetadataParsers.spotify(og("Song"), ItemType.TRACK))
        // "by" the album's artist is only dropped when the title also has the album's name after a dash.
        assertEquals("Name by Band", MetadataParsers.spotify(og("Name by Band", "Band · album · 2020"), ItemType.ALBUM)!!.title)
        assertEquals("Name - Album by Other", MetadataParsers.spotify(og("Name - Album by Other", "Band · album · 2020"), ItemType.ALBUM)!!.title)
        assertEquals("Name", MetadataParsers.spotify(og("Name - Album by Band", "Band · album · 2020"), ItemType.ALBUM)!!.title)
        // A count too big to be one is no count.
        val huge = og("Album", "Band · album · 2020") + """<meta name="music:song_count" content="99999999999">"""
        assertNull(MetadataParsers.spotify(huge, ItemType.ALBUM)!!.trackCount)
        assertEquals(12, MetadataParsers.spotify(og("Album", "Band · album · 2020") + """<meta name="music:song_count" content="12">""", ItemType.ALBUM)!!.trackCount)
    }

    @Test
    fun playlistCoversSkipWhatTheServiceLeftOut() {
        fun page(data: String) = """<script id="initialState" type="text/plain">${java.util.Base64.getEncoder().encodeToString(data.toByteArray())}</script>"""
        fun song(uri: String, album: String) = """{"itemV2":{"data":{"uri":"$uri"$album}}}"""
        fun art(sources: String) = ""","albumOfTrack":{"coverArt":{"sources":$sources}}"""
        val items = listOf(
            "5", "{}", """{"content":{}}""", """{"content":{"items":[5,{},{"itemV2":{}},
                ${song("spotify:track:none", "")},
                ${song("spotify:track:noart", ""","albumOfTrack":{}""")},
                ${song("spotify:track:nosources", ""","albumOfTrack":{"coverArt":{}}""")},
                ${song("spotify:track:nocovers", art("[]"))},
                ${song("spotify:track:blank", art("""[{"url":"","width":300}]"""))},
                ${song("spotify:track:ok", art("""[{"url":"https://img/640","width":640},{"url":"https://img/300","width":300}]"""))},
                ${song("spotify:album:notasong", art("""[{"url":"https://img/x","width":300}]"""))}]}}"""
        )
        val state = """{"entities":{"items":{${items.mapIndexed { index, item -> "\"i$index\":$item" }.joinToString(",")}}}}"""
        assertEquals(mapOf("https://open.spotify.com/track/ok" to "https://img/300"), MetadataParsers.spotifyCovers(page(state)))
        assertEquals(emptyMap<String, String>(), MetadataParsers.spotifyCovers(page("{}")))
        assertEquals(emptyMap<String, String>(), MetadataParsers.spotifyCovers(page("""{"entities":{}}""")))
        assertEquals(emptyMap<String, String>(), MetadataParsers.spotifyCovers("""<script id="initialState" type="text/plain">%%%</script>"""))
        assertEquals(emptyMap<String, String>(), MetadataParsers.spotifyCovers("<html></html>"))
    }

    private fun musicVideo(fields: String, type: String = "ATV") = json(
        """{"panel":{"playlistPanelVideoRenderer":{"videoId":"v1",$fields,"navigationEndpoint":{"musicVideoType":"MUSIC_VIDEO_TYPE_$type"}}}}"""
    )

    private fun runs(text: String) = """{"runs":[{"text":"$text"}]}"""

    @Test
    fun youTubeMusicVideosKeepWhatItSaysAndLeaveOutWhatItDoesnt() {
        fun named(fields: String, type: String = "ATV") = MetadataParsers.youtubeMusicVideo(musicVideo(fields, type), "v1")
        val song = """"title":${runs("Song")},"longBylineText":${runs("Band • Album")}"""
        assertNull(named(""""longBylineText":${runs("Band")}"""))
        assertNull(named(""""title":${runs("(Official Video)")},"longBylineText":${runs("Band")}""", "OMV"))
        // A song's length counts hours, minutes and seconds, and only those.
        assertEquals(3_723_000L, named("""$song,"lengthText":${runs("1:02:03")}""")!!.lengthMs)
        assertEquals(262_000L, named("""$song,"lengthText":${runs("4:22")}""")!!.lengthMs)
        assertNull(named("""$song,"lengthText":${runs("4")}""")!!.lengthMs)
        assertNull(named("""$song,"lengthText":${runs("1:2:3:4")}""")!!.lengthMs)
        assertNull(named(song)!!.lengthMs)
        // The cover is the last picture, if it has an address.
        assertNull(named(song)!!.artworkUrl)
        assertNull(named("""$song,"thumbnail":{}""")!!.artworkUrl)
        assertNull(named("""$song,"thumbnail":{"thumbnails":[]}""")!!.artworkUrl)
        assertNull(named("""$song,"thumbnail":{"thumbnails":[{"url":"https://a"},{}]}""")!!.artworkUrl)
        assertEquals("https://b", named("""$song,"thumbnail":{"thumbnails":[{"url":"https://a"},{"url":"https://b"}]}""")!!.artworkUrl)
        // Someone's upload has no length to read.
        assertNull(named("""$song,"lengthText":${runs("4:22")}""", "UGC")!!.lengthMs)
    }

    @Test
    fun youTubeMusicPagesWithLessThanAnAlbumHasStillGetTheirName() {
        fun page(header: String, rest: String = "") = json("""{"header":{"musicDetailHeaderRenderer":$header}$rest}""")
        fun named(header: String, rest: String = "") = MetadataParsers.youtubeMusicPage(page(header, rest), ItemType.ALBUM)
        assertNull(named("{}"))
        val withTitle = """"title":${runs("Pocket Park")}"""
        assertEquals(MusicMetadata("Pocket Park", "", ItemType.ALBUM), named("{$withTitle}"))
        assertNull(named("{$withTitle,\"thumbnail\":{\"thumbnails\":[]}}")!!.artworkUrl)
        assertNull(named("{$withTitle,\"thumbnail\":{\"thumbnails\":[{}]}}")!!.artworkUrl)
        // Rows with no name for a song aren't songs.
        val row = { columns: String -> """{"musicResponsiveListItemRenderer":$columns}""" }
        val rows = listOf(
            row("{}"), row("""{"flexColumns":[]}"""), row("""{"flexColumns":[{}]}"""),
            row("""{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":${runs(" ")}}}]}"""),
            row("""{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{}}]}"""),
        )
        assertEquals(emptyList<MusicMetadata>(), named("{$withTitle}", ""","contents":[${rows.joinToString(",")}]""")!!.tracks)
        // An artist's page is just the name.
        assertEquals(MusicMetadata("Band", "", ItemType.ARTIST), MetadataParsers.youtubeMusicPage(json("""{"a":[{"musicVisualHeaderRenderer":{"title":${runs("Band")}}}]}"""), ItemType.ARTIST))
        assertNull(MetadataParsers.youtubeMusicPage(json("""{"a":[{"musicVisualHeaderRenderer":{}}]}"""), ItemType.ARTIST))
    }

    @Test
    fun aSongNamedBothWaysIsBySomeoneOnlyWhenTheChannelSaysWhoOrNobodyDoes() {
        // With no channel to go by, the dash is the artist's.
        assertEquals(MusicMetadata("Ciro y los Persas", "Luz"), MetadataParsers.youtubeVideo("Luz - Ciro y los Persas", ""))
        // The channel is the right-hand name: the song is the left.
        assertEquals(MusicMetadata("Luz", "Ciro y los Persas"), MetadataParsers.youtubeVideo("Luz - Ciro y los Persas", "Ciro y los Persas"))
        // Both names are the channel's, so the dash is the artist's.
        assertEquals(MusicMetadata("Ciro y los Persas", "Ciro y los Persas"), MetadataParsers.youtubeVideo("Ciro y los Persas - Ciro y los Persas", "Ciro y los Persas"))
        // The channel is neither.
        assertEquals(MusicMetadata("Song", "Band"), MetadataParsers.youtubeVideo("Band - Song", "Somebody else"))
    }

    @Test
    fun aSongSaidAgainInJapaneseNeedsItsArtistToBeInJapaneseToo() {
        assertEquals("Plastic Love", MetadataParsers.beforeItInJapanese("Plastic Love 竹内まりや - プラスティック・ラブ 1984"))
        // The second name isn't in Japanese, or the first one isn't all.
        assertNull(MetadataParsers.beforeItInJapanese("Plastic Love 竹内まりや - Plastic Love"))
        assertNull(MetadataParsers.beforeItInJapanese("Plastic Love 竹内まりや feat. Mari - プラスティック・ラブ"))
        assertNull(MetadataParsers.beforeItInJapanese("Plastic Love - プラスティック・ラブ"))
        assertNull(MetadataParsers.beforeItInJapanese("Plastic Love"))
    }

    private fun lockup(fields: String, type: String = "LOCKUP_CONTENT_TYPE_VIDEO") = """{"lockupViewModel":{"contentType":"$type",$fields}}"""

    @Test
    fun youTubePlaylistPagesListTheirVideosWhateverPartsTheyLackAndIgnoreOtherLockups() {
        fun page(data: String?, title: String? = "Mix") = og(title) + (data?.let { """<script>var ytInitialData = $it;</script>""" } ?: "")
        fun title(text: String) = """"title":{"content":"$text"}"""
        fun metadata(inside: String) = """"metadata":{"lockupMetadataViewModel":{${title("Song")}$inside}}"""
        val lockups = listOf(
            // The channel is read from deep inside; any part of it missing leaves the video without one.
            lockup(metadata("")),
            lockup(metadata(""","metadata":{}""")),
            lockup(metadata(""","metadata":{"contentMetadataViewModel":{}}""")),
            lockup(metadata(""","metadata":{"contentMetadataViewModel":{"metadataRows":[]}}""")),
            lockup(metadata(""","metadata":{"contentMetadataViewModel":{"metadataRows":[{}]}}""")),
            lockup(metadata(""","metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[]}]}}""")),
            lockup(metadata(""","metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{}]}]}}""")),
            lockup(metadata(""","metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{}}]}]}}"""), "LOCKUP_CONTENT_TYPE_VIDEO"),
            // No title, no song; not a video, not looked into.
            lockup("\"metadata\":{}"), lockup("\"contentId\":\"x\""), lockup(metadata(""), "LOCKUP_CONTENT_TYPE_PLAYLIST")
        )
        val videos = MetadataParsers.youtubePlaylist(page("""{"items":[${lockups.joinToString(",")},5]}"""))!!.tracks
        assertEquals(List(8) { MusicMetadata("Song", "") }, videos)
        // With an id, a video has its picture and link.
        val withId = MetadataParsers.youtubePlaylist(page("""[${lockup("\"contentId\":\"abc\"," + metadata(""))}]"""))!!.tracks.single()
        assertEquals("https://www.youtube.com/watch?v=abc", withId.url)
        assertEquals("https://i.ytimg.com/vi/abc/mqdefault.jpg", withId.artworkUrl)
        // No title for the playlist, or nothing in its data to read, still gives what's there.
        assertNull(MetadataParsers.youtubePlaylist(page(null, title = null)))
        assertNull(MetadataParsers.youtubePlaylist(page(null, title = " ")))
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.youtubePlaylist(page(null))!!.tracks)
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.youtubePlaylist(page("{"))!!.tracks)
    }

    @Test
    fun appleLookupsAndPlaylistsWithLessThanTheyShouldStillAnswerOrNot() {
        assertNull(MetadataParsers.appleMusic(json("{}"), ItemType.TRACK))
        assertNull(MetadataParsers.appleMusic(json("""{"results":[]}"""), ItemType.TRACK))
        // An album's songs are the track results after it; anything else there is left out.
        val album = json("""{"results":[{"collectionName":"Album - EP","artistName":"Band"},5,{"wrapperType":"collection"},{"wrapperType":"track","trackName":""},{"wrapperType":"track","trackName":"One","artistName":"Band"}]}""")
        assertEquals(listOf(MusicMetadata("One", "Band")), MetadataParsers.appleMusic(album, ItemType.ALBUM)!!.tracks)
        // A playlist is named by its page.
        fun playlist(data: String?, title: String? = "Mix on Apple Music") =
            og(title) + (data?.let { """<script type="application/json" id="serialized-server-data">$it</script>""" } ?: "")
        assertNull(MetadataParsers.applePlaylist(playlist(null, title = null)))
        assertNull(MetadataParsers.applePlaylist(playlist(null, title = " on Apple Music")))
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.applePlaylist(playlist(null))!!.tracks)
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.applePlaylist(playlist("{"))!!.tracks)
        fun song(title: String, artwork: String) = """{"title":"$title","artistName":"Band","contentDescriptor":{"kind":"song"}$artwork}"""
        val songs = MetadataParsers.applePlaylist(
            playlist(
                """[${song("A", "")},${song("B", ""","artwork":{}""")},${song("C", ""","artwork":{"dictionary":{}}""")},${song("D", ""","artwork":{"dictionary":{"url":""}}""")},
                ${song("E", ""","artwork":{"dictionary":{"url":"https://img/{w}x{h}bb.{f}"}}""")},${song("", "")},{"title":"Not a song"},7]"""
            )
        )!!.tracks
        assertEquals(listOf("A", "B", "C", "D", "E"), songs.map { it.title })
        assertEquals(listOf(null, null, null, null, "https://img/300x300bb.jpg"), songs.map { it.artworkUrl })
    }

    @Test
    fun spotifyEmbedPagesWithoutTheirSongListGiveNone() {
        fun page(data: String) = """<script id="__NEXT_DATA__" type="application/json">$data</script>"""
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.spotifyEmbedTracks("<html></html>"))
        val path = listOf("props", "pageProps", "state", "data", "entity")
        for (depth in 0..path.size) {
            val partial = path.take(depth).foldRight("{}") { key, inner -> """{"$key":$inner}""" }
            assertEquals(partial, emptyList<MusicMetadata>(), MetadataParsers.spotifyEmbedTracks(page(partial)))
        }
        val tracks = """[5,{"title":""},{"title":"One","subtitle":"Band","uri":"spotify:track:1"}]"""
        val entity = path.foldRight("""{"trackList":$tracks}""") { key, inner -> """{"$key":$inner}""" }
        assertEquals(listOf(MusicMetadata("One", "Band", url = "https://open.spotify.com/track/1")), MetadataParsers.spotifyEmbedTracks(page(entity)))
    }

    @Test
    fun deezerAnswersWithLessThanTheyShouldStillGiveTheirNameAndCover() {
        // Without a cover of its own, the album's.
        assertEquals("https://c/album", MetadataParsers.deezer(json("""{"title":"Song","album":{"cover_big":"https://c/album"}}"""), ItemType.TRACK)!!.artworkUrl)
        assertNull(MetadataParsers.deezer(json("""{"title":"Song","album":{}}"""), ItemType.TRACK)!!.artworkUrl)
        assertEquals("https://c/own", MetadataParsers.deezer(json("""{"title":"Album","cover_big":"https://c/own","picture_big":"https://c/picture"}"""), ItemType.ALBUM)!!.artworkUrl)
        assertEquals("https://c/picture", MetadataParsers.deezer(json("""{"name":"Band","picture_big":"https://c/picture"}"""), ItemType.ARTIST)!!.artworkUrl)
        // The list of songs may be missing, or empty of anything with a name.
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.deezer(json("""{"title":"Mix","tracks":{}}"""), ItemType.PLAYLIST)!!.tracks)
        val tracks = """[5,{"title":""},{"title":"A","artist":{"name":"Band"},"album":{"cover_medium":"https://c/a"}},{"title":"B"},{"title":"C","album":{}},{"title":"D","album":{"cover_medium":""}}]"""
        val listed = MetadataParsers.deezer(json("""{"title":"Mix","tracks":{"data":$tracks}}"""), ItemType.PLAYLIST)!!.tracks
        assertEquals(listOf("A", "B", "C", "D"), listed.map { it.title })
        assertEquals(listOf("Band", "", "", ""), listed.map { it.artist })
        assertEquals(listOf("https://c/a", null, null, null), listed.map { it.artworkUrl })
    }

    @Test
    fun tidalSoundCloudAndBandcampPagesLackingPartsGiveWhatIsThere() {
        assertNull(MetadataParsers.tidal("<html></html>", ItemType.TRACK))
        assertNull(MetadataParsers.tidal("<title>  on TIDAL</title>", ItemType.TRACK))
        // An author who isn't the one the title says is not split from it.
        assertEquals(MusicMetadata("Song by Somebody", "Band", ItemType.TRACK, "https://c/x"), MetadataParsers.soundCloud(json("""{"title":"Song by Somebody","author_name":"Band","thumbnail_url":"https://c/x"}"""), ItemType.TRACK))
        assertEquals(MusicMetadata("Song", "", ItemType.TRACK), MetadataParsers.soundCloud(json("""{"title":"Song"}"""), ItemType.TRACK))
        assertEquals(MusicMetadata("Song", "", ItemType.ARTIST), MetadataParsers.soundCloud(json("""{"title":"Song"}"""), ItemType.ARTIST))
        // Bandcamp
        assertNull(MetadataParsers.bandcamp("<html></html>", ItemType.ALBUM))
        assertNull(MetadataParsers.bandcamp(og(" "), ItemType.ALBUM))
        fun album(data: String?, image: String = "") = og("Volume Alpha, by C418") + image + (data?.let { """<script type="application/ld+json" id="x">$it</script>""" } ?: "")
        assertNull(MetadataParsers.bandcamp(album(null, """<meta property="og:image" content=" ">"""), ItemType.ALBUM)!!.artworkUrl)
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.bandcamp(album(null), ItemType.ALBUM)!!.tracks)
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.bandcamp(album("not json"), ItemType.ALBUM)!!.tracks)
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.bandcamp(album("""{"byArtist":{"name":"C418"}}"""), ItemType.ALBUM)!!.tracks)
        assertEquals(emptyList<MusicMetadata>(), MetadataParsers.bandcamp(album("""{"track":{}}"""), ItemType.ALBUM)!!.tracks)
        val listed = MetadataParsers.bandcamp(
            album("""{"track":{"itemListElement":[5,{},{"item":{}},{"item":{"name":""}},{"item":{"name":"Key"}}]}}"""), ItemType.ALBUM
        )!!.tracks
        assertEquals(listOf(MusicMetadata("Key", "")), listed)
    }
}
