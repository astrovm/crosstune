package com.astrovm.crosstune

import org.json.JSONArray
import java.text.Normalizer
import kotlin.math.abs

/** A chord played from letter [at] of a line. */
internal data class Chord(val at: Int, val name: String)

/** A line of a chord sheet: its words, and the chords over them; a line of chords alone has no words. */
internal data class SheetLine(val text: String, val chords: List<Chord>)

/** Where a song's chords were found. */
internal enum class ChordSite { U_FRET, ULTIMATE_GUITAR, LA_CUERDA, CIFRA_CLUB, J_TOTAL, CHORDWIKI }

/** ChordWiki's sheet as its text names it: the lines, the fret a capo goes on, and the title and singer. */
internal data class ChordWikiSong(val lines: List<SheetLine>, val capo: Int = 0, val title: String = "", val artist: String = "")

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

    /** A reading in brackets right after a kanji: kana, and the dashes that hold a sound. */
    private val reading = Regex("""(?<=\p{IsHan})[(（][ぁ-ゟァ-ヿｦ-ﾟ\-－]+[)）]""")
    private val ugChord = Regex("""\[ch]([^\[]*)\[/ch]""")
    private val ugTab = Regex("""\[/?tab]""")
    private val section = Regex("""^\s*\[[^\]]*]\s*$""")
    private val solfege = mapOf("DO" to "C", "RE" to "D", "MI" to "E", "FA" to "F", "SOL" to "G", "LA" to "A", "SI" to "B")
    private val jTotalLineEnd = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    /** A chord, linked to the picture of how it's played, and any bass written after the link: "E" then "onG#". */
    private val jTotalChord = Regex("""<a [^>]*cd/[^>]*>([^<]*[^<\s][^<]*)</a>(?:on([A-G][#b]?))?""")

    /** A chord link a <br> cut in half, so the line starts with the name and the closing tag. */
    private val splitChord = Regex("""^([^<]*[^<\s][^<]*)</a>(?:on([A-G][#b]?))?""")
    private val markup = Regex("""<[^>]+>""")
    private val nonAnchorMarkup = Regex("""<(?!/?a(?:\s|>))/?[^>]+>""", RegexOption.IGNORE_CASE)
    private val sourceSpace = Regex("""[ \t\r\n]+""")
    private val spacesAcrossTags = Regex(""" ((?:<[^>]+>)+) +""")
    private val leadingSpaceInTags = Regex("""^((?:<[^>]+>)+) +""")

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
     * it at one. A letter in neither font is as wide as the browser's fallback draws it, and a chord
     * goes over the letter that starts nearest where it is. Only a <br> ends a line, and spaces count as
     * a browser counts them, as its source wraps anywhere. Tags are not drawn: an empty link adds
     * no column, and a link split by a <br> still names the chord on the next line.
     */
    fun fromJTotal(sheet: String): List<SheetLine> {
        val lines = sheet.split(jTotalLineEnd).map {
            it.replace(nonAnchorMarkup, "").replace(sourceSpace, " ").trim(' ')
                .replace(spacesAcrossTags, " $1").replace(leadingSpaceInTags, "$1")
        }
            .filter { it.replace(markup, "").trim(' ').isNotEmpty() }
        val result = mutableListOf<SheetLine>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index++]
            if (!jTotalChord.containsMatchIn(line) && !splitChord.containsMatchIn(line)) {
                result += SheetLine(line.replace(markup, ""), emptyList())
                continue
            }
            val chords = mutableListOf<Pair<Int, String>>()
            var column = 0
            var from = 0
            // The opening tag stayed on the previous line, so this one starts at the chord's name.
            splitChord.find(line)?.let { split ->
                val (name, bass) = split.destructured
                chords += column to if (bass.isEmpty()) name.trim() else "${name.trim()}/$bass"
                column += columns(split.value.replace(markup, ""))
                from = split.range.last + 1
            }
            jTotalChord.findAll(line).forEach { match ->
                val (name, bass) = match.destructured
                column += columns(line.substring(from, match.range.first).replace(markup, ""))
                chords += column to if (bass.isEmpty()) name.trim() else "${name.trim()}/$bass"
                column += columns(match.value.replace(markup, ""))
                from = match.range.last + 1
            }
            val words = lines.getOrNull(index)?.takeUnless {
                jTotalChord.containsMatchIn(it) || splitChord.containsMatchIn(it)
            }?.replace(markup, "")
            if (words != null) index++
            result += SheetLine(words.orEmpty(), chords.map { (at, name) -> Chord(letterAt(words.orEmpty(), at), name) })
        }
        return result
    }

    /**
     * How wide [text] is in the font the page asks for, as Chromium draws it, in thousandths of a
     * column ([JTotalWidths]). A letter takes the width of the font it is drawn from: Inconsolata's
     * own at one column, the Japanese monospace font's at one or two, and any other from a proportional
     * font, at whatever it measures. A full-width bracket or stop is two, then one where Chromium's
     * default `text-spacing-trim: normal` kerns it. That follows the CSS Text 4 classes: a closing mark
     * before a closing mark, a middle dot or an ideographic space, and an opening mark after those
     * or after a closing mark. For Japanese, and on an untagged page, the full-width colon and
     * semicolon are middle dots and the stops are closing marks. A right quote trims the closing
     * mark before it and not the opening mark after it: Inconsolata draws that quote at one column.
     * A letter outside the Basic Multilingual Plane is one letter, not two halves.
     */
    private fun columns(text: String): Int {
        var total = 0
        var index = 0
        while (index < text.length) {
            total += widthAt(text, index)
            index += Character.charCount(text.codePointAt(index))
        }
        return total
    }

    private fun widthAt(text: String, index: Int): Int {
        val width = JTotalWidths.of(text.codePointAt(index))
        return if (width == 2 * JTotalWidths.COLUMN && trimmed(text, index)) JTotalWidths.COLUMN else width
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

    /**
     * The letter of [words] whose start is nearest [column], the later one when two are as near: a
     * chord over the middle of a wide letter is read as the next. Widths use the whole line, so a
     * bracket can see the punctuation beside it. Letters that start in the same place, as a mark with
     * no width does, give the first. The end of the line is where a chord past the last letter goes.
     */
    private fun letterAt(words: String, column: Int): Int {
        var best = 0
        var bestStart = 0
        var bestDistance = Int.MAX_VALUE
        var start = 0
        var index = 0
        while (true) {
            val distance = abs(start - column)
            if (distance < bestDistance || (distance == bestDistance && start > bestStart)) {
                best = index
                bestStart = start
                bestDistance = distance
            }
            if (index >= words.length) return best
            start += widthAt(words, index)
            index += Character.charCount(words.codePointAt(index))
        }
    }

    /** A ChordPro directive name, as `{title:…}` and `{start_of_tab}` write it. */
    private val directiveName = Regex("""[a-z_][a-z0-9_]*""")

    /** The longest editor on an edit page; a page with none is the ChordPro text itself. */
    private val textarea = Regex("""<textarea\b[^>]*>(.*?)</textarea>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** A chord token: a letter root, then only what a chord name uses, and not a whole word. */
    private val chordToken = Regex("""[A-G][0-9A-Za-z#/()+\-]*""")
    private val letterRun = Regex("""[A-Za-z]{4,}""")
    private val chordWord = Regex("""maj|min|sus|add|dim|aug""")

    /** `(4/4)` at the start of a line, which is not sung and not part of where a chord sits. */
    private val timeSignature = Regex("""^\(\d+/\d+\)""")

    /**
     * Who sings it, after 歌 and a colon (half or full width). The credit that follows (作詞, 作曲,
     * 編曲, 歌詞) is not their name. A subtitle with no such mark is the name itself.
     */
    private val sungBy = Regex("""歌[:：][\s　]*(.+?)(?=[\s　]*(?:作詞|作曲|編曲|歌詞)|$)""")

    /** Title, singer and capo read so far, and whether a tab or grid is open. */
    private data class ChordWikiHead(
        val title: String = "",
        val artist: String = "",
        val capo: Int = 0,
        val capoSet: Boolean = false,
        val inTab: Boolean = false
    )

    /**
     * ChordWiki's [source], ChordPro as its editor has it, or the edit page that holds that editor.
     * A `#` ends a note, `{…}` is a direction rather than a line sung, and `[Am]` is the chord played
     * on the letter it stands before. Bars and a leading time signature are not part of the words.
     */
    fun fromChordWiki(source: String): ChordWikiSong {
        var head = ChordWikiHead()
        val lines = mutableListOf<SheetLine>()
        for (raw in chordWikiText(source).lineSequence()) {
            val line = chordWikiBare(raw).trim()
            val directive = chordWikiDirectiveLine(line)
            if (directive != null) {
                head = chordWikiApply(head, directive)
                continue
            }
            if (head.inTab || line.isEmpty()) continue
            chordWikiLine(line)?.let { lines += it }
        }
        return ChordWikiSong(lines, head.capo, head.title, head.artist)
    }

    /** `{…}` when [line] is one directive, braces included. */
    private fun chordWikiDirectiveLine(line: String): Pair<String, String>? =
        line.takeIf { it.startsWith("{") && it.endsWith("}") }?.let(::chordWikiDirective)

    /** [head] after one directive. The first title, singer and capo stay; a bad capo leaves room for a later one. */
    private fun chordWikiApply(head: ChordWikiHead, directive: Pair<String, String>): ChordWikiHead {
        val (name, value) = directive
        return when (name) {
            "title", "t" -> if (head.title.isNotEmpty() || value.isEmpty()) head else head.copy(title = value)
            "subtitle", "st" -> chordWikiNamed(head, value)
            "capo" -> chordWikiCapoOn(head, value)
            else -> head.copy(inTab = chordWikiInTab(name, head.inTab))
        }
    }

    /** [head] with the singer [subtitle] names, when it doesn't already name one. */
    private fun chordWikiNamed(head: ChordWikiHead, subtitle: String): ChordWikiHead {
        if (head.artist.isNotEmpty()) return head
        val artist = chordWikiArtist(subtitle)
        return if (artist.isEmpty()) head else head.copy(artist = artist)
    }

    /** [head] with the fret [value] names, when no capo was set yet and [value] is one. */
    private fun chordWikiCapoOn(head: ChordWikiHead, value: String): ChordWikiHead {
        if (head.capoSet) return head
        val capo = chordWikiCapo(value) ?: return head
        return head.copy(capo = capo, capoSet = true)
    }

    /** The ChordPro text: the longest textarea on an edit page, or [page] when it has none. An empty editor stays empty. */
    private fun chordWikiText(page: String): String {
        val editors = textarea.findAll(page).map { it.groupValues[1] }.toList()
        if (editors.isEmpty()) return page
        return ChordFinder.unescape(editors.maxBy { it.length })
    }

    /** [line] up to a `#` that isn't inside a chord, so `[C#m]` stays and the note after it doesn't. */
    private fun chordWikiBare(line: String): String = buildString {
        var depth = 0
        for (ch in line) {
            if (ch == '#' && depth == 0) break
            if (ch == '[') depth++ else if (ch == ']' && depth > 0) depth--
            append(ch)
        }
    }

    /** `{name}` or `{name: value}` when [line] is one directive, braces included. */
    private fun chordWikiDirective(line: String): Pair<String, String>? {
        val body = line.substring(1, line.lastIndex)
        val name = body.substringBefore(':').trim().lowercase()
        if (!directiveName.matches(name)) return null
        return name to body.substringAfter(':', "").trim()
    }

    /** The singer named in a subtitle, or nothing when it names nobody. */
    private fun chordWikiArtist(subtitle: String): String {
        sungBy.find(subtitle)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return subtitle.substringBefore("作詞").substringBefore("作曲").substringBefore("編曲").substringBefore("歌詞").trim()
    }

    /** The fret [value] names, when it's a guitar fret; words and a number past the neck are not. */
    private fun chordWikiCapo(value: String): Int? {
        val number = Regex("""\d+""").find(value)?.value?.toIntOrNull() ?: return null
        return number.takeIf { it <= 24 }
    }

    /** Whether [name] opens or closes a tab or grid, leaving [inTab] as it was for any other directive. */
    private fun chordWikiInTab(name: String, inTab: Boolean): Boolean = when (name) {
        "sot", "start_of_tab", "sog", "start_of_grid" -> true
        "eot", "end_of_tab", "eog", "end_of_grid" -> false
        else -> inTab
    }

    /**
     * One ChordPro line. A bracket that isn't a chord is dropped, not sung. A line of chords and
     * bars, with no letters left, keeps where each chord sat and has no words. Nothing at all is omitted.
     */
    private fun chordWikiLine(line: String): SheetLine? {
        val bare = line.replace("|", "")
        val body = bare.removePrefix(timeSignature.find(bare)?.value.orEmpty())
        val chords = mutableListOf<Chord>()
        val text = StringBuilder()
        var from = 0
        inline.findAll(body).forEach { match ->
            text.append(body, from, match.range.first)
            val name = match.groupValues[1].trim()
            if (chordWikiChord(name)) chords += Chord(text.length, name)
            from = match.range.last + 1
        }
        text.append(body.substring(from))
        val (words, placed) = withoutReadings(text.toString(), chords)
        if (placed.isEmpty() && words.none { it.isLetter() }) return null
        return SheetLine(if (words.any { it.isLetter() }) words else "", placed)
    }

    /**
     * [words] without the readings ChordWiki writes after a kanji, "川(かわ)", which are not sung, with
     * each of [chords] still before the letter it was. A chord inside a reading goes where the reading was.
     * Left in, they are letters the song never has, and a line that is mostly readings looks like another verse.
     */
    private fun withoutReadings(words: String, chords: List<Chord>): Pair<String, List<Chord>> {
        val ranges = reading.findAll(words).map { it.range }.toList()
        if (ranges.isEmpty()) return words to chords
        return reading.replace(words, "") to chords.map { chord ->
            val gone = ranges.sumOf { range -> (minOf(chord.at, range.last + 1) - range.first).coerceAtLeast(0) }
            chord.copy(at = chord.at - gone)
        }
    }

    /** [name] is a chord, including N.C., and not a word or a section such as Chorus or サビ. */
    private fun chordWikiChord(name: String): Boolean {
        if (name.equals("N.C.", ignoreCase = true) || name.equals("NC", ignoreCase = true)) return true
        if (name.length > 15 || !chordToken.matches(name)) return false
        return letterRun.findAll(name).all { chordWord.containsMatchIn(it.value) }
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
     * How [sheet] lines up with the words shown. [placed] is null when its words aren't this song,
     * or when none of its chords land on them. [lines] is each sheet line: how many of its letters
     * lined up, and which of its chords were drawn. A line under [GOOD_LINE] keeps a chord when the
     * line is too short to be another verse, or when the letter that chord is played on lined up.
     */
    internal data class ChordAlign(
        val placed: List<LineChords>?,
        val matchedLetters: Int,
        val letters: Int,
        val runs: Int,
        val lines: List<LineFit>,
    )

    /** One sheet line. [drawn] is the chords that landed, in the order they were on the page. */
    internal data class LineFit(val chords: List<String>, val letters: Int, val matched: Int, val drawn: List<String>)

    /**
     * The [sheet]'s chords over each of [texts], the lines shown; null when its words aren't these,
     * as for another song of the same name, and when none of its chords land on them, as for a page of
     * the words alone.
     */
    fun place(sheet: ChordSheet, texts: List<String>): List<LineChords>? = align(sheet, texts).placed

    /** [place], plus the letter match and which chords were left off a line. */
    fun align(sheet: ChordSheet, texts: List<String>): ChordAlign {
        val direct = matchLines(sheet, texts)
        if (direct.placed != null) return direct.copy(placed = direct.placed.takeUnless { lines -> lines.all { it.isEmpty } })
        val said = said(texts) ?: return direct
        val roman = matchLines(sheet, said)
        if (roman.placed == null) return direct
        val shown = romanizedChords(roman.placed, texts, said)
        return roman.copy(placed = shown.takeUnless { lines -> lines.all { it.isEmpty } })
    }

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

    /** Chords placed on the romanized words, moved back onto the letters that are shown. */
    private fun romanizedChords(placed: List<LineChords>, texts: List<String>, said: List<String>): List<LineChords> =
        placed.mapIndexed { number, line ->
            val length = texts[number].length
            val readLength = said[number].length.coerceAtLeast(1)
            LineChords(line.before, line.over.map { chord -> Chord((chord.at.toLong() * length / readLength).toInt().coerceIn(0, length), chord.name) })
        }

    /** The match of [sheet]'s letters to [texts]. [ChordAlign.placed] is every chord laid down, before a page of the words alone is told apart. */
    private fun matchLines(sheet: ChordSheet, texts: List<String>): ChordAlign {
        val lines = inScriptOf(sheet.lines, texts)
        val ours = Letters(texts)
        val theirs = Letters(lines.map { it.text })
        val fewer = minOf(theirs.size, ours.size)
        val unmatched = lines.map { LineFit(it.chords.map { chord -> chord.name }, 0, 0, emptyList()) }
        if (theirs.size < MIN_LETTERS || ours.size == 0) return ChordAlign(null, 0, fewer, 0, unmatched)
        val matchedAt = BorrowedTimings.lineUp(ours.keys, theirs.keys) ?: return ChordAlign(null, 0, fewer, 0, unmatched)
        val matched = matchedAt.count { it >= 0 }
        val runs = (1 until matchedAt.size).count { at -> matchedAt[at - 1] >= 0 && matchedAt[at] == matchedAt[at - 1] + 1 }
        val fits = lines.mapIndexed { number, line ->
            val letters = theirs.of(number)
            LineFit(line.chords.map { chord -> chord.name }, letters.size, letters.count { matchedAt[it] >= 0 }, emptyList())
        }
        if (matched < fewer * GOOD_LETTERS || runs < fewer * GOOD_RUNS) return ChordAlign(null, matched, fewer, runs, fits)
        return layChords(lines, texts, ours, theirs, matchedAt, ChordAlign(null, matched, fewer, runs, fits))
    }

    /**
     * The chords of [lines] over [texts]. A chord line with no words waits for the next one that is
     * kept, and what's still waiting after the last line is played at the end of it. A line under
     * [GOOD_LINE] keeps its chords when it has too few letters to be another verse. A longer one keeps
     * only a chord played on a letter that lined up.
     */
    private fun layChords(
        lines: List<SheetLine>,
        texts: List<String>,
        ours: Letters,
        theirs: Letters,
        matchedAt: IntArray,
        soFar: ChordAlign,
    ): ChordAlign {
        val before = Array(texts.size) { mutableListOf<String>() }
        val over = Array(texts.size) { mutableListOf<Chord>() }
        val waiting = mutableListOf<String>()
        val waitingLine = mutableListOf<Int>()
        // Not MutableList(size) { }: its counting loop is a change no test can see, as the one that counts backwards never ends.
        val landed = lines.mapTo(ArrayList<MutableList<String>>(lines.size)) { mutableListOf() }
        fun hold(line: SheetLine, number: Int) {
            line.chords.forEach { chord ->
                waiting += chord.name
                waitingLine += number
            }
        }
        // [chords] of this line. A kept line with none of its own still takes what was waiting on it.
        fun place(number: Int, letters: List<Int>, chords: List<Chord>) {
            if (letters.none { matchedAt[it] >= 0 } || (chords.isEmpty() && waiting.isEmpty())) return
            landWaiting(waiting, waitingLine, landed, before, texts, lineAt(letters, ours, matchedAt))
            chords.forEach { chord ->
                placeChord(chord, letters, theirs, ours, matchedAt)?.let { (at, char) ->
                    over[at] += Chord(char, chord.name)
                    landed[number] += chord.name
                }
            }
        }
        lines.forEachIndexed { number, line ->
            val letters = theirs.of(number)
            if (letters.isEmpty()) {
                hold(line, number)
            } else if (underLine(letters, matchedAt) && letters.size < MIN_LETTERS) {
                hold(line, number)
            } else if (underLine(letters, matchedAt)) {
                place(number, letters, line.chords.filter { onMatchedLetter(it, letters, theirs, matchedAt) })
            } else {
                place(number, letters, line.chords)
            }
        }
        landRest(waiting, waitingLine, landed, over, texts)
        val raw = texts.indices.map { LineChords(before[it], over[it].sortedBy { chord -> chord.at }) }
        val fits = soFar.lines.mapIndexed { index, fit -> fit.copy(drawn = landed[index]) }
        return soFar.copy(placed = raw, lines = fits)
    }

    /** A line with words, under [GOOD_LINE]: too little of it is this song to take the line as a whole. */
    private fun underLine(letters: List<Int>, matchedAt: IntArray): Boolean =
        letters.count { matchedAt[it] >= 0 } < letters.size * GOOD_LINE

    /** The letter [chord] is played on lined up with the words shown. */
    private fun onMatchedLetter(chord: Chord, letters: List<Int>, theirs: Letters, matchedAt: IntArray): Boolean {
        val on = letters.firstOrNull { theirs.char[it] >= chord.at }
        return matchedAt[on ?: letters.last()] >= 0
    }

    /** Chords still waiting once the lines have been read, drawn after the last line's own. */
    private fun landRest(
        waiting: MutableList<String>,
        waitingLine: MutableList<Int>,
        landed: MutableList<MutableList<String>>,
        over: Array<MutableList<Chord>>,
        texts: List<String>,
    ) {
        if (waiting.isEmpty()) return
        // Some line has letters, or nothing would have been matched, so there is a last line with words.
        val last = texts.indexOfLast { it.isNotBlank() }
        over[last] += waiting.map { name -> Chord(texts[last].length, name) }
        waiting.forEachIndexed { index, name -> landed[waitingLine[index]] += name }
    }

    private fun lineAt(letters: List<Int>, ours: Letters, matchedAt: IntArray): Int {
        val anchor = letters.first { matchedAt[it] >= 0 }
        return ours.line[matchedAt[anchor]]
    }

    private fun landWaiting(
        waiting: MutableList<String>,
        waitingLine: MutableList<Int>,
        landed: MutableList<MutableList<String>>,
        before: Array<MutableList<String>>,
        texts: List<String>,
        lineAt: Int,
    ) {
        if (waiting.isEmpty()) return
        val gap = lineAt - 1
        (if (gap >= 0 && texts[gap].isBlank() && before[gap].isEmpty()) before[gap] else before[lineAt]) += waiting
        waiting.forEachIndexed { index, name -> landed[waitingLine[index]] += name }
        waiting.clear()
        waitingLine.clear()
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
