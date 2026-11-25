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

    fun albumUrl(code: String): String = "https://streaming.prestomusic.com/album/$code"

    /** Names alone, as a title starts with who wrote it: "Davis, M", "Taylor Swift, Jack Antonoff", not "Symphony No. 5". */
    private val names = phoneRegex("""^[\p{L}'’. &,-]+$""")

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

    fun leader(credits: String): String = musicians(credits).firstOrNull().orEmpty()

    private val creditSeparators = phoneRegex("""[^\p{L}\p{N}\p{M}]+""")

    private fun genericCredit(name: String): Boolean =
        MetadataParsers.noArtist(name) || MetadataParsers.noArtist(name.replace(creditSeparators, " "))

    /** Album credits from other services. Do not split band names on "&" or "and". */
    fun albumArtists(credit: String): List<String>? {
        val names = credit.split(", ")
        if (names.any(::genericCredit)) return null
        return names.map(SongNames::normalize).takeUnless { it.any(String::isEmpty) }
    }

    /** The first artist must agree. Extra named credits can tell apart two albums by the same ensemble. */
    fun matchesAlbumArtists(wanted: List<String>, credits: String): Boolean {
        val names = musicians(credits)
        val lead = names.firstOrNull() ?: return false
        return SongNames.normalize(lead) == wanted.first() && !genericCredit(lead) && names.map(SongNames::normalize).containsAll(wanted)
    }

    /**
     * A song as Presto names it, [title] by its [musicians], as other services do. Who wrote it comes off the title when
     * they play on it or wrote the piece, its [composer]: "Davis, M: So What" is "So What" by "Miles Davis", and
     * "Beethoven: Symphony No. 5" is "Symphony No. 5". It's by the writer who plays it, or else by its first musician.
     */
    fun song(title: String, musicians: List<String>, composer: String? = null): MusicMetadata {
        val colon = title.indexOf(": ")
        val writers = title.take(maxOf(colon, 0)).replace('\u00a0', ' ').takeIf(names::matches)?.let(::writers).orEmpty()
        val player = musicians.firstOrNull { musician -> writers.any { it.isWho(musician) } }
        val wrote = player != null || composer != null && writers.any { it.isWho(composer) }
        return MusicMetadata(if (wrote) title.substring(colon + 2).trim() else title, player ?: musicians.firstOrNull().orEmpty())
    }

    /**
     * Who a title's credit names, by surname and the names or initials before it: "Davis, M" is one, "Taylor Swift, Jack
     * Antonoff" two, "Lennon, May" two, and "Davis, M & Evans, B" two, each with their initial.
     */
    private fun writers(credit: String): List<Writer> = credit.split('&').flatMap { group ->
        val parts = group.split(',').map(String::trim).filter(String::isNotEmpty)
        // After a surname, initials and the particles that are the surname's: "Bach, J. S.", "Bach, JS", "Beethoven, L. van".
        val after = parts.getOrNull(1)?.let(::words).orEmpty().flatMap(::initials)
        val initialsOnly = after.isNotEmpty() && after.all { it.length == 1 || isParticle(it) }
        if (parts.size == 2 && initialsOnly) {
            listOf(Writer(parts[0], after.filter(::isParticle), after.filterNot(::isParticle)))
        } else {
            parts.map { part ->
                // The last word is the surname, hyphen and all, as in "Smith-Jones"; "van" and "di" before it may be the
                // surname's too, as in "van Halen", and the names before them, however they're joined, as in "J.-P.".
                val words = part.split(' ', '.').filter(String::isNotBlank)
                val before = words.dropLast(1)
                val given = before.dropLastWhile(::isParticle)
                Writer(words.lastOrNull().orEmpty(), before.drop(given.size), given.flatMap(::words))
            }
        }
    }

    /** "van", "di" or "Van", as in "Van Halen", but not "v.", an initial as in "L. v. Beethoven". */
    private fun isParticle(word: String) = word.length > 1 && (word.all(Char::isLowerCase) || word.lowercase() in particles)

    /** Particles a surname starts with, capitalized as often as not. */
    private val particles = setOf("van", "von", "der", "den", "de", "di", "da", "del", "della", "du", "la", "le", "ter", "ten")

    /** "JS" is two initials, as "J. S." is, but "May" is a name and "VAN" a particle. */
    private fun initials(word: String) =
        if (word.length in 2..3 && word.all(Char::isUpperCase) && !isParticle(word)) word.map(Char::toString) else listOf(word)

    /** A name's words, however they're joined: "J.S. Bach" and "Jean-Pierre Rampal" are three each. */
    private fun words(name: String): List<String> = name.split(' ', '\u00a0', '.', '-').filter(String::isNotBlank)

    /** A writer, as a title's credit names them: a surname, the particles before it, and the names or initials before those. */
    private class Writer(surname: String, particles: List<String>, given: List<String>) {
        private val surname = SongNames.normalize(surname)
        private val particles = names(particles)
        private val givens = readings(given)

        /**
         * Whether [name] is theirs: its last words are the surname, whole, and the names before match the credit's, an
         * initial its name's first letter. "Al Di Meola" is "A. DiMeola", "J.S. Bach" is "Johann Sebastian Bach", and
         * "Eddie van Halen" is "van Halen", but "Bill Evans" isn't "Evans, M", "Timothy Swift" isn't "Taylor Swift",
         * "Jacques Offenbach" isn't "Bach", and "." is no one.
         */
        fun isWho(name: String): Boolean = readings(words(name)).any { words ->
            // A particle is the surname's, as in "Eddie Van Halen", or else a name of its own, as in "V. Morrison".
            givens.any { given ->
                isWho(words, given, particles.joinToString("") { it.text } + surname) || isWho(words, given + particles, surname)
            }
        }

        private fun isWho(words: List<Name>, given: List<Name>, surname: String) = words.indices.any { first ->
            words.drop(first).joinToString("") { it.text } == surname && given.size <= first && given.indices.all { given[it].isLike(words[it]) }
        }
    }

    /**
     * A name's words as written and with short capitals split, as "LEE" may be a name or three initials. Read as a name,
     * "JS" is whole: it isn't "J", or else "JS Bach" would be "J. C. Bach".
     */
    private fun readings(words: List<String>): List<List<Name>> =
        listOf(names(words, whole = { initials(it).size > 1 }), names(words.flatMap(::initials)))

    private fun names(words: List<String>, whole: (String) -> Boolean = { false }) =
        words.map { Name(SongNames.normalize(it), whole(it)) }.filter { it.text.isNotEmpty() }

    /** One of a name's words, which a single letter is the initial of, unless it's [whole]. */
    private class Name(val text: String, private val whole: Boolean) {
        /** The same name, or one's initial: "J" is "Johann", but "Taylor" isn't "Timothy". */
        fun isLike(other: Name) =
            text == other.text || !whole && !other.whole && minOf(text.length, other.text.length) == 1 && text[0] == other.text[0]
    }

    /** An album, as Presto's API answers for it: its name, its leader, whom Presto credits first, and its cover. */
    fun album(json: JSONObject): MusicMetadata? {
        val album = json.optJSONObject("payload") ?: return null
        val title = album.nonBlankString("title") ?: return null
        val leader = leader(album.nonBlankString("artistSummary").orEmpty())
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
