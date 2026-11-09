package com.astrovm.crosstune

/**
 * Where the reading of a Japanese word goes when the word has kana among its kanji: over the
 * kanji, and over a kana only when it isn't said as it's written.
 *
 * The word is cut into runs, kanji and kana in turn (飛 び 立 つ), and its reading is laid along
 * them: each run of kana has to be found in the reading, and what's left between two of them is
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
            if (runs.isNotEmpty() && Readings.isKana(runs.last().first()) == Readings.isKana(c)) runs[runs.lastIndex] += c else runs += c.toString()
            runs
        }
        val plan = Aligner(runs, kana).best(0, 0)
        // Nothing to read it over: no kanji, or no reading. The word stays as written.
        return if (plan.kept < 0) listOf(Ruby(surface)) else plan.parts
    }

    /** A way to lay the reading along the runs from some point on: [parts] of it, and how many kana of the word it finds in the reading. */
    private class Plan(val kept: Int, val parts: List<Ruby>)

    /** No way at all. */
    private val NONE = Plan(-1, emptyList())

    /**
     * The best way to lay [kana] along [runs]. The one that finds the most written kana in the
     * reading wins, and when they all can't be found, which is when the reading is not this word's
     * (飛び立つ read ゆく), it is the least wrong thing to say: only the kana at the start and at the
     * end of the word are left out of the reading, as nothing written there can be inside the
     * kanji's, and what's between the kanji, kana and all, gets the one reading together, as 今日 does
     * with its two kanji. That is a group, not a guess at where the reading splits.
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

        /** The run of kana at [i]: found in the reading, or, at the start or the end of the word, not in it. */
        private fun written(i: Int, at: Int): Plan {
            val run = runs[i]
            val found = spelledAt(run, at)?.let { then(i + 1, at + run.length, it, run.length) } ?: NONE
            val left = if (i == 0 || i == runs.lastIndex) then(i + 1, at, listOf(Ruby(run)), 0) else NONE
            return listOf(found, left).maxBy { it.kept }
        }

        /** The kanji at [i], on their own or with the kana and kanji up to a later run [j] of kanji, read as far as [kana] goes, [end]. */
        private fun kanji(i: Int, at: Int): Plan {
            val plans = (i until runs.size step 2).flatMap { j ->
                (at + 1..kana.length).map { end ->
                    then(j + 1, end, listOf(Ruby(runs.subList(i, j + 1).joinToString(""), kana.substring(at, end))), 0)
                }
            }
            return plans.maxByOrNull { it.kept } ?: NONE
        }

        /** [parts], and then the best of the rest of the runs from the [next]th, if it can be done. */
        private fun then(next: Int, at: Int, parts: List<Ruby>, kept: Int): Plan {
            val rest = best(next, at)
            return if (rest.kept < 0) NONE else Plan(rest.kept + kept, parts + rest.parts)
        }

        /**
         * [run] as found in the reading from [at], if it is: as written, with what each kana that
         * isn't said as written is said as over it on its own.
         */
        private fun spelledAt(run: String, at: Int): List<Ruby>? {
            if (at + run.length > kana.length) return null
            val parts = mutableListOf<Ruby>()
            for (k in run.indices) {
                val said = sounds[at + k]
                val written = sound(run[k])
                when {
                    written == said || stretched(written, sounds.getOrNull(at + k - 1)) == stretched(said, sounds.getOrNull(at + k - 1)) || "$said$written" in SAID_AS ->
                        if (parts.lastOrNull()?.reading == null && parts.isNotEmpty()) parts[parts.lastIndex] = Ruby(parts.last().text + run[k]) else parts += Ruby(run[k].toString())
                    "$written$said" in SAID_AS -> parts += Ruby(run[k].toString(), kana.substring(at + k, at + k + 1))
                    else -> return null
                }
            }
            return parts
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
