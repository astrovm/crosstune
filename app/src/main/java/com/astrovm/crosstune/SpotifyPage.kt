package com.astrovm.crosstune

internal data class TrackMetadata(val title: String, val artist: String)

/** Reads Open Graph metadata from a public Spotify page. Pure Kotlin, no Android APIs. */
internal object SpotifyPage {
    private val metaTagRegex = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
    private val attributeRegex = Regex("""([A-Za-z:_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val entityRegex = Regex("""&(#[0-9]+|#[xX][0-9A-Fa-f]+|[A-Za-z]+);""")
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " "
    )

    /** Track pages describe themselves as "Artist · Album · Song · Year". */
    fun parseTrack(html: String): TrackMetadata? {
        val tags = openGraphTags(html)
        val title = tags["og:title"]?.takeIf { it.isNotBlank() } ?: return null
        val artist = tags["og:description"]?.split(" · ")?.first()?.trim().orEmpty()
        return TrackMetadata(title, artist)
    }

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
