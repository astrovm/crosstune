package com.astrovm.crosstune

import com.atilika.kuromoji.ipadic.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class ReadingsTest {

    @Test
    fun futariAfterANounIsNotAnOldKanaVerb() = assertJapaneseBoundaries(listOf(
        Triple("おとなふたりとこどもひとり", "otona futari to kodomo hitori", listOf("おとな", "ふたり", "と", "こども", "ひとり")),
        Triple("私たちふたり", "watashitachi futari", listOf("私たち", "ふたり")),
        Triple("ふたりで", "futari de", listOf("ふたり", "で")),
        Triple("ふたりきり", "futari kiri", listOf("ふたり", "きり")),
    ))

    @Test
    fun tariAfterTeOrDeStaysWithTheVerb() = assertJapaneseBoundaries(listOf(
        Triple("読んでたりする", "yondetari suru", listOf("読んでたり", "する")),
        Triple("食べてたりする", "tabetetari suru", listOf("食べてたり", "する")),
        Triple("車で食べたりする", "kuruma de tabetari suru", listOf("車", "で", "食べたり", "する")),
    ))

    @Test
    fun tariFormsStayWithTheirVerbOrAdjective() = assertJapaneseBoundaries(listOf(
        Triple("食べたり", "tabetari", listOf("食べたり")),
        Triple("行ったり", "ittari", listOf("行ったり")),
        Triple("読んだり", "yondari", listOf("読んだり")),
        Triple("遊んだり", "asondari", listOf("遊んだり")),
        Triple("高かったり", "takakattari", listOf("高かったり")),
        Triple("静かだったり", "shizuka dattari", listOf("静か", "だったり")),
        Triple("読んだりする", "yondari suru", listOf("読んだり", "する")),
        Triple("あたり", "atari", listOf("あたり")),
        Triple("本たり", "hon tari", listOf("本", "たり")),
    ))

    @Test
    fun copulasHaveTheirOwnRomajiAndPracticeWords() {
        val lines = listOf("無駄だから", "人間だから", "時代ですから")
        val romaji = listOf("muda da kara", "ningen da kara", "jidai desu kara")
        val words = listOf(listOf("無駄", "だ", "から"), listOf("人間", "だ", "から"), listOf("時代", "です", "から"))
        fun read() = Readings.of(lines, Script.JAPANESE).map { it.romanized } to lines.map { line ->
            Readings.words(line, Script.JAPANESE).map { it.text }
        }
        val expected = romaji to words
        assertEquals(listOf(expected, expected), listOf(read(), TestUniDic.reading(::read)))
    }

    @Test
    fun bothDictionariesTagTheCopulaButNotTheVoicedPastAsACopula() {
        val line = "読んだ。鳥だ。鳥です。鳥ダ。森じゃない。綺麗な鳥。鳥だった。"
        val ipadic = Tokenizer.Builder().build().tokenize(line)
        val unidic = TestUniDic.tokenizer.tokenize(line)
        val heads = listOf("だ", "です", "だっ")
        assertEquals(heads, ipadic.filter { Readings.startsCopula(it.partOfSpeechLevel1, it.conjugationType, it.surface) }.map { it.surface })
        assertEquals(listOf("だ", "です", "ダ", "だっ"),
            unidic.filter { Readings.startsCopula(it.partOfSpeechLevel1, it.conjugationType, it.surface) }.map { it.surface })
    }

    @Test
    fun theRestOfTheCopulaStaysInTheSameWord() {
        val cases = listOf(
            Triple("本だった", "hon datta", listOf("本", "だった")),
            Triple("本でした", "hon deshita", listOf("本", "でした")),
            Triple("本だろう", "hon darou", listOf("本", "だろう")),
            Triple("本でしょう", "hon deshou", listOf("本", "でしょう")),
            Triple("本ですか", "hon desu ka", listOf("本", "です", "か")),
            Triple("春だったら", "haru dattara", listOf("春", "だったら")),
            Triple("本だったり", "hon dattari", listOf("本", "だったり")),
            Triple("『夢』だった", "『yume』 datta", listOf("夢", "だった")),
            Triple("綺麗です", "kiree desu", listOf("綺麗", "です")),
            Triple("高いです", "takai desu", listOf("高い", "です")),
            Triple("高かったです", "takakatta desu", listOf("高かった", "です")),
            Triple("太郎様だ", "taroosama da", listOf("太郎様", "だ"))
        )
        assertJapaneseBoundaries(cases)
    }

    @Test
    fun ambiguousDeshiteKeepsTheExistingBoundary() = assertJapaneseBoundaries(listOf(
        Triple("仕事はあとでして", "shigoto wa atodeshite", listOf("仕事", "は", "あとでして")),
        Triple("作業は二人でして", "sagyoo wa futarideshite", listOf("作業", "は", "二人でして")),
        Triple("ここは学校でして", "koko wa gakkoodeshite", listOf("ここ", "は", "学校でして")),
        Triple("ここは学校でした", "koko wa gakkoo deshita", listOf("ここ", "は", "学校", "でした")),
    ))

    @Test
    fun bothDictionariesGiveTheTwoUsesOfDeshiteTheSameTags() {
        val ipadic = Tokenizer.Builder().build()
        val unidic = TestUniDic.tokenizer
        fun ending(type: String, form: String) = listOf(
            listOf("でし", "助動詞", "*", type, form),
            listOf("て", "助詞", "接続助詞", "*", "*")
        )
        for (line in listOf("仕事はあとでして", "作業は二人でして", "ここは学校でして")) {
            assertEquals(line, ending("特殊・デス", "連用形"), ipadic.tokenize(line).takeLast(2).map {
                listOf(it.surface, it.partOfSpeechLevel1, it.partOfSpeechLevel2, it.conjugationType, it.conjugationForm)
            })
            assertEquals(line, ending("助動詞-デス", "連用形-一般"), unidic.tokenize(line).takeLast(2).map {
                listOf(it.surface, it.partOfSpeechLevel1, it.partOfSpeechLevel2, it.conjugationType, it.conjugationForm)
            })
        }
    }

    @Test
    fun interrogativeNanDaSeparatesAfterATopic() = assertJapaneseBoundaries(listOf(
        Triple("これは何だ", "kore wa nan da", listOf("これ", "は", "何", "だ")),
        Triple("それは何だ?", "sore wa nan da?", listOf("それ", "は", "何", "だ")),
        Triple("君は何だ", "kimi wa nan da", listOf("君", "は", "何", "だ")),
        Triple("仕事が何だ", "shigoto ga nan da", listOf("仕事", "が", "何", "だ")),
        Triple("これも何だ", "kore mo nan da", listOf("これ", "も", "何", "だ")),
        Triple("それ何だ", "sore nan da", listOf("それ", "何", "だ")),
        Triple("何だ？", "nan da？", listOf("何", "だ")),
    ))

    @Test
    fun nanDaInterjectionsAndOtherExpressionsKeepTheirWords() = assertJapaneseBoundaries(listOf(
        Triple("何だ、お前か", "nanda、 omae ka", listOf("何だ", "お前", "か")),
        Triple("それから何だ、お前か", "sorekara nanda、 omae ka", listOf("それから", "何だ", "お前", "か")),
        Triple("何だ君か", "nan da kimi ka", listOf("何", "だ", "君", "か")),
        Triple("なんだ君か", "nan da kimi ka", listOf("なん", "だ", "君", "か")),
        Triple("なんだ、君か", "nanda、 kimi ka", listOf("なんだ", "君", "か")),
        Triple("「なんだ、君か」", "「nanda、 kimi ka」", listOf("なんだ", "君", "か")),
        Triple("これはなんだ、君か", "kore wa nan da、 kimi ka", listOf("これ", "は", "なん", "だ", "君", "か")),
        Triple("ああ、なんだ、君か", "aa、 nanda、 kimi ka", listOf("ああ", "なんだ", "君", "か")),
        Triple("なんだ, 君か", "nanda, kimi ka", listOf("なんだ", "君", "か")),
        Triple("なんだ", "nanda", listOf("なんだ")),
        Triple("何だ", "nanda", listOf("何だ")),
        Triple("何だか眠い", "nandaka nemui", listOf("何だか", "眠い")),
        Triple("何だかんだ", "nandakanda", listOf("何だかんだ")),
        Triple("何だって？", "nan datte？", listOf("何", "だって")),
        Triple("なんだかなあ", "nandaka naa", listOf("なんだか", "なあ")),
    ))

    @Test
    fun nanDaReviewCasesRespectBoundariesAndTopics() = assertJapaneseBoundaries(listOf(
        Triple("何だ お前か", "nanda omae ka", listOf("何だ", "お前", "か")),
        Triple("何だ，君か", "nanda， kimi ka", listOf("何だ", "君", "か")),
        Triple("なんだ，君か", "nanda， kimi ka", listOf("なんだ", "君", "か")),
        Triple("「何だ」", "「nanda」", listOf("何だ")),
        Triple("何だ！", "nanda！", listOf("何だ")),
        Triple("何だ何だ!?", "nanda nanda!?", listOf("何だ", "何だ")),
        Triple("何だ何だ？", "nanda nanda？", listOf("何だ", "何だ")),
        Triple("なんだなんだ", "nanda nanda", listOf("なんだ", "なんだ")),
        Triple("なんだなんだなんだ!?", "nanda nanda nanda!?", listOf("なんだ", "なんだ", "なんだ")),
        Triple("なんだ何だ", "nanda nanda", listOf("なんだ", "何だ")),
        Triple("なんだ何だ？", "nanda nanda？", listOf("なんだ", "何だ")),
        Triple("何だなんだ", "nanda nanda", listOf("何だ", "なんだ")),
        Triple("なんだ、なんだ", "nanda、 nanda", listOf("なんだ", "なんだ")),
        Triple("これは一体何だ", "kore wa ittai nan da", listOf("これ", "は", "一体", "何", "だ")),
        Triple("これはいったい何だ", "kore wa ittai nan da", listOf("これ", "は", "いったい", "何", "だ")),
        Triple("これは本当に何だ", "kore wa hontooni nan da", listOf("これ", "は", "本当に", "何", "だ")),
        Triple("これはいったいなんだ", "kore wa ittai nan da", listOf("これ", "は", "いったい", "なん", "だ")),
        Triple("これは本当になんだ？", "kore wa hontooni nan da？", listOf("これ", "は", "本当に", "なん", "だ")),
        Triple("これもなんだ？", "kore mo nan da？", listOf("これ", "も", "なん", "だ")),
    ))

    @Test
    fun explanatoryNandaStaysWholeAndInterrogativeNanSeparates() = assertJapaneseBoundaries(listOf(
        Triple("今日はここまでなんだ", "kyoo wa koko made nanda", listOf("今日", "は", "ここ", "まで", "なんだ")),
        Triple("つらいからなんだよ", "tsurai kara nanda yo", listOf("つらい", "から", "なんだ", "よ")),
        Triple("なんだろう、この音は", "nan darou、 kono oto wa", listOf("なん", "だろう", "この", "音", "は")),
        Triple("何だろう、この音は", "nan darou、 kono oto wa", listOf("何", "だろう", "この", "音", "は")),
        Triple("「なんだろう」", "「nan darou」", listOf("なん", "だろう")),
        Triple("これはなんだ", "kore wa nan da", listOf("これ", "は", "なん", "だ")),
        Triple("これは何だろう", "kore wa nan darou", listOf("これ", "は", "何", "だろう")),
        Triple("仕事がなんだ", "shigoto ga nan da", listOf("仕事", "が", "なん", "だ")),
        Triple("つらいがなんだよ", "tsuraiga nanda yo", listOf("つらいが", "なんだ", "よ")),
    ))

    @Test
    fun onlyTheInterrogativeNanWithACopulaGetsANewBoundary() = assertJapaneseBoundaries(listOf(
        Triple("なんだよ", "nan da yo", listOf("なん", "だ", "よ")),
        Triple("なんだよ、この音は", "nan da yo、 kono oto wa", listOf("なん", "だ", "よ", "この", "音", "は")),
        Triple("なんだか眠い", "nandaka nemui", listOf("なんだか", "眠い")),
        Triple("なんだって？", "nan datte？", listOf("なん", "だって")),
        Triple("なんで帰る", "nande kaeru", listOf("なんで", "帰る")),
        Triple("そうなんだね", "soona nda ne", listOf("そうな", "んだ", "ね")),
        Triple("猫が好きなんだ", "neko ga sukina nda", listOf("猫", "が", "好きな", "んだ")),
        Triple("この音は何なんだ", "kono oto wa nanna nda", listOf("この", "音", "は", "何な", "んだ")),
    ))

    @Test
    fun katakanaCopulasAndConnectivesGetTheSameBoundaries() = assertJapaneseBoundaries(listOf(
        Triple("無駄ダカラ", "muda da kara", listOf("無駄", "ダ", "カラ")),
        Triple("無駄だから", "muda da kara", listOf("無駄", "だ", "から")),
        Triple("無駄ナラバ", "muda naraba", listOf("無駄", "ナラバ")),
        Triple("無駄ならば", "muda naraba", listOf("無駄", "ならば")),
    ))

    @Test
    fun unattachedKatakanaKaraAndOtherKatakanaParticlesKeepTheirBoundaries() = assertJapaneseBoundaries(listOf(
        Triple("カラダが熱い", "karada ga atsui", listOf("カラダ", "が", "熱い")),
        Triple("僕ノカラダガ熱イ", "boku no karada ga atsui", listOf("僕", "ノ", "カラダ", "ガ", "熱イ")),
        Triple("車デ帰る", "kuruma de kaeru", listOf("車", "デ", "帰る")),
    ))

    @Test
    fun nonCopulaEndingsAndWordsKeepTheirBoundaries() = assertJapaneseBoundaries(listOf(
        Triple("読んで", "yonde", listOf("読んで")),
        Triple("死んで", "shinde", listOf("死んで")),
        Triple("車で帰る", "kuruma de kaeru", listOf("車", "で", "帰る")),
        Triple("だから", "dakara", listOf("だから")),
        Triple("でも", "demo", listOf("でも")),
        Triple("だって", "datte", listOf("だって")),
        Triple("だが", "daga", listOf("だが")),
        Triple("だるい", "darui", listOf("だるい")),
        Triple("ダンス", "dansu", listOf("ダンス")),
    ))

    private fun assertJapaneseBoundaries(cases: List<Triple<String, String, List<String>>>) {
        val lines = cases.map { it.first }
        fun read() = Readings.of(lines, Script.JAPANESE).mapIndexed { index, reading ->
            val line = lines[index]
            Triple(line, reading.romanized, Readings.words(line, Script.JAPANESE).map { it.text })
        }
        assertEquals(listOf(cases, cases), listOf(read(), TestUniDic.reading(::read)))
    }

    @Test
    fun theVoicedPastAndFixedAuxiliaryPhrasesKeepTheirRomaji() {
        val cases = mapOf(
            "読んだ" to "yonda", "死んだ" to "shinda", "泳いだ" to "oyoida", "読んだら" to "yondara", "読んだり" to "yondari",
            "読んだそうだ" to "yonda sooda", "読むそうだ" to "yomu sooda", "泳ぐようだ" to "oyogu yooda", "夢のようだ" to "yume no yooda",
            "そうだ" to "sooda", "そうです" to "soodesu", "読むのだ" to "yomu noda", "読むんだ" to "yomu nda", "のだ" to "noda", "んだ" to "nda",
            "本なんだ" to "honna nda", "夢なのだ" to "yumena noda", "本じゃない" to "hon ja nai", "本ではない" to "hon de wa nai",
            "本である" to "hondearu", "綺麗な" to "kireena", "人ですら" to "hitode sura",
            "夢の様だ" to "yume no yooda", "夜景みたいだ" to "yakee mitaida", "会いたくなんだ" to "aitaku nanda", "痛くなんだ" to "itaku nanda",
            "ひとなでして" to "hitonadeshite"
        )
        fun readings() = Readings.of(cases.keys.toList(), Script.JAPANESE).map { it.romanized }
        val expected = cases.values.toList()
        assertEquals(listOf(expected, expected), listOf(readings(), TestUniDic.reading(::readings)))
    }

    @Test
    fun anApostropheBeforeKaraOpensOnlyWithoutAWord() {
        val cases = listOf(
            Triple("'からだ", "' karada", listOf("からだ")),
            Triple("君'からだ", "kimi' kara da", listOf("君", "から", "だ")),
            Triple("Don'tからだ", "Don't kara da", listOf("Don't", "から", "だ")),
            Triple("1'からだ", "1' kara da", listOf("から", "だ")),
            Triple("、'からだ", "、' karada", listOf("からだ"))
        )
        for ((line, romaji, words) in cases) {
            fun read() = Readings.of(listOf(line), Script.JAPANESE).single().romanized to Readings.words(line, Script.JAPANESE).map { it.text }
            assertEquals(line, listOf(romaji to words, romaji to words), listOf(read(), TestUniDic.reading { read() }))
        }
    }

    @Test
    fun additionalOpeningMarksStartAPhrase() {
        for (mark in listOf("［", "[", "‘", "〔", "｛", "{", "〖", "〘", "〚", "｢", "«", "‹", "⟨", "〝")) {
            val line = "${mark}からだ"
            val expected = "${mark}karada" to listOf("からだ")
            fun read() = Readings.of(listOf(line), Script.JAPANESE).single().romanized to Readings.words(line, Script.JAPANESE).map { it.text }
            assertEquals(line, listOf(expected, expected), listOf(read(), TestUniDic.reading { read() }))
        }
    }

    @Test
    fun aStraightQuoteAfterAWordCanOpenAPhrase() {
        val cases = listOf(
            Triple("君\"からだ\"", "kimi\" karada\"", listOf("君", "からだ")),
            Triple("\"LOVE\"の\"からだ\"", "\" LOVE\" no\" karada\"", listOf("LOVE", "の", "からだ")),
            Triple("君\"からだ\"からだ", "kimi\" karada\" kara da", listOf("君", "からだ", "から", "だ"))
        )
        for ((line, romaji, words) in cases) {
            fun read() = Readings.of(listOf(line), Script.JAPANESE).single().romanized to Readings.words(line, Script.JAPANESE).map { it.text }
            assertEquals(line, listOf(romaji to words, romaji to words), listOf(read(), TestUniDic.reading { read() }))
        }
    }

    @Test
    fun aClosingQuoteKeepsTheWordBeforeTheParticle() {
        val line = "「君」からだ"
        val expected = "「kimi」 kara da" to listOf("君", "から", "だ")
        fun read() = Readings.of(listOf(line), Script.JAPANESE).single().romanized to Readings.words(line, Script.JAPANESE).map { it.text }
        assertEquals(listOf(expected, expected), listOf(read(), TestUniDic.reading { read() }))
    }

    @Test
    fun particlesDoNotSplitOtherWords() {
        val cases = listOf(
            Triple("さようなら", "sayoonara", listOf("さようなら")),
            Triple("並ぶ星", "narabu hoshi", listOf("並ぶ", "星")),
            Triple("ならぶ", "narabu", listOf("ならぶ")),
            Triple("習う", "narau", listOf("習う")),
            Triple("ならう", "narau", listOf("ならう")),
            Triple("体", "karada", listOf("体")),
            Triple("からだ", "karada", listOf("からだ")),
            Triple("からだが熱い", "karada ga atsui", listOf("からだ", "が", "熱い")),
            Triple("僕のからだが熱い", "boku no karada ga atsui", listOf("僕", "の", "からだ", "が", "熱い")),
            Triple("君のからだが", "kimi no karada ga", listOf("君", "の", "からだ", "が")),
            Triple("僕にからだを", "boku ni karada o", listOf("僕", "に", "からだ", "を")),
            Triple("「からだが熱い」", "「karada ga atsui」", listOf("からだ", "が", "熱い")),
            Triple("、からだが熱い", "、 karada ga atsui", listOf("からだ", "が", "熱い")),
            Triple("君 からだが熱い", "kimi karada ga atsui", listOf("君", "からだ", "が", "熱い")),
            Triple("空から降る", "sora kara furu", listOf("空", "から", "降る")),
            Triple("のなら", "no nara", listOf("の", "なら")),
            Triple("「ならば」", "「naraba」", listOf("ならば")),
            Triple("君 ならば", "kimi naraba", listOf("君", "ならば")),
            Triple("「君」から", "「kimi」 kara", listOf("君", "から")),
            Triple("『夢』なら", "『yume』 nara", listOf("夢", "なら")),
            Triple("『夢』からだ", "『yume』 kara da", listOf("夢", "から", "だ")),
            Triple("(笑)から", "(emi) kara", listOf("笑", "から")),
            Triple("(笑)からだ", "(emi) kara da", listOf("笑", "から", "だ")),
            Triple("\"LOVE\"なら", "\" LOVE\" nara", listOf("LOVE", "なら")),
            Triple("\"LOVE\"からだ", "\" LOVE\" kara da", listOf("LOVE", "から", "だ")),
            Triple("\"からだが熱い\"", "\" karada ga atsui\"", listOf("からだ", "が", "熱い")),
            Triple("からい", "karai", listOf("からい")),
            Triple("からす", "karasu", listOf("からす")),
            Triple("空っぽ", "karappo", listOf("空っぽ")),
            Triple("からっぽ", "karappo", listOf("からっぽ")),
            Triple("それなら", "sorenara", listOf("それなら")),
            Triple("だから", "dakara", listOf("だから")),
            Triple("なぜなら", "nazenara", listOf("なぜなら")),
            Triple("奈良", "nara", listOf("奈良")),
            Triple("ありきたりな喜びきっと二人なら見つけられる", "arikitari na yorokobi kitto futari nara mitsukerareru",
                listOf("ありきたり", "な", "喜び", "きっと", "二人", "なら", "見つけられる")),
            Triple("抱きしめた温もりで溶かすから", "dakishimeta nukumori de tokasu kara", listOf("抱きしめた", "温もり", "で", "溶かす", "から"))
        )
        for ((line, romaji, words) in cases) {
            fun read() = Readings.of(listOf(line), Script.JAPANESE).single().romanized to Readings.words(line, Script.JAPANESE).map { it.text }
            assertEquals(line, romaji to words, read())
            assertEquals(line, romaji to words, TestUniDic.reading { read() })
        }
    }

    @Test
    fun japaneseWordsAndParticlesHaveSeparateRomaji() {
        val lines = listOf("ありきたりな喜びきっと二人なら見つけられる", "抱きしめた温もりで溶かすから", "誰かを想いやりゃあだになり")
        val expected = listOf(
            "arikitari na yorokobi kitto futari nara mitsukerareru", "dakishimeta nukumori de tokasu kara", "dare ka o omoi yarya ada ni nari"
        )
        fun readings() = Readings.of(lines, Script.JAPANESE).map { it.romanized }
        assertEquals(listOf(expected, expected), listOf(readings(), TestUniDic.reading { readings() }))
    }

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
        assertEquals(listOf(Word("мир", null, "mir", by = listOf(Reader.LATIN))), Readings.words("мир", Script.CYRILLIC))
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
        val fixed = Readings.corrected(listOf(line), listOf(ReadingFix(0, "飛", "とび", Reader.SUNG)))[0]
        assertEquals(listOf(Ruby("飛", "と"), Ruby("び"), Ruby("立", "た"), Ruby("つ")), fixed.parts)
        val other = Readings.corrected(listOf(line), listOf(ReadingFix(0, "飛", "ひ", Reader.SUNG)))[0]
        assertEquals(listOf(Ruby("飛", "ひ", Reader.SUNG), Ruby("び"), Ruby("立", "た"), Ruby("つ")), other.parts)
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
    fun longMarksDrawOutKanaAcrossDictionarySplits() {
        val lines = mapOf(
            "ぱーてぃー" to "paatii",
            "めんそーれ" to "mensoore",
            "やっ ほー" to "ya hoo",
            "とぅもろー" to "tumoroo",
            "ひぇー" to "hyee"
        )
        fun readings() = Readings.of(lines.keys.toList(), Script.JAPANESE).map { it.romanized to it.typed }
        val expected = lines.values.map { it to it }
        assertEquals(listOf(expected, expected), listOf(readings(), TestUniDic.reading { readings() }))
    }

    @Test
    fun longMarksStayWithTheVowelWithoutTakingTheNextWord() {
        val lines = mapOf("ほーー" to "hooo", "うれしーよ" to "ureshii yo", "ねーきみは" to "nee kimi wa", "ぎたーを" to "gitaa o", "ほ ー" to "ho")
        fun readings() = Readings.of(lines.keys.toList(), Script.JAPANESE).map { it.romanized }
        val expected = lines.values.toList()
        assertEquals(listOf(expected, expected), listOf(readings(), TestUniDic.reading { readings() }))
    }

    @Test
    fun wordsWithLongMarksStayWholeOnTheWordSheet() {
        val lines = mapOf("ぱーてぃー" to "paatii", "めんそーれ" to "mensoore", "とぅもろー" to "tumoroo", "ひぇー" to "hyee")
        fun readings() = lines.keys.map { line ->
            Readings.words(line, Script.JAPANESE).map { Triple(it.text, it.romanized, it.typed) }
        }
        val expected = lines.map { (line, romaji) -> listOf(Triple(line, romaji, romaji)) }
        assertEquals(listOf(expected, expected), listOf(readings(), TestUniDic.reading { readings() }))
    }

    @Test
    fun smallVowelsKeepTheDictionaryWordTheyDrawOut() {
        fun readings() = listOf("だぁいすき", "ばかぁ").map { line ->
            Readings.words(line, Script.JAPANESE).map { Triple(it.text, it.romanized, it.lookup) }
        }
        val expected = listOf(listOf(Triple("だぁいすき", "daaisuki", "大好き")), listOf(Triple("ばかぁ", "bakaa", "ばか")))
        assertEquals(listOf(expected, expected), listOf(readings(), TestUniDic.reading { readings() }))
    }

    @Test
    fun longMarkKeepsTheNextWordSeparate() = assertDrawnReading(
        "食べるー好きだよ", "taberuu suki da yo",
        Triple("食べるー", "taberuu", "食べる"), Triple("好き", "suki", "好き"), Triple("だ", "da", "だ"), Triple("よ", "yo", "よ")
    )

    @Test
    fun onlyTheLeadingLongMarksOfATokenAttach() {
        assertDrawnReading(
            "食べるーーコーヒー", "taberuuu koohii",
            Triple("食べるーー", "taberuuu", "食べる"), Triple("コーヒー", "koohii", "コーヒー")
        )
        assertDrawnReading(
            "おぉーーコーヒー飲む", "oooo koohii nomu",
            Triple("おぉーー", "oooo", "おお"), Triple("コーヒー", "koohii", "コーヒー"), Triple("飲む", "nomu", "飲む")
        )
        assertDrawnReading(
            "食べるーコーヒーぃ", "taberuu koohiii",
            Triple("食べるー", "taberuu", "食べる"), Triple("コーヒーぃ", "koohiii", "コーヒー")
        )
    }

    @Test
    fun smallTsuBeforeALongMarkDoesNotDoubleTheNextWord() {
        assertDrawnReading("あっーた", "a ta", Triple("あっー", "a", "あっ"), Triple("た", "ta", "た"))
        assertEquals("ata", Readings.romaji("あっーーた"))
    }

    @Test
    fun drawnOutInterjectionKeepsItsParticleSeparate() = assertDrawnReading(
        "おぉと叫ぶ", "oo to sakebu",
        Triple("おぉ", "oo", "おお"), Triple("と", "to", "と"), Triple("叫ぶ", "sakebu", "叫ぶ")
    )

    @Test
    fun drawnOutAdverbKeepsItsDictionaryForm() {
        assertDrawnReading(
            "そーだと思う", "sooda to omou",
            Triple("そーだ", "sooda", "そう"), Triple("と", "to", "と"), Triple("思う", "omou", "思う")
        )
        assertDrawnReading(
            "そーだが信じない", "soodaga shinjinai",
            Triple("そーだが", "soodaga", "そう"), Triple("信じない", "shinjinai", "信じる")
        )
        assertDrawnReading("そーす", "soosu", Triple("そーす", "soosu", "ソース"))
        assertDrawnReading("ソーダ", "sooda", Triple("ソーダ", "sooda", "ソーダ"))
    }

    @Test
    fun repeatedLongMarksKeepTheAdverbWithItsAuxiliary() = assertDrawnReading(
        "そーーだと思う", "soooda to omou",
        Triple("そーーだ", "soooda", "そう"), Triple("と", "to", "と"), Triple("思う", "omou", "思う")
    )

    @Test
    fun theDictionaryRecognizesDrawnOutAdverbsAndInterjectionsBeforeAnAuxiliary() {
        assertDrawnReading("こーだ", "kooda", Triple("こーだ", "kooda", "こう"))
        assertDrawnReading("どーだ", "dooda", Triple("どーだ", "dooda", "どう"))
        assertDrawnReading("あーだった", "aadatta", Triple("あーだった", "aadatta", "ああ"))
        assertDrawnReading("じーだ", "jii da", Triple("じー", "jii", "じ"), Triple("だ", "da", "だ"))
    }

    @Test
    fun aCaseParticleKeepsTheLongMarkedLoanwordAsANoun() {
        assertDrawnReading(
            "そーだを飲む", "sooda o nomu",
            Triple("そーだ", "sooda", "ソーダ"), Triple("を", "o", "を"), Triple("飲む", "nomu", "飲む"),
            typed = "sooda wo nomu"
        )
        assertDrawnReading(
            "そーーだを飲む", "soooda o nomu",
            Triple("そーーだ", "soooda", "ソーダ"), Triple("を", "o", "を"), Triple("飲む", "nomu", "飲む"),
            typed = "soooda wo nomu"
        )
        assertDrawnReading(
            "そーだより紅茶", "sooda yori koocha",
            Triple("そーだ", "sooda", "ソーダ"), Triple("より", "yori", "より"), Triple("紅茶", "koocha", "紅茶"),
            typed = "sooda yori koucha"
        )
        assertDrawnReading(
            "そーだにする", "sooda ni suru",
            Triple("そーだ", "sooda", "ソーダ"), Triple("に", "ni", "に"), Triple("する", "suru", "する")
        )
    }

    @Test
    fun repeatedLongMarksKeepTheLoanwordsDictionaryForm() {
        assertDrawnReading("ぱーーてぃーー", "paaatiii", Triple("ぱーーてぃーー", "paaatiii", "パーティー"))
        val line = "ぱ${"ー".repeat(13)}てぃー"
        val romaji = "pa${"a".repeat(13)}tii"
        assertDrawnReading(line, romaji, Triple(line, romaji, "パーティー"))
    }

    @Test
    fun aRepeatedOkinawanGreetingKeepsEveryWord() {
        // The refrain of 金城賢's めんそーれ沖縄.
        val word = Triple("めんそーれ", "mensoore", "めんそーれ")
        assertDrawnReading("めんそーれめんそーれめんそーれ", "mensoore mensoore mensoore", word, word, word)
    }

    @Test
    fun aSmallHiraganaVowelCanBelongToALoanword() = assertDrawnReading(
        // The call ending in こんがらがっちゅれーしょん！ by ふぁんたす！.
        "ふぁんたじーさぁ", "fantajii saa",
        Triple("ふぁんたじー", "fantajii", "ファンタジー"), Triple("さぁ", "saa", "さあ")
    )

    @Test
    fun aCryAtTheEndOfTheLineKeepsItsOwnLookup() = assertDrawnReading(
        // The shout in Chevon's ダンス・デカダンス.
        "あーーーー", "aaaaa", Triple("あーーーー", "aaaaa", "あ")
    )

    @Test
    fun aDrawnOutAdverbAfterAnotherWordKeepsItsLookup() = assertDrawnReading(
        // From BRADIO's Feel all right, with どう drawn out as どー.
        "調子はどーだ", "chooshi wa dooda",
        Triple("調子", "chooshi", "調子"), Triple("は", "wa", "は"), Triple("どーだ", "dooda", "どう"),
        typed = "choushi wa dooda"
    )

    @Test
    fun theLastNounAndParticleStaySeparateInBothDictionaries() = assertDrawnReading(
        // From 井上陽水's Mellow touch, with ソーダ in hiragana.
        "ゆれるクリームそーだに", "yureru kuriimu sooda ni",
        Triple("ゆれる", "yureru", "ゆれる"), Triple("クリーム", "kuriimu", "クリーム"),
        Triple("そーだ", "sooda", "ソーダ"), Triple("に", "ni", "に")
    )

    @Test
    fun anEnumeratingParticleStaysWholeWhenALoanwordWouldChangeIt() = assertDrawnReading(
        // From suzunoki's あーだのこーだの.
        "こーだの言ったって", "koo dano ittatte",
        Triple("こー", "koo", "こ"), Triple("だの", "dano", "だの"), Triple("言ったって", "ittatte", "言う")
    )

    @Test
    fun aThreeKanaCallStaysOutsideTheShortAdverbRule() = assertDrawnReading(
        // Put an auxiliary after the drawn-out call in KANA-BOON's シルエット to check the length limit.
        "いっせーだ", "issee da", Triple("いっせー", "issee", "いっす"), Triple("だ", "da", "だ")
    )

    @Test
    fun drawnWordSearchKeepsItsTwelveKanaLimit() {
        // The refrain of Gacharic Spin's ジェネレーションギャップ, in hiragana.
        assertDrawnReading(
            "じぇねれーしょんぎゃっぷ", "jenereeshongyappu",
            Triple("じぇねれーしょんぎゃっぷ", "jenereeshongyappu", "ジェネレーションギャップ")
        )
        // The サーティワンアイスクリーム jingle, in hiragana.
        assertDrawnReading(
            "さーてぃわんあいすくりーむ", "saa ti wan aisukuriimu",
            Triple("さー", "saa", "さ"), Triple("てぃ", "ti", "てぃ"), Triple("わん", "wan", "わん"),
            Triple("あいすくりーむ", "aisukuriimu", "アイスクリーム")
        )
    }

    @Test
    fun drawnWordSearchStopsAtAKatakanaWord() = assertDrawnReading(
        // The start of the refrain of 二人だけのセレモニー, with パ written as ぱ.
        "ぱーティーは", "paa tii wa",
        Triple("ぱー", "paa", "ぱ"), Triple("ティー", "tii", "ティー"), Triple("は", "wa", "は")
    )

    private fun assertDrawnReading(
        line: String,
        romaji: String,
        vararg words: Triple<String, String, String>,
        typed: String = romaji
    ) {
        fun reading() = Readings.of(listOf(line), Script.JAPANESE).single().let { it.romanized to it.typed }
        fun groups() = Readings.words(line, Script.JAPANESE).map { Triple(it.text, it.romanized, it.lookup) }
        val expected = (romaji to typed) to words.toList()
        assertEquals(listOf(expected, expected), listOf(reading() to groups(), TestUniDic.reading { reading() to groups() }))
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
    @Config(sdk = [29])
    fun chineseReadOtherwiseIsWrittenOutFromAndroid10On() {
        val parts = listOf(Ruby("我", "wǒ"), Ruby("爱", "āi", Reader.USER))
        assertEquals("wǒ āi", Readings.readAs(parts, Script.CHINESE).romanized)
    }

    @Test
    fun chineseReadOtherwiseInAPlaceIsWrittenOutAsALineIs() {
        val parts = listOf(Ruby("a"), Ruby("我", "wǒ"), Ruby("爱", "āi", Reader.USER), Ruby("你", "nǐ"), Ruby("baby。"))
        val line = Readings.readAs(parts, Script.CHINESE)
        assertEquals(parts, line.parts)
        // A space between syllables and next to letters, none before the stop, written as Android writes it.
        assertEquals("a wǒ āi nǐ baby.", line.romanized)
        assertEquals(line.romanized, line.reading)
        // As the dictionary reads it, it's the dictionary's line.
        val (read) = Readings.of(listOf("我爱你!"), Script.CHINESE)
        assertEquals(read, Readings.readAs(read.parts, Script.CHINESE))
        assertEquals("", Readings.readAs(emptyList(), Script.CHINESE).romanized)
        assertEquals(Readings.of(listOf("한"), Script.KOREAN).single(), Readings.readAs(listOf(Ruby("한")), Script.KOREAN))
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
        // How IPADIC says it, with ー for an old kana said long: きのふ is said きのー.
        assertEquals("きのう", modernKana("きのふ", Furigana.saidAsSpelled("きのふ", "キノー")))
        assertEquals("いい", modernKana("いひ", Furigana.saidAsSpelled("いひ", "イー")))
        assertEquals("とおい", modernKana("とほい", Furigana.saidAsSpelled("とほい", "トーイ")))
        assertEquals("キノ", Furigana.saidAsSpelled("きのふ", "キノ"))
        assertEquals("アーフ", Furigana.saidAsSpelled("ああふ", "アーフ"))
        // Hiragana is ぁ to ゖ, both ends in.
        assertEquals(true, Furigana.inOldKana("ぁひゖ"))
        assertEquals(false, Furigana.inOldKana("ひ\u3097"))
        assertEquals(false, Furigana.inOldKana("\u3040ひ"))
        assertEquals(false, Furigana.inOldKana("あい"))
    }

    @Test
    fun aSmallKanaStaysInTheWordThatSaysIt() {
        val text = "かいしゃのけいひ"
        fun words() = Readings.words(text, Script.JAPANESE).map { it.text to it.lookup }
        val expected = listOf("かいしゃ" to "会社", "の" to "の", "けいひ" to "経費")
        assertEquals(listOf(expected, expected), listOf(words(), TestUniDic.reading { words() }))
        fun line() = Readings.of(listOf(text), Script.JAPANESE).single().typed
        assertEquals("kaisha no keihi", line())
        assertEquals("kaisha no keihi", TestUniDic.reading { line() })
    }

    @Test
    fun kanaWordsMissingFromOldKanaKeepTheirDictionaryLookups() {
        val cases = mapOf(
            "でんしゃでまち" to listOf("でんしゃ" to "電車", "で" to "で", "まち" to "まち"),
            "きょねんのゆめ" to listOf("きょねん" to "去年", "の" to "の", "ゆめ" to "ゆめ"),
            "しゅみはうた" to listOf("しゅみ" to "趣味", "は" to "は", "うた" to "うた"),
            "にっきをかく" to listOf("にっき" to "日記", "を" to "を", "かく" to "かく")
        )
        fun words() = cases.keys.map { line -> Readings.words(line, Script.JAPANESE).map { it.text to it.lookup } }
        val expected = cases.values.toList()
        assertEquals(listOf(expected, expected), listOf(words(), TestUniDic.reading { words() }))
    }

    @Test
    fun repeatedKanaWordsKeepTheirLookupsAcrossSearchWindows() {
        fun check() {
            val words = Readings.words("でんしゃででんしゃ", Script.JAPANESE)
            assertEquals(listOf("でんしゃ" to "電車", "で" to "で", "でんしゃ" to "電車"), words.map { it.text to it.lookup })
            val bridged = Readings.words("りゅうっっさくら りゅうっっさくら", Script.JAPANESE)
            assertEquals(listOf("りゅうっっ", "さくら", "りゅうっっ", "さくら"), bridged.map { it.text })
            assertEquals(listOf("りゅう", "さくら", "りゅう", "さくら"), bridged.map { it.lookup })
        }
        check()
        TestUniDic.reading { check() }
    }

    @Test
    fun searchVocabularyFindsTheLongestWordAndKeepsOldKanaPrecedence() {
        val empty = Readings.OldKana(emptyMap(), setOf("あ"))
        assertEquals(0, empty.longestSearch)
        val single = Readings.OldKana(mapOf("あ" to "亜"), setOf("あ"))
        assertEquals(1, single.longestSearch)
        val words = Readings.OldKana(mapOf("あい" to "愛"), setOf("あ"), mapOf("あいうえ" to "上", "あい" to "藍"))
        assertEquals(4, words.longestSearch)
        assertEquals("愛", words.search["あい"])
    }

    @Test
    fun adjectiveCandidatesMustCrossTheTokenBoundary() {
        val vocabulary = Readings.OldKana(
            emptyMap(), setOf("あ"), mapOf("あいうえ" to "亜"),
            mapOf("あほ" to listOf("阿呆"), "あり" to listOf("有り"))
        )
        val tokenizer = SmallKanaTokenizer { vocabulary }
        val plain = Tokenizer.Builder().build()
        assertEquals(0..1, tokenizer.crossingFor("あほな", plain.tokenize("あほな"), 1))
        assertNull(tokenizer.crossingFor("ありな", plain.tokenize("ありな"), 1))
    }

    @Test
    fun aKanaWordMayStartAtTheBeginningOfAnInflectedDictionaryToken() {
        fun words() = Readings.words("そのきっさき", Script.JAPANESE).map { it.text }
        assertEquals(listOf("その", "きっさき"), words())
        assertEquals(listOf("その", "きっさき"), TestUniDic.reading { words() })
    }

    @Test
    fun aSingleKnownKanjiWordKeepsItsDictionaryReading() {
        val previous = Readings.listed
        try {
            Readings.listed = mapOf("東京" to "みやこ")
            assertEquals("とうきょう", Readings.of(listOf("東京"), Script.JAPANESE).single().reading)
            assertEquals("とうきょう", TestUniDic.reading { Readings.of(listOf("東京"), Script.JAPANESE).single().reading })
        } finally {
            Readings.listed = previous
        }
    }

    @Test
    fun aListedSuffixKeepsTheWordBeforeIt() {
        val previous = Readings.listed
        try {
            // A fake listing makes the two dictionary pieces 夢 and 的 one suffix.
            Readings.listed = mapOf("夢的" to "むてき")
            val words = Readings.words("人生夢的", Script.JAPANESE)
            assertEquals(listOf("人生夢的"), words.map { it.text })
            assertEquals(listOf(Reader.IPADIC, Reader.JMDICT), words.single().by)
        } finally {
            Readings.listed = previous
        }
    }

    @Test
    fun kanaWordsStayWholeBeforeIpadicsSmallTsuWords() {
        val cases = mapOf(
            "いえはいっしょ" to listOf("いえ", "は", "いっしょ"),
            "いえはずっと" to listOf("いえ", "は", "ずっと"),
            "きみってだれ" to listOf("きみ", "って", "だれ")
        )
        fun words() = cases.keys.map { line -> Readings.words(line, Script.JAPANESE).map { it.text } }
        val expected = cases.values.toList()
        assertEquals(listOf(expected, expected), listOf(words(), TestUniDic.reading { words() }))
    }

    @Test
    fun aSmallTsuWordCannotCutACommonWordDuringSearch() {
        val tokenizer = SmallKanaTokenizer { Readings.oldKana }
        val split = tokenizer.searched("はずっと", 0)
        assertEquals(listOf("は", "ずっと"), split?.map { it.surface })
        val particle = tokenizer.searched("きみってだれ", 0)
        assertEquals(listOf("きみ", "って", "だれ"), particle?.map { it.surface })
    }

    @Test
    fun knownSmallTsuParticlesStayWholeWhenAListedWordCrossesThem() {
        val vocabulary = Readings.OldKana(emptyMap(), setOf("あ"), mapOf("あ" to "亜", "れって" to "列島", "ずっ" to "ずっ", "ずっと" to "ずっと"))
        val tokenizer = SmallKanaTokenizer { vocabulary }
        val plain = com.atilika.kuromoji.ipadic.Tokenizer.Builder().build()
        val particle = "それってこと"
        val particleTokens = plain.tokenize(particle)
        assertEquals(listOf("それ", "って", "こと"), particleTokens.map { it.surface })
        assertNull(tokenizer.crossingFor(particle, particleTokens, 1))
        val word = "いえはずっと"
        val wordTokens = plain.tokenize(word)
        assertEquals(3..5, tokenizer.crossingFor(word, wordTokens, 2))
    }

    @Test
    fun crossingWordsReachTheStartAndEndOfTheirKanaRun() {
        val vocabulary = Readings.OldKana(emptyMap(), setOf("あ"), mapOf("いゃ" to "いゃ", "あいっえお" to "あいっえお"))
        val tokenizer = SmallKanaTokenizer { vocabulary }
        val plain = com.atilika.kuromoji.ipadic.Tokenizer.Builder().build()
        fun crossing(text: String, at: Int): IntRange? {
            val tokens = plain.tokenize(text)
            val index = tokens.indexOfFirst { it.position == at }
            assertTrue("$text has no token at $at", index >= 0)
            return tokenizer.crossingFor(text, tokens, index)
        }
        assertEquals(0..1, crossing("いゃ", 1))
        assertEquals(0..4, crossing("あいっえお", 1))
        assertEquals(0..4, crossing("あいっえお", 4))
        assertEquals(1..5, crossing("漢あいっえお", 2))
        assertNull(crossing("あいっえあか", 3))
    }

    @Test
    fun searchWindowCoversExactlyTheTokensACrossingWordNeeds() {
        val text = "いえはずっと"
        val tokens = com.atilika.kuromoji.ipadic.Tokenizer.Builder().build().tokenize(text)
        assertEquals(listOf("いえ", "はず", "っと"), tokens.map { it.surface })
        val tokenizer = SmallKanaTokenizer { Readings.oldKana }
        // A crossing ending in the next token needs it, but one ending at the boundary does not.
        assertEquals(0 to 2, tokenizer.searchWindow(text, tokens, 1, 0, 1..4))
        assertEquals(0 to 1, tokenizer.searchWindow(text, tokens, 1, 0, 1..3))
        // A word beginning inside the preceding token needs it; one beginning at its boundary does not.
        assertEquals(0 to 2, tokenizer.searchWindow(text, tokens, 2, 0, 0..4))
        assertEquals(1 to 2, tokenizer.searchWindow(text, tokens, 2, 0, 2..4))
        assertEquals(1 to 2, tokenizer.searchWindow(text, tokens, 2, 0, null))
    }

    @Test
    fun consecutiveKanaSearchWindowsKeepTheirText() {
        val line = "もっともっともっと"
        fun check() = assertEquals(List(3) { "もっと" to "もっと" }, Readings.words(line, Script.JAPANESE).map { it.text to it.lookup })
        check()
        TestUniDic.reading { check() }
    }

    @Test
    fun retryDoesNotLeakBetweenSearchesOrAcrossOneLine() {
        val tokenizer = SmallKanaTokenizer { Readings.oldKana }
        fun signature(tokens: List<com.atilika.kuromoji.ipadic.Token>?) =
            tokens?.map { listOf(it.surface, it.reading, it.baseForm, it.position.toString(), it.allFeatures) }
        val first = "りゅうっっさくら"
        val second = "きゅうッりい"
        val retry = checkNotNull(tokenizer.searched(first, 0))
        assertEquals(listOf("りゅうっっ", "さくら"), retry.map { it.surface })
        assertEquals(listOf(0, 5), retry.map { it.position })
        assertEquals(signature(SmallKanaTokenizer { Readings.oldKana }.searched(second, 0)), signature(tokenizer.searched(second, 0)))
        for (text in listOf(first, second, "にっき", "あ りょこう", "あ りゅうっっさくら")) {
            val atZero = checkNotNull(tokenizer.searched(text, 0))
            val atEleven = checkNotNull(tokenizer.searched(text, 11))
            assertEquals(text, atZero.map { it.position + 11 }, atEleven.map { it.position })
            assertEquals(text, atZero.map { it.surface to it.allFeatures }, atEleven.map { it.surface to it.allFeatures })
        }
        val offered = checkNotNull(tokenizer.searched("あ りょこう", 0)).filterIsInstance<SmallKanaTokenizer.JmdictToken>().single()
        assertEquals(2, offered.position)
        assertEquals(Tokenizer().tokenize("旅行").single().allFeatures, offered.allFeatures)
        assertTrue(checkNotNull(tokenizer.searched("あ りゅうっっさくら", 0)).any { it is SmallKanaTokenizer.BridgedToken && it.position > 0 })
        val line = "$first $second"
        assertEquals(signature(SmallKanaTokenizer { Readings.oldKana }.tokenize(line)), signature(tokenizer.tokenize(line)))
        fun checkWords() = Readings.words(line, Script.JAPANESE).map { it.text to it.lookup }
        val expected = listOf("りゅうっっ" to "りゅう", "さくら" to "さくら", "きゅうッり" to "きゅう", "い" to "い")
        assertEquals(expected, checkWords())
        assertEquals(expected, TestUniDic.reading { checkWords() })
    }

    @Test
    fun anUnreachableSearchKeepsTheOriginalTokens() {
        val tokenizer = SmallKanaTokenizer { Readings.oldKana }
        val line = "あぁゃ"
        assertNull(tokenizer.searched(line, 0))
        fun signature(tokens: List<com.atilika.kuromoji.ipadic.Token>) = tokens.map { Triple(it.surface, it.reading, it.position) }
        val plain = com.atilika.kuromoji.ipadic.Tokenizer.Builder().build().tokenize(line)
        assertEquals(signature(plain), signature(tokenizer.tokenize(line)))
        fun checkReading() {
            val reading = Readings.of(listOf(line), Script.JAPANESE).single()
            assertEquals("あぁゃ", reading.reading)
            assertEquals("a aya", reading.romanized)
        }
        checkReading()
        TestUniDic.reading { checkReading() }
    }

    @Test
    fun aKanaCandidateWithTheWrongWrittenReadingIsNotOffered() {
        val vocabulary = Readings.OldKana(emptyMap(), setOf("あ"), mapOf("りょこう" to "東京", "にっき" to "東京"))
        val tokenizer = SmallKanaTokenizer { vocabulary }
        for (text in listOf("りょこう", "にっき", "あ りょこう", "あ にっき")) {
            val actual = checkNotNull(tokenizer.searched(text, 0))
            assertTrue(text, actual.none { it is SmallKanaTokenizer.JmdictToken })
        }
    }

    @Test
    fun aRetrySelectsTheDictionaryPathFromTheStartOfTheLine() {
        val tokens = checkNotNull(SmallKanaTokenizer { Readings.oldKana }.searched("きょうっっさくら", 0))
        assertEquals(listOf("きょうっっ", "さくら"), tokens.map { it.surface })
        assertEquals(listOf(0, 5), tokens.map { it.position })
        assertEquals("副詞可能", tokens.first().partOfSpeechLevel2)
    }

    @Test
    fun aCommonKanaWordDoesNotSplitAnInflectedVerb() {
        fun check() = assertEquals("shimatchaeba", Readings.of(listOf("しまっちゃえば"), Script.JAPANESE).single().romanized)
        check()
        TestUniDic.reading { check() }
    }

    @Test
    fun dictionaryWordsKeepTheirReadingsAlone() {
        val cases = mapOf(
            "薄化粧" to "うすげしょう", "丸印" to "まるじるし", "一口" to "ひとくち", "丸印の" to "まるじるしの"
        )
        fun readings() = Readings.of(cases.keys.toList(), Script.JAPANESE).map { it.reading }
        val expected = cases.values.toList()
        assertEquals(listOf(expected, expected), listOf(readings(), TestUniDic.reading { readings() }))
    }

    @Test
    fun ambiguousDictionaryWordsStillReadWrongInTheAuditedLyricLines() {
        fun check(hitokuchi: String) {
            assertEquals("ひのまるしるしのにりんしゃころがし", Readings.of(listOf("日の丸印の二輪車転がし"), Script.JAPANESE).single().reading)
            assertEquals(hitokuchi, Readings.words("一口だけで fall in love", Script.JAPANESE).first().reading)
        }
        check("いっくち")
        TestUniDic.reading { check("ひとくち") }
    }

    @Test
    fun thinMakeupKeepsItsReadingInAShortSentence() {
        fun check() = assertEquals("うすげしょうをして", Readings.of(listOf("薄化粧をして"), Script.JAPANESE).single().reading)
        check()
        TestUniDic.reading { check() }
    }

    @Test
    fun unreadSingleKanjiVerbsUseTheirUnambiguousDictionaryReading() {
        val cases = mapOf("奔る" to "はしる", "溢るる" to "あふるる")
        fun readings() = Readings.of(cases.keys.toList(), Script.JAPANESE).map { it.reading }
        val expected = cases.values.toList()
        assertEquals(listOf(expected, expected), listOf(readings(), TestUniDic.reading { readings() }))
        fun lookups() = cases.keys.map { line -> Readings.words(line, Script.JAPANESE).map { it.lookup } }
        val expectedLookups = cases.keys.map { listOf(it) }
        assertEquals(listOf(expectedLookups, expectedLookups), listOf(lookups(), TestUniDic.reading { lookups() }))
        assertEquals(listOf(Reader.JMDICT), Readings.words("奔る", Script.JAPANESE).single().by)
        fun afterNoun() = Readings.words("奴隷奔る", Script.JAPANESE).map { it.text to it.lookup }
        val separate = listOf("奴隷" to "奴隷", "奔る" to "奔る")
        assertEquals(separate, afterNoun())
        assertEquals(separate, TestUniDic.reading { afterNoun() })
        val inContext = mapOf("奔る奴隷" to "はしるどれい", "溢るる涙" to "あふるるなみだ")
        fun contextualReadings() = Readings.of(inContext.keys.toList(), Script.JAPANESE).map { it.reading }
        val contextualExpected = inContext.values.toList()
        assertEquals(listOf(contextualExpected, contextualExpected), listOf(contextualReadings(), TestUniDic.reading { contextualReadings() }))
    }

    @Test
    fun aKnownSingleKanjiVerbKeepsThePhoneDictionaryReading() {
        val previous = Readings.listed
        try {
            Readings.listed = mapOf("走る" to "にげる")
            assertEquals("はしる", Readings.of(listOf("走る"), Script.JAPANESE).single().reading)
            assertEquals("はしる", TestUniDic.reading { Readings.of(listOf("走る"), Script.JAPANESE).single().reading })
        } finally {
            Readings.listed = previous
        }
    }

    @Test
    fun anUnreadRuFormDoesNotJoinAFollowingVerb() {
        fun reading() = Readings.of(listOf("溺れ死にそう"), Script.JAPANESE).single().reading
        assertEquals("おぼれしにそう", reading())
        assertEquals("おぼれしにそう", TestUniDic.reading { reading() })
    }

    @Test
    fun noWordOfALineInKanaStartsOnASmallKana() {
        // IPADIC alone splits each of these before a small kana: じ and ゅぎょう, し and ゅくだい, に and っ and き.
        val lines = mapOf(
            "あのじゅぎょう" to listOf("あの" to "あの", "じゅぎょう" to "授業"),
            "しゅくだいをともだち" to listOf("しゅくだい" to "宿題", "を" to "を", "ともだち" to "ともだち"),
            "きょうしつのせんせい" to listOf("きょうしつ" to "教室", "の" to "の", "せんせい" to "せんせい"),
            "いしゃのけいひ" to listOf("いしゃ" to "いしゃ", "の" to "の", "けいひ" to "経費"),
            "にっきをかく" to listOf("にっき" to "日記", "を" to "を", "かく" to "かく"),
        )
        for ((line, expected) in lines) assertEquals(line, expected, Readings.words(line, Script.JAPANESE).map { it.text to it.lookup })
        // A word JMdict has in kanji is read so, as IPADIC reads the kanji.
        assertEquals(listOf(Reader.JMDICT), Readings.words("あのじゅぎょう", Script.JAPANESE).last().by)
        for (line in lines.keys + listOf("これじゃひとりぼっち", "うっせぇうっせぇうっせぇわ", "クソだりぃな", "きょねんのゆめ", "まばたきみたいないっしゅん")) {
            for (words in listOf(Readings.words(line, Script.JAPANESE), TestUniDic.reading { Readings.words(line, Script.JAPANESE) })) {
                assertTrue("$line: $words", words.none { it.text.first() in "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ" })
            }
        }
    }

    @Test
    fun aSmallVowelDrawsOutTheWordBeforeIt() {
        // Each word as IPADIC splits it, with the small vowels after it that draw it out: looked up as it is.
        val lines = mapOf(
            "ありがとぉ" to listOf("ありがとぉ" to "ありがと"),
            "おねがいぃ" to listOf("おねがいぃ" to "おねがい"),
            "だめぇ" to listOf("だめぇ" to "だめ"),
            "ほんとぉ" to listOf("ほんとぉ" to "ほんと"),
            "しらないぃ" to listOf("しらないぃ" to "しる"),
            "きみがすきぃ" to listOf("きみ" to "きみ", "が" to "が", "すきぃ" to "すく"),
            "まってよぉぉ" to listOf("まって" to "まつ", "よぉぉ" to "よ"),
            "ズットォ" to listOf("ズットォ" to "ズットォ"),
            // A small vowel that spells a long vowel draws it out too: ありがとぅ is ありがとう, not ありが and とぅ.
            "ありがとぅ" to listOf("ありがとぅ" to "ありがとう"),
        )
        for ((line, expected) in lines) assertEquals(line, expected, Readings.words(line, Script.JAPANESE).map { it.text to it.lookup })
        for (line in lines.keys + listOf("やだぁ", "すきすきすきぃ", "あいたいよぉ")) {
            for (words in listOf(Readings.words(line, Script.JAPANESE), TestUniDic.reading { Readings.words(line, Script.JAPANESE) })) {
                assertTrue("$line: $words", words.none { it.text.first() in "ぁぃぅぇぉァィゥェォ" })
                assertTrue("$line: $words", words.none { it.by == listOf(Reader.JMDICT) })
            }
        }
        assertEquals("arigatoo", Readings.of(listOf("ありがとぉ"), Script.JAPANESE).single().typed)
        assertEquals("arigatou", Readings.of(listOf("ありがとぅ"), Script.JAPANESE).single().typed)
        // No word JMdict has in kanji is offered before a small vowel it may go on in: ちょうだぃ is no 長打.
        assertTrue(Readings.words("ちょうだぃ", Script.JAPANESE).none { it.lookup == "長打" })
        // おう and えい spelled small, and a vowel after ー, which draws out the kana before it too, stay with their word.
        val longVowels = mapOf("せんせぃ" to listOf("せんせぃ"), "くださぃ" to listOf("くださぃ"), "ねーぇ" to listOf("ねーぇ"), "すごーぃ" to listOf("すごーぃ"))
        for ((line, expected) in longVowels) {
            assertEquals(line, expected, Readings.words(line, Script.JAPANESE).map { it.text })
            assertEquals(line, expected, TestUniDic.reading { Readings.words(line, Script.JAPANESE).map { it.text } })
        }
        // A small vowel that makes a sound with the kana before, ぃ in でぃ and ぇ in ふぇ, is said with it, not drawn out.
        for (line in listOf("めでぃあ", "かふぇてらす")) {
            val words = Readings.words(line, Script.JAPANESE).map { it.text }
            assertTrue("$line: $words", words.any { "でぃ" in it || "ふぇ" in it })
        }
        assertEquals("（risei adiosu）", Readings.of(listOf("（理性アディオス）"), Script.JAPANESE).single().typed)
    }

    @Test
    fun aWordThatEndsOnASmallTsuRunsIntoTheNextInRomaji() {
        fun typed(line: String) = Readings.of(listOf(line), Script.JAPANESE).single().typed
        assertEquals("isshun no yume", typed("いっしゅんのゆめ"))
        assertEquals("zukkyun", typed("ずっきゅん"))
    }

    @Test
    fun aSmallTsuDoublesOnlyAConsonantRightAfterIt() {
        // Not across the line's own space, nor before a vowel: there it only cuts the word short. ッ as っ.
        val lines = mapOf(
            "あっ あっ" to "a a", "あっあっ" to "a a", "きゃっ きゃっ" to "kya kya", "きゃっきゃっ" to "kyakkya",
            "がっ ちゃ" to "ga cha", "ヤッ た" to "ya ta", "ヤッた" to "yatta",
        )
        for ((line, typed) in lines) {
            assertEquals(line, typed, Readings.of(listOf(line), Script.JAPANESE).single().typed)
            assertEquals(line, typed, TestUniDic.reading { Readings.of(listOf(line), Script.JAPANESE).single().typed })
        }
    }

    @Test
    fun hiraganaToAndDoWithASmallUAreASoundBeforeMoreKanaAndDrawOutAtTheEnd() {
        // (said, typed): とぅ is tu before more kana, as トゥ is, and draws と out at the end of a word, ありがとう.
        val lines = mapOf(
            "とぅないと" to ("tu naito" to "tu naito"),
            "どぅびどぅば" to ("du biduba" to "du biduba"),
            "うとぅ" to ("utoo" to "utou"),
            "ありがとぅ" to ("arigatoo" to "arigatou"),
            "ありがとぅ！" to ("arigatoo！" to "arigatou！"),
        )
        fun read(line: String) = Readings.of(listOf(line), Script.JAPANESE).single().let { it.romanized to it.typed }
        for ((line, romaji) in lines) {
            assertEquals(line, romaji, read(line))
            assertEquals(line, romaji, TestUniDic.reading { read(line) })
        }
        val sound = "とぅるるる"
        assertEquals(sound, "tu", read(sound).second.take(2))
        assertEquals(sound, "tu", TestUniDic.reading { read(sound).second.take(2) })
    }

    @Test
    fun katakanaToAndDoWithASmallUAreSoundsWhereverTheyAre() {
        for (words in listOf(Readings.words("アリガトゥ", Script.JAPANESE), TestUniDic.reading { Readings.words("アリガトゥ", Script.JAPANESE) })) {
            assertEquals(listOf("アリガトゥ" to "アリガトゥ"), words.map { it.text to it.lookup })
        }
        assertEquals("arigatu", Readings.of(listOf("アリガトゥ"), Script.JAPANESE).single().typed)
        assertEquals("ari ga tu", Readings.of(listOf("ありがトゥ"), Script.JAPANESE).single().typed)
    }

    @Test
    fun aWordSmallVowelsDrawOutIsTheWordTheySpellFullSize() {
        val lines = mapOf(
            "せんせぃ" to listOf("せんせぃ" to "せんせい"),
            "くださぃ" to listOf("くださぃ" to "くださる"),
            "おはよぅ" to listOf("おはよぅ" to "おはよう"),
            "だよぅ" to listOf("だ" to "だ", "よぅ" to "よう"),
            "かわぃぃ" to listOf("かわぃぃ" to "かわいい"),
            // Not a word read right before, ない, split for another: しらな and いい.
            "しらないぃ" to listOf("しらないぃ" to "しる"),
            // A small ぇ after the い row is a sound: ひぇー is a cry, not ひる.
            "ひぇー" to listOf("ひぇー" to "ひぇ"),
            "きぇー" to listOf("きぇー" to "きぇ"),
        )
        fun words(line: String) = Readings.words(line, Script.JAPANESE).map { it.text to it.lookup }
        for ((line, expected) in lines) {
            assertEquals(line, expected, words(line))
            assertEquals(line, expected, TestUniDic.reading { words(line) })
        }
        assertEquals("pyen", Readings.of(listOf("ぴぇん"), Script.JAPANESE).single().typed)
        // ずっとう is no one word, so ずっと keeps its reading, drawn out, with the ぅ that would make とぅ a sound full size.
        assertEquals(listOf("ずっとぅ" to "ずっと"), words("ずっとぅ"))
        assertEquals("zuttou", Readings.of(listOf("ずっとぅ"), Script.JAPANESE).single().typed)
    }

    @Test
    fun aWordDrawnOutStaysOneBeforeMoreKanaAndAParticleDrawnOutIsNoCall() {
        val lines = mapOf(
            // A word the dictionary has ends on the と, so the ぅ draws it out, more kana after it or not.
            "ありがとぅね" to listOf("ありがとぅ" to "ありがとう", "ね" to "ね"),
            "ありがとぅございます" to listOf("ありがとぅ" to "ありがとう", "ございます" to "ござる"),
            "ずっとぅいっしょ" to listOf("ずっとぅ" to "ずっと", "いっしょ" to "いっしょ"),
            // A particle drawn out is still one, not よお the call; ねえ is a particle too.
            "そうだよぉ" to listOf("そうだ" to "そう", "よぉ" to "よ"),
            "そうだねぇ" to listOf("そうだ" to "そう", "ねぇ" to "ねえ"),
            "かわぃぃよぉ" to listOf("かわぃぃ" to "かわいい", "よぉ" to "よ"),
            // No particle starts a line: there it's the call.
            "よぉ、元気？" to listOf("よぉ" to "よお", "元気" to "元気"),
            // IPADIC takes the も for a particle, but drawn out it's もう.
            "もぅいいよ" to listOf("もぅ" to "もう", "いい" to "いい", "よ" to "よ"),
        )
        fun words(line: String) = Readings.words(line, Script.JAPANESE).map { it.text to it.lookup }
        for ((line, expected) in lines) {
            assertEquals(line, expected, words(line))
            assertEquals(line, expected, TestUniDic.reading { words(line) })
        }
        fun typed(line: String) = Readings.of(listOf(line), Script.JAPANESE).single().typed
        assertEquals("arigatou ne", typed("ありがとぅね"))
        assertEquals("zuttou issho", typed("ずっとぅいっしょ"))
        // No word ends on a と that starts the line, so there it's a sound.
        assertEquals("tu naito", typed("とぅないと"))
        // Ending the line, no kana is said after it: drawn out, a word the dictionary has before it or not.
        assertEquals("minna tou", typed("みんなとぅ"))
        // A word of two kana ends on the と too.
        assertEquals("hitou ga", typed("ひとぅが"))
        // But not the start of one alone: そと starts words the dictionary has, and isn't one of them, so とぅ is tu.
        assertEquals("so tu nai", typed("そとぅない"))
    }

    @Test
    fun aDrawnOutYoAfterATeFormIsStillTheEndingParticle() {
        for ((line, verb) in mapOf("まって" to "まつ", "待って" to "待つ", "あそんで" to "あそぶ", "見て" to "見る")) {
            for (vowels in listOf("ぉ", "ぉぉ")) {
                val text = line + "よ" + vowels
                fun words() = Readings.words(text, Script.JAPANESE).map { it.text to it.lookup }
                val expected = listOf(line to verb, "よ$vowels" to "よ")
                assertEquals(text, expected, words())
                assertEquals(text, expected, TestUniDic.reading { words() })
                for (word in listOf(Readings.words(text, Script.JAPANESE).last(), TestUniDic.reading { Readings.words(text, Script.JAPANESE).last() })) {
                    assertNull(text, word.reading)
                    assertEquals(text, "yo" + "o".repeat(vowels.length), word.romanized)
                    assertEquals(text, word.romanized, word.typed)
                    assertEquals(text, listOf(Reader.RULES), word.by)
                }
            }
        }
        // The call alone and a different ending after the connective keep their own dictionary forms.
        for ((text, lookup) in mapOf("よぉ" to "よお", "元気？よぉ" to "よお", "まってねぇ" to "ねぇ", "まっておねがいぃ" to "おねがい")) {
            assertEquals(text, lookup, Readings.words(text, Script.JAPANESE).last().lookup)
            assertEquals(text, lookup, TestUniDic.reading { Readings.words(text, Script.JAPANESE).last().lookup })
        }
    }

    @Test
    fun aSmallKanaBeforeASmallVowelStillStartsNoWord() {
        val words = Readings.words("でしょぅ", Script.JAPANESE).map { it.text }
        assertTrue("$words", words.none { it.first() in "ぁぃぅぇぉっゃゅょゎ" })
    }

    @Test
    fun aStraySmallKanaGoesWithTheKanaBeforeIt() {
        fun words(line: String) = Readings.words(line, Script.JAPANESE).map { it.text }
        assertEquals(listOf("ちいさ", "なゃ"), words("ちいさなゃ"))
    }

    @Test
    fun aSmallTsuGoesWithTheKanaItDoublesAndTheSmallKanaSaidWithThat() {
        val lines = mapOf(
            "ちゅっちゅ" to "chutchu", "きゃっきゃっ" to "kyakkya", "しゃっしゃっ" to "shassha", "にっきにっき" to "nikki nikki",
            // With no kana after it to double, っ cuts the word before short, and goes with it.
            "きゃっ" to "kya", "かいしゃっ" to "kaisha", "ちょっ！" to "cho！", "すごーっ" to "sugoo",
        )
        for ((line, typed) in lines) {
            val words = Readings.words(line, Script.JAPANESE).map { it.text }
            assertTrue("$line: $words", words.none { it.first() in "ぁぃぅぇぉっゃゅょゎ" })
            assertEquals(line, typed, Readings.of(listOf(line), Script.JAPANESE).single().typed)
        }
        assertEquals(listOf("かいしゃっ"), Readings.words("かいしゃっ！", Script.JAPANESE).map { it.text })
        // Before a word that starts wrong, a word IPADIC starts on っ is searched again with the word before it, so the
        // search knows what kana that っ follows; but never with a word already searched again, にっき in にっきっしょゃ.
        for (line in listOf("まってっゃ", "きみってゃん", "にっきっしょゃ")) {
            val words = Readings.words(line, Script.JAPANESE).map { it.text }
            assertTrue("$line: $words", words.none { it.first() in "ぁぃぅぇぉっゃゅょゎ" })
        }
    }

    @Test
    fun aWordBeforeSmallTsuLeavesAWayThrough() = checkWordBeforeSmallTsu()

    @Test
    fun aWordBeforeSmallTsuLeavesAWayThroughWithUniDic() = TestUniDic.reading { checkWordBeforeSmallTsu() }

    @Test
    fun consecutiveSmallTsuAndKatakanaSmallTsuKeepTheirWords() = checkConsecutiveSmallTsu()

    @Test
    fun consecutiveSmallTsuAndKatakanaSmallTsuKeepTheirWordsWithUniDic() = TestUniDic.reading { checkConsecutiveSmallTsu() }

    @Test
    fun smallTsuBridgeAndLongVowelsReadTogether() {
        val line = "りゅうっっさくら ぱーてぃー"
        fun check() {
            val reading = Readings.of(listOf(line), Script.JAPANESE).single()
            assertEquals(line, "ryuussakura paatii", reading.romanized)
            assertEquals(line, "りゅうっっさくら ぱーてぃー", reading.reading)
            val bridges = SmallKanaTokenizer { Readings.oldKana }.tokenize(line).filterIsInstance<SmallKanaTokenizer.BridgedToken>()
            assertEquals(line, listOf("りゅうっっ"), bridges.map { it.surface })
        }
        check()
        TestUniDic.reading { check() }
    }

    private fun checkConsecutiveSmallTsu() {
        for (line in listOf("りゅうっっさくら", "りゅうっッさくら", "りゅうッっッさくら")) {
            assertEquals(line, "ryuussakura", Readings.of(listOf(line), Script.JAPANESE).single().romanized)
            assertEquals(line, listOf("りゅう", "さくら"), Readings.words(line, Script.JAPANESE).map { it.lookup })
        }
    }

    private fun assertSmallTsuReading(line: String, expected: String) {
        fun check() = assertEquals(line, expected, Readings.of(listOf(line), Script.JAPANESE).single().reading)
        check()
        TestUniDic.reading { check() }
    }

    private fun assertSmallTsuWords(line: String, expected: List<String>) {
        fun check() = assertEquals(line, expected, Readings.words(line, Script.JAPANESE).map { it.text })
        check()
        TestUniDic.reading { check() }
    }

    @Test fun smallTsuRunReading() = assertSmallTsuReading("りゅうっっさくら", "りゅうっっさくら")
    @Test fun smallTsuRunWords() = assertSmallTsuWords("りゅうっっさくら", listOf("りゅうっっ", "さくら"))
    @Test fun mixedSmallTsuRunReading() = assertSmallTsuReading("りゅうっッさくら", "りゅうっっさくら")
    @Test fun mixedSmallTsuRunWords() = assertSmallTsuWords("りゅうっッさくら", listOf("りゅうっッ", "さくら"))
    @Test fun smallTsuRunAtStartReading() = assertSmallTsuReading("っっさくら", "っっさくら")
    @Test fun smallTsuRunAtStartWords() = assertSmallTsuWords("っっさくら", listOf("っっさ", "くら"))
    @Test fun smallTsuRunAtEndReading() = assertSmallTsuReading("りゅうっっ", "りゅうっっ")
    @Test fun smallTsuRunAtEndWords() = assertSmallTsuWords("りゅうっっ", listOf("りゅうっっ"))
    @Test fun smallTsuRunBeforeKanjiReading() = assertSmallTsuReading("りゅうっっ桜", "りゅうっっさくら")
    @Test fun smallTsuRunBeforeKanjiWords() = assertSmallTsuWords("りゅうっっ桜", listOf("りゅうっっ", "桜"))

    @Test
    fun terminalSmallTsuKeepsTheKnownWord() = checkTerminalSmallTsu()

    @Test
    fun terminalSmallTsuKeepsTheKnownWordWithUniDic() = TestUniDic.reading { checkTerminalSmallTsu() }

    private fun checkTerminalSmallTsu() {
        for ((line, expected) in mapOf(
            "りゅうっっ" to listOf("りゅうっっ" to "りゅう"),
            "りゅうっっ桜" to listOf("りゅうっっ" to "りゅう", "桜" to "桜"),
            "りゅうっ桜" to listOf("りゅうっ" to "りゅう", "桜" to "桜"),
        )) {
            val words = Readings.words(line, Script.JAPANESE)
            assertEquals(line, expected, words.map { it.text to it.lookup })
            assertTrue(line, words.none { it.text.first() in "ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ" })
            assertEquals(line, line.replace("桜", "さくら"), Readings.of(listOf(line), Script.JAPANESE).single().reading)
        }
    }

    @Test
    fun smallTsuRunBeforePunctuationKeepsItsWord() {
        fun check() {
            val line = "りゅうっっ！"
            assertEquals(listOf("りゅうっっ" to "りゅう"), Readings.words(line, Script.JAPANESE).map { it.text to it.lookup })
            assertEquals(line, Readings.of(listOf(line), Script.JAPANESE).single().reading)
        }
        check()
        TestUniDic.reading { check() }
    }

    @Test
    fun smallTsuRunBeforeSpaceKeepsItsWord() {
        fun check() {
            val line = "りゅうっっ 桜"
            assertEquals(listOf("りゅうっっ" to "りゅう", "桜" to "桜"), Readings.words(line, Script.JAPANESE).map { it.text to it.lookup })
            assertEquals("りゅうっっ さくら", Readings.of(listOf(line), Script.JAPANESE).single().reading)
        }
        check()
        TestUniDic.reading { check() }
    }

    @Test
    fun anIpadicBridgeKeepsItsModernLookup() {
        fun check() {
            val tokenizer = SmallKanaTokenizer { Readings.oldKana }
            for ((line, lookup) in mapOf("思ふっっ" to "思う", "思ひっっ" to "思い")) {
                val bridge = tokenizer.tokenize(line).filterIsInstance<SmallKanaTokenizer.BridgedToken>().single()
                val word = Readings.words(line, Script.JAPANESE).single()
                assertEquals(line, line, word.text)
                assertEquals(line, lookup, word.lookup)
                assertEquals(line, false, bridge.jmdict)
                assertEquals(line, line, bridge.surface)
            }
        }
        check()
        TestUniDic.reading { check() }
    }

    @Test
    fun katakanaSmallTsuKeepsItsWord() {
        fun check() {
            val line = "きゅうッりい"
            assertEquals("kyuurri i", Readings.of(listOf(line), Script.JAPANESE).single().romanized)
            assertEquals("きゅう", Readings.words(line, Script.JAPANESE).first().lookup)
            assertEquals("きゅうッり", Readings.words(line, Script.JAPANESE).first().text)
        }
        check()
        TestUniDic.reading { check() }
    }

    private fun checkWordBeforeSmallTsu() {
        val actual = Readings.words("りゅうっなも", Script.JAPANESE).map { it.text to it.lookup }
        assertEquals(listOf("りゅうっな" to "りゅう", "も" to "も"), actual)
        val line = Readings.of(listOf("りゅうっなも"), Script.JAPANESE).single()
        assertEquals("ryuunna mo", line.romanized)
        assertEquals("ryuunna mo", line.typed)
        assertEquals(listOf("ちいさ", "なゃ"), Readings.words("ちいさなゃ", Script.JAPANESE).map { it.text })
        val after = Readings.words("りゅうっさくら", Script.JAPANESE)
        assertEquals("さくら", after.last().text)
        assertEquals("さくら", after.last().lookup)
        val travel = Readings.words("りょこうっなも", Script.JAPANESE).first()
        assertEquals("りょこうっな", travel.text)
        assertEquals("旅行", travel.lookup)
        assertTrue("$travel", Reader.JMDICT in travel.by)
    }

    @Test
    fun theSearchFindsWhatKuromojiDoesWhereNoWordStartsOnASmallKana() {
        val kuromoji = Tokenizer()
        val tokenizer = SmallKanaTokenizer { Readings.OldKana(emptyMap(), setOf("あ")) }
        val lines = listOf(
            "ジョンスミスさんは2024年にＸＹＺ社へ行った", "彁という字はない", "彁彁彁あ", "ABCアイウエオ123", "東京特許許可局で",
            "ヴォルデモートとハリー・ポッター", "αβγとабв", "今日は　いい天気", "ｶﾀｶﾅとカタカナ", "!!??…♪", "ノートパソコンを買う",
        )
        for (line in lines) {
            val searched = tokenizer.searched(line, 0)?.map { it.surface to it.allFeatures }
            assertEquals(line, kuromoji.tokenize(line).map { it.surface to it.allFeatures }, searched)
        }
    }

    @Test
    fun aDrawnOutWordIsSaidAsTheDictionarySaysIt() {
        // せんせい is said せんせー: drawn out, せんせーぃ.
        val reading = Readings.of(listOf("せんせいぃ"), Script.JAPANESE).single()
        assertEquals("senseei", reading.romanized)
        assertEquals("senseii", reading.typed)
    }

    @Test
    fun aWordIsSearchedAgainOnlyBesideTheSmallKanaItStartedOn() {
        fun words(line: String) = Readings.words(line, Script.JAPANESE).map { it.text }
        // IPADIC has いじ and ょう: only those two are searched again, so これ and もう stay as they were.
        assertEquals(listOf("きっと", "もう", "これ", "い", "じょう"), words("きっともうこれいじょう"))
    }

    @Test
    fun wordsThatStartWithASmallTsuStay() {
        fun words(line: String) = Readings.words(line, Script.JAPANESE).map { it.text }
        assertEquals(listOf("きみ", "って", "こどもっぽい"), words("きみってこどもっぽい"))
        // っ alone after て is ていく said short: 消えてった is 消えていった, and った its いった.
        assertEquals(listOf("消えて", "った"), words("消えてった"))
    }

    @Test
    fun onlyThePartOfALineSplitBeforeASmallKanaIsSearchedAgain() {
        fun words(line: String) = Readings.words(line, Script.JAPANESE).map { it.text }
        assertEquals(listOf("きょう", "は", "かいしゃ", "の", "けいひ"), words("きょうは、かいしゃのけいひ"))
    }

    @Test
    fun aWordInKanaIsOfferedOnlyWhereIpadicReadsItsKanjiSo() {
        val cached = Readings.oldKanaRead
        val vocabulary = Readings.oldKana
        fun words() = Readings.words("かいしゃのけいひ", Script.JAPANESE).map { it.text to it.lookup }
        try {
            // 携帯 is no けいひ, so the line is left with the words IPADIC knows in kana.
            Readings.oldKanaRead = Readings.OldKana(vocabulary.today + ("けいひ" to "携帯"), vocabulary.modern)
            assertTrue(words().none { it.second == "携帯" })
            assertEquals("かいしゃ" to "会社", words().first())
        } finally {
            Readings.oldKanaRead = cached
        }
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
    fun theOldSpellingOfBeingKeepsItsVerbWhenTheLineIsShort() {
        fun reading(line: String) = Readings.words(line, Script.JAPANESE).filter { it.modern != null }
            .map { listOf(it.text, it.modern, it.lookup) }
        val lines = listOf("ゐたんだ", "「ゐたんだ」", "待つてゐたんだよ")
        val being = listOf("ゐたんだ", "いたんだ", "いる")
        val wanted = listOf(listOf(being), listOf(being), listOf(listOf("待つて", "待って", "待つ"), being))
        val builtIn = lines.map(::reading)
        val uniDic = TestUniDic.reading { lines.map(::reading) }
        assertEquals(listOf(wanted, wanted), listOf(builtIn, uniDic))
        assertTrue(reading("いたんだ").isEmpty())
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
        assertEquals("matte itanda yo", line("待つてゐたんだよ").romanized)
        assertEquals(listOf("kyoo", "ame"), line("けふ 雨").romanized.split(" ").map { it.replace("kyou", "kyoo") }.take(2))
    }

    @Test
    fun oldVerbEndingsWrittenWithABigTsuReadAsSmallTsu() {
        val historical = listOf(
            "待つて" to listOf("待つて", "待って", "待つ", "matte"),
            "「放つて」" to listOf("放つて", "放って", "放つ", "hanatte"),
            "立つてから" to listOf("立つて", "立って", "立つ", "tatte")
        )
        fun readings() = historical.map { (line, _) ->
            Readings.words(line, Script.JAPANESE).filter { it.modern != null }.map { listOf(it.text, it.modern, it.lookup, it.romanized) }
        }
        val ipadic = readings()
        val unidic = TestUniDic.reading { readings() }
        val expected = historical.map { listOf(it.second) }
        assertEquals("IPADIC", expected, ipadic)
        assertEquals("UniDic", expected, unidic)
        val controls = listOf("かつて", "つてがある", "一つていねいに置く", "つてを探す", "待っている", "行つて")
        fun remaining() {
            controls.forEach { line ->
                assertEquals(line, emptyList<String>(), Readings.words(line, Script.JAPANESE).mapNotNull { it.modern })
            }
            val line = "待つてゐたんだよ"
            assertEquals("まっていたんだよ", Readings.of(listOf(line), Script.JAPANESE).single().reading)
            val noted = Readings.words(line, Script.JAPANESE).filter { it.modern != null }
            assertEquals(listOf("待って" to "待つ", "いたんだ" to "いる"), noted.map { it.modern to it.lookup })
        }
        remaining()
        TestUniDic.reading { remaining() }
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
    fun aKanaWordAlreadySpelledAsItIsTodayDoesNotGetAnOldSpellingNote() {
        val modern = listOf("はいひん", "ばいひん", "ちひき", "ふひ").flatMap { word ->
            listOf(word, "「$word」", "${word}がある")
        }
        val historical = listOf("いふ", "おもひで")
        fun notes(words: List<String>) = words.associateWith { text ->
            Readings.words(text, Script.JAPANESE).mapNotNull { it.modern }
        }
        val builtIn = notes(modern)
        val uniDic = TestUniDic.reading { notes(modern) }
        val noNotes = modern.associateWith { emptyList<String>() }
        assertEquals(listOf(noNotes, noNotes), listOf(builtIn, uniDic))
        val expected = mapOf("いふ" to listOf("いう"), "おもひで" to listOf("おもいで"))
        fun checkReadings() {
            modern.zip(Readings.of(modern, Script.JAPANESE)).forEach { (text, line) ->
                assertEquals(text, text, line.parts.joinToString("") { it.text })
            }
            assertEquals(expected, notes(historical))
            val lines = Readings.of(listOf("はいひん", "ばいひん"), Script.JAPANESE)
            assertEquals(listOf("haihin", "baihin"), lines.map { it.romanized })
        }
        checkReadings()
        TestUniDic.reading {
            checkReadings()
            assertEquals(mapOf("こふ" to listOf("こう")), notes(listOf("こふ")))
        }
    }

    @Test
    fun aRareKanaSpellingDoesNotReachAcrossTheEndOfAnOldVerb() {
        val text = "いふひと"
        fun check() {
            assertEquals(listOf("いう"), Readings.words(text, Script.JAPANESE).mapNotNull { it.modern })
            assertEquals("iu hito", Readings.of(listOf(text), Script.JAPANESE).single().romanized)
        }
        check()
        TestUniDic.reading { check() }
    }

    @Test
    fun aFuOrHiAfterKanaAloneIsNotAnOldEnding() {
        // ふ and ひ say う and い only after a kanji: ふふ is a laugh, not ふ and an old ending.
        listOf("ふふ" to "fufu", "ひふ" to "hifu").forEach { (text, romaji) ->
            assertEquals(emptyList<String>(), Readings.words(text, Script.JAPANESE).mapNotNull { it.modern })
            assertEquals(romaji, Readings.of(listOf(text), Script.JAPANESE).single().romanized.replace(" ", ""))
        }
    }

    @Test
    fun aFuInAWordOfKanaAloneIsNotAnOldEndingWhateverFollowsIt() {
        // くふうして is くふう and して, not くふ, the verb 食ふ, and うして.
        listOf("くふうして生きる", "くふうしている", "くふうが好き").forEach { line ->
            assertEquals(line, emptyList<String>(), Readings.words(line, Script.JAPANESE).mapNotNull { it.modern })
            TestUniDic.reading { assertEquals(line, emptyList<String>(), Readings.words(line, Script.JAPANESE).mapNotNull { it.modern }) }
        }
    }

    @Test
    fun aWordInOldKanaKeepsTheReadingUniDicGivesItAndNamesTheWordTodayFromItsListing() {
        TestUniDic.reading {
            // UniDic reads 云 and 言 ゆ: the reading and how it's said stay, and the word is named by its spelling today.
            listOf("云ふ" to "云う", "言ふ" to "言う").forEach { (text, modern) ->
                val word = Readings.words(text, Script.JAPANESE).single()
                assertEquals(text, listOf("ゆう", modern), listOf(word.reading, word.modern))
                assertEquals(text, "yuu", Readings.of(listOf(text), Script.JAPANESE).single().romanized)
            }
            // In kana alone, with no rule to tell it, UniDic's listing of the word does: it has no reading of its own for きのふ.
            val yesterday = Readings.words("きのふ", Script.JAPANESE).single()
            assertEquals(listOf(null, "きのう", "きのう"), listOf(yesterday.reading, yesterday.modern, yesterday.lookup))
        }
        // IPADIC alone says it きのー, the way it's said today.
        assertEquals("きのう", Readings.words("きのふ", Script.JAPANESE).single().modern)
    }

    @Test
    fun aWordInOldKanaAloneIsTheWordItIsTodayWithOrWithoutUniDic() {
        // Text, spelling today and lookup of each word with a spelling today, in a line in kana alone IPADIC splits or misreads.
        val lines = listOf(
            "おもひで" to listOf("おもひで", "おもいで", "思い出"),
            "手をとりあふ" to listOf("とりあふ", "とりあう", "とりあう"),
            "あひたい" to listOf("あひたい", "あいたい", "あう"),
            "きのふ" to listOf("きのふ", "きのう", "きのう"),
            // Its first kana is never an old ending: ひろふ is ひろう, not いろう.
            "ひろふ" to listOf("ひろふ", "ひろう", "ひろう"),
            "ひきあふ" to listOf("ひきあふ", "ひきあう", "ひきあう"),
            // Wherever it is in the line, and whatever marks are around it.
            "ずっとふるふ" to listOf("ふるふ", "ふるう", "ふるう"),
            "あっといふま" to listOf("といふ", "という", "という"),
            "「あやふく」" to listOf("あやふく", "あやうく", "あやうい"),
            "いふ（「ずっと」）" to listOf("いふ", "いう", "いう")
        )
        fun check() = lines.forEach { (line, expected) ->
            val modern = Readings.words(line, Script.JAPANESE).filter { it.modern != null }.map { listOf(it.text, it.modern, it.lookup) }
            assertEquals(line, listOf(expected), modern)
        }
        check()
        TestUniDic.reading { check() }
    }

    @Test
    fun aWholeOldKanaWordGetsItsModernReadingWithTheBuiltInDictionary() {
        val cases = listOf(
            Triple("さそふ", "さそう", "誘う"),
            Triple("におふ", "におう", "におう"),
            Triple("たまふ", "たまう", "たまう"),
            Triple("ゆふべ", "ゆうべ", "昨夜"),
            Triple("ゆふぐれ", "ゆうぐれ", "夕暮れ"),
            Triple("ふくろふ", "ふくろう", "ふくろう"),
            Triple("めまひ", "めまい", "めまい"),
            Triple("つひに", "ついに", "ついに")
        )
        cases.forEach { (text, modern, lookup) ->
            val words = Readings.words(text, Script.JAPANESE)
            assertEquals(text, listOf(Triple(text, modern, lookup)), words.map { Triple(it.text, it.modern, it.lookup) })
            assertEquals(text, modern, Readings.of(listOf(text), Script.JAPANESE).single().reading)
        }
    }

    @Test
    fun aWholeOldKanaWordDoesNotReplaceAnExistingReadingOrAModernPhrase() {
        val cases = listOf(
            "こふ" to emptyList(),
            "あるひは" to emptyList(),
            "ちひき" to emptyList(),
            "ふひ" to emptyList(),
            "きのふれる" to emptyList(),
            "きのふ" to listOf("きのう"),
            "ふるふ" to listOf("ふるう"),
            "あっといふま" to listOf("という")
        )
        cases.forEach { (text, modern) ->
            assertEquals(text, modern, Readings.words(text, Script.JAPANESE).mapNotNull { it.modern })
            val line = Readings.of(listOf(text), Script.JAPANESE).single()
            assertEquals(text, text, line.parts.joinToString("") { it.text })
        }
        TestUniDic.reading {
            assertNull(Readings.words("さそふ", Script.JAPANESE).single().reading)
            assertEquals("さそう", Readings.words("さそふ", Script.JAPANESE).single().modern)
        }
    }

    @Test
    fun anOldKanaWordOnItsOwnKeepsTheMarksAroundIt() {
        val cases = listOf(
            Triple("さそふ。", "さそう。", "誘う"),
            Triple("「ゆふべ」", "「ゆうべ」", "昨夜"),
            Triple("　たまふ！", "　たまう！", "たまう")
        )
        cases.forEach { (text, kana, lookup) ->
            val word = Readings.words(text, Script.JAPANESE).single()
            assertEquals(text, kana.trim { !it.isLetterOrDigit() }, word.modern)
            assertEquals(text, lookup, word.lookup)
            val line = Readings.of(listOf(text), Script.JAPANESE).single()
            assertEquals(text, kana, line.reading)
            assertEquals(text, text, line.parts.joinToString("") { it.text })
        }
        listOf("こふ。", "あるひは！", "今日もめまひ。").forEach { text ->
            assertEquals(text, emptyList<String>(), Readings.words(text, Script.JAPANESE).mapNotNull { it.modern })
        }
    }

    @Test
    fun aCombiningMarkAttachedToAnOldKanaPieceKeepsItsText() {
        // IPADIC keeps the leading combining dakuten in the word's first piece.
        val text = "\u3099さそふ"
        val line = Readings.of(listOf(text), Script.JAPANESE).single()
        assertEquals(text, line.parts.joinToString("") { it.text })
        assertEquals(emptyList<String>(), Readings.words(text, Script.JAPANESE).mapNotNull { it.modern })
    }

    @Test
    fun aWordInKanaAloneIsOldOnlyWhereItCanBe() {
        // A particle starts no word, an adjective's い was never ひ, and a verb in ひ may be a verb and 日: 降る日 is ふるひ.
        val lines = listOf("あとへはひく", "あめのふるひ", "ひふ", "あいうえお", "ふふふ", "あの日へ", "おひるのひ", "あひるのこ", "やまはふじ").map { it to emptyList<String>() } +
            // A word written so today that holds the old kana and more is that word: 財布, 回避, 手札, 威風, 都会風.
            listOf("さいふ", "ぼくのさいふ", "かいひ", "てふだ", "いふう", "かいらいせいふ", "とかいふう", "せいふ", "せいひ", "いひん").map { it to emptyList() } +
            // Where it's the word itself, the old one stays: いふ is いう more than 畏怖.
            listOf("いふ" to listOf("いう"), "ちょうちょうといふ" to listOf("という"))
        fun check() = lines.forEach { (line, modern) ->
            val words = Readings.words(line, Script.JAPANESE)
            assertEquals(line, modern, words.mapNotNull { it.modern })
            // And no word goes missing: the words are the whole line.
            assertEquals(line, line, words.joinToString("") { it.text })
        }
        check()
        TestUniDic.reading { check() }
        // Nor one the dictionary can't name today, あふぎ (Issue #904).
        assertEquals("あふぎ", Readings.words("あふぎ", Script.JAPANESE).joinToString("") { it.text })
    }

    @Test
    fun aModernWordTheDictionarySplitsIsSaidAsItIsWritten() {
        // IPADIC says the verb いふ as いう and いひ as いい. Inside a longer word written so today, that sound is the word's own.
        // たいふう, こうふく and まほう are modern too, and their long vowels stay. おおはば's は is the particle wa, not old kana.
        val lines = listOf(
            "さいふ" to "saifu",
            "かいひ" to "kaihi",
            "ないふ" to "naifu",
            "いふう" to "ifuu",
            "けいひ" to "keihi",
            "がいふう" to "gaifuu",
            "ぼくのさいふ" to "bokunosaifu",
            "たいふう" to "taifuu",
            "こうふく" to "koofuku",
            "まほう" to "mahoo",
            "おおはば" to "oowaba",
            "とかいふう" to "tokaifuu"
        )
        fun check() = lines.forEach { (line, romaji) ->
            val read = Readings.of(listOf(line), Script.JAPANESE).single()
            assertEquals("$line said [${read.romanized}]", romaji, read.romanized.replace(" ", ""))
            assertEquals("$line kana", line, read.reading)
            assertEquals(line, emptyList<String>(), Readings.words(line, Script.JAPANESE).mapNotNull { it.modern })
            assertEquals(line, romaji, Readings.words(line, Script.JAPANESE).joinToString("") { it.romanized.orEmpty() })
        }
        check()
        TestUniDic.reading { check() }
    }

    @Test
    fun threadsThatAskForATableAtOnceGetTheOneTableReadOnce() {
        // Issue #911: each thread that asked before the first had read the file read it again, into a table of its own.
        fun <T> atOnce(forget: () -> Unit, ask: () -> T): List<T> {
            forget()
            val go = CountDownLatch(1)
            val got = Collections.synchronizedList(mutableListOf<T>())
            val threads = List(8) { thread { go.await(); got += ask() } }
            go.countDown()
            threads.forEach { it.join() }
            return got.toList()
        }
        val listed = atOnce({ Readings.listed = null }) { Readings.listed }
        assertTrue(listed.size == 8 && listed.all { it === listed.first() })
        val oldKana = atOnce({ Readings.oldKanaRead = null }) { Readings.oldKana }
        assertTrue(oldKana.size == 8 && oldKana.all { it === oldKana.first() })
    }

    @Test
    fun theWordsInKanaFromJmdictAreReadFromTheirFile() {
        // Read from the file again, and its header is no word.
        Readings.oldKanaRead = null
        val table = Readings.oldKana
        assertTrue(table.modern.isNotEmpty() && table.modern.none { it.startsWith("#") })
        assertEquals("思い出", table.today["おもいで"])
        assertTrue("くふう" in table.modern && "おもいで" !in table.modern)
        assertEquals(table.modern.maxOf { it.length }, table.longest)
        assertTrue(table.modern.first().length < table.longest)
        // Read once: the same table after, and kept.
        assertSame(table, Readings.oldKana)
        assertSame(table, Readings.oldKanaRead)
    }

    @Test
    fun aParticleAfterAWordInOldKanaIsStillSaidAsAParticle() {
        listOf("逢ひたいは" to "aitai wa", "逢ひたいへ" to "aitai e", "云ふは" to "iu wa").forEach { (text, romaji) ->
            assertEquals(text, romaji, Readings.of(listOf(text), Script.JAPANESE).single().romanized)
            TestUniDic.reading { assertEquals(text, romaji, Readings.of(listOf(text), Script.JAPANESE).single().romanized) }
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
        // The first change stays the first when more follow.
        assertEquals(0, requireNotNull(Furigana.byRule("けふけふ")).first)
        val kyou = requireNotNull(Furigana.byRule("今日けふ"))
        assertEquals(listOf(0, 1, 2, 2, 2), kyou.origins)
        assertEquals(emptyList<Int>(), kyou.plain)
        assertEquals(listOf(2), kyou.contracted)
        assertEquals(2, kyou.first)
    }

    @Test
    fun aHoInAnOldKanaWordGetsItsModernSpellingWithoutChangingModernWords() {
        fun checkSpelling() {
            val old = mapOf("とほい" to "とおい", "おほぜい" to "おおぜい", "こほり" to "こおり", "なほさら" to "なおさら", "うるほふ" to "うるおう")
            for ((text, modern) in old) {
                assertEquals(text, modern, Readings.words(text, Script.JAPANESE).singleOrNull()?.modern)
            }
            for (text in listOf("ほほ", "まほう", "ほほえむ", "えてにほをあげる", "しりにほをかける")) {
                assertNull(text, Readings.words(text, Script.JAPANESE).firstNotNullOfOrNull(Word::modern))
            }
            // The dictionary's long vowel is still heard in a modern word that contains ほ.
            assertEquals("goohoo", Readings.words("あんごうほう", Script.JAPANESE).last().romanized)
        }
        checkSpelling()
        TestUniDic.reading(::checkSpelling)
    }

    @Test
    fun aModernWordAcrossTheEndOfAnOldSpellingCandidateKeepsItsKana() {
        fun checkSpelling() {
            for (text in listOf("とほいどう", "うるほふく", "こほりのふかい")) {
                assertEquals(text, text, Readings.of(listOf(text), Script.JAPANESE).single().reading)
                assertNull(text, Readings.words(text, Script.JAPANESE).firstNotNullOfOrNull(Word::modern))
            }
        }
        checkSpelling()
        TestUniDic.reading(::checkSpelling)
    }

    @Test
    fun repeatedOldKanaPairsSplitTheDictionaryPieceBetweenThem() {
        fun checkReading() {
            val text = "けふけふ"
            val reading = Readings.of(listOf(text), Script.JAPANESE).single()
            assertEquals("きょうきょう", reading.reading)
            val today = Word("けふ", "きょう", "kyoo", "きょう", "kyou", by = listOf(Reader.IPADIC), modern = "きょう")
            assertEquals(listOf(today, today), Readings.words(text, Script.JAPANESE))
            // A longer modern word at the pair's boundary must stay whole.
            assertEquals(listOf("け", "ふたり"), Readings.words("けふたり", Script.JAPANESE).map { it.text })
            assertNull(Readings.words("けふたり", Script.JAPANESE).firstNotNullOfOrNull(Word::modern))
        }
        checkReading()
        TestUniDic.reading(::checkReading)
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
            // Its lookup and the lyric's own text keep what is written. UniDic kept the word whole, so it read the kanji.
            assertEquals(
                Word("思ひ出", "おもいで", "omoide", "思い出", "omoide", by = listOf(Reader.UNIDIC), modern = "思い出"),
                Readings.words("思ひ出", Script.JAPANESE).single()
            )
        }
    }

    @Test
    fun aLineSplitsIntoWordsToLookUp() {
        val ipadic = listOf(Reader.IPADIC)
        assertEquals(
            listOf(Word("夜空", "よぞら", "yozora", "夜空", by = ipadic), Word("に", null, "ni", "に", by = ipadic), Word("分かった", "わかった", "wakatta", "分かる", by = ipadic)),
            Readings.words("夜空に、分かった!", Script.JAPANESE)
        )
        // Marks alone aren't words.
        assertEquals(listOf(Word("さよなら", null, "sayonara", "さよなら", by = ipadic)), Readings.words("「さよなら」…", Script.JAPANESE))
        // Chinese has no spaces: Android's dictionary splits it.
        val chinese = Readings.words("我们喜欢音乐。", Script.CHINESE)
        assertEquals(listOf("我们", "喜欢", "音乐"), chinese.map { it.text })
        assertEquals("wǒ men", chinese.first().reading)
        assertEquals(listOf(Word("사랑해", null, "saranghae", by = listOf(Reader.ROMAJA)), Word("한국", null, "hanguk", by = listOf(Reader.ROMAJA))), Readings.words("사랑해, 한국!", Script.KOREAN))
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
        // As the dictionary really reads Asphalt Lady: 重ね着's 着 as ちゃく; 君 after "Shock!" it now reads right, きみ.
        val read = Readings.of(listOf("Shock!　君はアスファルト・レディ", "恋を一枚　重ね着したね", "ガラス越しの City Talk"), Script.JAPANESE)
        assertEquals("きみ", read[0].parts.first { it.text == "君" }.reading)
        val fixed = Readings.corrected(read, listOf(ReadingFix(0, "君", "きみ", Reader.SUNG), ReadingFix(1, "着", "ぎ", Reader.SUNG), ReadingFix(2, "越", "ごし", Reader.SUNG)))
        assertEquals("きみ", fixed[0].parts.first { it.text == "君" }.reading)
        assertEquals(true, fixed[0].reading.contains("きみ") && !fixed[0].reading.contains("くん"))
        assertEquals(true, fixed[0].romanized.contains("kimi wa"))
        assertEquals(true, fixed[0].typed.contains("kimi wa") && !fixed[0].typed.contains("kun"))
        assertEquals("ぎ", fixed[1].parts.first { it.text == "着" }.reading)
        // ごし for 越 is only 越's ご, with the し written after it: nothing to put right.
        assertEquals(read[2], fixed[2])
        // いま for 今 is all 今's, though ま comes after it too.
        val now = listOf(LineReading(listOf(Ruby("今", "こん"), Ruby("まで")), "こんまで", "konmade"))
        assertEquals(LineReading(listOf(Ruby("今", "いま", Reader.SUNG), Ruby("まで")), "いままで", "ima made"), Readings.corrected(now, listOf(ReadingFix(0, "今", "いま", Reader.SUNG)))[0])
        // In katakana it's the same; a kanji the line hasn't, or a reading that isn't kana, is left out.
        assertEquals("きみ", Readings.corrected(read, listOf(ReadingFix(0, "君", "キミ", Reader.SUNG)))[0].parts.first { it.text == "君" }.reading)
        assertEquals(read, Readings.corrected(read, listOf(ReadingFix(0, "愛", "あい", Reader.SUNG), ReadingFix(1, "着", "gi", Reader.SUNG), ReadingFix(9, "君", "きみ", Reader.SUNG), ReadingFix(0, "君", "", Reader.SUNG))))
        // Only those kanji are read otherwise: the same sounds written in kana elsewhere in the line stay.
        val kana = listOf(LineReading(listOf(Ruby("君", "くん"), Ruby("はくん")), "くんはくん", "kun wa kun"))
        assertEquals(LineReading(listOf(Ruby("君", "きみ", Reader.SUNG), Ruby("はくん")), "きみはくん", "kimi wa kun"), Readings.corrected(kana, listOf(ReadingFix(0, "君", "きみ", Reader.SUNG)))[0])
        // Romaji drawn out as it's said is put right drawn out too.
        val said = listOf(LineReading(listOf(Ruby("生", "せい"), Ruby("と")), "せいと", "seeto"))
        assertEquals("shoo to", Readings.corrected(said, listOf(ReadingFix(0, "生", "しょう", Reader.SUNG)))[0].romanized)
        // Inside a word romaji runs together, the new reading is in it too: atama n naka, not atama nchuu.
        val inside = Readings.corrected(Readings.of(listOf("頭ん中 埋め尽くす"), Script.JAPANESE), listOf(ReadingFix(0, "中", "なか", Reader.SUNG)))[0]
        assertEquals("あたまんなか うめつくす", inside.reading)
        assertTrue(inside.romanized, "naka" in inside.romanized && "chuu" !in inside.romanized)
        assertTrue(inside.typed, "naka" in inside.typed && "chuu" !in inside.typed)
        // After a reading the lyrics give in brackets, it's still where it's written.
        val after = Readings.corrected(Readings.of(listOf("歌（うた）って頭の中"), Script.JAPANESE), listOf(ReadingFix(0, "中", "うち", Reader.SUNG)))[0]
        assertEquals("うたってあたまのうち", after.reading)
        assertTrue(after.romanized, after.romanized.endsWith("uchi"))
        // Typed, it's put right as the kana spell it, only where those kanji are.
        val typed = listOf(LineReading(listOf(Ruby("生", "せい"), Ruby("と"), Ruby("せい")), "せいとせい", "seeto see", "seito sei"))
        val fixedTyped = Readings.corrected(typed, listOf(ReadingFix(0, "生", "しょう", Reader.SUNG)))[0]
        assertTrue(fixedTyped.typed, fixedTyped.typed.startsWith("shou") && fixedTyped.typed.endsWith("sei"))
        assertTrue(fixedTyped.romanized, fixedTyped.romanized.startsWith("shoo") && fixedTyped.romanized.endsWith("see"))
    }

    @Test
    fun aKeptFixIsMadeAtTheOnePlaceItWasKeptFor() {
        val twice = Readings.of(listOf("本と本"), Script.JAPANESE).single()
        // Not copied to the other 本, though ours read both alike.
        val (once, none) = Readings.correctedWhereKept(twice, listOf(ReadingFix(0, "本", "ぼん", Reader.SUNG)))
        assertEquals(listOf(Ruby("本", "ぼん", Reader.SUNG), Ruby("と"), Ruby("本", "ほん")), once.parts)
        assertEquals(emptyList<Int>(), none.map { it.start })
        // The next 本 for the next fix, and never the same one twice.
        val both = Readings.correctedWhereKept(twice, listOf(ReadingFix(0, "本", "ぼん", Reader.SUNG), ReadingFix(0, "本", "ぽん", Reader.SUNG))).first
        assertEquals("ぼんとぽん", both.reading)
        assertEquals(twice, Readings.correctedWhereKept(twice, emptyList()).first)
        // Where ours join the stretch's words, it's one part; where it cuts through one of ours, it's left, and said where.
        val joined = Readings.of(listOf("三本の花"), Script.JAPANESE).single()
        assertEquals(Ruby("三本", "さんぼん"), joined.parts.first())
        val (made, cut) = Readings.correctedWhereKept(joined, listOf(ReadingFix(0, "三本", "さんぽん", Reader.SUNG)))
        assertEquals(Ruby("三本", "さんぽん", Reader.SUNG), made.parts.first())
        assertEquals(emptyList<Int>(), cut.map { it.start })
        val (same, left) = Readings.correctedWhereKept(joined, listOf(ReadingFix(0, "本", "ぽん", Reader.SUNG)))
        assertEquals(joined, same)
        assertEquals(listOf(1 to 2), left.map { it.start to it.end })
        assertEquals(listOf("ぽん"), left.map { it.fix.reading })
    }


    @Test
    fun aKeptFixThatCutsThroughAPartIsLeftAloneWhenThePartAlreadyReadsItSo() {
        fun line(vararg parts: Ruby) = LineReading(parts.toList(), parts.joinToString("") { it.reading ?: it.text }, "")
        fun cut(parts: List<Ruby>, kanji: String, reading: String) =
            Readings.correctedWhereKept(line(*parts.toTypedArray()), listOf(ReadingFix(0, kanji, reading, Reader.SUNG)))
        // The end of a run of kanji: 三本 さんぼん ends in ぼん, so there is nothing to keep and nothing to make.
        val joined = listOf(Ruby("三本", "さんぼん"), Ruby("の"), Ruby("花", "はな"))
        val (agreed, none) = cut(joined, "本", "ぼん")
        assertEquals(joined, agreed.parts)
        assertEquals(emptyList<Int>(), none.map { it.start })
        // The start of one: 三 さん begins 三本 さんぼん. Katakana says the same.
        assertEquals(emptyList<Int>(), cut(joined, "三", "サン").second.map { it.start })
        // Kana among the kanji give the place too: 食べ物 たべもの, 物 at the end of its run.
        assertEquals(emptyList<Int>(), cut(listOf(Ruby("食べ物", "たべもの")), "物", "もの").second.map { it.start })
        // The fix is the run itself, with kana around it: 食 た in 食べる.
        assertEquals(emptyList<Int>(), cut(listOf(Ruby("食べる", "たべる")), "食", "た").second.map { it.start })
        // The place counts from the start of the part, not of the line: 三本 is the second part here.
        val later = listOf(Ruby("花", "はな"), Ruby("の"), Ruby("三本", "さんぼん"))
        assertEquals(emptyList<Int>(), cut(later, "本", "ぼん").second.map { it.start })
        assertEquals(listOf(3), cut(later, "本", "ぽん").second.map { it.start })
        assertEquals(emptyList<Int>(), cut(later, "三", "さん").second.map { it.start })
        // A reading the part doesn't hold at that place is the card's to keep, as it was: ぽん is not at the end of さんぼん.
        val (same, left) = cut(joined, "本", "ぽん")
        assertEquals(line(*joined.toTypedArray()), same)
        assertEquals(listOf(1 to 2), left.map { it.start to it.end })
        // Nor at the other end: さん is not at the end, and ぼん is not at the start.
        assertEquals(listOf(0), cut(joined, "三", "ぼん").second.map { it.start })
        assertEquals(listOf(1), cut(joined, "本", "さん").second.map { it.start })
        // The fix's reading is all the part has: nothing is left for the other kanji, so it can't hold.
        assertEquals(listOf(1), cut(listOf(Ruby("三本", "ぼん")), "本", "ぼん").second.map { it.start })
    }

    @Test
    fun aKeptFixThatCutsThroughAPartIsNotTakenToAgreeWhenItsPlaceIsNotKnown() {
        fun line(vararg parts: Ruby) = LineReading(parts.toList(), parts.joinToString("") { it.reading ?: it.text }, "")
        fun left(vararg parts: Ruby, kanji: String, reading: String) =
            Readings.correctedWhereKept(line(*parts), listOf(ReadingFix(0, kanji, reading, Reader.SUNG))).second.map { it.start }
        // In the middle of a run: 二 に is inside いちにさん, but where one kanji's reading stops can't be told.
        assertEquals(listOf(1), left(Ruby("一二三", "いちにさん"), kanji = "二", reading = "に"))
        // A reading shared with the kana: 飛び立 ゆく is not laid over 立 alone, so ゆく ending in く says nothing.
        assertEquals(listOf(2), left(Ruby("飛び立つ", "ゆく"), kanji = "立", reading = "く"))
        // The fix cuts through two parts, so no one part holds it.
        assertEquals(listOf(1), left(Ruby("三本", "さんぼん"), Ruby("目", "め"), kanji = "本目", reading = "ぼんめ"))
        // The fix reaches from kanji into the kana after them, 食べ in 食べる, so no one piece of the part has it all.
        assertEquals(listOf(0), left(Ruby("食べる", "たべる"), kanji = "食べ", reading = "たべ"))
        // Or it names kana alone, べ, which no reading is laid over.
        assertEquals(listOf(1), left(Ruby("食べる", "たべる"), kanji = "べ", reading = "べ"))
        // A part with no reading holds nothing.
        assertEquals(listOf(1), left(Ruby("三本"), kanji = "本", reading = "ぼん"))
    }


    @Test
    fun aSungReadingIsMadeWhereItsKanjiAreHoweverTheDictionarySplitThem() {
        fun line(vararg parts: Ruby) = LineReading(parts.toList(), parts.joinToString("") { it.reading ?: it.text }, "")
        // UtaTen reads as one what the dictionary split in two: 闇間 is やみま, not 闇 and 間 read やみかん.
        val joined = Readings.corrected(listOf(line(Ruby("闇", "やみ"), Ruby("間", "かん"), Ruby("に"))), listOf(ReadingFix(0, "闇間", "やみま", Reader.SUNG)))[0]
        assertEquals(listOf(Ruby("闇間", "やみま", Reader.SUNG), Ruby("に")), joined.parts)
        assertEquals("やみまに", joined.reading)
        // And splits what the dictionary read as one: 身体 からだ and 中 じゅう, not 身体中 しんたいちゅう.
        val whole = line(Ruby("身体中", "しんたいちゅう"), Ruby("が"))
        val split = Readings.corrected(listOf(whole), listOf(ReadingFix(0, "身体", "からだ", Reader.SUNG), ReadingFix(0, "中", "じゅう", Reader.SUNG)))[0]
        assertEquals(listOf(Ruby("身体", "からだ", Reader.SUNG), Ruby("中", "じゅう", Reader.SUNG), Ruby("が")), split.parts)
        assertEquals("からだじゅうが", split.reading)
        // Only when they read all of it: a kanji left without its reading would have none.
        assertEquals(whole, Readings.corrected(listOf(whole), listOf(ReadingFix(0, "身体", "からだ", Reader.SUNG)))[0])
        assertEquals(whole, Readings.corrected(listOf(whole), listOf(ReadingFix(0, "中", "じゅう", Reader.SUNG)))[0])
        assertEquals(whole, Readings.corrected(listOf(whole), listOf(ReadingFix(0, "身体", "からだ", Reader.SUNG), ReadingFix(0, "中", "ju", Reader.SUNG)))[0])
        // Kana between them stay, and so do those after.
        val between = line(Ruby("春"), Ruby("の"), Ruby("日々", "ひび"))
        assertEquals(between, Readings.corrected(listOf(between), listOf(ReadingFix(0, "日", "ひ", Reader.SUNG)))[0])
        val around = Readings.corrected(listOf(line(Ruby("夢見草", "ゆめみそう"), Ruby("よ"))), listOf(ReadingFix(0, "夢", "ゆめ", Reader.SUNG), ReadingFix(0, "見草", "みぐさ", Reader.SUNG)))[0]
        assertEquals(listOf(Ruby("夢", "ゆめ", Reader.SUNG), Ruby("見草", "みぐさ", Reader.SUNG), Ruby("よ")), around.parts)
        // A reading of kanji with kana among them that can't be laid over the kanji alone goes over all of it.
        val unlaid = line(Ruby("不本意", "ふほんい"), Ruby("な"), Ruby("現状", "げんじょう"))
        assertEquals(listOf(Ruby("不本意な現状", "いま", Reader.SUNG)), Readings.corrected(listOf(unlaid), listOf(ReadingFix(0, "不本意な現状", "いま", Reader.SUNG)))[0].parts)
        // As is one that reaches into a word without covering it, or has no kanji to read.
        assertEquals(unlaid, Readings.corrected(listOf(unlaid), listOf(ReadingFix(0, "意な現", "いなげん", Reader.SUNG), ReadingFix(0, "な", "に", Reader.SUNG)))[0])
        // A kanji the dictionary couldn't read takes the reading it's sung with.
        assertEquals(listOf(Ruby("踠", "もが", Reader.SUNG), Ruby("く")), Readings.corrected(listOf(line(Ruby("踠"), Ruby("く"))), listOf(ReadingFix(0, "踠", "もが", Reader.SUNG)))[0].parts)
        // A reading drawn out with ー, as a word from another language is sung: 狩人 as イェーガー.
        val drawn = Readings.corrected(listOf(line(Ruby("狩人", "かりゅうど"))), listOf(ReadingFix(0, "狩人", "イェーガー", Reader.SUNG)))[0]
        assertEquals(listOf(Ruby("狩人", "いぇーがー", Reader.SUNG)), drawn.parts)
        assertEquals("yeegaa", drawn.romanized)
        // And the rest of katakana's sounds for words from other languages.
        assertEquals(
            listOf("sheru", "jetto", "chekku", "wooku", "tsaa", "tsiitaa", "tsee", "tsoo", "tuu", "duu", "viinasu", "vee", "voo"),
            listOf("シェル", "ジェット", "チェック", "ウォーク", "ツァー", "ツィーター", "ツェー", "ツォー", "トゥー", "ドゥー", "ヴィーナス", "ヴェー", "ヴォー").map { Readings.romaji(it) }
        )
        // The first reading for a kanji is its own; another for the same kanji later is not.
        val twice = Readings.corrected(listOf(line(Ruby("君", "くん"))), listOf(ReadingFix(0, "君", "きみ", Reader.SUNG), ReadingFix(0, "君", "くん", Reader.SUNG)))[0]
        assertEquals(listOf(Ruby("君", "きみ", Reader.SUNG)), twice.parts)
    }

    @Test
    fun wordsTheDictionaryReadsAsPartOfAnotherAreReadAsTheyStand() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> (if (withUniDic) TestUniDic.reading { reading(line) } else reading(line)).reading }
            // A suffix after a mark, not a noun: 心 after 走れ! is こころ, and 君 after ( is きみ.
            assertEquals("$withUniDic", "はしれ!こころにきめた", read("走れ!心に決めた"))
            assertEquals("$withUniDic", "あめ (きみのこえ)", read("雨 (君の声)"))
            // But a suffix after a word stays one: 運転手 is うんてんしゅ, 飛び方 とびかた, 4時 よじ; and so does a prefix before kanji, 前前前世.
            assertEquals("$withUniDic", "うんてんしゅ", read("運転手"))
            assertEquals("$withUniDic", "だんすてをあげる", read("ダンス手を上げる"))
            assertEquals("$withUniDic", "かしゅをしる", read("歌手を知る"))
            // Katakana to its edges, ァ and ヺ, and its long mark, as in ファン and ダンサー.
            assertEquals("$withUniDic", "だんさーてをあげる", read("ダンサー手を上げる"))
            assertEquals("$withUniDic", "ふぁんてをふる", read("ファン手を振る"))
            assertEquals("$withUniDic", "ヺてをあげる", read("ヺ手を上げる"))
            // Only before を: with nothing after, it's left as the dictionary reads it.
            assertEquals("$withUniDic", "だんすしゅ", read("ダンス手"))
            assertEquals("$withUniDic", "とびかたをしる", read("飛び方を知る"))
            assertTrue("$withUniDic", read("4時に").endsWith("じに"))
            assertTrue("$withUniDic", read("10年後に").endsWith("ねんごに"))
            assertTrue("$withUniDic", read("前前前世から").startsWith("ぜんぜんぜん"))
            // 君 after anything but a name is きみ; after one, くん.
            assertEquals("$withUniDic", "なんどきみとみた", read("何度君と見た"))
            assertTrue("$withUniDic", read("太郎君と").endsWith("くんと"))
            assertTrue("$withUniDic", read("ケンジ君と").endsWith("くんと"))
            // The katakana name range includes its first letter and its last, and a long mark.
            assertTrue("$withUniDic", read("ファ君と").endsWith("くんと"))
            assertTrue("$withUniDic", read("ヺ君と").endsWith("くんと"))
            assertTrue("$withUniDic", read("カー君と").endsWith("くんと"))
            // A middle dot is not katakana. 君 after that word is きみ, not くん.
            assertTrue("$withUniDic", read("フ・君と").endsWith("きみと"))
            // 愛して and 楽して are verbs, not an adjective's stem: あいして, not いとして.
            assertEquals("$withUniDic", "そのときは あいしてる", read("その時は 愛してる"))
            assertEquals("$withUniDic", "らくしていきる", read("楽して生きる"))
            assertEquals("$withUniDic", "いとしいひと", read("愛しい人"))
            // 何 before d, t or n is なん; before the rest, なに.
            assertEquals("$withUniDic", "なんでもいい", read("何でもいい"))
            assertEquals("$withUniDic", "なんのために", read("何のために"))
            assertEquals("$withUniDic", "なにもない", read("何もない"))
            assertEquals("$withUniDic", "なにがほしい", read("何が欲しい"))
            // 色 after one kanji or kana is いろ; after a word of more kanji, しょく.
            assertEquals("$withUniDic", "なみだいろのはな", read("涙色の花"))
            assertEquals("$withUniDic", "ちほうしょく", read("地方色"))
        }
    }

    @Test
    fun kanaAfterAKanjiWrittenInKatakanaAreReadAsIfInHiragana() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> if (withUniDic) TestUniDic.reading { reading(line) } else reading(line) }
            assertEquals("$withUniDic", "よるにまぎれ", read("夜ニ紛レ").reading)
            assertEquals("$withUniDic", listOf(Ruby("夜", "よる"), Ruby("ニ"), Ruby("紛", "まぎ"), Ruby("レ")), read("夜ニ紛レ").parts)
            assertEquals("$withUniDic", "こえもとどかない", read("声モ届カナイ").reading)
            // Two such words in one line: each is read, and the second still starts where the first ended.
            assertEquals(
                "$withUniDic",
                if (withUniDic) "よるにまぎれてせいもとどかない" else "よるにまぎれてごえもとどかない",
                read("夜ニ紛レテ声モ届カナイ").reading,
            )
            // A word in katakana after a kanji is still that word.
            assertEquals("$withUniDic", listOf(Ruby("夏", "なつ"), Ruby("アイス")), read("夏アイス").parts)
        }
    }

    @Test
    fun numbersRunIntoTheCountersAfterThemAsTheyreSaid() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> (if (withUniDic) TestUniDic.reading { reading(line) } else reading(line)).reading }
            // 十, 八 and 一 run into k, s, t and h; 六 and 百 into k and h, and h is then p.
            assertEquals("$withUniDic", "じゅっかい", read("十回"))
            assertEquals("$withUniDic", "はっさい", read("八歳"))
            assertEquals("$withUniDic", "ろっぽん", read("六本"))
            assertEquals("$withUniDic", "なんびゃっかい", read("何百回"))
            assertEquals("$withUniDic", "ろくさい", read("六歳"))
            // Nor into another number, or a counter they don't run into.
            assertEquals("$withUniDic", "じゅうさん", read("十三"))
            assertEquals("$withUniDic", "じゅうじに", read("十時に"))
            // 百 and 千 after a number sound with it: さんびゃく, ろっぴゃく, はっせん, なんぜん.
            assertEquals("$withUniDic", "さんびゃくねん", read("三百年"))
            assertEquals("$withUniDic", "ろっぴゃく", read("六百"))
            assertEquals("$withUniDic", "はっせんねん", read("八千年"))
            assertEquals("$withUniDic", "なんぜんかい", read("何千回"))
            assertEquals("$withUniDic", "にひゃく", read("二百"))
            // 万 and 億 do not. 三 stays さん, and 何 stays なん.
            assertEquals("$withUniDic", "さんまん", read("三万"))
            assertEquals("$withUniDic", "なんおく", read("何億"))
            // 色 is a colour, not a counter, so 十 does not run into it.
            assertEquals("$withUniDic", "じゅういろ", read("十色"))
            assertEquals("$withUniDic", "はちいろ", read("八色"))
            // A counter of more than one character, as the dictionary has it, runs in too; a kana that is not a kanji is no counter.
            assertEquals("$withUniDic", "じゅっかげつ", read("十ヶ月"))
            assertEquals("$withUniDic", "いっしゅうかん", read("一週間"))
            assertEquals("$withUniDic", "いっぷんかん", read("一分間"))
            assertEquals("$withUniDic", "じゅうこ", read("十こ"))
            assertEquals("$withUniDic", "にぶんのいち", read("二分の一"))
        }
    }

    @Test
    fun countersSoundOtherwiseAfterN() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> (if (withUniDic) TestUniDic.reading { reading(line) } else reading(line)).reading }
            // After さん and なん, 本, 杯, 匹, 階 and 歩 are said with a voice or a p.
            assertEquals("$withUniDic", "さんぼんのはな", read("三本の花"))
            assertEquals("$withUniDic", "なんがいまで", read("何階まで"))
            assertEquals("$withUniDic", "さんばいめ", read("三杯目"))
            assertEquals("$withUniDic", "さんびきのねこ", read("三匹の猫"))
            assertEquals("$withUniDic", "さんぽすすんで", read("三歩進んで"))
            // After よん they're as written, but for 分, which is ぷん after any ん.
            assertEquals("$withUniDic", "よんほん", read("四本"))
            assertEquals("$withUniDic", "よんぷん", read("四分"))
            assertEquals("$withUniDic", "あとさんぷんで", read("あと三分で"))
            // Counters that don't change stay: 三回 さんかい.
            assertEquals("$withUniDic", "さんかいめ", read("三回目"))
            // A word after a number that doesn't count it stays as it is: 八方向 はちほうこう.
            assertEquals("$withUniDic", "はちほうこうに", read("八方向に"))
            assertEquals("$withUniDic", "ろくほうこう", read("六方向"))
        }
    }

    @Test
    fun aHalfAndEnoughBeforeATimeAreMinutes() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> (if (withUniDic) TestUniDic.reading { reading(line) } else reading(line)).reading }
            assertEquals("$withUniDic", "にふんごにきた", read("二分後に来た"))
            assertEquals("$withUniDic", "じゅっぷんまえ", read("十分前"))
            assertEquals("$withUniDic", "ごふんまえ", read("五分前"))
            // 何 counting minutes is なん, as it is before any counter: なんぷん, not なにふん.
            assertEquals("$withUniDic", "なんぷんまえ", read("何分前"))
            assertEquals("$withUniDic", "なんぷんいない", read("何分以内"))
            // Before anything else, 十分 is enough, and 二分 a half.
            assertEquals("$withUniDic", "じゅうぶんだよ", read("十分だよ"))
            assertEquals("$withUniDic", "にぶんする", read("二分する"))
            // A word that only ends in 分 is no number of minutes: 随分前 ずいぶんまえ.
            assertEquals("$withUniDic", "ずいぶんまえに", read("随分前に"))
            // 分 after a number after 寸 or 割 is a tenth, ぶ.
            assertEquals("$withUniDic", "よんすんよんぶ", read("四寸四分"))
            assertEquals("$withUniDic", "ごわりごぶ", read("五割五分"))
        }
    }

    @Test
    fun whatBeforeACounterIsNan() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> (if (withUniDic) TestUniDic.reading { reading(line) } else reading(line)).reading }
            mapOf("何本" to "なんぼん", "何回" to "なんかい", "何匹" to "なんびき", "何杯" to "なんばい", "何階" to "なんがい", "何人" to "なんにん")
                .forEach { (line, kana) -> assertEquals("$line $withUniDic", kana, read(line)) }
        }
    }

    @Test
    fun aKanjiWithAClassicalEndingIsReadAsTheClassicalVerb() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> if (withUniDic) TestUniDic.reading { reading(line) } else reading(line) }
            // 晴るる and 流る, not はれるる and ながれる.
            assertEquals("$withUniDic", "はるるそら", read("晴るる空").reading)
            assertTrue("$withUniDic", Ruby("晴", "は") in read("晴るる空").parts)
            assertEquals("$withUniDic", "ながるかわ", read("流る川").reading)
            // The modern verb, written so, stays.
            assertEquals("$withUniDic", "はれるそら", read("晴れる空").reading)
            assertEquals("$withUniDic", "ながれるかわ", read("流れる川").reading)
            // And so does one whose れ is in its kanji, as the dictionary has it: 洒落る is しゃれる, not しゃる.
            assertEquals("$withUniDic", "しゃれる", read("洒落る").reading)
            assertEquals("$withUniDic", "しゃれるふく", read("洒落る服").reading)
            // The same for each verb JMdict writes so and reads one way only: 小洒落る. (黄昏る is たそがる too.)
            assertEquals("$withUniDic", "こじゃれる", read("小洒落る").reading)
        }
    }

    @Test
    fun aNounUniDicKnowsIsNotASuffixToTheWordBefore() {
        TestUniDic.reading {
            // IPADIC takes these for suffixes after the noun before them; UniDic reads them as the nouns they are.
            assertEquals("すてきなざわめきこころに", reading("素敵なざわめき心に").reading)
            assertEquals("ぼくらてをたたいて", reading("僕ら手を叩いて").reading)
            assertEquals("いつかひがのぼる", reading("いつか日が昇る").reading)
            // A suffix said with a voice after a noun it's part of stays: ガラス玉, and 一番星.
            assertEquals("がらすだま", reading("ガラス玉").reading)
            assertEquals("いちばんぼしを", reading("一番星を").reading)
            // After a number it counts.
            assertEquals("さんにんで", reading("三人で").reading)
        }
    }

    @Test
    fun aHandAfterAPronounIsAHandNotASuffix() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> (if (withUniDic) TestUniDic.reading { reading(line) } else reading(line)).reading }
            // IPADIC takes 手 for the suffix of a person, しゅ, after 僕ら: it is the hand, before any particle.
            assertEquals("$withUniDic", "ぼくらてをたたいて", read("僕ら手を叩いて"))
            assertEquals("$withUniDic", "ぼくらてがふるえる", read("僕ら手が震える"))
            // A suffix a pronoun does take stays: あなた方, 私達 わたしたち.
            assertEquals("$withUniDic", "あなたかたは", read("あなた方は"))
            assertEquals("$withUniDic", "わたしたちは", read("私達は"))
        }
    }

    @Test
    fun aSuffixSaidWithAVoiceAfterAWordItCantJoinIsTheNounItIsAlone() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> (if (withUniDic) TestUniDic.reading { reading(line) } else reading(line)).reading }
            // 日 after いつか and いつの is a day, ひ, not the び of a word's second half.
            assertEquals("$withUniDic", "いつかひがのぼる", read("いつか日が昇る"))
            assertEquals("$withUniDic", "いつのひも", read("いつの日も"))
            // 星 after how many times is a star, ほし, as it's after 一度 or いちど.
            assertEquals("$withUniDic", "いちどほしに", read("一度星に"))
            assertEquals("$withUniDic", "もいちどほしにひかれ", read("もいちど星にひかれ"))
            assertEquals("$withUniDic", "なんどほしに", read("何度星に"))
            // After a word it's the second half of, it stays: 一番星, ガラス玉, and 日 after a kanji.
            assertEquals("$withUniDic", "いちばんぼしを", read("一番星を"))
            assertEquals("$withUniDic", "がらすだま", read("ガラス玉"))
            // 一度 and 星 as words: 一度だけ stays.
            assertEquals("$withUniDic", "いちどだけ", read("一度だけ"))
        }
    }

    @Test
    fun aKanjiAfterAVerbIsNotANameForHito() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> (if (withUniDic) TestUniDic.reading { reading(line) } else reading(line)).reading }
            // 等 is ひとし, a name, but after a verb it's など.
            assertEquals("$withUniDic", "かなうなど", read("叶う等"))
            assertEquals("$withUniDic", "かなうなど…", read("叶う等…"))
        }
        // Where it follows a surname it's the name.
        assertEquals("やまだひとし", reading("山田等").reading)
    }

    @Test
    fun aNounAfterANumberAndACounterIsSaidWithAVoice() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> (if (withUniDic) TestUniDic.reading { reading(line) } else reading(line)).reading }
            assertEquals("$withUniDic", "せんぼんざくら よるにまぎれ", read("千本桜 夜ニ紛レ"))
            assertEquals("$withUniDic", "ひゃっぽんざくらがさく", read("百本桜が咲く"))
            assertEquals("$withUniDic", "かたるにまいば", read("騙る二枚刃"))
            // One word, as it's spelled in romaji and over the kanji.
            val word = if (withUniDic) TestUniDic.reading { reading("千本桜") } else reading("千本桜")
            assertEquals("$withUniDic", listOf(Ruby("千本桜", "せんぼんざくら")), word.parts)
            assertEquals("$withUniDic", "senbonzakura", word.romanized)
            val blade = if (withUniDic) TestUniDic.reading { reading("二枚刃") } else reading("二枚刃")
            assertEquals("$withUniDic", listOf(Ruby("二枚刃", "にまいば")), blade.parts)
            // 桜 and 刃 after anything else are as they are alone.
            assertEquals("$withUniDic", "はるのさくら", read("春の桜"))
            assertEquals("$withUniDic", "さくらがさく", read("桜が咲く"))
            // Nor after a word of more than one kanji that is no number and counter.
            assertEquals("$withUniDic", "せかいさくら", read("世界桜"))
            assertEquals("$withUniDic", "はのひかり", read("刃の光"))
        }
    }

    @Test
    fun aThousandNightsIsSenYaWhateverNameUniDicHasForIt() {
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> (if (withUniDic) TestUniDic.reading { reading(line) } else reading(line)).reading }
            assertEquals("$withUniDic", "ひとりまうせんや", read("一人舞う千夜"))
            assertEquals("$withUniDic", "さんやのつき", read("三夜の月"))
        }
    }

    @Test
    fun wordsJmdictListsAreReadAsItReadsThem() {
        // Read from the file again, and its header is no word.
        Readings.listed = null
        assertTrue(Readings.listed.orEmpty().keys.none { it.startsWith("#") })
        for (withUniDic in listOf(false, true)) {
            val read = { line: String -> if (withUniDic) TestUniDic.reading { reading(line) } else reading(line) }
            // Pieces that make a word JMdict reads one way only: 体中 からだじゅう, 水車小屋 すいしゃごや.
            assertEquals("$withUniDic", "からだじゅうがあつい", read("体中が熱い").reading)
            assertEquals("$withUniDic", "すいしゃごや", read("水車小屋").reading)
            assertTrue("$withUniDic", Ruby("体中", "からだじゅう") in read("体中が").parts)
            // A verb is looked up as the dictionary writes it, and keeps the ending the line gives it.
            assertEquals("$withUniDic", "にくづいた", read("肉付いた").reading)
            // Not out of part of a piece, 一人舞 out of 一人舞う, nor a word ending in a particle, 今日は.
            assertEquals("$withUniDic", "ひとりまう", read("一人舞う").reading)
            assertEquals("$withUniDic", "きょうははれ", read("今日は晴れ").reading)
            // Nor one piece alone the dictionary reads as a word it knows: 上野 is うえの, not JMdict's こうずけ.
            assertEquals("$withUniDic", "うえののはな", read("上野の花").reading)
            // Nor the start of a longer word in kanji: 一重 is ひとえ, but 一重項 is いちじゅうこう.
            assertEquals("$withUniDic", "いちじゅうこう", read("一重項").reading)
            // A number that ends a line, or goes on in kana or into a verb, is the word JMdict reads.
            assertEquals("$withUniDic", "ひとこきゅう", read("一呼吸").reading)
            assertEquals("$withUniDic", "ひとこきゅうおいて", read("一呼吸おいて").reading)
            assertEquals("$withUniDic", "ひとこきゅうおいて", read("一呼吸置いて").reading)
            assertEquals("$withUniDic", "いちにんのりじてんしゃ", read("一人乗り自転車").reading)
            // A word that starts with no number goes on as JMdict reads it: 保湿 of 保湿剤.
            assertEquals("$withUniDic", "ほしつざい", read("保湿剤").reading)
            // Kana end a word, so a verb before a noun in kanji is still the word JMdict reads: 目醒める時.
            assertEquals("$withUniDic", "めざめるとき", read("目醒める時").reading)
        }
        assertEquals(listOf(Reader.JMDICT), Readings.words("水車小屋", Script.JAPANESE).single().by)
        // Without the table, as when it's made, the dictionaries read the word.
        Readings.listed = emptyMap()
        try {
            assertEquals("からだちゅうが", reading("体中が").reading)
            // A word listed as its pieces read it stays those pieces.
            Readings.listed = mapOf("花火大会" to reading("花火大会").reading)
            assertTrue(Readings.words("花火大会", Script.JAPANESE).none { Reader.JMDICT in it.by })
        } finally {
            Readings.listed = null
        }
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
            assertEquals("$withUniDic", "kumo ga mada futari no kage o nokosu kara", line.romanized)
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
        assertEquals("Shock! kimi wa asufaruto redi", line.romanized)
        assertEquals("Shock! kimi wa asufaruto redi", line.typed)
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
            // 羽 stays the noun. A space can make はね look like the verb 跳ね, and rewriting every one would also rewrite 鳥はねた.
            assertEquals("$withUniDic", "hane o hirogete", read("羽を広げて").romanized)
            assertTrue("$withUniDic", read("鳥はねた").romanized.startsWith("tori haneta"))
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
            assertEquals(Word("溢した", "こぼした", "koboshita", lookup = "溢す", by = listOf(Reader.UNIDIC)), Readings.words("君が溢した", Script.JAPANESE).last())
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
    fun aWordTakenForAPrefixOrASuffixToNothingIsAWord() {
        assertEquals("きみにあいたい", reading(" 君に会いたい").reading.trim())
        assertEquals("ああいまとなりで", reading("ああ今となりで").reading)
        // A prefix before a noun stays one: 今世紀 is こんせいき.
        assertEquals("こんせいき", reading("今世紀").reading)
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
        // After the mark that closes the kanji, it's still their reading, and the mark stays where it is.
        reading("《運命》（さだめ）を越えて").let {
            assertEquals("《運命》（さだめ）を越えて", it.parts.joinToString("") { part -> part.text })
            assertEquals(Ruby("運命", "さだめ"), it.parts.first { part -> part.reading != null })
            assertTrue(it.reading, "さだめをこえて" in it.reading)
        }
        assertTrue("ゆめ" in reading("「夢」（ゆめ）").reading)
        // For all the marks hold, kana too, laid over all of it when it can't be laid over the kanji alone.
        reading("《今の気持ち》（こころ）が").let {
            assertEquals("《今の気持ち》（こころ）が", it.parts.joinToString("") { part -> part.text })
            assertEquals(listOf(Ruby("今の気持ち", "こころ")), it.parts.filter { part -> part.reading != null })
            assertTrue(it.reading, "こころが" in it.reading && "きもち" !in it.reading)
        }
        // Only after a mark that closes them: inside the marks, it's for the kanji before it alone.
        assertTrue(reading("「君と僕（ぼく）」").reading.contains("きみとぼく"))
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
        assertEquals(emptyList<Word>(), Readings.words("123 !\u0021", Script.JAPANESE))
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
        assertEquals(read, Readings.corrected(read, listOf(ReadingFix(0, "は", "き", Reader.SUNG))))
        // The same reading it has is no fix.
        assertEquals(read, Readings.corrected(read, listOf(ReadingFix(0, "君", "くん", Reader.SUNG))))
        // Only the kanji read as the first was.
        val fixed = Readings.corrected(read, listOf(ReadingFix(0, "君", "きみ", Reader.SUNG)))[0]
        assertEquals(listOf("きみ", null, "きみ"), fixed.parts.map { it.reading })
    }

    @Test
    fun theSameKanjiLaterKeepsTheSungReadingAndAFixMayStartWhereAnotherEnds() {
        // One fix for a kanji covers the next one when the dictionary read them the same.
        val twice = LineReading(listOf(Ruby("中", "ちゅう"), Ruby("と"), Ruby("中", "ちゅう")), "ちゅうとちゅう", "")
        val both = Readings.corrected(listOf(twice), listOf(ReadingFix(0, "中", "じゅう", Reader.SUNG)))[0]
        assertEquals(listOf("じゅう", null, "じゅう"), both.parts.map { it.reading })
        // Listed second, the earlier kanji still gets its reading: the two fixes only touch.
        val whole = LineReading(listOf(Ruby("身体中", "しんたいちゅう"), Ruby("が")), "しんたいちゅうが", "")
        val reversed = Readings.corrected(listOf(whole), listOf(ReadingFix(0, "中", "じゅう", Reader.SUNG), ReadingFix(0, "身体", "からだ", Reader.SUNG)))[0]
        assertEquals(listOf(Ruby("身体", "からだ", Reader.SUNG), Ruby("中", "じゅう", Reader.SUNG), Ruby("が")), reversed.parts)
        // The kanji are not the first part. The part after が is still split.
        val tail = LineReading(listOf(Ruby("が"), Ruby("身体中", "しんたいちゅう")), "がしんたいちゅう", "")
        val later = Readings.corrected(listOf(tail), listOf(ReadingFix(0, "身体", "からだ", Reader.SUNG), ReadingFix(0, "中", "じゅう", Reader.SUNG)))[0]
        assertEquals(listOf(Ruby("が"), Ruby("身体", "からだ", Reader.SUNG), Ruby("中", "じゅう", Reader.SUNG)), later.parts)
        // A sung reading may be the first hiragana, the last, or the mark that draws one out.
        fun sung(reading: String) = Readings.corrected(
            listOf(LineReading(listOf(Ruby("中", "ちゅう")), "ちゅう", "")),
            listOf(ReadingFix(0, "中", reading, Reader.SUNG)),
        )[0]
        assertEquals(listOf(Ruby("中", "ぁ", Reader.SUNG)), sung("ぁ").parts)
        assertEquals(listOf(Ruby("中", "ゟ", Reader.SUNG)), sung("ゟ").parts)
        assertEquals(listOf(Ruby("中", "ー", Reader.SUNG)), sung("ー").parts)
        // The mark after the last hiragana is not a reading, so that fix is left unused.
        assertEquals(listOf(Ruby("中", "ちゅう")), sung("゠").parts)
    }
}
