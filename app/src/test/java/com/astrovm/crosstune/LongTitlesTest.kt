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
        assertEquals(emptyList<String>(), Growth.outOfStep(LongTitleInputs::titles, LongTitleInputs.LENGTH, ::named, ::read))
    }

    @Test
    fun noTitleTakesLongToSayIfItsAnInstrumental() {
        // Its looks are the readers', so each shape and piece once as it is, by an artist named first and by one named in each tag.
        val titles = LongTitleInputs::instrumentals
        val slow = listOf("Channel", "a").flatMap { artist ->
            Growth.outOfStep(titles, LongTitleInputs.LENGTH, ::named) { title -> SongNames.saysInstrumental(title, artist) }.map { "$artist: $it" }
        }
        assertEquals(emptyList<String>(), slow)
    }

    @Test
    fun comparingRecordingNotesTakesWorkInStepWithTheTitle() {
        assertEquals(emptyList<String>(), Growth.outOfStep(LongTitleInputs::comparisons, LongTitleInputs.LENGTH, ::named) { title ->
            SongNames.wordsMatch(title, title.replaceFirst("Hello", "Hello World"))
        })
    }

    /** How a title is named when it's out of step: how it starts and ends. */
    private fun named(title: String) = "${title.take(20).replace("\n", "\\n")}…${title.takeLast(2).replace("\n", "\\n")}"

    @Test
    fun noShareTakesLongToRead() {
        // A line that Now Playing's wordings split at many places, and that doesn't end as one does.
        assertEquals(emptyList<String>(), Growth.outOfStep(LongTitleInputs::shares, LongTitleInputs.LENGTH, ::named, MusicLinks::parse))
    }

    @Test
    fun aLongTitleIsReadAsAShortOne() {
        val title = "S".repeat(LongTitleInputs.LENGTH) + " (Acoustic Cover)"
        val song = "S".repeat(LongTitleInputs.LENGTH)
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
}
