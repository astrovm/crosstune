package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Titles that read worse than on main in a review, each read here as main read it or better. [TitlesAgainstMainTest]
 * finds such titles at random; these are the ones found, kept by name.
 */
class TitlesReadAsMainTest {
    @Test
    fun aCoverAfterADashIsReadOnTheLastLineAndBeforeItsLineBreak() {
        assertEquals(SongNames.Covered("Song", null), SongNames.coverOf("Song - Acoustic\nCover"))
        assertEquals(SongNames.Covered("Song", null), SongNames.coverOf("Song - Acoustic Cover\r\n"))
        // Not a cover on a line before the last.
        assertNull(SongNames.coverOf("Song - Acoustic Cover\nLive"))
    }

    @Test
    fun aBlankBracketNamesNoSong() {
        assertNull(MetadataParsers.bracketedSong("Artist「 」"))
        assertNull(MetadataParsers.bracketedSong("Artist『　』"))
        // The next one does.
        assertEquals("Artist「 」 Band" to "Song", MetadataParsers.bracketedSong("Artist「 」 Band「Song」"))
        val title = "/ \n 「 」 \n in the style of Q"
        assertEquals(MusicMetadata(title, ""), MetadataParsers.playerSong(title, ""))
    }

    @Test
    fun aGuestAfterANumberIsCutFromTheLastSpaceBeforeIt() {
        assertEquals("Song 2 ", SongNames.withoutFeaturing("Song 2  ft. X"))
        assertEquals("Song 2\t", SongNames.withoutFeaturing("Song 2\t ft. X"))
        assertEquals("Song 2\n", SongNames.withoutFeaturing("Song 2\n ft. X"))
        // A height is no guest.
        assertEquals("6 ft. Under", SongNames.withoutFeaturing("6 ft. Under"))
    }

    @Test
    fun bracketsThatCloseOneOfAnotherKindAreReadOneAtATime() {
        assertEquals(setOf("artist", "live", "song"), SongNames.words(outsideBrackets("Artist - Song （］「『[］)Live - 」/")))
        // A tag in a tag, each closed by its own kind, is one tag.
        assertEquals(setOf("artist", "song"), SongNames.words(outsideBrackets("Artist - Song (Live [2019])")))
    }

    @Test
    fun aTagThatClosesOnAnotherKindKeepsTheGuestAfterIt() {
        val title = "Artist - Song [Official Video :(] (feat. X) :)"
        assertEquals(MusicMetadata("Song (feat. X) :)", "Artist"), MetadataParsers.youtubeVideo(title, "Channel"))
        assertEquals(MusicMetadata("Song (feat. X) :)", "Artist"), MetadataParsers.musicVideo(title, "Channel"))
    }

    @Test
    fun aCreditIsCutWithTheSpacesAPatternReads() {
        // A full-width space isn't one, as main had it.
        assertEquals(listOf("夜に駆ける　（歌：YOASOBI）", "夜に駆ける　"), chordWikiTitles("夜に駆ける　（歌：YOASOBI）"))
        // A line break that ends the title stays, after the credit.
        assertEquals(listOf("夜に駆ける （歌：YOASOBI）\u0085", "夜に駆ける\u0085"), chordWikiTitles("夜に駆ける （歌：YOASOBI）\u0085"))
        // A credit in a bracket that closes on another kind runs from the first bracket after the close before it.
        assertEquals(listOf("Song (A) （歌：B (C）", "Song (A)"), chordWikiTitles("Song (A) （歌：B (C）"))
    }

    @Test
    fun aLongShareIsReadAsAShortOne() {
        val song = "S".repeat(LONG)
        val share = "$song by Artist\nhttps://www.google.com/search?q=$song+by+Artist"
        assertEquals(MusicMetadata(song, "Artist"), (MusicLinks.parse(share) as? LinkInput.RecognizedSong)?.metadata)
    }

    @Test
    fun aSpotifyPageIsATitleOfOneLine() {
        assertEquals(MusicMetadata("Song", "Artist"), MetadataParsers.musicVideo("Song - song and lyrics by Artist | Spotify", ""))
        // One that goes on past its end is a video's "Artist - Song".
        assertEquals(
            MusicMetadata("song and lyrics by Artist | Spotify\u0085", "Song"), MetadataParsers.musicVideo("Song - song and lyrics by Artist | Spotify\u0085", "")
        )
        assertEquals(MusicMetadata("Song", "Artist"), MetadataParsers.playerSong("song and lyrics by Artist | Spotify", "Song"))
        // A description over two lines is no page's: the player's title and artist, as they are.
        assertEquals(MusicMetadata("song and lyrics by \nArtist | Spotify", "Song"), MetadataParsers.playerSong("song and lyrics by \nArtist | Spotify", "Song"))
    }

    private companion object {
        const val LONG = 2_000
    }
}
