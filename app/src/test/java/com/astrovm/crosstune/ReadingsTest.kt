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
    fun koreanIsRomanizedSyllableBySyllable() {
        val (line) = Readings.of(listOf("사랑해 한국"), Script.KOREAN)
        assertEquals(listOf(Ruby("사랑해 한국")), line.parts)
        assertEquals("saranghae hanguk", line.romanized)
        assertEquals("kkotbat", Readings.romaja("꽃밭"))
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
        // In katakana it's the same; a kanji the line hasn't, or a reading that isn't kana, is left out.
        assertEquals("きみ", Readings.corrected(read, listOf(ReadingFix(0, "君", "キミ")))[0].parts.first { it.text == "君" }.reading)
        assertEquals(read, Readings.corrected(read, listOf(ReadingFix(0, "愛", "あい"), ReadingFix(1, "着", "gi"), ReadingFix(9, "君", "きみ"), ReadingFix(0, "君", ""))))
        // Only those kanji are read otherwise: the same sounds written in kana elsewhere in the line stay.
        val kana = listOf(LineReading(listOf(Ruby("君", "くん"), Ruby("はくん")), "くんはくん", "kun wa kun"))
        assertEquals(LineReading(listOf(Ruby("君", "きみ"), Ruby("はくん")), "きみはくん", "kimi wa kun"), Readings.corrected(kana, listOf(ReadingFix(0, "君", "きみ")))[0])
        // Romaji drawn out as it's said is put right drawn out too.
        val said = listOf(LineReading(listOf(Ruby("生", "せい"), Ruby("と")), "せいと", "seeto"))
        assertEquals("shooto", Readings.corrected(said, listOf(ReadingFix(0, "生", "しょう")))[0].romanized)
        // Typed, it's put right as the kana spell it, only where those kanji are.
        val typed = listOf(LineReading(listOf(Ruby("生", "せい"), Ruby("と"), Ruby("せい")), "せいとせい", "seeto see", "seito sei"))
        val fixedTyped = Readings.corrected(typed, listOf(ReadingFix(0, "生", "しょう")))[0]
        assertEquals("shouto sei", fixedTyped.typed)
        assertEquals("shooto see", fixedTyped.romanized)
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
    }

    @Test
    fun eachOfChinasKanjiIsOneJapanDoesntWriteAndEachOfJapansOneItDoes() {
        // Japan writes what Shift_JIS, its own way of storing text, has room for.
        val japan = java.nio.charset.Charset.forName("Shift_JIS").newEncoder()
        Readings.JAPANS_KANJI.forEach { (china, japans) ->
            assertTrue("$china", china == '仆' || !japan.canEncode(china))
            assertTrue("$japans", japan.canEncode(japans) && Readings.isHan(japans))
        }
    }

    private fun reading(line: String) = Readings.of(listOf(line), Script.JAPANESE).single()

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
}
