package com.astrovm.crosstune

/**
 * Each reader's time on the long titles that took longest, at three lengths, on the engine it runs on: on an emulator,
 * ICU, as on a phone. A time that grows four times as the title doubles is time squared. `baseline.py --long` runs it,
 * on main or on any commit, as LongTitlesTest can't: on the JVM the patterns are written out and run slower.
 */
object LongTitlesProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val readers = listOf<Pair<String, (String) -> Any?>>(
            "musicVideo" to { t -> TitleProbes.musicVideo(t, "Channel") },
            "youtubeVideo" to { t -> TitleProbes.youtubeVideo(t, "Channel") },
            "playerSong" to { t -> TitleProbes.playerSong(t, "") },
            "bracketedSong" to { t -> TitleProbes.bracketedSong(t) },
            "coverOf" to { t -> TitleProbes.coverOf(t) },
            "bareTitle" to { t -> TitleProbes.bareTitle(t) },
            "withoutEditionTag" to { t -> TitleProbes.withoutEditionTag(t) },
            "withoutFeaturing" to { t -> TitleProbes.withoutFeaturing(t) },
            "chordWikiTitles" to { t -> TitleProbes.chordWikiTitles(t) },
        )
        if (args.firstOrNull() == "instrumental") {
            // Whether a title of tags says it's an instrumental, by an artist named first and by one named in each tag.
            val says = listOf("Channel", "a").map { artist -> artist to { t: String -> TitleProbes.saysInstrumental(t, artist) } }
            for (piece in INSTRUMENTAL) {
                for (length in listOf(2_500, 5_000, 10_000)) {
                    val title = piece.first + piece.second.repeat(length / piece.second.length) + piece.third
                    says.forEach { (_, read) -> read(title.take(500)) }
                    println("'${piece.first}'+'${piece.second}'+'${piece.third}' $length " + says.joinToString(" ") { (artist, read) ->
                        val began = System.nanoTime(); read(title); "$artist=${(System.nanoTime() - began) / 1_000_000}"
                    })
                }
            }
            return
        }
        if (args.firstOrNull() == "all") {
            // Each of LongTitlesTest's shapes once, at 10,000 letters: only the readers slower than 50 ms are named.
            for (title in SHAPES + PIECES.flatMap { piece -> ENDINGS.map { ending -> piece.repeat(LONG / piece.length) + ending } }) {
                readers.forEach { (_, read) -> read(title.take(500)) }
                val slow = readers.mapNotNull { (name, read) ->
                    val began = System.nanoTime()
                    read(title)
                    ((System.nanoTime() - began) / 1_000_000).takeIf { it > SLOW_MS }?.let { "$name=$it" }
                }
                if (slow.isNotEmpty()) println("'${title.take(16).replace("\n", "\\n")}'…'${title.takeLast(10).replace("\n", "\\n")}' ${slow.joinToString(" ")}")
            }
            println("done")
            return
        }
        for ((piece, end) in listOf(" - " to " | Spotify", " - Live" to " | Spotify", " HD" to "", " Lyrics" to "", "(" to ")")) {
            for (length in listOf(2_500, 5_000, 10_000)) {
                val title = piece.repeat(length / piece.length) + end
                readers.forEach { (_, read) -> read(title.take(500)) }
                println("'$piece'+'$end' $length " + readers.joinToString(" ") { (name, read) ->
                    val began = System.nanoTime(); read(title); "$name=${(System.nanoTime() - began) / 1_000_000}"
                })
            }
        }
    }

    private const val LONG = 10_000
    private const val SLOW_MS = 50

    /** LongTitlesTest's titles, at [LONG] letters. */
    private val SHAPES = listOf(
        "Artist - Song " + "\n".repeat(LONG) + ")", " - ".repeat(LONG / 3), " - Cover".repeat(LONG / 16) + "\n" + " - Cover".repeat(LONG / 16),
        "Song " + "cover ".repeat(LONG / 6), "Song - " + "cover ".repeat(LONG / 6), "Song " + "(".repeat(LONG), "Song " + "((Lyrics)".repeat(LONG / 9),
        "Song " + " (Live)".repeat(LONG / 7), "Song " + " (feat. A".repeat(LONG / 9), "Song " + " ft. A".repeat(LONG / 6) + ")",
        "Song " + "(((Live)))".repeat(LONG / 10), " ft. a  ".repeat(LONG / 8) + ")", "(feat. a".repeat(LONG / 9) + ")".repeat(LONG / 9),
        "(M/V".repeat(LONG / 5) + ")".repeat(LONG / 5), "Song ft. a" + " - Remaster)".repeat(LONG / 12),
        "Song" + "|lyrics".repeat(LONG / 7), "Song" + " - ".repeat(LONG / 3) + "song and lyrics by Artist | Spotify",
    )

    /** LongTitlesTest's titles of an instrumental's tags: what starts them, what's repeated, and what ends them. */
    private val INSTRUMENTAL = listOf(
        Triple("Channel", " - Instrumental", ""), Triple("a", " - Instrumental & a", ""), Triple("a", " - Instrumental and a", ""),
        Triple("Song", " (Instrumental)", " ft. a"), Triple("Song", " ~Instrumental", " ft. a"), Triple("", " - ", ""),
    )

    /** LongTitlesTest's pieces and endings. */
    private val PIECES = listOf(
        " ", "\n", "\r\n", "\t", "-", " - ", " – ", "|", " | ", "//", ":", "(", ")", "[", "]", "（", "）", "【", "】", "「", "」",
        "『", "』", "( ", "[ ", "( )", "x ", "a by ", " ft. a ", " ft. a  ", " feat. ", "(feat. a", "2  ft. a", " M/V",
        " (M/V", " Cover", " - Cover", "\nCover", " cover of a", " in the style of a (", " Lyrics", " HD", " Official Video",
        " Video", " (Live)", "(Live) ", " - Live", " Remastered", " - Remastered 2012", " (Remastered", "Remix ", "「a」",
        "a「", "（歌：", "（歌：a）", "(歌", " sub esp", "|| sub español ", " - Song, Lyrics, and Music Video by ", "song by ",
        " | Spotify", " by ", "\u3000", "\u0085", " ft. a - Remaster)", " ft. a - Remaster", "x[[4K]2 ", " cover", "cover ",
    )
    private val ENDINGS = listOf("", ")", "]", "」", "\n", "\r\n", "\u0085", " x", " - Topic", " | Spotify")
}
