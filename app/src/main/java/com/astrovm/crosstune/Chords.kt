package com.astrovm.crosstune

import org.json.JSONArray
import java.text.Normalizer

/** A chord played from letter [at] of a line. */
internal data class Chord(val at: Int, val name: String)

/** A line of a chord sheet: its words, and the chords over them; a line of chords alone has no words. */
internal data class SheetLine(val text: String, val chords: List<Chord>)

/** Where a song's chords were found. */
internal enum class ChordSite { U_FRET, ULTIMATE_GUITAR, LA_CUERDA, CIFRA_CLUB }

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
        val (root, accidental, rest, bass, bassAccidental) = solfegeChord.matchEntire(name)?.destructured ?: return name
        val letters = StringBuilder(solfege.getValue(root)).append(accidental).append(rest)
        if (bass.isNotEmpty()) letters.append('/').append(solfege.getValue(bass)).append(bassAccidental)
        return letters.toString()
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
            if (words != null && words.isNotBlank() && !ugChord.containsMatchIn(words) && !section.matches(words)) {
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
     * as for another song of the same name.
     */
    fun place(sheet: ChordSheet, texts: List<String>): List<LineChords>? = placeIn(sheet, texts) ?: placeRomanized(sheet, texts)

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
