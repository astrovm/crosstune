package com.astrovm.crosstune

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Turns interception of each source service's links on or off. Each service has its own
 * activity-alias in the manifest, so disabled services' links never reach Crosstune and Android's
 * "Open by default" settings only list the domains the user chose.
 */
internal class LinkInterception(private val context: Context) {

    fun isEnabled(service: MusicService): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component(service))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> service in ENABLED_BY_DEFAULT
            else -> false
        }

    fun setEnabled(service: MusicService, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(component(service), state, PackageManager.DONT_KILL_APP)
    }

    private fun component(service: MusicService) =
        ComponentName(context.packageName, "$ALIAS_PREFIX${service.name}")

    companion object {
        const val ALIAS_PREFIX = "com.astrovm.crosstune.intercept."

        /** Matches the manifest: Crosstune has always handled Spotify links. */
        private val ENABLED_BY_DEFAULT = setOf(MusicService.SPOTIFY)
    }
}
