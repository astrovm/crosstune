package com.astrovm.crosstune

import org.json.JSONObject

/** What Crosstune knows about an item; [artist] is blank for artists and playlists. */
internal data class MusicMetadata(
    val title: String,
    val artist: String,
    val type: ItemType = ItemType.TRACK
)

/** Turns each service's page, oEmbed or API response into [MusicMetadata]. Returns null when unusable. */
internal object MetadataParsers {
    private val metaTagRegex = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
    private val titleTagRegex = Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)
    private val attributeRegex = Regex("""([A-Za-z:_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val tidalSuffixRegex = Regex("""(^|\s)on TIDAL$""")
    private val entityRegex = Regex("""&(#[0-9]+|#[xX][0-9A-Fa-f]+|[A-Za-z]+);""")
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " "
    )

    /** Video titles often carry release noise such as "(Official Video)" or "[Lyrics]". */
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
        return MusicMetadata(title, artist, type)
    }

    /** YouTube oEmbed: "Artist - Song (Official Video)" by "ArtistVEVO", or "Song" by "Artist - Topic". */
    fun youtube(json: JSONObject): MusicMetadata? {
        val rawTitle = json.optString("title").replace(videoNoiseRegex, "").trim().ifEmpty { return null }
        val author = json.optString("author_name").removeSuffix(" - Topic").removeSuffix("VEVO").trim()
        val separator = rawTitle.indexOf(" - ")
        return if (separator > 0) {
            MusicMetadata(rawTitle.substring(separator + 3).trim(), rawTitle.substring(0, separator).trim())
        } else {
            MusicMetadata(rawTitle, author)
        }
    }

    /** iTunes Lookup API result for a song, album or artist. */
    fun appleMusic(json: JSONObject, type: ItemType): MusicMetadata? {
        val result = json.optJSONArray("results")?.optJSONObject(0) ?: return null
        val artist = result.optString("artistName")
        return when (type) {
            ItemType.TRACK -> MusicMetadata(result.optString("trackName"), artist, type)
            ItemType.ALBUM -> MusicMetadata(
                result.optString("collectionName").removeSuffix(" - Single").removeSuffix(" - EP"), artist, type
            )
            else -> MusicMetadata(artist, "", type)
        }.takeIf { it.title.isNotBlank() }
    }

    /** Apple Music playlist pages title themselves "Name on Apple Music". */
    fun applePlaylist(html: String): MusicMetadata? =
        openGraphTags(html)["og:title"]?.removeSuffix(" on Apple Music")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { MusicMetadata(it, "", ItemType.PLAYLIST) }

    /** Deezer API object; errors come back as {"error": {...}} with HTTP 200. */
    fun deezer(json: JSONObject, type: ItemType): MusicMetadata? {
        if (json.has("error")) return null
        val title = json.optString("title").ifEmpty { json.optString("name") }
        val artist = if (type == ItemType.TRACK || type == ItemType.ALBUM) {
            json.optJSONObject("artist")?.optString("name").orEmpty()
        } else {
            ""
        }
        return MusicMetadata(title, artist, type).takeIf { title.isNotBlank() }
    }

    /** TIDAL pages title themselves "Name by Artist on TIDAL" or "Name on TIDAL". */
    fun tidal(html: String, type: ItemType): MusicMetadata? {
        val title = titleTag(html)?.replace(tidalSuffixRegex, "")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return splitBy(title, " by ", type)
    }

    /** SoundCloud oEmbed: tracks and sets are "Name by Artist", artists are just the name. */
    fun soundCloud(json: JSONObject, type: ItemType): MusicMetadata? {
        val title = json.optString("title").trim().ifEmpty { return null }
        val author = json.optString("author_name").trim()
        if (type == ItemType.ARTIST) return MusicMetadata(author.ifEmpty { title }, "", type)
        val byAuthor = " by $author"
        return if (author.isNotEmpty() && title.endsWith(byAuthor)) {
            MusicMetadata(title.removeSuffix(byAuthor), author, type)
        } else {
            MusicMetadata(title, author, type)
        }
    }

    /** Bandcamp pages use og:title "Name, by Artist". */
    fun bandcamp(html: String, type: ItemType): MusicMetadata? {
        val title = openGraphTags(html)["og:title"]?.takeIf { it.isNotBlank() } ?: return null
        return splitBy(title, ", by ", type)
    }

    private fun splitBy(text: String, separator: String, type: ItemType): MusicMetadata {
        val index = text.lastIndexOf(separator)
        return if (index > 0) {
            MusicMetadata(text.substring(0, index).trim(), text.substring(index + separator.length).trim(), type)
        } else {
            MusicMetadata(text, "", type)
        }
    }

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
