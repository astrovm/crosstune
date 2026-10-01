package com.astrovm.crosstune

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Turns interception of each source service's links on or off, and reports what Android allows.
 * Each service has its own activity-alias in the manifest, all disabled until the user picks
 * services during setup. Android's "Open by default" settings still list every alias's domains,
 * enabled or not, so setup names the ones to select for each service.
 */
internal class LinkInterception(private val context: Context) {

    fun isEnabled(service: MusicService): Boolean =
        context.packageManager.getComponentEnabledSetting(component(service)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(service: MusicService, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(component(service), state, PackageManager.DONT_KILL_APP)
    }

    /** Services whose app is installed, so setup can suggest them first. */
    fun installedServices(): Set<MusicService> = MusicService.entries.filter { service ->
        try {
            context.packageManager.getPackageInfo(service.packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }.toSet()

    /**
     * The hosts of each source the user hasn't allowed Crosstune to open yet, empty once all are,
     * or null before Android 12, which has no way to ask.
     */
    fun unapprovedHosts(): Map<MusicService, List<String>>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return null
        val state = try {
            manager.getDomainVerificationUserState(context.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return null
        return unapprovedFrom(state.hostToStateMap)
    }

    /**
     * Installed music apps that still open links Crosstune is set to intercept. A domain an app
     * has verified goes to that app before any app the user allows, so Crosstune only gets the
     * link once "Open supported links" is turned off in that app's own settings. Null before
     * Android 12, which can't say.
     */
    fun blockingApps(sources: Set<MusicService>): Set<MusicService>? =
        claimingApps(sources)?.filterValues { it }?.keys

    /**
     * Installed music apps that claim links Crosstune is set to intercept, each with whether it
     * still opens them, i.e. hasn't had "Open supported links" turned off. Null before Android 12,
     * which can't say.
     */
    fun claimingApps(sources: Set<MusicService>): Map<MusicService, Boolean>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return null
        val wanted = sources.flatMap { HOSTS[it].orEmpty() }
        return installedServices().mapNotNull { app ->
            val state = try {
                manager.getDomainVerificationUserState(app.packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } ?: return@mapNotNull null
            val claims = state.hostToStateMap.any { (host, hostState) ->
                hostState != DomainVerificationUserState.DOMAIN_STATE_NONE && wanted.any { covers(it, host) }
            }
            if (claims) app to state.isLinkHandlingAllowed else null
        }.toMap()
    }

    private fun component(service: MusicService) =
        ComponentName(context.packageName, "$ALIAS_PREFIX${service.name}")

    companion object {
        const val ALIAS_PREFIX = "com.astrovm.crosstune.intercept."

        /** Every web host each source's alias declares in the manifest; a service is allowed once all are. */
        val HOSTS = mapOf(
            MusicService.SPOTIFY to listOf("open.spotify.com", "spotify.link", "www.spotify.link"),
            MusicService.YOUTUBE_MUSIC to listOf("music.youtube.com"),
            MusicService.YOUTUBE to listOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"),
            MusicService.APPLE_MUSIC to listOf("music.apple.com", "geo.music.apple.com"),
            MusicService.DEEZER to listOf(
                "deezer.com", "www.deezer.com", "link.deezer.com", "deezer.page.link", "dzr.page.link"
            ),
            MusicService.TIDAL to listOf("tidal.com", "www.tidal.com", "listen.tidal.com"),
            MusicService.SOUNDCLOUD to listOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com", "on.soundcloud.com"),
            MusicService.BANDCAMP to listOf("*.bandcamp.com")
        )

        /** Whether two hosts, either of which may be a `*.` wildcard, can name the same site. */
        private fun covers(a: String, b: String): Boolean {
            fun suffix(host: String) = host.removePrefix("*")
            return when {
                a.startsWith("*.") -> b.endsWith(suffix(a))
                b.startsWith("*.") -> a.endsWith(suffix(b))
                else -> a == b
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun unapprovedFrom(hostStates: Map<String, Int>): Map<MusicService, List<String>> =
            HOSTS.mapValues { (_, hosts) ->
                hosts.filter { host ->
                    (hostStates[host] ?: DomainVerificationUserState.DOMAIN_STATE_NONE) ==
                        DomainVerificationUserState.DOMAIN_STATE_NONE
                }
            }
    }
}
