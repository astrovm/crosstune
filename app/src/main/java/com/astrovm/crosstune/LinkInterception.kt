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
