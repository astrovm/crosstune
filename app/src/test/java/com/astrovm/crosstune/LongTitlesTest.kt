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
        val titles = listOf(
            // A run of line breaks, then something that isn't a space.
            "Artist - Song " + "\n".repeat(LENGTH) + ")",
            // A dash that a pass strips, again and again.
            " - ".repeat(LENGTH / 3),
            // A cover after a dash on each line but the last.
            " - Cover".repeat(LENGTH / 16) + "\n" + " - Cover".repeat(LENGTH / 16),
            "Song " + "(".repeat(LENGTH),
            "Song " + "((Lyrics)".repeat(LENGTH / 9),
            "Song " + " (Live)".repeat(LENGTH / 7),
            "Song " + " (feat. A".repeat(LENGTH / 9),
            "Song " + " ft. A".repeat(LENGTH / 6) + ")",
            "Song " + "(((Live)))".repeat(LENGTH / 10),
            "Song " + "(".repeat(LENGTH / 2) + ")".repeat(LENGTH / 2),
            // The review's slowest: a player's title of dashes, and guests that each end where the next starts.
            " - ".repeat(LENGTH / 3) + "\n",
            " ft. a  ".repeat(LENGTH / 8) + ")",
        ) + PIECES.flatMap { piece -> ENDINGS.map { ending -> piece.repeat(LENGTH / piece.length) + ending } }
        // Once, so the first call's start-up isn't timed.
        read("Artist - Song (Official Video)")
        val slow = titles.mapNotNull { title ->
            val began = System.nanoTime()
            read(title)
            val millis = (System.nanoTime() - began) / 1_000_000
            "${title.take(20).replace("\n", "\\n")}…${title.takeLast(2).replace("\n", "\\n")} ${millis}ms".takeIf { millis > LIMIT_MS }
        }
        assertEquals(emptyList<String>(), slow)
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
        const val LIMIT_MS = 300L

        /** What a title is made of where the readers look for a tag, each repeated to [LENGTH]. */
        val PIECES = listOf(
            " ", "\n", "\r\n", "\t", "-", " - ", " – ", "|", " | ", "//", ":", "(", ")", "[", "]", "（", "）", "【", "】", "「", "」",
            "『", "』", "( ", "[ ", "( )", "x ", "a by ", " ft. a ", " ft. a  ", " feat. ", "(feat. a", "2  ft. a", " M/V",
            " (M/V", " Cover", " - Cover", "\nCover", " cover of a", " in the style of a (", " Lyrics", " HD", " Official Video",
            " Video", " (Live)", "(Live) ", " - Live", " Remastered", " - Remastered 2012", " (Remastered", "Remix ", "「a」",
            "a「", "（歌：", "（歌：a）", "(歌", " sub esp", "|| sub español ", " - Song, Lyrics, and Music Video by ", "song by ",
            " | Spotify", " by ", "\u3000", "\u0085",
        )

        /** How each of [PIECES]' titles ends: as it is, on a bracket, a line break, or a word. */
        val ENDINGS = listOf("", ")", "]", "」", "\n", "\r\n", "\u0085", " x", " - Topic", " | Spotify")
    }
}
