package com.astrovm.crosstune

/**
 * Where a run of spaces starts, or where the last match ended. A pattern that starts with spaces tries from
 * here only: from each space of a long run it would read the rest of the run again, and take time squared.
 * It finds what it found before, as a match from inside a run is also one from where the run starts.
 */
internal const val SPACES_START = """(?:(?<!\s)|\G)"""

/** A space as a pattern's `\s` reads it: a space, a tab, a line break, a vertical tab or a form feed. Not U+3000. */
internal fun isPatternSpace(char: Char) = char == ' ' || char in '\t'..'\r'

/** A letter that ends a line, which a pattern's `.` never reads. */
internal fun isLineBreak(char: Char) = char == '\n' || char == '\r' || char == '\u0085' || char == '\u2028' || char == '\u2029'

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
internal class Kept(private val text: String, val end: Int, val after: String) : CharSequence {
    override val length get() = end + after.length
    override fun get(index: Int) = if (index < end) text[index] else after[index - end]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = when {
        startIndex >= end -> after.substring(startIndex - end, endIndex - end)
        endIndex <= end -> text.substring(startIndex, endIndex)
        else -> text.substring(startIndex, end) + after.substring(0, endIndex - end)
    }
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
 * does ("\r\n" being one), or else at its end.
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
 * Whether a pattern that ignores case reads [char] as one of a to z: those, and the letters whose other case is one,
 * the Kelvin sign as "k", "ſ" as "s", "ı" and "İ" as "i".
 */
internal fun isLatinLetterIgnoringCase(char: Char) = char.lowercaseChar() in 'a'..'z' || char.uppercaseChar() in 'A'..'Z'

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
