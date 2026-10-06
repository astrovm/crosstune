package com.astrovm.crosstune

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A link to one item on one service. [url] is canonical; [region] is the storefront for Apple Music. */
internal data class MusicLink(
    /** Null for recognized songs shared as search links rather than a music service link. */
    val service: MusicService?,
    val type: ItemType,
    val id: String,
    val url: String,
    val region: String? = null,
    /** Came from a frontend such as Invidious or Piped; [url] is still the service's own. */
    val viaFrontend: Boolean = false,
    /** Which one, when it came from one of its popular sites, so its own rule can apply. */
    val frontend: Frontend? = null,
    /** The frontend's own link, to open as is when the video isn't music. */
    val frontendUrl: String? = null
)

/** What a pasted or shared piece of text points at. */
internal sealed interface LinkInput {
    data class Link(val link: MusicLink) : LinkInput
    data class ShortLink(val url: String) : LinkInput
    /** An older Shazam link, whose song is looked up by Shazam's own [key]. */
    data class ShazamTrack(val key: String) : LinkInput
    /**
     * A song known by name rather than by a music service link: shared by Pixel Now Playing, the
     * song in words then a Google search for it, or Google's own song result, by its title only.
     */
    data class RecognizedSong(val url: String, val metadata: MusicMetadata) : LinkInput {
        /** Shared text that parses back to this song, for the link field. */
        val text get() = if (metadata.artist.isBlank()) url else MusicLinks.nowPlayingShare(metadata)
    }
}

/** Parses links, URIs and IDs from every supported source service. Pure Kotlin, no Android APIs. */
internal object MusicLinks {
    private const val NOW_PLAYING_SEARCH = "https://www.google.com/search?q="
    private val frontendSites = Frontend.SOURCES.flatMap { it.sites }.toSet()

    /** Stops at quotes, angle brackets and CJK brackets, which share text often wraps links in. */
    private val urlRegex = Regex("""https?://[^\s"'<>「」『』（）【】]+""", RegexOption.IGNORE_CASE)
    private val spotifyIdRegex = Regex("""^[A-Za-z0-9]{22}$""")
    private val youtubeIdRegex = Regex("""^[A-Za-z0-9_-]{11}$""")
    /** Playlist IDs, e.g. PL… for user playlists or OLAK5uy_… for albums on YouTube Music. */
    private val youtubeListRegex = Regex("""^[A-Za-z0-9_-]{12,}$""")
    private val numericIdRegex = Regex("""^\d+$""")
    private val googleHostRegex = Regex("""(www\.)?google\.[a-z]{2,3}(\.[a-z]{2})?""")
    private val tidalIdRegex = Regex("""^[A-Za-z0-9-]+$""")
    private val spotifyUrlInPageRegex =
        Regex("""https://open\.spotify\.com/(?:intl-[A-Za-z-]+/)?(track|album|artist|playlist)/([A-Za-z0-9]{22})""")

    private val shortLinkHosts = setOf(
        "spotify.link", "link.deezer.com", "deezer.page.link", "dzr.page.link", "on.soundcloud.com",
        // Google's shared results, such as a song its song search found.
        "share.google"
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
        nowPlayingSong(text)?.let { return it }
        val value = (extractFirstUrl(text) ?: text).trim()
        spotifyUriOrId(value)?.let { return LinkInput.Link(it) }

        // People sometimes copy a link without its scheme, e.g. "open.spotify.com/track/...".
        val url = value.toHttpUrlOrNull()
            ?: "https://$value".toHttpUrlOrNull()?.takeIf { serviceForHost(it.host) != null || it.host.isOn("shazam.com") }
            ?: return null
        shazamTrack(url)?.let { return it }
        googleSong(url)?.let { return it }
        fromUrl(url)?.let { return LinkInput.Link(it) }
        // g.co also shortens Google's other links; its shared results are under /kgs.
        if (!url.host.isShortLinkHost() && !(url.host == "g.co" && url.pathSegments.firstOrNull() == "kgs")) return null
        // Short links are always served over HTTPS; upgrading avoids a blocked cleartext request.
        return LinkInput.ShortLink(url.newBuilder().scheme("https").build().toString())
    }

    /**
     * A song as Now Playing shares it: the song in the phone's language, then a Google search
     * for it. The search isn't encoded, only its spaces become "+", so it's compared as text.
     * Any other search, like one that happens to contain " by ", isn't a song.
     */
    private fun nowPlayingSong(text: String): LinkInput.RecognizedSong? {
        val start = text.indexOf(NOW_PLAYING_SEARCH)
        if (start < 0) return null
        val song = text.substring(0, start).trim()
        val search = text.substring(start).trim()
        return nowPlayingPatterns.firstNotNullOfOrNull { (shared, searched) ->
            val groups = shared.regex.matchEntire(song)?.groupValues ?: return@firstNotNullOfOrNull null
            val title = groups[shared.titleGroup].takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
            val artist = groups[3 - shared.titleGroup].takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
            if (search != nowPlayingSearch(searched.fill(title, artist))) return@firstNotNullOfOrNull null
            recognizedSong(MusicMetadata(title, artist))
        }
    }

    /** One of Now Playing's wordings, "%1$s" being the title and "%2$s" the artist. */
    private class SongPattern(val text: String) {
        val titleGroup = if (text.indexOf("%1\$s") < text.indexOf("%2\$s")) 1 else 2
        val regex = text.split("%1\$s", "%2\$s").joinToString("(.+)") { Regex.escape(it) }.toRegex()
        fun fill(title: String, artist: String) = text.replace("%1\$s", title).replace("%2\$s", artist)
    }

    private val nowPlayingPatterns by lazy {
        NowPlayingShares.patterns.map { (shared, searched) -> SongPattern(shared) to SongPattern(searched) }
    }

    /** What Now Playing shares for a song with the English wording, which every phone reads. */
    fun nowPlayingShare(metadata: MusicMetadata): String {
        val song = "${metadata.title} by ${metadata.artist}"
        return "$song ${nowPlayingSearch(song)}"
    }

    private fun nowPlayingSearch(query: String) = NOW_PLAYING_SEARCH + query.replace(' ', '+')

    /** The service a URL belongs to, even when it isn't a song, album, artist or playlist. */
    fun serviceFor(text: String): MusicService? = text.trim().toHttpUrlOrNull()?.host?.let(::serviceForHost)

    /** What [text] is a link to, without fetching anything about it. */
    fun linkFor(text: String): MusicLink? = text.trim().toHttpUrlOrNull()?.let(::fromUrl)

    private val schemeRegex = Regex("""^[a-z][a-z0-9+.-]*:""", RegexOption.IGNORE_CASE)

    /**
     * An id pasted with its address left off, e.g. one copied from a Spotify url. Twenty characters
     * is well past any title anyone types, and short enough to catch an id with a character added
     * or lost.
     */
    private val bareIdRegex = Regex("""[A-Za-z0-9]{20,}""")

    /**
     * Whether [text] was meant as a link rather than as a song's name: it carries a scheme, or a
     * host with a dot in it, e.g. "spotify:track:x" or "open.spotify.com/track/x", or it is an ID
     * pasted on its own. A link Crosstune can't open is still a link, so it is reported as one
     * instead of being looked up as a song that happens to be named "open.spotify.com/track/x".
     *
     * The dot on its own proves nothing, since names carry them too: "Mr.Big" and "S.O.S" are songs.
     * So a dotted name counts only as an address Crosstune already knows, or one with something
     * past its host, which is what makes the rest of it a link.
     */
    fun looksLikeALink(text: String): Boolean {
        val value = text.trim()
        // Names have spaces in them, and nothing else typed here does.
        if (value.isEmpty() || value.any { it.isWhitespace() }) return false
        if (schemeRegex.containsMatchIn(value)) return true
        // A bare path, e.g. "/track/11dFg", is a link with its address left off.
        if (value.startsWith('/')) return true
        // A bare id, e.g. one copied from a Spotify url, is a link with its address left off.
        if (bareIdRegex.matches(value)) return true
        val host = value.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        if (!host.contains('.') || host.substringAfterLast('.').length < 2) return false
        return value.length > host.length || serviceForHost(host) != null
    }

    private fun serviceForHost(host: String): MusicService? = when {
        host.isOn("spotify.com") || host.isOn("spotify.link") -> MusicService.SPOTIFY
        host == "music.youtube.com" -> MusicService.YOUTUBE_MUSIC
        host == "youtu.be" || host.isOn("youtube.com") -> MusicService.YOUTUBE
        host.isOn("music.apple.com") -> MusicService.APPLE_MUSIC
        host.isOn("deezer.com") || host.isOn("deezer.page.link") || host == "dzr.page.link" -> MusicService.DEEZER
        host.isOn("tidal.com") -> MusicService.TIDAL
        host.isOn("soundcloud.com") -> MusicService.SOUNDCLOUD
        host.endsWith(".bandcamp.com") -> MusicService.BANDCAMP
        host.isOn("audiomack.com") -> MusicService.AUDIOMACK
        else -> null
    }

    /** True for [domain] itself and its subdomains, but not look-alikes such as "notdeezer.com". */
    private fun String.isOn(domain: String) = this == domain || endsWith(".$domain")

    fun extractFirstUrl(text: String): String? {
        val match = urlRegex.find(text)?.value ?: return null
        return match.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '。', '、')
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
            host == "audiomack.com" || host == "www.audiomack.com" -> audiomack(segments)
            host == "shazam.com" || host == "www.shazam.com" -> shazamSong(segments)
            // Invidious and Piped, on any of their many sites, use YouTube's own watch links; the
            // popular sites' other video paths are recognised too.
            segments == listOf("watch") || host in frontendSites -> frontendVideo(url, segments)
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
        segments == listOf("playlist") -> url.queryParameter("list")?.takeIf { youtubeListRegex.matches(it) }?.let { list ->
            val base = if (service == MusicService.YOUTUBE_MUSIC) "https://music.youtube.com" else "https://www.youtube.com"
            MusicLink(service, ItemType.PLAYLIST, list, "$base/playlist?list=$list")
        }
        else -> null
    }

    private fun frontendVideo(url: HttpUrl, segments: List<String>): MusicLink? {
        val id = when {
            segments == listOf("watch") -> url.queryParameter("v")
            segments.size == 2 && segments[0] in setOf("shorts", "live", "embed") -> segments[1]
            else -> null
        }
        val frontend = Frontend.SOURCES.firstOrNull { url.host in it.sites }
        return id?.let { youtube(MusicService.YOUTUBE, it) }?.copy(viaFrontend = true, frontend = frontend, frontendUrl = url.toString())
    }

    private fun youtube(service: MusicService, id: String): MusicLink? {
        if (!youtubeIdRegex.matches(id)) return null
        val base = if (service == MusicService.YOUTUBE_MUSIC) "https://music.youtube.com" else "https://www.youtube.com"
        return MusicLink(service, ItemType.TRACK, id, "$base/watch?v=$id")
    }

    /** audiomack.com/{artist}/{song|album|playlist}/{slug}; the id keeps the artist and slug. */
    private fun audiomack(segments: List<String>): MusicLink? {
        if (segments.size != 3) return null
        val type = mapOf("song" to ItemType.TRACK, "album" to ItemType.ALBUM, "playlist" to ItemType.PLAYLIST)[segments[1]] ?: return null
        return MusicLink(MusicService.AUDIOMACK, type, "${segments[0]}/${segments[2]}", "https://audiomack.com/${segments.joinToString("/")}")
    }

    /** music.apple.com/{region}/{song|album|artist|playlist}/{slug}/{id}, where album?i= is a song. */
    private fun appleMusic(url: HttpUrl, segments: List<String>): MusicLink? {
        val region = segments.firstOrNull()?.takeIf { it.length == 2 }?.lowercase() ?: "us"
        val typeIndex = segments.indexOfFirst { it in setOf("song", "album", "artist", "playlist") }
        if (typeIndex == -1 || typeIndex == segments.lastIndex) return null
        val id = segments.last().removePrefix("id")
        // An empty or broken "i" still leaves a valid album link.
        val trackId = url.queryParameter("i")?.takeIf { numericIdRegex.matches(it) }
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

    /**
     * Shazam's song pages, shazam.com/song/{id}/{name}, go by the song's Apple Music ID, so it
     * opens as that song. Its own pages are blank without JavaScript, but Apple's lookup isn't.
     */
    private fun shazamSong(segments: List<String>): MusicLink? {
        if (segments.firstOrNull() != "song") return null
        val id = segments.getOrNull(1)?.takeIf { numericIdRegex.matches(it) } ?: return null
        return MusicLink(MusicService.APPLE_MUSIC, ItemType.TRACK, id, "https://music.apple.com/us/song/$id", "us")
    }

    /** Older Shazam links, shazam.com/track/{key}/{name}, use Shazam's own key for the song. */
    private fun shazamTrack(url: HttpUrl): LinkInput.ShazamTrack? {
        if (!url.host.isOn("shazam.com")) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.firstOrNull() != "track") return null
        return segments.getOrNull(1)?.takeIf { numericIdRegex.matches(it) }?.let { LinkInput.ShazamTrack(it) }
    }

    /**
     * A Google result for one thing, such as a song its song search found: a search with the
     * thing's Knowledge Graph ID, "kgmid". It only says the song's title, so it's searched by that.
     */
    fun googleSong(url: HttpUrl): LinkInput.RecognizedSong? {
        if (!url.host.isGoogle() || url.pathSegments != listOf("search")) return null
        val id = url.queryParameter("kgmid")?.takeIf { it.isNotBlank() } ?: return null
        val title = url.queryParameter("q")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val canonical = "https://www.google.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", title).addQueryParameter("kgmid", id).build().toString()
        return LinkInput.RecognizedSong(canonical, MusicMetadata(title, ""))
    }

    /** Google's own sites, e.g. google.com or google.com.ar, but not "notgoogle.com". */
    private fun String.isGoogle() = googleHostRegex.matches(this)

    /** A song known only by name, kept as a Google search for it, as Now Playing shares it. */
    fun recognizedSong(metadata: MusicMetadata): LinkInput.RecognizedSong {
        val canonical = "https://www.google.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "${metadata.title} by ${metadata.artist}").build().toString()
        return LinkInput.RecognizedSong(canonical, metadata)
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
        val trackId = url.queryParameter("i")?.takeIf { numericIdRegex.matches(it) }
        return url.newBuilder().scheme("https").query(null)
            .apply { if (trackId != null) addQueryParameter("i", trackId) }
            .build().toString()
    }

    private fun String.isShortLinkHost() = this in shortLinkHosts || endsWith(".spotify.link")
}

private val youtubeServices = setOf(MusicService.YOUTUBE, MusicService.YOUTUBE_MUSIC)

/** This YouTube or YouTube Music playlist's own page on [service], if that's one of the two. */
internal fun MusicLink.youtubePlaylistOn(service: MusicService?): String? {
    if (type != ItemType.PLAYLIST || this.service !in youtubeServices || service !in youtubeServices) return null
    val base = if (service == MusicService.YOUTUBE_MUSIC) "https://music.youtube.com" else "https://www.youtube.com"
    return "$base/playlist?list=$id"
}

/**
 * This YouTube or YouTube Music video's own page on [service], if that's one of the two. The two
 * apps play the same videos, so one opens as itself in the other: a search for the title finds
 * another recording, or none, and never the video the user picked.
 */
internal fun MusicLink.youtubeVideoOn(service: MusicService?): String? {
    if (type != ItemType.TRACK || this.service !in youtubeServices || service !in youtubeServices) return null
    val base = if (service == MusicService.YOUTUBE_MUSIC) "https://music.youtube.com" else "https://www.youtube.com"
    return "$base/watch?v=$id"
}
