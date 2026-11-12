package com.astrovm.crosstune

/**
 * Where a run of spaces starts, or where the last match ended. A pattern that starts with spaces tries from
 * here only: from each space of a long run it would read the rest of the run again, and take time squared.
 * It finds what it found before, as a match from inside a run is also one from where the run starts.
 */
internal const val SPACES_START = """(?:(?<!\s)|\G)"""

/** A title past this many letters is no title, but a page or a text that went wrong, and isn't read for a song. */
internal const val LONGEST_TITLE = 500

/**
 * The tags in brackets in [text], brackets and all, [opens] and [closes] being the brackets, each of which closes any
 * other. Where every bracket closes in order, a tag holds the ones inside it, "(Acoustic Cover (Live [2019]))". Where
 * one doesn't, a tag runs from the first bracket that opens after the last tag to the next that closes, so "Song
 * (Lyrics()" has "(Lyrics()" and "Song (Official Video :( )" has "(Official Video :( )". Read in code, as a pattern can
 * only hold so many brackets in brackets.
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

/** Whether each bracket in [text] closes, in order. */
internal fun bracketsInOrder(text: String, opens: String, closes: String): Boolean {
    var depth = 0
    for (char in text) {
        if (char in opens) depth++ else if (char in closes && --depth < 0) return false
    }
    return depth == 0
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
