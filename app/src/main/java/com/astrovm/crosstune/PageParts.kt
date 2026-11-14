package com.astrovm.crosstune

import java.util.regex.Matcher

/**
 * The parts of a page that a pattern of main's like `open[^>]*>(.*?)close` read, read as it read them, in time in step
 * with the page. Such a pattern takes, from each place [open] matches, the tag to its first `>`, then all up to the
 * first [close] after it, nested opens and `<` included. Where it can't, it tries from each later place, and each try
 * looks to the end of the page: time squared on a page of opens that never close. Here each mark is looked for once,
 * from where it was last found, as every try after a failed one only finds them further on.
 *
 * [open] is where a part starts, and can't overlap itself. [tagEnd] ends its opening tag, as `[^>]*>` reads it: the
 * first of its first letter, which the rest has to follow. Null when what's between starts right after [open].
 * [holds] is what the opening tag has in it, as `[^>]*holds[^>]*>` reads it. [close] ends a part: null for a tag
 * alone. What's between has no [without] in it, as `[^<]*` reads it for `<`, and with [oneLine] no line break, as `.`
 * reads it without [RegexOption.DOT_MATCHES_ALL].
 */
internal class PageParts(
    private val open: Regex,
    private val close: Regex?,
    private val tagEnd: String? = ">",
    private val holds: String? = null,
    private val without: Char? = null,
    private val oneLine: Boolean = false,
) {
    /** A part's whole range, and the range of what's between its opening tag and its [close]. */
    data class Part(val whole: IntRange, val between: IntRange)

    /** The first part of [page], or null. */
    fun firstIn(page: String): Part? = allIn(page).firstOrNull()

    /** What's between in the first part of [page], or null. */
    fun firstBetween(page: String): String? = firstIn(page)?.let { page.substring(it.between) }

    /** [page] with each part as [transform] gives it from what's between. */
    fun replaceParts(page: String, transform: (String) -> CharSequence): String {
        val kept = StringBuilder()
        var from = 0
        for (part in allIn(page)) {
            kept.append(page, from, part.whole.first).append(transform(page.substring(part.between)))
            from = part.whole.last + 1
        }
        return kept.append(page, from, page.length).toString()
    }

    /** [page] without its parts. */
    fun cutFrom(page: String): String = replaceParts(page) { "" }

    /** Each part of [page], in order, as `findAll` finds them: the next from where the last one ended. */
    fun allIn(page: String): Sequence<Part> = sequence {
        val opens = open.toPattern().matcher(page)
        val closes = close?.toPattern()?.matcher(page)
        val marks = Marks(page)
        var from = 0
        while (opens.find()) {
            if (opens.start() < from) continue
            val between = marks.between(opens.end())
            if (between == NONE_AFTER) return@sequence
            if (between == NOT_ONE) continue
            if (closes == null) {
                yield(Part(opens.start() until between, between until between))
                from = between
                continue
            }
            val closeStart = marks.closeAfter(closes, between)
            if (closeStart == NONE_AFTER) return@sequence
            if (closeStart == NOT_ONE) continue
            yield(Part(opens.start() until marks.closeEnd, between until closeStart))
            from = marks.closeEnd
        }
    }

    /**
     * The marks around the parts of [page], each the first of its kind from where it was last needed, or the page's
     * length for none: each is looked for again only past where it was found.
     */
    private inner class Marks(private val page: String) {
        // Each asked only where what it looks for is given.
        private val tagCloses = NextFound(page, tagEnd.orEmpty().take(1))
        private val helds = NextFound(page, holds.orEmpty())
        private val stops = NextFound(page, without?.toString().orEmpty())
        private var tagClose = -1
        private var closeStart = -1
        private val lineBreaks = NextFound(page) { from -> (from until page.length).firstOrNull { isLineBreak(page[it]) } ?: -1 }

        /** Where the [close] last found ends. */
        var closeEnd = -1
            private set

        /**
         * Where what's between starts, for an open that ends at [end]: past its opening tag. [NOT_ONE] where that tag
         * isn't one, and [NONE_AFTER] where no tag after it is either.
         */
        fun between(end: Int): Int {
            var between = end
            if (tagEnd != null) {
                tagClose = tagCloses.from(between)
                // A tag no `>` closes, nor any after it.
                if (tagClose == page.length) return NONE_AFTER
                if (!page.startsWith(tagEnd, tagClose)) return NOT_ONE
                between = tagClose + tagEnd.length
            }
            return holds?.let { heldIn(it, end, between) } ?: between
        }

        /** [between], where the opening tag that ends at [end] has [holds] in it, as [between] says. */
        private fun heldIn(holds: String, end: Int, between: Int): Int {
            val held = helds.from(end)
            return when {
                held == page.length -> NONE_AFTER
                // All of it in the tag, before the `>` that ends it.
                held + holds.length > tagClose -> NOT_ONE
                else -> between
            }
        }

        /**
         * Where the first of [closes] after [between] starts, for a part that has what's between from there: [NOT_ONE]
         * where a [without] or a line break comes first, and [NONE_AFTER] where nothing closes it, nor any part after it.
         */
        fun closeAfter(closes: Matcher, between: Int): Int {
            while (closeStart < between) {
                if (!closes.find()) return NONE_AFTER
                closeStart = closes.start()
                closeEnd = closes.end()
            }
            if (without != null && stops.from(between) < closeStart) return NOT_ONE
            if (oneLine && lineBreaks.from(between) < closeStart) return NOT_ONE
            return closeStart
        }
    }
}

/** What [PageParts.Marks] says of an open that starts no part, and of one after which none starts either. */
private const val NOT_ONE = -1
private const val NONE_AFTER = -2

/** This place, or null for none: -1, as `indexOf` says. */
internal fun Int.orNone(): Int? = if (this == -1) null else this

/**
 * Where [find] finds what it looks for in [text] from a place on, or [text]'s length for none, remembered: asked of
 * places in order, what it found stays the answer till a place past it is asked, so [text] is looked through once,
 * however many times it's asked. [find] says -1 for none, as `indexOf` does.
 */
internal class NextFound(private val text: String, private val find: (from: Int) -> Int) {
    /** Where [what] is next, with case or without it. */
    constructor(text: String, what: String, ignoreCase: Boolean = false) : this(text, { text.indexOf(what, it, ignoreCase) })

    private var found = -1

    /** How many times [text] was looked through from a place, which reading it in step with its length keeps few. */
    var looks = 0
        private set

    /** Where it's next from [place] on, or [text]'s length for none. */
    fun from(place: Int): Int {
        if (found < place) {
            found = find(place).let { if (it == -1) text.length else it }
            looks++
        }
        return found
    }
}

/**
 * Where each tag in [text] ends, asked of places in order, as main's `<[^>]+>` read a tag: from a `<` to the first `>`
 * after it, `<` and line breaks between too, with more than [least] letters between. `<[^>]*>` is a [least] of 0.
 */
internal class TagEnds(private val text: String, private val least: Int = 1) {
    private val closes = NextFound(text, ">")

    /** Where the tag that starts at [at] ends, at its `>`, or -1 for none. */
    fun at(at: Int): Int {
        if (text[at] != '<') return -1
        val close = closes.from(at)
        return if (close != text.length && close - at > least) close else -1
    }
}

/**
 * [text] with each tag as [TagEnds] reads them put [with], as main's `text.replace(Regex("<[^>]+>"), with)` did, but for
 * a `<` where [skip] says a tag isn't. A page's tags are cut in time in step with it, also with no `>` after them.
 */
internal fun withoutTags(text: String, with: String = "", least: Int = 1, skip: (Int) -> Boolean = { false }): String {
    val ends = TagEnds(text, least)
    val kept = StringBuilder()
    var from = 0
    var at = text.indexOf('<')
    while (at >= 0) {
        val end = if (skip(at)) -1 else ends.at(at)
        if (end == -1) {
            at = text.indexOf('<', at + 1)
            continue
        }
        kept.append(text, from, at).append(with)
        from = end + 1
        at = text.indexOf('<', from)
    }
    return kept.append(text, from, text.length).toString()
}
