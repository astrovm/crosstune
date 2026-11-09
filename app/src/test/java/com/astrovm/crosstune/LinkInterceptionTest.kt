package com.astrovm.crosstune

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
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
            MusicService.TIDAL to "https://tidal.com/browse/track/134858527"
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
    fun soundCloudBandcampAndAudiomackLinksAreOnlySharedOrPasted() {
        // Much of what's on them is on no other service (#372), so tapped, their links stay in their own apps.
        val links = mapOf(
            MusicService.SOUNDCLOUD to "https://soundcloud.com/theweeknd/blinding-lights",
            MusicService.BANDCAMP to "https://artist.bandcamp.com/album/record",
            MusicService.AUDIOMACK to "https://audiomack.com/artist/song/title"
        )
        MusicService.entries.filter { it.opensTappedLinks }.forEach { interception.setEnabled(it, true) }
        for ((service, link) in links) {
            assertTrue(service.name, service.canBeSource)
            assertFalse(service.name, service.opensTappedLinks)
            assertFalse(service.name, interception.isEnabled(service))
            assertFalse(link, handledByCrosstune(link))
        }
    }

    @Test
    fun hostsMatchEachAliasInTheManifest() {
        MusicService.entries.filter { it.opensTappedLinks }.forEach { service ->
            val component = ComponentName(app, "${LinkInterception.ALIAS_PREFIX}${service.name}")
            val declared = shadowOf(app.packageManager).getIntentFiltersForActivity(component)
                .flatMap { filter -> (0 until filter.countDataAuthorities()).map { filter.getDataAuthority(it).host } }
            assertEquals(service.name, declared.sorted(), LinkInterception.HOSTS[service].orEmpty().sorted())
        }
    }

    @Test
    fun invidiousAndPipedSitesArentLinksToAllow() {
        // Each declared site is a box to tick in Android's settings, so frontends' aren't declared.
        MusicService.entries.filter { it.opensTappedLinks }.forEach { interception.setEnabled(it, true) }
        assertFalse(handledByCrosstune("https://yewtu.be/watch?v=4NRXx6U8ABQ"))
        assertFalse(handledByCrosstune("https://piped.video/shorts/4NRXx6U8ABQ"))
        val declared = MusicService.entries.filter { it.opensTappedLinks }.flatMap { service ->
            shadowOf(app.packageManager).getIntentFiltersForActivity(ComponentName(app, "${LinkInterception.ALIAS_PREFIX}${service.name}"))
                .flatMap { filter -> (0 until filter.countDataAuthorities()).map { filter.getDataAuthority(it).host } }
        }
        assertEquals(LinkInterception.HOSTS.values.flatten().sorted(), declared.sorted())
        assertEquals(18, declared.size)
    }

    @Test
    fun aServiceIsAllowedOnlyOnceAllItsHostsAre() {
        var states: Map<String, Int>? = mapOf(
            "open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_SELECTED,
            "spotify.link" to DomainVerificationUserState.DOMAIN_STATE_NONE,
            "www.youtube.com" to DomainVerificationUserState.DOMAIN_STATE_NONE,
            "tidal.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED,
            "www.tidal.com" to DomainVerificationUserState.DOMAIN_STATE_SELECTED,
            "listen.tidal.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        )
        FakeDomainVerification.install(app) { states }

        val unapproved = interception.linkState().unapprovedHosts!!
        assertEquals(listOf("spotify.link", "www.spotify.link"), unapproved[MusicService.SPOTIFY])
        assertEquals(LinkInterception.HOSTS[MusicService.YOUTUBE], unapproved[MusicService.YOUTUBE])
        assertEquals(emptyList<String>(), unapproved[MusicService.TIDAL])

        states = null
        assertNull(interception.linkState().unapprovedHosts)
    }

    @Test
    fun approvalsAreUnknownWithoutTheSystemService() {
        // Robolectric, like some trimmed-down devices, has no domain verification service.
        assertNull(interception.linkState().unapprovedHosts)
    }

    @Test
    @Config(sdk = [30])
    fun approvalsAreUnknownBeforeAndroid12() {
        assertNull(interception.linkState().unapprovedHosts)
    }

    @Test
    fun installedAppsAreDetected() {
        assertTrue(interception.installedServices().isEmpty())
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.aspiro.tidal" })
        assertEquals(setOf(MusicService.TIDAL), interception.installedServices())
        // A build patched from an app, under a name of its own, is that app.
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "app.revanced.android.apps.youtube.music" })
        assertEquals(setOf(MusicService.TIDAL, MusicService.YOUTUBE_MUSIC), interception.installedServices())
        assertEquals("app.revanced.android.apps.youtube.music", interception.linkState().serviceApps[MusicService.YOUTUBE_MUSIC]?.packageName)
    }

    @Test
    fun aBuildPatchedUnderANameNobodyKnowsIsFoundByTheLinksItOpens() {
        val fork = "org.example.android.apps.youtube.music"
        val plainYouTube = "org.example.android.youtube"
        shadowOf(app).checkActivities(true)
        listOf(fork, plainYouTube).forEach { name ->
            shadowOf(app.packageManager).installPackage(installedApp(name, name))
            val opens = ComponentName(name, "$name.UrlActivity")
            shadowOf(app.packageManager).addActivityIfNotPresent(opens)
            shadowOf(app.packageManager).addIntentFilterForActivity(
                opens, IntentFilter(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addDataScheme("https")
                    addDataAuthority("music.youtube.com", null)
                    addDataAuthority("www.youtube.com", null)
                    addDataAuthority("youtu.be", null)
                }
            )
        }
        // An app of some other service that opens no such link is not mistaken for one.
        shadowOf(app.packageManager).installPackage(installedApp("org.example.player", "Player"))
        assertEquals(setOf(MusicService.YOUTUBE_MUSIC, MusicService.YOUTUBE), interception.installedServices())
        val state = interception.linkState()
        assertEquals(fork, state.serviceApps[MusicService.YOUTUBE_MUSIC]?.packageName)
        assertEquals(plainYouTube, state.serviceApps[MusicService.YOUTUBE]?.packageName)
        assertEquals(listOf(fork, plainYouTube), state.apps)
    }

    @Test
    fun everyBuildOfAServiceIsListedAsAMusicAppAndNoneTwice() {
        shadowOf(app.packageManager).installPackage(installedApp("com.spotify.music", "Spotify"))
        shadowOf(app.packageManager).installPackage(installedApp("app.morphe.android.apps.youtube.music", "Morphe"))
        shadowOf(app.packageManager).installPackage(installedApp("com.google.android.apps.youtube.music", "YouTube Music"))
        assertEquals(
            setOf("com.spotify.music", "app.morphe.android.apps.youtube.music", "com.google.android.apps.youtube.music"),
            interception.linkState().apps.toSet()
        )
        assertEquals(3, interception.linkState().apps.size)
        assertTrue(LinkState(emptyMap(), null, null).apps.isEmpty())
    }

    @Test
    fun aPackageNamedAsPatchersNameThemIsTheServiceItIsPatchedFrom() {
        assertEquals(MusicService.YOUTUBE_MUSIC, MusicService.ofPackage("app.morphe.android.apps.youtube.music"))
        assertEquals(MusicService.YOUTUBE_MUSIC, MusicService.ofPackage("org.example.android.apps.youtube.music"))
        assertEquals(MusicService.YOUTUBE, MusicService.ofPackage("app.morphe.android.youtube"))
        assertEquals(MusicService.YOUTUBE, MusicService.ofPackage("org.example.android.youtube"))
        assertEquals(MusicService.SPOTIFY, MusicService.ofPackage("com.spotify.music"))
        // Neither another app of YouTube's, nor no app, is one.
        assertNull(MusicService.ofPackage("com.google.android.apps.youtube.kids"))
        assertNull(MusicService.ofPackage("org.example.player"))
        assertNull(MusicService.ofPackage(null))
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
        assertEquals(setOf(linkApp(MusicService.SPOTIFY)), interception.linkState().blockingApps(sources))
        // A service Crosstune isn't intercepting is none of its business.
        assertEquals(emptySet<LinkApp>(), interception.linkState().blockingApps(setOf(MusicService.TIDAL)))
    }

    @Test
    fun anAppBlocksTheLinksOfAnotherServiceItAlsoClaims() {
        installApp(MusicService.YOUTUBE_MUSIC)
        FakeDomainVerification.installPerPackage(app) {
            mapOf("www.youtube.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }

        assertEquals(setOf(linkApp(MusicService.YOUTUBE_MUSIC)), interception.linkState().blockingApps(setOf(MusicService.YOUTUBE)))
    }

    @Test
    fun wildcardHostsAreMatchedBySuffix() {
        installApp(MusicService.SPOTIFY)
        FakeDomainVerification.installPerPackage(app) {
            mapOf("*.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        assertEquals(setOf(linkApp(MusicService.SPOTIFY)), interception.linkState().blockingApps(setOf(MusicService.SPOTIFY)))
        assertEquals(emptySet<LinkApp>(), interception.linkState().blockingApps(setOf(MusicService.DEEZER)))

        // Only what ends in it: the wildcard's own site, spotify.com, isn't one of Crosstune's.
        FakeDomainVerification.installPerPackage(app) {
            mapOf("*.open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        assertEquals(emptySet<LinkApp>(), interception.linkState().blockingApps(setOf(MusicService.SPOTIFY)))
    }

    @Test
    fun anAppStopsBlockingOnceItsLinkHandlingIsTurnedOff() {
        installApp(MusicService.SPOTIFY)
        var allowed = true
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { allowed }) {
            mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        assertEquals(setOf(linkApp(MusicService.SPOTIFY)), interception.linkState().blockingApps(setOf(MusicService.SPOTIFY)))

        allowed = false
        assertEquals(emptySet<LinkApp>(), interception.linkState().blockingApps(setOf(MusicService.SPOTIFY)))
    }

    @Test
    fun anAppWithLinkHandlingOffStillClaimsTheLinksButDoesNotBlockThem() {
        installApp(MusicService.SPOTIFY)
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { false }) {
            mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        assertEquals(mapOf(linkApp(MusicService.SPOTIFY) to false), interception.linkState().claimingApps(setOf(MusicService.SPOTIFY)))
        assertEquals(emptyMap<LinkApp, Boolean>(), interception.linkState().claimingApps(setOf(MusicService.TIDAL)))
    }

    @Test
    fun knownAppsThatKeepAServicesLinksAreFoundEvenWithoutAMatchingLinkFilter() {
        // YouTube Create verifies youtube.com but only declares some of its paths.
        val create = "com.google.android.apps.youtube.producer"
        shadowOf(app.packageManager).installPackage(installedApp(create, "YouTube Create"))
        FakeDomainVerification.installPerPackage(app) { packageName ->
            if (packageName == create) mapOf("youtube.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED) else null
        }
        assertEquals(setOf(LinkApp(create, "YouTube Create")), interception.linkState().blockingApps(setOf(MusicService.YOUTUBE)))
    }

    @Test
    fun blockingAppsAreUnknownWithoutTheSystemService() {
        installApp(MusicService.SPOTIFY)
        assertNull(interception.linkState().blockingApps(setOf(MusicService.SPOTIFY)))
    }

    @Test
    @Config(sdk = [30])
    fun blockingAppsAreUnknownBeforeAndroid12() {
        assertNull(interception.linkState().blockingApps(setOf(MusicService.SPOTIFY)))
    }

    @Test
    fun anAppTheSystemDoesNotShowIsNotBlocking() {
        installApp(MusicService.SPOTIFY)
        FakeDomainVerification.installPerPackage(app) { null }
        assertEquals(emptySet<LinkApp>(), interception.linkState().blockingApps(setOf(MusicService.SPOTIFY)))
    }

    @Test
    fun oneLookAtAndroidCoversEverySource() {
        installApp(MusicService.SPOTIFY)
        installApp(MusicService.TIDAL)
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { it == MusicService.SPOTIFY.packageName }) { packageName ->
            when (packageName) {
                MusicService.SPOTIFY.packageName -> mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
                MusicService.TIDAL.packageName -> mapOf("tidal.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
                else -> emptyMap()
            }
        }
        val state = interception.linkState()

        assertEquals(mapOf(MusicService.SPOTIFY to linkApp(MusicService.SPOTIFY), MusicService.TIDAL to linkApp(MusicService.TIDAL)), state.serviceApps)
        // An app that claims several sources' links is one app, listed once.
        assertEquals(
            listOf(linkApp(MusicService.SPOTIFY) to true, linkApp(MusicService.TIDAL) to false),
            state.claimingApps(setOf(MusicService.TIDAL, MusicService.SPOTIFY))!!.toList()
        )
        // What's worked out for some sources doesn't need Android again.
        assertEquals(setOf(linkApp(MusicService.SPOTIFY)), state.blockingApps(setOf(MusicService.SPOTIFY)))
        assertEquals(emptySet<LinkApp>(), state.blockingApps(setOf(MusicService.TIDAL)))
        assertEquals(listOf(linkApp(MusicService.SPOTIFY)), state.installedSourceApps(setOf(MusicService.SPOTIFY, MusicService.DEEZER)))
    }

    @Test
    fun aSourceNoAppClaimsLinksForHasNoClaimingApps() {
        installApp(MusicService.SPOTIFY)
        FakeDomainVerification.installPerPackage(app) {
            mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        // SoundCloud's links are never intercepted when tapped, so no host of its is looked at.
        assertEquals(emptyMap<LinkApp, Boolean>(), interception.linkState().claimingApps(setOf(MusicService.SOUNDCLOUD)))
    }

    @Test
    fun anAppUninstalledWhileLookingIsNamedByItsPackage() {
        val gone = "gone.player"
        val found = ComponentName(gone, "Open")
        shadowOf(app.packageManager).addActivityIfNotPresent(found)
        shadowOf(app.packageManager).addIntentFilterForActivity(found, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
            addDataAuthority("open.spotify.com", null)
        })
        // Gone by the time it's named: Android no longer knows its app.
        shadowOf(app.packageManager).getInternalMutablePackageInfo(gone).applicationInfo = null
        FakeDomainVerification.installPerPackage(app) { packageName ->
            if (packageName == gone) mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED) else null
        }
        assertEquals(mapOf(LinkApp(gone, gone) to true), interception.linkState().claimingApps(setOf(MusicService.SPOTIFY)))
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12OnlyTheInstalledAppsAreKnown() {
        installApp(MusicService.SPOTIFY)
        val state = interception.linkState()
        assertEquals(listOf(linkApp(MusicService.SPOTIFY)), state.installedSourceApps(setOf(MusicService.SPOTIFY)))
        assertNull(state.unapprovedHosts)
        assertNull(state.claimingApps(setOf(MusicService.SPOTIFY)))
    }
}
