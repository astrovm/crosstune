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
    fun aMarkInAnotherScriptIsFoundButNotInAWordThatOnlyHoldsIt() {
        fun other(name: String, title: String = "Fake Song", artist: String = "Fake Band") = SongNames.saysAnotherVersion(name, title, artist)
        // A longer word that only holds a mark, a sound mark off, or a Latin mark inside a longer Latin word, is another word.
        for (same in listOf(
            "インストール", "Fake Song インストール", "ドライブ", "Fake Song ドライブ", "Fake Song ライブラリー", "ライフ", "ライフ・イズ",
            "Fake Song ディスカバー", "Fake Song 디스커버", "Fake Song 드라이브", "라이브러리", "커버리지", "반주자",
            "ATV Sizes", "Fake Song ATV Size", "TV Sizes", "Fake Song TV Size2", "Fake Song (TV Size\u0332)"
        )) {
            assertFalse(same, other(same))
        }
        // As a word of its own, in brackets, with what's between its letters, or in full or half width, it says so.
        for (said in listOf(
            "Fake Song (インスト)", "Fake Song (TV Size)", "Fake Song TV-size", "Fake Song (オフ・ヴォーカル)", "Fake Song (Off Vocal)",
            "Fake Song ライブ版", "Fake Song (반주)", "Fake Song 라이브 버전", "Fake Song 翻唱版", "Fake Song 卡拉OK版", "Fake Song (ＴＶサイズ)",
            "Fake Song ｶﾗｵｹ", "Fake Song【カラオケ】", "Fake Song 메들리", "Fake Song 混音版", "Fake Song 串烧",
            "Fake Songのカラオケ", "Fake Song ライブを歌う", "Fake Song 歌ってみたい", "カラオケ Fake Song", "Fake Song TV Size版",
            "Fake Song (インストゥルメンタル)", "Fake Song (アコースティックカバー)"
        )) {
            assertTrue(said, other(said))
        }
        // The song's own name says it, so it's no other version.
        assertFalse(SongNames.saysAnotherVersion("ライブ (Fake Band)", "ライブ", "Fake Band"))
        assertFalse(SongNames.saysAnotherVersion("Fake Song (Fake カバー)", "Fake Song", "Fake カバー"))
        assertTrue(SongNames.saysAnotherVersion("ライフ (ライブ)", "ライフ", "Fake Band"))
        // Many songs in one is found the same way.
        for (many in listOf("夏メドレー", "夏のメドレー", "ディズニーメドレー", "ヒットメドレー2020", "메들리곡", "Fake Song (串燒)")) {
            assertTrue(many, SongNames.manySongs(MusicMetadata(many, "Fake Band")))
        }
    }

    @Test
    fun aMarkRunOnIntoAnotherWordIsFoundAsMainFoundIt() {
        // Every mark, alone, after a word and before one, as Japanese, Korean, Chinese and Thai write them with no space.
        val before = listOf("", "ピアノ", "アコースティック", "ディズニー", "ヒット", "피아노", "钢琴", "เปียโน")
        val after = listOf(
            "", "バージョン", "ver", "版", "ソロ", "アレンジ", "カバー", "ビデオ", "ツアー", "映像", "よ", "を歌う",
            "버전", "곡", "영상", "클립", "现场版", "เวอร์ชัน"
        )
        val songs = listOf("Fake Song " to "Fake Song", "假的歌" to "假的歌", "เพลงปลอม" to "เพลงปลอม")
        val names = MARKS.flatMap { mark -> before.flatMap { first -> after.flatMap { last -> songs.map { (name, title) -> "$name$first$mark$last" to title } } } }
        val lost = names.filter { (name, title) -> mainSays(name, title) && !SongNames.saysAnotherVersion(name, title, "Fake Band") }
        assertEquals("what main found and isn't now", emptyList<Pair<String, String>>(), lost)
        // Each mark main didn't have is found as main's are.
        for ((name, title) in names.filter { (name, _) -> NEW_MARKS.any { it in name } && !name.endsWith("ver") }) {
            assertTrue(name, SongNames.saysAnotherVersion(name, title, "Fake Band"))
        }
        // Main found a mark in a longer word that only holds it; these, and only these, aren't found now.
        val heldOnly = listOf("ドライブ", "ライブラリー", "ディスカバー", "디스커버", "드라이브", "라이브러리", "커버리지", "ライフ")
        val words = heldOnly + listOf("インストール", "반주자", "Fake Song ATV Size", "ライブ", "カラオケ")
        assertEquals(heldOnly, words.filter { mainSays("Fake Song $it", "Fake Song") && !SongNames.saysAnotherVersion("Fake Song $it", "Fake Song", "Fake Band") })
    }

    @Test
    fun aMarkTheSongsOwnNameSaysInOtherWordsIsNoOtherVersion() {
        // "Nagoriyuki (Live)" is live, so "なごり雪 (ライブ)" is no other version of it, nor one on its own tribute album.
        assertFalse(SongNames.saysAnotherVersion("なごり雪 (ライブ)", "Nagoriyuki (Live)", "Fake Band"))
        assertFalse(SongNames.saysAnotherVersion("白銀の子守唄 (アコースティックギター)", "Shirogane No Komoriuta Acoustic Guitar Version", "Fake Band"))
        assertFalse(SongNames.saysAnotherVersion("絶体絶命 MOMOE YAMAGUCHI TRIBUTE SELECTION", "Zettai Zetsumei", "Fake Band", album = "Momoe Yamaguchi Tribute Selection"))
        // What it says in other words is another version still.
        assertTrue(SongNames.saysAnotherVersion("なごり雪 (カラオケ)", "Nagoriyuki (Live)", "Fake Band"))
        assertTrue(SongNames.saysAnotherVersion("絶体絶命 (Karaoke) Tribute Selection", "Zettai Zetsumei", "Fake Band", album = "Tribute Selection"))
        assertTrue(SongNames.saysAnotherVersion("絶体絶命 MOMOE YAMAGUCHI TRIBUTE SELECTION", "Zettai Zetsumei", "Fake Band"))
    }

    @Test
    fun aShortWordForWhatTheSongsOwnNameSaysIsNoOtherVersion() {
        // "ver." for the song's "Version", and "Inst." for its "Instrumental", as main opened them.
        assertFalse(SongNames.saysAnotherVersion("真夜中のドア (シングルver.)", "Fake Door (Single Version)", "Fake Band"))
        assertFalse(SongNames.saysAnotherVersion("子守唄_Acoustic Guitar Ver.", "Fake Lullaby Acoustic Guitar Version", "Fake Band"))
        assertFalse(SongNames.saysAnotherVersion("子守唄 (Inst.)", "Fake Lullaby (Instrumental)", "Fake Band"))
        // A version the song's own name doesn't say still is one.
        assertTrue(SongNames.saysAnotherVersion("真夜中のドア (シングルver.)", "Fake Door", "Fake Band"))
        assertTrue(SongNames.saysAnotherVersion("真夜中のドア (Live ver.)", "Fake Door (Single Version)", "Fake Band"))
        assertTrue(SongNames.saysAnotherVersion("子守唄 (Inst.)", "Fake Lullaby", "Fake Band"))
    }

    @Test
    fun aSymbolIsNoLetter() {
        // Only widths are folded: "Song™" is "Song", "№1" is "1" and "℃" is nothing, as before.
        assertTrue(SongNames.same("Song™", "Song"))
        assertEquals("1", SongNames.normalize("№1"))
        assertEquals("", SongNames.normalize("℃"))
        assertEquals(setOf("song"), SongNames.words("Song™"))
        assertEquals(setOf("1"), SongNames.words("№1"))
        // And a ligature is as it was, one letter of its own.
        assertEquals("\ufb01ne", SongNames.normalize("ﬁne"))
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

    private companion object {
        /** Main's marks, then those this branch adds. */
        val MAIN_MARKS = listOf(
            "カラオケ", "ライブ", "弾き語り", "歌ってみた", "演奏してみた", "弾いてみた", "叩いてみた", "ピアノ", "ギター",
            "カバー", "メドレー", "練習", "オルゴール", "アカペラ", "翻唱", "伴奏", "伴唱", "卡拉OK", "커버", "라이브", "노래방"
        )
        val NEW_MARKS = listOf(
            "串烧", "串燒", "메들리", "Off Vocal", "TV Size", "オフボーカル", "オフヴォーカル", "インスト", "TVサイズ", "テレビサイズ",
            "纯音乐", "純音樂", "인스트", "반주", "リミックス", "混音", "리믹스", "演唱会", "演唱會", "アコースティック"
        )
        val MARKS = MAIN_MARKS + NEW_MARKS

        /** Main's [SongNames.saysAnotherVersion], as it was before marks were words: a word of its own, or a mark anywhere. */
        fun mainSays(name: String, title: String): Boolean {
            fun words(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
                .lowercase(java.util.Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
            fun letters(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
                .lowercase(java.util.Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), "")
            if ((words(name) - words(title) - words("Fake Band")).any { it in MAIN_VERSION_WORDS }) return true
            return MAIN_MARKS.map(::letters).any { it in letters(name) && it !in letters(title) }
        }

        val MAIN_VERSION_WORDS = setOf(
            "cover", "covers", "covered", "karaoke", "live", "unplugged", "mtv", "vivo", "directo", "session", "sessions",
            "concert", "tour", "remix", "mix", "instrumental", "inst", "acoustic", "acustico", "piano", "guitar",
            "bass", "drum", "drums", "chords", "tab", "tabs", "tutorial", "lesson", "practice", "reaction", "slowed",
            "reverb", "sped", "nightcore", "8d", "demo", "medley", "mashup", "amv", "mmd", "ver", "tribute", "ktv",
            "acapella", "acappella", "cappella", "capella", "8bit", "orgel", "musicbox"
        )
    }
}
