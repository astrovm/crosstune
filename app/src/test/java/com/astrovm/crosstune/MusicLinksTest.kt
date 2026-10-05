package com.astrovm.crosstune

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicLinksTest {

    private fun link(text: String): MusicLink? = (MusicLinks.parse(text) as? LinkInput.Link)?.link

    private fun assertLink(text: String, service: MusicService, type: ItemType, id: String, url: String, region: String? = null) {
        assertEquals(text, MusicLink(service, type, id, url, region), link(text))
    }

    /** What Now Playing shares: the song in words, then a search whose spaces became "+" and nothing else. */
    private fun nowPlaying(song: String, search: String = song) = "$song\nhttps://www.google.com/search?q=${search.replace(' ', '+')}"

    private fun recognized(text: String) = (MusicLinks.parse(text) as? LinkInput.RecognizedSong)?.metadata

    @Test
    fun shazamSongsOpenAsTheirAppleMusicSong() {
        val song = LinkInput.Link(MusicLink(MusicService.APPLE_MUSIC, ItemType.TRACK, "1109658204", "https://music.apple.com/us/song/1109658204", "us"))
        assertEquals(song, MusicLinks.parse("https://www.shazam.com/song/1109658204/iris"))
        // As Shazam shares it, and copied without the scheme.
        assertEquals(song, MusicLinks.parse("I used Shazam to discover Iris by The Goo Goo Dolls. https://www.shazam.com/song/1109658204/iris?referrer=share"))
        assertEquals(song, MusicLinks.parse("shazam.com/song/1109658204"))
        // Older links use Shazam's own key, looked up later.
        assertEquals(LinkInput.ShazamTrack("20066955"), MusicLinks.parse("https://www.shazam.com/track/20066955/kiss-the-rain"))
        // Shazam's other pages aren't songs.
        assertEquals(null, MusicLinks.parse("https://www.shazam.com/artist/hugel/978839124"))
        assertEquals(null, MusicLinks.parse("https://www.shazam.com/song/iris"))
        assertEquals(null, MusicLinks.parse("https://www.shazam.com/track/kiss-the-rain"))
        assertEquals(null, MusicLinks.parse("https://www.shazam.com/charts/top-200/world"))
    }

    @Test
    fun googleSongResultsAreSearchedByTitle() {
        val iris = LinkInput.RecognizedSong("https://www.google.com/search?q=Iris&kgmid=%2Fg%2F11c2p4p6vv", MusicMetadata("Iris", ""))
        // Where Google's shared result leads, on any of its sites.
        assertEquals(iris, MusicLinks.parse("https://www.google.com/search?kgmid=/g/11c2p4p6vv&hl=en-AR&q=Iris&shem=dlvs1&source=sh/x/kp/osrp/m1/4"))
        assertEquals(iris, MusicLinks.parse("https://google.com.ar/search?q=Iris&kgmid=/g/11c2p4p6vv"))
        // With no artist, the field shows the link itself.
        assertEquals(iris.url, iris.text)
        // A plain search isn't a song, nor is a look-alike site.
        assertEquals(null, MusicLinks.parse("https://www.google.com/search?q=Iris"))
        assertEquals(null, MusicLinks.parse("https://www.google.com/search?kgmid=/g/11c2p4p6vv"))
        assertEquals(null, MusicLinks.parse("https://notgoogle.com/search?q=Iris&kgmid=/g/1"))
        assertEquals(null, MusicLinks.parse("https://www.google.com/maps?q=Iris&kgmid=/g/1"))
        // Its short links are followed later.
        assertEquals(LinkInput.ShortLink("https://share.google/09OHXUIfQTRCqXUCJ"), MusicLinks.parse("Iris https://share.google/09OHXUIfQTRCqXUCJ"))
        assertEquals(LinkInput.ShortLink("https://g.co/kgs/AbC123"), MusicLinks.parse("https://g.co/kgs/AbC123"))
        assertEquals(null, MusicLinks.parse("https://g.co/meet/abc"))
    }

    @Test
    fun nowPlayingSharesDecodeSongAndArtist() {
        val expected = LinkInput.RecognizedSong(
            "https://www.google.com/search?q=A%20Song%20by%20Example%20Band",
            MusicMetadata("A Song", "Example Band")
        )
        assertEquals(expected, MusicLinks.parse(nowPlaying("A Song by Example Band")))
        // The link box shows it on one line, which reads back as the same song.
        assertEquals(expected, MusicLinks.parse(expected.text))
        assertEquals(MusicMetadata("Walk by Night", "Band"), recognized(nowPlaying("Walk by Night by Band")))
        // The search isn't encoded, so "&" and "+" stay as they are.
        assertEquals(MusicMetadata("Mrs. Robinson", "Simon & Garfunkel"), recognized(nowPlaying("Mrs. Robinson by Simon & Garfunkel")))
        assertEquals(MusicMetadata("Gonna Make You Sweat", "C+C Music Factory"), recognized(nowPlaying("Gonna Make You Sweat by C+C Music Factory")))
    }

    @Test
    fun nowPlayingSharesInEveryLanguage() {
        assertEquals(MusicMetadata("Canción", "Artista"), recognized(nowPlaying("Canción de Artista")))
        assertEquals(MusicMetadata("Lied", "Band"), recognized(nowPlaying("„Lied“ von Band")))
        assertEquals(MusicMetadata("노래", "가수"), recognized(nowPlaying("가수의 노래")))
        assertEquals(MusicMetadata("歌", "歌手"), recognized(nowPlaying("歌手的《歌》")))
        // Some languages share one wording and search another.
        assertEquals(MusicMetadata("Chanson", "Groupe"), recognized(nowPlaying("Chanson par Groupe", "Chanson (Groupe)")))
        assertEquals(MusicMetadata("Şarkı", "Grup"), recognized(nowPlaying("Grup, Şarkı", "Şarkı, Grup")))
        assertEquals(
            MusicMetadata("Песня", "Группа"),
            recognized(nowPlaying("\"Песня\", Группа", "Песня \"Песня\" исполнителя \"Группа\""))
        )
    }

    @Test
    fun otherGoogleSearchesAreNotSongs() {
        listOf(
            "https://www.google.com/search?q=A+Song+by+Example+Band",
            "https://www.google.com/search?q=A%20Song%20by%20Example%20Band",
            "https://www.google.com/search?q=what+to+do+by+tomorrow",
            "Look at this https://www.google.com/search?q=A+Song+by+Example+Band",
            nowPlaying("A Song by Other Band", "A Song by Example Band"),
            nowPlaying("weather"),
            nowPlaying(" by Band"),
            nowPlaying("A Song by Example Band") + "&utm_source=test",
            "A Song by Example Band\nhttps://google.com/search?q=A+Song+by+Example+Band",
            "A Song by Example Band\nhttps://www.google.com.evil.example/search?q=A+Song+by+Example+Band"
        ).forEach { assertNull(it, MusicLinks.parse(it)) }
    }

    @Test
    fun youtubeVideosFromEveryUrlShape() {
        val id = "4NRXx6U8ABQ"
        val youtube = "https://www.youtube.com/watch?v=$id"
        assertLink("https://www.youtube.com/watch?v=$id&t=10", MusicService.YOUTUBE, ItemType.TRACK, id, youtube)
        assertLink("https://m.youtube.com/watch?v=$id", MusicService.YOUTUBE, ItemType.TRACK, id, youtube)
        assertLink("https://youtube.com/shorts/$id", MusicService.YOUTUBE, ItemType.TRACK, id, youtube)
        assertLink("https://www.youtube.com/live/$id", MusicService.YOUTUBE, ItemType.TRACK, id, youtube)
        assertLink("https://youtu.be/$id?si=abc", MusicService.YOUTUBE, ItemType.TRACK, id, youtube)
        assertLink(
            "Listen: https://music.youtube.com/watch?v=$id&list=RDAMVM",
            MusicService.YOUTUBE_MUSIC, ItemType.TRACK, id, "https://music.youtube.com/watch?v=$id"
        )

        // Playlists and channels have no usable metadata; malformed IDs aren't videos.
        listOf(
            "https://music.youtube.com/playlist?list=OLAK5uy_x",
            "https://www.youtube.com/@TheWeeknd",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/watch?v=short",
            "https://youtu.be/",
            "https://www.youtube.com/shorts/$id/extra"
        ).forEach { assertNull(it, link(it)) }
    }

    @Test
    fun invidiousAndPipedLinksOnAnySiteAreYouTubeVideos() {
        val id = "4NRXx6U8ABQ"
        listOf("https://yewtu.be/watch?v=$id&t=5", "https://piped.example.org/watch?v=$id").forEach { url ->
            val parsed = link(url)!!
            assertEquals(url, MusicService.YOUTUBE, parsed.service)
            assertEquals("https://www.youtube.com/watch?v=$id", parsed.url)
            assertEquals(true, parsed.viaFrontend)
        }
        // The popular sites' other video paths count too, but not on any site.
        assertEquals("https://www.youtube.com/watch?v=$id", link("https://inv.nadeko.net/shorts/$id")!!.url)
        assertEquals("https://www.youtube.com/watch?v=$id", link("https://piped.video/embed/$id")!!.url)
        assertNull(link("https://example.org/embed/$id"))
        assertNull(link("https://piped.video/playlist?list=PL1"))
        // Only a video's watch page counts, and only with a real video ID.
        assertNull(link("https://yewtu.be/watch?v=short"))
        assertNull(link("https://yewtu.be/channel/$id"))
        assertEquals(false, link("https://www.youtube.com/watch?v=$id")!!.viaFrontend)
    }

    @Test
    fun appleMusicSongsAlbumsArtistsAndPlaylists() {
        assertLink(
            "https://music.apple.com/AR/song/cut-to-the-feeling/1445304939",
            MusicService.APPLE_MUSIC, ItemType.TRACK, "1445304939", "https://music.apple.com/AR/song/cut-to-the-feeling/1445304939", "ar"
        )
        assertLink(
            "https://music.apple.com/us/album/cut-to-the-feeling/1445304934?i=1445304939&ls=1",
            MusicService.APPLE_MUSIC, ItemType.TRACK, "1445304939",
            "https://music.apple.com/us/album/cut-to-the-feeling/1445304934?i=1445304939", "us"
        )
        assertLink(
            "https://music.apple.com/gb/album/after-hours/1499385848",
            MusicService.APPLE_MUSIC, ItemType.ALBUM, "1499385848", "https://music.apple.com/gb/album/after-hours/1499385848", "gb"
        )
        assertLink(
            "http://itunes.apple.com/album/id1499385848",
            MusicService.APPLE_MUSIC, ItemType.ALBUM, "1499385848", "https://itunes.apple.com/album/id1499385848", "us"
        )
        assertLink(
            "https://geo.music.apple.com/artist/the-weeknd/479756766",
            MusicService.APPLE_MUSIC, ItemType.ARTIST, "479756766", "https://geo.music.apple.com/artist/the-weeknd/479756766", "us"
        )
        assertLink(
            "https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd",
            MusicService.APPLE_MUSIC, ItemType.PLAYLIST, "pl.f4d106fed2bd",
            "https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd", "us"
        )
        listOf(
            "https://music.apple.com/us/browse",
            "https://music.apple.com/us/album",
            "https://music.apple.com/us/album/name/not-a-number",
            "https://music.apple.com/us/playlist/name/not-a-playlist-id"
        ).forEach { assertNull(it, link(it)) }
    }

    @Test
    fun deezerAndTidalItemsWithOrWithoutLocalePrefixes() {
        assertLink("https://www.deezer.com/en/track/908604612", MusicService.DEEZER, ItemType.TRACK, "908604612", "https://www.deezer.com/track/908604612")
        assertLink("https://deezer.com/album/137272602", MusicService.DEEZER, ItemType.ALBUM, "137272602", "https://www.deezer.com/album/137272602")
        assertLink("https://www.deezer.com/fr/artist/4050205", MusicService.DEEZER, ItemType.ARTIST, "4050205", "https://www.deezer.com/artist/4050205")
        assertLink("https://www.deezer.com/playlist/3155776842", MusicService.DEEZER, ItemType.PLAYLIST, "3155776842", "https://www.deezer.com/playlist/3155776842")
        assertNull(link("https://www.deezer.com/track/abc"))

        assertLink("https://tidal.com/browse/track/134858527", MusicService.TIDAL, ItemType.TRACK, "134858527", "https://tidal.com/track/134858527")
        assertLink("https://listen.tidal.com/album/134858522", MusicService.TIDAL, ItemType.ALBUM, "134858522", "https://tidal.com/album/134858522")
        assertLink(
            "https://www.tidal.com/playlist/0a1b-2c3d",
            MusicService.TIDAL, ItemType.PLAYLIST, "0a1b-2c3d", "https://tidal.com/playlist/0a1b-2c3d"
        )
        assertNull(link("https://tidal.com/track/bad_id!"))
        assertNull(link("https://tidal.com/browse"))
    }

    @Test
    fun soundCloudArtistsTracksAndSets() {
        assertLink("https://soundcloud.com/theweeknd", MusicService.SOUNDCLOUD, ItemType.ARTIST, "theweeknd", "https://soundcloud.com/theweeknd")
        assertLink("https://m.soundcloud.com/theweeknd/tracks", MusicService.SOUNDCLOUD, ItemType.ARTIST, "theweeknd", "https://soundcloud.com/theweeknd")
        assertLink(
            "https://soundcloud.com/theweeknd/blinding-lights?si=1",
            MusicService.SOUNDCLOUD, ItemType.TRACK, "theweeknd/blinding-lights", "https://soundcloud.com/theweeknd/blinding-lights"
        )
        assertLink(
            "https://www.soundcloud.com/theweeknd/sets/after-hours",
            MusicService.SOUNDCLOUD, ItemType.PLAYLIST, "theweeknd/sets/after-hours", "https://soundcloud.com/theweeknd/sets/after-hours"
        )
        listOf(
            "https://soundcloud.com/",
            "https://soundcloud.com/discover",
            "https://soundcloud.com/theweeknd/blinding-lights/comments/1",
            "https://soundcloud.com/theweeknd/likes/extra"
        ).forEach { assertNull(it, link(it)) }
    }

    @Test
    fun bandcampTracksAndAlbums() {
        assertLink(
            "https://artist.bandcamp.com/track/song?from=x",
            MusicService.BANDCAMP, ItemType.TRACK, "artist.bandcamp.com/track/song", "https://artist.bandcamp.com/track/song"
        )
        assertLink(
            "https://artist.bandcamp.com/album/record",
            MusicService.BANDCAMP, ItemType.ALBUM, "artist.bandcamp.com/album/record", "https://artist.bandcamp.com/album/record"
        )
        listOf(
            "https://artist.bandcamp.com/",
            "https://artist.bandcamp.com/merch/shirt",
            "https://daily.bandcamp.com/track/feature",
            "https://bandcamp.com/track/x"
        ).forEach { assertNull(it, link(it)) }
    }

    @Test
    fun shortLinksFromEveryServiceAreFollowedLater() {
        listOf(
            "https://spotify.link/abc", "http://www.spotify.link/abc", "https://link.deezer.com/s/abc",
            "https://deezer.page.link/abc", "https://dzr.page.link/abc", "https://on.soundcloud.com/abc"
        ).forEach { text ->
            val input = MusicLinks.parse(text) as LinkInput.ShortLink
            assertEquals(text.replace("http://", "https://"), input.url)
        }
        assertNull(MusicLinks.parse("https://example.com/abc"))
        assertNull(MusicLinks.parse("not a link"))
    }

    @Test
    fun spotifyLinksFoundInLandingPagesAndRedirects() {
        assertEquals(
            MusicLink(MusicService.SPOTIFY, ItemType.PLAYLIST, "37i9dQZF1DXcBWIGoYBM5M", "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"),
            MusicLinks.fromPage("<a href='https://open.spotify.com/intl-fr/playlist/37i9dQZF1DXcBWIGoYBM5M'>")
        )
        assertNull(MusicLinks.fromPage("<html></html>"))
        assertEquals(MusicService.DEEZER, MusicLinks.fromUrl("https://www.deezer.com/track/1".toHttpUrl())?.service)
        assertNull(MusicLinks.fromUrl("https://www.deezer.com/deezer-links-404".toHttpUrl()))
        assertNull(link("spotify:podcast:37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun anyPageOfAKnownServiceIsAttributedToIt() {
        val pages = mapOf(
            "https://open.spotify.com/show/abc" to MusicService.SPOTIFY,
            "https://www.spotify.com/premium" to MusicService.SPOTIFY,
            "https://spotify.link/x" to MusicService.SPOTIFY,
            "https://music.youtube.com/library" to MusicService.YOUTUBE_MUSIC,
            "https://www.youtube.com/@TheWeeknd" to MusicService.YOUTUBE,
            "https://youtu.be/" to MusicService.YOUTUBE,
            "https://youtube.com/feed" to MusicService.YOUTUBE,
            "https://music.apple.com/us/browse" to MusicService.APPLE_MUSIC,
            "https://www.deezer.com/en/channels" to MusicService.DEEZER,
            "https://deezer.page.link/x" to MusicService.DEEZER,
            "https://dzr.page.link/x" to MusicService.DEEZER,
            "https://listen.tidal.com/feed" to MusicService.TIDAL,
            "https://soundcloud.com/discover" to MusicService.SOUNDCLOUD,
            "https://artist.bandcamp.com/merch" to MusicService.BANDCAMP
        )
        pages.forEach { (url, service) -> assertEquals(url, service, MusicLinks.serviceFor(url)) }
        assertNull(MusicLinks.serviceFor("https://example.com/music"))
        assertNull(MusicLinks.serviceFor("not a url"))
    }

    @Test
    fun linksWrappedInQuotesOrBracketsAreFound() {
        val spotify = "https://open.spotify.com/track/11dFghVXANMlKmJXsNCbNl"
        listOf("\"$spotify\"", "'$spotify'", "<$spotify>", "「$spotify」", "Listen: $spotify。")
            .forEach { assertEquals(it, spotify, link(it)?.url) }
    }

    @Test
    fun linksWithoutAScheme() {
        assertEquals("https://open.spotify.com/track/11dFghVXANMlKmJXsNCbNl", link("open.spotify.com/track/11dFghVXANMlKmJXsNCbNl")?.url)
        assertEquals("https://www.youtube.com/watch?v=4NRXx6U8ABQ", link("www.youtube.com/watch?v=4NRXx6U8ABQ")?.url)
        assertEquals(LinkInput.ShortLink("https://spotify.link/AbCdEf"), MusicLinks.parse("spotify.link/AbCdEf"))
        assertNull(MusicLinks.parse("example.com/track/11dFghVXANMlKmJXsNCbNl"))
        assertNull(MusicLinks.parse("just some words"))
    }

    @Test
    fun appleAlbumWithAnEmptyOrBrokenSongIdStaysAnAlbum() {
        listOf("", "abc").forEach { song ->
            assertLink(
                "https://music.apple.com/us/album/x/1499385848?i=$song",
                MusicService.APPLE_MUSIC, ItemType.ALBUM, "1499385848", "https://music.apple.com/us/album/x/1499385848", "us"
            )
        }
    }

    @Test
    fun lookalikeDomainsBelongToNoService() {
        listOf("https://notdeezer.com/x", "https://evilspotify.link/x", "https://faketidal.com/x", "https://mysoundcloud.com/x")
            .forEach { assertNull(it, MusicLinks.serviceFor(it)) }
    }

    @Test
    fun youtubeAndYouTubeMusicPlaylistsAndWhereEachOpensAsItself() {
        val list = "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"
        assertLink("https://www.youtube.com/playlist?list=$list", MusicService.YOUTUBE, ItemType.PLAYLIST, list, "https://www.youtube.com/playlist?list=$list")
        assertLink("https://music.youtube.com/playlist?list=$list&si=x", MusicService.YOUTUBE_MUSIC, ItemType.PLAYLIST, list, "https://music.youtube.com/playlist?list=$list")
        assertNull(link("https://www.youtube.com/playlist?list=short"))
        assertNull(link("https://www.youtube.com/playlist"))

        val playlist = link("https://www.youtube.com/playlist?list=$list")!!
        assertEquals("https://music.youtube.com/playlist?list=$list", playlist.youtubePlaylistOn(MusicService.YOUTUBE_MUSIC))
        assertEquals("https://www.youtube.com/playlist?list=$list", playlist.youtubePlaylistOn(MusicService.YOUTUBE))
        assertNull(playlist.youtubePlaylistOn(MusicService.SPOTIFY))
        assertNull(playlist.youtubePlaylistOn(null))
        assertNull(link("https://www.youtube.com/watch?v=4NRXx6U8ABQ")!!.youtubePlaylistOn(MusicService.YOUTUBE_MUSIC))
        assertNull(link("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")!!.youtubePlaylistOn(MusicService.YOUTUBE_MUSIC))
    }

    @Test
    fun audiomackSongsAlbumsAndPlaylists() {
        assertLink("https://audiomack.com/burna-boy/song/last-last", MusicService.AUDIOMACK, ItemType.TRACK, "burna-boy/last-last", "https://audiomack.com/burna-boy/song/last-last")
        assertLink("https://www.audiomack.com/burna-boy/album/love-damini", MusicService.AUDIOMACK, ItemType.ALBUM, "burna-boy/love-damini", "https://audiomack.com/burna-boy/album/love-damini")
        assertLink("https://audiomack.com/someone/playlist/mix", MusicService.AUDIOMACK, ItemType.PLAYLIST, "someone/mix", "https://audiomack.com/someone/playlist/mix")
        assertNull(link("https://audiomack.com/burna-boy"))
        assertNull(link("https://audiomack.com/burna-boy/podcast/episode"))
        assertEquals(MusicService.AUDIOMACK, MusicLinks.serviceFor("https://audiomack.com/burna-boy"))
    }
}
