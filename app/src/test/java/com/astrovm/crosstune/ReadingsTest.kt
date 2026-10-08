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
        assertEquals("I love toukyou", line.romanized)
        val (blank) = Readings.of(listOf(""), Script.JAPANESE)
        assertEquals("", blank.romanized)
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
}
