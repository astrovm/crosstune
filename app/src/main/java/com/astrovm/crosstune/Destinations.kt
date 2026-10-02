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

    data class Service(val service: MusicService) : Destination() {
        override val key: String get() = service.name
        override fun searchUrl(query: String) = service.searchUrl(query)
        override val matchService: MusicService get() = service
        override val packageName: String get() = service.packageName
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

        /** A template needs the placeholder and a URI scheme so Android can route it to an app or browser. */
        fun isValidTemplate(template: String): Boolean {
            val scheme = template.trim().toUri().scheme
            return QUERY_PLACEHOLDER in template && !scheme.isNullOrBlank()
        }
    }
}

/**
 * Persists the default destination, per-source rules, custom destinations and the sites web
 * frontends open on. Frontend apps are offered only while [isInstalled].
 */
internal class DestinationStore(
    private val preferences: SharedPreferences,
    private val isInstalled: (String) -> Boolean = { true }
) {

    fun customDestinations(): List<Destination.Custom> {
        val stored = preferences.getString(KEY_CUSTOM, null) ?: return emptyList()
        val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").ifEmpty { return@mapNotNull null }
            val name = item.optString("name").ifEmpty { return@mapNotNull null }
            val template = item.optString("template").takeIf(Destination::isValidTemplate) ?: return@mapNotNull null
            Destination.Custom(id, name, template)
        }
    }

    fun allDestinations(): List<Destination> =
        MusicService.entries.map(Destination::Service) + frontends() + customDestinations()

    private fun frontends(): List<Destination.Alternative> = Frontend.entries.mapNotNull { frontend ->
        val packageName = frontend.packageName
        when {
            packageName == null -> Destination.Alternative(frontend, instance(frontend))
            isInstalled(packageName) -> Destination.Alternative(frontend)
            else -> null
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

    fun defaultDestination(): Destination =
        find(preferences.getString(KEY_DEFAULT, null)) ?: Destination.Service(MusicService.YOUTUBE_MUSIC)

    /** False until the user picks a default, e.g. during first-run setup. */
    fun hasDefault(): Boolean = find(preferences.getString(KEY_DEFAULT, null)) != null

    fun setDefault(destination: Destination) {
        preferences.edit { putString(KEY_DEFAULT, destination.key) }
    }

    /** The destination chosen for links from [source], or null to use the default. */
    fun rule(source: MusicService): Destination? = find(preferences.getString(ruleKey(source), null))

    fun setRule(source: MusicService, destination: Destination?) {
        preferences.edit {
            if (destination == null) remove(ruleKey(source)) else putString(ruleKey(source), destination.key)
        }
    }

    private fun find(key: String?): Destination? = key?.let { wanted -> allDestinations().firstOrNull { it.key == wanted } }

    private fun saveCustom(list: List<Destination.Custom>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("template", it.template)) }
        preferences.edit { putString(KEY_CUSTOM, array.toString()) }
    }

    private fun ruleKey(source: MusicService) = "rule_${source.name}"

    /** The destination chosen for links from [frontend]'s sites, or null to use YouTube's. */
    fun rule(frontend: Frontend): Destination? = find(preferences.getString(frontendRuleKey(frontend), null))

    fun setRule(frontend: Frontend, destination: Destination?) {
        preferences.edit {
            if (destination == null) remove(frontendRuleKey(frontend)) else putString(frontendRuleKey(frontend), destination.key)
        }
    }

    private fun frontendRuleKey(frontend: Frontend) = "rule_frontend_${frontend.name}"

    private companion object {
        // Kept from when the default could only be a built-in service, so existing choices carry over.
        const val KEY_DEFAULT = "default_target"
        const val KEY_CUSTOM = "custom_destinations"
    }
}
