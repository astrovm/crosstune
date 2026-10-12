package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }
}
