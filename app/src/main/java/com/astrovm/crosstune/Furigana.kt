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
        // No reading, or too long a word to count its kana: the word stays as written. So it does with no way to lay the reading.
        if (kana.isEmpty() || surface.length > MAX_LETTERS) return listOf(Ruby(surface))
        val laid = Aligner(runs, kana).parts() ?: return listOf(Ruby(surface))
        return laid.fold(mutableListOf()) { parts, part ->
            if (part.reading == null && parts.lastOrNull()?.reading == null && parts.isNotEmpty()) parts[parts.lastIndex] = Ruby(parts.last().text + part.text) else parts += part
            parts
        }
    }

    /**
     * [surface] spelled the modern way, if it has old kana in it: ゐ, ゑ, ひ, ふ and ほ written for い,
     * え, い, う and お, as 思ひ出 is 思い出. Each reading in [kana] is tried in turn, as one tokenizer
     * spells a long vowel or a kana another way than the next. Null when no kana is old, and for ヶ and
     * the particles は and へ, which are modern writing.
     */
    fun modern(surface: String, vararg kana: String): String? {
        if (surface.none { it in OLD }) return null
        for (reading in kana) {
            val parts = over(surface, reading)
            if (parts.any { it.reading != null && it.text in OLD_KANA }) {
                return parts.joinToString("") { if (it.reading != null && it.text in OLD_KANA) Readings.hiragana(it.reading) else it.text }
            }
        }
        return null
    }

    /**
     * [text] spelled today, by rule, with where each of its letters comes from in [text] ([origins], a contracted pair
     * giving the same origin to each letter it makes), the places in the spelling today where a ひ, ふ or へ was changed
     * ([plain]), and the places in [text] where a pair like けふ starts ([contracted]) and where the first change is ([first]).
     */
    class Today(val text: String, val origins: List<Int>, val plain: List<Int>, val contracted: List<Int>, val first: Int)

    /**
     * [text] spelled the modern way by rule, when it can only be old kana: ゐ and ゑ, which no modern word has; け, て and the
     * like with ふ, said きょう and ちょう; and ふ, ひ and へ right after a kanji, said う, い and え,
     * 云ふ, 思ひ出, 耐へ. In kana alone, ふ and ひ are only taken for old kana after い, いふ and いひ: modern words have them too, ふふ.
     * Null when nothing changes. The caller checks that the result is a word.
     */
    fun byRule(text: String): Today? {
        val out = StringBuilder()
        val origins = mutableListOf<Int>()
        val plain = mutableListOf<Int>()
        val pairs = mutableListOf<Int>()
        var first = -1
        var sure = false
        var at = 0
        while (at < text.length) {
            val change = changeAt(text, at)
            val said = change?.said ?: text[at].toString()
            if (change != null) {
                if (first < 0) first = at
                sure = sure || change.sure
                if (change.length > 1) pairs += at else if (text[at] in "ひふへ") plain += out.length
            }
            repeat(said.length) { origins += at }
            out.append(said)
            at += change?.length ?: 1
        }
        return if (sure && first >= 0) Today(out.toString(), origins, plain, pairs, first) else null
    }

    /** What the old kana at [at] in a text is said as, [length] letters of the text; [sure] when only old kana can be spelled so. */
    private class Change(val said: String, val length: Int, val sure: Boolean)

    private fun changeAt(text: String, at: Int): Change? {
        val c = text[at]
        val before = text.getOrNull(at - 1)
        val contracted = CONTRACTED.entries.firstOrNull { text.startsWith(it.key, at) }
        return when {
            contracted != null -> Change(contracted.value, contracted.key.length, true)
            c in "ゐゑ" -> Change(ENDINGS_SAID.getValue(c.toString()), 1, true)
            before != null && c in "ひふ" -> Change(ENDINGS_SAID.getValue(c.toString()), 1, Readings.isHan(before) || before == 'い')
            before != null && c == 'へ' && Readings.isHan(before) -> Change("え", 1, true)
            else -> null
        }
    }

    /** Whether [surface] is written in hiragana alone, with old kana in it: いふ, まゐる, てふてふ. */
    fun inOldKana(surface: String) = surface.any { it in OLD } && surface.all { it in 'ぁ'..'ゖ' }

    /**
     * A word in old kana alone, [surface], spelled the modern way by the first of [forms] that
     * spells the same word: how it's said (まゐり is said まいり) and the reading of its dictionary
     * form (いふ is いう, てふてふ ちょうちょう). A form counts only if it differs from [surface] only
     * where old kana is said another way, so a different word of the same sound, とこ for ところ, or
     * a long vowel marked ー, names nothing wrong. Null if none does.
     */
    fun modernKana(surface: String, forms: List<String>): String? {
        if (!inOldKana(surface)) return null
        return forms.map(Readings::hiragana).firstOrNull { spelled(surface, it) }
    }

    /**
     * How a word in old kana alone, [surface], is [said], with each long vowel mark where it writes an old kana as the vowel
     * that kana is said: きのふ said きのー is きのう. As it's said otherwise when the two differ in length.
     */
    fun saidAsSpelled(surface: String, said: String): String =
        if (said.length != surface.length) said else said.mapIndexed { at, c -> if (c == 'ー') LONG_SAID[surface[at]] ?: c else c }.joinToString("")

    /** The vowel an old kana is said as, drawn out after the kana before it: ふ in きのふ, ひ in いひ, ほ in とほい. */
    private val LONG_SAID = mapOf('ひ' to 'い', 'ふ' to 'う', 'ほ' to 'お')

    /** Whether [modern] is [surface] with only its old kana changed, to how they're said. */
    private fun spelled(surface: String, modern: String): Boolean {
        var at = 0
        var to = 0
        while (at < surface.length && to < modern.length) {
            val contracted = CONTRACTED.entries.firstOrNull { surface.startsWith(it.key, at) && modern.startsWith(it.value, to) }
            when {
                contracted != null -> {
                    at += contracted.key.length
                    to += contracted.value.length
                }
                surface[at] == modern[to] || "${surface[at]}${modern[to]}" in OLD_SAID_AS -> {
                    at++
                    to++
                }
                else -> return false
            }
        }
        return at == surface.length && to == modern.length && modern != surface
    }

    /** How ゐ, ゑ, ひ and ふ are said, as in 恋ひ, こい, and 住まゐ, すまい. */
    private val ENDINGS_SAID = mapOf("ゐ" to "い", "ゑ" to "え", "ひ" to "い", "ふ" to "う")

    /** Old kana said another way, as in [SAID_AS] but only the pairs a word in old kana is spelled with. */
    private val OLD_SAID_AS = setOf("ゐい", "ゑえ", "ひい", "ふう", "ほお")

    /** Old kana that join the one before it in one sound: けふ is きょう, てふ ちょう. */
    private val CONTRACTED = mapOf(
        "えふ" to "よう", "けふ" to "きょう", "せふ" to "しょう", "てふ" to "ちょう", "ねふ" to "にょう",
        "へふ" to "ひょう", "めふ" to "みょう", "れふ" to "りょう", "げふ" to "ぎょう", "でふ" to "じょう"
    )

    /** The kana only the old spelling writes for another sound. */
    private const val OLD = "ゐゑひふほヰヱ"
    private val OLD_KANA = OLD.map(Char::toString).toSet()

    /**
     * How good a way to lay the reading along the runs is, as one number to compare: more kana of the word found in the
     * reading first, then fewer readings laid over it. Each fits in 16 bits, see [MAX_LETTERS].
     */
    private fun score(kept: Int, readings: Int) = (kept shl 16) or (0xFFFF - readings)

    /** No way at all. */
    private const val NONE = -1

    /** The runs and the reading both used up. */
    private val DONE = score(0, 0)

    /** The longest word laid out letter by letter; a longer one stays as written. */
    const val MAX_LETTERS = 0x7FFF

    /** How a kana is found at a place in the reading: not there, said as written, or said as something else. */
    private enum class Said { NOT, AS_WRITTEN, OTHERWISE }

    /**
     * The best way to lay [kana] along [runs] finds the most written kana in the reading. If some
     * cannot be found, adjacent kanji and kana can share a reading. Among equally good matches,
     * fewer readings win: 飛び立つ read ゆく gets ゆく over 飛び立, not a guessed split. Of ways as good, the first
     * tried wins: a kana found before one left out before a kanji group, and of groups, the shortest, read the shortest.
     *
     * Worked out from the last run back, once for each run and place in the reading. A kanji group from a run may end at
     * any later run and any later place in the reading; the best of those is kept for each run and place as it's worked
     * out, so a group is picked in one step, not by trying them all. A long word, a reading in brackets over a quoted
     * phrase, takes as long as its runs times its kana. Each run and place keeps two numbers, how good its best way is and
     * where that way's first part ends, and parts are made only for the way picked, so it takes as much memory too.
     */
    private class Aligner(private val runs: List<String>, private val kana: String) {
        private val sounds = kana.map(::sound)
        private val size = kana.length

        /** What draws out the vowel before each place in the reading, as in [stretched]; nothing at the start. */
        private val drawsOut = Array(size + 1) { at -> sounds.getOrNull(at - 1)?.let(::drawsOutAfter).orEmpty() }

        /** How each kana run sounds, as in [sound], and what it's said as when the reading spells it the other way, or another, as in [SAID_AS]. */
        private val runSounds = runs.map { sound(it.first()) }
        private val spelledBack = runSounds.map { sound -> SAID_AS.filter { it[1] == sound }.map { it[0] }.joinToString("") }
        private val saidOtherwise = runSounds.map { sound -> SAID_AS.filter { it[0] == sound }.map { it[1] }.joinToString("") }

        /** How many places a run has in the tables: one for each place in the reading, and its end. */
        private val width = size + 1
        private val isKana = runs.map { Readings.isKana(it.first()) }

        /** Where each run starts in the word, and where the last one ends. */
        private val starts = runs.runningFold(0) { at, run -> at + run.length }
        private val word = runs.joinToString("")

        /** Whether all runs before the [i]th are kana. */
        private val kanaBefore = isKana.runningFold(true) { all, it -> all && it }

        /** The first run from the [i]th on with kanji in it; [runs]' size when none has. */
        private val nextKanji = IntArray(runs.size + 1) { runs.size }.also { next -> for (i in runs.indices.reversed()) next[i] = if (isKana[i]) next[i + 1] else i }

        /** How good the best way is from each run and place in the reading on, at the run times [width] plus the place. */
        private val best = IntArray((runs.size + 1) * width) { NONE }.also { it[runs.size * width + size] = DONE }

        /** Where that way's first part ends, the same way: the run after it times [width], plus the place in the reading after it. */
        private val next = IntArray(runs.size * width)

        /** The best of the ways tried for a run and place so far, and where its first part ends. */
        private var found = NONE
        private var foundNext = 0

        /** The parts of the best way to lay the reading; null when there's none. */
        fun parts(): List<Ruby>? {
            // A kanji group ending at a run from the next one on, and at a place from each on: where the best of them ends,
            // as the run it ends at and the place in the reading. Kept for the next run, and for the first with kanji after it.
            // Three rows each, used in turn: one for this run, one for the next and one for the first with kanji after it.
            // Its reading ends after the kanji's starts, so a group whose reading ends from a place on is kept one before it.
            val runRows = Array(3) { IntArray(size) }
            val endRows = Array(3) { IntArray(size) }
            var later = 0
            var kanjiRow = 0
            for (i in runs.indices.reversed()) {
                val row = (0..2).first { it != later && it != kanjiRow }
                groups(i, runRows[later], endRows[later], runRows[row], endRows[row])
                if (!isKana[i]) kanjiRow = row
                for (at in 0..size) {
                    found = NONE
                    if (isKana[i]) written(i, at)
                    if (at < size) kanji(i, runRows[kanjiRow][at], endRows[kanjiRow][at])
                    best[i * width + at] = found
                    next[i * width + at] = foundNext
                }
                later = row
            }
            if (best[0] == NONE) return null
            return buildList {
                var i = 0
                var at = 0
                while (i < runs.size) {
                    val end = next[i * width + at]
                    add(part(i, at, end / width, end % width))
                    i = end / width
                    at = end % width
                }
            }
        }

        /**
         * The best ends of a kanji group ending at the [j]th run or later, for each place its reading may end from, as tried
         * in order, into [groupRun] and [groupEnd] one before that place, from [laterRun] and [laterEnd], the same for the run
         * after it, if any.
         * Of ends as good, the first tried: the group ending soonest, its reading ending soonest.
         */
        private fun groups(j: Int, laterRun: IntArray, laterEnd: IntArray, groupRun: IntArray, groupEnd: IntArray) {
            val after = (j + 1) * width
            var end = size
            for (at in size downTo 1) {
                // This run, the reading ending here or, when that's better, later.
                if (best[after + at] >= best[after + end]) end = at
                val laterBetter = j != runs.lastIndex && best[after + end] < best[(laterRun[at - 1] + 1) * width + laterEnd[at - 1]]
                groupRun[at - 1] = if (laterBetter) laterRun[at - 1] else j
                groupEnd[at - 1] = if (laterBetter) laterEnd[at - 1] else end
            }
        }

        /** A kana found in the reading, or left out at the edge. */
        private fun written(i: Int, at: Int) {
            val said = said(i, at)
            if (said != Said.NOT) offer(i + 1, at + 1, 1, said == Said.OTHERWISE)
            // Left out at the end, the kana after it are left out too: if one of them were read as written, this one would be too.
            if (kanaBefore[i]) offer(i + 1, at, 0, false) else if (nextKanji[i] == runs.size) offer(runs.size, at, 0, false)
        }

        /** A group containing kanji, from the [i]th run to the [last]th, read from where it starts as far as [end] in the reading. */
        private fun kanji(i: Int, last: Int, end: Int) {
            if (nextKanji[i] < runs.size) offer(last + 1, end, 0, true)
        }

        /** A first part ending before the [run]th run and at [at] in the reading, finding [kept] kana, with a reading over it when [read]. */
        private fun offer(run: Int, at: Int, kept: Int, read: Boolean) {
            val rest = best[run * width + at]
            if (rest == NONE) return
            val score = rest + (kept shl 16) - (if (read) 1 else 0)
            // The first found wins a tie.
            if (score > found) {
                found = score
                foundNext = run * width + at
            }
        }

        /** The part from the [i]th run and [at] in the reading to the [run]th and [end]. */
        private fun part(i: Int, at: Int, run: Int, end: Int): Ruby {
            val text = word.substring(starts[i], starts[run])
            return when {
                end == at -> Ruby(text)
                isKana[i] && run == i + 1 && said(i, at) == Said.AS_WRITTEN -> Ruby(text)
                else -> Ruby(text, kana.substring(at, end))
            }
        }

        /**
         * How the kana of the [i]th run is found in the reading at [at]: as written, as something it isn't written as, or not at
         * all. Asked once for each run and place, so it makes nothing new.
         */
        private fun said(i: Int, at: Int): Said {
            // Past the reading's end, it isn't in it.
            if (at == size) return Said.NOT
            val heard = sounds[at]
            val written = runSounds[i]
            return when {
                written == heard || stretched(written, drawsOut[at]) == stretched(heard, drawsOut[at]) || heard in spelledBack[i] -> Said.AS_WRITTEN
                heard in saidOtherwise[i] -> Said.OTHERWISE
                else -> Said.NOT
            }
        }
    }

    /** [c] as kana are told apart here: in hiragana, with the small ones as their big ones and the ones said alike as one. */
    private fun sound(c: Char): Char = FOLDED[Readings.hiragana(c.toString())[0]] ?: Readings.hiragana(c.toString())[0]

    /** A long vowel is one sound, however it's spelled: the mark ー, or one of the vowels that [drawOut] the kana before. */
    private fun stretched(c: Char, drawOut: String): Char = if (c == 'ー' || c in drawOut) 'ー' else c

    /** The vowels that draw out the one [before] ends in, あ, い, う, え or お; nothing after a kana with no vowel. */
    private fun drawsOutAfter(before: Char): String = ROWS.entries.firstOrNull { before in it.value }?.let { STRETCHES.getValue(it.key) }.orEmpty()

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
