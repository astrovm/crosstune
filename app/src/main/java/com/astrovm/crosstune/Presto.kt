package com.astrovm.crosstune

import org.json.JSONObject

/**
 * Presto Music's albums and songs, read from the API its own website and app ask, and named as other services name them.
 * Presto credits every musician with what they play, "Miles Davis (trumpet), John Coltrane (tenor saxophone)", and
 * starts a song with who wrote it, "Davis, M: So What", which no other service writes. Other services credit an album
 * or a song to its leader, and a search for all seven of Kind of Blue's musicians finds nothing, so it's named by one.
 */
internal object Presto {
    const val API = "https://ajax-www.prestomusic.com/api/app/6"

    /** "Davis, M" or "Beethoven", before the colon: who wrote it, as Presto starts a title with them. */
    private val writer = phoneRegex("""^([\p{L}'’. -]+?)(?:,\s*+([\p{L}. -]+))?$""")

    /**
     * The musicians in Presto's credits, "Bill Evans (piano), Wiener Philharmoniker", in its order, without what they
     * play. A long credit goes on in another line, and a guest is "with" them.
     */
    fun musicians(credits: String): List<String> {
        val names = mutableListOf<String>()
        val name = StringBuilder()
        var depth = 0
        for (char in credits) {
            when {
                char == '(' -> depth++
                char == ')' -> depth = maxOf(0, depth - 1)
                depth > 0 -> Unit
                char == ',' || char == '\n' -> names += name.toString().also { name.clear() }
                else -> name.append(char)
            }
        }
        names += name.toString()
        return names.map { it.trim().removePrefix("with ").trim() }.filter { it.isNotEmpty() }
    }

    /**
     * A song as Presto names it, [title] by its [musicians], as other services do. One that starts with who wrote it, when
     * that's who plays it, as jazz does, is theirs: "Davis, M: So What" is "So What" by "Miles Davis". Otherwise, as a
     * classical work is, the title stays whole, "Beethoven: Symphony No. 5", by the first of its musicians.
     */
    fun song(title: String, musicians: List<String>): MusicMetadata {
        val colon = title.indexOf(": ")
        val credit = if (colon > 0) writer.matchEntire(title.substring(0, colon)) else null
        val leader = credit?.let { match ->
            val surname = match.groupValues[1].trim()
            val initials = match.groupValues[2].replace(".", "").trim()
            musicians.firstOrNull { it.endsWith(" $surname") && it.startsWith(initials) }
        }
        return if (leader != null) MusicMetadata(title.substring(colon + 2).trim(), leader) else MusicMetadata(title, musicians.firstOrNull().orEmpty())
    }

    /** An album, as Presto's API answers for it: its name, its leader, whom Presto credits first, and its cover. */
    fun album(json: JSONObject): MusicMetadata? {
        val album = json.optJSONObject("payload") ?: return null
        val title = album.optString("title").ifBlank { return null }
        return MusicMetadata(title, musicians(album.optString("artistSummary")).firstOrNull().orEmpty(), ItemType.ALBUM, cover(album))
    }

    /** The medium cover, sharp in a list and on the card, or else the one it has. */
    private fun cover(album: JSONObject): String? =
        album.optJSONArray("images_sizes")?.optJSONObject(0)?.nonBlankString("medium")
            ?: album.optJSONArray("images")?.optJSONObject(0)?.nonBlankString("image")

    /**
     * The songs of [album], as Presto's API lists them, each piece's movements in turn, with its cover. A piece its leader
     * plays on is theirs, as on the album, though Presto lists a piece's musicians in another order.
     */
    fun tracks(json: JSONObject, album: MusicMetadata): List<MusicMetadata> =
        json.optJSONObject("payload")?.optJSONArray("works").objects().flatMap { work ->
            val musicians = work.optJSONArray("artistData").objects().map { it.optString("artistName").trim() }.filter { it.isNotEmpty() }
                .sortedByDescending { it == album.artist }
            work.optJSONArray("tracks").objects().mapNotNull { track ->
                val title = track.nonBlankString("singleFieldTrackTitle") ?: track.nonBlankString("trackTitle") ?: return@mapNotNull null
                song(title, musicians).copy(artworkUrl = album.artworkUrl, lengthMs = MetadataParsers.clockMs(track.optString("duration")))
            }
        }
}
