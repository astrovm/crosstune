package com.astrovm.crosstune

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LinkInterceptionTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val interception = LinkInterception(app)

    private fun handledByCrosstune(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        return app.packageManager.queryIntentActivities(intent, 0).any { it.activityInfo.packageName == app.packageName }
    }

    @Test
    fun nothingIsInterceptedUntilTheUserChooses() {
        MusicService.entries.filter { it.canBeSource }.forEach { service ->
            assertFalse(service.name, interception.isEnabled(service))
        }
        assertFalse(handledByCrosstune("https://open.spotify.com/track/11dFghVXANMlKmJXsNCbNl"))
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

    @Test
    fun hostsMatchEachAliasInTheManifest() {
        MusicService.entries.filter { it.canBeSource }.forEach { service ->
            val component = ComponentName(app, "${LinkInterception.ALIAS_PREFIX}${service.name}")
            val declared = shadowOf(app.packageManager).getIntentFiltersForActivity(component)
                .flatMap { filter -> (0 until filter.countDataAuthorities()).map { filter.getDataAuthority(it).host } }
            assertEquals(service.name, declared.sorted(), LinkInterception.HOSTS[service].orEmpty().sorted())
        }
    }

    @Test
    fun aServiceIsAllowedOnlyOnceAllItsHostsAre() {
        var states: Map<String, Int>? = mapOf(
            "open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_SELECTED,
            "spotify.link" to DomainVerificationUserState.DOMAIN_STATE_NONE,
            "www.youtube.com" to DomainVerificationUserState.DOMAIN_STATE_NONE,
            "*.bandcamp.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        )
        FakeDomainVerification.install(app) { states }

        val unapproved = interception.unapprovedHosts()!!
        assertEquals(listOf("spotify.link", "www.spotify.link"), unapproved[MusicService.SPOTIFY])
        assertEquals(LinkInterception.HOSTS[MusicService.YOUTUBE], unapproved[MusicService.YOUTUBE])
        assertEquals(emptyList<String>(), unapproved[MusicService.BANDCAMP])

        states = null
        assertNull(interception.unapprovedHosts())
    }

    @Test
    fun approvalsAreUnknownWithoutTheSystemService() {
        // Robolectric, like some trimmed-down devices, has no domain verification service.
        assertNull(interception.unapprovedHosts())
    }

    @Test
    @Config(sdk = [30])
    fun approvalsAreUnknownBeforeAndroid12() {
        assertNull(interception.unapprovedHosts())
    }

    @Test
    fun installedAppsAreDetected() {
        assertTrue(interception.installedServices().isEmpty())
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.aspiro.tidal" })
        assertEquals(setOf(MusicService.TIDAL), interception.installedServices())
    }

    private fun linkApp(service: MusicService) = LinkApp(service.packageName, app.getString(service.labelRes))

    private fun installApp(service: MusicService) =
        shadowOf(app.packageManager).installPackage(installedApp(service.packageName, app.getString(service.labelRes)))

    @Test
    fun anInstalledAppThatVerifiedTheLinksBlocksThem() {
        installApp(MusicService.SPOTIFY)
        installApp(MusicService.TIDAL)
        FakeDomainVerification.installPerPackage(app) { packageName ->
            when (packageName) {
                MusicService.SPOTIFY.packageName -> mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
                // TIDAL's app has not verified anything.
                else -> mapOf("tidal.com" to DomainVerificationUserState.DOMAIN_STATE_NONE)
            }
        }

        val sources = setOf(MusicService.SPOTIFY, MusicService.TIDAL)
        assertEquals(setOf(linkApp(MusicService.SPOTIFY)), interception.blockingApps(sources))
        // A service Crosstune isn't intercepting is none of its business.
        assertEquals(emptySet<LinkApp>(), interception.blockingApps(setOf(MusicService.TIDAL)))
    }

    @Test
    fun anAppBlocksTheLinksOfAnotherServiceItAlsoClaims() {
        installApp(MusicService.YOUTUBE_MUSIC)
        FakeDomainVerification.installPerPackage(app) {
            mapOf("www.youtube.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }

        assertEquals(setOf(linkApp(MusicService.YOUTUBE_MUSIC)), interception.blockingApps(setOf(MusicService.YOUTUBE)))
    }

    @Test
    fun wildcardHostsAreMatchedBySuffix() {
        installApp(MusicService.SPOTIFY)
        FakeDomainVerification.installPerPackage(app) {
            mapOf("artist.bandcamp.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        assertEquals(setOf(linkApp(MusicService.SPOTIFY)), interception.blockingApps(setOf(MusicService.BANDCAMP)))

        FakeDomainVerification.installPerPackage(app) {
            mapOf("*.bandcamp.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        assertEquals(setOf(linkApp(MusicService.SPOTIFY)), interception.blockingApps(setOf(MusicService.BANDCAMP)))
        assertEquals(emptySet<LinkApp>(), interception.blockingApps(setOf(MusicService.DEEZER)))
    }

    @Test
    fun anAppStopsBlockingOnceItsLinkHandlingIsTurnedOff() {
        installApp(MusicService.SPOTIFY)
        var allowed = true
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { allowed }) {
            mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        assertEquals(setOf(linkApp(MusicService.SPOTIFY)), interception.blockingApps(setOf(MusicService.SPOTIFY)))

        allowed = false
        assertEquals(emptySet<LinkApp>(), interception.blockingApps(setOf(MusicService.SPOTIFY)))
    }

    @Test
    fun anAppWithLinkHandlingOffStillClaimsTheLinksButDoesNotBlockThem() {
        installApp(MusicService.SPOTIFY)
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { false }) {
            mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        assertEquals(mapOf(linkApp(MusicService.SPOTIFY) to false), interception.claimingApps(setOf(MusicService.SPOTIFY)))
        assertEquals(emptyMap<LinkApp, Boolean>(), interception.claimingApps(setOf(MusicService.TIDAL)))
    }

    @Test
    fun blockingAppsAreUnknownWithoutTheSystemService() {
        installApp(MusicService.SPOTIFY)
        assertNull(interception.blockingApps(setOf(MusicService.SPOTIFY)))
    }

    @Test
    @Config(sdk = [30])
    fun blockingAppsAreUnknownBeforeAndroid12() {
        assertNull(interception.blockingApps(setOf(MusicService.SPOTIFY)))
    }

    @Test
    fun anAppTheSystemDoesNotShowIsNotBlocking() {
        installApp(MusicService.SPOTIFY)
        FakeDomainVerification.installPerPackage(app) { null }
        assertEquals(emptySet<LinkApp>(), interception.blockingApps(setOf(MusicService.SPOTIFY)))
    }
}
