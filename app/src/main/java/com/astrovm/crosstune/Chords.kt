package com.astrovm.crosstune

import org.json.JSONArray
import java.text.Normalizer

/** A chord played from letter [at] of a line. */
internal data class Chord(val at: Int, val name: String)

/** A line of a chord sheet: its words, and the chords over them; a line of chords alone has no words. */
internal data class SheetLine(val text: String, val chords: List<Chord>)

/** Where a song's chords were found. */
internal enum class ChordSite { U_FRET, ULTIMATE_GUITAR, LA_CUERDA, CIFRA_CLUB, J_TOTAL }

/** A song's chords as a sheet has them, with the fret a capo goes on to play them, and where it's from. */
internal data class ChordSheet(val lines: List<SheetLine>, val capo: Int = 0, val site: ChordSite, val url: String)

/** A line's chords: those played before it's sung, as in an intro, and those over its letters. */
internal data class LineChords(val before: List<String> = emptyList(), val over: List<Chord> = emptyList()) {
    val isEmpty get() = before.isEmpty() && over.isEmpty()
}

/**
 * Chord sheets, read from the sites that have them and put over the words shown: both are lined up
 * letter by letter, so each chord lands on the letter it's played on, wherever the lines break.
 */
internal object ChordSheets {
    /**
     * [chords], placed over [written], moved onto [shown], the same line in kana or Latin letters.
     * Where [parts] spell [shown] out, a piece at a time, a chord goes over the start of the kana of the
     * kanji it was over, or the same letter; otherwise as far along [shown], at the start of that word.
     */
    fun onto(chords: LineChords, written: String, shown: String, parts: List<Ruby>?): LineChords {
        val spelled = parts?.takeIf { it.joinToString("") { part -> part.text } == written && it.sumOf { part -> (part.reading ?: part.text).length } == shown.length }
        return chords.copy(over = chords.over.map { chord -> chord.copy(at = if (spelled != null) inParts(chord.at, spelled) else asFarAlong(chord.at, written.length, shown)) })
    }

    /** Where letter [at] of the parts as written goes in them as read. */
    private fun inParts(at: Int, parts: List<Ruby>): Int {
        var written = 0
        var shown = 0
        for (part in parts) {
            if (at < written + part.text.length) return shown + if (part.reading == null) at - written else 0
            written += part.text.length
            shown += (part.reading ?: part.text).length
        }
        return shown
    }

    /** Letter [at] of a line [length] long, as far along [shown], at the start of the word it's in. */
    private fun asFarAlong(at: Int, length: Int, shown: String): Int {
        if (shown.isEmpty() || length == 0) return 0
        val along = (at.toLong() * shown.length / length).toInt().coerceIn(0, shown.length - 1)
        return shown.lastIndexOf(' ', along) + 1
    }

    /**
     * The sheet counts once this much of its words line up with the song's, or of the song's with
     * its, whichever are fewer: a sheet may have the song twice, in another tuning, and the words
     * shown may be only part of it.
     */
    private const val GOOD_LETTERS = 0.6

    /**
     * And this much of it lines up a letter after the one before, as a line's words do: another
     * song's letters can be found one by one in long enough words, rap's often, scattered.
     */
    private const val GOOD_RUNS = 0.4

    /** And a line of it once this much of it does. */
    private const val GOOD_LINE = 0.5

    /** Fewer letters than this say too little to tell it's the same song. */
    private const val MIN_LETTERS = 10

    /** How long a line with nothing after it takes per letter, to time its chords. */
    private const val LETTER_MS = 150L

    private val inline = Regex("""\[([^\]]*)]""")
    private val ugChord = Regex("""\[ch]([^\[]*)\[/ch]""")
    private val ugTab = Regex("""\[/?tab]""")
    private val section = Regex("""^\s*\[[^\]]*]\s*$""")
    private val solfege = mapOf("DO" to "C", "RE" to "D", "MI" to "E", "FA" to "F", "SOL" to "G", "LA" to "A", "SI" to "B")
    private val jTotalLineEnd = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    /** A chord, linked to the picture of how it's played, and any bass written after the link: "E" then "onG#". */
    private val jTotalChord = Regex("""<a [^>]*>([^<]+)</a>(?:on([A-G][#b]?))?""")

    /** A chord link a <br> cut in half, so the line starts with the name and the closing tag. */
    private val splitChord = Regex("""^([^<]+)</a>(?:on([A-G][#b]?))?""")
    private val markup = Regex("""<[^>]+>""")
    private val sourceSpace = Regex("""[ \t\r\n]+""")

    private val solfegeChord = Regex("""^(SOL|DO|RE|MI|FA|LA|SI)([#b]?)((?:maj|min|sus|add|dim|aug|alt|m|M|b|[^A-Za-z/])*)(?:/(SOL|DO|RE|MI|FA|LA|SI)([#b]?))?$""")

    /** U-FRET's sheet: each line its words with each chord in brackets where it's played, "[Am]五月[Em]雨". */
    fun fromUFret(lines: JSONArray): List<SheetLine> = (0 until lines.length()).map { index ->
        val line = lines.getString(index).trimEnd('\r', '\n')
        val chords = mutableListOf<Chord>()
        val text = StringBuilder()
        var from = 0
        inline.findAll(line).forEach { match ->
            text.append(line, from, match.range.first)
            match.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { chords += Chord(text.length, it) }
            from = match.range.last + 1
        }
        text.append(line.substring(from))
        SheetLine(text.toString(), chords)
    }

    /**
     * [name] with its root written as a letter when it's in solfège, as Spanish sheets have it:
     * "LAm" is "Am", "SOL7/SI" is "G7/B", "LAm7b5" is "Am7b5". Roots are capitalised as such, so "Fadd9" stays.
     */
    fun letterName(name: String): String {
        val match = solfegeChord.matchEntire(name) ?: return name
        val (root, accidental, rest, bass, bassAccidental) = match.destructured
        val letters = StringBuilder(solfege.getValue(root)).append(accidental).append(rest)
        if (bass.isNotEmpty()) letters.append('/').append(solfege.getValue(bass)).append(bassAccidental)
        return letters.toString()
    }

    /**
     * J-Total's sheet, as written into its page: a line of chords over the line of words they're
     * played on, each over its letter. Japanese takes two columns, and the Latin font the page names
     * first takes one. A full-width bracket or stop is two as well, except where the browser draws
     * it at one. Only a <br> ends a line, and spaces count as
     * a browser counts them, as its source wraps anywhere. Tags are not drawn: an empty link adds
     * no column, and a link split by a <br> still names the chord on the next line.
     */
    fun fromJTotal(sheet: String): List<SheetLine> {
        val lines = sheet.split(jTotalLineEnd).map { it.replace(sourceSpace, " ").trim(' ') }
            .filter { it.replace(markup, "").trim(' ').isNotEmpty() }
        val result = mutableListOf<SheetLine>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index++]
            if (!jTotalChord.containsMatchIn(line) && !splitChord.containsMatchIn(line)) {
                result += SheetLine(line, emptyList())
                continue
            }
            val chords = mutableListOf<Pair<Int, String>>()
            var column = 0
            var from = 0
            // The opening tag stayed on the previous line, so this one starts at the chord's name.
            splitChord.find(line)?.let { split ->
                val (name, bass) = split.destructured
                chords += column to if (bass.isEmpty()) name else "$name/$bass"
                column += columns(split.value.replace(markup, ""))
                from = split.range.last + 1
            }
            jTotalChord.findAll(line).forEach { match ->
                val (name, bass) = match.destructured
                column += columns(line.substring(from, match.range.first).replace(markup, ""))
                chords += column to if (bass.isEmpty()) name else "$name/$bass"
                column += columns(match.value.replace(markup, ""))
                from = match.range.last + 1
            }
            val words = lines.getOrNull(index)?.takeUnless {
                jTotalChord.containsMatchIn(it) || splitChord.containsMatchIn(it)
            }
            if (words != null) index++
            result += SheetLine(words.orEmpty(), chords.map { (at, name) -> Chord(letterAt(words.orEmpty(), at), name) })
        }
        return result
    }

    /**
     * How wide [text] is in the font the page asks for, as Chromium draws it. Inconsolata, named
     * first, draws its own letters at one column and its combining marks at none. The Japanese
     * monospace font under it draws half-width kana at one, the two half-width voice marks at none,
     * and Japanese at two. A full-width bracket or stop is two, then one where Chromium's default
     * `text-spacing-trim: normal` kerns it. That follows the CSS Text 4 classes: a closing mark
     * before a closing mark, a middle dot or an ideographic space, and an opening mark after those
     * or after a closing mark. For Japanese, and on an untagged page, the full-width colon and
     * semicolon are middle dots and the stops are closing marks. A right quote trims the closing
     * mark before it and not the opening mark after it: Inconsolata draws that quote at one column.
     * Measured on the page's font.
     */
    private fun columns(text: String) = text.indices.sumOf { columnsAt(text, it) }

    private fun columnsAt(text: String, index: Int): Int {
        val width = columnsOf(text[index])
        return if (width == 2 && trimmed(text, index)) 1 else width
    }

    private fun columnsOf(letter: Char): Int = when {
        letter in zeroWidth -> 0
        letter.code <= 0xFF || inconsolataOne.any { letter in it } -> 1
        letter in '\uFF61'..'\uFF9D' -> 1
        else -> 2
    }

    /** A two-column bracket or stop that the browser draws at one because of the letter beside it. */
    private fun trimmed(text: String, index: Int): Boolean {
        val letter = text[index]
        if (fullwidthClose(letter) && index + 1 < text.length && closesBefore(text[index + 1])) return true
        return fullwidthOpen(letter) && index > 0 && opensAfter(text[index - 1])
    }

    private fun closesBefore(next: Char): Boolean {
        if (next == '\u3000' || next in middleDots || next in rightQuotes) return true
        return fullwidthClose(next) || Character.getType(next) == Character.END_PUNCTUATION.toInt()
    }

    private fun opensAfter(prev: Char): Boolean {
        if (prev == '\u3000' || prev in middleDots || prev in leftQuotes) return true
        return fullwidthClose(prev) || Character.getType(prev) == Character.START_PUNCTUATION.toInt()
    }

    /** Stops Chromium trims as closing punctuation. They are not Unicode category Pe. */
    private fun fullwidthClose(letter: Char) = letter in "、。，．" || cjkBracket(letter, Character.END_PUNCTUATION.toInt())

    private fun fullwidthOpen(letter: Char) = cjkBracket(letter, Character.START_PUNCTUATION.toInt())

    private fun cjkBracket(letter: Char, type: Int): Boolean {
        val block = Character.UnicodeBlock.of(letter)
        val cjk = block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        return cjk && Character.getType(letter) == type
    }

    /** Middle dots, plus the full-width colon and semicolon. Japanese, and Chromium on an untagged page, trim beside both. */
    private val middleDots = setOf('\u00B7', '\u2027', '\u30FB', '\uFF1A', '\uFF1B')

    private val leftQuotes = setOf('\u2018', '\u201C')

    private val rightQuotes = setOf('\u2019', '\u201D')

    /** Letters with no advance: Inconsolata's combining marks, the half-width voice marks, and spaces the browser drops. */
    private val zeroWidth = setOf(
        '\u0300', '\u0301', '\u0303', '\u0304', '\u0308', '\u0309', '\u0323',
        '\u200B', '\uFEFF', '\uFF9E', '\uFF9F',
    )

    /**
     * Letters Inconsolata draws at one column, above Latin-1. These are the glyphs in the font
     * files the page's stylesheet loads, measured in Chromium: each is one column wide there.
     */
    private val inconsolataOne = listOf(
        '\u0100'..'\u0131',
        '\u0134'..'\u0148',
        '\u014A'..'\u017E',
        '\u018F'..'\u018F',
        '\u0192'..'\u0192',
        '\u0198'..'\u0198',
        '\u01A0'..'\u01A1',
        '\u01AF'..'\u01B0',
        '\u01B8'..'\u01B9',
        '\u01C7'..'\u01C9',
        '\u01E6'..'\u01E7',
        '\u01EA'..'\u01EB',
        '\u01FA'..'\u021B',
        '\u022A'..'\u022D',
        '\u0230'..'\u0233',
        '\u0237'..'\u0237',
        '\u024D'..'\u024D',
        '\u0259'..'\u0259',
        '\u027B'..'\u027B',
        '\u0298'..'\u0298',
        '\u029A'..'\u029A',
        '\u02B9'..'\u02BC',
        '\u02BE'..'\u02BF',
        '\u02C6'..'\u02CC',
        '\u02DA'..'\u02DA',
        '\u02DC'..'\u02DD',
        '\u1E08'..'\u1E09',
        '\u1E0C'..'\u1E0F',
        '\u1E14'..'\u1E17',
        '\u1E1C'..'\u1E1D',
        '\u1E20'..'\u1E21',
        '\u1E24'..'\u1E25',
        '\u1E2A'..'\u1E2B',
        '\u1E2E'..'\u1E2F',
        '\u1E36'..'\u1E37',
        '\u1E3A'..'\u1E3B',
        '\u1E42'..'\u1E49',
        '\u1E4C'..'\u1E53',
        '\u1E5A'..'\u1E5B',
        '\u1E5E'..'\u1E69',
        '\u1E6C'..'\u1E6F',
        '\u1E78'..'\u1E7B',
        '\u1E80'..'\u1E85',
        '\u1E8E'..'\u1E8F',
        '\u1E92'..'\u1E93',
        '\u1E97'..'\u1E97',
        '\u1E9E'..'\u1E9E',
        '\u1EA0'..'\u1EF9',
        '\u2009'..'\u2009',
        '\u2013'..'\u2014',
        '\u2018'..'\u201A',
        '\u201C'..'\u201E',
        '\u2020'..'\u2020',
        '\u2022'..'\u2022',
        '\u2026'..'\u2026',
        '\u2032'..'\u2033',
        '\u2039'..'\u203A',
        '\u2044'..'\u2044',
        '\u20A1'..'\u20A1',
        '\u20A3'..'\u20A4',
        '\u20A6'..'\u20A7',
        '\u20A9'..'\u20A9',
        '\u20AB'..'\u20AD',
        '\u20B1'..'\u20B2',
        '\u20B5'..'\u20B5',
        '\u20B9'..'\u20BA',
        '\u20BC'..'\u20BD',
        '\u2113'..'\u2113',
        '\u2122'..'\u2122',
        '\u2191'..'\u2191',
        '\u2193'..'\u2193',
        '\u2212'..'\u2212',
        '\u2215'..'\u2215',
        '\uFFFD'..'\uFFFD',
    )

    /**
     * The first letter of [words] that starts at or after [column]. Widths use the whole line, so a
     * bracket can see the punctuation beside it.
     */
    private fun letterAt(words: String, column: Int): Int {
        var total = 0
        for (index in words.indices) {
            if (total >= column) return index
            total += columnsAt(words, index)
        }
        return words.length
    }

    /**
     * Ultimate Guitar's sheet: a line of chords over the line of words they're played on, each over
     * the letter it starts on; a line of chords with no words under it is played on its own.
     */
    fun fromUltimateGuitar(content: String): List<SheetLine> {
        val lines = content.replace("\r", "").replace(ugTab, "").split("\n")
        val sheet = mutableListOf<SheetLine>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            index++
            if (section.matches(line)) continue
            if (!ugChord.containsMatchIn(line)) {
                if (line.isNotBlank()) sheet += SheetLine(line.trimEnd(), emptyList())
                continue
            }
            // Where each chord starts, with the markup around them taken out.
            val chords = mutableListOf<Chord>()
            val plain = StringBuilder()
            var from = 0
            ugChord.findAll(line).forEach { match ->
                plain.append(line, from, match.range.first)
                chords += Chord(plain.length, letterName(match.groupValues[1].trim()))
                plain.append(match.groupValues[1])
                from = match.range.last + 1
            }
            val words = lines.getOrNull(index)
            if (!words.isNullOrBlank() && !ugChord.containsMatchIn(words) && !section.matches(words)) {
                index++
                sheet += SheetLine(words.trimEnd(), chords.map { it.copy(at = it.at.coerceAtMost(words.trimEnd().length)) })
            } else {
                sheet += SheetLine("", chords)
            }
        }
        return sheet
    }

    /**
     * The [sheet]'s chords over each of [texts], the lines shown; null when its words aren't these,
     * as for another song of the same name, and when none of its chords land on them, as for a page of
     * the words alone.
     */
    fun place(sheet: ChordSheet, texts: List<String>): List<LineChords>? =
        (placeIn(sheet, texts) ?: placeRomanized(sheet, texts))?.takeUnless { placed -> placed.all { it.isEmpty } }

    /** The lines [texts] as they read in Latin letters, kept for the next sheet tried: the same words are put against several. */
    private var lastTexts: List<String>? = null
    private var lastSaid: List<String>? = null

    private fun said(texts: List<String>): List<String>? = synchronized(this) {
        if (texts != lastTexts) {
            lastSaid = Readings.scriptOf(texts)?.let { script -> Readings.of(texts, script).zip(texts) { reading, text -> reading.romanized.ifBlank { text } } }
            lastTexts = texts
        }
        lastSaid
    }

    /**
     * Words in Japanese, Chinese or Korean against a sheet that writes them in Latin letters, as
     * most of them are for such songs on Ultimate Guitar: the words as they read, and each chord on
     * the same part of its line as it was, since the letters no longer line up with the written ones.
     */
    private fun placeRomanized(sheet: ChordSheet, texts: List<String>): List<LineChords>? {
        val said = said(texts) ?: return null
        val placed = placeIn(sheet, said) ?: return null
        return placed.mapIndexed { number, line ->
            val length = texts[number].length
            val readLength = said[number].length.coerceAtLeast(1)
            LineChords(line.before, line.over.map { chord -> Chord((chord.at.toLong() * length / readLength).toInt().coerceIn(0, length), chord.name) })
        }
    }

    private fun placeIn(sheet: ChordSheet, texts: List<String>): List<LineChords>? {
        val lines = inScriptOf(sheet.lines, texts)
        val ours = Letters(texts)
        val theirs = Letters(lines.map { it.text })
        if (theirs.size < MIN_LETTERS || ours.size == 0) return null
        val matchedAt = BorrowedTimings.lineUp(ours.keys, theirs.keys) ?: return null
        val fewer = minOf(theirs.size, ours.size)
        if (matchedAt.count { it >= 0 } < fewer * GOOD_LETTERS) return null
        if ((1 until matchedAt.size).count { matchedAt[it - 1] >= 0 && matchedAt[it] == matchedAt[it - 1] + 1 } < fewer * GOOD_RUNS) return null

        val before = Array(texts.size) { mutableListOf<String>() }
        val over = Array(texts.size) { mutableListOf<Chord>() }
        // Chords played on their own, waiting for the next line sung.
        val waiting = mutableListOf<String>()
        lines.forEachIndexed { number, line ->
            val letters = theirs.of(number)
            if (letters.isEmpty()) {
                waiting += line.chords.map { it.name }
                return@forEachIndexed
            }
            // Its words aren't the song's, as for a verse the song doesn't have: neither are its chords.
            if (letters.count { matchedAt[it] >= 0 } < letters.size * GOOD_LINE) return@forEachIndexed
            val anchor = letters.first { matchedAt[it] >= 0 }
            val lineAt = ours.line[matchedAt[anchor]]
            if (waiting.isNotEmpty()) {
                // Into the gap before the line, when the words show one, e.g. for a solo.
                val gap = lineAt - 1
                (if (gap >= 0 && texts[gap].isBlank() && before[gap].isEmpty()) before[gap] else before[lineAt]) += waiting
                waiting.clear()
            }
            line.chords.forEach { chord ->
                placeChord(chord, letters, theirs, ours, matchedAt)?.let { (at, char) -> over[at] += Chord(char, chord.name) }
            }
        }
        return texts.indices.map { LineChords(before[it], over[it].sortedBy { chord -> chord.at }) }
    }

    private fun latin(text: String): Boolean = text.filter(Char::isLetter).let { letters -> letters.isNotEmpty() && letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN } * 2 > letters.length }

    /**
     * The sheet's lines, but for the ones in Latin letters that carry no chord when the song is in
     * another script and the sheet writes it in that too: they are the same words said again, as
     * "habibi ya nur" under "حبيبي يا نور", and would be words the song never sings.
     */
    private fun inScriptOf(lines: List<SheetLine>, texts: List<String>): List<SheetLine> {
        if (latin(texts.joinToString(""))) return lines
        val written = lines.any { it.text.any(Char::isLetter) && !latin(it.text) }
        return if (written) lines.filterNot { it.chords.isEmpty() && latin(it.text) } else lines
    }

    /** The line shown and the letter in it [chord] lands on, from the nearest letter of its own line that lined up. */
    private fun placeChord(chord: Chord, letters: List<Int>, theirs: Letters, ours: Letters, matchedAt: IntArray): Pair<Int, Int>? {
        // The letter it's played on; past the line's last, just after it.
        val on = letters.firstOrNull { theirs.char[it] >= chord.at }
        val letter = on ?: letters.last()
        val near = letters.sortedBy { kotlin.math.abs(it - letter) }.firstOrNull { matchedAt[it] >= 0 } ?: return null
        val at = matchedAt[near]
        val line = ours.line[at]
        // As many letters on from there, within the same line.
        val target = (at + (letter - near)).coerceIn(ours.firstOf(line), ours.lastOf(line))
        return line to (ours.char[target] + if (on == null) 1 else 0)
    }

    /**
     * When [line]'s letter [at] is sung: within the word it's in, for words timed one by one, or that
     * far through the line, up to when [nextMs] the next one starts.
     */
    fun timeOf(line: LyricLine, at: Int, nextMs: Long?): Long {
        val endMs = nextMs ?: (line.timeMs + line.text.length * LETTER_MS)
        if (line.words.isNotEmpty()) {
            var from = 0
            line.words.forEachIndexed { index, word ->
                val to = from + word.text.length
                if (at < to || index == line.words.lastIndex) {
                    val wordEnd = line.words.getOrNull(index + 1)?.timeMs ?: endMs
                    val into = (at - from).coerceIn(0, word.text.length)
                    return word.timeMs + (wordEnd - word.timeMs) * into / word.text.length.coerceAtLeast(1)
                }
                from = to
            }
        }
        return line.timeMs + (endMs - line.timeMs) * at.coerceIn(0, line.text.length) / line.text.length.coerceAtLeast(1)
    }

    /** The chord being played at [positionMs] among [line]'s, by its place in them; -1 before the first. */
    fun playing(line: LyricLine, chords: List<Chord>, nextMs: Long?, positionMs: Long): Int =
        chords.indexOfLast { timeOf(line, it.at, nextMs) <= positionMs }

    /** The letters of some lines, each with the line and place it's at, as they're compared: in lower case, full width as half. */
    private class Letters(texts: List<String>) {
        val line: IntArray
        val char: IntArray
        val keys: String
        private val firsts: IntArray
        private val ends: IntArray

        init {
            val lines = mutableListOf<Int>()
            val chars = mutableListOf<Int>()
            val keys = StringBuilder()
            firsts = IntArray(texts.size)
            ends = IntArray(texts.size)
            texts.forEachIndexed { number, text ->
                firsts[number] = lines.size
                text.forEachIndexed { at, letter ->
                    if (letter.isLetterOrDigit()) {
                        lines += number
                        chars += at
                        keys.append(key(letter))
                    }
                }
                ends[number] = lines.size
            }
            line = lines.toIntArray()
            char = chars.toIntArray()
            this.keys = keys.toString()
        }

        val size get() = line.size

        fun of(number: Int): List<Int> = (firsts[number] until ends[number]).toList()

        fun firstOf(number: Int) = firsts[number]

        fun lastOf(number: Int) = ends[number] - 1

        private fun key(letter: Char): Char {
            val plain = if (letter in '！'..'～') letter - 0xFEE0 else letter
            // Without its accent, as a sheet may write it: "ǒ" of the pinyin the words are read in, "ó" of "canción".
            val base = if (Character.UnicodeScript.of(plain.code) == Character.UnicodeScript.LATIN) Normalizer.normalize(plain.toString(), Normalizer.Form.NFD)[0] else plain
            return base.lowercaseChar()
        }
    }
}
