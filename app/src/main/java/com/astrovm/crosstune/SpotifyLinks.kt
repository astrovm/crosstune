package com.astrovm.crosstune

import androidx.annotation.StringRes
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class SpotifyType(val path: String, @StringRes val labelRes: Int) {
    TRACK("track", R.string.type_track),
    ALBUM("album", R.string.type_album),
    ARTIST("artist", R.string.type_artist),
    PLAYLIST("playlist", R.string.type_playlist);

    companion object {
        fun fromPath(path: String): SpotifyType? = entries.firstOrNull { it.path.equals(path, ignoreCase = true) }
    }
}

internal data class SpotifyItem(val type: SpotifyType, val id: String) {
    val url: String get() = "https://open.spotify.com/${type.path}/$id"
}

/** What a pasted or shared piece of text points at on Spotify. */
internal sealed interface SpotifyInput {
    data class Item(val item: SpotifyItem) : SpotifyInput
    data class ShortLink(val url: String) : SpotifyInput
}

/** Parses the Spotify links, URIs and IDs people paste or share. Pure Kotlin, no Android APIs. */
internal object SpotifyLinks {
    private val idRegex = Regex("""^[A-Za-z0-9]{22}$""")
    private val urlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val itemUrlInPageRegex =
        Regex("""https://open\.spotify\.com/(?:intl-[A-Za-z-]+/)?(track|album|artist|playlist)/([A-Za-z0-9]{22})""")

    fun parse(text: String): SpotifyInput? {
        val value = (extractFirstUrl(text) ?: text).trim()
        extractItem(value)?.let { return SpotifyInput.Item(it) }

        val url = value.toHttpUrlOrNull() ?: return null
        if (!url.host.isSpotifyShortLinkHost()) return null
        // Short links are always served over HTTPS; upgrading avoids a blocked cleartext request.
        return SpotifyInput.ShortLink(url.newBuilder().scheme("https").build().toString())
    }

    /** Reads the item from a URL a short link redirected to. */
    fun itemFromUrl(url: HttpUrl): SpotifyItem? = extractItem(url.toString())

    /** Short-link landing pages sometimes redirect with JavaScript; the target URL is still in the HTML. */
    fun itemFromPage(html: String): SpotifyItem? {
        val match = itemUrlInPageRegex.find(html) ?: return null
        return SpotifyItem(SpotifyType.fromPath(match.groupValues[1])!!, match.groupValues[2])
    }

    fun extractFirstUrl(text: String): String? {
        val match = urlRegex.find(text)?.value ?: return null
        return match.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
    }

    private fun extractItem(value: String): SpotifyItem? {
        // A bare ID is assumed to be a track, the most commonly shared item.
        if (idRegex.matches(value)) return SpotifyItem(SpotifyType.TRACK, value)

        if (value.startsWith("spotify:", ignoreCase = true)) {
            val parts = value.split(':')
            if (parts.size != 3) return null
            return item(parts[1], parts[2])
        }

        val url = value.toHttpUrlOrNull() ?: return null
        url.queryParameter("uri")?.let { extractItem(it) }?.let { return it }

        if (!url.host.isSpotifyHost()) return null
        val segments = url.pathSegments
        return segments.indices.firstNotNullOfOrNull { index ->
            segments.getOrNull(index + 1)?.let { id -> item(segments[index], id) }
        }
    }

    private fun item(typePath: String, id: String): SpotifyItem? {
        val type = SpotifyType.fromPath(typePath) ?: return null
        return SpotifyItem(type, id).takeIf { idRegex.matches(id) }
    }

    private fun String.isSpotifyHost() = this == "spotify.com" || endsWith(".spotify.com")

    private fun String.isSpotifyShortLinkHost() = this == "spotify.link" || endsWith(".spotify.link")
}
