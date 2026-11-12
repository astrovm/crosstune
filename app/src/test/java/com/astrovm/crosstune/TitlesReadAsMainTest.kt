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
        // The next one does, read as if the blank one wasn't there, as main read that title.
        assertEquals("Artist Band" to "Song", MetadataParsers.bracketedSong("Artist「 」 Band「Song」"))
        // A blank one main didn't read the song in stays: it read the song before it, or none past its first line.
        assertEquals("" to "x『 ", MetadataParsers.bracketedSong("「x『 」"))
        assertEquals("" to "『", MetadataParsers.bracketedSong("「『」」"))
        assertEquals("A「」" to "x", MetadataParsers.bracketedSong("A「」「 」『x』"))
        assertNull(MetadataParsers.bracketedSong("A「x"))
        // Past the first line, one after only spaces is read, as main's pattern reached it; one after a word isn't.
        assertEquals("A" to "x", MetadataParsers.bracketedSong("A\n「 」『x』"))
        assertNull(MetadataParsers.bracketedSong("A\nB「 」『x』"))
        assertEquals(MusicMetadata("/ \n  \n in the style of Q", ""), MetadataParsers.playerSong("/ \n 「 」 \n in the style of Q", ""))
    }

    @Test
    fun aGuestBeforeAnEndTagGoesWithTheTag() {
        // A tag led by a dash that closes a bracket it didn't open, "- Remastered 2011)", leaves the guest at the end.
        for (title in listOf("Song ft. X - Remastered 2011)", "Song feat. X - Remastered)", "Song ft. X - 2011 Remaster]", "Song featuring X - Single Version)")) {
            assertEquals(title, "Song", SongNames.withoutEditionTag(title))
        }
        assertEquals("Song\n", SongNames.withoutEditionTag("Song ft. X - Remastered 2011)\n"))
        assertEquals("Song (Live)", SongNames.withoutEditionTag("Song ft. X - Remastered (Live)"))
    }

    @Test
    fun aLetterThatIsOneOfAToZIgnoringCaseIsALetter() {
        // The Kelvin sign is "k", "ſ" is "s", "ı" and "İ" are "i", to a pattern that ignores case, as main's did.
        for (title in listOf("Song 4\u212A", "Song - Lyric\u017F", "Song Lyr\u0131cs", "Song Lyr\u0130cs")) {
            assertEquals(title, MusicMetadata("Song", "Channel"), MetadataParsers.youtubeVideo(title, "Channel"))
        }
        assertEquals(MusicMetadata("Song H\u0131", "Channel"), MetadataParsers.youtubeVideo("Song H\u0131", "Channel"))
    }

    @Test
    fun aTitleEndsBeforeTheLineBreakAPatternEndsBefore() {
        // "\u0085" ends a line to a pattern's end, and isn't a space to trim; the others are both.
        assertEquals(MusicMetadata("Song\u0085", "Artist"), MetadataParsers.youtubeVideo("Artist - Song | Lyrics |HD|\u0085", "Channel"))
        assertEquals(MusicMetadata("Song\u0085", "Artist"), MetadataParsers.youtubeVideo("Artist - Song -\u0085", "Channel"))
        for (end in listOf("\u2028", "\u2029", "\r\n")) {
            assertEquals(MusicMetadata("Song", "Artist"), MetadataParsers.youtubeVideo("Artist - Song | Lyrics |HD|$end", "Channel"))
        }
        assertEquals(MusicMetadata("Song", "Artist"), MetadataParsers.youtubeVideo("Artist - Song 4K\u2028", "Channel"))
        // Not one with more after it.
        assertEquals(MusicMetadata("Song | Lyrics |HD|\u0085x", "Artist"), MetadataParsers.youtubeVideo("Artist - Song | Lyrics |HD|\u0085x", "Channel"))
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

    @Test
    fun aSpotifyPageIsReadOnTheLineItsSongClosesOn() {
        // The lines after a song in 「」 say nothing of it: it's read as main read the title without them.
        val page = "Song - song and lyrics by Artist『x』 | Spotify"
        for (title in listOf(page, "$page\nmore")) {
            assertEquals(title, MusicMetadata("Song", "Artist『x』"), MetadataParsers.musicVideo(title, "Channel"))
            assertEquals(title, MusicMetadata("x", "Song - song and lyrics by Artist"), MetadataParsers.playerSong(title, ""))
            assertEquals(title, MusicMetadata("x", "Song - song and lyrics by Artist"), MetadataParsers.youtubeVideo(title, "Channel"))
        }
    }

    private companion object {
        const val LONG = 2_000
    }
}
