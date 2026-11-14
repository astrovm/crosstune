package com.astrovm.crosstune

/**
 * Where a run of spaces starts, or where the last match ended. A pattern that starts with spaces tries from
 * here only: from each space of a long run it would read the rest of the run again, and take time squared.
 * It finds what it found before, as a match from inside a run is also one from where the run starts.
 */
internal const val SPACES_START = """(?:(?<!\s)|\G)"""

/**
 * A space as a pattern's `\s` reads it on a phone, by [PHONE_SPACE]: a space, a tab, a line break, a vertical tab, a form
 * feed, U+3000, a no-break space and Unicode's others. All are in the basic plane.
 */
internal fun isPatternSpace(char: Char) = char.code in PATTERN_SPACES
private val PATTERN_SPACES = CodeRuns(PHONE_SPACE)

/** Whether the letter at [at] in [text] is between spaces, as a dash in `\s[-–]\s` is: false for -1, for none. */
internal fun isBetweenSpaces(text: String, at: Int) = text.getOrNull(at - 1)?.let(::isPatternSpace) == true && text.getOrNull(at + 1)?.let(::isPatternSpace) == true

/** A letter that ends a line on a phone, by [PHONE_LINE_BREAK], which a pattern's `.` never reads: a vertical tab and a form feed too. */
internal fun isLineBreak(char: Char) = char.code in LINE_BREAKS
private val LINE_BREAKS = CodeRuns(PHONE_LINE_BREAK)

/**
 * The first match of [regex] in [text] from [start] on, by its range in [text], looked for in [text] from a little before
 * [start] only: on a phone each look copies what it looks in first, and a look at the whole of a long title each pass
 * of a loop takes time squared. A little is what a look behind at [start] reads before it, as `(?<!\s)` and `\b` do:
 * the marks and format letters a `\b` looks past, then the letter before them.
 */
internal fun findNear(regex: Regex, text: CharSequence, start: Int): IntRange? {
    var from = start
    while (from > 0 && isLookedPast(Character.codePointBefore(text, from))) from = Character.offsetByCodePoints(text, from, -1)
    if (from > 0) from = Character.offsetByCodePoints(text, from, -1)
    return regex.find(text.subSequence(from, text.length), start - from)?.range?.let { it.first + from..it.last + from }
}

/**
 * Each match of this in [input], from [start] on, as [Regex.findAll] finds them, by one look along it. [Regex.findAll],
 * and [Regex.replace] with a function, start a new look for each match, and on a phone each new look copies the whole
 * input first: time squared on a long page or title with many matches. After a match with no letters the look goes
 * on from the next letter, a letter past U+FFFF whole on a phone. From the start, it looks as [Regex.split] does, each
 * time from where the last match ended, so a pattern with `\G` reads here as there: on a phone a look from [start]
 * reads `\G` otherwise.
 */
internal fun Regex.matchesIn(input: CharSequence, start: Int = 0): Sequence<MatchResult> = sequence {
    val matcher = toPattern().matcher(input)
    var found = if (start == 0) matcher.find() else matcher.find(start)
    while (found) {
        yield(FoundMatch(this@matchesIn, input, IntArray(2 * matcher.groupCount() + 2) { if (it % 2 == 0) matcher.start(it / 2) else matcher.end(it / 2) }))
        found = matcher.find()
    }
}

/**
 * [input] with each match of this as [transform] gives it, as [Regex.replace] puts them, by one look along it: see
 * [matchesIn]. As there, a match with no letters at the end, after one that ends there, is left as it is.
 */
internal fun Regex.replaceEach(input: CharSequence, transform: (MatchResult) -> CharSequence): String {
    val kept = StringBuilder()
    var from = 0
    for (match in matchesIn(input)) {
        kept.append(input, from, match.range.first).append(transform(match))
        from = match.range.last + 1
        if (from >= input.length) break
    }
    return kept.append(input, from, input.length).toString()
}

/** A match that [matchesIn] found: where it and each group start and end in [input], -1 for a group with none. */
private class FoundMatch(private val regex: Regex, private val input: CharSequence, private val bounds: IntArray) : MatchResult {
    override val range get() = bounds[0] until bounds[1]
    override val value get() = input.substring(bounds[0], bounds[1])
    override val groups: MatchGroupCollection = Groups()
    override val groupValues: List<String> get() = List(groups.size) { groups[it]?.value.orEmpty() }

    /** The next match, by a look of its own, as [Regex.find]'s. */
    override fun next(): MatchResult? {
        val next = if (range.isEmpty()) bounds[1] + 1 else bounds[1]
        return if (next > input.length) null else regex.find(input, next)
    }

    private inner class Groups : AbstractCollection<MatchGroup?>(), MatchGroupCollection {
        override val size get() = bounds.size / 2
        override fun iterator() = List(size, ::get).iterator()
        override fun get(index: Int): MatchGroup? {
            val start = bounds[2 * index]
            return if (start < 0) null else MatchGroup(input.substring(start, bounds[2 * index + 1]), start until bounds[2 * index + 1])
        }
    }
}

/** Whether a `\b` looks past [code] to the letter before it on a phone, by [PHONE_WORD_EDGE] and [PHONE_NOT_WORD_EDGE]: a mark or a format letter. */
internal fun isLookedPast(code: Int) = code !in WORD_EDGES && code !in NOT_WORD_EDGES
private val WORD_EDGES by lazy { CodeRuns(PHONE_WORD_EDGE) }
private val NOT_WORD_EDGES by lazy { CodeRuns(PHONE_NOT_WORD_EDGE) }

/** A digit as a pattern's `\d` reads it on a phone, by [PHONE_DIGIT]: any of Unicode's, "２" and "٢" too. */
internal fun isPatternDigit(code: Int) = code in DIGITS
private val DIGITS = CodeRuns(PHONE_DIGIT)

/**
 * Whether a pattern that ignores case reads [char], in a word, as [letter], which is folded already: "F" as "f", the
 * Kelvin sign as "k", but "ﬀ" as "ff" and so not as "f" alone.
 */
internal fun readsAs(char: Char, letter: Char) = !CaseFolds.foldsLonger(char.code) && CaseFolds.simple(char.code) == letter.code

/** Where the run of [isPatternSpace] spaces that ends at [at] starts, [from] at the earliest. */
internal fun spacesStart(text: CharSequence, at: Int, from: Int = 0): Int {
    var start = at
    while (start > from && isPatternSpace(text[start - 1])) start--
    return start
}

/** Where the run of [isPatternSpace] spaces from [at] ends. */
internal fun spacesEnd(text: CharSequence, at: Int): Int {
    var end = at
    while (end < text.length && isPatternSpace(text[end])) end++
    return end
}

/**
 * A text cut short: [text] up to [end], then [after], what followed a tag cut off its end, as the line break that ended
 * it. Read as one text without copying it, so a loop that cuts tags off a long title's end one at a time reads near
 * that end only.
 */
internal class Kept(private val text: String, val end: Int, private val after: String) : CharSequence {
    override val length get() = end + after.length
    override fun get(index: Int) = if (index < end) text[index] else after[index - end]
    // What's asked of the title, then what's asked of what follows it: either may be none.
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        text.substring(minOf(startIndex, end), minOf(endIndex, end)) + after.substring(maxOf(startIndex - end, 0), maxOf(endIndex - end, 0))
    override fun toString() = text.substring(0, end) + after

    /** Without [range], which is near the end. */
    fun without(range: IntRange): Kept = minOf(range.first, end).let { cut -> Kept(text, cut, "${subSequence(cut, range.first)}${subSequence(range.last + 1, length)}") }

    /** Without the spaces it ends with, as [String.trim] takes them, and those it starts with once all but [after] is cut. */
    fun trimmed(): Kept {
        val rest = after.trimEnd()
        var cut = end
        if (rest.isEmpty()) while (cut > 0 && text[cut - 1].isWhitespace()) cut--
        return Kept(text, cut, if (cut == 0) rest.trimStart() else rest)
    }
}

/**
 * Where a pattern's `$` first matches in [text], from [from] on: before the one line break that ends it, if one
 * does ("\r\n" being one, and [isLineBreak] the others), or else at its end.
 */
internal fun lineEnd(text: CharSequence, from: Int = 0): Int = (text.length - finalLineBreak(text)).takeIf { it >= from } ?: text.length

/** How long the line break that ends [text] is: "\r\n" is one, of two chars. */
private fun finalLineBreak(text: CharSequence): Int = when {
    text.endsWith("\r\n") -> 2
    text.isNotEmpty() && isLineBreak(text.last()) -> 1
    else -> 0
}

/**
 * The tags in brackets in [text], brackets and all, [opens] and [closes] being the brackets, in pairs. Where every
 * bracket closes in order, each with its own kind, a tag holds the ones inside it, "(Acoustic Cover (Live [2019]))".
 * Where one doesn't, each closes any other, as main read them: a tag runs from the first bracket that opens after the
 * last tag to the next that closes, so "Song (Lyrics()" has "(Lyrics()", "Song (Official Video :( )" has "(Official
 * Video :( )" and "Song [Video :(] (feat. X) :)" has "[Video :(]". Read in code, as a pattern can only hold so many
 * brackets in brackets.
 */
internal fun bracketTags(text: String, opens: String, closes: String): List<IntRange> {
    val inOrder = bracketsInOrder(text, opens, closes)
    val tags = mutableListOf<IntRange>()
    var start = -1
    var depth = 0
    text.forEachIndexed { at, char ->
        if (char in opens) {
            if (start < 0) start = at
            if (inOrder) depth++
        } else if (char in closes && start >= 0) {
            if (inOrder) depth--
            if (depth == 0) {
                tags += start..at
                start = -1
            }
        }
    }
    return tags
}

/**
 * Whether a pattern that ignores case reads [char] as one of a to z on a phone, by [PHONE_LATIN_IGNORING_CASE]: those,
 * "ſ" as "s" and the Kelvin sign as "k". Not "ı" or "İ", which ICU folds to no "i".
 */
internal fun isLatinLetterIgnoringCase(char: Char) = char.code in LATIN_IGNORING_CASE
private val LATIN_IGNORING_CASE = CodeRuns(PHONE_LATIN_IGNORING_CASE)

/** Whether each bracket in [text] closes, in order, with the bracket of its own kind: [closes] pairs with [opens]. */
internal fun bracketsInOrder(text: String, opens: String, closes: String): Boolean {
    val open = StringBuilder()
    for (char in text) {
        val kind = opens.indexOf(char)
        if (kind >= 0) {
            open.append(closes[kind])
        } else if (char in closes) {
            if (open.isEmpty() || open.last() != char) return false
            open.setLength(open.length - 1)
        }
    }
    return open.isEmpty()
}

/** [text] with each of its [bracketTags] put as [by]. */
internal fun replacingBracketTags(text: String, opens: String, closes: String, by: String): String {
    val kept = StringBuilder()
    var from = 0
    for (tag in bracketTags(text, opens, closes)) {
        kept.append(text, from, tag.first).append(by)
        from = tag.last + 1
    }
    return kept.append(text, from, text.length).toString()
}
