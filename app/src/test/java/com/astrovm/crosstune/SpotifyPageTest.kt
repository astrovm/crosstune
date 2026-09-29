package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyPageTest {

    @Test
    fun readsTagsInAnyAttributeOrderAndQuoteStyle() {
        val html = """
            <meta content='Carly Rae Jepsen · Cut To The Feeling · Song · 2017' property='og:description'>
            <META CONTENT="Cut To The Feeling" PROPERTY="og:title" />
        """
        assertEquals(SpotifyMetadata("Cut To The Feeling", "Carly Rae Jepsen"), SpotifyPage.parse(html, SpotifyType.TRACK))
    }

    @Test
    fun firstTagWinsAndIncompleteTagsAreSkipped() {
        val html = """
            <meta name="viewport" content="width=device-width">
            <meta property="og:title">
            <meta property="og:title" content="First">
            <meta property="og:title" content="Second">
        """
        assertEquals(SpotifyMetadata("First", ""), SpotifyPage.parse(html, SpotifyType.TRACK))
    }

    @Test
    fun missingOrBlankTitleIsNotATrack() {
        assertNull(SpotifyPage.parse("<html></html>", SpotifyType.TRACK))
        assertNull(SpotifyPage.parse("""<meta property="og:title" content="  ">""", SpotifyType.TRACK))
    }

    @Test
    fun decodesNamedDecimalAndHexEntities() {
        assertEquals(
            "Rock & Roll <AC/DC> \"live\" it's Beyoncé 🎵 x",
            SpotifyPage.decodeEntities("Rock &amp; Roll &lt;AC&#47;DC&gt; &quot;live&quot; it&apos;s Beyonc&#xE9; &#x1F3B5;&nbsp;x")
        )
    }

    @Test
    fun leavesUnknownOrInvalidEntitiesUntouched() {
        assertEquals(
            "&bogus; &#1114112; &#99999999999;",
            SpotifyPage.decodeEntities("&bogus; &#1114112; &#99999999999;")
        )
    }

    @Test
    fun albumTitleDropsTheAlbumByArtistSuffix() {
        val html = """
            <meta property="og:title" content="After Hours - Deluxe - Album by The Weeknd | Spotify">
            <meta property="og:description" content="The Weeknd · album · 2020 · 14 songs">
        """
        assertEquals(
            SpotifyMetadata("After Hours - Deluxe", "The Weeknd", SpotifyType.ALBUM),
            SpotifyPage.parse(html, SpotifyType.ALBUM)
        )
        // Tracks keep their full title even when it looks like the album pattern.
        assertEquals(
            "After Hours - Deluxe - Album by The Weeknd",
            SpotifyPage.parse(html, SpotifyType.TRACK)?.title
        )
        val plain = """<meta property="og:title" content="Starboy"><meta property="og:description" content="The Weeknd · album">"""
        assertEquals("Starboy", SpotifyPage.parse(plain, SpotifyType.ALBUM)?.title)
    }

    @Test
    fun artistsAndPlaylistsIgnoreTheirFreeTextDescription() {
        val html = """<meta property="og:title" content="Today&#8217;s Top Hits"><meta property="og:description" content="The hottest 50 · Cover: ADÉLA">"""
        assertEquals(
            SpotifyMetadata("Today\u2019s Top Hits", "", SpotifyType.PLAYLIST),
            SpotifyPage.parse(html, SpotifyType.PLAYLIST)
        )
        assertEquals("", SpotifyPage.parse(html, SpotifyType.ARTIST)?.artist)
    }
}
