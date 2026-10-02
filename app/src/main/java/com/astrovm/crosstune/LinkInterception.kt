package com.astrovm.crosstune

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.net.toUri

/**
 * Turns interception of each source service's links on or off, and reports what Android allows.
 * Each service has its own activity-alias in the manifest, all disabled until the user picks
 * services during setup. Android's "Open by default" settings still list every alias's domains,
 * enabled or not, so setup names the ones to select for each service.
 */
/** An installed app, by package and the name the user knows it by. */
internal data class LinkApp(val packageName: String, val label: String)

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
     * Installed apps that still open links Crosstune is set to intercept. A domain an app has
     * verified goes to that app before any app the user allows, so Crosstune only gets the link
     * once "Open supported links" is turned off in that app's own settings. Null before Android 12,
     * which can't say.
     */
    fun blockingApps(sources: Set<MusicService>): Set<LinkApp>? =
        claimingApps(sources)?.filterValues { it }?.keys

    /**
     * Installed apps that claim links Crosstune is set to intercept, each with whether it still
     * opens them, i.e. hasn't had "Open supported links" turned off. Not just the services' own
     * apps: e.g. YouTube Create also claims YouTube's links. Null before Android 12, which can't say.
     */
    fun claimingApps(sources: Set<MusicService>): Map<LinkApp, Boolean>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return null
        val wanted = sources.flatMap { HOSTS[it].orEmpty() }
        val candidates = (installedServices().map { it.packageName } + installedOtherApps() + appsOpening(wanted)).distinct()
        return candidates.mapNotNull { packageName ->
            val state = try {
                manager.getDomainVerificationUserState(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } ?: return@mapNotNull null
            val claims = state.hostToStateMap.any { (host, hostState) ->
                hostState != DomainVerificationUserState.DOMAIN_STATE_NONE && wanted.any { covers(it, host) }
            }
            if (claims) appFor(packageName) to state.isLinkHandlingAllowed else null
        }.sortedBy { it.first.label.lowercase() }.toMap()
    }

    /** Installed apps whose services' links Crosstune opens, for when Android can't say which really take them. */
    fun installedSourceApps(sources: Set<MusicService>): List<LinkApp> =
        MusicService.entries.filter { it in sources && it in installedServices() }.map { appFor(it.packageName) }

    /** Apps in [OTHER_LINK_APPS] that are installed. */
    private fun installedOtherApps(): List<String> = OTHER_LINK_APPS.filter(::isInstalled)

    fun isInstalled(packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    /** Other apps with a link filter for any of [hosts]; whether they verified them is checked separately. */
    private fun appsOpening(hosts: List<String>): Set<String> = hosts.flatMap { host ->
        // A wildcard host's own apps are found through a made-up subdomain.
        val uri = "https://${host.replace("*.", "any.")}/".toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
    }.filter { it != context.packageName }.toSet()

    /** The app's name as the launcher shows it, or its package name if it's just been uninstalled. */
    private fun appFor(packageName: String): LinkApp {
        val pm = context.packageManager
        val label = runCatching { pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString() }.getOrDefault(packageName)
        return LinkApp(packageName, label)
    }

    private fun component(service: MusicService) =
        ComponentName(context.packageName, "$ALIAS_PREFIX${service.name}")

    companion object {
        const val ALIAS_PREFIX = "com.astrovm.crosstune.intercept."

        /**
         * Apps known to verify a service's links without being that service's app, which a search
         * by link can miss when they only list some paths. Keep in sync with the manifest's queries.
         */
        private val OTHER_LINK_APPS = listOf(
            "com.google.android.apps.youtube.producer", // YouTube Create
            "com.google.android.apps.youtube.creator", // YouTube Studio
            "com.google.android.apps.youtube.kids", // YouTube Kids
            "com.google.android.apps.youtube.unplugged" // YouTube TV
        )

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
