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
        // Each once, shorter, so that what's timed is the reading and not the start-up of the code that reads it.
        titles(LENGTH / 10).forEach(::read)
        val slow = titles(LENGTH).mapNotNull { title ->
            val began = System.nanoTime()
            read(title)
            val millis = (System.nanoTime() - began) / 1_000_000
            "${title.take(20).replace("\n", "\\n")}…${title.takeLast(2).replace("\n", "\\n")} ${millis}ms".takeIf { millis > LIMIT_MS }
        }
        assertEquals(emptyList<String>(), slow)
    }

    /** The worst titles found for the readers, each about [length] long. */
    private fun titles(length: Int): List<String> {
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
        ) + PIECES.flatMap { piece -> ENDINGS.map { ending -> piece.repeat(length / piece.length) + ending } }
    }

    @Test
    fun noShareTakesLongToRead() {
        // A line that Now Playing's wordings split at many places, and that doesn't end as one does.
        val share = "a by ".repeat(LENGTH / 5) + "\nx\nhttps://www.google.com/search?q=x"
        MusicLinks.parse("Song by Artist\nhttps://www.google.com/search?q=Song+Artist")
        val began = System.nanoTime()
        MusicLinks.parse(share)
        assertEquals(true, (System.nanoTime() - began) / 1_000_000 <= LIMIT_MS)
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
        const val LENGTH = 20_000
        /**
         * Here the patterns are written out in what a phone's ICU reads, which the JVM reads two to three times slower
         * than they were: 374 ms for the slowest title, " HD" again and again. On an emulator, as on a phone, the same
         * title takes 148 ms at half the length, where main took 3,597 ms. `scripts/titlefuzz/baseline.py --long` times
         * them there, where a look with a pattern costs the title's length.
         */
        const val LIMIT_MS = 600L

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
