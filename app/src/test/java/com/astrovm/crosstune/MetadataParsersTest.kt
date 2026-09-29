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
}
