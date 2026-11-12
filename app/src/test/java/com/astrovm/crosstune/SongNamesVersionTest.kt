package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongNamesVersionTest {

    @Test
    fun aTitleThatSaysItsACoverNamesItsSong() {
        assertEquals(SongNames.Covered("Bring Me to Life", null), SongNames.coverOf("Bring Me to Life (Cyberpunk Shamisen Cover)"))
        assertEquals(SongNames.Covered("Creep", null), SongNames.coverOf("Creep - Acoustic Cover"))
        assertEquals(SongNames.Covered("アイドル", null), SongNames.coverOf("アイドル【歌ってみた】"))
        assertEquals(SongNames.Covered("Zombie", "The Cranberries"), SongNames.coverOf("Zombie (Originally Performed by The Cranberries) [Karaoke]"))
        assertEquals(SongNames.Covered("Halo", "Beyoncé"), SongNames.coverOf("Halo (Made Famous by Beyoncé)"))
        // A song named with the word is the artist's own, as is a tag that's all there is.
        for (own in listOf("Cover Me", "Undercover (Remix)", "Cover Girl - Live", "(Cover)")) assertNull(own, SongNames.coverOf(own))
    }

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
    fun aGuestWrittenOutInTheTitleIsLeftOutLikeOneInBrackets() {
        // Searched with its guest, Qobuz lists only tribute albums for Despacito, and says it hasn't the song.
        assertEquals("Despacito", SongNames.withoutEditionTag("Despacito ft. Daddy Yankee"))
        assertEquals("Song", SongNames.withoutEditionTag("Song feat Someone"))
        assertEquals("Song", SongNames.withoutEditionTag("Song FEATURING Someone & Other"))
        assertEquals("Song", SongNames.withoutEditionTag("Song (feat. Someone)"))
        // The recording's other marks stay, and a length isn't a guest, nor is a word that starts the same.
        assertEquals("Song (Live)", SongNames.withoutEditionTag("Song ft. Someone (Live)"))
        assertEquals("6 ft. Under", SongNames.withoutEditionTag("6 ft. Under"))
        assertEquals("Feats of Strength", SongNames.withoutEditionTag("Feats of Strength"))
        assertEquals("Despacito Luis Fonsi", searchQuery(MusicMetadata("Despacito ft. Daddy Yankee", "Luis Fonsi")))
    }

    @Test
    fun aVideosMarkIsLeftOutSoASearchFindsTheSong() {
        // With it, Qobuz's store lists nothing at all for Gangnam Style, and 60 without.
        assertEquals("GANGNAM STYLE(강남스타일)", SongNames.withoutEditionTag("GANGNAM STYLE(강남스타일) M/V"))
        assertEquals("Song", SongNames.withoutEditionTag("Song (M/V)"))
        assertEquals("Song", SongNames.withoutEditionTag("Song [m/v]"))
        assertEquals("M/V", SongNames.withoutEditionTag("M/V"))
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

    @Test
    fun aMarkInAnotherScriptCountsOnlyAsAWordOfItsOwn() {
        fun other(name: String, title: String = "Fake Song", artist: String = "Fake Band") = SongNames.saysAnotherVersion(name, title, artist)
        // Inside a longer word in its script, a sound mark off, or widths apart, it's another word.
        for (same in listOf("インストール", "Fake Song インストール", "ATV Sizes", "Fake Song ATV Sizes", "ライフ", "ライフ・イズ", "반주자", "커버리지", "라이브러리", "TV Sizes")) {
            assertFalse(same, other(same))
        }
        // As a word of its own, in brackets, with what's between its letters, or in full or half width, it says so.
        for (said in listOf(
            "Fake Song (インスト)", "Fake Song (TV Size)", "Fake Song TV-size", "Fake Song (オフ・ヴォーカル)", "Fake Song (Off Vocal)",
            "Fake Song ライブ版", "Fake Song (반주)", "Fake Song 라이브 버전", "Fake Song 翻唱版", "Fake Song 卡拉OK版", "Fake Song (ＴＶサイズ)",
            "Fake Song ｶﾗｵｹ", "Fake Song【カラオケ】", "Fake Song 메들리", "Fake Song 混音版"
        )) {
            assertTrue(said, other(said))
        }
        // The song's own name says it, so it's no other version.
        assertFalse(SongNames.saysAnotherVersion("ライブ (Fake Band)", "ライブ", "Fake Band"))
        assertFalse(SongNames.saysAnotherVersion("Fake Song (Fake カバー)", "Fake Song", "Fake カバー"))
        assertTrue(SongNames.saysAnotherVersion("ライフ (ライブ)", "ライフ", "Fake Band"))
        // Many songs in one is a word of its own too: メドレー, and not inside a longer word.
        assertTrue(SongNames.manySongs(MusicMetadata("夏メドレー", "Fake Band")))
        assertFalse(SongNames.manySongs(MusicMetadata("メドレーズ", "Fake Band")))
    }

    @Test
    fun aNameInFullWidthIsTheSameNameButIsKeptAsWritten() {
        assertEquals(SongNames.normalize("Love Song"), SongNames.normalize("ＬＯＶＥ ＳＯＮＧ"))
        assertTrue(SongNames.same("ＬＯＶＥ ＳＯＮＧ", "Love Song"))
        assertEquals("カラオケ", SongNames.normalize("ｶﾗｵｹ"))
        // What's kept for a song stays under the name it was kept under.
        assertEquals("ｌｏｖｅｓｏｎｇ", SongNames.keyOf("ＬＯＶＥ ＳＯＮＧ"))
        assertEquals(SongNames.normalize("Édition"), SongNames.keyOf("Edition!"))
    }
}
