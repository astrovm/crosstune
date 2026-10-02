package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrontendTest {

    private val video = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    private val search = MusicService.YOUTUBE.searchUrl("Song Artist")

    @Test
    fun webFrontendsOpenTheSameVideoOnTheirSite() {
        assertEquals("https://yewtu.be/watch?v=dQw4w9WgXcQ", Frontend.INVIDIOUS.onSite(video, "https://yewtu.be"))
        assertEquals("https://piped.video/watch?v=dQw4w9WgXcQ", Frontend.PIPED.onSite(video, "https://piped.video"))
    }

    @Test
    fun invidiousSearchesAtItsOwnPathAndPipedAtYouTubes() {
        assertEquals("https://yewtu.be/search?q=Song%20Artist", Frontend.INVIDIOUS.onSite(search, "https://yewtu.be"))
        assertEquals("https://piped.video/results?search_query=Song%20Artist", Frontend.PIPED.onSite(search, "https://piped.video"))
    }

    @Test
    fun linksThatArentYouTubesAreLeftAlone() {
        val spotify = "https://open.spotify.com/track/11dFghVXANMlKmJXsNCbNl"
        assertEquals(spotify, Frontend.INVIDIOUS.onSite(spotify, "https://yewtu.be"))
        assertEquals(video, Frontend.INVIDIOUS.onSite(video, "not a site"))
    }

    @Test
    fun anAddressBecomesTheSitesRoot() {
        assertEquals("https://yewtu.be", Frontend.instanceOf("yewtu.be"))
        assertEquals("http://192.168.1.2:3000", Frontend.instanceOf("http://192.168.1.2:3000/feed?x=1"))
        assertNull(Frontend.instanceOf("localhost"))
        assertNull(Frontend.instanceOf(""))
    }

    @Test
    fun destinationsAdaptLinksForTheirFrontend() {
        val newPipe = Destination.Alternative(Frontend.NEWPIPE)
        assertEquals(video, newPipe.adapt(video))
        assertEquals("org.schabi.newpipe", newPipe.packageName)
        assertEquals(MusicService.YOUTUBE, newPipe.matchService)
        assertEquals("frontend:NEWPIPE", newPipe.key)
        assertEquals(search, newPipe.searchUrl("Song Artist"))

        val piped = Destination.Alternative(Frontend.PIPED, "https://piped.video")
        assertNull(piped.packageName)
        assertEquals("https://piped.video/results?search_query=Song%20Artist", piped.searchUrl("Song Artist"))

        val custom = Destination.Custom("1", "Site", "https://example.com/?q={query}")
        assertNull(custom.matchService)
        assertNull(custom.packageName)
        assertEquals(video, custom.adapt(video))
    }
}
