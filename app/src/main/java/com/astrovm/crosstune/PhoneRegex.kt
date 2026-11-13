package com.astrovm.crosstune

import java.util.BitSet
import java.util.regex.Pattern

/**
 * [pattern] read as a phone reads it. On Android, [Regex] runs on ICU, which reads `\s`, `\w`, `\d`, `\b`, `.`, `$` and
 * case otherwise than the JVM that tests run on: U+3000 is a space, "２" a digit, and "İ" no "i". On a phone this is
 * [Regex] itself. Elsewhere it's the same pattern written out in what ICU reads, by the table in
 * `app/src/test/regextable/` that [PHONE_ENGINE] made, so a test reads a title as a phone does.
 */
internal fun phoneRegex(pattern: String, vararg options: RegexOption): Regex = phoneRegex(pattern, options.toSet(), ICU)

/** [pattern] as [Regex] reads it on ICU when [onIcu], or else written out for the JVM in what ICU reads. */
internal fun phoneRegex(pattern: String, options: Set<RegexOption>, onIcu: Boolean): Regex =
    if (onIcu) Regex(pattern, options) else Regex(IcuPattern(pattern, options).written)

/** Whether [Regex] runs on ICU here: only ICU reads U+3000 as a space. */
internal val ICU = Regex("""\s""").matches("\u3000")

/** Code points, from runs in hex as [PHONE_SPACE] has them: "9-d,20" is U+0009 to U+000D, and U+0020. */
internal class CodeRuns(text: String) {
    /** Each run's first and last code point, one run after another. */
    private val runs = text.split(',').filter(String::isNotEmpty).flatMap { run ->
        listOf(run.substringBefore('-').toInt(HEX), run.substringAfter('-').toInt(HEX))
    }.toIntArray()

    operator fun contains(code: Int): Boolean {
        var low = 0
        var high = runs.size / 2 - 1
        while (low <= high) {
            val middle = (low + high) / 2
            when {
                code < runs[2 * middle] -> high = middle - 1
                code > runs[2 * middle + 1] -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    fun bits() = BitSet().also { bits -> for (at in runs.indices step 2) bits.set(runs[at], runs[at + 1] + 1) }
}

/** How a pattern that ignores case reads letters on a phone: by ICU's case folds, from [PHONE_SIMPLE_FOLDS] and [PHONE_FULL_FOLDS]. */
internal object CaseFolds {
    private val simple = PHONE_SIMPLE_FOLDS.split(',').associate { it.substringBefore(':').toInt(HEX) to it.substringAfter(':').toInt(HEX) }
    private val full = PHONE_FULL_FOLDS.split(',').associate { fold ->
        fold.substringBefore(':').toInt(HEX) to fold.substringAfter(':').split(' ').map { it.toInt(HEX) }.toIntArray()
    }

    /** The letters each folds from, and itself. */
    private val folding by lazy { simple.entries.groupBy({ it.value }, { it.key }).mapValues { (fold, codes) -> codes + fold } }

    /** The letter [code] folds to by itself: "K" (the Kelvin sign) to "k". */
    fun simple(code: Int) = simple[code] ?: code

    /** What [code] folds to in a word, which can be more than one letter: "ß" to "ss". */
    fun full(code: Int): IntArray = full[code] ?: intArrayOf(simple(code))

    /** Whether [code] folds to more than one letter in a word. */
    fun foldsLonger(code: Int) = code in full

    /** Each letter that folds by itself to what [code] does: "k", "K" and "K" for "k". */
    fun sameCase(code: Int): List<Int> = folding[simple(code)] ?: listOf(code)

    /** The letters in a word that fold to [folds] from [at] on, more than one of them, and how many: "ﬃ" for "ffi". */
    fun longerAt(folds: IntArray, at: Int): List<Int> = full.filter { (_, fold) ->
        fold.size <= folds.size - at && fold.indices.all { folds[at + it] == fold[it] }
    }.keys.sorted()
}

/**
 * A pattern written out for the JVM in what ICU reads: each set, `\b`, `.`, `$` and each letter ignoring case as the
 * table has them. A construct it doesn't know throws, so none is read the JVM's way unseen.
 */
internal class IcuPattern(private val pattern: String, options: Set<RegexOption>) {
    private var at = 0
    val written: String

    init {
        val unread = options - setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
        require(unread.isEmpty()) { "ICU's $unread isn't written out" }
        val body = sequence(Flags(RegexOption.IGNORE_CASE in options, RegexOption.DOT_MATCHES_ALL in options, RegexOption.MULTILINE in options))
        require(at == pattern.length) { "A ) at $at that no ( opens in $pattern" }
        // Unicode's \w and \d, which the sets are written from.
        written = "(?U)$body"
    }

    private data class Flags(val ignoreCase: Boolean, val dotAll: Boolean, val lines: Boolean)

    /** What's read from here to the ) that closes the group it's in, or to the end. */
    private fun sequence(start: Flags): String {
        var flags = start
        val written = Written()
        while (at < pattern.length && pattern[at] != ')') flags = next(flags, written)
        return written.done()
    }

    /** The piece at [at] read into [written], and the flags for the rest of the group. */
    private fun next(flags: Flags, written: Written): Flags {
        when (pattern[at]) {
            '(' -> {
                val (text, set) = group(flags)
                written.atom(text)
                return set ?: flags
            }
            '[' -> written.atom(charClass(flags.ignoreCase))
            '\\' -> escape(flags.ignoreCase, written)
            '*', '+', '?', '{' -> written.quantified(quantifier())
            else -> {
                val plain = plain(flags)
                if (plain != null) {
                    written.atom(plain)
                    at++
                } else {
                    val code = pattern.codePointAt(at)
                    written.letter(code, flags.ignoreCase)
                    at += Character.charCount(code)
                }
            }
        }
        return flags
    }

    /** What `|`, `.`, `^` and `$` are written as, or null for a letter. */
    private fun plain(flags: Flags): String? = when (pattern[at]) {
        '|' -> "|"
        '.' -> if (flags.dotAll) "(?s:.)" else "[^${runs(LINE_BREAKS.bits())}]"
        '^' -> if (flags.lines) lineStart else "^"
        '$' -> if (flags.lines) anyLineEnd else lineEnd
        else -> null
    }

    /** A sequence as it's written out, with the letters read one after another kept as one word till a piece ends it. */
    private class Written {
        private val out = StringBuilder()

        /** The letters read one after another, which ICU reads as one word ignoring case: "ss" is "ß". */
        private val word = mutableListOf<Pair<Int, Boolean>>()

        fun letter(code: Int, ignoreCase: Boolean) {
            word += code to ignoreCase
        }

        fun atom(text: String) {
            flush()
            out.append(text)
        }

        /** A quantifier takes the last letter alone. */
        fun quantified(quantifier: String) {
            val last = word.removeLastOrNull()
            flush()
            if (last != null) out.append(letters(intArrayOf(last.first), last.second))
            out.append(quantifier)
        }

        fun done(): String {
            flush()
            return out.toString()
        }

        private fun flush() {
            var from = 0
            while (from < word.size) {
                val ignoreCase = word[from].second
                var until = from
                while (until < word.size && word[until].second == ignoreCase) until++
                out.append(letters(word.subList(from, until).map { it.first }.toIntArray(), ignoreCase))
                from = until
            }
            word.clear()
        }
    }

    /** A group from its (, and the flags it sets for the rest of the one it's in, if it's only them: "(?i)". */
    private fun group(flags: Flags): Pair<String, Flags?> {
        at++
        if (!pattern.startsWith("?", at)) return "(${closed(flags)}" to null
        LOOKS.firstOrNull { pattern.startsWith(it, at) }?.let { look ->
            at += look.length
            return "($look${closed(flags)}" to null
        }
        var set = flags
        var on = true
        at++
        while (at < pattern.length && pattern[at] != ')' && pattern[at] != ':') {
            when (pattern[at++]) {
                '-' -> on = false
                'i' -> set = set.copy(ignoreCase = on)
                's' -> set = set.copy(dotAll = on)
                'm' -> set = set.copy(lines = on)
                else -> throw IllegalArgumentException("The flag at ${at - 1} in $pattern isn't written out")
            }
        }
        require(at < pattern.length) { "A ( that doesn't close in $pattern" }
        return if (pattern[at++] == ')') "" to set else "(?:${closed(set)}" to null
    }

    /** What's in a group, to and with its ). */
    private fun closed(flags: Flags): String {
        val body = sequence(flags)
        require(at < pattern.length) { "A ( that doesn't close in $pattern" }
        at++
        return "$body)"
    }

    private fun quantifier(): String {
        val start = at
        if (pattern[at++] == '{') {
            while (at < pattern.length && pattern[at] != '}') at++
            require(at < pattern.length) { "A { that doesn't close in $pattern" }
            at++
        }
        if (at < pattern.length && pattern[at] in "?+") at++
        return pattern.substring(start, at)
    }

    /** What a \ starts, as a letter of [written]'s word or a piece of its own. */
    private fun escape(ignoreCase: Boolean, written: Written) {
        at++
        require(at < pattern.length) { "A \\ that ends $pattern" }
        val letter = pattern[at]
        val anchor = escapedAnchor(letter, ignoreCase)
        when {
            letter in SETS || letter in "pP" -> written.atom(set(ignoreCase).let { (set, negated) -> if (negated) "[^$set]" else set })
            anchor != null -> {
                written.atom(anchor)
                at++
            }
            letter == 'Q' -> {
                val end = pattern.indexOf("\\E", at).takeIf { it >= 0 } ?: pattern.length
                pattern.substring(at + 1, end).codePoints().forEach { written.letter(it, ignoreCase) }
                at = minOf(end + 2, pattern.length)
            }
            else -> written.letter(escaped(), ignoreCase)
        }
    }

    /** What `\b`, `\z`, `\A`, `\G`, `\Z` and a group read again are written as, or null for any other escape. */
    private fun escapedAnchor(letter: Char, ignoreCase: Boolean): String? = when (letter) {
        'b' -> boundary
        'z', 'A', 'G' -> "\\$letter"
        'Z' -> lineEnd
        in '1'..'9' -> {
            // ICU reads a group again ignoring case as a word, which isn't written out.
            require(!ignoreCase) { "A group read again ignoring case in $pattern isn't written out" }
            "\\$letter"
        }
        else -> null
    }

    /** The letter an escape from [at], past its \, stands for: "\t", "\x{3000}", "\u3000", "\-". */
    private fun escaped(): Int {
        val letter = pattern[at++]
        return when (letter) {
            't' -> '\t'.code
            'n' -> '\n'.code
            'r' -> '\r'.code
            'f' -> '\u000C'.code
            'u' -> pattern.substring(at, at + UNICODE_DIGITS).toInt(HEX).also { at += UNICODE_DIGITS }
            'x' -> {
                val end = pattern.indexOf('}', at)
                pattern.substring(at + 1, end).toInt(HEX).also { at = end + 1 }
            }
            else -> {
                require(!letter.isLetterOrDigit()) { "The escape \\$letter in $pattern isn't written out" }
                letter.code
            }
        }
    }

    /** A set an escape from [at] names, `\s` or `\p{L}`, written out, and whether it's the letters it doesn't hold. */
    private fun set(ignoreCase: Boolean): Pair<String, Boolean> {
        val letter = pattern[at++]
        val name = if (letter in "pP") {
            require(pattern.startsWith("{", at)) { "A \\$letter with no name in $pattern" }
            pattern.substring(at + 1, pattern.indexOf('}', at)).also { at += it.length + 2 }
        } else {
            letter.lowercase()
        }
        val set = NAMED[name] ?: throw IllegalArgumentException("The set $name in $pattern isn't written out")
        return set.written(ignoreCase) to (letter.isUpperCase())
    }

    /** A class from its [, written out with each letter it holds, those of each case too when [ignoreCase]. */
    private fun charClass(ignoreCase: Boolean): String {
        at++
        val negated = pattern.startsWith("^", at).also { if (it) at++ }
        val letters = BitSet()
        val sets = StringBuilder()
        var open = true
        while (open) open = classPart(ignoreCase, negated, letters, sets)
        at++
        val held = if (ignoreCase) closed(letters) else letters
        return "[${if (negated) "^" else ""}${if (held.isEmpty) "" else runs(held)}$sets]"
    }

    /** One part of a class read into [letters] or [sets]: a letter, a run, a set or a part both hold. False at its ]. */
    private fun classPart(ignoreCase: Boolean, negated: Boolean, letters: BitSet, sets: StringBuilder): Boolean {
        require(at < pattern.length) { "A [ that doesn't close in $pattern" }
        if (pattern.startsWith("&&[", at)) {
            sets.append("&&").append(bothHold(ignoreCase || negated))
            return true
        }
        require(pattern[at] != '[' && !pattern.startsWith("&&", at)) { "A class in a class in $pattern isn't written out" }
        when {
            pattern[at] == ']' -> return false
            pattern[at] == '\\' && pattern.getOrNull(at + 1)?.let { it in SETS || it in "pP" } == true -> {
                at++
                set(ignoreCase).let { (set, turned) -> sets.append(if (turned) "[^$set]" else set) }
            }
            else -> classRange(letters)
        }
        return true
    }

    /** A letter in a class, or a run of them, "a-z", read into [letters]. */
    private fun classRange(letters: BitSet) {
        val first = classLetter()
        val ranged = pattern.startsWith("-", at) && pattern.getOrNull(at + 1) != ']'
        if (ranged) at++
        letters.set(first, (if (ranged) classLetter() else first) + 1)
    }

    /** The class after a "&&", which both parts hold. */
    private fun bothHold(foldedOrTurned: Boolean): String {
        // ICU folds case and turns ^ after taking the part both hold, which the JVM doesn't.
        require(!foldedOrTurned) { "A part both hold, ignoring case or turned, in $pattern isn't written out" }
        at += 2
        return charClass(false).also { require(pattern.startsWith("]", at)) { "More after a part both hold in $pattern isn't written out" } }
    }

    /** A letter in a class, or an escaped one. */
    private fun classLetter(): Int = if (pattern[at] == '\\') {
        at++
        escaped()
    } else {
        pattern.codePointAt(at).also { at += Character.charCount(it) }
    }

    private companion object {
        const val SETS = "sSwWdD"
        const val UNICODE_DIGITS = 4
    }
}

/** How a group that isn't only flags starts, after its (: no group, ahead, behind, or kept. */
private val LOOKS = listOf("?:", "?=", "?!", "?>", "?<=", "?<!")

/** [codes] written as letters ICU reads one after another: ignoring case, each as each case of it, and runs as one word. */
private fun letters(codes: IntArray, ignoreCase: Boolean): String = when {
    !ignoreCase -> codes.joinToString("") { written(it) }
    codes.size == 1 && !CaseFolds.foldsLonger(codes[0]) -> runs(closed(BitSet().apply { set(codes[0]) }))
    else -> folded(codes.flatMap { CaseFolds.full(it).toList() }.toIntArray(), 0)
}

/**
 * A word ignoring case as ICU reads it: each letter in the text folds to a part of [folds], from [from] on, so "ﬃ" reads
 * as "ffi", and "ß" as "ss". A letter alone that folds to one is in a class with each case of it.
 */
private fun folded(folds: IntArray, from: Int): String {
    val out = StringBuilder()
    for (at in from until folds.size) {
        val one = runs(BitSet().apply { CaseFolds.sameCase(folds[at]).filterNot(CaseFolds::foldsLonger).forEach(::set) })
        val longer = CaseFolds.longerAt(folds, at)
        if (longer.isEmpty()) {
            out.append(one)
            continue
        }
        out.append("(?:").append(one).append(folded(folds, at + 1))
        for ((fold, codes) in longer.groupBy { CaseFolds.full(it).size }) out.append('|').append(runs(BitSet().apply { codes.forEach(::set) })).append(folded(folds, at + fold))
        return out.append(')').toString()
    }
    return out.toString()
}

/** [letters] with each case of each, as ICU closes a class ignoring case. */
private fun closed(letters: BitSet): BitSet {
    val all = letters.clone() as BitSet
    var code = letters.nextSetBit(0)
    while (code >= 0) {
        CaseFolds.sameCase(code).forEach(all::set)
        code = letters.nextSetBit(code + 1)
    }
    return all
}

/**
 * [codes] as a class: one letter alone, or else in brackets. The JVM reads a class's runs one by one, so many runs are
 * halved again and again, each half kept behind the span it covers: a letter in none is turned away in a few looks.
 */
private fun runs(codes: BitSet): String {
    if (codes.cardinality() == 1) return written(codes.nextSetBit(0))
    val runs = mutableListOf<IntRange>()
    var start = codes.nextSetBit(0)
    while (start >= 0) {
        val end = codes.nextClearBit(start) - 1
        runs += start..end
        start = codes.nextSetBit(end + 1)
    }
    return StringBuilder().also { spans(runs, it) }.toString()
}

private fun spans(runs: List<IntRange>, out: StringBuilder) {
    out.append('[')
    if (runs.size <= FEW_RUNS) {
        for (run in runs) {
            out.append(written(run.first))
            if (run.last > run.first) out.append('-').append(written(run.last))
        }
    } else {
        for (half in listOf(runs.subList(0, runs.size / 2), runs.subList(runs.size / 2, runs.size))) {
            out.append('[').append(written(half.first().first)).append('-').append(written(half.last().last)).append("&&")
            spans(half, out)
            out.append(']')
        }
    }
    out.append(']')
}

/** How many runs a class has before it's halved: few enough that reading them one by one is quick. */
private const val FEW_RUNS = 8

private fun written(code: Int) = "\\x{${code.toString(HEX)}}"

/**
 * A set as ICU reads it, [table] being its letters, written for the JVM as [jvm], its nearest set there, with the
 * letters it adds and those it lacks: whole, the JVM reads a long class letter by letter.
 */
private class PhoneSet(private val table: BitSet, private val jvm: String) {
    private val plain by lazy { written(table) }
    private val ignoringCase by lazy { written(closed(table)) }

    fun written(ignoreCase: Boolean) = if (ignoreCase) ignoringCase else plain

    private fun written(codes: BitSet): String {
        val there = jvmCodes(jvm)
        val more = (codes.clone() as BitSet).apply { andNot(there) }
        val fewer = (there.clone() as BitSet).apply { andNot(codes) }
        val beyond = "[$jvm${if (more.isEmpty) "" else runs(more)}${if (fewer.isEmpty) "" else "&&[^${runs(fewer)}]"}]"
        // ASCII first and alone, as most titles are: the JVM reads the runs after it only for other letters.
        val ascii = codes.get(0, ASCII)
        return "[${if (ascii.isEmpty) "" else runs(ascii)}[\\x{80}-\\x{10ffff}&&$beyond]]"
    }
}

/** What the JVM's [set] holds, by Unicode, read over every code point. */
private fun jvmCodes(set: String): BitSet = synchronized(JVM_SETS) { JVM_SETS.getOrPut(set) { jvmCodesNow(set) } }

private fun jvmCodesNow(set: String): BitSet {
    val codes = BitSet()
    val matcher = Pattern.compile("(?U)$set").matcher(EVERY_LETTER)
    while (matcher.find()) codes.set(EVERY_LETTER.codePointAt(matcher.start()))
    // Half a surrogate pair is read alone.
    for (half in Char.MIN_SURROGATE..Char.MAX_SURROGATE) if (matcher.reset(half.toString()).matches()) codes.set(half.code)
    return codes
}

private val JVM_SETS = mutableMapOf<String, BitSet>()

/** Each code point but the halves of surrogate pairs, in order. */
private val EVERY_LETTER: String by lazy {
    buildString { for (code in 0..Character.MAX_CODE_POINT) if (code !in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code) appendCodePoint(code) }
}

private val LINE_BREAKS = CodeRuns(PHONE_LINE_BREAK)

/** Where a `\b` reads a word's letters, and those it skips, which ICU looks past: marks and format letters. */
private val WORD_EDGE = CodeRuns(PHONE_WORD_EDGE).bits()
private val SKIPPED = BitSet().apply {
    set(0, Character.MAX_CODE_POINT + 1)
    andNot(WORD_EDGE)
    andNot(CodeRuns(PHONE_NOT_WORD_EDGE).bits())
}

private val NAMED = mapOf(
    "s" to PhoneSet(CodeRuns(PHONE_SPACE).bits(), """\s"""),
    "w" to PhoneSet(CodeRuns(PHONE_WORD).bits(), """\w"""),
    "d" to PhoneSet(CodeRuns(PHONE_DIGIT).bits(), """\d"""),
    "L" to PhoneSet(CodeRuns(PHONE_LETTER).bits(), """\p{L}"""),
    "M" to PhoneSet(CodeRuns(PHONE_MARK).bits(), """\p{M}"""),
    "N" to PhoneSet(CodeRuns(PHONE_NUMBER).bits(), """\p{N}"""),
    "Zs" to PhoneSet(CodeRuns(PHONE_SPACE_SEPARATOR).bits(), """\p{Zs}"""),
    "Mn" to PhoneSet(CodeRuns(PHONE_NONSPACING_MARK).bits(), """\p{Mn}"""),
    "Me" to PhoneSet(CodeRuns(PHONE_ENCLOSING_MARK).bits(), """\p{Me}"""),
    "Cf" to PhoneSet(CodeRuns(PHONE_FORMAT).bits(), """\p{Cf}"""),
    "sc=Han" to PhoneSet(CodeRuns(PHONE_HAN).bits(), """\p{sc=Han}"""),
    "sc=Hangul" to PhoneSet(CodeRuns(PHONE_HANGUL).bits(), """\p{sc=Hangul}"""),
    "sc=Hiragana" to PhoneSet(CodeRuns(PHONE_HIRAGANA).bits(), """\p{sc=Hiragana}"""),
    "sc=Katakana" to PhoneSet(CodeRuns(PHONE_KATAKANA).bits(), """\p{sc=Katakana}"""),
    "sc=Latin" to PhoneSet(CodeRuns(PHONE_LATIN).bits(), """\p{sc=Latin}"""),
)

/**
 * `\b` as ICU reads it: not before a letter it skips, and else where a word letter is on one side only, the one before
 * being the last it doesn't skip.
 */
private val boundary: String by lazy {
    // The JVM's sets nearest these, so each is written with few letters more or less: the JVM reads a class's runs one by one.
    val word = PhoneSet(WORD_EDGE, """[\w&&[^\p{Mn}\p{Me}\p{Cf}]]""").written(false)
    val skipped = PhoneSet(SKIPPED, """[\p{Mn}\p{Me}\p{Cf}]""").written(false)
    val afterWord = "(?:(?<=$word)|(?<=$skipped)(?<=$word$skipped*))"
    "(?:(?=$word)(?!$afterWord)|(?![$word$skipped])$afterWord)"
}

/** `^` reading lines, as ICU reads it: at the start, or after a line break but at the end, "\r\n" being one. */
private val lineStart: String by lazy {
    val breaks = LINE_BREAKS.bits().apply { clear('\r'.code) }
    """(?:^|(?:(?<=${runs(breaks)})|(?<=\r)(?!\n))(?!\z))"""
}

/** `$` reading lines, as ICU reads it: at the end, or before a line break, "\r\n" being one. */
private val anyLineEnd: String by lazy {
    val breaks = LINE_BREAKS.bits().apply { clear('\n'.code) }
    """(?:\z|(?=${runs(breaks)})|(?<!\r)(?=\n))"""
}

/**
 * `$` as ICU reads it: at the end, or before the line break that ends the text, "\r\n" being one, and a vertical tab
 * or a form feed being others.
 */
private val lineEnd: String by lazy {
    val breaks = LINE_BREAKS.bits()
    """(?:\z|(?=\r\n\z)|(?=${runs(breaks.apply { clear('\n'.code) })}\z)|(?<!\r)(?=\n\z))"""
}

private const val HEX = 16
private const val ASCII = 0x80
