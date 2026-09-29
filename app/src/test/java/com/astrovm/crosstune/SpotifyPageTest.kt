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
        assertEquals(TrackMetadata("Cut To The Feeling", "Carly Rae Jepsen"), SpotifyPage.parseTrack(html))
    }

    @Test
    fun firstTagWinsAndIncompleteTagsAreSkipped() {
        val html = """
            <meta name="viewport" content="width=device-width">
            <meta property="og:title">
            <meta property="og:title" content="First">
            <meta property="og:title" content="Second">
        """
        assertEquals(TrackMetadata("First", ""), SpotifyPage.parseTrack(html))
    }

    @Test
    fun missingOrBlankTitleIsNotATrack() {
        assertNull(SpotifyPage.parseTrack("<html></html>"))
        assertNull(SpotifyPage.parseTrack("""<meta property="og:title" content="  ">"""))
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
}
