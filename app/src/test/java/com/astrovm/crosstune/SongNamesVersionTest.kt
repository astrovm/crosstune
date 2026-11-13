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
        // A karaoke label's "Made Popular By" is left as it was: whether its tracks show the song's words is still to be decided (Issue #922).
        assertNull(SongNames.coverOf("Song (Made Popular By Singer) [Instrumental Version]"))
        // A song named with the word is the artist's own, as is a tag that's all there is.
        for (own in listOf("Cover Me", "Undercover (Remix)", "Cover Girl - Live", "(Cover)")) assertNull(own, SongNames.coverOf(own))
    }

    @Test
    fun aTitleThatSaysNoOneSingsInItSaysItsInstrumental() {
        // The list scripts/songcheck/instrumental_rules.py is tested against too, so both read titles alike.
        val titles = javaClass.getResource("/instrumental-titles.tsv")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        for (line in titles) {
            val (says, title) = line.split("\t")
            val artist = line.split("\t").getOrElse(2) { "" }
            assertEquals(line, says == "yes", SongNames.saysInstrumental(title, artist))
        }
    }

    @Test
    fun anAlbumOfInstrumentalsSaysSo() {
        for (album in listOf("Album (Instrumentals)", "Album (Instrumental Version)", "Album - Instrumental", "Album (Off Vocal Ver.)", "专辑 (伴奏版)", "Ａｌｂｕｍ （Ｉｎｓｔｒｕｍｅｎｔａｌｓ）",
            // Review of PR #919: short forms as a whole tag, and tracks to sing over.
            "Album (Inst.)", "Album (MR)", "Album (Karaoke Version)", "Album (Backing Tracks)")) {
            assertTrue(album, SongNames.albumOfInstrumentals(album))
        }
        // "Inst." and "MR" are short for other words too, and a word holding "instrumental" is another word.
        for (album in listOf("Album", "", "Live at MIT Inst.", "Mr. Album", "Instrumentalists")) assertFalse(album, SongNames.albumOfInstrumentals(album))
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
        fun other(name: String, title: String = "Fake Song", artist: String = "Fake Band") = SongNames.saysAnother(name, "", listOf(title), listOf(artist))
        // A longer word that only holds a mark, a sound mark off, or a Latin mark inside a longer Latin word, is another word.
        for (same in listOf(
            "インストール", "Fake Song インストール", "Fake Song (インストラクター)", "Fake Song (アライヴ)", "ドライブ", "Fake Song ドライブ", "Fake Song ライブラリー", "ライフ", "ライフ・イズ",
            "Fake Song ディスカバー", "Fake Song 디스커버", "Fake Song 드라이브", "라이브러리", "커버리지", "반주자",
            "Fake Song (ATV Sizes)", "Fake Song ATV Size", "Fake Song (TV Sizes)", "Fake Song TV Size2", "Fake Song (TV Size̲)",
            // Katakana from its first letter, ァ, to its last, ヿ, with ー, goes on a run.
            "Fake Song (ァライブ)", "Fake Song (ライブヺ)", "Fake Song (ーライブ)", "Fake Song (ライブヿ)"
        )) {
            assertFalse(same, other(same))
        }
        // As a word of its own, in brackets, with what's between its letters, or in full or half width, it says so.
        for (said in listOf(
            "Fake Song (インスト)", "Fake Song (TV Size)", "Fake Song TV-size", "Fake Song (オフ・ヴォーカル)", "Fake Song (Off Vocal)",
            "Fake Song ライブ版", "Fake Song (반주)", "Fake Song 라이브 버전", "Fake Song 翻唱版", "Fake Song 卡拉OK版", "Fake Song (ＴＶサイズ)",
            "Fake Song ｶﾗｵｹ", "Fake Song【カラオケ】", "Fake Song 메들리", "Fake Song 混音版", "Fake Song 串烧",
            "Fake Songのカラオケ", "Fake Song ライブを歌う", "Fake Song 歌ってみたい", "カラオケ Fake Song", "Fake Song TV Size版",
            "Fake Song (インストゥルメンタル)", "Fake Song (アコースティックカバー)",
            // Just past katakana, ゠ and ・ end a run.
            "Fake Song (゠ライブ)", "Fake Song (ライブ・)"
        )) {
            assertTrue(said, other(said))
        }
        // The song's own name says it, so it's no other version.
        assertFalse(other("ライブ (Fake Band)", "ライブ"))
        assertFalse(other("Fake Song (Fake カバー)", artist = "Fake カバー"))
        assertTrue(other("ライフ (ライブ)", "ライフ"))
        // Many songs in one is found the same way.
        for (many in listOf("夏メドレー", "夏のメドレー", "ディズニーメドレー", "ヒットメドレー2020", "메들리곡", "Fake Song (串燒)")) {
            assertTrue(many, SongNames.manySongs(MusicMetadata(many, "Fake Band")))
        }
    }

    @Test
    fun aMarkRunOnIntoAnotherWordIsFoundAsMainFoundItButInKatakanaOnlyBesideVersionWords() {
        // Every mark, alone, after a word and before one, as Japanese, Korean, Chinese and Thai write them with no space;
        // in katakana, only words a version tag says.
        val before = listOf("", "ピアノ", "アコースティック", "バンド", "피아노", "밴드", "钢琴", "เปียโน")
        val after = listOf("", "バージョン", "ver", "版", "カバー", "映像", "よ", "を歌う", "버전", "곡", "영상", "클립", "现场版", "เวอร์ชัน")
        val songs = listOf("Fake Song " to "Fake Song", "假的歌" to "假的歌", "เพลงปลอม" to "เพลงปลอม")
        fun other(name: String, title: String) = SongNames.saysAnother(name, "", listOf(title), listOf("Fake Band"))
        val names = MARKS.flatMap { mark -> before.flatMap { first -> after.flatMap { last -> songs.map { (name, title) -> "$name$first$mark$last" to title } } } }
        // Each of main's marks is found where main found it.
        val lost = names.filter { (name, title) -> MAIN_MARKS.any { it in name } && mainSays(name, title) && !other(name, title) }
        assertEquals("what main found and isn't now", emptyList<Pair<String, String>>(), lost)
        // Each mark main didn't have is found as main's are, but one in Latin letters only as a word of its own.
        val beside = listOf("", "ピアノ", "アコースティック", "バンド", "피아노", "钢琴")
        val next = listOf("", "バージョン", "版", "カバー", "映像", "よ", "を歌う", "버전", "现场版")
        for (mark in NEW_MARKS) for (first in beside) for (last in next) for ((song, title) in songs) {
            assertTrue("$song$first$mark$last", other("$song$first$mark$last", title))
        }
        // Main found a mark in a longer word that only holds it, or beside a word in katakana no version tag says, as in
        // the name "Alive"; these say nothing, as this only takes away what main opened.
        for (held in listOf("ドライブ", "ライブラリー", "ディスカバー", "디스커버", "드라이브", "라이브러리", "커버리지", "ライフ", "アライブ", "ワールドライブ", "カラオケビデオ")) {
            assertTrue(held, mainSays("Fake Song $held", "Fake Song"))
            assertFalse(held, other("Fake Song $held", "Fake Song"))
        }
        // After a word a version tag says, it is one: "バンドライブ" is a band live, as main had it.
        for (live in listOf("Fake Song (バンドライブ)", "Fake Song (밴드라이브)")) {
            assertTrue(live, mainSays(live, "Fake Song"))
            assertTrue(live, other(live, "Fake Song"))
        }
    }

    @Test
    fun aMarkTheSongsOwnNameSaysInOtherWordsIsNoOtherVersion() {
        // "Nagoriyuki (Live)" is live, so "なごり雪 (ライブ)" is no other version of it, for a song in another script.
        fun other(name: String, title: String, artist: String = "Fake Band") = SongNames.saysAnother(name, "", listOf(title), listOf(artist))
        assertFalse(other("なごり雪 (ライブ)", "Nagoriyuki (Live)"))
        assertFalse(other("白銀の子守唄 (アコースティックギター)", "Shirogane No Komoriuta Acoustic Guitar Version"))
        // What it says in other words is another version still, and only the title says what the song is.
        assertTrue(other("なごり雪 (カラオケ)", "Nagoriyuki (Live)"))
        assertTrue(other("絶体絶命 MOMOE YAMAGUCHI TRIBUTE SELECTION", "Zettai Zetsumei"))
        assertTrue(other("なごり雪 (Instrumental)", "Nagoriyuki"))
        assertTrue(other("なごり雪 (ライブ)", "Nagoriyuki"))
        assertTrue(other("なごり雪 (ライブ)", "Nagoriyuki", "Fake Band Live"))
        // Elsewhere a name says what it says, as main had it: "曲 (ライブ)" is another version of "Live Forever".
        assertTrue(SongNames.saysAnotherVersion("曲 (ライブ)", "Live Forever", "Fake Band"))
        assertTrue(mainSays("曲 (ライブ)", "Live Forever"))
        assertTrue(SongNames.saysAnotherVersion("なごり雪 (ライブ)", "Nagoriyuki (Live)", "Fake Band"))
        // A mark that means a word of the song's name, in the name itself, is the name read out: the sixth review, 8.
        assertFalse(other("ライヴ・フォーエヴァー (Live Forever)", "Live Forever"))
        assertFalse(other("ライヴ・フォーエヴァー", "Live Forever"))
        assertFalse(SongNames.saysAnotherVersion("ライヴ・フォーエヴァー (Live Forever)", "Live Forever", "Fake Band"))
        assertTrue(other("曲 (ライヴ)", "Live Forever"))
        assertFalse(other("インストラクター", "Fake Song"))
        assertFalse(SongNames.saysAnotherVersion("インストラクター", "Fake Song", "Fake Band"))
        // A tag that starts with a version word is the song's own version, where one that names a film isn't.
        assertFalse(other("偽の歌 (ライブ・アット・武道館)", "Fake Song (Live at Budokan)"))
        assertTrue(other("さくら (ライブ)", "Sakura (Theme from Live Forever)"))
        // What a tag says it's from is a film's name; the name before the tags is the song's, "from" or not.
        assertFalse(other("Fake Song - From \"Live Forever\"", "Fake Song"))
        assertFalse(other("Fake Song (Theme from Live Forever)", "Fake Song"))
        assertTrue(other("From Me to You (Live)", "From Me to You"))
        assertFalse(other("From Me to You", "From Me to You"))
    }

    @Test
    fun aHalfThatSaysOnlyAVersionIsAnotherVersion() {
        fun other(name: String, title: String = "曲") = SongNames.saysAnother(name, "", listOf(title), listOf("Fake Band"))
        for (half in listOf("Live", "ライブ", "Acoustic Ver.", "Live 2019", "Live at Budokan")) {
            assertTrue(half, other("曲 - $half"))
            assertTrue(half, other("$half - 曲"))
        }
        // A half that says no version, an edition, a film, the artist or the song in other words, is no other version.
        for (half in listOf("From \"Live Forever\"", "Remastered 2012", "Fake Band", "Single Version")) assertFalse(half, other("曲 - $half"))
        // The other half saying that version too, it's one version said twice.
        assertFalse(other("なごり雪 (ライブ) - Nagoriyuki (Live)", "Nagoriyuki (Live)"))
        assertFalse(other("子守唄_Acoustic Guitar Ver.", "Fake Lullaby Acoustic Guitar Version"))
        assertTrue(other("子守唄_Acoustic Guitar Ver.", "Fake Lullaby"))
    }

    @Test
    fun aShortWordForWhatTheSongsOwnNameSaysIsNoOtherVersion() {
        // "ver." for the song's "Version", and "Inst." for its "Instrumental", as main opened a song in another script.
        fun other(name: String, title: String) = SongNames.saysAnother(name, "", listOf(title), listOf("Fake Band"))
        assertFalse(other("真夜中のドア (シングルver.)", "Fake Door (Single Version)"))
        assertFalse(other("子守唄_Acoustic Guitar Ver.", "Fake Lullaby Acoustic Guitar Version"))
        assertFalse(other("子守唄 (Inst.)", "Fake Lullaby (Instrumental)"))
        // A single is an edition, so it says no version, either way: the seventh review, 3.
        assertFalse(other("真夜中のドア (シングルver.)", "Fake Door"))
        // A version the song's own name doesn't say still is one.
        assertTrue(other("真夜中のドア (Live ver.)", "Fake Door (Single Version)"))
        assertTrue(other("子守唄 (Inst.)", "Fake Lullaby"))
        // Only in a tag that says nothing else: "(TV ver.)" is another version of "(Single Version)".
        assertFalse(other("真夜中のドア - Mayonaka no Door (Single ver.)", "Fake Door (Single Version)"))
        assertFalse(other("シンデレラ・ハネムーン (Speed Up Ver.)", "Cinderella Honeymoon (Speed-Up Version)"))
        assertTrue(other("真夜中のドア (TV ver.)", "Fake Door (Single Version)"))
        assertTrue(other("真夜中のドア (Single ver.) - Fake Door (TV ver.)", "Fake Door (Single Version)"))
        // A version the song says in a word a list has, "Speed-Up", the name doesn't say, though it says another in words
        // no list has, "Disco funk".
        assertTrue(other("シンデレラ・ハネムーン – Disco funk ver.", "Cinderella Honeymoon (Speed-Up Version)"))
        assertFalse(other("シンデレラ・ハネムーン – Disco funk ver.", "Cinderella Honeymoon (Disco Version)"))
        // Elsewhere "ver." is another version, as main had it: a video "(TV ver.)" of "(Single Version)" isn't the song.
        for (tag in listOf("TV", "English", "Short", "Dance", "Live", "Single")) {
            assertTrue(tag, mainSays("Fake Song ($tag ver.)", "Fake Song (Single Version)"))
            assertTrue(tag, SongNames.saysAnotherVersion("Fake Song ($tag ver.)", "Fake Song (Single Version)", "Fake Band"))
        }
        assertTrue(SongNames.saysAnotherVersion("子守唄 (Inst.)", "Fake Lullaby (Instrumental)", "Fake Band"))
        // An apostrophe is spelling: "Directors" is "Director's".
        assertFalse(other("偽の歌 (Directors Edit Version)", "Fake Song (Director's Edit Version)"))
        assertFalse(other("偽の歌 (Director’s Edit Version)", "Fake Song (Directors Edit Version)"))
        assertFalse(other("偽の歌 (Directors Cut Version)", "Fake Song (Director's Edit Version)"))
        assertTrue(other("偽の歌 (Directors Live Version)", "Fake Song (Director's Edit Version)"))
    }

    @Test
    fun whatAllTheSongsNamesSayTogetherIsItsOwn() {
        fun other(name: String, vararg titles: String) = SongNames.saysAnother(name, "", titles.toList(), listOf("Fake Band"))
        // "Single" in English and "シングル" in Japanese: "(Single ver.)" is the song's own version.
        val single = arrayOf("Fake Song (Single Version)", "偽の歌 (シングル・バージョン)")
        assertFalse(other("偽の歌 (Single ver.)", *single))
        assertFalse(other("偽の歌 (シングル・バージョン)", single.first()))
        assertFalse(other("偽の歌 (ｼﾝｸﾞﾙ･ﾊﾞｰｼﾞｮﾝ)", single.first()))
        assertFalse(other("偽の歌 (シングル・バージョン)", "Fake Song", "偽の歌 (シングル・バージョン)"))
        // Katakana runs words into one: "スピードアップ" is "Speed-Up", and "ダンスリミックス" "Dance Remix".
        assertFalse(other("偽の歌 (スピードアップバージョン)", "Fake Song (Speed-Up Version)"))
        assertTrue(other("偽の歌 (スピードアップバージョン)", "Fake Song"))
        assertFalse(other("偽の歌 (ダンスリミックス・バージョン)", "Fake Song (Dance Remix Version)"))
        assertFalse(other("偽の歌 (ダンスフロアリミックス・バージョン)", "Fake Song (Dance Floor Remix Version)"))
        // In a run with a word no list has, "フロア", it can't be told, and "version" alone doesn't say which.
        assertFalse(other("偽の歌 (ダンスフロアリミックス・バージョン)", "Fake Song"))
        assertTrue(other("偽の歌 (ダンスリミックス・バージョン)", "Fake Song"))
        // In a tag that says "version", a word in Latin letters says which, but its artist's name: "Fake Band" is no band version.
        assertTrue(other("偽の歌 (TV Version)", "Fake Song"))
        assertFalse(other("偽の歌 (Fake Band Version)", "Fake Song"))
        assertTrue(other("偽の歌 (Band Version)", "Fake Song"))
        // A version word near a word of its name, or of another tag, is another version.
        for ((title, word) in listOf(
            "Love Song" to "ライヴ", "Sorry" to "スロー", "Wrong Way" to "ロング", "Remix Me" to "リミックス", "Adam" to "デモ", "Speed Up" to "スピードアップ"
        )) {
            assertTrue(word, other("偽の歌 (${word}バージョン)", "$title (Single Version)"))
        }
        // But a short or a full version is an edition, or the whole song.
        for ((title, word) in listOf("Stay" to "ショート", "Free" to "フル")) assertFalse(word, other("偽の歌 (${word}バージョン)", "$title (Single Version)"))
        assertTrue(other("曲 (ライブ)", "Live Forever"))
        assertFalse(other("曲 (ライヴ)", "Fake Song (Live)"))
        // "Xバージョン" is "X ver.": a version the names don't say is another, in any script.
        for (name in listOf("偽の歌 (TVバージョン)", "偽の歌 (TV Version)", "偽の歌 (Orchestra Version)", "봄날 (라이브 버전)", "偽の歌 (ライブ・ヴァージョン)")) {
            assertTrue(name, other(name, *single))
        }
        // But not in a word no list has, "英語", nor with no word: "version" alone doesn't say which.
        assertFalse(other("偽の歌 (英語バージョン)", *single))
        assertFalse(other("偽の歌 (バージョン)", "Fake Song"))
        // A version none of them says by name, against an edition, can't be told apart.
        assertFalse(other("偽の歌 (Single ver.)", "Fake Song (Single)"))
        // "ver" and "version" are one word, whichever the song's names and the name say.
        assertFalse(other("スパークル (movie ver.)", "Sparkle - Movie Ver."))
        assertFalse(other("スパークル (Movie Version)", "Sparkle - Movie Ver."))
        assertFalse(other("スパークル ver", "Sparkle Ver"))
        // "Movie" in a tag that says "version" is the film's version, which "original" isn't.
        assertTrue(other("スパークル (original ver.)", "Sparkle - Movie Ver."))
        // A word katakana says a version with says it in katakana, "バラード" against "Speed-Up", where in Latin letters
        // it may be anything, "TV Sizes"; and either way it's what the other says.
        assertTrue(other("偽の歌 (バラードバージョン)", "Fake Song (Speed-Up Version)"))
        assertTrue(other("偽の歌 (スピードアップバージョン)", "Fake Song (Ballad Version)"))
        assertFalse(other("偽の歌 (バラードバージョン)", "Fake Song (Ballad Version)"))
        assertFalse(other("偽の歌 (Ballad Version)", "偽の歌 (バラードバージョン)"))
        assertTrue(other("偽の歌 (テレビバージョン)", "Fake Song (Single Version)"))
        assertFalse(other("偽の歌 (テレビバージョン)", "Fake Song (TV Version)"))
        assertFalse(other("偽の歌 - Ballad", "Fake Song"))
        // Two versions both said by a word a list has are two: English and Japanese.
        assertTrue(other("スパークル (English ver.)", "Sparkle - Japanese Ver."))
        assertTrue(other("スパークル (イングリッシュ・バージョン)", "Sparkle (Japanese Version)"))
        // Its album is a tag of its own.
        assertTrue(other("偽の歌 | Instrumental Works", *single))
        assertFalse(other("偽の歌 | Fake Works", *single))
    }

    @Test
    fun whatSaysNoVersionIsTakenOffBothFirst() {
        fun other(name: String, title: String = "偽の歌", album: String = "") = SongNames.saysAnother(name, album, listOf(title), listOf("Fake Band"))
        // An edition, what it's from, and a subtitle: the seventh review, 3, 4 and 6.
        for (said in listOf(
            "偽の歌 (Album Version)", "偽の歌 (Single Version)", "偽の歌 (Original Version)", "偽の歌 (映画「ライブ・フォーエバー」主題歌)",
            "偽の歌 ～偽りの夜～", "偽の歌 〜偽りの夜〜", "偽の歌 (Full Version)", "偽の歌 - Full", "偽の歌 (Theme from \"Live Forever\")"
        )) {
            assertFalse(said, other(said))
            assertFalse(said, other("偽の歌", said))
        }
        // A word no list has says no version, in any script: the seventh review, 5.
        assertFalse(other("偽の歌 (クリスマス・バージョン)", "Fake Song (Christmas Version)"))
        assertFalse(other("Fake Song (Christmas Version)", "偽の歌 (クリスマス・バージョン)"))
        // Nor does "Version" alone: it doesn't say which, as "Smooth Rap" may be said without it.
        assertFalse(other("偽の歌 (クリスマス・バージョン)"))
        assertFalse(other("Fake Song - Version Español", "Fake Song"))
        assertFalse(other("Fake Song - Version Español", "Fake Song (Version Español)"))
        assertFalse(other("偽の歌 smooth rap - Fake Song smooth rap", "Fake Song (Smooth Rap Version)"))
        assertFalse(other("偽の歌", "Fake Song (Director's Edit Version)"))
        assertTrue(other("偽の歌", "Fake Song (Live Version)"))
        // What the song says, its album may say: "Fake Song (Live)" on "Live at Budokan".
        assertTrue(other("偽の歌", "偽の歌 (Live)"))
        assertFalse(other("偽の歌", "偽の歌 (Live)", album = "Fake Band • Live at Budokan"))
        assertFalse(other("偽の歌 (ライブ・バージョン)", "偽の歌 (Live)"))
        // A version the song's name says in katakana, its album may say in Latin letters, but not in its artist's name.
        assertFalse(other("偽の歌", "偽の歌 (バラードバージョン)", album = "Fake Band • Ballad Best"))
        assertTrue(other("偽の歌", "偽の歌 (バンドバージョン)", album = "Fake Band • Fake Album"))
        assertFalse(other("偽の歌", "偽の歌 (バンドバージョン)", album = "Fake Band • Band Versions"))
    }

    @Test
    fun aNameIsAnotherOnlyInItsOwnScriptAndUnequalAfterTheFolds() {
        fun other(name: String, vararg titles: String) = SongNames.saysAnother(name, "", titles.toList(), listOf("Fake Band"))
        // Written two ways, it's one name: a long vowel, ヴ, kana, German's "ue", a mark or a stroke, China's kanji: the seventh review, 2.
        for ((name, title) in listOf(
            "とうきょう" to "トーキョー", "とーきょー" to "とうきょう", "さようなら" to "さよなら", "櫻" to "桜", "バイオリン" to "ヴァイオリン",
            "Müde" to "Muede", "Muede" to "Müde", "はなみずき" to "ﾊﾅﾐｽﾞｷ", "さくら" to "サクラ", "Fake Søng" to "Fake Song", "Straße Æon" to "Strasse Aeon",
            "Canción Falsa" to "Cancion Falsa", "小幸運" to "小幸运", "雨" to "飴", "Fake Song Pt. 2" to "Fake Song"
        )) {
            assertFalse("$name $title", other(name, title))
            assertFalse("$title $name", other(title, name))
        }
        // In two scripts, or kana against kanji, it can't be told: never another.
        for ((name, title) in listOf(
            "明日" to "あす", "さくら" to "桜", "東京" to "トーキョー", "ばら" to "原", "偽の歌" to "Fake Song", "♪" to "桜"
        )) {
            assertFalse("$name $title", other(name, title))
        }
        // A part that's only a version, or its artist, names nothing: "偽の歌 - Live" for "Fake Song (Live)".
        assertFalse(other("偽の歌 - Live", "Fake Song (Live)"))
        assertFalse(other("Fake Band - ライブ", "Fake Song (Live)"))
        assertFalse(other("Live 2019 - 偽の歌", "Fake Song (Live 2019)"))
        // Kanji read the same only when the whole reads the same: 脳 (のう) isn't 別の歌 (べつのうた).
        assertTrue(other("別の歌", "脳"))
        // A kanji no reading knows may be any: 小幸运 and 大幸运 can't be told apart.
        assertFalse(other("大幸运", "小幸运"))
        // In one script and unequal, it's another: by its letters, by how kanji read, with their sound marks.
        for ((name, title) in listOf(
            "別の歌" to "偽の歌", "Other Song" to "Fake Song", "はら" to "ばら", "きんか" to "ぎんか", "이별" to "사랑", "Canción Falsa" to "Fake Song",
            "Fake Band - 別の歌" to "偽の歌", "別の歌 - Fake Band" to "偽の歌"
        )) {
            assertTrue("$name $title", other(name, title))
        }
        // One of the song's names that it is, in its script, is enough: "Tokyo" in Japan is "トーキョー".
        assertFalse(other("とうきょう", "Tokyo", "トーキョー"))
        assertFalse(other("Tokyo", "Tokyo", "トーキョー"))
        assertTrue(other("別の歌", "偽の歌", "Fake Song"))
        assertFalse(other("別の歌", "Tokyo", "トーキョー"))
        // A name in brackets in another script names it too, where the name in Latin letters may be another way to
        // say it: "Fake Prayer (가짜 기도)" may be "Fake Wish".
        assertFalse(other("Other Song (偽の歌)", "Fake Song"))
        assertFalse(other("別の歌 (Fake Song)", "偽の歌"))
        assertTrue(other("Other Song", "Fake Song"))
        // But not a guest, a version, what it's from, or a year, which name no song.
        for (tag in listOf("(feat. 偽の人)", "(ライブ)", "(映画「偽物」主題歌)", "(2019)", "[偽物 Remix]")) assertTrue(tag, other("Other Song $tag", "Fake Song"))
        // Nor a name in brackets in the script of the name before it.
        assertTrue(other("別の歌 (偽の夜)", "偽の歌"))
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
