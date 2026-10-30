package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the screen state says is possible, from what it holds. */
class UiStateTest {
    private val song = MusicMetadata("Song", "Band")
    private val list = MusicMetadata("List", "", ItemType.PLAYLIST, tracks = listOf(song))

    @Test
    fun aListPlaysAllWhereItsAppTakesSongsOneByOneAndIsInstalledAndNotWhereThereIsNoApp() {
        val tidal = UiState(result = list, selectedDestination = Destination.Service(MusicService.TIDAL), installed = setOf(MusicService.TIDAL))
        assertTrue(tidal.canPlayAll)
        assertFalse(tidal.copy(installed = emptySet()).canPlayAll)
        // Opening in the browser, or at a template of the user's, there's no app to ask.
        assertFalse(tidal.copy(selectedDestination = Destination.Custom("x", "Mine", "https://example.com/?q={query}")).canPlayAll)
        // YouTube Music makes a queue of any list of videos, installed or not.
        assertTrue(tidal.copy(selectedDestination = Destination.Service(MusicService.YOUTUBE_MUSIC), installed = emptySet()).canPlayAll)
        assertFalse(tidal.copy(result = list.copy(tracks = emptyList())).canPlayAll)
        // An app that takes a list plays it as itself, and one that takes none but TIDAL's one by one isn't asked.
        assertFalse(tidal.copy(link = MusicLink(MusicService.TIDAL, ItemType.PLAYLIST, "1", "https://tidal.com/playlist/1")).canPlayAll)
        assertFalse(tidal.copy(selectedDestination = Destination.Service(MusicService.SPOTIFY), installed = setOf(MusicService.SPOTIFY)).canPlayAll)
    }

    @Test
    fun theWordsCanStartTheSongOnlyOnceTheyAreItsAndNotFollowedInAnAppYet() {
        val ready = UiState(
            result = song, lyricsFor = song,
            selectedDestination = Destination.Service(MusicService.YOUTUBE_MUSIC), installed = setOf(MusicService.YOUTUBE_MUSIC)
        )
        assertTrue(ready.canPlayFromLyrics)
        assertFalse(ready.copy(lyricsFor = null).canPlayFromLyrics)
        assertFalse(ready.copy(lyricsFor = song.copy(title = "Other")).canPlayFromLyrics)
        assertFalse(ready.copy(lyricsFor = song.copy(artist = "Other")).canPlayFromLyrics)
        assertFalse(ready.copy(isLoadingLyrics = true).canPlayFromLyrics)
        assertFalse(ready.copy(following = Following(PlaybackClock(0, 0), "Spotify", canSeek = true)).canPlayFromLyrics)
        // Followed, but in no app by name, it still can.
        assertTrue(ready.copy(following = Following(PlaybackClock(0, 0), null, canSeek = false)).canPlayFromLyrics)
    }

    @Test
    fun someLinksAreNotAllowedWhenCrosstuneOwnLinksAreOffOrAHostIsUnapproved() {
        val spotify = setOf(MusicService.SPOTIFY)
        assertFalse(UiState().someLinksNotAllowed)
        // Its own links off matters only for links it opens at all.
        assertFalse(UiState(ownLinksOff = true).someLinksNotAllowed)
        assertTrue(UiState(ownLinksOff = true, intercepted = spotify).someLinksNotAllowed)
        // Android 12 can say which hosts aren't allowed; before it, nothing is said.
        assertFalse(UiState(intercepted = spotify, unapprovedHosts = null).someLinksNotAllowed)
        assertFalse(UiState(intercepted = spotify, unapprovedHosts = mapOf(MusicService.SPOTIFY to emptyList())).someLinksNotAllowed)
        assertFalse(UiState(intercepted = spotify, unapprovedHosts = mapOf(MusicService.DEEZER to listOf("deezer.com"))).someLinksNotAllowed)
        assertTrue(UiState(intercepted = spotify, unapprovedHosts = mapOf(MusicService.SPOTIFY to listOf("open.spotify.com"))).someLinksNotAllowed)
    }

    private val reading = LineReading(listOf(Ruby("夜", "よる")), "よる", "yoru", typed = "yoru!")
    private val silent = LineReading(listOf(Ruby("…")), "…", "")

    @Test
    fun wordsShowAsKanaOrLatinOnlyWhereThereIsSuchAReadingAndTheScriptHasKana() {
        val learning = Learning(script = Script.JAPANESE, lines = listOf(reading, silent), wordsAs = WordsAs.KANA)
        assertEquals("よる", learning.insteadOf(0))
        assertEquals("yoru", learning.copy(wordsAs = WordsAs.LATIN).insteadOf(0))
        assertEquals("yoru!", learning.copy(wordsAs = WordsAs.LATIN, romajiTyped = true).insteadOf(0))
        // A line whose Latin letters are blank, or with no reading yet, shows as written.
        assertNull(learning.copy(wordsAs = WordsAs.LATIN).insteadOf(1))
        assertNull(learning.insteadOf(2))
        assertNull(learning.copy(wordsAs = WordsAs.WRITTEN).insteadOf(0))
        // Only Japanese has kana: the others show as written.
        assertEquals(WordsAs.WRITTEN, learning.copy(script = Script.KOREAN).showsAs)
        assertNull(learning.copy(script = Script.KOREAN).insteadOf(0))
        assertEquals("yoru", learning.copy(script = Script.KOREAN, wordsAs = WordsAs.LATIN).insteadOf(0))
    }
}
