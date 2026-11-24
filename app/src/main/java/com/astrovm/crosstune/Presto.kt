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

    /** Names alone, as a title starts with who wrote it: "Davis, M", "Taylor Swift, Jack Antonoff", not "Symphony No. 5". */
    private val names = phoneRegex("""^[\p{L}'’. &,-]+$""")

    /** "M" or "A. B.", what "Davis, M" gives after a surname. */
    private val initials = phoneRegex("""^(?:\p{L}\.?\s*+){1,3}$""")

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
     * A song as Presto names it, [title] by its [musicians], as other services do. Who wrote it comes off the title when
     * they play on it or wrote the piece, its [composer]: "Davis, M: So What" is "So What" by "Miles Davis", and
     * "Beethoven: Symphony No. 5" is "Symphony No. 5". It's by the writer who plays it, or else by its first musician.
     */
    fun song(title: String, musicians: List<String>, composer: String? = null): MusicMetadata {
        val colon = title.indexOf(": ")
        val writers = title.take(maxOf(colon, 0)).takeIf(names::matches)?.let(::writers).orEmpty()
        val player = musicians.firstOrNull { musician -> writers.any { it.isWho(musician) } }
        val wrote = player != null || composer != null && writers.any { it.isWho(composer) }
        return MusicMetadata(if (wrote) title.substring(colon + 2).trim() else title, player ?: musicians.firstOrNull().orEmpty())
    }

    /** Who a title's credit names, by surname and any initials: "Davis, M" is one, "Taylor Swift, Jack Antonoff" two. */
    private fun writers(credit: String): List<Writer> {
        val parts = credit.split(',', '&').map(String::trim).filter(String::isNotEmpty)
        if (parts.size == 2 && initials.matches(parts[1])) return listOf(Writer(parts[0], parts[1]))
        return parts.map { part ->
            // A part is trimmed, so its last word is never blank; a blank one between is no initial.
            val words = part.split(' ')
            Writer(words.last(), words.dropLast(1).filter { it.trimEnd('.').length == 1 }.joinToString(""))
        }
    }

    /** A writer, as a title's credit names them; [initials] only when it gives them. */
    private class Writer(surname: String, initials: String) {
        private val surname = SongNames.normalize(surname)
        private val initials = SongNames.normalize(initials)

        /** Whether [name] is theirs: "Al Di Meola" is "A. DiMeola", but "Bill Evans" isn't "Evans, M", and "." is no one. */
        fun isWho(name: String): Boolean =
            surname.isNotEmpty() && SongNames.normalize(name).let { it.endsWith(surname) && it.startsWith(initials) }
    }

    /** An album, as Presto's API answers for it: its name, its leader, whom Presto credits first, and its cover. */
    fun album(json: JSONObject): MusicMetadata? {
        val album = json.optJSONObject("payload") ?: return null
        val title = album.nonBlankString("title") ?: return null
        val leader = musicians(album.nonBlankString("artistSummary").orEmpty()).firstOrNull().orEmpty()
        return MusicMetadata(title, leader, ItemType.ALBUM, cover(album))
    }

    /** The medium cover, sharp in a list and on the card, or else the one it has. */
    private fun cover(album: JSONObject): String? =
        album.optJSONArray("images_sizes")?.optJSONObject(0)?.nonBlankString("medium")
            ?: album.optJSONArray("images")?.optJSONObject(0)?.nonBlankString("image")

    /**
     * The songs of [album], as Presto's API lists them, each piece's movements in turn, with its cover. A piece its leader
     * plays on is theirs, as on the album, though Presto lists a piece's musicians in another order and may spell them
     * another way: "Al DiMeola" on the album is "Al Di Meola" on his pieces.
     */
    fun tracks(json: JSONObject, album: MusicMetadata): List<MusicMetadata> =
        json.optJSONObject("payload")?.optJSONArray("works").objects().flatMap { work ->
            val musicians = work.optJSONArray("artistData").objects().mapNotNull { it.nonBlankString("artistName")?.trim() }
                .sortedByDescending { SongNames.same(it, album.artist) }
            val composer = work.nonBlankString("composerName")
            work.optJSONArray("tracks").objects().mapNotNull { track ->
                val title = track.nonBlankString("singleFieldTrackTitle") ?: track.nonBlankString("trackTitle") ?: return@mapNotNull null
                val length = track.nonBlankString("duration")?.let(MetadataParsers::clockMs)
                song(title, musicians, composer).copy(artworkUrl = album.artworkUrl, lengthMs = length)
            }
        }
}
