package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A title comes from a player, a page or a share, and can be anything, very long too. Reading one takes time
 * in step with its length at most, however its patterns and the loops that strip its tags meet: here, each
 * of the functions that read titles on the worst titles found for them.
 */
class LongTitlesTest {
    @Test
    fun noTitleTakesLongToRead() {
        assertEquals(emptyList<String>(), Growth.outOfStep(::titles, LENGTH, ::named, ::read))
    }

    @Test
    fun noTitleTakesLongToSayIfItsAnInstrumental() {
        // Its looks are the readers', so each shape and piece once as it is, by an artist named first and by one named in each tag.
        val titles = { length: Int -> shapes(length) + PIECES.map { it.repeat(length / it.length) } }
        val slow = listOf("Channel", "a").flatMap { artist ->
            Growth.outOfStep(titles, LENGTH, ::named) { title -> SongNames.saysInstrumental(title, artist) }.map { "$artist: $it" }
        }
        assertEquals(emptyList<String>(), slow)
    }

    /** How a title is named when it's out of step: how it starts and ends. */
    private fun named(title: String) = "${title.take(20).replace("\n", "\\n")}…${title.takeLast(2).replace("\n", "\\n")}"

    /** The worst titles found for the readers, each about [length] long. */
    private fun titles(length: Int) = shapes(length) + PIECES.flatMap { piece -> ENDINGS.map { ending -> piece.repeat(length / piece.length) + ending } }

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

    @Test
    fun noShareTakesLongToRead() {
        // A line that Now Playing's wordings split at many places, and that doesn't end as one does.
        val shares = { length: Int -> listOf("a by ".repeat(length / 5) + "\nx\nhttps://www.google.com/search?q=x") }
        assertEquals(emptyList<String>(), Growth.outOfStep(shares, LENGTH, ::named, MusicLinks::parse))
    }

    @Test
    fun aLongTitleIsReadAsAShortOne() {
        val title = "S".repeat(LENGTH) + " (Acoustic Cover)"
        val song = "S".repeat(LENGTH)
        assertEquals(MusicMetadata(title, "Channel"), MetadataParsers.youtubeVideo(title, "Channel"))
        assertEquals(null, MetadataParsers.musicVideo(title, "Channel"))
        assertEquals(MusicMetadata(title, ""), MetadataParsers.playerSong(title, ""))
        assertEquals(song, SongNames.coverOf(title)?.title)
        assertEquals(song, SongNames.bareTitle(title))
        assertEquals(title, SongNames.withoutEditionTag(title))
    }

    private fun read(title: String) {
        MetadataParsers.youtubeVideo(title, "Channel")
        MetadataParsers.musicVideo(title, "Channel")
        MetadataParsers.playerSong(title, "")
        MetadataParsers.bracketedSong(title)
        SongNames.coverOf(title)
        SongNames.bareTitle(title)
        SongNames.withoutEditionTag(title)
        SongNames.withoutFeaturing(title)
        chordWikiTitles(title)
        outsideBrackets(title)
    }

    private companion object {
        /**
         * How long a title is read, and then [Growth.TIMES] it. Here the patterns are written out in what a phone's ICU
         * reads, which the JVM reads two to three times slower than they were: the slowest title, " HD" again and again,
         * takes about 300 ms at 10,000 letters on a quiet machine, and on a busy one twice that, which is why the test
         * holds each reader to its growth and not to a time. On an emulator, as on a phone, the same title takes 148 ms
         * at 10,000 letters, where main took 3,597 ms. `scripts/titlefuzz/baseline.py --long` times them there, where a
         * look with a pattern costs the title's length.
         */
        const val LENGTH = 2_500

        /** What a title is made of where the readers look for a tag, each repeated to [LENGTH]. */
        val PIECES = listOf(
            " ", "\n", "\r\n", "\t", "-", " - ", " – ", "|", " | ", "//", ":", "(", ")", "[", "]", "（", "）", "【", "】", "「", "」",
            "『", "』", "( ", "[ ", "( )", "x ", "a by ", " ft. a ", " ft. a  ", " feat. ", "(feat. a", "2  ft. a", " M/V",
            " (M/V", " Cover", " - Cover", "\nCover", " cover of a", " in the style of a (", " Lyrics", " HD", " Official Video",
            " Video", " (Live)", "(Live) ", " - Live", " Remastered", " - Remastered 2012", " (Remastered", "Remix ", "「a」",
            "a「", "（歌：", "（歌：a）", "(歌", " sub esp", "|| sub español ", " - Song, Lyrics, and Music Video by ", "song by ",
            " | Spotify", " by ", "\u3000", "\u0085", " ft. a - Remaster)", " ft. a - Remaster", "x[[4K]2 ",
        )

        /** How each of [PIECES]' titles ends: as it is, on a bracket, a line break, or a word. */
        val ENDINGS = listOf("", ")", "]", "」", "\n", "\r\n", "\u0085", " x", " - Topic", " | Spotify")
    }
}
