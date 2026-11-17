package com.astrovm.crosstune

/** Made-up long inputs shared by the deterministic guard and the native Android timing probe. */
internal object LongTitleInputs {
    /** The worst titles found for the readers, each about [length] long. */
    fun titles(length: Int) = shapes(length) + PIECES.flatMap { piece -> ENDINGS.map { ending -> piece.repeat(length / piece.length) + ending } }

    /** The titles found worst for a reader, each about [length] long, that aren't a piece again and again. */
    private fun shapes(length: Int): List<String> {
        return listOf(
            // A run of line breaks, then something that isn't a space.
            "Artist - Song " + "\n".repeat(length) + ")",
            // A dash that a pass strips, again and again.
            " - ".repeat(length / 3),
            // A cover after a dash on each line but the last.
            " - Cover".repeat(length / 16) + "\n" + " - Cover".repeat(length / 16),
            "Song " + "(".repeat(length),
            "Song " + "((Lyrics)".repeat(length / 9),
            "Song " + " (Live)".repeat(length / 7),
            "Song " + " (feat. A".repeat(length / 9),
            "Song " + " ft. A".repeat(length / 6) + ")",
            "Song " + "(((Live)))".repeat(length / 10),
            "Song " + "(".repeat(length / 2) + ")".repeat(length / 2),
            // The review's slowest: a player's title of dashes, and guests that each end where the next starts.
            " - ".repeat(length / 3) + "\n",
            " ft. a  ".repeat(length / 8) + ")",
            // Guests and marks in each other, cut one a pass from the inside, with tags cut off the end on the same passes.
            "(feat. a".repeat(length / 9) + ")".repeat(length / 9),
            "(M/V".repeat(length / 5) + ")".repeat(length / 5),
            "(feat. a (M/V".repeat(length / 15) + "))".repeat(length / 15),
            "(feat. a".repeat(length / 18) + ")".repeat(length / 18) + " x".repeat(length / 4) + " - Remastered".repeat(length / 52),
            "Song ft. a" + " - Remaster)".repeat(length / 12),
            // The same with no tag: where the last look for one started holds, as no cut takes a letter after it.
            "(feat. a".repeat(length / 18) + ")".repeat(length / 18) + " x".repeat(length / 4),
            // The ninth review's: on a phone a look along the title each cover, tag or dash took time squared.
            "Song " + "cover ".repeat(length / 6),
            "Song - " + "cover ".repeat(length / 6),
            "|lyrics".repeat(length / 7),
            "Song" + " - a".repeat(length / 4) + " - song and lyrics by Artist | Spotify",
            // An instrumental's tags after the artist's name, ended by a separator or a conjunction and the name again, or a singer.
            "Channel" + " - Instrumental".repeat(length / 15),
            "a" + " - Instrumental & a".repeat(length / 19),
            "a" + " - Instrumental and a".repeat(length / 21),
            "Song" + " (Instrumental)".repeat(length / 15) + " ft. a",
            "Song" + " ~Instrumental".repeat(length / 14) + " ft. a",
        )
    }

    fun instrumentals(length: Int) = shapes(length) + PIECES.map { it.repeat(length / it.length) }

    fun comparisons(length: Int): List<String> =
        listOf("unknown ", "wordwordwordwordword ", "【Live】", "((Live))", "[").map { piece ->
            "Hello " + piece.repeat(length / piece.length) + " (Live)"
        } + listOf(
            "Hello - " + "English ".repeat(length / 8) + "Version",
            "Hello " + "(".repeat(length / 4) + "2".repeat(length / 2) + " Live" + ")".repeat(length / 4)
        )
    fun shares(length: Int) = listOf("a by ".repeat(length / 5) + "\nx\nhttps://www.google.com/search?q=x")

    /** The guard reads each shape at this length and four times longer. */
    const val LENGTH = 2_500

    /** What a title is made of where the readers look for a tag, each repeated to [LENGTH]. */
    private val PIECES = listOf(
        " ", "\n", "\r\n", "\t", "-", " - ", " – ", "|", " | ", "//", ":", "(", ")", "[", "]", "（", "）", "【", "】", "「", "」",
        "『", "』", "( ", "[ ", "( )", "x ", "a by ", " ft. a ", " ft. a  ", " feat. ", "(feat. a", "2  ft. a", " M/V",
        " (M/V", " Cover", " - Cover", "\nCover", " cover of a", " in the style of a (", " Lyrics", " HD", " Official Video",
        " Video", " (Live)", "(Live) ", " - Live", " Remastered", " - Remastered 2012", " (Remastered", "Remix ", "「a」",
        "a「", "（歌：", "（歌：a）", "(歌", " sub esp", "|| sub español ", " - Song, Lyrics, and Music Video by ", "song by ",
        " | Spotify", " by ", "\u3000", "\u0085", " ft. a - Remaster)", " ft. a - Remaster", "x[[4K]2 ",
    )

    /** How each of [PIECES]' titles ends: as it is, on a bracket, a line break, or a word. */
    private val ENDINGS = listOf("", ")", "]", "」", "\n", "\r\n", "\u0085", " x", " - Topic", " | Spotify")
}
