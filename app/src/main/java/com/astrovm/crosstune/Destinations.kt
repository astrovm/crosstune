package com.astrovm.crosstune

import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Where Crosstune can send an item: a built-in service, an alternative frontend for one, or a
 * user-defined URL template.
 */
internal sealed class Destination {
    abstract val key: String

    abstract fun searchUrl(query: String): String

    /** The service whose links this destination opens, so its exact match can be looked up. */
    open val matchService: MusicService? get() = null

    /** The app to open links in, or null to let Android pick, e.g. a browser. */
    open val packageName: String? get() = null

    /** Turns a link to [matchService] into one this destination opens. */
    open fun adapt(url: String): String = url

    /**
     * [service]'s own app, which stands in for a build patched from it when it isn't installed, or, with
     * [build], that build's package alone, e.g. YouTube Music by Morphe, which has an app of its own to open.
     */
    data class Service(val service: MusicService, val build: String? = null) : Destination() {
        override val key: String get() = if (build == null) service.name else "${service.name}$BUILD_SEPARATOR$build"
        override fun searchUrl(query: String) = service.searchUrl(query)
        override val matchService: MusicService get() = service
        override val packageName: String get() = build ?: service.packageName

        /** Starting a song here can take over the screen or leave it silent. A TV can cover its YouTube player. */
        fun opensAppToStartSong(phone: Phone): Boolean = when (service) {
            MusicService.APPLE_MUSIC, MusicService.DEEZER, MusicService.AUDIOMACK -> true
            MusicService.YOUTUBE_MUSIC, MusicService.YOUTUBE -> build == null && phone != Phone.TV
            else -> false
        }
    }

    /** [instance] is the site a web frontend opens on, e.g. "https://yewtu.be"; null for apps. */
    data class Alternative(val frontend: Frontend, val instance: String? = null) : Destination() {
        override val key: String get() = FRONTEND_PREFIX + frontend.name
        override fun searchUrl(query: String) = adapt(frontend.via.searchUrl(query))
        override val matchService: MusicService get() = frontend.via
        override val packageName: String? get() = frontend.packageName
        override fun adapt(url: String): String = instance?.let { frontend.onSite(url, it) } ?: url
    }

    /** [template] is any URL containing [QUERY_PLACEHOLDER], e.g. "https://example.com/search?q={query}". */
    data class Custom(val id: String, val name: String, val template: String) : Destination() {
        override val key: String get() = CUSTOM_PREFIX + id
        override fun searchUrl(query: String) = template.replace(QUERY_PLACEHOLDER, Uri.encode(query))
    }

    companion object {
        const val QUERY_PLACEHOLDER = "{query}"
        const val CUSTOM_PREFIX = "custom:"
        const val FRONTEND_PREFIX = "frontend:"
        const val BUILD_SEPARATOR = "@"

        /** A template needs the placeholder and a URI scheme so Android can route it to an app or browser. */
        fun isValidTemplate(template: String): Boolean {
            val scheme = template.trim().toUri().scheme
            return QUERY_PLACEHOLDER in template && !scheme.isNullOrBlank()
        }
    }
}

/**
 * Persists the default destination, per-source rules, custom destinations and the sites web
 * frontends open on. Frontend apps and patched builds are offered only while [isInstalled], and
 * builds Crosstune has no name for while [found] by the links they open; see [LinkState.apps].
 */
internal class DestinationStore(
    private val preferences: SharedPreferences,
    private val found: () -> List<String> = { emptyList() },
    private val isInstalled: (String) -> Boolean = { true }
) {

    fun customDestinations(): List<Destination.Custom> {
        val stored = preferences.getString(KEY_CUSTOM, null) ?: return emptyList()
        val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
        return array.objects().mapNotNull { item ->
            val id = item.optString("id").ifEmpty { return@mapNotNull null }
            val name = item.optString("name").ifEmpty { return@mapNotNull null }
            val template = item.optString("template").takeIf(Destination::isValidTemplate) ?: return@mapNotNull null
            Destination.Custom(id, name, template)
        }
    }

    fun allDestinations(): List<Destination> = services() + frontends() + customDestinations()

    /**
     * Each service, then the builds patched from it that are installed, each an app of its own: those Crosstune
     * knows by name, then those [found] by the links they open. Without its own app, a service opens in such a
     * build, so that build alone stands for it, e.g. YouTube's TV app on a TV, rather than two rows that open the
     * same app.
     */
    private fun services(): List<Destination.Service> {
        val found = found()
        return MusicService.entries.flatMap { service ->
            // Asked again here, as what was found may be from before it was uninstalled.
            val unnamed = found.filter { it !in service.packageNames && MusicService.ofPackage(it) == service && isInstalled(it) }
            val builds = (service.builds.filter(isInstalled) + unnamed).map { Destination.Service(service, it) }
            if (builds.isNotEmpty() && !isInstalled(service.packageName)) builds else listOf(Destination.Service(service)) + builds
        }
    }

    private fun frontends(): List<Destination.Alternative> {
        // Some apps, like NewPipe, are frontends for several services; Android is asked about each once.
        val installed = mutableMapOf<String, Boolean>()
        return Frontend.entries.mapNotNull { frontend ->
            val packageName = frontend.packageName
            when {
                packageName == null -> Destination.Alternative(frontend, instance(frontend))
                installed.getOrPut(packageName) { isInstalled(packageName) } -> Destination.Alternative(frontend)
                else -> null
            }
        }
    }

    /** The site a web frontend opens on: the user's choice, or a public one to start with. */
    fun instance(frontend: Frontend): String? =
        preferences.getString(instanceKey(frontend), null) ?: frontend.defaultInstance

    /** Saves [address] as the site [frontend] opens on; false if it isn't a web address. */
    fun setInstance(frontend: Frontend, address: String): Boolean {
        val instance = Frontend.instanceOf(address) ?: return false
        preferences.edit { putString(instanceKey(frontend), instance) }
        return true
    }

    private fun instanceKey(frontend: Frontend) = "frontend_instance_${frontend.name}"

    fun addCustom(name: String, template: String): Destination.Custom {
        val custom = Destination.Custom(UUID.randomUUID().toString(), name.trim(), template.trim())
        saveCustom(customDestinations() + custom)
        return custom
    }

    /** Removing a custom destination also drops it wherever it was chosen. */
    fun removeCustom(custom: Destination.Custom) {
        saveCustom(customDestinations().filterNot { it.id == custom.id })
        preferences.edit {
            if (preferences.getString(KEY_DEFAULT, null) == custom.key) remove(KEY_DEFAULT)
            MusicService.entries.forEach { service ->
                if (preferences.getString(ruleKey(service), null) == custom.key) remove(ruleKey(service))
            }
            Frontend.SOURCES.forEach { frontend ->
                if (preferences.getString(frontendRuleKey(frontend), null) == custom.key) remove(frontendRuleKey(frontend))
            }
        }
    }

    /**
     * Each lookup takes [all] destinations, asking Android which frontend apps are installed unless
     * given, so reading every choice at once can share one list.
     */
    fun defaultDestination(all: List<Destination> = allDestinations()): Destination =
        find(preferences.getString(KEY_DEFAULT, null), all) ?: find(MusicService.YOUTUBE_MUSIC.name, all) ?: Destination.Service(MusicService.YOUTUBE_MUSIC)

    /**
     * With none picked, the first music app on the phone, YouTube, mostly videos, last. Android is asked here, which
     * is quick, rather than after its slow look at links, so it's the same however soon after starting it's asked.
     */
    fun offered(all: List<Destination> = allDestinations()): Destination.Service? {
        val onPhone = all.filterIsInstance<Destination.Service>().filter { it.build != null || it.service.appPackages.any(isInstalled) }
        return onPhone.firstOrNull { it.service != MusicService.YOUTUBE } ?: onPhone.firstOrNull()
    }

    /** False until the user picks a default, e.g. during first-run setup. */
    fun hasDefault(all: List<Destination> = allDestinations()): Boolean = find(preferences.getString(KEY_DEFAULT, null), all) != null

    fun setDefault(destination: Destination) = choose(KEY_DEFAULT, destination)

    /** The destination chosen for links from [source], or null to use the default. */
    fun rule(source: MusicService, all: List<Destination> = allDestinations()): Destination? =
        find(preferences.getString(ruleKey(source), null), all)

    fun setRule(source: MusicService, destination: Destination?) = choose(ruleKey(source), destination)

    /**
     * Saves [destination] under [key], or forgets it when null. The build standing in for a service chosen
     * before, chosen again, keeps the service's key, so its own app takes over once installed.
     */
    private fun choose(key: String, destination: Destination?) {
        val saved = preferences.getString(key, null)
        if (destination is Destination.Service && saved == destination.service.name && find(saved, allDestinations()) == destination) return
        preferences.edit { if (destination == null) remove(key) else putString(key, destination.key) }
    }

    // A build that was chosen and is gone falls back to its service, and a service without its own app to the build that stands for it.
    private fun find(key: String?, all: List<Destination>): Destination? = key?.let { wanted ->
        val service = wanted.substringBefore(Destination.BUILD_SEPARATOR)
        all.firstOrNull { it.key == wanted } ?: all.firstOrNull { it.key == service }
            ?: all.firstOrNull { it is Destination.Service && it.service.name == service }
    }

    private fun saveCustom(list: List<Destination.Custom>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("template", it.template)) }
        preferences.edit { putString(KEY_CUSTOM, array.toString()) }
    }

    private fun ruleKey(source: MusicService) = "rule_${source.name}"

    /** The destination chosen for links from [frontend]'s sites, or null to use YouTube's. */
    fun rule(frontend: Frontend, all: List<Destination> = allDestinations()): Destination? =
        find(preferences.getString(frontendRuleKey(frontend), null), all)

    fun setRule(frontend: Frontend, destination: Destination?) = choose(frontendRuleKey(frontend), destination)

    private fun frontendRuleKey(frontend: Frontend) = "rule_frontend_${frontend.name}"

    private companion object {
        // Kept from when the default could only be a built-in service, so existing choices carry over.
        const val KEY_DEFAULT = "default_target"
        const val KEY_CUSTOM = "custom_destinations"
    }
}
