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

/** An installed app, by package and the name the user knows it by. */
internal data class LinkApp(val packageName: String, val label: String)

/**
 * What [LinkInterception.linkState] read from Android. Which sources Crosstune opens can change
 * any moment, so what concerns them is worked out from this when needed, without asking again.
 */
internal data class LinkState(
    /** The installed services' apps, so setup can suggest them first. */
    val serviceApps: Map<MusicService, LinkApp>,
    /**
     * The hosts of each source the user hasn't allowed Crosstune to open yet, empty once all are,
     * or null before Android 12, which has no way to ask.
     */
    val unapprovedHosts: Map<MusicService, List<String>>?,
    /** Like [unapprovedHosts], for each web frontend's sites. */
    val unapprovedFrontendHosts: Map<Frontend, List<String>>?,
    /** For every source, by [Destination.key], installed apps that claim its links and whether each still opens them; null before Android 12. */
    val claimingAppsBySource: Map<String, Map<LinkApp, Boolean>>?
) {
    /**
     * Installed apps that claim links Crosstune is set to intercept, each with whether it still
     * opens them, i.e. hasn't had "Open supported links" turned off. Not just the services' own
     * apps: e.g. YouTube Create also claims YouTube's links. Null before Android 12, which can't say.
     */
    fun claimingApps(sources: Set<MusicService>, frontends: Set<Frontend> = emptySet()): Map<LinkApp, Boolean>? {
        val bySource = claimingAppsBySource ?: return null
        val keys = sources.map { it.name } + frontends.map { Destination.FRONTEND_PREFIX + it.name }
        return keys.flatMap { bySource[it].orEmpty().toList() }.distinct().sortedBy { it.first.label.lowercase() }.toMap()
    }

    /**
     * Installed apps that still open links Crosstune is set to intercept. A domain an app has
     * verified goes to that app before any app the user allows, so Crosstune only gets the link
     * once "Open supported links" is turned off in that app's own settings. Null before Android 12,
     * which can't say.
     */
    fun blockingApps(sources: Set<MusicService>, frontends: Set<Frontend> = emptySet()): Set<LinkApp>? =
        claimingApps(sources, frontends)?.filterValues { it }?.keys

    /** Installed apps whose services' links Crosstune opens, for when Android can't say which really take them. */
    fun installedSourceApps(sources: Set<MusicService>): List<LinkApp> =
        MusicService.entries.filter { it in sources }.mapNotNull(serviceApps::get)
}

/**
 * Turns interception of each source service's links on or off, and reports what Android allows.
 * Each service has its own activity-alias in the manifest, all disabled until the user picks
 * services during setup. Android's "Open by default" settings still list every alias's domains,
 * enabled or not, so setup names the ones to select for each service.
 */
internal class LinkInterception(private val context: Context) {

    fun isEnabled(service: MusicService): Boolean = isEnabled(component(service.name))

    fun setEnabled(service: MusicService, enabled: Boolean) = setEnabled(component(service.name), enabled)

    /** Whether Crosstune opens tapped links from [frontend]'s popular sites, which have their own alias. */
    fun isEnabled(frontend: Frontend): Boolean = isEnabled(component(frontend.name))

    fun setEnabled(frontend: Frontend, enabled: Boolean) = setEnabled(component(frontend.name), enabled)

    private fun isEnabled(component: ComponentName): Boolean =
        context.packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun setEnabled(component: ComponentName, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
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
     * Everything Android says about apps and links, read in one go. It takes many slow calls to
     * the system, so it's read away from the main thread and each call is made only once.
     */
    fun linkState(): LinkState {
        val installed = installedServices()
        val serviceApps = installed.associateWith { appFor(it.packageName) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return LinkState(serviceApps, null, null, null)
        val own = ownHostStates()
        return LinkState(
            serviceApps,
            unapprovedHosts = own?.let(::unapprovedFrom),
            unapprovedFrontendHosts = own?.let(::unapprovedFrontendsFrom),
            claimingAppsBySource = claimingAppsBySource(installed)
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun ownHostStates(): Map<String, Int>? {
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return null
        return try {
            manager.getDomainVerificationUserState(context.packageName)?.hostToStateMap
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * For every source on its own, whether Crosstune opens its links or not, the installed apps that
     * claim them, each with whether it still opens them, keyed like [Destination.key]: a service's
     * name, or "frontend:" and a web frontend's. Android is asked about the apps once, for every
     * source's hosts together. Null when Android can't say.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun claimingAppsBySource(installed: Set<MusicService>): Map<String, Map<LinkApp, Boolean>>? {
        val hosts = HOSTS.mapKeys { it.key.name } + Frontend.SOURCES.associate { Destination.FRONTEND_PREFIX + it.name to it.sites }
        val states = appStates(installed, hosts.values.flatten()) ?: return null
        return hosts.mapValues { (_, wanted) -> claims(states, wanted) }
    }

    /** Each installed app that may claim any of [hosts], with what Android says about its links. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun appStates(installed: Set<MusicService>, hosts: List<String>): List<Pair<LinkApp, DomainVerificationUserState>>? {
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return null
        val candidates = (installed.map { it.packageName } + installedOtherApps() + appsOpening(hosts)).distinct()
        return candidates.mapNotNull { packageName ->
            val state = try {
                manager.getDomainVerificationUserState(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } ?: return@mapNotNull null
            appFor(packageName) to state
        }.sortedBy { it.first.label.lowercase() }
    }

    /** The apps among [states] that verified any of [wanted], each with whether it still opens them. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun claims(states: List<Pair<LinkApp, DomainVerificationUserState>>, wanted: List<String>): Map<LinkApp, Boolean> =
        states.filter { (_, state) ->
            state.hostToStateMap.any { (host, hostState) ->
                hostState != DomainVerificationUserState.DOMAIN_STATE_NONE && wanted.any { covers(it, host) }
            }
        }.associate { (app, state) -> app to state.isLinkHandlingAllowed }

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

    private fun component(alias: String) = ComponentName(context.packageName, "$ALIAS_PREFIX$alias")

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
            HOSTS.mapValues { (_, hosts) -> hosts.filter { notAllowed(hostStates, it) } }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun unapprovedFrontendsFrom(hostStates: Map<String, Int>): Map<Frontend, List<String>> =
            Frontend.SOURCES.associateWith { frontend -> frontend.sites.filter { notAllowed(hostStates, it) } }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun notAllowed(hostStates: Map<String, Int>, host: String): Boolean =
            (hostStates[host] ?: DomainVerificationUserState.DOMAIN_STATE_NONE) == DomainVerificationUserState.DOMAIN_STATE_NONE
    }
}
