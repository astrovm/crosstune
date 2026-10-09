package com.astrovm.crosstune

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Base64

/** What Crosstune knows about an item; [artist] is blank for artists and playlists. */
internal data class MusicMetadata(
    val title: String,
    val artist: String,
    val type: ItemType = ItemType.TRACK,
    /** Cover art, thumbnail or artist picture, when the service gives one. */
    val artworkUrl: String? = null,
    /** A playlist's songs, as far as its service shows them without signing in; empty otherwise. */
    val tracks: List<MusicMetadata> = emptyList(),
    /** A playlist's song on its own service, which some covers are looked up by. */
    val url: String? = null,
    /** How many songs a playlist has, when its service says and lists fewer of them. */
    val trackCount: Int? = null
)

/** Turns each service's page, oEmbed or API response into [MusicMetadata]. Returns null when unusable. */
internal object MetadataParsers {
    private val metaTagRegex = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
    private val titleTagRegex = Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)
    private val attributeRegex = Regex("""([A-Za-z:_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val tidalSuffixRegex = Regex("""(^|\s)on TIDAL$""")
    private val entityRegex = Regex("""&(#[0-9]+|#[xX][0-9A-Fa-f]+|[A-Za-z]+);""")
    // https://developers.deezer.com/api/errors
    private const val DEEZER_QUOTA_EXCEEDED = 4
    private const val DEEZER_SERVICE_BUSY = 700
    private const val DEEZER_DATA_NOT_FOUND = 800
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " "
    )

    /** Video titles often carry release noise such as "(Official Video)" or "[Lyrics]". */
    private val bracketedSongRegex = Regex("""(.*?)\s*[「『]([^」』]+)[」』].*""")

    private val videoNoiseRegex = Regex(
        """\s*[(\[][^)\]]*\b(official|video|audio|lyrics?|visuali[sz]er|hd|4k|mv|m/v)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Spotify tracks describe themselves as "Artist · Album · Song · Year" and albums as
     * "Artist · album · Year · N songs", with a title like "Name - Album by Artist | Spotify".
     * Artist and playlist descriptions are free text, so only their title is used.
     */
    fun spotify(html: String, type: ItemType): MusicMetadata? {
        val tags = openGraphTags(html)
        val rawTitle = tags["og:title"]?.removeSuffix(" | Spotify")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val artist = when (type) {
            ItemType.TRACK, ItemType.ALBUM -> tags["og:description"]?.split(" · ")?.first()?.trim().orEmpty()
            ItemType.ARTIST, ItemType.PLAYLIST -> ""
        }
        val byArtist = " by $artist"
        val title = if (type == ItemType.ALBUM && rawTitle.endsWith(byArtist) && " - " in rawTitle) {
            rawTitle.removeSuffix(byArtist).substringBeforeLast(" - ")
        } else {
            rawTitle
        }
        val count = Regex("""<meta name="music:song_count" content="(\d+)"""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        return MusicMetadata(title, artist, type, tags.image(), trackCount = count)
    }

    private val spotifyStateRegex = Regex("""<script id="initialState" type="text/plain">([^<]*)</script>""")

    /**
     * Spotify's playlist page lists its first songs with their album covers, by song link, in its
     * own data, which is base64-encoded JSON.
     */
    fun spotifyCovers(html: String): Map<String, String> {
        val encoded = spotifyStateRegex.find(html)?.groupValues?.get(1) ?: return emptyMap()
        val state = runCatching { JSONObject(String(Base64.getDecoder().decode(encoded.trim()))) }.getOrNull() ?: return emptyMap()
        val items = state.optJSONObject("entities")?.optJSONObject("items") ?: return emptyMap()
        return items.keys().asSequence().mapNotNull { items.optJSONObject(it)?.optJSONObject("content")?.optJSONArray("items") }
            .flatMap { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optJSONObject("itemV2")?.optJSONObject("data") } }
            .mapNotNull { song ->
                val url = spotifyTrackUrl(song.optString("uri")) ?: return@mapNotNull null
                val sources = song.optJSONObject("albumOfTrack")?.optJSONObject("coverArt")?.optJSONArray("sources") ?: return@mapNotNull null
                // The 300px one: sharp in a list without downloading the 640px one.
                val cover = (0 until sources.length()).mapNotNull(sources::optJSONObject)
                    .minByOrNull { kotlin.math.abs(it.optInt("width") - 300) }?.optString("url")?.ifBlank { null }
                cover?.let { url to it }
            }.toMap()
    }

    /** "spotify:track:ID" as the song's link. */
    private fun spotifyTrackUrl(uri: String): String? =
        uri.removePrefix("spotify:track:").takeIf { it != uri && it.isNotBlank() }?.let { "https://open.spotify.com/track/$it" }

    /** YouTube oEmbed: "Artist - Song (Official Video)" by "ArtistVEVO", or "Song" by "Artist - Topic". */
    fun youtube(json: JSONObject): MusicMetadata? {
        // hqdefault is letterboxed to 4:3; mqdefault is the bare 16:9 frame, whose center is the cover on art tracks.
        val artwork = json.optString("thumbnail_url").replace("/hqdefault.", "/mqdefault.").ifBlank { null }
        return youtubeVideo(json.optString("title"), json.optString("author_name"))?.copy(artworkUrl = artwork)
    }

    /** A song from a video's title and channel, which often put "Artist - Song" in the title. */
    fun youtubeVideo(title: String, channel: String): MusicMetadata? {
        val rawTitle = title.replace(videoNoiseRegex, "").trim().ifEmpty { return null }
        val author = channel.removeSuffix(" - Topic").removeSuffix("VEVO").trim()
        // Japanese videos name the song in brackets after the artist, whatever follows them:
        // "YOASOBI「アイドル」 Official Music Video".
        bracketedSongRegex.matchEntire(rawTitle)?.let { found ->
            val (artist, song) = found.destructured
            return MusicMetadata(song.trim(), artist.trim().ifEmpty { author })
        }
        // Topic channels title songs by name alone, so a dash there is part of it, e.g. "Song - Remastered 2011".
        val separator = if (channel.endsWith(" - Topic")) -1 else rawTitle.indexOf(" - ")
        return if (separator > 0) {
            MusicMetadata(rawTitle.substring(separator + 3).trim(), rawTitle.substring(0, separator).trim())
        } else {
            MusicMetadata(rawTitle, author)
        }
    }

    private val youtubeDataRegex = Regex("""var ytInitialData = (\{.*?\});</script>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * A YouTube playlist page: its name, picture and, in the page's own data, its first videos, each
     * with its title and channel, read as songs like single videos are.
     */
    fun youtubePlaylist(html: String): MusicMetadata? {
        val tags = openGraphTags(html)
        val title = tags["og:title"]?.takeIf { it.isNotBlank() } ?: return null
        val data = youtubeDataRegex.find(html)?.groupValues?.get(1)
        val videos = data?.let { runCatching { youtubeVideos(JSONTokener(it).nextValue()) }.getOrNull() }.orEmpty()
        return MusicMetadata(title, "", ItemType.PLAYLIST, tags.image(), videos)
    }

    private fun youtubeVideos(value: Any?): List<MusicMetadata> = when (value) {
        is JSONObject -> value.optJSONObject("lockupViewModel")?.takeIf { it.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" }?.let { lockup ->
            val metadata = lockup.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            val channel = metadata?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")
                ?.optJSONArray("metadataRows")?.optJSONObject(0)?.optJSONArray("metadataParts")?.optJSONObject(0)
                ?.optJSONObject("text")?.optString("content").orEmpty()
            val id = lockup.optString("contentId").ifBlank { null }
            listOfNotNull(
                youtubeVideo(metadata?.optJSONObject("title")?.optString("content").orEmpty(), channel)?.copy(
                    artworkUrl = id?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" },
                    url = id?.let { "https://www.youtube.com/watch?v=$it" }
                )
            )
        } ?: value.keys().asSequence().flatMap { youtubeVideos(value.opt(it)) }.toList()
        is JSONArray -> (0 until value.length()).flatMap { youtubeVideos(value.opt(it)) }
        else -> emptyList()
    }

    /** iTunes Lookup API result for a song, album or artist. */
    fun appleMusic(json: JSONObject, type: ItemType): MusicMetadata? {
        val result = json.optJSONArray("results")?.optJSONObject(0) ?: return null
        val artist = result.optString("artistName")
        // The lookup only offers small sizes, but the image server renders any size in the path.
        val artwork = result.optString("artworkUrl100").replace("/100x100bb.", "/600x600bb.").ifBlank { null }
        return when (type) {
            ItemType.TRACK -> MusicMetadata(result.optString("trackName"), artist, type, artwork)
            // Albums are looked up with their songs, which follow the album in the results.
            ItemType.ALBUM -> MusicMetadata(
                result.optString("collectionName").removeSuffix(" - Single").removeSuffix(" - EP"), artist, type, artwork,
                appleAlbumSongs(json.optJSONArray("results")!!)
            )
            else -> MusicMetadata(artist, "", type)
        }.takeIf { it.title.isNotBlank() }
    }

    private fun appleAlbumSongs(results: JSONArray): List<MusicMetadata> = (1 until results.length()).mapNotNull { index ->
        val song = results.optJSONObject(index)?.takeIf { it.optString("wrapperType") == "track" } ?: return@mapNotNull null
        MusicMetadata(song.optString("trackName").ifBlank { return@mapNotNull null }, song.optString("artistName"))
    }

    /**
     * Apple Music playlist pages title themselves "Name on Apple Music", and list their songs in
     * the page's own data, each with a title, its artists and "song" as its kind.
     */
    fun applePlaylist(html: String): MusicMetadata? {
        val tags = openGraphTags(html)
        val title = tags["og:title"]?.removeSuffix(" on Apple Music")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val data = appleDataRegex.find(html)?.groupValues?.get(1)
        val songs = data?.let { runCatching { appleSongs(JSONTokener(it).nextValue()) }.getOrNull() }.orEmpty()
        return MusicMetadata(title, "", ItemType.PLAYLIST, tags.image(), songs)
    }

    private val appleDataRegex = Regex("""<script type="application/json" id="serialized-server-data">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** Every song found anywhere in Apple Music's page data, in page order. */
    private fun appleSongs(value: Any?): List<MusicMetadata> = when (value) {
        is JSONObject -> {
            val isSong = value.optJSONObject("contentDescriptor")?.optString("kind") == "song"
            val title = value.optString("title")
            if (isSong && title.isNotBlank()) {
                // The cover's address has its size left for the app to fill in.
                val artwork = value.optJSONObject("artwork")?.optJSONObject("dictionary")?.optString("url")
                    ?.replace("{w}x{h}bb.{f}", "300x300bb.jpg")?.ifBlank { null }
                listOf(MusicMetadata(title, value.optString("artistName"), artworkUrl = artwork))
            } else {
                value.keys().asSequence().flatMap { appleSongs(value.opt(it)) }.toList()
            }
        }
        is JSONArray -> (0 until value.length()).flatMap { appleSongs(value.opt(it)) }
        else -> emptyList()
    }

    /** Spotify's embed page lists an album's or playlist's first songs, each with its title and artists. */
    fun spotifyEmbedTracks(html: String): List<MusicMetadata> {
        val data = spotifyDataRegex.find(html)?.groupValues?.get(1) ?: return emptyList()
        val list = JSONObject(data).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity")?.optJSONArray("trackList") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val track = list.optJSONObject(index) ?: return@mapNotNull null
            MusicMetadata(track.optString("title").ifBlank { return@mapNotNull null }, track.optString("subtitle"), url = spotifyTrackUrl(track.optString("uri")))
        }
    }

    private val spotifyDataRegex = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** Deezer API object; errors come back as {"error": {...}} with HTTP 200, see [deezerError]. */
    fun deezer(json: JSONObject, type: ItemType): MusicMetadata? {
        if (json.has("error")) return null
        val title = json.optString("title").ifEmpty { json.optString("name") }
        val artist = if (type == ItemType.TRACK || type == ItemType.ALBUM) {
            json.optJSONObject("artist")?.optString("name").orEmpty()
        } else {
            ""
        }
        // Albums have a cover and artists and playlists a picture; tracks use their album's cover.
        // The 500px size is sharp at the biggest cover Crosstune shows; 1000px only costs more data.
        val artwork = json.optString("cover_big").ifEmpty { json.optString("picture_big") }
            .ifEmpty { json.optJSONObject("album")?.optString("cover_big").orEmpty() }
            .ifBlank { null }
        // An album's or playlist's object lists its songs, up to a few hundred.
        val tracks = json.optJSONObject("tracks")?.optJSONArray("data")?.let(::deezerTracks).orEmpty()
        val count = json.optInt("nb_tracks").takeIf { it > tracks.size }
        return MusicMetadata(title, artist, type, artwork, tracks, trackCount = count).takeIf { title.isNotBlank() }
    }

    private fun deezerTracks(data: JSONArray): List<MusicMetadata> = (0 until data.length()).mapNotNull { index ->
        val track = data.optJSONObject(index) ?: return@mapNotNull null
        MusicMetadata(
            track.optString("title").ifBlank { return@mapNotNull null },
            track.optJSONObject("artist")?.optString("name").orEmpty(),
            // The 250px size: sharp in a list.
            artworkUrl = track.optJSONObject("album")?.optString("cover_medium")?.ifBlank { null }
        )
    }

    /** Audiomack oEmbed: the song, album or playlist's name, its artist and its cover. */
    fun audiomack(json: JSONObject, type: ItemType): MusicMetadata? {
        val title = json.optString("title").ifBlank { return null }
        val artist = if (type == ItemType.PLAYLIST) "" else json.optString("author_name")
        return MusicMetadata(title, artist, type, json.optString("thumbnail_url").ifBlank { null })
    }

    /** The error inside a Deezer API response, or null when there is none. */
    fun deezerError(json: JSONObject): AppError? {
        val error = json.optJSONObject("error") ?: return null
        return when (error.optInt("code")) {
            DEEZER_QUOTA_EXCEEDED -> AppError.RATE_LIMITED
            DEEZER_SERVICE_BUSY -> AppError.SERVICE_UNAVAILABLE
            DEEZER_DATA_NOT_FOUND -> AppError.NOT_FOUND
            else -> AppError.METADATA_UNAVAILABLE
        }
    }

    /** TIDAL pages title themselves "Name by Artist on TIDAL" or "Name on TIDAL". */
    fun tidal(html: String, type: ItemType): MusicMetadata? {
        val title = titleTag(html)?.replace(tidalSuffixRegex, "")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // Only songs and albums name an artist; "Death by Stereo" is an artist, not "Death" by "Stereo".
        val metadata = if (type == ItemType.TRACK || type == ItemType.ALBUM) {
            splitBy(title, " by ", type)
        } else {
            MusicMetadata(title, "", type)
        }
        return metadata.copy(artworkUrl = openGraphTags(html).image())
    }

    /** SoundCloud oEmbed: tracks and sets are "Name by Artist", artists are just the name. */
    fun soundCloud(json: JSONObject, type: ItemType): MusicMetadata? {
        val title = json.optString("title").trim().ifEmpty { return null }
        val author = json.optString("author_name").trim()
        val artwork = json.optString("thumbnail_url").ifBlank { null }
        if (type == ItemType.ARTIST) return MusicMetadata(author.ifEmpty { title }, "", type, artwork)
        val byAuthor = " by $author"
        return if (author.isNotEmpty() && title.endsWith(byAuthor)) {
            MusicMetadata(title.removeSuffix(byAuthor), author, type, artwork)
        } else {
            MusicMetadata(title, author, type, artwork)
        }
    }

    /** Bandcamp pages use og:title "Name, by Artist". */
    fun bandcamp(html: String, type: ItemType): MusicMetadata? {
        val tags = openGraphTags(html)
        val title = tags["og:title"]?.takeIf { it.isNotBlank() } ?: return null
        val tracks = if (type == ItemType.ALBUM) bandcampAlbumSongs(html) else emptyList()
        return splitBy(title, ", by ", type).copy(artworkUrl = tags.image(), tracks = tracks)
    }

    private val bandcampDataRegex = Regex("""<script type="application/ld\+json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** A Bandcamp album page's structured data lists its songs, all by the album's artist. */
    private fun bandcampAlbumSongs(html: String): List<MusicMetadata> {
        val data = bandcampDataRegex.find(html)?.groupValues?.get(1) ?: return emptyList()
        val album = runCatching { JSONObject(data) }.getOrNull() ?: return emptyList()
        val artist = album.optJSONObject("byArtist")?.optString("name").orEmpty()
        val list = album.optJSONObject("track")?.optJSONArray("itemListElement") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val name = list.optJSONObject(index)?.optJSONObject("item")?.optString("name")
            name?.ifBlank { null }?.let { MusicMetadata(it, artist) }
        }
    }

    private fun splitBy(text: String, separator: String, type: ItemType): MusicMetadata {
        val index = text.lastIndexOf(separator)
        return if (index > 0) {
            MusicMetadata(text.substring(0, index).trim(), text.substring(index + separator.length).trim(), type)
        } else {
            MusicMetadata(text, "", type)
        }
    }

    private fun Map<String, String>.image(): String? = this["og:image"]?.takeIf { it.isNotBlank() }

    private fun titleTag(html: String): String? =
        titleTagRegex.find(html)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()

    /** Collects og:* meta tags regardless of attribute order or quote style. */
    private fun openGraphTags(html: String): Map<String, String> = buildMap {
        for (tag in metaTagRegex.findAll(html)) {
            val attributes = attributeRegex.findAll(tag.value).associate { match ->
                match.groupValues[1].lowercase() to match.groupValues[2].ifEmpty { match.groupValues[3] }
            }
            val property = attributes["property"] ?: continue
            val content = attributes["content"] ?: continue
            putIfAbsent(property, decodeEntities(content).trim())
        }
    }

    fun decodeEntities(text: String): String = entityRegex.replace(text) { match ->
        val entity = match.groupValues[1]
        val codePoint = when {
            entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()
            else -> return@replace namedEntities[entity.lowercase()] ?: match.value
        }
        codePoint?.takeIf { Character.isValidCodePoint(it) }
            ?.let { String(Character.toChars(it)) }
            ?: match.value
    }
}
