package com.astrovm.crosstune

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a pasted or shared piece of text points at on Spotify. */
internal sealed interface SpotifyInput {
    data class Track(val id: String) : SpotifyInput
    data class ShortLink(val url: String) : SpotifyInput
}

/** Parses the Spotify links, URIs and IDs people paste or share. Pure Kotlin, no Android APIs. */
internal object SpotifyLinks {
    private val idRegex = Regex("""^[A-Za-z0-9]{22}$""")
    private val urlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val trackUrlInPageRegex =
        Regex("""https://open\.spotify\.com/(?:intl-[A-Za-z-]+/)?track/([A-Za-z0-9]{22})""")

    fun parse(text: String): SpotifyInput? {
        val value = (extractFirstUrl(text) ?: text).trim()
        extractTrackId(value)?.let { return SpotifyInput.Track(it) }

        val url = value.toHttpUrlOrNull() ?: return null
        if (!url.host.isSpotifyShortLinkHost()) return null
        // Short links are always served over HTTPS; upgrading avoids a blocked cleartext request.
        return SpotifyInput.ShortLink(url.newBuilder().scheme("https").build().toString())
    }

    fun trackUrl(id: String): String = "https://open.spotify.com/track/$id"

    /** Reads the track ID from a URL a short link redirected to. */
    fun trackIdFromUrl(url: HttpUrl): String? = extractTrackId(url.toString())

    /** Short-link landing pages sometimes redirect with JavaScript; the target URL is still in the HTML. */
    fun trackIdFromPage(html: String): String? = trackUrlInPageRegex.find(html)?.groupValues?.get(1)

    fun extractFirstUrl(text: String): String? {
        val match = urlRegex.find(text)?.value ?: return null
        return match.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
    }

    private fun extractTrackId(value: String): String? {
        if (idRegex.matches(value)) return value

        if (value.startsWith("spotify:track:", ignoreCase = true)) {
            return value.substringAfterLast(':').takeIf { idRegex.matches(it) }
        }

        val url = value.toHttpUrlOrNull() ?: return null
        url.queryParameter("uri")?.let { extractTrackId(it) }?.let { return it }

        if (!url.host.isSpotifyHost()) return null
        val segments = url.pathSegments
        val trackIndex = segments.indexOf("track")
        return segments.getOrNull(trackIndex + 1)?.takeIf { trackIndex != -1 && idRegex.matches(it) }
    }

    private fun String.isSpotifyHost() = this == "spotify.com" || endsWith(".spotify.com")

    private fun String.isSpotifyShortLinkHost() = this == "spotify.link" || endsWith(".spotify.link")
}
