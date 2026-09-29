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
}
