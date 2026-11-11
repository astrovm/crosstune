package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * Where a word's reading goes over it. [Furigana.over] once tried each way to lay the reading, copying the parts of each;
 * a long word, a reading in brackets over a quoted phrase, took seconds. It now works each run and place in the reading
 * out once. [Before] is the old way, kept to show the new one lays every reading where the old one did.
 */
@RunWith(RobolectricTestRunner::class)
class FuriganaTest {

    @Test
    fun aReadingIsLaidOverTheKanjiAndOverAKanaOnlyWhereItsSaidOtherwise() {
        assertEquals(listOf(Ruby("飛", "と"), Ruby("び"), Ruby("立", "た"), Ruby("つ")), Furigana.over("飛び立つ", "とびたつ"))
        assertEquals(listOf(Ruby("思", "おも"), Ruby("ひ", "い"), Ruby("出", "で")), Furigana.over("思ひ出", "おもいで"))
        // Its kana not in the reading: one reading over the kanji and the kana among them, not a guessed split.
        assertEquals(listOf(Ruby("飛び立", "ゆく"), Ruby("つ")), Furigana.over("飛び立つ", "ゆく"))
        // No reading: the word stays as written.
        assertEquals(listOf(Ruby("山の川")), Furigana.over("山の川", ""))
    }

    @Test
    fun everyReadingIsLaidWhereTheOldWayLaidIt() {
        val random = Random(954)
        repeat(20_000) {
            val word = word(random, random.nextInt(1, 10))
            val kana = if (random.nextInt(4) == 0) String(CharArray(random.nextInt(0, 12)) { KANA[random.nextInt(KANA.length)] }) else reading(random, word)
            assertEquals("$word / $kana", Before.over(word, kana), Furigana.over(word, kana))
        }
    }

    @Test
    fun wordsAsLongAsAQuotedPhraseAreLaidWhereTheOldWayLaidThem() {
        val random = Random(18)
        repeat(30) {
            val word = word(random, random.nextInt(14, 19))
            val kana = reading(random, word)
            assertEquals("$word / $kana", Before.over(word, kana), Furigana.over(word, kana))
        }
    }

    @Test
    fun aLongReadingInBracketsTakesNoTimeToLay() {
        // 108 letters and 138 kana: the old way took 1.6 s at half that, and minutes at this.
        val word = "飛び立つ空を見上げた".repeat(11).take(108)
        val kana = "とびたつそらをみあげた".repeat(13).take(138)
        val start = System.nanoTime()
        val parts = Furigana.over(word, kana)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$ms ms", ms < 1_000)
        assertEquals(word, parts.joinToString("") { it.text })
    }

    /** A made-up word of [length] letters: kanji, and kana of every kind the reading treats apart. */
    private fun word(random: Random, length: Int) =
        String(CharArray(length) { if (random.nextInt(3) == 0) KANJI[random.nextInt(KANJI.length)] else KANA[random.nextInt(KANA.length)] })

    /** A reading for [word]: its kana mostly as written, now and then left out, changed or in hiragana, and a few kana for each kanji. */
    private fun reading(random: Random, word: String) = buildString {
        for (c in word) {
            if (!Readings.isKana(c)) {
                repeat(random.nextInt(0, 4)) { append(KANA[random.nextInt(KANA.length)]) }
                continue
            }
            when (random.nextInt(8)) {
                0 -> Unit
                1 -> append(KANA[random.nextInt(KANA.length)])
                2 -> append(Readings.hiragana(c.toString()))
                else -> append(c)
            }
        }
    }

    /** [Furigana.over] as it was, trying each way and copying its parts. */
    private object Before {
        fun over(surface: String, kana: String): List<Ruby> {
            val runs = surface.fold(mutableListOf<String>()) { runs, c ->
                if (runs.isNotEmpty() && !Readings.isKana(c) && !Readings.isKana(runs.last().first())) runs[runs.lastIndex] += c else runs += c.toString()
                runs
            }
            val plan = Aligner(runs, kana).best(0, 0)
            return if (plan.kept < 0) listOf(Ruby(surface)) else plan.parts.fold(mutableListOf()) { parts, part ->
                if (part.reading == null && parts.lastOrNull()?.reading == null && parts.isNotEmpty()) parts[parts.lastIndex] = Ruby(parts.last().text + part.text) else parts += part
                parts
            }
        }

        private class Plan(val kept: Int, val parts: List<Ruby>)

        private val order = compareBy<Plan> { it.kept }.thenBy { -it.parts.count { part -> part.reading != null } }
        private val NONE = Plan(-1, emptyList())

        private class Aligner(private val runs: List<String>, private val kana: String) {
            private val sounds = kana.map(::sound)
            private val memo = HashMap<Pair<Int, Int>, Plan>()

            fun best(i: Int, at: Int): Plan = memo.getOrPut(i to at) {
                when {
                    i == runs.size -> if (at == kana.length) Plan(0, emptyList()) else NONE
                    Readings.isKana(runs[i].first()) -> written(i, at)
                    else -> kanji(i, at)
                }
            }

            private fun written(i: Int, at: Int): Plan {
                val c = runs[i].single()
                val found = spelledAt(c, at)?.let { then(i + 1, at + 1, listOf(it), 1) } ?: NONE
                val left = when {
                    runs.take(i).all { Readings.isKana(it.first()) } -> then(i + 1, at, listOf(Ruby(c.toString())), 0)
                    runs.drop(i).all { Readings.isKana(it.first()) } -> then(runs.size, at, listOf(Ruby(runs.drop(i).joinToString(""))), 0)
                    else -> NONE
                }
                return listOf(found, left, kanji(i, at)).maxWith(order)
            }

            private fun kanji(i: Int, at: Int): Plan {
                val plans = (i until runs.size).filter { j -> runs.subList(i, j + 1).any { !Readings.isKana(it.first()) } }.flatMap { j ->
                    (at + 1..kana.length).map { end ->
                        then(j + 1, end, listOf(Ruby(runs.subList(i, j + 1).joinToString(""), kana.substring(at, end))), 0)
                    }
                }
                return plans.maxWithOrNull(order) ?: NONE
            }

            private fun then(next: Int, at: Int, parts: List<Ruby>, kept: Int): Plan {
                val rest = best(next, at)
                return if (rest.kept < 0) NONE else Plan(rest.kept + kept, parts + rest.parts)
            }

            private fun spelledAt(c: Char, at: Int): Ruby? {
                val said = sounds.getOrNull(at) ?: return null
                val written = sound(c)
                val before = sounds.getOrNull(at - 1)
                return when {
                    written == said || stretched(written, before) == stretched(said, before) || "$said$written" in SAID_AS -> Ruby(c.toString())
                    "$written$said" in SAID_AS -> Ruby(c.toString(), kana.substring(at, at + 1))
                    else -> null
                }
            }
        }

        private fun sound(c: Char): Char = FOLDED[Readings.hiragana(c.toString())[0]] ?: Readings.hiragana(c.toString())[0]

        private fun stretched(c: Char, before: Char?): Char {
            val row = ROWS.entries.firstOrNull { before != null && before in it.value }?.key
            return if (c == 'ー' || (row != null && c in STRETCHES.getValue(row))) 'ー' else c
        }

        private val FOLDED = mapOf(
            'ぁ' to 'あ', 'ぃ' to 'い', 'ぅ' to 'う', 'ぇ' to 'え', 'ぉ' to 'お', 'っ' to 'つ', 'ゃ' to 'や', 'ゅ' to 'ゆ', 'ょ' to 'よ', 'ゎ' to 'わ',
            'を' to 'お', 'ぢ' to 'じ', 'づ' to 'ず'
        )
        private val SAID_AS = setOf("ゖか", "ゖが", "ゖこ", "ゕか", "ゕが", "ゕこ", "ゐい", "ゑえ", "はわ", "へえ", "ふう", "ひい", "ほお")
        private val ROWS = mapOf(
            'あ' to "あかがさざただなはばぱまやらわ",
            'い' to "いきぎしじちにひびぴみり",
            'う' to "うくぐすずつぬふぶぷむゆる",
            'え' to "えけげせぜてでねへべぺめれ",
            'お' to "おこごそぞとどのほぼぽもよろ"
        )
        private val STRETCHES = mapOf('あ' to "あ", 'い' to "い", 'う' to "う", 'え' to "えい", 'お' to "おう")
    }

    private companion object {
        const val KANJI = "山川木日月花空海夜星雨風光道心"

        /** Kana of every kind: small, said alike, old, katakana, ヶ and the long mark. */
        const val KANA = "あいうえおかがきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをんぁぃぅぇぉっゃゅょゎぢづゐゑゕゖアイウエオカキクシターヶ"
    }
}
