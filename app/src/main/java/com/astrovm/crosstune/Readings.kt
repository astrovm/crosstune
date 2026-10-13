package com.astrovm.crosstune

import android.annotation.SuppressLint
import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import com.atilika.kuromoji.ipadic.Tokenizer

/** The writing systems whose words read differently from how they look, and so have readings shown. */
internal enum class Script { JAPANESE, CHINESE, KOREAN }

/** A piece of a line, with how it's read when that isn't plain from it, e.g. kanji with their kana. */
internal data class Ruby(val text: String, val reading: String? = null)

/**
 * How a line reads: in [parts], each piece with its reading over it; [reading] the whole line as
 * read, in kana or pinyin; and [romanized], in Latin letters.
 */
internal data class LineReading(val parts: List<Ruby>, val reading: String, val romanized: String)

/**
 * A word of a line: as written, how it reads when that isn't plain from it, and what to look up
 * for its meaning, e.g. 分かる for 分かった.
 */
internal data class Word(val text: String, val reading: String? = null, val romanized: String? = null, val lookup: String = text)

/** A reading put right: on line [line], from 0, [kanji] reads [reading], in hiragana. */
internal data class ReadingFix(val line: Int, val kanji: String, val reading: String)

/** Readings of lyrics in Japanese, Chinese and Korean, all worked out on the phone. */
internal object Readings {

    /** The script [lines] are in: any kana is Japanese, as Japanese mixes kana with kanji, which Chinese never does. */
    fun scriptOf(lines: List<String>): Script? {
        val text = lines.joinToString("")
        return when {
            text.any(::isKana) -> Script.JAPANESE
            text.any(::isHangul) -> Script.KOREAN
            text.any(::isHan) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Script.CHINESE
            else -> null
        }
    }

    /** How each of [lines] reads, in [script]. Japanese takes a moment the first time, to load its dictionary. */
    // Words are only ever Chinese from Android 10 on, see scriptOf.
    @SuppressLint("NewApi")
    fun of(lines: List<String>, script: Script): List<LineReading> = when (script) {
        Script.JAPANESE -> lines.map(::japanese)
        Script.CHINESE -> lines.map(::chinese)
        Script.KOREAN -> lines.map(::korean)
    }.zip(lines) { reading, line ->
        // A line already in Latin letters, as an English one in a Korean song, isn't written out again.
        if (letters(reading.romanized) == letters(line)) reading.copy(romanized = "") else reading
    }

    private fun letters(text: String) = text.filter(Char::isLetterOrDigit).lowercase()

    private val tokenizer by lazy { Tokenizer() }

    private fun japanese(line: String): LineReading {
        val pieces = together(pieces(line))
        val parts = pieces.flatMap { if (it.surface.any(::isHan) && it.kana != it.surface) furigana(it.surface, it.kana) else listOf(Ruby(it.surface)) }
        return LineReading(joined(parts), pieces.joinToString("") { it.kana }, romajiWords(pieces).joinToString(" ") { romaji(it) })
    }

    // Kanji in their old forms, as 燈 for 灯, read as the new ones the dictionary knows, and stay as written.
    private fun pieces(line: String): List<Piece> = tokenizer.tokenize(line.map { NEW_FORMS[it] ?: it }.joinToString("")).map { token ->
            // Words the dictionary doesn't know, e.g. English ones, have no reading: they read as written.
            Piece(
                line.substring(token.position, token.position + token.surface.length),
                token.reading?.takeIf { it != "*" }?.let(::hiragana) ?: token.surface,
                token.partOfSpeechLevel1,
                token.partOfSpeechLevel2,
                token.baseForm?.takeIf { it != "*" } ?: token.surface,
                token.pronunciation?.takeIf { it != "*" }?.let { drawnOutE(hiragana(it)) }
            )
        }

    /** Old forms of kanji, each before its new one, as lyrics keep them for their feel. */
    private val NEW_FORMS = ("燈灯戀恋櫻桜聲声體体國国學学氣気會会來来晝昼黑黒發発傳伝實実廣広獨独歸帰圓円驛駅舊旧" +
        "樂楽萬万與与爭争戰戦繪絵戲戯聽聴譯訳變変邊辺鐵鉄靜静顏顔惠恵濱浜擧挙寫写對対數数斷断曉暁歡歓歲歳殘残滿満" +
        "燒焼爲為畫画當当盡尽眞真禮礼經経綠緑總総續続處処號号螢蛍觀観讀読豐豊賣売輕軽辭辞醉酔黃黄齒歯淚涙")
        .chunked(2).associate { it[0] to it[1] }

    /** The dictionary draws out おう as おー, but not えい, as in せんせい, said せんせー. */
    private fun drawnOutE(kana: String) = kana.replace(Regex("([えけげせぜてでねへべぺめれ])い"), "$1ー")

    /**
     * A word as the dictionary splits a line, with how it reads and what part of speech it is, and
     * how it's [spoken], when that's known: 先生, read せんせい, is said せんせー.
     */
    private class Piece(val surface: String, val kana: String, val kind: String, val subkind: String, val base: String = surface, val spoken: String? = null) {
        /** How it's said, for romaji, so it's sung right: sensee, not sensei; the particles は and へ are wa and e. */
        val said get() = if (kind == "助詞") when (surface) { "は" -> "わ"; "へ" -> "え"; else -> kana } else spoken ?: kana
    }

    /**
     * Pieces the dictionary splits but that read as one, like 二人, ふたり rather than に and にん.
     * Kept to numbers of people, the ones lyrics are full of.
     */
    private fun together(pieces: List<Piece>): List<Piece> {
        val out = mutableListOf<Piece>()
        for (piece in pieces) {
            val last = out.lastOrNull()
            val word = last?.let { TOGETHER[it.surface + piece.surface] }
            if (word != null) out[out.lastIndex] = Piece(last.surface + piece.surface, word, "名詞", "一般") else out += piece
        }
        return out
    }

    private val TOGETHER = mapOf("一人" to "ひとり", "二人" to "ふたり")

    /**
     * [read] with [fixes] made: each kanji takes its new reading, on the line and in its kana and
     * romaji. A fix that also gives the kana written after the kanji, ごし for 越 in 越し, has
     * those left off; one that changes nothing, or names a kanji the line hasn't, is left out.
     */
    fun corrected(read: List<LineReading>, fixes: List<ReadingFix>): List<LineReading> = read.mapIndexed { index, line ->
        fixes.filter { it.line == index }.fold(line) { fixed, fix ->
            val at = fixed.parts.indexOfFirst { it.text == fix.kanji && it.reading != null }
            if (at < 0) return@fold fixed
            val old = fixed.parts[at].reading!!
            val after = hiragana(fixed.parts.drop(at + 1).joinToString("") { it.text })
            val wanted = hiragana(fix.reading).let { reading ->
                (reading.length - 1 downTo 1).firstOrNull { cut -> after.startsWith(reading.substring(cut)) }?.let { reading.substring(0, it) } ?: reading
            }
            if (wanted.isEmpty() || wanted == old || wanted.any { it !in 'ぁ'..'ゟ' }) return@fold fixed
            // Only where those kanji are: another word read the same, e.g. a く of 君 in くん, stays.
            val fixing = fixed.parts.indices.filter { fixed.parts[it].text == fix.kanji && fixed.parts[it].reading == old }
            val starts = fixed.parts.runningFold(0) { at, part -> at + (part.reading ?: part.text).length }
            val inReading = fixing.map { starts[it] }.filter { fixed.reading.startsWith(old, it) }
            // Which times the old reading comes in the line, counted as romaji counts them, are these.
            val times = Regex(Regex.escape(old)).findAll(fixed.reading).map { it.range.first }.toList()
            val nth = inReading.map(times::indexOf).filter { it >= 0 }.toSet()
            var seen = -1
            LineReading(
                fixed.parts.mapIndexed { at, part -> if (at in fixing) part.copy(reading = wanted) else part },
                inReading.sortedDescending().fold(fixed.reading) { line, at -> line.replaceRange(at, at + old.length, wanted) },
                // Romaji is as it's said, so the reading may be there drawn out, sensee for sensei.
                fixed.romanized.replace(Regex("""\b(${Regex.escape(drawnOut(romaji(old)))}|${Regex.escape(romaji(old))})""")) {
                    if (++seen !in nth) it.value else if (it.value == romaji(old)) romaji(wanted) else drawnOut(romaji(wanted))
                }
            )
        }
    }

    /** Romaji with its long vowels as they're said: ei and ou drawn out, as ee and oo. */
    private fun drawnOut(romaji: String) = romaji.replace("ei", "ee").replace("ou", "oo")

    /**
     * The words of a line in kana, as romaji spaces them: a verb with its endings is one word, e.g.
     * 分かった is wakatta rather than waka ta, while particles stand alone. Brackets and marks sit
     * against what they're beside.
     */
    private fun romajiWords(pieces: List<Piece>): List<String> = grouped(pieces).map { word -> word.joinToString("") { it.said } }

    /** [pieces] in words, as [romajiWords] spaces them. */
    private fun grouped(pieces: List<Piece>): List<List<Piece>> {
        val words = mutableListOf<MutableList<Piece>>()
        var previous: Piece? = null
        var opened = false
        for (piece in pieces) {
            if (piece.surface.isBlank()) {
                previous = null
                continue
            }
            val opening = piece.surface in OPENING
            val ending = piece.kind == "助動詞" || (piece.kind == "助詞" && piece.subkind == "接続助詞") ||
                (piece.kind == "動詞" && piece.subkind == "非自立") || piece.subkind == "接尾"
            // An ending after a particle starts a word of its own: だけだった is dake datta. English
            // the dictionary splits, "Don't" into Don, ' and t, stays as written.
            val english = previous != null && isEnglish(previous.surface) && isEnglish(piece.surface)
            val attaches = previous != null && (opened || english || (piece.kind == "記号" && !opening) || (ending && previous.kind != "助詞"))
            if (attaches) words.last() += piece else words += mutableListOf(piece)
            opened = opening
            previous = piece
        }
        return words
    }

    /** Latin letters, digits and apostrophes, as English is written in Japanese lyrics. */
    private fun isEnglish(text: String) = text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '\'' || it == '’' }

    /**
     * The words of [line], to look up one by one: Japanese as its dictionary splits it, with each
     * word's kana and the form it's listed under; Chinese, which has no spaces, as Android's own
     * dictionary splits it, with its pinyin; anything else between its spaces.
     */
    // Words are only ever Chinese from Android 10 on, see scriptOf.
    @SuppressLint("NewApi")
    fun words(line: String, script: Script?): List<Word> = when (script) {
        Script.JAPANESE -> grouped(together(pieces(line))).mapNotNull { word ->
            val text = word.joinToString("") { it.surface }.trim { !it.isLetterOrDigit() }
            if (text.none(Char::isLetter)) return@mapNotNull null
            val kana = word.joinToString("") { it.kana }.trim { !it.isLetterOrDigit() }
            // Kana reads as written; the reading's only worth showing for kanji.
            val said = word.joinToString("") { it.said }.trim { !it.isLetterOrDigit() }
            Word(text, kana.takeIf { it != text }, romaji(said), lookup = word.first { it.kind != "記号" }.base)
        }
        else -> segments(line).map { text ->
            when (script) {
                Script.CHINESE -> chinese(text).let { Word(text, it.reading, null) }
                Script.KOREAN -> Word(text, null, romaja(text))
                else -> Word(text)
            }
        }
    }

    /** The words between [line]'s spaces and marks, or, in Chinese, between its words. */
    private fun segments(line: String): List<String> {
        val breaks = android.icu.text.BreakIterator.getWordInstance()
        breaks.setText(line)
        val words = mutableListOf<String>()
        var start = breaks.first()
        var end = breaks.next()
        while (end != android.icu.text.BreakIterator.DONE) {
            line.substring(start, end).takeIf { it.any(Char::isLetter) }?.let(words::add)
            start = end
            end = breaks.next()
        }
        return words
    }

    private const val OPENING = "「『（(【〈《“"

    /** Kana over only the kanji of [surface]: the kana it starts or ends with read as written. */
    private fun furigana(surface: String, kana: String): List<Ruby> {
        val tail = surface.reversed().zip(kana.reversed()).takeWhile { (s, k) -> s == k && isKana(s) }.size
        val head = surface.zip(kana).takeWhile { (s, k) -> s == k && isKana(s) }.size
        val core = surface.substring(head, surface.length - tail)
        val coreReading = kana.substring(head, kana.length - tail)
        return listOfNotNull(
            surface.take(head).takeIf { it.isNotEmpty() }?.let { Ruby(it) },
            Ruby(core, coreReading),
            surface.takeLast(tail).takeIf { it.isNotEmpty() }?.let { Ruby(it) }
        )
    }

    private var transliterator: Transliterator? = null

    /** Android's own pinyin, made once, the first time it's needed. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun pinyin(): Transliterator = transliterator ?: Transliterator.getInstance("Han-Latin").also { transliterator = it }

    private var simplifier: Transliterator? = null

    /** [text] in simplified characters, "小幸運" as "小幸运", as sites write the same Chinese title either way. Unchanged before Android 10. */
    @SuppressLint("NewApi")
    fun simplified(text: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || text.none(::isHan)) return text
        return synchronized(this) { (simplifier ?: Transliterator.getInstance("Hant-Hans").also { simplifier = it }).transliterate(text) }
    }

    /** One line at a time, as Android's transliterator can't be used from two threads at once. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun chinese(line: String): LineReading = synchronized(this) {
        val parts = line.map { char -> if (isHan(char)) Ruby(char.toString(), pinyin().transliterate(char.toString())) else Ruby(char.toString()) }
        val romanized = pinyin().transliterate(line).trim()
        LineReading(joined(parts), romanized, romanized)
    }

    private fun korean(line: String): LineReading {
        val romanized = romaja(line)
        return LineReading(listOf(Ruby(line)), romanized, romanized)
    }

    /** Neighboring pieces read as written join into one. */
    private fun joined(parts: List<Ruby>): List<Ruby> = parts.fold(mutableListOf()) { joined, part ->
        val last = joined.lastOrNull()
        if (last != null && last.reading == null && part.reading == null) {
            joined[joined.lastIndex] = Ruby(last.text + part.text)
        } else {
            joined += part
        }
        joined
    }

    fun isKana(c: Char) = c in 'ぁ'..'ゟ' || c in '゠'..'ヿ'
    private fun isHangul(c: Char) = c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'
    fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々'

    /** Katakana as hiragana, which is how furigana are written. */
    fun hiragana(katakana: String): String = katakana.map { if (it in 'ァ'..'ヶ') it - 0x60 else it }.joinToString("")

    /** Kana in Latin letters, the Hepburn way: しゃ is sha, っ doubles what follows, ー lengthens the vowel before. */
    fun romaji(kana: String): String {
        val hira = hiragana(kana)
        val out = StringBuilder()
        var double = false
        var afterN = false
        var i = 0
        while (i < hira.length) {
            val two = if (i + 1 < hira.length) hira.substring(i, i + 2) else null
            val syllable = two?.let(YOUON::get)?.also { i += 2 } ?: KANA[hira[i]]?.also { i++ }
            if (syllable == null) {
                when (val c = hira[i]) {
                    'っ' -> double = true
                    'ー' -> out.lastOrNull { it in "aeiou" }?.let(out::append)
                    else -> out.append(c)
                }
                afterN = false
                i++
                continue
            }
            if (double) {
                out.append(if (syllable.startsWith("ch")) 't' else syllable[0])
                double = false
            }
            // ん before a vowel or y is n', so it isn't read with them.
            if (afterN && syllable[0] in "aeiouy") out.append('\'')
            afterN = hira[i - 1] == 'ん'
            out.append(syllable)
        }
        return out.toString()
    }

    private val KANA: Map<Char, String> = buildMap {
        val rows = listOf(
            "あいうえお" to listOf("a", "i", "u", "e", "o"),
            "かきくけこ" to listOf("ka", "ki", "ku", "ke", "ko"),
            "がぎぐげご" to listOf("ga", "gi", "gu", "ge", "go"),
            "さしすせそ" to listOf("sa", "shi", "su", "se", "so"),
            "ざじずぜぞ" to listOf("za", "ji", "zu", "ze", "zo"),
            "たちつてと" to listOf("ta", "chi", "tsu", "te", "to"),
            "だぢづでど" to listOf("da", "ji", "zu", "de", "do"),
            "なにぬねの" to listOf("na", "ni", "nu", "ne", "no"),
            "はひふへほ" to listOf("ha", "hi", "fu", "he", "ho"),
            "ばびぶべぼ" to listOf("ba", "bi", "bu", "be", "bo"),
            "ぱぴぷぺぽ" to listOf("pa", "pi", "pu", "pe", "po"),
            "まみむめも" to listOf("ma", "mi", "mu", "me", "mo"),
            "やゆよ" to listOf("ya", "yu", "yo"),
            "らりるれろ" to listOf("ra", "ri", "ru", "re", "ro"),
            "わをん" to listOf("wa", "o", "n"),
            "ぁぃぅぇぉ" to listOf("a", "i", "u", "e", "o"),
            "ゃゅょゎ" to listOf("ya", "yu", "yo", "wa"),
            "ゔ" to listOf("vu")
        )
        rows.forEach { (kana, latin) -> kana.forEachIndexed { index, c -> put(c, latin[index]) } }
    }

    private val YOUON: Map<String, String> = buildMap {
        val small = listOf('ゃ' to "a", 'ゅ' to "u", 'ょ' to "o")
        listOf("き" to "ky", "ぎ" to "gy", "し" to "sh", "じ" to "j", "ち" to "ch", "ぢ" to "j", "に" to "ny", "ひ" to "hy", "び" to "by", "ぴ" to "py", "み" to "my", "り" to "ry")
            .forEach { (kana, start) -> small.forEach { (s, vowel) -> put(kana + s, start + vowel) } }
        put("ふぁ", "fa"); put("ふぃ", "fi"); put("ふぇ", "fe"); put("ふぉ", "fo")
        put("てぃ", "ti"); put("でぃ", "di"); put("うぃ", "wi"); put("うぇ", "we"); put("ゔぁ", "va")
    }

    /** Hangul in Latin letters, the Revised Romanization way, syllable by syllable. */
    fun romaja(hangul: String): String = hangul.map { c ->
        if (c !in '가'..'힣') return@map c.toString()
        val index = c - '가'
        INITIALS[index / (21 * 28)] + MEDIALS[(index % (21 * 28)) / 28] + FINALS[index % 28]
    }.joinToString("")

    private val INITIALS = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val MEDIALS = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val FINALS = listOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
}
