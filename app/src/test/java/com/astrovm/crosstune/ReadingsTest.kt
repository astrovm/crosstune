package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ReadingsTest {

    @Test
    fun theScriptIsWhatTheLinesAreWrittenIn() {
        assertEquals(Script.JAPANESE, Readings.scriptOf(listOf("Hello", "夜空に星が")))
        // Kanji alone, with no kana anywhere, is Chinese.
        assertEquals(Script.CHINESE, Readings.scriptOf(listOf("我爱你")))
        assertEquals(Script.KOREAN, Readings.scriptOf(listOf("사랑해 baby")))
        assertNull(Readings.scriptOf(listOf("Only English", "♪", "")))
        assertNull(Readings.scriptOf(emptyList()))
    }

    @Test
    fun otherScriptsAreToldApartWhenMostOfTheLettersAreInThem() {
        assertEquals(Script.CYRILLIC, Readings.displayScriptOf(listOf("Привет, мир", "baby")))
        assertEquals(Script.GREEK, Readings.displayScriptOf(listOf("Αγάπη μου")))
        assertEquals(Script.ARABIC, Readings.displayScriptOf(listOf("أحبك")))
        assertEquals(Script.HEBREW, Readings.displayScriptOf(listOf("שלום")))
        assertEquals(Script.INDIC, Readings.displayScriptOf(listOf("मेरा दिल")))
        assertEquals(Script.THAI, Readings.displayScriptOf(listOf("รักเธอ")))
        // A Russian word in an English song doesn't make the song Russian.
        assertNull(Readings.displayScriptOf(listOf("I love you my dear", "спасибо")))
        assertEquals(listOf("日", "中", "한", "Я", "Ω", "ع", "א", "अ", "ก"), Script.entries.map { it.glyph })
    }

    @Test
    fun otherScriptsReadInPlainLatinLetters() {
        val (line) = Readings.of(listOf("Привет мир"), Script.CYRILLIC)
        assertEquals(listOf(Ruby("Привет мир")), line.parts)
        assertEquals("Privet mir", line.romanized)
        assertEquals(listOf(Word("мир", null, "mir")), Readings.words("мир", Script.CYRILLIC))
        assertEquals(listOf(Word("love")), Readings.words("love", null))
    }

    @Test
    fun russianAndHindiReadAsTheyreSaid() {
        fun romanized(line: String, script: Script) = Readings.of(listOf(line), script).single().romanized
        assertEquals("Chto zhe ty, paren, ya zhdu tebya", romanized("Что же ты, парень, я жду тебя", Script.CYRILLIC))
        assertEquals("Shchuka shyot, yozh khochet chayu", romanized("Щука шьёт, ёж хочет чаю", Script.CYRILLIC))
        assertEquals("Yizhak i yenot", romanized("Їжак і єнот", Script.CYRILLIC))
        assertEquals("mujhe tumse pyar hai, sham dhal chuki", romanized("मुझे तुमसे प्यार है, शाम ढल चुकी", Script.INDIC))
        assertEquals("main hun, sambandh", romanized("मैं हूँ, संबंध", Script.INDIC))
        // An a no one says between two consonants goes too, taken from the end of the word.
        assertEquals("tu chup hai to ye dar lagta hai, apna mujhko", romanized("तू चुप है तो ये डर लगता है, अपना मुझको", Script.INDIC))
        assertEquals("samajhna bachpan sundarta", romanized("समझना बचपन सुंदरता", Script.INDIC))
        // ड़ and ੜ are typed d, and ऋ ri.
        assertEquals("phir se ud chala, kudi, kripa krishna", romanized("फिर से उड़ चला, ਕੁੜੀ, कृपा कृष्ण", Script.INDIC))
        // Telugu says the a its words end in.
        assertEquals("prema", romanized("ప్రేమ", Script.INDIC))
        // English in the line stays as it is.
        assertEquals("sham attractive, chal", romanized("ਸ਼ਾਮ attractive, ਚੱਲ", Script.INDIC))
    }

    @Test
    fun aChineseTitleIsWrittenInSimplifiedCharacters() {
        assertEquals("小幸运", Readings.simplified("小幸運"))
        assertEquals("我的少女时代 Our Times - 小幸运", Readings.simplified("我的少女時代 Our Times - 小幸運"))
        // Nothing else changes.
        assertEquals("Plastic Love", Readings.simplified("Plastic Love"))
    }

    @Test
    @Config(sdk = [28])
    fun beforeAndroid10ATitleIsLeftAsWritten() {
        assertEquals("小幸運", Readings.simplified("小幸運"))
    }

    @Test
    fun aChineseNameIsSaidInPlainLetters() {
        assertEquals("qingtian", Readings.pinyinLetters("晴天"))
        assertEquals("guangnianzhiwai", Readings.pinyinLetters("光年之外 (Light Years Away)"))
        assertNull(Readings.pinyinLetters("Plastic Love"))
    }

    @Test
    @Config(sdk = [29])
    fun fromAndroid10AChineseNameIsSaidInLetters() {
        assertEquals("qingtian", Readings.pinyinLetters("晴天"))
    }

    @Test
    @Config(sdk = [28])
    fun beforeAndroid10AChineseNameCantBeSaidInLetters() {
        assertNull(Readings.pinyinLetters("晴天"))
    }

    @Test
    @Config(sdk = [28])
    fun beforeAndroid10OtherScriptsHaveNoLatinReading() {
        assertNull(Readings.displayScriptOf(listOf("Привет, мир")))
    }

    @Test
    @Config(sdk = [28])
    fun beforeAndroid10ChineseHasNoReadings() {
        assertNull(Readings.scriptOf(listOf("我爱你")))
        assertEquals(Script.JAPANESE, Readings.scriptOf(listOf("ありがとう")))
    }

    @Test
    fun japaneseKanjiGetTheirKanaAndTheLineItsRomaji() {
        val (line) = Readings.of(listOf("夜空に星が"), Script.JAPANESE)
        assertEquals(listOf(Ruby("夜空", "よぞら"), Ruby("に"), Ruby("星", "ほし"), Ruby("が")), line.parts)
        assertEquals("よぞらにほしが", line.reading)
        assertEquals("yozora ni hoshi ga", line.romanized)
    }

    @Test
    fun kanaAKanjiEndsOrStartsWithReadsAsWritten() {
        val (eat) = Readings.of(listOf("食べる"), Script.JAPANESE)
        assertEquals(listOf(Ruby("食", "た"), Ruby("べる")), eat.parts)
        val (polite) = Readings.of(listOf("お茶"), Script.JAPANESE)
        assertEquals("お", polite.parts.first().text)
        assertEquals(Ruby("茶", "ちゃ"), polite.parts.last())
    }

    @Test
    fun kanaInsideAWordKeepsItsReadingOffAndEachKanjiRunKeepsItsOwn() {
        val (flying) = Readings.of(listOf("窓辺からやがて飛び立つ"), Script.JAPANESE)
        assertEquals(
            listOf(Ruby("窓辺", "まどべ"), Ruby("からやがて"), Ruby("飛", "と"), Ruby("び"), Ruby("立", "た"), Ruby("つ")),
            flying.parts
        )
        assertEquals("まどべからやがてとびたつ", flying.reading)
        val (handle) = Readings.of(listOf("取り扱い"), Script.JAPANESE)
        assertEquals(listOf(Ruby("取", "と"), Ruby("り"), Ruby("扱", "あつか"), Ruby("い")), handle.parts)
        val (think) = Readings.of(listOf("思い出す"), Script.JAPANESE)
        assertEquals(listOf(Ruby("思", "おも"), Ruby("い"), Ruby("出", "だ"), Ruby("す")), think.parts)
        // At the end, at the start and both: the same as before.
        assertEquals(listOf(Ruby("食", "た"), Ruby("べる")), Readings.of(listOf("食べる"), Script.JAPANESE)[0].parts)
        assertEquals(listOf(Ruby("お"), Ruby("茶", "ちゃ")), Readings.of(listOf("お茶"), Script.JAPANESE)[0].parts)
        assertEquals(listOf(Ruby("お"), Ruby("願", "ねが"), Ruby("い")), Readings.of(listOf("お願い"), Script.JAPANESE)[0].parts)
    }

    @Test
    fun aWordWithKanaInItIsSplitAroundThemWhereverItIsRead() {
        assertEquals(listOf(Ruby("飛", "と"), Ruby("び"), Ruby("立", "た"), Ruby("つ")), Readings.furigana("飛び立つ", "とびたつ"))
        // Katakana kana count too, however the reading spells them.
        assertEquals(listOf(Ruby("手", "て"), Ruby("ー"), Ruby("袋", "ぶくろ")), Readings.furigana("手ー袋", "てーぶくろ"))
        assertEquals(listOf(Ruby("飛", "と"), Ruby("ビ"), Ruby("立", "た")), Readings.furigana("飛ビ立", "とびた"))
        // A kana that is also in the reading is looked for again where the first try leaves nothing for the rest.
        assertEquals(listOf(Ruby("切", "き"), Ruby("り"), Ruby("取", "と"), Ruby("り"), Ruby("線", "せん")), Readings.furigana("切り取り線", "きりとりせん"))
        assertEquals(listOf(Ruby("気", "き"), Ruby("に"), Ruby("入", "い"), Ruby("る")), Readings.furigana("気に入る", "きにいる"))
        // A reading that isn't this word's: the kana at its ends are left out of it, and the rest is one group.
        assertEquals(listOf(Ruby("飛び立", "ゆく"), Ruby("つ")), Readings.furigana("飛び立つ", "ゆく"))
        assertEquals(listOf(Ruby("飛び立", "とべばた"), Ruby("つ")), Readings.furigana("飛び立つ", "とべばたつ"))
        // Kanji alone, or none, have nothing to split around.
        assertEquals(listOf(Ruby("東京", "とうきょう")), Readings.furigana("東京", "とうきょう"))
        assertEquals(listOf(Ruby("2人", "ふたり")), Readings.furigana("2人", "ふたり"))
        assertEquals(listOf(Ruby("ひらがな")), Readings.furigana("ひらがな", "ひらがな"))
    }

    @Test
    fun piecesKeptWithTheirKanaUnderAReadingAreSplitToo() {
        val kept = listOf(Ruby("窓辺", "まどべ"), Ruby("からやがて"), Ruby("飛び立", "とびた"), Ruby("つ"))
        assertEquals(
            listOf(Ruby("窓辺", "まどべ"), Ruby("からやがて"), Ruby("飛", "と"), Ruby("び"), Ruby("立", "た"), Ruby("つ")),
            Readings.rubied(kept)
        )
        val right = Readings.rubied(kept)
        assertEquals(right, Readings.rubied(right))
        // Chinese and kanji alone are as they were.
        val pinyin = listOf(Ruby("我", "wǒ"), Ruby("爱", "ài"))
        assertEquals(pinyin, Readings.rubied(pinyin))
        assertEquals(listOf(Ruby("飛び立", "ゆく"), Ruby("つ")), Readings.rubied(listOf(Ruby("飛び立つ", "ゆく"))))
        // What it made is as it should be, so it is left as it is.
        assertEquals(listOf(Ruby("飛び立", "ゆく"), Ruby("つ")), Readings.rubied(listOf(Ruby("飛び立", "ゆく"), Ruby("つ"))))
    }

    @Test
    fun aReadingThatSpellsTheKanaAnotherWayGoesOverTheKanjiAndOverTheKanaNotSaidAsWritten() {
        fun over(surface: String, reading: String, vararg parts: Ruby) = assertEquals("$surface $reading", parts.toList(), Readings.furigana(surface, reading))
        // Katakana for hiragana and small kana for big ones, and the other way round.
        over("飛び立つ", "トビタツ", Ruby("飛", "ト"), Ruby("び"), Ruby("立", "タ"), Ruby("つ"))
        over("食べちゃう", "たべちやう", Ruby("食", "た"), Ruby("べちゃう"))
        over("行つて", "いって", Ruby("行", "い"), Ruby("つて"))
        over("行って", "いつて", Ruby("行", "い"), Ruby("って"))
        // The old kana aren't read as written, so what they're said as goes over them: ゐ, ゑ, ひ, ほ and ふ said い, え, い, お and う.
        over("住まゐ", "すまい", Ruby("住", "す"), Ruby("ま"), Ruby("ゐ", "い"))
        over("植ゑる", "うえる", Ruby("植", "う"), Ruby("ゑ", "え"), Ruby("る"))
        over("思ひ出", "おもいで", Ruby("思", "おも"), Ruby("ひ", "い"), Ruby("出", "で"))
        over("遠ほい", "トオイ", Ruby("遠", "ト"), Ruby("ほ", "オ"), Ruby("い"))
        over("言ふ", "ゆう", Ruby("言", "ゆ"), Ruby("ふ", "う"))
        // ヲ, づ and ぢ are said as written: お, ず and じ.
        over("愛ヲ", "あいお", Ruby("愛", "あい"), Ruby("ヲ"))
        over("近づく", "ちかずく", Ruby("近", "ちか"), Ruby("づく"))
        over("恥ぢる", "はじる", Ruby("恥", "は"), Ruby("ぢる"))
        // A kana said as it's not written: は, へ and ヶ, with what it's said as over it.
        over("今日は", "こんにちわ", Ruby("今日", "こんにち"), Ruby("は", "わ"))
        over("山へ", "やまえ", Ruby("山", "やま"), Ruby("へ", "え"))
        over("3ヶ月", "さんかげつ", Ruby("3", "さん"), Ruby("ヶ", "か"), Ruby("月", "げつ"))
        over("3ヶ所", "さんがしょ", Ruby("3", "さん"), Ruby("ヶ", "が"), Ruby("所", "しょ"))
        over("一ヶ月", "いっこげつ", Ruby("一", "いっ"), Ruby("ヶ", "こ"), Ruby("月", "げつ"))
        // Spelled the way it's written, the other way round, it's still read as written.
        over("今日わ", "こんにちは", Ruby("今日", "こんにち"), Ruby("わ"))
        // A long vowel drawn out as ー, or spelled with the vowel that follows, in the word or in the reading.
        over("凄ーい", "すごおい", Ruby("凄", "すご"), Ruby("ーい"))
        over("凄おい", "すごーい", Ruby("凄", "すご"), Ruby("おい"))
        over("思う", "おもー", Ruby("思", "おも"), Ruby("う"))
        over("通う", "かよう", Ruby("通", "かよ"), Ruby("う"))
        over("遠い", "とおい", Ruby("遠", "とお"), Ruby("い"))
        over("大きい", "おおきー", Ruby("大", "おお"), Ruby("きい"))
        over("大きー", "おおきい", Ruby("大", "おお"), Ruby("きー"))
        // A run longer than what's left of the reading isn't in it.
        over("見ていた", "みて", Ruby("見", "み"), Ruby("ていた"))
        over("先生ぇ", "せんせい", Ruby("先生", "せんせ"), Ruby("ぇ"))
        over("今日は", "きょうは", Ruby("今日", "きょう"), Ruby("は"))
    }

    @Test
    fun whenTheReadingDoesNotHaveTheKanaTheyAreLeftOutOfItAndNeverRepeated() {
        fun over(surface: String, reading: String, vararg parts: Ruby) = assertEquals("$surface $reading", parts.toList(), Readings.furigana(surface, reading))
        // At the start of a word, at its end, and both.
        over("お茶", "ちゃ", Ruby("お"), Ruby("茶", "ちゃ"))
        over("食べる", "たべ", Ruby("食", "た"), Ruby("べる"))
        over("お願い", "ねがえ", Ruby("お"), Ruby("願", "ねがえ"), Ruby("い"))
        // In the middle, between kanji, it can't be left out of the one reading, but what's found around it is.
        over("気に入る", "けいる", Ruby("気に入", "けい"), Ruby("る"))
        over("切り取り線", "きとせん", Ruby("切り取り線", "きとせん"))
        over("思って", "おもえて", Ruby("思っ", "おもえ"), Ruby("て"))
        over("思ッテ", "おもえて", Ruby("思ッ", "おもえ"), Ruby("テ"))
        over("行ってる", "いえてる", Ruby("行っ", "いえ"), Ruby("てる"))
        over("思って出", "おもえてで", Ruby("思っ", "おもえ"), Ruby("て"), Ruby("出", "で"))
        over("思って出", "おもったで", Ruby("思", "おも"), Ruby("っ"), Ruby("て出", "たで"))
        // Nothing to read it over.
        over("ひらがな", "ゆく", Ruby("ひらがな"))
        over("東京", "", Ruby("東京"))
    }

    @Test
    fun aWordKeptWithItsReadingShowsItOverItsKanjiOnly() {
        assertEquals(listOf(Ruby("飛", "と"), Ruby("び"), Ruby("立", "た"), Ruby("つ")), Readings.rubyOf("飛び立つ", "とびたつ"))
        assertEquals(listOf(Ruby("我爱", "wǒài")), Readings.rubyOf("我爱", "wǒài"))
    }

    @Test
    fun aReadingFixedByHandReachesTheKanjiNextToKana() {
        val (line) = Readings.of(listOf("飛び立つ"), Script.JAPANESE)
        val fixed = Readings.corrected(listOf(line), listOf(ReadingFix(0, "飛", "とび")))[0]
        assertEquals(listOf(Ruby("飛", "と"), Ruby("び"), Ruby("立", "た"), Ruby("つ")), fixed.parts)
        val other = Readings.corrected(listOf(line), listOf(ReadingFix(0, "飛", "ひ")))[0]
        assertEquals(listOf(Ruby("飛", "ひ"), Ruby("び"), Ruby("立", "た"), Ruby("つ")), other.parts)
        assertEquals("ひびたつ", other.reading)
    }

    @Test
    fun wordsTheDictionaryDoesntKnowReadAsWritten() {
        val (line) = Readings.of(listOf("I love 東京"), Script.JAPANESE)
        assertEquals(Ruby("東京", "とうきょう"), line.parts.last())
        assertEquals("I love ", line.parts.first().text)
        assertEquals("I love tookyoo", line.romanized)
        val (blank) = Readings.of(listOf(""), Script.JAPANESE)
        assertEquals("", blank.romanized)
    }

    @Test
    fun kanjiInTheirOldFormsReadAsTheirNewOnes() {
        // 燈 is how 灯 was written before, and lyrics keep it for its feel.
        val (old) = Readings.of(listOf("ガス燈が夜を"), Script.JAPANESE)
        val (new) = Readings.of(listOf("ガス灯が夜を"), Script.JAPANESE)
        assertEquals(new.romanized, old.romanized)
        assertEquals(new.reading, old.reading)
        assertEquals(new.parts.map { it.text.replace('灯', '燈') to it.reading }, old.parts.map { it.text to it.reading })
        assertEquals("gasutoo ga yoru o", old.romanized)
    }

    @Test
    fun romajiKeepsAVerbWithItsEndingsAndMarksAgainstTheirWords() {
        fun romanized(line: String) = Readings.of(listOf(line), Script.JAPANESE).single().romanized
        assertEquals("wakatta", romanized("分かった"))
        assertEquals("tokete yuku", romanized("溶けてゆく"))
        assertEquals("「sayonara」 dake datta", romanized("「さよなら」だけだった"))
        assertEquals("kasanatte ita", romanized("重なっていた"))
        // The particles は and へ are said wa and e, as written they're ha and he.
        assertEquals("kimi wa", romanized("君は"))
        assertEquals("umi e", romanized("海へ"))
        assertEquals("hana", romanized("花"))
        assertEquals("wa", Readings.words("君は", Script.JAPANESE).last().romanized)
    }

    @Test
    fun romajiIsAsTheWordsAreSaid() {
        fun romanized(line: String) = Readings.of(listOf(line), Script.JAPANESE).single().romanized
        // Long vowels are drawn out as they're sung, though the kana write them otherwise.
        assertEquals("sensee", romanized("先生"))
        assertEquals("geejutsu", romanized("芸術"))
        assertEquals("koinu no yoo ni", romanized("仔犬のように"))
        assertEquals("yoo", Readings.words("仔犬のように", Script.JAPANESE).first { it.text == "よう" }.romanized)
        // Only within a word: 思う's う is said on its own.
        assertEquals("omou", romanized("思う"))
    }

    @Test
    fun romajiCanBeSpelledAsAKeyboardTypesIt() {
        fun read(line: String) = Readings.of(listOf(line), Script.JAPANESE).single()
        // Long vowels as the kana write them, and を as wo; the particles は and へ still wa and e.
        assertEquals("sensei", read("先生").typed)
        assertEquals("toukyou wo", read("東京を").typed)
        assertEquals("tookyoo o", read("東京を").romanized)
        assertEquals("kimi wa umi e", read("君は海へ").typed)
        assertEquals("koinu no you ni", read("仔犬のように").typed)
        assertEquals("toukyou wo", read("東京を").latin(typed = true))
        assertEquals("tookyoo o", read("東京を").latin(typed = false))
        val word = Readings.words("先生", Script.JAPANESE).single()
        assertEquals("sensee", word.romanized)
        assertEquals("sensei", word.typed)
        // A line already in Latin letters has neither.
        assertEquals("", read("Just summer night").typed)
        // Chinese and Korean are spelled one way.
        val korean = Readings.of(listOf("사랑해"), Script.KOREAN).single()
        assertEquals(korean.romanized, korean.typed)
        assertEquals("saranghae", Readings.words("사랑해", Script.KOREAN).single().typed)
    }

    @Test
    fun bothRomajiShowThePickedOneFirstAndTheOtherWhereItDiffers() {
        assertEquals(listOf("sensee", "sensei"), bothRomaji("sensee", "sensei", typedFirst = false))
        assertEquals(listOf("sensei", "sensee"), bothRomaji("sensee", "sensei", typedFirst = true))
        assertEquals(listOf("kimi wa"), bothRomaji("kimi wa", "kimi wa", typedFirst = true))
        // Kept before they were typed too, a line has only the one.
        assertEquals(listOf("sensee"), bothRomaji("sensee", null, typedFirst = true))
        assertEquals(emptyList<String>(), bothRomaji(null, "", typedFirst = false))
    }

    @Test
    fun numbersOfPeopleReadAsOneWord() {
        val (line) = Readings.of(listOf("二人で一人"), Script.JAPANESE)
        assertEquals(listOf(Ruby("二人", "ふたり"), Ruby("で"), Ruby("一人", "ひとり")), line.parts)
        assertEquals("futari de hitori", line.romanized)
        // Other numbers stay as the dictionary reads them.
        assertEquals("さんにん", Readings.of(listOf("三人"), Script.JAPANESE).single().reading)
    }

    @Test
    fun romajiFollowsHepburn() {
        assertEquals("shashin", Readings.romaji("しゃしん"))
        assertEquals("kitte", Readings.romaji("きって"))
        assertEquals("matcha", Readings.romaji("まっちゃ"))
        assertEquals("kon'ya", Readings.romaji("こんや"))
        assertEquals("hon'i", Readings.romaji("ほんい"))
        assertEquals("konnichiha", Readings.romaji("こんにちは"))
        // Katakana too, with ー lengthening the vowel before it.
        assertEquals("raamen", Readings.romaji("ラーメン"))
        assertEquals("fan", Readings.romaji("ファン"))
        // What isn't kana stays as it is.
        assertEquals("a!", Readings.romaji("あ!"))
        assertEquals("", Readings.romaji("ー"))
    }

    @Test
    fun chineseHanziGetTheirPinyin() {
        val (line) = Readings.of(listOf("我爱你!"), Script.CHINESE)
        assertEquals(listOf(Ruby("我", "wǒ"), Ruby("爱", "ài"), Ruby("你", "nǐ"), Ruby("!")), line.parts)
        assertEquals("wǒ ài nǐ!", line.romanized)
        assertEquals(line.romanized, line.reading)
    }

    @Test
    fun bopomofoIsAlreadyAReadingThoughItsㄧIsWrittenAs一() {
        // Jay Chou's 晴天 writes a sound "ㄒ一", with 一 for the bopomofo ㄧ; elsewhere 一 is a word.
        val (line) = Readings.of(listOf("ㄒ一 一起 一ㄠˋ"), Script.CHINESE)
        assertEquals(listOf(Ruby("ㄒ一 "), Ruby("一", "yī"), Ruby("起", "qǐ"), Ruby(" 一ㄠˋ")), line.parts)
        assertEquals("ㄒㄧ yī qǐ ㄧㄠˋ", line.romanized)
    }

    @Test
    fun everyBopomofoLetterIsAReading() {
        // The first and last of bopomofo's letters, and of its extended ones.
        for (letter in listOf('ㄅ', 'ㄯ', 'ㆠ', 'ㆿ')) {
            val (line) = Readings.of(listOf("${letter}一"), Script.CHINESE)
            assertEquals(letter.toString(), listOf(Ruby("${letter}一")), line.parts)
        }
    }

    @Test
    fun koreanIsRomanizedAsItsSung() {
        val (line) = Readings.of(listOf("사랑해 한국"), Script.KOREAN)
        assertEquals(listOf(Ruby("사랑해 한국")), line.parts)
        assertEquals("saranghae hanguk", line.romanized)
        val said = mapOf(
            "꽃밭" to "kkotbat", "강아지" to "gangaji",
            // A vowel takes the sound before it.
            "있을까요" to "isseulkkayo", "찾아" to "chaja", "다섯이" to "daseosi", "없이" to "eopsi", "밟아" to "balba",
            // ㄷ and ㅌ soften before i.
            "굳이" to "guji", "같이" to "gachi", "붙여" to "buchyeo", "닫혀" to "dachyeo",
            // ㅎ goes quiet, or makes the sound next to it harder.
            "좋아" to "joa", "많이" to "mani", "싫어" to "sireo", "이렇게" to "ireoke", "좋다" to "jota", "좋지" to "jochi", "좋습니다" to "josseumnida",
            "축하" to "chuka", "입학" to "ipak", "깨끗해" to "kkaekkeutae", "밝혀" to "balkyeo", "맞혀" to "machyeo", "전화" to "jeonhwa",
            "놓는" to "nonneun", "않는" to "anneun", "싫니" to "silli", "좋며" to "jomyeo",
            // Through the nose before n and m.
            "국물" to "gungmul", "있는" to "inneun", "합니다" to "hamnida", "끝나" to "kkeunna", "읽는" to "ingneun", "없는" to "eomneun",
            // ㄴ and ㄹ.
            "신라" to "silla", "설날" to "seollal", "몰라" to "molla", "심리" to "simni", "종로" to "jongno", "독립" to "dongnip", "협력" to "hyeomnyeok",
            // Two consonants closing a syllable sound as one.
            "닭" to "dak", "맑게" to "malge", "삶" to "sam", "읊다" to "eupda", "여덟" to "yeodeol", "값" to "gap", "앉다" to "anda", "넋" to "neok", "많다" to "manta"
        )
        assertEquals(said, said.mapValues { Readings.romaja(it.key) })
        // Only within a word.
        assertEquals("ot an, illyeon", Readings.romaja("옷 안, 1년"))
    }

    @Test
    fun koreanNumbersInDigitsAreSaidAsWhatTheyCountIs() {
        val said = mapOf(
            // Korean's own numbers count hours, years of age, people and things.
            "12시" to "yeoldusi", "1시" to "hansi", "2시" to "dusi", "24시간" to "seumullesigan", "20살" to "seumusal", "16살" to "yeollyeoseotsal", "6살" to "yeoseotsal",
            "99살" to "aheunahopsal", "2개" to "dugae",
            // Those from Chinese count all else, and anything past 99.
            "1년" to "illyeon", "6년" to "yungnyeon", "7월의" to "chirworui", "11월" to "sibirwol", "6월" to "yuwol", "10월" to "siwol", "3개월" to "samgaewol",
            "0부터" to "yeongbuteo", "0시" to "yeongsi", "100까지" to "baekkkaji", "176에" to "baekchilsimnyuge", "100살" to "baeksal", "1000번" to "cheonbeon",
            "2024년" to "icheonisipsanyeon", "10000원" to "manwon", "100000000원" to "ireogwon", "지난1년은" to "jinanillyeoneun",
            // Only digits a word in Hangul goes on from.
            "11:11 속에" to "11:11 soge", "MP3를" to "MP3reul", "3.5배" to "3.5bae", "1,000원" to "1,000won", "007가" to "007ga", "2 살" to "2 sal"
        )
        assertEquals(said, said.mapValues { Readings.romaja(it.key) })
    }

    @Test
    fun aWordInOldKanaSaysHowItIsSpelledToday() {
        fun modern(surface: String, reading: String, said: String? = null) = Furigana.modern(surface, *listOfNotNull(reading, said).toTypedArray())
        // ゐ and ゑ, and ひ, ふ and ほ said い, う and お.
        assertEquals("住まい", modern("住まゐ", "すまい"))
        assertEquals("植える", modern("植ゑる", "うえる"))
        assertEquals("思い出", modern("思ひ出", "おもいで"))
        assertEquals("言う", modern("言ふ", "ゆう"))
        assertEquals("遠おい", modern("遠ほい", "トオイ"))
        assertEquals("言い", modern("言ひ", "いい"))
        // Said the other way round, when the first reading has no old kana to find.
        assertEquals("思い出", modern("思ひ出", "おもひで", "おもいで"))
        // A modern spelling has none to name, ヶ and the particles は and へ included.
        assertNull(modern("思い出", "おもいで"))
        assertNull(modern("三ヶ月", "さんかげつ"))
        assertNull(modern("今日は", "こんにちわ"))
        assertNull(modern("都へ", "みやこえ"))
        // As written and said alike, a ひ stays: 日々 has none, and a reading that isn't the word's names nothing.
        assertNull(modern("ひとり", "ひとり"))
        assertNull(modern("思ひ出", "ゆく"))
        assertNull(modern("思ひ出", ""))
    }

    private fun modernKana(surface: String, vararg forms: String) = Furigana.modernKana(surface, forms.toList())

    @Test
    fun aWordInOldKanaAloneIsSpelledTodayByTheFormThatSaysItAlike() {
        // The dictionary form's reading, the way it's said, and the old kana joined to the one before it.
        assertEquals("いう", modernKana("いふ", "イウ"))
        assertEquals("まいる", modernKana("まゐる", "マイル"))
        assertEquals("ちょうちょう", modernKana("てふてふ", "チョウチョウ"))
        assertEquals("きょう", modernKana("けふ", "キョウ"))
        assertEquals("ぎょうぎ", modernKana("げふぎ", "ぎょうぎ"))
        assertEquals("いい", modernKana("いひ", "いい"))
        assertEquals("あうぎ", modernKana("あふぎ", "あうぎ"))
        assertEquals("まいり", modernKana("まゐり", "マイリ"))
        // The next form is tried when the first doesn't say it alike: ー for a long vowel, or the form of another word.
        assertEquals("いう", modernKana("いふ", "ユー", "イウ"))
        assertNull(modernKana("いふ", "ユー"))
        assertNull(modernKana("いひ", "イウ"))
        assertNull(modernKana("とこ", "トコロ"))
        assertNull(modernKana("ひとり", "ヒトリ"))
        // Only a word in hiragana with old kana in it has one.
        assertNull(modernKana("思ひ出", "オモイデ"))
        assertNull(modernKana("いう", "イウ"))
        assertNull(modernKana("いふ"))
        assertNull(modernKana("いふ", "イウエ"))
        assertNull(modernKana("いふう", "イウ"))
        // Hiragana is ぁ to ゖ, both ends in.
        assertEquals(true, Furigana.inOldKana("ぁひゖ"))
        assertEquals(false, Furigana.inOldKana("ひ\u3097"))
        assertEquals(false, Furigana.inOldKana("\u3040ひ"))
        assertEquals(false, Furigana.inOldKana("あい"))
    }

    @Test
    fun aWordInOldKanaIsLookedUpAsTheWordItsSpellingTodayIs() {
        fun lookups(line: String) = Readings.words(line, Script.JAPANESE).map { it.lookup }
        // A translator knows 云う and 思い出, and takes 云ふ for a name and 思ふ for another word than 思ひ出: what's asked for is the word the card names.
        fun inBoth(expected: List<String>, line: String) {
            assertEquals("$line without UniDic", expected, lookups(line))
            TestUniDic.reading { assertEquals("$line with UniDic", expected, lookups(line)) }
        }
        inBoth(listOf("思い出"), "思ひ出")
        inBoth(listOf("思い出す"), "思ひ出して")
        inBoth(listOf("住まい"), "住まゐ")
        inBoth(listOf("云う"), "云ふ")
        inBoth(listOf("言う"), "言ふ")
        inBoth(listOf("言う"), "言ひ")
        inBoth(listOf("恋う"), "恋ひ")
        inBoth(listOf("いる"), "ゐる")
        inBoth(listOf("いる"), "ゐた")
        // The lyric's own text is what's written.
        assertEquals(listOf("思ひ出"), Readings.words("思ひ出", Script.JAPANESE).map { it.text })
        // What IPADIC splits, or reads as written, is the word its spelling today is.
        inBoth(listOf("植える"), "植ゑる")
        inBoth(listOf("きょう"), "けふ")
        inBoth(listOf("ちょうちょ"), "てふてふ")
        inBoth(listOf("まいる"), "まゐる")
        inBoth(listOf("いう"), "いふ")
        // A modern word, or one with old kana in no word of its own, is asked for as it is, ひ and ふ and ほ in it or not.
        inBoth(listOf("夜空", "に", "分かる"), "夜空に分かった")
        inBoth(listOf("思い出"), "思い出")
        inBoth(listOf("ひとり"), "ひとり")
        TestUniDic.reading { assertEquals(listOf("言う", "ふたり"), lookups("言う ふたり")) }
    }

    @Test
    fun aWordInOldKanaIsReadAsTheWordItsSpellingTodayIsWithOrWithoutUniDic() {
        // Text, reading, spelling today, and the word it is looked up as: the same from either dictionary.
        val words = listOf(
            listOf("けふ", "きょう", "きょう", "きょう"),
            listOf("まゐる", "まいる", "まいる", "まいる"),
            listOf("植ゑる", "うえる", "植える", "植える"),
            listOf("いふ", "いう", "いう", "いう"),
            listOf("てふてふ", "ちょうちょう", "ちょうちょう", "ちょうちょ"),
            listOf("ゐた", "いた", "いた", "いる"),
            listOf("嫌ひ", "きらい", "嫌い", "嫌う"),
            listOf("耐へない", "たえない", "耐えない", "耐える"),
            listOf("超へられぬ", "こえられぬ", "超えられぬ", "超える"),
            listOf("逢ひたい", "あいたい", "逢いたい", "逢う"),
            listOf("使ふ", "つかう", "使う", "使う"),
            listOf("笑ひ", "わらい", "笑い", "笑い"),
            listOf("思ひ出", "おもいで", "思い出", "思い出")
        )
        fun check() = words.forEach { (text, reading, modern, lookup) ->
            val word = Readings.words(text, Script.JAPANESE).single()
            assertEquals(text, listOf(text, reading, modern, lookup), listOf(word.text, word.reading, word.modern, word.lookup))
        }
        check()
        TestUniDic.reading { check() }
    }

    @Test
    fun aWordInOldKanaIsSaidAndShownTheWayItsSpellingTodayIsSaid() {
        fun line(text: String) = Readings.of(listOf(text), Script.JAPANESE).single()
        // 云ふ is うん and ふ to IPADIC: it's said いう, with い over the kanji.
        line("云ふ").let {
            assertEquals(listOf(Ruby("云", "い"), Ruby("ふ", "う")), it.parts)
            assertEquals("いう", it.kana)
            assertEquals("iu", it.romanized)
            assertEquals("iu", it.typed)
        }
        // 思ひ出 is the same line with or without UniDic: the kanji's readings, and い over ひ.
        val omoide = listOf(Ruby("思", "おも"), Ruby("ひ", "い"), Ruby("出", "で"))
        assertEquals(omoide, line("思ひ出").parts)
        assertEquals("omoide", line("思ひ出").romanized)
        TestUniDic.reading { assertEquals(omoide, line("思ひ出").parts) }
        // The lyric's words are the ones it has, as the dictionary splits what is around them.
        assertEquals("matsute itanda yo", line("待つてゐたんだよ").romanized)
        assertEquals(listOf("kyoo", "ame"), line("けふ 雨").romanized.split(" ").map { it.replace("kyou", "kyoo") }.take(2))
    }

    @Test
    fun aModernLineHasNoWordInOldKana() {
        // Modern kana the old spellings share: ひ, ふ and ほ, へ and は after kanji or kana, and words IPADIC splits oddly.
        val lines = listOf(
            "ふふ、ひとひら舞い落ちる", "あひるが泳ぐ", "おふろに入ろう", "きほんは大事", "にほんの夜明け", "いまはまだ", "これはなに", "それはね秘密", "海へ行こう",
            "家へ帰る道", "君へ", "空へ飛び立つ", "夢へ向かって", "あふれる想い", "ほほえむ君", "ひふがきれい", "くふうして生きる", "たいへんだ", "ふたりでひとり",
            "ひとりぼっち", "ふゆのひ", "ほしがふる夜", "はるのかぜ", "ふわふわ", "ひらひら", "へへへ", "ほほほ", "ふーん", "あしたへ", "明日へ続く", "未来へ", "どこへ行くの",
            "ここへおいで", "では、また", "には負けない", "とは言えない", "ふとした瞬間", "おもいで", "思い出の場所", "笑い声", "使う", "言う", "会う", "買う", "洗う",
            "歌う", "植える", "考える", "迎える", "ふれあい", "ひかり", "ほのお", "へんじ", "はなびら", "いふく", "ひふく", "ふふふ", "ほおずき", "ふうせん", "ゆうひ",
            "ふたたび", "ひかりへ", "ひとへ", "きみへ", "愛してる、ふたりへ", "ほほ", "一人ひとり", "二人ふたり", "手ふり", "君のひ", "来てふたり", "見てふと"
        )
        fun noWordInOldKana() = lines.forEach { line ->
            assertEquals(line, emptyList<String>(), Readings.words(line, Script.JAPANESE).mapNotNull { it.modern })
            assertEquals(line, line, Readings.of(listOf(line), Script.JAPANESE).single().parts.joinToString("") { it.text })
        }
        noWordInOldKana()
        TestUniDic.reading { noWordInOldKana() }
    }

    @Test
    fun aKanaThatTheDictionaryTakesForAWordAndAnOldEndingIsOneModernWord() {
        // ふ and ひ after a verb's stem of one kana say う and い only when the stem has kanji: ふふ is a laugh, not ふ and an old ending.
        listOf("ふふ" to "fufu", "ひふ" to "hifu").forEach { (text, romaji) ->
            assertEquals(emptyList<String>(), Readings.words(text, Script.JAPANESE).mapNotNull { it.modern })
            assertEquals(romaji, Readings.of(listOf(text), Script.JAPANESE).single().romanized.replace(" ", ""))
        }
    }

    @Test
    fun aLineWithLettersTheDictionaryTakesForMarksStillSplitsIntoWords() {
        // Hangul in a Japanese line is a word of marks: it looks itself up.
        val words = Readings.words("사랑 愛", Script.JAPANESE)
        assertTrue(words.any { it.text.contains("愛") })
        assertTrue(words.any { it.text.contains("사랑") && it.lookup.contains("사랑") })
    }

    @Test
    fun aWordIsSpelledTodayByRuleOnlyWhereItCanOnlyBeOldKana() {
        fun today(text: String) = Furigana.byRule(text)?.text
        // ゐ and ゑ, and a contraction like けふ, are old wherever they are.
        assertEquals("いる", today("ゐる"))
        assertEquals("うえる", today("うゑる"))
        assertEquals("きょう", today("けふ"))
        assertEquals("ちょうちょう", today("てふてふ"))
        assertEquals("ぎょうぎ", today("げふぎ"))
        // ひ, ふ and へ after kanji say い, う and え.
        assertEquals("思い出", today("思ひ出"))
        assertEquals("云う", today("云ふ"))
        assertEquals("耐え", today("耐へ"))
        assertEquals("海え", today("海へ"))
        // In kana alone, only after い: modern words have them too.
        assertEquals("いう", today("いふ"))
        assertEquals("いい", today("いひ"))
        listOf("ふふ", "ひふ", "あひる", "おふろ", "ひとり", "海を", "ほほ", "かへる", "へへ", "思い出", "いま").forEach { assertNull(it, today(it)) }
        // Where each letter comes from, which ones were a ひ, ふ or へ said another way, and where the first change is.
        val omoide = requireNotNull(Furigana.byRule("思ひ出"))
        assertEquals(listOf(0, 1, 2), omoide.origins)
        assertEquals(listOf(1), omoide.plain)
        assertEquals(1, omoide.first)
        val kyou = requireNotNull(Furigana.byRule("今日けふ"))
        assertEquals(listOf(0, 1, 2, 2, 2), kyou.origins)
        assertEquals(emptyList<Int>(), kyou.plain)
        assertEquals(listOf(2), kyou.contracted)
        assertEquals(2, kyou.first)
    }

    @Test
    fun aWordCarriesItsModernSpellingOnlyWhenItIsWrittenInOldKana() {
        assertNull(Readings.words("夜空に、分かった!", Script.JAPANESE).firstNotNullOfOrNull { it.modern })
        // The shipped dictionary says 思ひ出 as it's sung. It splits the old ending off 恋ひ and 住まゐ, which is said with its word.
        assertEquals("思い出", Readings.words("思ひ出", Script.JAPANESE).single().modern)
        Readings.words("恋ひ", Script.JAPANESE).single().let {
            assertEquals(listOf("恋ひ", "こい", "恋う", "恋い"), listOf(it.text, it.reading, it.lookup, it.modern))
        }
        assertEquals("住まい", Readings.words("住まゐ", Script.JAPANESE).single().modern)
        assertEquals("sumai", Readings.words("住まゐ", Script.JAPANESE).single().romanized)
        // An ending of its own with a particle between is no ending: it's a word of its own.
        assertEquals(listOf("恋", "を", "ひ"), Readings.words("恋 を ひ", Script.JAPANESE).map { it.text })
        assertEquals(listOf("まゐる"), Readings.words("まゐる", Script.JAPANESE).map { it.text })
        assertEquals(listOf("まいる"), Readings.words("まゐる", Script.JAPANESE).map { it.modern })
        assertEquals("いい", Readings.words("いひ", Script.JAPANESE).single().modern)
        assertNull(Readings.words("ふたりでほしい", Script.JAPANESE).firstNotNullOfOrNull { it.modern })
        TestUniDic.reading {
            assertEquals("思い出", Readings.words("思ひ出", Script.JAPANESE).single().modern)
            assertEquals("言う", Readings.words("言ふ", Script.JAPANESE).single().modern)
            assertEquals("言い", Readings.words("言ひ", Script.JAPANESE).single().modern)
            assertEquals("植える", Readings.words("植ゑる", Script.JAPANESE).single().modern)
            // Words in kana alone: the dictionary form's reading, and how an inflected one is said.
            assertEquals("いう", Readings.words("いふ", Script.JAPANESE).single().modern)
            assertEquals("まいる", Readings.words("まゐる", Script.JAPANESE).single().modern)
            assertEquals("ちょうちょう", Readings.words("てふてふ", Script.JAPANESE).single().modern)
            assertEquals("きょう", Readings.words("けふ", Script.JAPANESE).single().modern)
            assertEquals("まいります", Readings.words("まゐります", Script.JAPANESE).single().modern)
            assertEquals("恋い", Readings.words("恋ひ", Script.JAPANESE).single().modern)
            assertEquals("逢い", Readings.words("逢ひ", Script.JAPANESE).single().modern)
            assertNull(Readings.words("いう", Script.JAPANESE).single().modern)
            assertNull(Readings.words("ふたり", Script.JAPANESE).single().modern)
            // Its lookup and the lyric's own text keep what is written.
            assertEquals(Word("思ひ出", "おもいで", "omoide", "思い出", "omoide", "思い出"), Readings.words("思ひ出", Script.JAPANESE).single())
        }
    }

    @Test
    fun aLineSplitsIntoWordsToLookUp() {
        assertEquals(
            listOf(Word("夜空", "よぞら", "yozora", "夜空"), Word("に", null, "ni", "に"), Word("分かった", "わかった", "wakatta", "分かる")),
            Readings.words("夜空に、分かった!", Script.JAPANESE)
        )
        // Marks alone aren't words.
        assertEquals(listOf(Word("さよなら", null, "sayonara", "さよなら")), Readings.words("「さよなら」…", Script.JAPANESE))
        // Chinese has no spaces: Android's dictionary splits it.
        val chinese = Readings.words("我们喜欢音乐。", Script.CHINESE)
        assertEquals(listOf("我们", "喜欢", "音乐"), chinese.map { it.text })
        assertEquals("wǒ men", chinese.first().reading)
        assertEquals(listOf(Word("사랑해", null, "saranghae"), Word("한국", null, "hanguk")), Readings.words("사랑해, 한국!", Script.KOREAN))
        assertEquals(listOf(Word("Don't"), Word("stop"), Word("me")), Readings.words("Don't stop me 123 ♪", null))
        assertEquals(emptyList<Word>(), Readings.words("", null))
    }

    @Test
    fun aLineAlreadyInLatinLettersIsntWrittenOutAgain() {
        val korean = Readings.of(listOf("Funny how I feel for you", "처음 느낌 그대로"), Script.KOREAN)
        assertEquals("", korean[0].romanized)
        assertEquals("cheoeum neukkim geudaero", korean[1].romanized)
        assertEquals("", Readings.of(listOf("Just summer night"), Script.JAPANESE).single().romanized)
    }

    @Test
    fun englishInJapaneseLyricsIsRomanizedAsWritten() {
        // The dictionary splits "Don't" into Don, ' and t.
        assertEquals("Don't Say dare ka no udedokee wa", Readings.of(listOf("Don't Say 誰かの腕時計は"), Script.JAPANESE).single().romanized)
        assertEquals("I’m Here kimi no", Readings.of(listOf("I’m Here 君の"), Script.JAPANESE).single().romanized)
        // And looked up as one word.
        assertEquals(true, Readings.words("Don't Say 誰かの腕時計は", Script.JAPANESE).any { it.text == "Don't" })
    }

    @Test
    fun readingsTheDictionaryGotWrongArePutRight() {
        // As the dictionary really reads Asphalt Lady: 君 as くん, and 重ね着's 着 as ちゃく.
        val read = Readings.of(listOf("Shock!　君はアスファルト・レディ", "恋を一枚　重ね着したね", "ガラス越しの City Talk"), Script.JAPANESE)
        assertEquals("くん", read[0].parts.first { it.text == "君" }.reading)
        val fixed = Readings.corrected(read, listOf(ReadingFix(0, "君", "きみ"), ReadingFix(1, "着", "ぎ"), ReadingFix(2, "越", "ごし")))
        assertEquals("きみ", fixed[0].parts.first { it.text == "君" }.reading)
        assertEquals(true, fixed[0].reading.contains("きみ") && !fixed[0].reading.contains("くん"))
        assertEquals(true, fixed[0].romanized.contains("kimi wa"))
        assertEquals(true, fixed[0].typed.contains("kimi wa") && !fixed[0].typed.contains("kun"))
        assertEquals("ぎ", fixed[1].parts.first { it.text == "着" }.reading)
        // ごし for 越 is only 越's ご, with the し written after it: nothing to put right.
        assertEquals(read[2], fixed[2])
        // いま for 今 is all 今's, though ま comes after it too.
        val now = listOf(LineReading(listOf(Ruby("今", "こん"), Ruby("まで")), "こんまで", "konmade"))
        assertEquals(LineReading(listOf(Ruby("今", "いま"), Ruby("まで")), "いままで", "ima made"), Readings.corrected(now, listOf(ReadingFix(0, "今", "いま")))[0])
        // In katakana it's the same; a kanji the line hasn't, or a reading that isn't kana, is left out.
        assertEquals("きみ", Readings.corrected(read, listOf(ReadingFix(0, "君", "キミ")))[0].parts.first { it.text == "君" }.reading)
        assertEquals(read, Readings.corrected(read, listOf(ReadingFix(0, "愛", "あい"), ReadingFix(1, "着", "gi"), ReadingFix(9, "君", "きみ"), ReadingFix(0, "君", ""))))
        // Only those kanji are read otherwise: the same sounds written in kana elsewhere in the line stay.
        val kana = listOf(LineReading(listOf(Ruby("君", "くん"), Ruby("はくん")), "くんはくん", "kun wa kun"))
        assertEquals(LineReading(listOf(Ruby("君", "きみ"), Ruby("はくん")), "きみはくん", "kimi wa kun"), Readings.corrected(kana, listOf(ReadingFix(0, "君", "きみ")))[0])
        // Romaji drawn out as it's said is put right drawn out too.
        val said = listOf(LineReading(listOf(Ruby("生", "せい"), Ruby("と")), "せいと", "seeto"))
        assertEquals("shoo to", Readings.corrected(said, listOf(ReadingFix(0, "生", "しょう")))[0].romanized)
        // Inside a word romaji runs together, the new reading is in it too: atama n naka, not atama nchuu.
        val inside = Readings.corrected(Readings.of(listOf("頭ん中 埋め尽くす"), Script.JAPANESE), listOf(ReadingFix(0, "中", "なか")))[0]
        assertEquals("あたまんなか うめつくす", inside.reading)
        assertTrue(inside.romanized, "naka" in inside.romanized && "chuu" !in inside.romanized)
        assertTrue(inside.typed, "naka" in inside.typed && "chuu" !in inside.typed)
        // After a reading the lyrics give in brackets, it's still where it's written.
        val after = Readings.corrected(Readings.of(listOf("歌（うた）って頭の中"), Script.JAPANESE), listOf(ReadingFix(0, "中", "うち")))[0]
        assertEquals("うたってあたまのうち", after.reading)
        assertTrue(after.romanized, after.romanized.endsWith("uchi"))
        // Typed, it's put right as the kana spell it, only where those kanji are.
        val typed = listOf(LineReading(listOf(Ruby("生", "せい"), Ruby("と"), Ruby("せい")), "せいとせい", "seeto see", "seito sei"))
        val fixedTyped = Readings.corrected(typed, listOf(ReadingFix(0, "生", "しょう")))[0]
        assertTrue(fixedTyped.typed, fixedTyped.typed.startsWith("shou") && fixedTyped.typed.endsWith("sei"))
        assertTrue(fixedTyped.romanized, fixedTyped.romanized.startsWith("shoo") && fixedTyped.romanized.endsWith("see"))
    }

    @Test
    fun aJapaneseLineWrittenChinasWayIsWrittenAndReadAsJapanWritesIt() {
        // 瞬间 is how China writes 瞬間, which the dictionary reads; the line ends in China's comma.
        val line = Readings.asJapanWrites("真実の瞬间はいつも，")
        assertEquals("真実の瞬間はいつも", line)
        assertEquals("しんじつのしゅんかんはいつも", Readings.of(listOf(line), Script.JAPANESE).single().reading)
        // 仆 is Japan's own, but only for an old word, and Chinese sites write it for 僕.
        assertEquals("僕や君のため", Readings.asJapanWrites("仆や君のため，  "))
        // A comma inside the line, and anything Japan writes already, stays.
        assertEquals("夢，夢 ", Readings.asJapanWrites("夢，夢 "))
        assertEquals("Plastic Love", Readings.asJapanWrites("Plastic Love"))
        // One for one, so a word's timings stay where they were.
        assertEquals("瞬間は，", Readings.inJapansKanji("瞬间は，"))
        // Japan has 梦 too, as an old form, but Chinese sites write it for 夢.
        assertEquals("鏡のような夢の中で、この頃", Readings.inJapansKanji("鏡のような梦の中で、この顷"))
    }

    @Test
    fun eachOfChinasKanjiIsOneJapanDoesntWriteAndEachOfJapansOneItDoes() {
        // Japan writes what Shift_JIS, its own way of storing text, has room for.
        val japan = java.nio.charset.Charset.forName("Shift_JIS").newEncoder()
        Readings.JAPANS_KANJI.forEach { (china, japans) ->
            assertTrue("$china", china in "仆梦" || !japan.canEncode(china))
            assertTrue("$japans", japan.canEncode(japans) && Readings.isHan(japans))
        }
    }

    private fun reading(line: String) = Readings.of(listOf(line), Script.JAPANESE).single()

    @Test
    fun aDigitCountingSomethingIsReadAsItsKanjiAndStaysAsWritten() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> if (withUniDic) TestUniDic.reading { reading(line) } else reading(line) }
            // 2人 is 二人, ふたり, not two and にん.
            val line = read("雲がまだ2人の影を残すから")
            assertEquals("$withUniDic", "くもがまだふたりのかげをのこすから", line.reading)
            assertTrue("$withUniDic", Ruby("2人", "ふたり") in line.parts)
            assertEquals("$withUniDic", "kumo ga mada futari no kage o nokosukara", line.romanized)
            assertEquals("$withUniDic", "ひとつになる", read("1つになる").reading)
            assertEquals("$withUniDic", "ふたりで", read("２人で").reading)
            // Part of a bigger number, it's that number.
            assertTrue("$withUniDic", read("12人で").reading.startsWith("12"))
            assertTrue("$withUniDic", read("2024年").reading.startsWith("2024"))
        }
    }

    @Test
    fun romajiSpacesWordsWithASpace() {
        // A wide space and ・ only space words; "!", which the dictionary takes for a word, goes with the one before.
        val line = reading("Shock!　君はアスファルト・レディ")
        assertEquals("Shock! kun wa asufaruto redi", line.romanized)
        assertEquals("Shock! kun wa asufaruto redi", line.typed)
        assertEquals("Oh! Baby nee", reading("Oh! Baby　ねえ").romanized)
        assertEquals("aishiteru zutto", reading("愛してる・・・ずっと").romanized)
    }

    @Test
    fun oneRunsIntoTheCounterAfterIt() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> if (withUniDic) TestUniDic.reading { reading(line) } else reading(line) }
            assertEquals("$withUniDic", "いっぽずつ", read("一歩ずつ").reading)
            assertTrue("$withUniDic", read("一歩ずつ").romanized.startsWith("ippo"))
            assertEquals("$withUniDic", "いっぽずつ", read("1歩ずつ").reading)
            assertEquals("$withUniDic", "いっかいだけ", read("一回だけ").reading)
            assertEquals("$withUniDic", "いっぱいの", read("一杯の").reading)
            // Before other sounds it's as it was.
            assertEquals("$withUniDic", "いちどだけ", read("一度だけ").reading)
            assertEquals("$withUniDic", "いちばん", read("一番").reading)
        }
    }

    @Test
    fun lyricsReadSomeWordsTheirOwnWay() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> if (withUniDic) TestUniDic.reading { reading(line) } else reading(line) }
            // 側 on its own is そば, a word of its own.
            val near = read("あんなに側にいたのに")
            assertEquals("$withUniDic", "あんなにそばにいたのに", near.reading)
            assertTrue("$withUniDic", Ruby("側", "そば") in near.parts)
            assertTrue("$withUniDic ${near.romanized}", " soba ni " in near.romanized)
            assertEquals("$withUniDic", "きみのそばで", read("君の側で").reading)
            assertEquals("$withUniDic", "ずっとそばにいたい", read("ずっと側に居たい").reading)
            // A side of something is がわ.
            assertEquals("$withUniDic", "こっちがわにきて", read("こっち側に来て").reading)
            assertEquals("$withUniDic", "むこうがわ", read("向こう側").reading)
            assertTrue("$withUniDic", read("反対側").reading.endsWith("がわ"))
            assertTrue("$withUniDic", read("ソファ側").reading.endsWith("がわ"))
            assertTrue("$withUniDic", read("サーバー側").reading.endsWith("がわ"))
            // は and ね, not a verb, before a space.
            assertEquals("$withUniDic", "bokura wa ne sonna", read("僕らはね そんな").romanized)
            assertEquals("$withUniDic", "hane o hirogete", read("羽を広げて").romanized)
            // 違く is said for 違って.
            assertEquals("$withUniDic", "うまれたほしのもとがちがくたって", read("生まれた星のもとが違くたって").reading)
            assertEquals("$withUniDic", "ちがくて", read("違くて").reading)
            assertTrue("$withUniDic", read("違かった").reading.startsWith("ちが"))
        }
    }

    @Test
    fun withUniDicKanjiTheShippedDictionaryCantReadAreRead() {
        // 溢 isn't in it: alone, it stays as written.
        assertEquals("うつむいてきみが溢した", reading("うつむいて君が溢した").reading)
        TestUniDic.reading {
            val line = reading("うつむいて君が溢した")
            assertEquals("うつむいてきみがこぼした", line.reading)
            assertTrue(Ruby("溢", "こぼ") in line.parts)
            assertEquals("utsumuite kimi ga koboshita", line.romanized)
            // As are its words, with the form each is listed under.
            assertEquals(Word("溢した", "こぼした", "koboshita", lookup = "溢す"), Readings.words("君が溢した", Script.JAPANESE).last())
            // Said ほー, it's spelled ほお as listed, not ほう as a long o most often is.
            assertEquals("ほおをつたう", reading("頰を伝う").reading)
        }
    }

    @Test
    fun withUniDicAWordTheShippedDictionaryBreaksUpReadsAsOne() {
        assertEquals("びえみをくれたのはだれ？", reading("微笑みをくれたのは誰？").reading)
        TestUniDic.reading {
            assertEquals("ほほえみをくれたのはだれ？", reading("微笑みをくれたのは誰？").reading)
            // Its long vowels as the word's spelled, and as it's said, or typed.
            val line = reading("ザッツ！超常")
            assertEquals("ザッツ！ちょうじょう", line.reading)
            assertEquals("zattsu！ choojoo", line.romanized)
            assertEquals("zattsu！ choujou", line.typed)
        }
    }

    @Test
    fun withUniDicAWordTakenForAPrefixOrASuffixToNothingIsAWord() {
        assertEquals("くんにあいたい", reading(" 君に会いたい").reading.trim())
        assertEquals("ああこんとなりで", reading("ああ今となりで").reading)
        TestUniDic.reading {
            assertEquals("きみにあいたい", reading(" 君に会いたい").reading.trim())
            assertEquals("ああいまとなりで", reading("ああ今となりで").reading)
            // A suffix to a word is one: 通り after 予想.
            assertEquals("それはよそうどおり", reading("それは予想通り").reading)
        }
    }

    @Test
    fun withUniDicTheShippedDictionaryStillReadsLyricsWhereItKnowsThem() {
        TestUniDic.reading {
            // UniDic has 私 as わたくし, and splits 不器用.
            assertEquals("わたしはぶきようだけど", reading("私は不器用だけど").reading)
            // What neither knows stays as written.
            assertEquals("龘", reading("龘").reading)
        }
    }

    @Test
    fun withUniDicAVerbFollowingAnotherIsOneWordWithIt() {
        TestUniDic.reading {
            assertEquals("sonomama mitsuzukeyoo", reading("そのまま見続けよう").romanized)
        }
    }

    @Test
    fun readingsTheLyricsGiveInBracketsAreTheKanjisAndArentReadAgain() {
        val line = reading("新（あたら）しい歌（うた） 歌（うた）えるまで")
        assertEquals("あたらしいうた うたえるまで", line.reading)
        assertEquals("atarashii uta utaeru made", line.romanized)
        // Shown as written, with the reading over the kanji too.
        assertEquals("新（あたら）しい歌（うた） 歌（うた）えるまで", line.parts.joinToString("") { it.text })
        assertEquals(listOf(Ruby("新", "あたら"), Ruby("歌", "うた"), Ruby("歌", "うた")), line.parts.filter { it.reading != null })
        // In round brackets, for kanji the dictionary can't read, and in katakana too.
        assertEquals("もがいている", reading("足搔(もが)いている").reading)
        assertEquals("にがくて", reading("苦（ニガ）くて").reading)
        // Before more kanji, those are read as in the word: 声 of 歌声, ごえ; or on their own, when it's read otherwise.
        val voice = reading("歌（うた）声")
        assertEquals("うたごえ", voice.reading)
        assertEquals(listOf(Ruby("歌", "うた"), Ruby("（うた）"), Ruby("声", "ごえ")), voice.parts)
        assertEquals("かこえ", reading("歌（か）声").reading)
        // After kana the dictionary reads with them, the reading still goes over its kanji.
        assertEquals(listOf(Ruby("お"), Ruby("茶", "ちゃ"), Ruby("（ちゃ）")), reading("お茶（ちゃ）").parts)
        // Further into the line, and with a reading for the kanji after too.
        assertEquals(listOf(Ruby("君", "きみ"), Ruby("の"), Ruby("新", "あたら"), Ruby("（あたら）しい")), reading("君の新（あたら）しい").parts)
        reading("あの頃の歌（うた）声（こえ）").let {
            assertEquals("あのころのうたこえ", it.reading)
            assertEquals(listOf(Ruby("歌", "うた"), Ruby("（うた）"), Ruby("声", "こえ"), Ruby("（こえ）")), it.parts.takeLast(4))
        }
        // Looked up as the word it is.
        assertEquals("悲しい", Readings.words("悲（かな）しい", Script.JAPANESE).single().text)
        // Brackets of anything else are words.
        assertEquals("きみ (きみ)", reading("君 (きみ)").reading)
    }

    @Test
    fun jamoAndRareKanjiCountAsKoreanAndChinese() {
        assertEquals(Script.KOREAN, Readings.scriptOf(listOf("가")))
        assertEquals(Script.KOREAN, Readings.scriptOf(listOf("ㅋㅋㅋ")))
        assertEquals(Script.CHINESE, Readings.scriptOf(listOf("㐀")))
        assertEquals(Script.CHINESE, Readings.scriptOf(listOf("々")))
        assertNull(Readings.scriptOf(listOf("ĀŌ")))
    }

    @Test
    fun marksAloneAreNoWordsToLookUp() {
        assertEquals(emptyList<Word>(), Readings.words("♪ … ♪", Script.JAPANESE))
        assertEquals(emptyList<Word>(), Readings.words("123 !!", Script.JAPANESE))
    }

    @Test
    fun aDigitAtTheEndOfALineIsLeftAsWritten() {
        assertEquals("人 2", reading("人 2").parts.joinToString("") { it.text })
        assertEquals("2", reading("2").reading)
        assertEquals("100人", reading("100人").parts.joinToString("") { it.text })
    }

    @Test
    fun cyrillicKeepsWhatHasNoLettersInIt() {
        val read = Readings.of(listOf("Мир, 12 ветер"), Script.CYRILLIC).single().romanized
        assertEquals("Mir, 12 veter", read)
    }

    @Test
    fun koreanSoundsChangeWhereASyllableEndsInRAndTheNextStartsWithIt() {
        fun romanized(line: String) = Readings.of(listOf(line), Script.KOREAN).single().romanized
        assertEquals("dalla", romanized("달라"))
        assertEquals("rara", romanized("라라"))
        assertEquals("dal", romanized("달"))
    }

    @Test
    fun aBopomofoOneIsAReadingNextToBopomofoOnEitherSide() {
        fun read(line: String) = Readings.of(listOf(line), Script.CHINESE).single().reading
        assertEquals(read("ㄇㄧ"), read("ㄇ一"))
        assertEquals(read("ㄧㄇ"), read("一ㄇ"))
    }

    @Test
    fun aFixNeedsTheKanjiToBeReadAndOnlyChangesAReadingItDiffersFrom() {
        val read = listOf(LineReading(listOf(Ruby("君", "くん"), Ruby("は"), Ruby("君", "きみ")), "くんはきみ", "kun wa kimi"))
        // The kana の isn't a kanji with a reading: nothing to put right.
        assertEquals(read, Readings.corrected(read, listOf(ReadingFix(0, "は", "き"))))
        // The same reading it has is no fix.
        assertEquals(read, Readings.corrected(read, listOf(ReadingFix(0, "君", "くん"))))
        // Only the kanji read as the first was.
        val fixed = Readings.corrected(read, listOf(ReadingFix(0, "君", "きみ")))[0]
        assertEquals(listOf("きみ", null, "きみ"), fixed.parts.map { it.reading })
    }
}
