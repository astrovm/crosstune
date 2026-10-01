package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingLinksTest {

    @Test
    fun dropsTrackingAndKeepsTheParametersThatPickTheItem() {
        assertEquals(
            "https://music.apple.com/us/album/song/1?i=2",
            TrackingLinks.clean("https://music.apple.com/us/album/song/1?i=2&uo=4")
        )
        assertEquals(
            "https://open.spotify.com/track/abc",
            TrackingLinks.clean("https://open.spotify.com/track/abc?si=123&utm_source=copy-link")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            TrackingLinks.clean("https://www.youtube.com/watch?v=abc&feature=shared&pp=xyz")
        )
        assertEquals(
            "https://artist.bandcamp.com/track/song",
            TrackingLinks.clean("https://artist.bandcamp.com/track/song?from=search&search_item_id=1&search_rank=1")
        )
    }

    @Test
    fun leavesSearchesOtherSitesAndCleanLinksAlone() {
        val search = "https://www.youtube.com/results?search_query=Song"
        assertEquals(search, TrackingLinks.clean(search))
        val custom = "https://example.com/search?q=Song&utm_source=x"
        assertEquals(custom, TrackingLinks.clean(custom))
        val clean = "https://www.deezer.com/track/1"
        assertEquals(clean, TrackingLinks.clean(clean))
    }
}
