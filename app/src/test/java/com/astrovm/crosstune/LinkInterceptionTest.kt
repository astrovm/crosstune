package com.astrovm.crosstune

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinkInterceptionTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val interception = LinkInterception(app)

    private fun handledByCrosstune(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        return app.packageManager.queryIntentActivities(intent, 0).any { it.activityInfo.packageName == app.packageName }
    }

    @Test
    fun onlySpotifyIsInterceptedByDefault() {
        MusicService.entries.filter { it.canBeSource }.forEach { service ->
            assertEquals(service.name, service == MusicService.SPOTIFY, interception.isEnabled(service))
        }
        assertTrue(handledByCrosstune("https://open.spotify.com/track/11dFghVXANMlKmJXsNCbNl"))
        assertFalse(handledByCrosstune("https://www.youtube.com/watch?v=4NRXx6U8ABQ"))
    }

    @Test
    fun everySourceCanBeTurnedOnAndOffAndItsLinksFollow() {
        val sampleLinks = mapOf(
            MusicService.SPOTIFY to "https://spotify.link/abc",
            MusicService.YOUTUBE_MUSIC to "https://music.youtube.com/watch?v=4NRXx6U8ABQ",
            MusicService.YOUTUBE to "https://youtu.be/4NRXx6U8ABQ",
            MusicService.APPLE_MUSIC to "https://music.apple.com/us/album/after-hours/1499385848",
            MusicService.DEEZER to "https://www.deezer.com/en/track/908604612",
            MusicService.TIDAL to "https://tidal.com/browse/track/134858527",
            MusicService.SOUNDCLOUD to "https://soundcloud.com/theweeknd/blinding-lights",
            MusicService.BANDCAMP to "https://artist.bandcamp.com/album/record"
        )
        for ((service, link) in sampleLinks) {
            interception.setEnabled(service, true)
            assertTrue(service.name, interception.isEnabled(service))
            assertTrue(link, handledByCrosstune(link))

            interception.setEnabled(service, false)
            assertFalse(service.name, interception.isEnabled(service))
            assertFalse(link, handledByCrosstune(link))
        }
        // Pages outside the supported item paths are left to their own apps.
        interception.setEnabled(MusicService.YOUTUBE, true)
        assertFalse(handledByCrosstune("https://www.youtube.com/@TheWeeknd"))
    }
}
