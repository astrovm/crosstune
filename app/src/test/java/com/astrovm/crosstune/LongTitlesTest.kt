package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        )
        // Once, so the first call's start-up isn't timed.
        read("Artist - Song (Official Video)")
        val slow = titles.mapNotNull { title ->
            val began = System.nanoTime()
            read(title)
            val millis = (System.nanoTime() - began) / 1_000_000
            "${title.take(20).replace("\n", "\\n")}… ${millis}ms".takeIf { millis > LIMIT_MS }
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
    fun aTitleTooLongToBeOneIsNoSong() {
        fun titled(length: Int) = "S".repeat(length - " (Acoustic Cover)".length) + " (Acoustic Cover)"
        val long = titled(LONGEST_TITLE + 1)
        assertNull(MetadataParsers.youtubeVideo(long, "Channel"))
        assertNull(MetadataParsers.musicVideo(long, "Channel"))
        assertNull(SongNames.coverOf(long))
        assertEquals(long, SongNames.withoutEditionTag(long))
        assertEquals(long, SongNames.bareTitle(long))
        // One letter shorter is read.
        val title = titled(LONGEST_TITLE)
        val song = title.substringBefore(" (")
        assertEquals(MusicMetadata(title, "Channel"), MetadataParsers.youtubeVideo(title, "Channel"))
        assertEquals(song, SongNames.coverOf(title)?.title)
        assertEquals(song, SongNames.bareTitle(title))
    }

    private fun read(title: String) {
        MetadataParsers.youtubeVideo(title, "Channel")
        MetadataParsers.musicVideo(title, "Channel")
        SongNames.coverOf(title)
        SongNames.bareTitle(title)
        SongNames.withoutEditionTag(title)
    }

    private companion object {
        const val LENGTH = 20_000
        const val LIMIT_MS = 300L
    }
}
