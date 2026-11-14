package com.astrovm.crosstune

/**
 * A title that guests, marks and tags are cut out of, pass after pass, as [SongNames.withoutEditionTag] cuts them,
 * without copying what's left on each pass. A place is where a letter is in the whole title; the cut ones are skipped.
 * Each cut has a number, so the title as it was before a pass is read back. And [cutEach], given the cuts made since it
 * last looked, looks again only near them: elsewhere it would find what it found then, which was nothing, as what it
 * found then was cut. So "(feat. a(feat. a(feat. a)))", cut from the inside one guest a pass, takes time in step with
 * its length, not squared.
 */
internal class CutTitle(private val title: String) {
    /** The place after the last letter. */
    val end = title.length

    /** Which cut took each letter, or [KEPT]. */
    private val cutBy = IntArray(end) { KEPT }
    private val cutFirst = mutableListOf<Int>()
    private val cutLast = mutableListOf<Int>()

    /** Whether the first bracket a cut took closes, as "- Remastered 2011)" does. */
    private val cutCloser = mutableListOf<Boolean>()

    /** What skips the cut letters, to each letter, each one that isn't a space, and each bracket, either way: each made once it's needed. */
    private val tables = arrayOfNulls<Skips>(TABLES)
    private var shown = title.count { !it.isWhitespace() }

    /** Where [cutEach] goes on from after the cut it just made, and the last letter that cut took. */
    private var landing = -1
    private var landedAfter = -1

    /** How many times a table was looked in: the steps reading it took, so a test can tell they're in step with its length. */
    var steps = 0L
        private set

    /** How many cuts have been made. */
    val cuts get() = cutFirst.size

    operator fun get(place: Int) = title[place]

    /** The first letter from [place] on, or [end]. */
    fun next(place: Int) = table(LETTERS).from(place)

    /** The last letter up to [place], or -1. */
    fun previous(place: Int) = table(LETTERS_BEFORE).from(place)

    /** Where the run of spaces from [place] ends, as [spacesEnd] says. */
    fun spacesEnd(place: Int) = table(WORDS).from(place)

    /** Where the run of spaces that ends at [place] starts, [from] at the earliest, as [spacesStart] says. */
    fun spacesStart(place: Int, from: Int = next(0)): Int {
        val word = wordBefore(place - 1)
        return if (word < from) from else next(word + 1)
    }

    /** The last letter up to [place] that isn't a space, or -1. */
    private fun wordBefore(place: Int) = table(WORDS_BEFORE).from(place)

    /** The first bracket from [place] on, or [end]. */
    fun nextBracket(place: Int) = table(BRACKETS_AFTER).from(place)

    private fun table(kind: Int): Skips {
        steps++
        return tables[kind] ?: Skips(IntArray(end + 1) { skip(kind, it) }, backward = kind >= BACKWARD).also { tables[kind] = it }
    }

    /** Where table [kind] leads from [index] at first: to it, if it stops at its letter, or else to the next. */
    private fun skip(kind: Int, index: Int): Int {
        if (index == end) return index
        val place = if (kind >= BACKWARD) end - 1 - index else index
        val stops = cutBy[place] == KEPT && when (kind % BACKWARD) {
            LETTERS -> true
            WORDS -> !isPatternSpace(title[place])
            else -> title[place] in BRACKETS
        }
        return if (stops) index else index + 1
    }

    /** The place [count] letters on from [place]. */
    fun after(place: Int, count: Int): Int {
        var at = next(place)
        repeat(count) { at = next(at + 1) }
        return at
    }

    /**
     * Up to [count] letters from [place] on, and the other half of a letter past U+FFFF that the last is the first half
     * of: a pattern would read a half alone as a letter of its own, which "\b" doesn't read as a word's.
     */
    fun letters(place: Int, count: Int): String {
        val letters = StringBuilder()
        var at = next(place)
        repeat(count) {
            title.getOrNull(at)?.let(letters::append)
            at = next(at + 1)
        }
        if (letters.lastOrNull()?.isHighSurrogate() == true && title.getOrNull(at)?.isLowSurrogate() == true) letters.append(title[at])
        return letters.toString()
    }

    /** The letters from [place] on, and the place of each. */
    fun lettersFrom(place: Int): Pair<String, IntArray> {
        val letters = StringBuilder()
        val places = mutableListOf<Int>()
        var at = next(place)
        while (at < end) {
            letters.append(title[at])
            places += at
            at = next(at + 1)
        }
        return letters.toString() to places.toIntArray()
    }

    /** The letters from [range]'s first place to its last. */
    fun letters(range: IntRange): String = buildString {
        var at = next(range.first)
        while (at <= range.last) {
            append(title[at])
            at = next(at + 1)
        }
    }

    /**
     * Where a pattern's `$` first matches, as [lineEnd] says: before the line break that ends what's left, or at the end.
     * It's never before a letter that's left, but the line break: from a letter that's no space on, it's where `$` matches.
     */
    fun lineEnd(): Int {
        val last = previous(end - 1)
        val beforeLast = previous(last - 1)
        return when {
            title.getOrNull(last) == '\n' && title.getOrNull(beforeLast) == '\r' -> beforeLast
            title.getOrNull(last)?.let(::isLineBreak) == true -> last
            else -> end
        }
    }

    /** Whether all that's left is spaces, as [String.isBlank] says. */
    fun isBlank() = shown == 0

    /** The letter before [place], as the pass that's looking read it: one the pass just cut, or -1 for none. */
    fun letterBefore(place: Int) = if (place == landing) landedAfter else previous(place - 1)

    /** Whether a cut since cut number [since] took a letter from [place] on. */
    fun cutSince(since: Int, place: Int) = (since until cuts).any { cutLast[it] >= place }

    /** Cuts the letters from [from] up to [to], of which there's at least one. */
    fun cut(from: Int, to: Int) {
        var at = next(from)
        // A cut of none would leave [cutEach] reading from the same place, cutting none, without end.
        check(at < to) { "No letters to cut from $from to $to" }
        cutFirst += at
        var bracket: Char? = null
        var last = at
        while (at < to) {
            val letter = title[at]
            if (bracket == null && letter in BRACKETS) bracket = letter
            if (!letter.isWhitespace()) shown--
            cutBy[at] = cuts - 1
            for (table in tables) table?.drop(at)
            last = at
            at = next(at + 1)
        }
        cutLast += last
        cutCloser += bracket != null && bracket in CLOSES
        landing = at
        landedAfter = last
    }

    /**
     * Cuts each match that [endAt] finds, from the left, as a pattern's replace takes them. [endAt] is given a place
     * and where the spaces from there end, and gives where a match from that place ends, or null. From each place in a
     * run of spaces a match reads the same, but from the first one after a digit.
     *
     * Given [since], the number of cuts there were when it last looked, it looks only where the cuts made since could
     * change what [endAt] finds, which holds for what guests and marks are: [endAt] reads at most [NEAR] letters on
     * from a place, spaces aside, and then the next bracket only from one that opens, "(feat. a", or after a guest
     * written out, " ft. a". That one is no match only when the bracket closes; a cut that took a bracket that closes
     * and none before it that opens is a tag's, "- Remastered 2011)".
     */
    fun cutEach(since: Int, endAt: (at: Int, word: Int) -> Int?) {
        landing = -1
        val windows = if (since == ALL) listOf(next(0) to end) else windowsSince(since)
        var at = -1
        var until = -1
        var window = 0
        var looking = false
        while (true) {
            if (!looking) {
                // On from where the next window starts, or from here if it started before: here is where the last
                // stopped, past where it could change what's found, so it's read again only as far as the next goes.
                if (window == windows.size) break
                at = maxOf(at, windows[window].first)
                until = windows[window++].second
                looking = true
            }
            if (at == end) break
            val word = spacesEnd(at)
            val matchEnd = endAt(at, word)
            if (matchEnd != null) {
                cut(at, matchEnd)
                at = landing
            } else {
                val afterDigit = afterDigit(at)
                at = if (afterDigit) next(at + 1) else next(word + 1)
                // Past the last place the cuts could change, from a place [endAt] read before, it finds what it found then.
                looking = afterDigit || word < until
            }
        }
    }

    /**
     * Whether [at] comes after a digit, as [isPatternDigit] says: if it starts a run of spaces, the next place in it reads
     * otherwise. One out of the basic plane is two letters, the second of which is before [at].
     */
    fun afterDigit(at: Int): Boolean {
        val before = letterBefore(at)
        // -1 for none; any other is a letter of the title, as the place it's before is at its end at most.
        if (before < 0) return false
        return isPatternDigit(Character.codePointBefore(title, before + 1))
    }

    /** Where to look again, from a place to the last place that the cuts since [since] could change what's found from. */
    private fun windowsSince(since: Int): List<Pair<Int, Int>> {
        val windows = mutableListOf<Pair<Int, Int>>()
        for (cut in since until cuts) {
            // The first and the last letter it took are cut, so they're skipped too.
            val left = previous(cutFirst[cut])
            val right = next(cutLast[cut])
            var near = right
            repeat(NEAR) { near = wordBefore(near - 1) }
            windows += next(near + 1) to right
            val bracket = table(BRACKETS_BEFORE).from(left)
            // "(feat. a" in "(feat. a(feat. b))": the bracket after its guest was cut.
            if (bracket >= 0 && title[bracket] in OPENS) windows += spacesStart(bracket) to bracket
            // " ft. X" in "Song ft. X - Remastered 2011)": the bracket that closed after it was cut, so it runs to the end.
            if (cutCloser[cut]) windows += next(bracket + 1) to left
        }
        return windows.sortedBy { it.first }
    }

    /** The title as it was before cut number [since] and the ones after it. */
    fun before(since: Int): String = buildString { for (place in 0 until end) if (cutBy[place] >= since) append(title[place]) }

    override fun toString() = before(cuts)

    companion object {
        /** [cutEach] looks at the whole title. */
        const val ALL = -1
        private const val KEPT = Int.MAX_VALUE
        private const val LETTERS = 0
        private const val WORDS = 1
        private const val BRACKETS_AFTER = 2

        /** Tables from here on go from the end. */
        private const val BACKWARD = 3
        private const val LETTERS_BEFORE = BACKWARD + LETTERS
        private const val WORDS_BEFORE = BACKWARD + WORDS
        private const val BRACKETS_BEFORE = BACKWARD + BRACKETS_AFTER
        private const val TABLES = 2 * BACKWARD

        /**
         * The most letters but spaces before a cut that a guest or a mark is read from, past the place [cutEach] reads
         * it from: "featuring" and the space after it, " featuring y" once "(feat. a)" is cut out of " featuring(feat.
         * a) y". One in brackets reads on from the bracket that opens it, which is the last before the cut.
         */
        private const val NEAR = 10
        private const val BRACKETS = "()[]"
        private const val OPENS = "(["
        private const val CLOSES = ")]"
    }
}

/**
 * The first place from one on that's one of those it stops at, skipping those [drop] took out, or the last up to one
 * if it goes [backward]: each found in about constant time, however many it skips, as it remembers where each skip
 * led. [ahead] holds each place for one it stops at and the next for one it doesn't, the last going one past the end,
 * from the end if it goes backward.
 */
private class Skips(private val ahead: IntArray, private val backward: Boolean) {
    private val last = ahead.size - 2

    /** From [place], or from the end it's past. */
    fun from(place: Int) = if (backward) last - find(last - maxOf(place, -1)) else find(minOf(place, last + 1))

    fun drop(place: Int) {
        val index = if (backward) last - place else place
        ahead[index] = index + 1
    }

    private fun find(index: Int): Int {
        var at = index
        while (ahead[at] != at) {
            ahead[at] = ahead[ahead[at]]
            at = ahead[at]
        }
        return at
    }
}
