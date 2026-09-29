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
 * services during setup, so Android's "Open by default" settings only list the domains they chose.
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
     * Services whose links the user has allowed Crosstune to open, or null before Android 12,
     * which has no way to ask.
     */
    fun approvedServices(): Set<MusicService>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return null
        val state = try {
            manager.getDomainVerificationUserState(context.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return null
        return approvedFrom(state.hostToStateMap)
    }

    private fun component(service: MusicService) =
        ComponentName(context.packageName, "$ALIAS_PREFIX${service.name}")

    companion object {
        const val ALIAS_PREFIX = "com.astrovm.crosstune.intercept."

        /** The main web host of each source; a service counts as allowed once this one is. */
        private val PRIMARY_HOSTS = mapOf(
            MusicService.SPOTIFY to "open.spotify.com",
            MusicService.YOUTUBE_MUSIC to "music.youtube.com",
            MusicService.YOUTUBE to "www.youtube.com",
            MusicService.APPLE_MUSIC to "music.apple.com",
            MusicService.DEEZER to "www.deezer.com",
            MusicService.TIDAL to "tidal.com",
            MusicService.SOUNDCLOUD to "soundcloud.com",
            MusicService.BANDCAMP to "*.bandcamp.com"
        )

        @RequiresApi(Build.VERSION_CODES.S)
        private fun approvedFrom(hostStates: Map<String, Int>): Set<MusicService> = PRIMARY_HOSTS
            .filterValues { host ->
                (hostStates[host] ?: DomainVerificationUserState.DOMAIN_STATE_NONE) !=
                    DomainVerificationUserState.DOMAIN_STATE_NONE
            }
            .keys
    }
}
