package com.astrovm.crosstune

/**
 * Where the reading of a Japanese word goes when the word has kana among its kanji: over the
 * kanji, and over a kana only when it isn't said as it's written.
 *
 * The word is cut into kanji runs and individual kana (飛 び 立 つ), and its reading is laid along
 * them: the kana are found in the reading, and what's left between them is
 * the reading of the kanji there. A reading often spells those kana another way than the word
 * does, and they still count as found. Katakana for hiragana, small kana for big ones, ぢ, づ and
 * を, and a long vowel drawn out or spelled out (おう, おお, おー) are said as they're written, so
 * nothing goes over them. The old ゐ and ゑ, ヶ, ふ said う, ひ said い, ほ said お, and は and へ
 * said わ and え are not, so each gets what it's said as over it: い over ひ in 思ひ出.
 */
internal object Furigana {

    /** [surface] with its [kana] over the kanji only, split around the kana written among them. */
    fun over(surface: String, kana: String): List<Ruby> {
        val runs = surface.fold(mutableListOf<String>()) { runs, c ->
            if (runs.isNotEmpty() && !Readings.isKana(c) && !Readings.isKana(runs.last().first())) runs[runs.lastIndex] += c else runs += c.toString()
            runs
        }
        val plan = Aligner(runs, kana).best(0, 0)
        // Nothing to read it over: no kanji, or no reading. The word stays as written.
        return if (plan.kept < 0) listOf(Ruby(surface)) else plan.parts.fold(mutableListOf()) { parts, part ->
            if (part.reading == null && parts.lastOrNull()?.reading == null && parts.isNotEmpty()) parts[parts.lastIndex] = Ruby(parts.last().text + part.text) else parts += part
            parts
        }
    }

    /** A way to lay the reading along the runs from some point on: [parts] of it, and how many kana of the word it finds in the reading. */
    private class Plan(val kept: Int, val parts: List<Ruby>)

    private val order = compareBy<Plan> { it.kept }.thenBy { -it.parts.count { part -> part.reading != null } }

    /** No way at all. */
    private val NONE = Plan(-1, emptyList())

    /**
     * The best way to lay [kana] along [runs] finds the most written kana in the reading. If some
     * cannot be found, adjacent kanji and kana can share a reading. Among equally good matches,
     * fewer readings win: 飛び立つ read ゆく gets ゆく over 飛び立, not a guessed split.
     */
    private class Aligner(private val runs: List<String>, private val kana: String) {
        private val sounds = kana.map(::sound)
        private val memo = HashMap<Pair<Int, Int>, Plan>()

        /** The best plan for the runs from the [i]th, with what's left of the reading from [at]. */
        fun best(i: Int, at: Int): Plan = memo.getOrPut(i to at) {
            when {
                i == runs.size -> if (at == kana.length) Plan(0, emptyList()) else NONE
                Readings.isKana(runs[i].first()) -> written(i, at)
                else -> kanji(i, at)
            }
        }

        /** A kana found in the reading, left out at the edge, or grouped with the kanji after it. */
        private fun written(i: Int, at: Int): Plan {
            val c = runs[i].single()
            val found = spelledAt(c, at)?.let { then(i + 1, at + 1, listOf(it), 1) } ?: NONE
            // Left out at the end, the kana after it are left out too: if one of them were read as written, this one would be too.
            val left = when {
                runs.take(i).all { Readings.isKana(it.first()) } -> then(i + 1, at, listOf(Ruby(c.toString())), 0)
                runs.drop(i).all { Readings.isKana(it.first()) } -> then(runs.size, at, listOf(Ruby(runs.drop(i).joinToString(""))), 0)
                else -> NONE
            }
            return listOf(found, left, kanji(i, at)).maxWith(order)
        }

        /** A group containing kanji, read as far as [kana] goes. */
        private fun kanji(i: Int, at: Int): Plan {
            val plans = (i until runs.size).filter { j -> runs.subList(i, j + 1).any { !Readings.isKana(it.first()) } }.flatMap { j ->
                (at + 1..kana.length).map { end ->
                    then(j + 1, end, listOf(Ruby(runs.subList(i, j + 1).joinToString(""), kana.substring(at, end))), 0)
                }
            }
            return plans.maxWithOrNull(order) ?: NONE
        }

        /** [parts], and then the best of the rest of the runs from the [next]th, if it can be done. */
        private fun then(next: Int, at: Int, parts: List<Ruby>, kept: Int): Plan {
            val rest = best(next, at)
            return if (rest.kept < 0) NONE else Plan(rest.kept + kept, parts + rest.parts)
        }

        /**
         * [c] as found in the reading at [at]: as written, or with what it's said as over it when it
         * isn't said as written. Nothing when it isn't found there.
         */
        private fun spelledAt(c: Char, at: Int): Ruby? {
            // Past the reading's end, it isn't in it.
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

    /** [c] as kana are told apart here: in hiragana, with the small ones as their big ones and the ones said alike as one. */
    private fun sound(c: Char): Char = FOLDED[Readings.hiragana(c.toString())[0]] ?: Readings.hiragana(c.toString())[0]

    /** A long vowel is one sound, however it's spelled: the mark ー, or the vowel that follows [before], あ, い, う, え or お. */
    private fun stretched(c: Char, before: Char?): Char {
        val row = ROWS.entries.firstOrNull { before != null && before in it.value }?.key
        return if (c == 'ー' || (row != null && c in STRETCHES.getValue(row))) 'ー' else c
    }

    /** The small kana, and the ones said alike, as the one kana they're said as. */
    private val FOLDED = mapOf(
        'ぁ' to 'あ', 'ぃ' to 'い', 'ぅ' to 'う', 'ぇ' to 'え', 'ぉ' to 'お', 'っ' to 'つ', 'ゃ' to 'や', 'ゅ' to 'ゆ', 'ょ' to 'よ', 'ゎ' to 'わ',
        'を' to 'お', 'ぢ' to 'じ', 'づ' to 'ず'
    )

    /**
     * Kana written one way, first, and said another: ヶ is か, が or こ; the old ゐ and ゑ; は, へ, ふ,
     * ひ and ほ as they're said in a word. A reading that spells it the way it's written the other way
     * round, は for a written わ, still says it as written.
     */
    private val SAID_AS = setOf("ゖか", "ゖが", "ゖこ", "ゕか", "ゕが", "ゕこ", "ゐい", "ゑえ", "はわ", "へえ", "ふう", "ひい", "ほお")

    /** The kana of each vowel's column, by that vowel. */
    private val ROWS = mapOf(
        'あ' to "あかがさざただなはばぱまやらわ",
        'い' to "いきぎしじちにひびぴみり",
        'う' to "うくぐすずつぬふぶぷむゆる",
        'え' to "えけげせぜてでねへべぺめれ",
        'お' to "おこごそぞとどのほぼぽもよろ"
    )

    /** What draws out a vowel after a kana of each column, as おう and えい say おー and えー. */
    private val STRETCHES = mapOf('あ' to "あ", 'い' to "い", 'う' to "う", 'え' to "えい", 'お' to "おう")
}
