package com.astrovm.crosstune

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Drops the tracking parameters music services add to their links, like Spotify's "si", Apple's
 * "uo" or Bandcamp's "search_*", and keeps the ones that pick the item, such as YouTube's "v".
 * Only links to a supported service are touched, so a custom site's own search URL stays as is.
 */
internal object TrackingLinks {
    private val trackingNames = setOf("si", "uo", "feature", "pp", "from", "ref", "ref_src", "fbclid", "gclid", "igshid")
    private val trackingPrefixes = listOf("utm_", "search_")
    /** Bandcamp's search tracking starts with "search_", but YouTube's search itself is "search_query". */
    private val kept = setOf("search_query")

    fun clean(url: String): String {
        if (MusicLinks.serviceFor(url) == null) return url
        val parsed = url.trim().toHttpUrlOrNull() ?: return url
        val tracking = parsed.queryParameterNames.filter(::isTracking)
        if (tracking.isEmpty()) return url
        return parsed.newBuilder().apply { tracking.forEach(::removeAllQueryParameters) }.build().toString()
    }

    private fun isTracking(name: String): Boolean {
        val lower = name.lowercase()
        return lower !in kept && (lower in trackingNames || trackingPrefixes.any(lower::startsWith))
    }
}
