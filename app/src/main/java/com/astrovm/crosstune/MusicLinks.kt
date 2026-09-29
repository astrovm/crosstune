package com.astrovm.crosstune

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A link to one item on one service. [url] is canonical; [region] is the storefront for Apple Music. */
internal data class MusicLink(
    val service: MusicService,
    val type: ItemType,
    val id: String,
    val url: String,
    val region: String? = null
)

/** What a pasted or shared piece of text points at. */
internal sealed interface LinkInput {
    data class Link(val link: MusicLink) : LinkInput
    data class ShortLink(val url: String) : LinkInput
}

/** Parses links, URIs and IDs from every supported source service. Pure Kotlin, no Android APIs. */
internal object MusicLinks {
    private val urlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val spotifyIdRegex = Regex("""^[A-Za-z0-9]{22}$""")
    private val youtubeIdRegex = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val numericIdRegex = Regex("""^\d+$""")
    private val tidalIdRegex = Regex("""^[A-Za-z0-9-]+$""")
    private val spotifyUrlInPageRegex =
        Regex("""https://open\.spotify\.com/(?:intl-[A-Za-z-]+/)?(track|album|artist|playlist)/([A-Za-z0-9]{22})""")

    private val shortLinkHosts = setOf(
        "spotify.link", "link.deezer.com", "deezer.page.link", "dzr.page.link", "on.soundcloud.com"
    )
    private val itemPaths = mapOf(
        "track" to ItemType.TRACK, "album" to ItemType.ALBUM, "artist" to ItemType.ARTIST, "playlist" to ItemType.PLAYLIST
    )
    private val soundCloudReservedPaths = setOf(
        "discover", "search", "stream", "you", "upload", "charts", "pages", "settings", "messages",
        "notifications", "terms-of-use", "mobile", "people", "tags", "stations", "feed", "signin", "logout"
    )
    private val soundCloudArtistTabs = setOf(
        "tracks", "albums", "sets", "reposts", "likes", "followers", "following", "popular-tracks", "comments"
    )

    fun parse(text: String): LinkInput? {
        val value = (extractFirstUrl(text) ?: text).trim()
        spotifyUriOrId(value)?.let { return LinkInput.Link(it) }

        val url = value.toHttpUrlOrNull() ?: return null
        fromUrl(url)?.let { return LinkInput.Link(it) }
        if (!url.host.isShortLinkHost()) return null
        // Short links are always served over HTTPS; upgrading avoids a blocked cleartext request.
        return LinkInput.ShortLink(url.newBuilder().scheme("https").build().toString())
    }

    /** The service a URL belongs to, even when it isn't a song, album, artist or playlist. */
    fun serviceFor(text: String): MusicService? {
        val host = text.trim().toHttpUrlOrNull()?.host ?: return null
        return when {
            host == "spotify.com" || host.endsWith(".spotify.com") || host.endsWith("spotify.link") -> MusicService.SPOTIFY
            host == "music.youtube.com" -> MusicService.YOUTUBE_MUSIC
            host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") -> MusicService.YOUTUBE
            host.endsWith("music.apple.com") -> MusicService.APPLE_MUSIC
            host.endsWith("deezer.com") || host.endsWith("deezer.page.link") || host == "dzr.page.link" ->
                MusicService.DEEZER
            host.endsWith("tidal.com") -> MusicService.TIDAL
            host.endsWith("soundcloud.com") -> MusicService.SOUNDCLOUD
            host.endsWith(".bandcamp.com") -> MusicService.BANDCAMP
            else -> null
        }
    }

    fun extractFirstUrl(text: String): String? {
        val match = urlRegex.find(text)?.value ?: return null
        return match.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
    }

    /** Short-link landing pages sometimes redirect with JavaScript; the target URL is still in the HTML. */
    fun fromPage(html: String): MusicLink? {
        val match = spotifyUrlInPageRegex.find(html) ?: return null
        return spotify(itemPaths.getValue(match.groupValues[1]), match.groupValues[2])
    }

    fun fromUrl(url: HttpUrl): MusicLink? {
        val host = url.host
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        return when {
            host == "spotify.com" || host.endsWith(".spotify.com") -> spotifyUrl(url, segments)
            host == "youtu.be" -> segments.firstOrNull()?.let { youtube(MusicService.YOUTUBE, it) }
            host == "music.youtube.com" -> youtubeWatch(MusicService.YOUTUBE_MUSIC, url, segments)
            host == "youtube.com" || host == "www.youtube.com" || host == "m.youtube.com" ->
                youtubeWatch(MusicService.YOUTUBE, url, segments)
            host == "music.apple.com" || host == "geo.music.apple.com" || host == "itunes.apple.com" ->
                appleMusic(url, segments)
            host == "deezer.com" || host == "www.deezer.com" -> deezer(segments)
            host == "tidal.com" || host == "www.tidal.com" || host == "listen.tidal.com" -> tidal(segments)
            host == "soundcloud.com" || host == "www.soundcloud.com" || host == "m.soundcloud.com" ->
                soundCloud(segments)
            host.endsWith(".bandcamp.com") && host != "daily.bandcamp.com" -> bandcamp(host, segments)
            else -> null
        }
    }

    private fun spotifyUriOrId(value: String): MusicLink? {
        // A bare ID is assumed to be a Spotify track, the most commonly shared item.
        if (spotifyIdRegex.matches(value)) return spotify(ItemType.TRACK, value)
        if (!value.startsWith("spotify:", ignoreCase = true)) return null
        val parts = value.split(':')
        if (parts.size != 3) return null
        return itemPaths[parts[1].lowercase()]?.let { spotify(it, parts[2]) }
    }

    private fun spotifyUrl(url: HttpUrl, segments: List<String>): MusicLink? {
        url.queryParameter("uri")?.let(::spotifyUriOrId)?.let { return it }
        return typedItem(segments) { type, id -> spotify(type, id) }
    }

    private fun spotify(type: ItemType, id: String): MusicLink? {
        if (!spotifyIdRegex.matches(id)) return null
        val path = itemPaths.entries.first { it.value == type }.key
        return MusicLink(MusicService.SPOTIFY, type, id, "https://open.spotify.com/$path/$id")
    }

    private fun youtubeWatch(service: MusicService, url: HttpUrl, segments: List<String>): MusicLink? = when {
        segments == listOf("watch") -> url.queryParameter("v")?.let { youtube(service, it) }
        segments.size == 2 && segments[0] in setOf("shorts", "live") -> youtube(service, segments[1])
        else -> null
    }

    private fun youtube(service: MusicService, id: String): MusicLink? {
        if (!youtubeIdRegex.matches(id)) return null
        val base = if (service == MusicService.YOUTUBE_MUSIC) "https://music.youtube.com" else "https://www.youtube.com"
        return MusicLink(service, ItemType.TRACK, id, "$base/watch?v=$id")
    }

    /** music.apple.com/{region}/{song|album|artist|playlist}/{slug}/{id}, where album?i= is a song. */
    private fun appleMusic(url: HttpUrl, segments: List<String>): MusicLink? {
        val region = segments.firstOrNull()?.takeIf { it.length == 2 }?.lowercase() ?: "us"
        val typeIndex = segments.indexOfFirst { it in setOf("song", "album", "artist", "playlist") }
        if (typeIndex == -1 || typeIndex == segments.lastIndex) return null
        val id = segments.last().removePrefix("id")
        val trackId = url.queryParameter("i")
        val (type, itemId) = when (segments[typeIndex]) {
            "song" -> ItemType.TRACK to id
            "album" -> if (trackId != null) ItemType.TRACK to trackId else ItemType.ALBUM to id
            "artist" -> ItemType.ARTIST to id
            else -> return MusicLink(MusicService.APPLE_MUSIC, ItemType.PLAYLIST, id, canonical(url), region)
                .takeIf { id.startsWith("pl.") }
        }
        if (!numericIdRegex.matches(itemId)) return null
        return MusicLink(MusicService.APPLE_MUSIC, type, itemId, canonical(url), region)
    }

    private fun deezer(segments: List<String>): MusicLink? = typedItem(segments) { type, id ->
        val path = itemPaths.entries.first { it.value == type }.key
        MusicLink(MusicService.DEEZER, type, id, "https://www.deezer.com/$path/$id").takeIf { numericIdRegex.matches(id) }
    }

    private fun tidal(segments: List<String>): MusicLink? = typedItem(segments) { type, id ->
        val path = itemPaths.entries.first { it.value == type }.key
        MusicLink(MusicService.TIDAL, type, id, "https://tidal.com/$path/$id").takeIf { tidalIdRegex.matches(id) }
    }

    /** soundcloud.com/{artist}, /{artist}/{track} and /{artist}/sets/{playlist}. */
    private fun soundCloud(segments: List<String>): MusicLink? {
        val artist = segments.firstOrNull()?.takeIf { it !in soundCloudReservedPaths } ?: return null
        val type = when {
            segments.size == 1 -> ItemType.ARTIST
            segments.size == 2 && segments[1] in soundCloudArtistTabs -> ItemType.ARTIST
            segments.size == 2 -> ItemType.TRACK
            segments.size == 3 && segments[1] == "sets" -> ItemType.PLAYLIST
            else -> return null
        }
        val path = if (type == ItemType.ARTIST) listOf(artist) else segments
        val id = path.joinToString("/")
        return MusicLink(MusicService.SOUNDCLOUD, type, id, "https://soundcloud.com/$id")
    }

    private fun bandcamp(host: String, segments: List<String>): MusicLink? {
        if (segments.size != 2) return null
        val type = when (segments[0]) {
            "track" -> ItemType.TRACK
            "album" -> ItemType.ALBUM
            else -> return null
        }
        val id = "$host/${segments[0]}/${segments[1]}"
        return MusicLink(MusicService.BANDCAMP, type, id, "https://$id")
    }

    /** Finds "{track|album|artist|playlist}/{id}" anywhere in the path, e.g. after a locale prefix. */
    private fun typedItem(segments: List<String>, build: (ItemType, String) -> MusicLink?): MusicLink? =
        segments.indices.firstNotNullOfOrNull { index ->
            val type = itemPaths[segments[index].lowercase()] ?: return@firstNotNullOfOrNull null
            segments.getOrNull(index + 1)?.let { build(type, it) }
        }

    private fun canonical(url: HttpUrl): String {
        val trackId = url.queryParameter("i")
        return url.newBuilder().scheme("https").query(null)
            .apply { if (trackId != null) addQueryParameter("i", trackId) }
            .build().toString()
    }

    private fun String.isShortLinkHost() = this in shortLinkHosts || endsWith(".spotify.link")
}
