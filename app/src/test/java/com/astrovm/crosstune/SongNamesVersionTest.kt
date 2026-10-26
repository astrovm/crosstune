package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongNamesVersionTest {

    @Test
    fun aVersionSaidOtherwiseIsTheSameVersion() {
        val remix = "Twilight Bay City (Remix)"
        assertTrue(SongNames.sameVersion(remix, "Twilight Bay City (Remix)"))
        // In a tag of its own, or its album.
        assertTrue(SongNames.sameVersion(remix, "Twilight Bay City (2022 Remix)"))
        assertTrue(SongNames.sameVersion(remix, "Twilight Bay City", "NEVER ENDING SUMMER REMIX"))
        assertTrue(SongNames.sameVersion(remix, "Twilight Bay City - Remix"))
        // A remaster isn't another version, either way round.
        assertTrue(SongNames.sameVersion("Asphalt Lady (2018 Remix) (2024 Remaster)", "Asphalt Lady", "Never Ending Summer Remix 2018"))
        // The original, or another song, isn't it.
        assertFalse(SongNames.sameVersion(remix, "Twilight Bay City", "NEVER ENDING SUMMER"))
        assertFalse(SongNames.sameVersion(remix, "Twilight Bay City"))
        assertFalse(SongNames.sameVersion(remix, "Midnight Bay City (Remix)"))
        assertFalse(SongNames.sameVersion("Song (Live)", "Song (Remix)", "Remixes"))
        // Without a version in the name, only the same name is.
        assertFalse(SongNames.sameVersion("Song", "Song (Remix)"))
    }

    @Test
    fun theGuestsATitleCreditsAreTheSongsArtistsToo() {
        assertEquals("The Walkers feat. A Singer", SongNames.withGuests("The Walkers", "River Road (feat. A Singer)"))
        assertEquals("The Walkers feat. A Singer & Another feat. Third", SongNames.withGuests("The Walkers", "River Road [ft A Singer & Another] (Featuring Third)"))
        assertTrue(SongNames.artistInside("A Singer", SongNames.withGuests("The Walkers", "River Road (feat. A Singer)")))
        // No guests, or a word that only starts like one.
        assertEquals("The Walkers", SongNames.withGuests("The Walkers", "River Road (Live)"))
        assertEquals("The Walkers", SongNames.withGuests("The Walkers", "River Road (Feathers)"))
    }

    @Test
    fun aNameWithSomeKanjiAsChinaWritesThemIsTheSameButForThem() {
        assertTrue(SongNames.sameButKanji("終わらない歌", "终わらない歌"))
        assertTrue(SongNames.sameButKanji("僕の右手", "仆の右手 (Remastered 2018)"))
        // Kana, or a letter, for a kanji isn't a way to write it.
        assertFalse(SongNames.sameButKanji("終わらない歌", "おわらない歌"))
        assertFalse(SongNames.sameButKanji("Song", "Sung"))
        // Nor is a name mostly other kanji, or longer.
        assertTrue(SongNames.sameButKanji("黄金時代", "黄金时代"))
        assertFalse(SongNames.sameButKanji("東京物語", "南京故事"))
        assertFalse(SongNames.sameButKanji("終わらない歌", "终わらない歌声"))
    }

    @Test
    fun whatFilmOrShowASongIsOfNamesNoOtherRecording() {
        assertEquals("小幸運", SongNames.withoutEditionTag("小幸運 (電影《我的少女時代》主題曲)"))
        assertEquals("光年之外", SongNames.withoutEditionTag("光年之外 (电影《Passengers》中国区主题曲)"))
        assertEquals("Lemon", SongNames.withoutEditionTag("Lemon (ドラマ『アンナチュラル』主題歌)"))
        assertEquals("Stay With Me", SongNames.withoutEditionTag("Stay With Me (From \"Goblin\" OST)"))
        assertEquals("Song", SongNames.withoutEditionTag("Song - Theme Song from Show"))
        assertTrue(SongNames.same("小幸運 (電影《我的少女時代》主題曲)", "小幸運(我的少女時代電影主題曲)"))
        // Words in a title aren't such a note: "Lost" isn't "OST", and a remix of the film's song is another recording.
        assertEquals("Song (Lost Love)", SongNames.withoutEditionTag("Song (Lost Love)"))
        assertEquals("Song (OST Remix)", SongNames.withoutEditionTag("Song (OST Remix)"))
    }

    @Test
    fun aTagThatAlsoNamesAnotherRecordingIsntJustAnEdition() {
        assertEquals("Hotel California", SongNames.withoutEditionTag("Hotel California (2013 Remaster)"))
        assertEquals("Hotel California (Live; 2013 Remaster)", SongNames.withoutEditionTag("Hotel California (Live; 2013 Remaster)"))
        assertEquals("Song - Stereo MC's Remix", SongNames.withoutEditionTag("Song - Stereo MC's Remix"))
        assertEquals("Song - Acoustic Mono Demo", SongNames.withoutEditionTag("Song - Acoustic Mono Demo (feat. A)"))
        // Only the last part after a dash is a tag: the name before it stays.
        assertEquals("Band - Song", SongNames.withoutEditionTag("Band - Song - Remastered 2011"))
        assertFalse(SongNames.same("Hotel California (Live; 2013 Remaster)", "Hotel California"))
        assertFalse(SongNames.sameVersion("Hotel California", "Hotel California (Live; 2013 Remaster)"))
        // A remaster of the live take is still that live take.
        assertTrue(SongNames.sameVersion("Hotel California (Live)", "Hotel California (Live; 2013 Remaster)"))
    }

    @Test
    fun aRemasterIsLeftOffInSpanishToo() {
        assertEquals("Ji Ji Ji", SongNames.withoutEditionTag("Ji Ji Ji - Remasterizado"))
        assertEquals("Ji Ji Ji", SongNames.withoutEditionTag("Ji Ji Ji (Remasterizada 2012)"))
        assertEquals("Ji Ji Ji (Live)", SongNames.withoutEditionTag("Ji Ji Ji (Live) - Remasterizado"))
    }

    @Test
    fun theBareNameLeavesOffWhatItAdds() {
        assertEquals("Song", SongNames.bareTitle("Song (2018 Remix) (2024 Remaster)"))
        assertEquals("Song", SongNames.bareTitle("Song [Live] - Remastered"))
        assertEquals("(Intro)", SongNames.bareTitle("(Intro)"))
        assertEquals("Song", SongNames.bareTitle("  Song  "))
    }
}
