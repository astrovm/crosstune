package com.astrovm.crosstune

import android.annotation.SuppressLint
import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import com.atilika.kuromoji.ipadic.Tokenizer
import kotlin.math.abs

/**
 * The writing systems that aren't Latin, so their words can be shown in Latin letters. Each has
 * the [glyph] that stands for it in the words menu, a letter of its own.
 */
internal enum class Script(val glyph: String) {
    JAPANESE("日"), CHINESE("中"), KOREAN("한"), CYRILLIC("Я"), GREEK("Ω"), ARABIC("ع"), HEBREW("א"), INDIC("अ"), THAI("ก")
}

/**
 * What gave a word its reading, as a report on a wrong one names it by [key]: for Japanese, the
 * dictionaries on the phone, IPADIC and UniDic, JMdict's words of more than one kanji, Crosstune's own rules (二人 as ふたり, 側 as そば),
 * the lyrics' own reading in brackets, UtaTen's sung readings, ChatGPT's, or the user's own on a
 * kept line; for Chinese, Android's pinyin; for Korean, Crosstune's romaja; for the rest, Android's
 * Latin letters.
 */
internal enum class Reader(val key: String) {
    IPADIC("ipadic"), UNIDIC("unidic"), JMDICT("jmdict"), RULES("crosstune_rules"), LYRICS("lyrics_ruby"), SUNG("utaten"), CHATGPT("chatgpt"), USER("user"),
    PINYIN("android_pinyin"), ROMAJA("crosstune_romaja"), LATIN("android_latin");

    companion object {
        /** The reader named [key], as kept on disk; null for one this version doesn't know. */
        fun of(key: String): Reader? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A piece of a line, with how it's read when that isn't plain from it, e.g. kanji with their kana;
 * [by] whatever put that reading right, when something did after the line was read.
 */
internal data class Ruby(val text: String, val reading: String? = null, val by: Reader? = null)

/**
 * How a line reads: in [parts], each piece with its reading over it; [reading] the whole line as
 * read, in kana or pinyin; and [romanized], in Latin letters, as it's said. Japanese has them
 * [typed] too, as a keyboard spells them: sensei, not sensee, and を as wo.
 */
internal data class LineReading(val parts: List<Ruby>, val reading: String, val romanized: String, val typed: String = romanized) {
    /** Its kanji in kana, and the rest as written, katakana and all; not in parts, its reading. */
    val kana: String get() = if (parts.isEmpty()) reading else parts.kana

    /** Its Latin letters as it's said, or as it's [typed]. */
    fun latin(typed: Boolean) = if (typed) this.typed else romanized
}

/** It with its katakana in hiragana, when [on]: どあ for ドア. */
internal fun String.inHiragana(on: Boolean): String =
    if (!on) this else map { if (it in 'ァ'..'ヶ') it - KATAKANA_TO_HIRAGANA else it }.joinToString("")

/** How far katakana sits from the same hiragana in Unicode, ア to あ. */
private const val KATAKANA_TO_HIRAGANA = 0x60

/** The parts' kanji in kana, and the rest as written, katakana and all. */
internal val List<Ruby>.kana: String get() = joinToString("") { it.reading ?: it.text }

/**
 * A word of a line: as written, how it reads when that isn't plain from it, and what to look up
 * for its meaning, e.g. 分かる for 分かった; its Latin letters as it's said, and as it's [typed];
 * what read it, [by], the first what read its kanji; and [modern], how it's spelled today when it's written in old kana.
 */
internal data class Word(
    val text: String,
    val reading: String? = null,
    val romanized: String? = null,
    val lookup: String = text,
    val typed: String? = romanized,
    val by: List<Reader> = emptyList(),
    /** How it's spelled today, when it's written in old kana, 思い出 for 思ひ出; the lyric keeps what it says. */
    val modern: String? = null
)

/**
 * Romaji as it's [said] and as it's [typed], the one picked, typed when [typedFirst], first; the
 * other only where it's spelled otherwise, sensee and sensei, but wa once.
 */
internal fun bothRomaji(said: String?, typed: String?, typedFirst: Boolean): List<String> =
    (if (typedFirst) listOf(typed, said) else listOf(said, typed)).filterNotNull().filter(String::isNotBlank).distinct()

/** How a word reads as its details show it: its reading, then its romaji as said and typed, the typed first when [typedFirst]. */
internal fun Word.shown(typedFirst: Boolean): List<String> = (listOfNotNull(reading) + bothRomaji(romanized, typed, typedFirst)).distinct()

/** A reading put right [by] something: on line [line], from 0, [kanji] reads [reading], in hiragana. */
internal data class ReadingFix(val line: Int, val kanji: String, val reading: String, val by: Reader)

/** Readings of lyrics in Japanese, Chinese and Korean, and Latin letters for other scripts, all worked out on the phone. */
internal object Readings {

    /**
     * Bumped when words are read otherwise than before, so the readings kept with a song's words are
     * worked out again: 2 for the readings a song is sung with, which were asked for in a way that found none,
     * and the dictionary's common misreadings, as 君 after a pause; 4 for counters, minutes, classical
     * verbs, the nouns IPADIC takes for suffixes, and the words JMdict reads one way only; 5 for words in old kana
     * alone that IPADIC splits, とりあふ and おもひで, modern words in kana that only looked old, さいふ said as written, 何分 as なんぷん,
     * 洒落る, and numbers that start a longer word, 一重項.
     */
    const val VERSION = 5

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

    /** The script [lines] show in, which also has any other script most of the letters are in, as Android 10 on can write those in Latin letters. */
    fun displayScriptOf(lines: List<String>): Script? = scriptOf(lines) ?: mostlyIn(lines.joinToString("")).takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q }

    /** The other script most of [text]'s letters are in, so a Russian word in an English song doesn't make it Russian. */
    private fun mostlyIn(text: String): Script? {
        val letters = text.filter(Char::isLetter)
        val (script, count) = letters.groupingBy(::scriptOfLetter).eachCount().filterKeys { it != null }.maxByOrNull { it.value } ?: return null
        return script.takeIf { count * 2 > letters.length }
    }

    private fun scriptOfLetter(c: Char): Script? = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.CYRILLIC -> Script.CYRILLIC
        Character.UnicodeScript.GREEK -> Script.GREEK
        Character.UnicodeScript.ARABIC -> Script.ARABIC
        Character.UnicodeScript.HEBREW -> Script.HEBREW
        Character.UnicodeScript.THAI -> Script.THAI
        Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.GUJARATI,
        Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM -> Script.INDIC
        else -> null
    }

    /** How each of [lines] reads, in [script]. Japanese takes a moment the first time, to load its dictionary. */
    // Words are only ever Chinese from Android 10 on, see scriptOf.
    @SuppressLint("NewApi")
    fun of(lines: List<String>, script: Script): List<LineReading> = when (script) {
        Script.JAPANESE -> lines.map(::japanese)
        Script.CHINESE -> lines.map(::chinese)
        Script.KOREAN -> lines.map(::korean)
        else -> lines.map(::latinized)
    }.zip(lines) { reading, line ->
        // A line already in Latin letters, as an English one in a Korean song, isn't written out again.
        if (letters(reading.romanized) == letters(line)) reading.copy(romanized = "", typed = "") else reading
    }

    private fun letters(text: String) = text.filter(Char::isLetterOrDigit).lowercase()

    private val tokenizer by lazy { Tokenizer() }

    private fun japanese(line: String): LineReading {
        val given = givenReadings(line)
        val pieces = readAsGiven(together(pieces(withoutGiven(line))), given)
        val parts = pieces.flatMap { piece ->
            if (piece.surface.none(::isHan) || piece.kana == piece.surface) return@flatMap listOf(Ruby(piece.surface))
            // A reading the lyrics give for kanji with kana among them, 不本意な現状 as いま, goes over all of it.
            if (piece.reader == Reader.LYRICS) laidOver(piece.surface, piece.kana) else furigana(piece.surface, piece.kana)
        }
        return LineReading(
            joined(withGiven(parts, given)),
            pieces.joinToString("") { it.kana },
            romanizedLine(pieces, typed = false),
            romanizedLine(pieces, typed = true)
        )
    }

    /** UniDic, once it's on the phone: Japanese is read with it too, where it knows better than IPADIC. */
    @Volatile
    var uniDic: com.atilika.kuromoji.unidic.Tokenizer? = null

    // Kanji in their old forms, as 燈 for 灯, read as the new ones the dictionary knows, and a digit
    // counting something, as 2 in 2人, as its kanji, 二人 ふたり, not a number on its own: they stay as written.
    private fun pieces(line: String): List<Piece> {
        val text = line.mapIndexed { at, c -> NEW_FORMS[c] ?: KANJI_DIGITS[c]?.takeIf { counts(line, at) } ?: c }.joinToString("")
        val known = okurigana(line, text, ipadicPieces(line, text))
        return saidAsWritten(modernPieces(uniDic?.let { mixed(known, uniDicPieces(it, line, text)) } ?: known))
    }

    /** [line] as IPADIC splits [text], the line with its kanji made the dictionary's. */
    private fun ipadicPieces(line: String, text: String) = tokenizer.tokenize(text).map { token ->
            // Words the dictionary doesn't know, e.g. English ones, have no reading: they read as written.
            val reading = token.reading?.takeIf { it != "*" }
            Piece(
                line.substring(token.position, token.position + token.surface.length),
                reading?.let(::hiragana) ?: token.surface,
                token.partOfSpeechLevel1,
                token.partOfSpeechLevel2,
                token.baseForm?.takeIf { it != "*" } ?: token.surface,
                token.pronunciation?.takeIf { it != "*" }?.let { drawnOutE(hiragana(it)) },
                token.position + token.surface.length,
                unread = reading == null
            )
        }

    /**
     * [pieces] of the line, where it writes the kana after a kanji in katakana, 夜ニ紛レ or 届カナイ,
     * and the dictionary can't read them so: read as if they were in hiragana, 夜に紛れ and 届かない.
     * Only there: a word in katakana after a kanji, 夏アイス, is one the dictionary knows, and stays.
     */
    private fun okurigana(line: String, text: String, pieces: List<Piece>): List<Piece> {
        if (!katakanaAfterKanji.containsMatchIn(text)) return pieces
        val hiraganaText = katakanaAfterKanji.replace(text) { it.value.take(1) + hiragana(it.value.drop(1)) }
        val theirs = ipadicPieces(line, hiraganaText)
        val out = mutableListOf<Piece>()
        var i = 0
        var j = 0
        while (i < pieces.size && j < theirs.size) {
            val a = mutableListOf(pieces[i++])
            val b = mutableListOf(theirs[j++])
            while (a.last().end != b.last().end) if (a.last().end < b.last().end) a += pieces[i++] else b += theirs[j++]
            // Theirs, once it can read the kana, replaces ours, which still can't.
            out += if (a.any(Piece::missedKana) && b.none(Piece::missedKana)) b else a
        }
        return out
    }

    /** A kanji and the katakana written right after it. */
    private val katakanaAfterKanji = Regex("""[\p{IsHan}々][\p{IsKatakana}&&[^ー・]]+""")

    /**
     * Words in old kana the dictionary splits (云 and ふ, け and ふ) or reads as written (いふ, 云ふ read うん), read as the
     * words their modern spelling is: the pieces of a word, [Furigana.byRule] spelled today, are tokenized as the words
     * they are today, and those words, each as it's written in the line, replace them. A spelling counts only when
     * the dictionary knows all its words and no ひ, ふ or へ it changed starts one, so a modern line, with は and ひ and ふ
     * in it, comes out as it was.
     */
    private fun modernPieces(pieces: List<Piece>): List<Piece> {
        val line = pieces.joinToString("") { it.surface }
        val out = mutableListOf<Piece>()
        var at = 0
        var from = 0
        while (at < pieces.size) {
            val counts = minOf(OLD_WORD_PIECES, pieces.size - at) downTo 1
            val found = counts.firstNotNullOfOrNull { count -> modernPiece(pieces.subList(at, at + count), line, from)?.let { count to it } }
                ?: counts.firstNotNullOfOrNull { count -> kanaToday(pieces.subList(at, at + count), line, from)?.let { count to it } }
            val taken = found?.first ?: 1
            from += pieces.subList(at, at + taken).sumOf { it.surface.length }
            if (found == null) out += pieces[at] else out += found.second
            at += taken
        }
        return out
    }

    /**
     * A [word] in old kana written in kana alone, at [at] in [line], that IPADIC splits into words, とり and あふ, あ and ひたい,
     * おも and ひで, read as the word it is today: each ひ and ふ after its first kana said い and う, as the old endings of verbs
     * and the nouns made of them are, とりあう, あいたい, おもいで. It counts when IPADIC reads that as one word, with each
     * change inside it or ending a verb that ends in う today, as the old verbs in ふ do; or when it is a common word in kana
     * JMdict has, as IPADIC knows おもいで only in kanji. Never where a word written with that ひ or ふ today is, おひる or くふう.
     */
    private fun kanaToday(word: List<Piece>, line: String, at: Int): List<Piece>? {
        val text = word.joinToString("") { it.surface }
        val changed = text.indices.filter { it > 0 && text[it] in KANA_SAID }
        if (word.size < 2 || changed.isEmpty() || !Furigana.inOldKana(text)) return null
        // A word starts with no particle: は and ひく in あとへはひく are no はいく.
        if (word[0].kind in GRAMMAR || grouped(word).size < 2 || changed.any { writtenToday(line, at + it) }) return null
        val today = text.mapIndexed { i, c -> if (i in changed) KANA_SAID.getValue(c) else c }.joinToString("")
        return knownToday(text, today, changed)
            ?: oldKana.today[today]?.let { lookup -> listOf(Piece(text, today, "名詞", "一般", lookup, today, reader = Reader.JMDICT, today = today)) }
    }

    /** How the old endings ひ and ふ are said in a word in kana alone. */
    private val KANA_SAID = mapOf('ひ' to 'い', 'ふ' to 'う')

    /**
     * Whether the kana at [at] in [line] is in a word written so today, おひる or くふう, as JMdict has them, that reaches
     * out of [word]: a longer word than the one the old kana would be in, かいひ more than いひ. Any such word, with no [word].
     */
    private fun writtenToday(line: String, at: Int, word: IntRange? = null): Boolean {
        val longest = oldKana.longest
        return (maxOf(0, at - longest + 1)..at).any { from ->
            (at + 1..minOf(line.length, from + longest)).any { to -> line.substring(from, to) in oldKana.modern && (word == null || from < word.first || to > word.last + 1) }
        }
    }

    /**
     * The word IPADIC reads [today], the spelling today of [text] with its [changed] kana, as [text] writes it: each change
     * inside a word, or ending a verb in う, as the old verbs in ふ do, or a noun, as 割合ひ does. Never ending an adjective,
     * whose い was never ひ: ふるひ is 降る日, not 古い. And no name, which IPADIC makes of kana it can't read otherwise.
     */
    private fun knownToday(text: String, today: String, changed: List<Int>): List<Piece>? {
        val tokens = tokenizer.tokenize(today)
        if (tokens.any { !it.isKnown || it.reading == "*" }) return null
        if (tokens.any { it.position in changed || it.partOfSpeechLevel2 == "固有名詞" }) return null
        val endings = changed.all { at ->
            val token = tokens.first { at < it.position + it.surface.length }
            val ends = at + 1 == token.position + token.surface.length
            !ends || token.partOfSpeechLevel1 in NOUNS || (token.partOfSpeechLevel1 == "動詞" && "ワ行" in token.conjugationType)
        }
        val spelling = Furigana.Today(today, text.indices.toList(), changed, emptyList(), changed.first())
        return tokens.map { todayPiece(listOf(it), spelling, text) }.takeIf { endings && grouped(it).size == 1 }
    }

    /** What a noun made of an old verb is, as IPADIC names it: うたがひ is a noun, わりあひ an adverb. */
    private val NOUNS = setOf("名詞", "副詞")

    /** Held while [listed] or [oldKana] is read from its file, so two threads that ask at once read it once. */
    private val tables = Any()

    /** Words in kana from JMdict: those a word in old kana alone may be [today], each with the form it's looked up by, and those written with a ひ or ふ today, [modern]. */
    internal class OldKana(val today: Map<String, String>, val modern: Set<String>) {
        val longest = modern.maxOf { it.length }
    }

    /**
     * [OldKana], from [OLD_KANA_WORDS]: made by `scripts/readingsaudit`, see CONTRIBUTING.md. Read once, as [oldKana],
     * when a line first needs it, whatever threads ask at once; null to read it again.
     */
    @Volatile
    internal var oldKanaRead: OldKana? = null

    internal val oldKana: OldKana
        get() = oldKanaRead ?: synchronized(tables) { oldKanaRead ?: readOldKana().also { oldKanaRead = it } }

    private fun readOldKana(): OldKana = checkNotNull(Readings::class.java.getResourceAsStream(OLD_KANA_WORDS)).bufferedReader().useLines { lines ->
        val (today, modern) = lines.filterNot { it.startsWith('#') }.partition { '\t' in it }
        OldKana(today.associate { it.substringBefore('\t') to it.substringAfter('\t') }, modern.toSet())
    }

    private const val OLD_KANA_WORDS = "/com/astrovm/crosstune/old-kana.txt"

    /** The pieces of a [word] in old kana, at [at] in [line], as the words it is today, when the dictionary knows them. */
    private fun modernPiece(word: List<Piece>, line: String, at: Int): List<Piece>? {
        val today = ruledToday(word) ?: return null
        val tokens = tokenizer.tokenize(today.text)
        // A word starting with a ひ, ふ or へ that was changed is a modern word: 一人ひとり is not 一人いとり.
        if (tokens.any { !it.isKnown || it.reading == "*" || it.position in today.plain }) return null
        // And so is a contracted pair that ends in the start of a longer word, て and ふたり in 来てふたり.
        val starts = word.runningFold(0) { at, piece -> at + piece.surface.length }
        if (today.contracted.any { pair -> word.indices.any { starts[it] == pair + 1 && word[it].surface.length > 1 } }) return null
        // And so is a word JMdict writes so today that holds the change and more: かいひ, いふう and てふだ are no かいい, いうう or ちょうだ.
        val text = word.joinToString("") { it.surface }
        val ends = tokens.map { it.position + it.surface.length }.map { if (it == today.text.length) text.length else today.origins[it] }
        val changed = today.plain.map(today.origins::get) + today.contracted.map { it + 1 }
        if (changed.any { change -> tokens.indices.any { i -> today.origins[tokens[i].position] <= change && change < ends[i] && writtenToday(line, at + change, at + today.origins[tokens[i].position] until at + ends[i]) } }) return null
        // A contracted pair stays whole: けふ is not き and ょう, and てふてふ is not ちょうちょ and う, but ちょうちょう.
        val words = tokens.fold(mutableListOf<MutableList<com.atilika.kuromoji.ipadic.Token>>()) { words, token ->
            if (token.position > 0 && today.origins[token.position] == today.origins[token.position - 1]) words.last() += token else words += mutableListOf(token)
            words
        }
        return words.map { group -> todayPiece(group, today, text) }
    }

    /** The spelling by rule of a [word] in old kana, when the dictionary doesn't already read it so and the change starts in its first piece. */
    private fun ruledToday(word: List<Piece>): Furigana.Today? {
        val text = word.joinToString("") { it.surface }
        val first = word[0]
        val kana = word.all { piece -> piece.surface.all { isHan(it) || isKana(it) } && piece.kind != "記号" }
        // One piece the dictionary already reads as old kana, 思ふ as おもう, is left as it is.
        val read = word.size == 1 && first.kana != text && spelledToday(text, first.kana, first.said) != null
        if (!kana || read) return null
        val today = Furigana.byRule(text) ?: return null
        // The change is in the first piece, or just after it, as ゐ is after ま in まゐる: but not after a particle, in 待つてゐた.
        val late = today.first > first.surface.length || (today.first == first.surface.length && first.kind in GRAMMAR)
        return today.takeUnless { late }
    }

    private val GRAMMAR = setOf("助詞", "助動詞")

    /** The piece for a [group] of tokens of the spelling [today] of [text], as it's written in the line. */
    private fun todayPiece(group: List<com.atilika.kuromoji.ipadic.Token>, today: Furigana.Today, text: String): Piece {
        val head = group.first()
        val from = today.origins[head.position]
        val to = group.last().let { it.position + it.surface.length }.let { if (it == today.text.length) text.length else today.origins[it] }
        return Piece(
            text.substring(from, to), group.joinToString("") { hiragana(it.reading) }, head.partOfSpeechLevel1, head.partOfSpeechLevel2,
            head.surface, longVowels(hiragana(group.joinToString("") { it.pronunciation })),
            today = group.joinToString("") { it.surface }
        )
    }

    /** The most pieces a word in old kana is split into: 思 ひ 出, 植 ゑ る, て ふ て ふ. */
    private const val OLD_WORD_PIECES = 5

    /** [line] as UniDic splits it. */
    private fun uniDicPieces(uniDic: com.atilika.kuromoji.unidic.Tokenizer, line: String, text: String) = uniDic.tokenize(text).map { token ->
        val surface = line.substring(token.position, token.position + token.surface.length)
        // UniDic reads Latin letters too, "eh" as え and エイチ, which stay as written.
        val said = token.pronunciation.takeIf { it != "*" && surface.any { isHan(it) || isKana(it) } }
        Piece(
            surface,
            if (said != null && surface.any(::isHan)) spelled(said, token.lemmaReadingForm) else hiragana(surface),
            token.partOfSpeechLevel1,
            // A verb that can follow another, 続ける in 見続ける, as IPADIC names it, so it's one word with it.
            if (token.partOfSpeechLevel2 == "非自立可能" && token.partOfSpeechLevel1 == "動詞") "非自立" else token.partOfSpeechLevel2,
            token.writtenBaseForm.takeIf { it != "*" } ?: surface,
            said?.let(::hiragana),
            token.position + token.surface.length,
            unread = said == null,
            reader = Reader.UNIDIC
        )
    }

    /**
     * The kana a word UniDic gives as it's [said], ちょーじょー, is spelled with, ちょうじょう: each
     * long vowel as the word's dictionary form, [listed], spells it there, or else as it most often
     * is, こう more than こお; and づ and ぢ, said as ず and じ, where it spells them so.
     */
    private fun spelled(said: String, listed: String): String {
        val spelling = hiragana(listed)
        val out = StringBuilder()
        hiragana(said).forEachIndexed { at, kana ->
            val there = spelling.getOrElse(at) { ' ' }
            out.append(
                when {
                    kana == 'ー' && out.isNotEmpty() -> LONG[romaji(out.last().toString()).lastOrNull()]?.let { if (there in it) there else it[0] } ?: kana
                    (kana == 'ず' && there == 'づ') || (kana == 'じ' && there == 'ぢ') -> there
                    else -> kana
                }
            )
        }
        return out.toString()
    }

    /** The kana a long vowel is spelled with after each vowel, the likelier first. */
    private val LONG = mapOf('a' to "あ", 'i' to "い", 'u' to "う", 'e' to "いえ", 'o' to "うお")

    /**
     * The line as IPADIC splits it, with UniDic's words where it knows better: kanji IPADIC has no
     * reading for, 溢 or 踠; a word IPADIC breaks up, 微笑み into 微 and 笑み; and a word IPADIC
     * takes for a prefix or a suffix to nothing, 君 read くん after a space. Elsewhere IPADIC reads
     * lyrics better: 私 わたし, where UniDic has わたくし, and 不器用 as a word of its own.
     */
    private fun mixed(ours: List<Piece>, theirs: List<Piece>): List<Piece> {
        val out = mutableListOf<Piece>()
        var i = 0
        var j = 0
        while (i < ours.size && j < theirs.size) {
            val before = ours.getOrNull(i - 1)
            // The pieces of each up to the next place both split the line.
            val a = mutableListOf(ours[i++])
            val b = mutableListOf(theirs[j++])
            while (a.last().end != b.last().end) if (a.last().end < b.last().end) a += ours[i++] else b += theirs[j++]
            val better = a.any { it.unknown } || keptWhole(a, b) || (a.size == 1 && b.size == 1 && (a[0].strayAffix(before) || nounNotAffix(a[0], b[0], before)))
            out += if (better && b.none { it.unknown }) b else a
        }
        return out
    }

    /**
     * Whether a kanji IPADIC takes for a suffix to the noun before it, [ours], is the plain noun UniDic
     * reads, [theirs]: 心 after ざわめき is こころ, not しん. Not after a number, where it counts, 三人; and
     * not when it's said with a voice, as the second half of a word, ガラス玉 がらすだま or 一番星 いちばんぼし,
     * unless the word before is one it can't be part of, as いつか in いつか日.
     */
    private fun nounNotAffix(ours: Piece, theirs: Piece, before: Piece?): Boolean {
        val plainNoun = theirs.kind == "名詞" && theirs.subkind == "普通名詞"
        val oneKanjiSuffix = ours.subkind == "接尾" && ours.surface.singleOrNull()?.let(::isHan) == true
        if (!plainNoun || !oneKanjiSuffix) return false
        if (before == null || before.subkind in NUMBERS) return false
        return ours.kana.first() !in VOICED || (before.subkind in NOT_PLAIN_NOUNS && before.surface.all(::isSaid))
    }

    /** Kana said with a voice, as the second half of a word starts: だま, ぼし, び. */
    private const val VOICED = "がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽ"

    /** Words in kana a noun after can't be the second half of: いつか, a pronoun, and the names IPADIC makes up, いつの. */
    private val NOT_PLAIN_NOUNS = setOf("副詞可能", "代名詞", "固有名詞")

    /** Whether UniDic keeps whole, as the word [b], what IPADIC breaks up, [a]: kanji, 逢ひ in 逢 and ひ, or old kana, けふ in け and ふ. */
    private fun keptWhole(a: List<Piece>, b: List<Piece>): Boolean {
        val whole = b.singleOrNull() ?: return false
        return a.size > 1 && (whole.surface.any(::isHan) || Furigana.modernKana(whole.surface, listOfNotNull(whole.spoken)) != null)
    }

    /**
     * A reading the lyrics give in brackets after the kanji it's for, as in 歌（うた） or
     * 悲（かな）しい: it's how they're sung, so it's theirs, and the line is read without it. It may
     * come after the mark that closes them, 《殺意》（しょうどう）, and be for all a pair of marks hold,
     * kana too, 《不本意な現状》（いま）: the closing mark goes with the brackets, so it's kept where it is.
     */
    private val givenReading = Regex(
        """(?<![\p{IsHan}々])([\p{IsHan}々]++)([》」』〉】]?[（(]([\p{IsHiragana}\p{IsKatakana}ー]+)[）)])""" +
            """|(?<=[《「『〈【])([^《》「」『』〈〉【】（）()\s]*\p{IsHan}[^《》「」『』〈〉【】（）()\s]*)([》」』〉】][（(]([\p{IsHiragana}\p{IsKatakana}ー]+)[）)])"""
    )

    /** What a reading is given for, its brackets and its kana, from either way it's written. */
    private fun MatchResult.given(group: Int) = (groups[group] ?: groups[group + 3])?.value.orEmpty()

    /** A reading the lyrics give: from [start] to [end] in the line without them, the kanji read [kana]; [brackets] as written. */
    private class Given(val start: Int, val end: Int, val kana: String, val brackets: String)

    private fun givenReadings(line: String): List<Given> {
        var cut = 0
        return givenReading.findAll(line).map { match ->
            val start = match.range.first - cut
            cut += match.given(2).length
            Given(start, start + match.given(1).length, hiragana(match.given(3)), match.given(2))
        }.toList()
    }

    private fun withoutGiven(line: String) = line.replace(givenReading) { it.given(1) }

    /**
     * [pieces] of the line without its given readings, with those kanji read as [given]: with the
     * kana written after them, 悲しい as かな and しい, or apart from more kanji after them.
     */
    private fun readAsGiven(pieces: List<Piece>, given: List<Given>): List<Piece> {
        if (given.isEmpty()) return pieces
        val left = ArrayDeque(pieces)
        val out = mutableListOf<Piece>()
        var at = 0
        while (left.isNotEmpty()) {
            val reading = given.firstOrNull { it.start == at }
            if (reading == null) {
                out += left.removeFirst().also { at += it.surface.length }
                continue
            }
            val first = left.first()
            var surface = ""
            var kana = ""
            while (at + surface.length < reading.end) left.removeFirst().let { surface += it.surface; kana += it.kana }
            val after = surface.drop(reading.end - at)
            if (after.any(::isHan)) {
                out += Piece(surface.take(reading.end - at), reading.kana, first.kind, first.subkind, reader = Reader.LYRICS)
                // The rest as the dictionary read the word, ごえ of 歌声, when it read the kanji so too; and
                // as the lyrics read it, when they give a reading for it too, 声（こえ）.
                val rest = if (kana.startsWith(reading.kana)) listOf(first) else together(pieces(after))
                left.addFirst(Piece(after, if (kana.startsWith(reading.kana)) kana.drop(reading.kana.length) else rest.joinToString("") { it.kana }, first.kind, first.subkind, reader = rest.first().reader))
                at = reading.end
            } else {
                out += Piece(surface, reading.kana + hiragana(after), first.kind, first.subkind, first.base, reader = Reader.LYRICS)
                at += surface.length
            }
        }
        return out
    }

    /** [parts] of the line without its given readings, with their brackets back where they're written. */
    private fun withGiven(parts: List<Ruby>, given: List<Given>): List<Ruby> {
        if (given.isEmpty()) return parts
        val out = mutableListOf<Ruby>()
        var at = 0
        for (part in parts) {
            var rest = part
            for (reading in given.filter { it.end > at && it.end <= at + part.text.length }) {
                val cut = reading.end - at - (part.text.length - rest.text.length)
                out += Ruby(rest.text.take(cut), rest.reading)
                out += Ruby(reading.brackets)
                rest = Ruby(rest.text.drop(cut))
            }
            if (rest.text.isNotEmpty()) out += rest
            at += part.text.length
        }
        return out
    }

    /** Whether the digit at [at] is one alone before a counter, a kanji or the つ of 1つ, rather than part of a number, 100人. */
    private fun counts(line: String, at: Int) =
        line.getOrNull(at - 1)?.isDigit() != true && line.getOrNull(at + 1)?.let { isHan(it) || it == 'つ' } == true

    /** Digits, also as written full width, and the kanji counting with them. */
    private val KANJI_DIGITS = ("1一2二3三4四5五6六7七8八9九" + "１一２二３三４四５五６六７七８八９九").chunked(2).associate { it[0] to it[1] }

    /** Old forms of kanji, each before its new one, as lyrics keep them for their feel. */
    private val NEW_FORMS = ("燈灯戀恋櫻桜聲声體体國国學学氣気會会來来晝昼黑黒發発傳伝實実廣広獨独歸帰圓円驛駅舊旧" +
        "樂楽萬万與与爭争戰戦繪絵戲戯聽聴譯訳變変邊辺鐵鉄靜静顏顔惠恵濱浜擧挙寫写對対數数斷断曉暁歡歓歲歳殘残滿満" +
        "燒焼爲為畫画當当盡尽眞真禮礼經経綠緑總総續続處処號号螢蛍觀観讀読豐豊賣売輕軽辭辞醉酔黃黄齒歯淚涙")
        .chunked(2).associate { it[0] to it[1] }

    /**
     * [line] of a Japanese song as Japan writes it, when a Chinese site wrote it China's way: kanji
     * Japan doesn't use, as 间 or 仆, as Japan's own, 間 and 僕, which have readings, and the comma
     * Chinese ends a line with, "，", left off.
     */
    fun asJapanWrites(line: String): String = inJapansKanji(line).let { if (it.trimEnd().endsWith('，')) it.trimEnd().dropLast(1).trimEnd() else it }

    /** [text] with China's kanji as Japan's, one for one, so its timings stay where they were. */
    fun inJapansKanji(text: String): String = if (text.none { it in JAPANS_KANJI }) text else text.map { JAPANS_KANJI[it] ?: it }.joinToString("")

    /**
     * Kanji as China writes them, each before Japan's own. Each of China's is one Japan doesn't
     * write, but for 仆 and 梦, which Japan only has for an old word and an old form, and Chinese
     * sites write for 僕 and 夢.
     */
    internal val JAPANS_KANJI = ("仆僕终終间間爱愛话話时時门門见見记記忆憶让譲这這远遠还還过過进進连連达達运運边辺对対发発东東车車" +
        "长長开開关関问問闻聞闪閃风風飞飛马馬鸟鳥鱼魚龙竜头頭颜顔题題认認讲講识識语語读読谁誰谢謝词詞试試诗詩请請调調" +
        "谈談论論设設许許证証评評访訪计計议議译訳诉訴课課实実汉漢满満线線红紅纸紙经経结結给給继継续続练練组組细細约約" +
        "级級纯純织織统統绘絵罗羅编編刚剛别別则則剑剣创創动動劳労势勢华華单単卖売压圧县県变変响響团団围囲园園图図场場" +
        "块塊坚堅处処备備复復夺奪奋奮孙孫宁寧宫宮寻尋导導岛島带帯庆慶应応废廃张張弹弾归帰彻徹怀懐态態恶悪恼悩惊驚惯慣" +
        "戏戯战戦扑撲执執扩拡扫掃扬揚护護报報拥擁择択挥揮损損换換摇揺摄摂敌敵显顕晓暁暂暫术術杀殺杂雑权権杨楊极極构構" +
        "树樹样様桥橋检検欢歓毕畢泽沢洁潔测測济済浓濃润潤渐漸渔漁灭滅灵霊灾災炼錬烦煩烧焼热熱爷爺牵牽猎猟环環现現电電" +
        "畅暢疗療盘盤码碼础礎确確离離种種积積稳穏穷窮竞競笔筆笼籠简簡紧緊纪紀纷紛绍紹络絡绩績绪緒维維绵綿缓緩缠纏缩縮" +
        "罚罰职職聪聡肠腸肤膚胜勝脑脳腾騰舰艦艺芸节節荣栄药薬获獲营営萤蛍蓝藍虑慮虽雖补補观観规規视視览覧觉覚讨討训訓" +
        "诚誠诞誕该該详詳误誤诸諸谊誼谜謎谦謙谱譜贝貝负負贡貢财財责責败敗货貨质質贫貧购購贯貫贵貴贸貿费費贺賀资資赏賞" +
        "赞賛赠贈跃躍轨軌轮輪软軟转転轻軽载載较較辈輩辉輝输輸迁遷违違迟遅适適选選遗遺邮郵邻隣释釈针針钟鐘钢鋼钱銭铁鉄" +
        "铃鈴银銀锁鎖错錯锦錦键鍵镜鏡闭閉队隊阳陽阴陰阵陣阶階际際陆陸险険隐隠难難雾霧韩韓项項顺順须須顽頑预預领領频頻" +
        "梦夢顷頃额額饥飢饭飯饮飲饱飽馆館驱駆验験骑騎骚騒鲜鮮鸣鳴齐斉齿歯龄齢龟亀为為乐楽买買亚亜产産亲親亿億仓倉仪儀优優传伝" +
        "伤傷伦倫伪偽侦偵侧側俭倹债債倾傾偿償兰蘭兴興养養兽獣军軍农農冻凍净浄减減凤鳳剂剤剧劇劝勧务務协協厅庁厌厭叹嘆" +
        "员員圆円圣聖坟墳坠墜垒塁壳殻奖奨妇婦婴嬰宠寵审審宪憲尔爾尘塵层層岭嶺币幣师師帐帳库庫庙廟异異强強总総恳懇惩懲" +
        "愤憤扰擾抚撫拟擬挚摯斋斎标標栋棟栏欄毁毀沟溝滥濫滨浜灿燦烛燭牺犠狮獅狱獄盐塩监監矫矯矿鉱类類绳縄缔締缚縛聋聾" +
        "肃粛肾腎艰艱艳艶苍蒼苏蘇茧繭莲蓮虏虜蚁蟻袭襲谓謂说説绿緑绝絶缘縁历歴宽寛岁歳录録赖頼闲閑阅閲鸡鶏众衆击撃" +
        "伞傘乡郷书書习習丽麗举挙义義仅僅侣侶储儲冈岡卫衛叠畳吓嚇吴呉哑唖唤喚喷噴宾賓帅帥忧憂悬懸户戸斩斬昙曇晕暈" +
        "枪槍栈桟汤湯浊濁涡渦涩渋溃潰滤濾滩灘烂爛疯瘋皱皺瞒瞞硕碩祸禍秃禿窝窩竖竪纠糾纤繊纬緯纱紗纲綱纳納纵縦纹紋" +
        "纺紡纽紐绅紳绊絆绞絞绢絹绣繍绮綺综綜绽綻缀綴缝縫罢罷耸聳肿腫胀脹胁脅胧朧脓膿荡蕩莺鶯萧蕭萨薩蔷薔蕴蘊蚀蝕蜗蝸" +
        "蝇蝿衔銜衬襯觅覓订訂讯訊讳諱讶訝讽諷诀訣诈詐诊診询詢诫誡诱誘诵誦诺諾谅諒谋謀谐諧谣謡谨謹贞貞贤賢贩販贪貪贱賤" +
        "贴貼贼賊赋賦赌賭赐賜赔賠赵趙趋趨轩軒轰轟辅輔辆輌辖轄辽遼迈邁逊遜递逓酱醤酿醸鉴鑑钓釣钝鈍钦欽铅鉛铜銅铭銘铺舗" +
        "锅鍋锋鋒锐鋭锻鍛镇鎮闷悶阀閥阁閣阔闊陈陳雏雛页頁顶頂顾顧顿頓颁頒颇頗颠顛颤顫飘飄饰飾饲飼饶饒饼餅驰馳驳駁" +
        "驻駐驾駕骂罵骏駿骗騙骤驟鲁魯鲸鯨鳞鱗鸥鴎鸭鴨鹅鵝鹤鶴鹰鷹")
        .chunked(2).associate { it[0] to it[1] }

    /** The dictionary draws out おう as おー, but not えい, as in せんせい, said せんせー. */
    private fun drawnOutE(kana: String) = kana.replace(Regex("([えけげせぜてでねへべぺめれ])い"), "$1ー")

    /**
     * A word as the dictionary splits a line, with how it reads and what part of speech it is, and
     * how it's [spoken], when that's known: 先生, read せんせい, is said せんせー.
     */
    private class Piece(
        val surface: String,
        val kana: String,
        val kind: String,
        val subkind: String,
        val base: String = surface,
        val spoken: String? = null,
        /** Where it ends in the line: the dictionaries' pieces are mixed by it, and a word in old kana read as its spelling today has none, as nothing reads it after. */
        val end: Int = 0,
        /** Whether the dictionary has no reading for it. */
        private val unread: Boolean = false,
        /** What read it. */
        val reader: Reader = Reader.IPADIC,
        /** How it is spelled today, when it is in old kana and [Furigana.byRule] says it. */
        val today: String? = null
    ) {
        /** Kanji the dictionary can't read. */
        val unknown get() = unread && surface.any(::isHan)

        /** Katakana the dictionary can't read, as it can't the kana after a kanji written so, ニ or カナイ. */
        val unreadKatakana get() = unread && surface.any { it in 'ァ'..'ヺ' }

        /** Kanji, or the katakana after them, that this dictionary could not read. */
        fun missedKana() = unknown || unreadKatakana

        /** A prefix or suffix of kanji with no word to be one of: a suffix fixes to a noun before it, [before]. */
        fun strayAffix(before: Piece?) = surface.any(::isHan) && (kind == "接頭詞" || (subkind == "接尾" && !(before?.isNoun ?: false)))

        /** A noun, a number in digits too, not a mark IPADIC calls one, ! or (. */
        private val isNoun get() = kind == "名詞" && surface.any(Char::isLetterOrDigit)

        /** It read [kana] by Crosstune's own rules, as a word of [kind] and [subkind] when that changes too. */
        fun read(kana: String, kind: String = this.kind, subkind: String = this.subkind) = Piece(surface, kana, kind, subkind, base, null, end, reader = Reader.RULES, today = today)

        /** It read [kana], and said [said]. */
        fun reading(kana: String, said: String) = Piece(surface, kana, kind, subkind, base, said, end, reader = reader, today = today)

        /** How it's said, for romaji, so it's sung right: sensee, not sensei; the particles は and へ are wa and e. */
        val said get() = if (kind == "助詞") particle ?: kana else spoken ?: kana

        /** How it's typed, for romaji as a keyboard spells it: sensei; the particles still wa and e, as romaji writes them. */
        val typed get() = if (kind == "助詞") particle ?: kana else kana

        private val particle get() = when (surface) { "は" -> "わ"; "へ" -> "え"; else -> null }
    }

    /**
     * Pieces the dictionary splits but that read as one, like 二人, ふたり rather than に and にん, also
     * written 2人. Kept to numbers of people, the ones lyrics are full of, and 一 before a counter,
     * which it runs into: 一歩 いっぽ, 一回 いっかい. And words read as lyrics sing them: 側 on its own
     * is そば, 君の側で, but after the side it is, こっち側, がわ.
     */
    private fun together(pieces: List<Piece>): List<Piece> {
        val out = mutableListOf<Piece>()
        for ((index, piece) in pieces.withIndex()) {
            val last = out.lastOrNull()
            val word = last?.let { TOGETHER[(it.surface + piece.surface).map { c -> KANJI_DIGITS[c] ?: c }.joinToString("")] }
            // 分 after a number after 寸 or 割 is a tenth of it, ぶ: 四寸四分, 五割五分. Elsewhere, minutes.
            val tenth = piece.surface == "分" && last?.subkind in NUMBERS && out.getOrNull(out.lastIndex - 1)?.surface?.lastOrNull()?.let { it in TENTHS } == true
            val count = last?.takeUnless { tenth }?.let { counted(it, piece) }
            val hundred = last?.takeIf { it.subkind in NUMBERS && piece.subkind in NUMBERS }?.let { hundreds(it.kana, piece.kana) }
            // A suffix after a mark or a space, or starting the line, is a suffix to nothing.
            val stray = piece.takeIf { it.subkind == "接尾" && it.surface.all(::isHan) && (last == null || last.surface.none(Char::isLetterOrDigit)) }?.let { alone(it.surface) }
            // A prefix of kanji goes before a word with kanji, 今世紀 or 前前前世; before one in kana, 今となり, it's a word.
            val strayBefore = last?.takeIf { it.kind == "接頭詞" && it.surface.all(::isHan) && piece.surface.none(::isHan) }?.let { alone(it.surface) }
            val next = pieces.getOrNull(index + 1)
            val classical = classical(piece, next)
            val minutes = minutes(piece, next)
            when {
                word != null -> out[out.lastIndex] = Piece(last.surface + piece.surface, word, "名詞", "一般", reader = Reader.RULES)
                last != null && count != null -> out[out.lastIndex] = Piece(last.surface + piece.surface, count, "名詞", "一般", reader = Reader.RULES)
                tenth -> out += piece.read("ぶ")
                last != null && hundred != null -> {
                    out[out.lastIndex] = last.read(hundred.first)
                    out += piece.read(hundred.second)
                }
                piece.surface == "側" && (last == null || !sideOf(last)) -> out += piece.read("そば", "名詞", "一般")
                // A suffix of kanji with no noun before it, 心 after 走れ!, is the word it is on its own: こころ, not しん.
                stray != null -> out += piece.read(stray, "名詞", "一般")
                // And a prefix with no word in kanji after it, 今 before となり: いま, not こん.
                last != null && strayBefore != null -> {
                    out[out.lastIndex] = last.read(strayBefore, "名詞", "一般")
                    out += piece
                }
                // 色 after one kanji or a word in kana is a colour, 涙色 なみだいろ; after a word of more, a character, 地方色 ちほうしょく.
                last != null && piece.surface == "色" && piece.kana == "しょく" && (last.surface.length == 1 || last.surface.none(::isHan)) ->
                    out += piece.read("いろ")
                // 君 is くん after a name, 太郎君; after anything else it's you, 何度君と, きみ.
                piece.surface == "君" && piece.kana == "くん" && (last == null || !namedBy(last)) -> out += piece.read("きみ")
                // 手 after a katakana word and before を is a hand, not a suffix naming a person.
                piece.surface == "手" && piece.kana == "しゅ" && last != null && inKatakana(last) &&
                    pieces.getOrNull(index + 1)?.surface == "を" -> out += piece.read("て", "名詞", "一般")
                // An adjective's stem can't go on with て or た: 愛して and 楽して are the verbs, あいして and らくして.
                last != null && stemOfVerb(last, piece) -> {
                    out[out.lastIndex] = last.read(tokenizer.tokenize(last.surface.dropLast(1)).joinToString("") { hiragana(it.reading ?: it.surface) } + "し", "動詞", "自立")
                    out += piece
                }
                // 何 before d, t or n is なん: 何でも, 何だ, 何と, 何て, 何の, 何なの.
                last?.surface == "何" && last.kana == "なに" && piece.surface.first() in "でだどとての" + "な" -> {
                    out[out.lastIndex] = last.read("なん")
                    out += piece
                }
                classical != null -> out += piece.read(classical)
                minutes != null -> out += piece.read(minutes)
                else -> out += piece
            }
        }
        return listedWords(out)
    }

    /**
     * How a [number] and the counter [piece] after it read, when the number runs into it: いち, はち and
     * じゅう before k, s, t or h, いっかい, はっさい, じゅっぽん; ろく and ひゃく before k or h, ろっかい, ひゃっぽん.
     * Null when it doesn't, or the piece is no counter but one kanji of another number, 十三.
     */
    private fun counted(number: Piece, piece: Piece): String? {
        if (number.subkind !in NUMBERS || !piece.counts()) return null
        return ranInto(number.kana, piece.surface, piece.kana)
    }

    /**
     * How a number read [number] sounds with the counter [counter], read [kana], when they run together:
     * いっかい and ろっぽん, as [counted] says; and after ん, 三本 さんぼん, 何階 なんがい, 四分 よんぷん.
     */
    private fun ranInto(number: String, counter: String, kana: String): String? {
        val first = kana.firstOrNull()?.takeIf { it in RUN_INTO } ?: return null
        val stem = when {
            number == "いち" || number == "はち" || number.endsWith("じゅう") -> number.dropLast(1)
            number.endsWith("く") && first in "かきくけこはひふへほ" -> number.dropLast(1)
            else -> return afterN(number, counter, kana)
        }
        return stem + "っ" + plosive(kana)
    }

    /** [ranInto] after ん: 分 is ぷん after any, the other counters in [AFTER_N] change only after さん and なん. */
    private fun afterN(number: String, counter: String, kana: String): String? {
        val start = AFTER_N[counter.first()] ?: return null
        val changes = if (counter.first() == '分') number.endsWith("ん") else number.endsWith("さん") || number.endsWith("なん")
        return if (changes) number + start + kana.drop(1) else null
    }

    /**
     * 二分 and 十分, as the dictionary reads them, are a half and enough, にぶん and じゅうぶん. Before 後, 前,
     * 間 or 半 they're minutes: 二分後 にふんご, 十分前 じゅっぷんまえ. 何分, なにぶん, is how many: 何 counts as なん, 何分前
     * なんぷんまえ, as before any counter. Null when [piece] is no such word.
     */
    private fun minutes(piece: Piece, next: Piece?): String? {
        if (next?.surface !in AFTER_MINUTES || !piece.surface.endsWith('分') || !piece.kana.endsWith("ぶん")) return null
        if (piece.surface.length < 2 || !piece.surface.dropLast(1).all { it in "一二三四五六七八九十百何" }) return null
        val number = piece.kana.dropLast(2).let { if (it == "なに") "なん" else it }
        return ranInto(number, "分", "ふん") ?: (number + "ふん")
    }

    /**
     * [piece] read as the classical verb the kana written after its kanji make it, 晴る and 流る: the
     * dictionary reads the kanji as the modern word, はれ and ながれ, but the line ends it with る, as
     * 晴るる空, so the kanji are は and なが. Null when the kana after it don't make one, or JMdict has the modern verb
     * written so, 洒落る しゃれる, whose れ is in its kanji: IPADIC reads 流る and 洒落る alike.
     */
    private fun classical(piece: Piece, next: Piece?): String? {
        val kanji = piece.surface.takeWhile(::isHan)
        val ending = piece.surface.drop(kanji.length)
        if (kanji.isEmpty() || ending.any { !isKana(it) } || !piece.kana.endsWith(hiragana(ending))) return null
        if (listed.orEmpty()[piece.base]?.endsWith("れる") == true) return null
        val over = piece.kana.dropLast(ending.length)
        val after = ending.ifEmpty { next?.surface.orEmpty() }.firstOrNull()
        return if (over.length > 1 && over.last() == 'れ' && after == 'る') over.dropLast(1) + hiragana(ending) else null
    }

    /**
     * Words of two kanji or more that JMdict reads one way only, and the dictionaries on the phone read
     * another, 体中 からだじゅう for からだちゅう, kept in [LISTED]: made by `scripts/readingsaudit`, see
     * CONTRIBUTING.md. Read once, when a line first needs them, whatever threads ask at once; null to read them
     * again, empty to read without them.
     */
    @Volatile
    internal var listed: Map<String, String>? = null
        get() = field ?: synchronized(tables) { field ?: readListed().also { field = it } }

    private const val LISTED = "/com/astrovm/crosstune/jmdict-readings.txt"

    private fun readListed(): Map<String, String> = checkNotNull(Readings::class.java.getResourceAsStream(LISTED)).bufferedReader().useLines { lines ->
        lines.filterNot { it.startsWith('#') }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
    }

    /**
     * [pieces] with the words JMdict [listed] read as it does, where the pieces next to each other
     * make one: 体 and 中 are 体中, からだじゅう, and 一番 and 星 are 一番星, いちばんぼし. A verb is
     * looked up as the dictionary writes it, 肉付く for 肉付いた. Only whole pieces, so 一人舞 is not taken
     * out of 一人舞う; never starting or ending in a particle, so 今日は stays きょうは; and never one piece
     * alone, as the dictionary read it as a word it knows, 上野 うえの, which JMdict has only as こうずけ; nor
     * a number that starts a longer word, 一重 of 一重項: see [numberGoesOn].
     */
    private fun listedWords(pieces: List<Piece>): List<Piece> {
        val table = listed.orEmpty()
        if (table.isEmpty()) return pieces
        val out = mutableListOf<Piece>()
        var at = 0
        while (at < pieces.size) {
            val found = (minOf(LISTED_PIECES, pieces.size - at) downTo 2).firstNotNullOfOrNull { count ->
                listedAs(pieces.subList(at, at + count), table)?.takeUnless { numberGoesOn(pieces, at, count) }?.let { count to it }
            }
            if (found == null) out += pieces[at++] else {
                out += found.second
                at += found.first
            }
        }
        return out
    }

    /**
     * Whether the [count] pieces at [at] start with a number and go on into a noun in kanji: then they're the start of a longer
     * word, 一重 of 一重項 いちじゅうこう or 二五 of 二五進法, which JMdict's 一重 ひとえ and the dictionary's 二十五 don't read.
     * Kana end a word, 一重の, and so does a verb, 三尺下がって. A word that starts with no number goes on as JMdict reads it, 保湿剤.
     */
    private fun numberGoesOn(pieces: List<Piece>, at: Int, count: Int): Boolean {
        val next = pieces.getOrNull(at + count) ?: return false
        return pieces[at].subkind in NUMBERS && isHan(pieces[at + count - 1].surface.last()) && isHan(next.surface.first()) && next.kind !in INFLECTED
    }

    /**
     * The one piece [word] makes as a word in [table], when it's there and read otherwise than its pieces.
     * Only the last piece may be a verb or an adjective: one before goes on into the next word, 溺れ in
     * 溺れ死にそう, not into a noun JMdict lists, 溺れ死に おぼれじに.
     */
    private fun listedAs(word: List<Piece>, table: Map<String, String>): Piece? {
        val last = word.last()
        val runsOn = word.dropLast(1).none { it.kind in GRAMMAR || it.kind in INFLECTED }
        if (!isHan(word[0].surface[0]) || last.kind in GRAMMAR || !runsOn) return null
        val surface = word.joinToString("") { it.surface }
        if (surface.count(::isHan) < 2) return null
        val kana = table[surface] ?: conjugated(word, table) ?: return null
        if (kana == word.joinToString("") { it.kana }) return null
        return Piece(surface, kana, last.kind, last.subkind, reader = Reader.JMDICT)
    }

    /**
     * How [word] reads when its last piece is a verb or an adjective JMdict has in its dictionary form:
     * 肉付い as 肉付く, にくづく, so にくづい. It's looked up as written, with the dictionary form's ending.
     */
    private fun conjugated(word: List<Piece>, table: Map<String, String>): String? {
        val last = word.last()
        if (last.kind !in INFLECTED) return null
        val ending = last.surface.takeLastWhile(::isKana)
        val listedEnding = last.base.takeLastWhile(::isKana)
        val written = word.dropLast(1).joinToString("") { it.surface } + last.surface.dropLast(ending.length)
        val kana = table[written + listedEnding] ?: return null
        return if (kana.endsWith(hiragana(listedEnding))) kana.dropLast(listedEnding.length) + hiragana(ending) else null
    }

    private val INFLECTED = setOf("動詞", "形容詞")

    /** The most pieces a word JMdict lists is looked for in: 水車 小屋, 一言 一句. */
    private const val LISTED_PIECES = 5

    /** How a number read [number] and 百 or 千 after it, read [hundred], sound together: さんびゃく, ろっぴゃく, はっせん, なんぜん. */
    private fun hundreds(number: String, hundred: String): Pair<String, String>? = when {
        number.endsWith("さん") || number.endsWith("なん") -> when (hundred) {
            "ひゃく" -> number to "びゃく"
            "せん" -> number to "ぜん"
            else -> null
        }
        (number == "ろく" || number == "はち") && hundred == "ひゃく" -> number.dropLast(1) + "っ" to "ぴゃく"
        number == "はち" && hundred == "せん" -> "はっ" to "せん"
        else -> null
    }

    /** How [kanji] read as a word on their own, when the dictionary has them as one. */
    private fun alone(kanji: String): String? = tokenizer.tokenize(kanji).singleOrNull()?.takeIf { it.partOfSpeechLevel2 != "接尾" }?.reading?.takeIf { it != "*" }?.let(::hiragana)

    /** Whether 君 after [piece] follows a name: one the dictionary knows, or one in katakana. */
    private fun namedBy(piece: Piece) = piece.subkind == "固有名詞" || inKatakana(piece)

    /** Whether [piece] is all katakana, long marks and all, as ダンサー. */
    private fun inKatakana(piece: Piece) = piece.surface.all { it in 'ァ'..'ヺ' || it == 'ー' }

    /** Whether [stem], an adjective's stem of kanji and し, goes on with て or た in [next], as only a verb can. */
    private fun stemOfVerb(stem: Piece, next: Piece) = stem.kind == "形容詞" && stem.surface.length > 1 && stem.surface.last() == 'し' &&
        stem.surface.dropLast(1).all(::isHan) && next.surface.first() in "てた"

    /**
     * Whether this counts the number before it: one kanji, 回 or 本, or a suffix the dictionary has
     * as one word, 週間 or ヶ月. 色 does not: 十色 is ten colours, and the number must not run into しょく.
     */
    private fun Piece.counts(): Boolean {
        if (surface == "色") return false
        if (subkind in NUMBERS) return false
        if (surface.length == 1) return isHan(surface[0])
        return subkind == "接尾" && (isHan(surface[0]) || surface[0] in "ヶヵケ") && surface.drop(1).all(::isHan)
    }

    /** [kana] with an h as p, ほ as ぽ, as it is after a sound run into. */
    private fun plosive(kana: String) = if (kana.first() in "はひふへほ") (kana.first() + 2) + kana.drop(1) else kana

    /** Whether 側 after [piece] is a side of it: after kanji or katakana, 反対側 and サーバー側, or a way, こっち側. */
    private fun sideOf(piece: Piece) = piece.surface in SIDES || piece.surface.last().let { isHan(it) || it in 'ァ'..'ヺ' || it == 'ー' }

    private val NUMBERS = setOf("数", "数詞")
    private const val RUN_INTO = "かきくけこさしすせそたちつてとはひふへほ"

    /** Counters said with a voice, or a p, after ん, by the kana they start with then: 三本 さんぼん, 三匹 さんびき, 三分 さんぷん. */
    private val AFTER_N = mapOf(
        '本' to "ぼ", '杯' to "ば", '匹' to "び", '階' to "が", '軒' to "げ", '足' to "ぞ", '歩' to "ぽ", '泊' to "ぱ", '発' to "ぱ", '分' to "ぷ"
    )

    /** What 分 is a tenth of, after a number after it: 寸 in 四寸四分, 割 in 五割五分. */
    private const val TENTHS = "寸割"

    /** What minutes come before: 二分後, 十分前, 三分間, 五分半. */
    private val AFTER_MINUTES = setOf("後", "前", "間", "半", "以内")
    private val SIDES = setOf("こっち", "そっち", "あっち", "どっち", "こちら", "そちら", "あちら", "どちら", "向こう")

    private val TOGETHER = mapOf("一人" to "ひとり", "二人" to "ふたり")

    /**
     * [read] with [fixes] made: each kanji takes its new reading, on the line and in its kana and
     * romaji. One that changes nothing, or names a kanji the line hasn't, is left out.
     */
    fun corrected(read: List<LineReading>, fixes: List<ReadingFix>): List<LineReading> = read.mapIndexed { index, line ->
        val parts = sungParts(line.parts, fixes.filter { it.line == index })
        if (parts == line.parts) line else readAs(parts)
    }

    /** A fix's kanji where they are in the line, from [start] to [end], and the parts of ours it covers, [first] to [last]. */
    private class Sung(val start: Int, val end: Int, val fix: ReadingFix, val first: Int, val last: Int)

    /**
     * [parts] with [fixes] made where their kanji are in the line, whether ours split them alike or
     * not: a fix may join two of ours, 闇 and 間 as 闇間 やみま, or a few may split one, 身体中 as
     * 身体 からだ and 中 じゅう, which is only done when they read all of it.
     */
    private fun sungParts(parts: List<Ruby>, fixes: List<ReadingFix>): List<Ruby> {
        val starts = parts.runningFold(0) { at, part -> at + part.text.length }
        val line = parts.joinToString("") { it.text }
        val sung = whereSung(line, parts, starts, fixes)
        return readSung(line, parts, starts, sung)
    }

    /** Each fix where its kanji are next in [line], then the same kanji again when ours read them alike. */
    private fun whereSung(line: String, parts: List<Ruby>, starts: List<Int>, fixes: List<ReadingFix>): List<Sung> {
        val sung = mutableListOf<Sung>()
        for (fix in fixes) {
            if (fix.kanji.isEmpty()) continue
            val at = findKanji(line, fix.kanji).firstOrNull { free(sung, it, fix.kanji.length) } ?: continue
            sung += sungAt(starts, at, fix)
        }
        // A copy: fixes added here are the same kanji, not a new word to copy again.
        for (first in sung.toList().distinctBy { it.fix.kanji }) {
            sung += findKanji(line, first.fix.kanji)
                .filter { free(sung, it, first.fix.kanji.length) }
                .map { sungAt(starts, it, first.fix) }
                .filter { readOver(parts, it) == readOver(parts, first) }
        }
        return sung
    }

    /** Each place [kanji] stands in [line]. */
    private fun findKanji(line: String, kanji: String) =
        generateSequence(line.indexOf(kanji).takeIf { it >= 0 }) { line.indexOf(kanji, it + 1).takeIf { at -> at >= 0 } }

    /** Whether [at] for [length] letters is still free of a fix. */
    private fun free(sung: List<Sung>, at: Int, length: Int) = sung.none { it.start < at + length && at < it.end }

    /** A fix placed at [at] in the line, and which of our parts that stretch covers. */
    private fun sungAt(starts: List<Int>, at: Int, fix: ReadingFix) = Sung(
        at,
        at + fix.kanji.length,
        fix,
        starts.indexOfLast { it <= at },
        starts.indexOfFirst { it >= at + fix.kanji.length } - 1,
    )

    /** Our reading over the parts a fix covers. */
    private fun readOver(parts: List<Ruby>, fix: Sung) = parts.subList(fix.first, fix.last + 1).joinToString("") { it.reading ?: it.text }

    /** [parts] with the fixes [sung] laid on, whole parts first and then a part that fixes split. */
    private fun readSung(line: String, parts: List<Ruby>, starts: List<Int>, sung: List<Sung>): List<Ruby> {
        val out = parts.map { listOf(it) }.toMutableList()
        val whole = sung.filter { starts[it.first] == it.start && starts[it.last + 1] == it.end }
        coverWhole(line, parts, out, whole)
        for ((index, part) in parts.withIndex()) {
            if (part.text.none(::isHan) || whole.any { index in it.first..it.last }) continue
            val inside = sung.filter { it.first == index && it.last == index }.sortedBy { it.start }
            if (inside.isEmpty()) continue
            split(line, starts[index], starts[index + 1], inside)?.let { out[index] = it }
        }
        return out.flatten()
    }

    /** Ours a fix covers whole, or more than one: read as it says. */
    private fun coverWhole(line: String, parts: List<Ruby>, out: MutableList<List<Ruby>>, whole: List<Sung>) {
        for (fix in whole) {
            if (parts.subList(fix.first, fix.last + 1).none { part -> part.text.any(::isHan) }) continue
            val reading = sungReading(readOver(parts, fix), fix.fix.reading, line.substring(fix.end)) ?: continue
            out[fix.first] = laidOver(fix.fix.kanji, reading).map { if (it.reading == null) it else it.copy(by = fix.fix.by) }
            clearAfter(out, fix)
        }
    }

    /** The parts after the first that a fix covers are now part of that first one. */
    private fun clearAfter(out: MutableList<List<Ruby>>, fix: Sung) {
        for (i in fix.first + 1..fix.last) out[i] = emptyList()
    }

    /** [kana] over the kanji of [surface], or over all of it when it has kana among them its reading hasn't, 不本意な現状 read いま. */
    private fun laidOver(surface: String, kana: String): List<Ruby> =
        furigana(surface, kana).takeIf { laid -> laid.joinToString("") { hiragana(it.reading ?: it.text) } == hiragana(kana) } ?: listOf(Ruby(surface, kana))

    /** The line from [from] to [to], one of our parts, read as the fixes [inside] it say; null when they leave one of its kanji unread. */
    private fun split(line: String, from: Int, to: Int, inside: List<Sung>): List<Ruby>? {
        val pieces = mutableListOf<Ruby>()
        var at = from
        for (fix in inside) {
            val between = line.substring(at, fix.start)
            if (between.any(::isHan)) return null
            if (between.isNotEmpty()) pieces += Ruby(between)
            pieces += Ruby(fix.fix.kanji, hiragana(fix.fix.reading).takeIf { it.all(::isSaid) } ?: return null, fix.fix.by)
            at = fix.end
        }
        val rest = line.substring(at, to)
        return if (rest.any(::isHan)) null else pieces + listOfNotNull(rest.ifEmpty { null }?.let(::Ruby))
    }

    /**
     * The reading a fix gives kanji the dictionary read [old], when it's another: it may give the
     * kana written [after] the kanji too, ごし for 越 in 越し, or end in kana that only happen to come
     * next, いま for 今 in 今まで, so it's whichever is nearer the length the dictionary read.
     */
    private fun sungReading(old: String, reading: String, after: String): String? {
        val given = hiragana(reading)
        val kana = hiragana(after)
        val wanted = (listOf(given) + (given.length - 1 downTo 1).filter { kana.startsWith(given.substring(it)) }.map { given.substring(0, it) })
            .minBy { abs(it.length - old.length) }
        return wanted.takeUnless { it.isEmpty() || it == old || !it.all(::isSaid) }
    }

    /** A letter a reading may be: kana, or the mark that draws one out, as in イェーガー. */
    private fun isSaid(c: Char) = c in 'ぁ'..'ゟ' || c == 'ー'

    /**
     * The line [parts] write, in kana and romaji, with each kanji read as they say: split into words
     * as any line is, so the romaji is too, 頭ん中 as atama n naka, not the dictionary's reading
     * with the new one pasted over it, where it can't be found in a word, atama nchuu.
     */
    fun readAs(parts: List<Ruby>): LineReading {
        val line = parts.joinToString("") { it.text }
        val reads = letterReadings(parts)
        val given = givenReadings(line)
        var from = 0
        val pieces = readAsGiven(together(pieces(withoutGiven(line))), given).map { piece ->
            val start = from.also { from += piece.surface.length }
            // Where each letter is in the line, past the readings it gives in brackets.
            val letters = piece.surface.indices.map { inLine(start + it, given) }
            val kana = letters.indices.joinToString("") { reads[letters[it]] ?: piece.surface[it].toString() }
            val said = letters.indices.joinToString("") { reads[letters[it]]?.let(::longVowels) ?: piece.surface[it].toString() }
            if (kana == piece.kana) piece else piece.reading(kana, said)
        }
        return LineReading(
            parts,
            pieces.joinToString("") { it.kana },
            romanizedLine(pieces, typed = false),
            romanizedLine(pieces, typed = true)
        )
    }

    /** What each letter of the line [parts] write reads as: a kanji's reading on its first letter, nothing on the rest of it. */
    private fun letterReadings(parts: List<Ruby>): Array<String?> {
        val reads = arrayOfNulls<String>(parts.sumOf { it.text.length })
        var at = 0
        for (part in parts) {
            if (part.reading != null) part.text.indices.forEach { reads[at + it] = "" }
            if (part.reading != null && part.text.isNotEmpty()) reads[at] = part.reading
            at += part.text.length
        }
        return reads
    }

    /** Where the letter [at] of a line is in it as written, past the readings it gives in brackets, [given], before it. */
    private fun inLine(at: Int, given: List<Given>) = at + given.filter { it.end <= at }.sumOf { it.brackets.length }

    /** A kanji's [kana] as it's said, せんせい as せんせー and しょう as しょー: drawn out, as romaji shows it. */
    private fun longVowels(kana: String) = drawnOutE(kana).replace(Regex("([おこごそぞとどのほぼぽもよろょ])う"), "$1ー")

    /**
     * The words of a line in kana, as romaji spaces them: a verb with its endings is one word, e.g.
     * 分かった is wakatta rather than waka ta, while particles stand alone. Brackets and marks sit
     * against what they're beside.
     */
    private fun romajiWords(pieces: List<Piece>, kana: (Piece) -> String): List<String> = grouped(pieces).map { word -> word.joinToString("", transform = kana) }

    /** The line [pieces] make in romaji, as it's said or [typed], with a space at most between words. */
    private fun romanizedLine(pieces: List<Piece>, typed: Boolean) =
        romajiWords(pieces) { if (typed) it.typed else it.said }.joinToString(" ") { romaji(it, typed) }.replace(Regex("""\s+"""), " ").trim()

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
            // the dictionary splits, "Don't" into Don, ' and t, stays as written. A mark goes with the word
            // before it, also one the dictionary takes for a word, as "!" in "Shock!".
            val english = previous != null && isEnglish(previous.surface) && isEnglish(piece.surface)
            val attaches = previous != null && (opened || english || ((piece.kind == "記号" || piece.surface.none(Char::isLetterOrDigit)) && !opening) || (ending && previous.kind != "助詞"))
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
        Script.JAPANESE -> japaneseWords(line, askToday = true)
        else -> segments(line).map { text ->
            when (script) {
                Script.CHINESE -> chinese(text).let { Word(text, it.reading, null, by = listOf(Reader.PINYIN)) }
                Script.KOREAN -> Word(text, null, romaja(text), by = listOf(Reader.ROMAJA))
                null -> Word(text)
                else -> Word(text, null, latin(text), by = listOf(Reader.LATIN))
            }
        }
    }

    /**
     * The words of a Japanese [line]. A word in old kana is looked up as the word its modern spelling is, the one the card
     * names: the dictionary takes 思い出 for one word, and 思ひ出 for a stem and another word. It's read in the line spelled
     * today, so it's the word it is there: ゐた in 待つてゐたんだよ is いた, いる, not いたむ, as いたんだ alone reads. Done once,
     * [askToday], as the line spelled today is itself a line to read.
     */
    private fun japaneseWords(line: String, askToday: Boolean): List<Word> {
        val groups = grouped(readAsGiven(together(pieces(withoutGiven(line))), givenReadings(line)))
        val written = groups.joinToString("") { word -> word.joinToString("") { it.surface } }
        var at = 0
        val words = groups.map { word ->
            val start = at
            at += word.sumOf { it.surface.length }
            japaneseWord(word, written, start until at)
        }
        if (!askToday || words.none { it.modern != null }) return words.filter { word -> word.text.any(Char::isLetter) }
        // A translator knows 云う, and takes 云ふ for a name: each word in old kana is looked up as the word at its place in the line spelled today.
        val today = StringBuilder()
        val starts = groups.indices.map { i ->
            val written = groups[i].joinToString("") { it.surface }
            val start = today.length + written.indexOfFirst(Char::isLetterOrDigit).coerceAtLeast(0)
            today.append(words[i].modern?.let { written.replaceFirst(words[i].text, it) } ?: written)
            start
        }
        val read = wordsAt(today.toString())
        return words.mapIndexedNotNull { i, word ->
            // A word JMdict names in kana keeps the form JMdict lists it by, おもいで as 思い出, which IPADIC reads otherwise.
            val inLine = word.modern != null && groups[i].none { it.reader == Reader.JMDICT }
            val lookup = if (inLine) read.lastOrNull { it.first <= starts[i] }?.second ?: word.lookup else word.lookup
            word.copy(lookup = lookup).takeIf { word.text.any(Char::isLetter) }
        }
    }

    /** The [Word] a [word] of pieces is, at [place] in the [line] it's in. */
    private fun japaneseWord(word: List<Piece>, line: String, place: IntRange): Word {
        val text = word.joinToString("") { it.surface }.trim { !it.isLetterOrDigit() }
        val kana = word.joinToString("") { it.kana }.trim { !it.isLetterOrDigit() }
        // Kana reads as written; the reading's only worth showing for kanji.
        val said = word.joinToString("") { it.said }.trim { !it.isLetterOrDigit() }
        val typed = word.joinToString("") { it.typed }.trim { !it.isLetterOrDigit() }
        // What read its kanji, or, for kana, what split it, which says how its particles read.
        val by = (word.filter { piece -> piece.surface.any(::isHan) }.ifEmpty { word }).map { it.reader }.distinct()
        // Letters the dictionary takes for marks, as Hangul in a Japanese line, are a word of marks: it looks itself up.
        val base = (word.firstOrNull { it.kind != "記号" } ?: word.first()).base
        // IPADIC says a few words in old kana alone as they're said today, きのふ as きのー: it names them so. Not an old
        // verb that ends in ひ, as that's a verb and 日 as well: ふるひ is 降る日 more than 振るひ.
        val ipadicSaid = word.singleOrNull()?.takeIf { it.reader == Reader.IPADIC && !(it.kind == "動詞" && it.surface.endsWith('ひ')) }?.said
        val ruled = word.joinToString("") { it.today ?: it.surface }.trim { !it.isLetterOrDigit() }.takeIf { it != text }
        val modern = (if (inLongerWord(text, line, place)) null else spelledToday(text, kana, said, ipadicSaid)) ?: ruled
        return Word(text, kana.takeIf { it != text }, romaji(said), lookup = base, typed = romaji(typed, typed = true), by = by, modern = modern)
    }

    /**
     * IPADIC says the verb いふ as いう, and いひ as いい. Where [inLongerWord] says that is not old kana,
     * いふ in さいふ, the piece is said as it is written too: the note and the sound are one decision.
     */
    private fun saidAsWritten(pieces: List<Piece>): List<Piece> {
        val line = pieces.joinToString("") { it.surface }
        var at = 0
        return pieces.map { piece ->
            val place = at until (at + piece.surface.length).also { at = it }
            if (piece.said != piece.kana && inLongerWord(piece.surface, line, place)) piece.reading(piece.kana, piece.kana) else piece
        }
    }

    /** Whether a word in old kana alone, [text] at [place] in [line], is inside a longer word written so today: いふ in さいふ is 財布, whatever the dictionaries split. */
    private fun inLongerWord(text: String, line: String, place: IntRange): Boolean =
        Furigana.inOldKana(text) && place.any { it > place.first && line[it] in "ひふほ" && writtenToday(line, it, place) }

    /** The lookup of each word of [line] spelled today, by where it starts in the line. */
    private fun wordsAt(line: String): List<Pair<Int, String>> {
        var at = 0
        return grouped(together(pieces(line))).map { word ->
            val written = word.joinToString("") { it.surface }
            val start = at + written.indexOfFirst(Char::isLetterOrDigit).coerceAtLeast(0)
            at += written.length
            start to (word.firstOrNull { it.kind != "記号" } ?: word.first()).base
        }
    }

    /**
     * [text] spelled the modern way, if it has old kana in it, from its reading [kana] and how it's [said]. In kana alone, the
     * reading of IPADIC is not enough: it takes the くふ of くふうして for the verb 食ふ. Only UniDic's listing of the word is,
     * and how IPADIC says a word it reads whole, [ipadicSaid], as it says only old kana another way: きのふ as きのー.
     * (Where a rule says it, the word's pieces carry it, see [modernPieces].)
     */
    private fun spelledToday(text: String, kana: String, said: String, ipadicSaid: String? = null): String? =
        if (Furigana.inOldKana(text)) {
            Furigana.modernKana(text, listedForm(text) + listOfNotNull(ipadicSaid?.let { Furigana.saidAsSpelled(text, it) }))
        } else {
            Furigana.modern(text, kana, said)
        }

    /** The reading of the dictionary form UniDic has for [text], when it takes it for one word: いふ has イウ. */
    private fun listedForm(text: String): List<String> =
        listOfNotNull(uniDic?.tokenize(text)?.singleOrNull()?.lemmaReadingForm?.takeIf { it != "*" })

    /**
     * [words] of a line as they show over its [parts]: a Japanese word whose kanji were put right
     * after the line was read, by UtaTen or ChatGPT, reads as the line does, so a word reads as it
     * shows over the line, and says what put it right.
     */
    fun asShown(words: List<Word>, parts: List<Ruby>): List<Word> {
        if (parts.none { it.by != null }) return words
        val text = parts.joinToString("") { it.text }
        var from = 0
        return words.map { word ->
            val at = text.indexOf(word.text, from).takeIf { it >= 0 } ?: return@map word
            from = at + word.text.length
            readOver(word, at, parts) ?: word
        }
    }

    /** [word], at [at] in the line, read as the [parts] over it are, when one of them was put right and none runs past it. */
    private fun readOver(word: Word, at: Int, parts: List<Ruby>): Word? {
        val end = at + word.text.length
        val kana = StringBuilder()
        val by = mutableListOf<Reader>()
        var start = 0
        for (part in parts) {
            val stop = start + part.text.length
            if (stop > at && start < end) {
                if (part.reading != null && (start < at || stop > end)) return null
                kana.append(part.reading ?: part.text.substring(maxOf(at, start) - start, minOf(end, stop) - start))
                part.by?.let(by::add)
            }
            start = stop
        }
        if (by.isEmpty()) return null
        val read = hiragana(kana.toString())
        return word.copy(reading = read, romanized = romaji(longVowels(read)), typed = romaji(read, typed = true), by = by.distinct())
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

    /** What only spaces words in Japanese, a wide space and the ・ of アスファルト・レディ: a space in romaji. */
    private val LATIN_MARKS = mapOf('　' to ' ', '・' to ' ')

    /**
     * [surface] with its [kana] over only the kanji, not over the kana written among them: と over 飛
     * and た over 立 in 飛び立つ, nothing over び or つ, and over only 食 in 食べる or 茶 in お茶. Where
     * the reading spells those kana another way, or doesn't have them, see [Furigana].
     */
    fun furigana(surface: String, kana: String): List<Ruby> = Furigana.over(surface, kana)

    /**
     * [parts] with each reading moved off the kana written among its kanji, for those kept when it
     * was laid over the whole of 飛び立: they show right without being read again.
     */
    fun rubied(parts: List<Ruby>): List<Ruby> = joined(
        parts.flatMap { part -> if (part.reading != null && part.text.any(::isKana)) furigana(part.text, part.reading).map { it.copy(by = part.by.takeIf { _ -> it.reading != null }) } else listOf(part) }
    )

    /** The word [text] with its [reading] over its kanji only, as one line's parts. */
    fun rubyOf(text: String, reading: String): List<Ruby> = rubied(listOf(Ruby(text, reading)))

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

    /**
     * How [text]'s Chinese is said, in plain letters, "晴天" as "qingtian", as someone who can't type
     * it would; null with none, or before Android 10, which has no pinyin.
     */
    @SuppressLint("NewApi")
    fun pinyinLetters(text: String): String? {
        val han = text.filter(::isHan).ifEmpty { return null }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return synchronized(this) { SongNames.normalize(pinyin().transliterate(han)) }
    }

    /** One line at a time, as Android's transliterator can't be used from two threads at once. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun chinese(line: String): LineReading = synchronized(this) {
        val parts = line.mapIndexed { index, char ->
            if (isHan(char) && !spelledInBopomofo(line, index)) Ruby(char.toString(), pinyin().transliterate(char.toString())) else Ruby(char.toString())
        }
        // Bopomofo is a reading already, so it's left as it's said.
        val said = line.mapIndexed { index, char -> if (spelledInBopomofo(line, index)) 'ㄧ' else char }.joinToString("")
        val romanized = pinyin().transliterate(said).trim()
        LineReading(joined(parts), romanized, romanized)
    }

    /** Whether [line]'s letter at [index] is 一 written for the bopomofo ㄧ it looks like, next to bopomofo: "ㄒ一" is "ㄒㄧ". */
    private fun spelledInBopomofo(line: String, index: Int): Boolean =
        line[index] == '一' && (line.getOrNull(index - 1)?.let(::isBopomofo) == true || line.getOrNull(index + 1)?.let(::isBopomofo) == true)

    /** A bopomofo letter, or a tone mark written with them. */
    private fun isBopomofo(c: Char) = c in 'ㄅ'..'ㄯ' || c in 'ㆠ'..'ㆿ' || c in "ˊˇˋ˙"

    /** Android's own letters-to-Latin, made once, the first time it's needed. */
    private var latinizer: Transliterator? = null

    /** [text] in plain Latin letters, as Cyrillic, Greek, Arabic, Hebrew, Indic and Thai are said. Words are only ever in these from Android 10 on, see displayScriptOf. */
    @SuppressLint("NewApi")
    private fun latin(text: String): String = synchronized(this) {
        val letters = latinizer ?: Transliterator.getInstance("Any-Latin; Latin-ASCII").also { latinizer = it }
        letters.transliterate(lettersAsSaid(text)).trim()
    }

    /** Android's own letters-to-Latin alone, which writes an Indic word letter by letter, marks and all. */
    private var indicLatinizer: Transliterator? = null

    /**
     * [text] with what Android writes in letters with marks that, once those are taken off, no one
     * reads as said: Russian's я, ж and ч are "ya", "zh" and "ch", not "a", "z" and "c"; Hindi's श
     * is "sh", and its words lose the a they aren't said with.
     */
    @SuppressLint("NewApi")
    private fun lettersAsSaid(text: String): String {
        val cyrillic = text.map { c -> CYRILLIC[c.lowercaseChar()]?.let { if (c.isUpperCase()) it.replaceFirstChar(Char::uppercaseChar) else it } ?: c.toString() }.joinToString("")
        val out = StringBuilder()
        var at = 0
        while (at < cyrillic.length) {
            val script = Character.UnicodeScript.of(cyrillic.codePointAt(at))
            if (script !in INDIC_SCRIPTS) {
                out.append(cyrillic[at++])
                continue
            }
            // The word, with its vowel signs and joiners.
            var end = at
            while (end < cyrillic.length && Character.UnicodeScript.of(cyrillic.codePointAt(end)).let { it == script || it == Character.UnicodeScript.INHERITED }) end++
            val word = cyrillic.substring(at, end)
            val letters = (indicLatinizer ?: Transliterator.getInstance("Any-Latin").also { indicLatinizer = it }).transliterate(word)
            out.append(indic(letters, hindiLike = script in HINDI_LIKE))
            at = end
        }
        return out.toString()
    }

    /** An Indic word's [letters], as Android writes them, the way they're said and typed. */
    private fun indic(letters: String, hindiLike: Boolean): String {
        var word = letters
        if (hindiLike) {
            // A word ending in a consonant's own a, after another vowel, isn't said with it: pyāra is pyār.
            word = FINAL_A.replace(word, "")
            // Nor is one between a vowel's consonant and the next's, taken from the end, as each one
            // taken changes the next: samajhanā is samajhnā, not samjhnā.
            while (true) word = MEDIAL_A.findAll(word).lastOrNull()?.let { word.removeRange(it.range) } ?: break
            // ड़ is typed d, as in "Ud Chala"; ऋ is ri, as in "kripa".
            word = word.replace("ṛ", "d").replace("r\u0325", "ri")
        }
        return word.replace(Regex("ch?")) { if (it.value == "ch") "chh" else "ch" }
            .replace("ś", "sh").replace("ṣ", "sh")
            .replace(Regex("ṁ(?=[pbm])"), "m").replace("ṁ", "n").replace("m\u0310", "n")
    }

    private fun latinized(line: String): LineReading = latin(line).let { LineReading(listOf(Ruby(line)), it, it) }

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

    /**
     * Kana in Latin letters, the Hepburn way: しゃ is sha, っ doubles what follows, ー lengthens the
     * vowel before; を is o, as it's said, or wo, as it's [typed].
     */
    fun romaji(kana: String, typed: Boolean = false): String {
        val hira = hiragana(kana)
        val out = StringBuilder()
        var double = false
        var afterN = false
        var i = 0
        while (i < hira.length) {
            val two = if (i + 1 < hira.length) hira.substring(i, i + 2) else null
            val syllable = two?.let(YOUON::get)?.also { i += 2 } ?: (if (typed && hira[i] == 'を') "wo" else KANA[hira[i]])?.also { i++ }
            if (syllable == null) {
                when (val c = hira[i]) {
                    'っ' -> double = true
                    'ー' -> out.lastOrNull { it in "aeiou" }?.let(out::append)
                    else -> out.append(LATIN_MARKS[c] ?: c)
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
        // The rest katakana writes words from other languages with: イェーガー, シェル, ジェット, チェック, ウォーク, ヴィーナス.
        put("いぇ", "ye"); put("うぉ", "wo"); put("しぇ", "she"); put("じぇ", "je"); put("ちぇ", "che"); put("とぅ", "tu"); put("どぅ", "du")
        put("つぁ", "tsa"); put("つぃ", "tsi"); put("つぇ", "tse"); put("つぉ", "tso"); put("ゔぃ", "vi"); put("ゔぇ", "ve"); put("ゔぉ", "vo")
    }

    /**
     * Hangul in Latin letters, the Revised Romanization way: as it's sung, where the end of a syllable
     * changes with the start of the next in the same word. 있어 is "isseo", 좋아 "joa", 같이 "gachi", 몰라 "molla".
     * A number in digits is said too, as the word it's part of: 1년 "illyeon".
     */
    fun romaja(hangul: String): String {
        val spelled = NUMBER_IN_A_WORD.replace(hangul) { number -> inHangul(number.value, hangul.substring(number.range.last + 1)) }
        return HANGUL_WORD.replace(spelled) { said(it.value) }
    }

    /**
     * [digits] as they're said before [counted]: in Korean's own numbers before what's counted with
     * them, as 12시 is 열두 시 and 2살 두 살, and otherwise in the ones from Chinese, as 7월 is 칠월.
     */
    private fun inHangul(digits: String, counted: String): String =
        // After another syllable, six's y sound takes an n: 십육 "simnyuk", 열여섯 "yeollyeoseot".
        number(digits.toLong(), counted).replace(SIX_AFTER) { if (it.value == "육") "뉵" else "녀" }

    private fun number(number: Long, counted: String): String {
        if (number in 1..99 && COUNTED_IN_KOREAN.any(counted::startsWith) && !counted.startsWith("개월")) {
            return if (number == 20L) "스무" else KOREAN_TENS[number.toInt() / 10] + KOREAN_ONES[number.toInt() % 10]
        }
        // June and October drop a sound: 유월, 시월.
        if (counted.startsWith("월") && number == 6L) return "유"
        if (counted.startsWith("월") && number == 10L) return "시"
        if (number == 0L) return "영"
        // In fours, as Korean counts: 만 is ten thousand, 억 a hundred million, 조 a trillion.
        var left = number
        var said = ""
        for (big in listOf("", "만", "억", "조")) {
            val four = (left % 10_000).toInt()
            left /= 10_000
            if (four > 0) said = (if (four == 1 && big == "만") "" else upTo9999(four)) + big + said
        }
        return said
    }

    /** A number up to 9999 in Korean's numbers from Chinese: 백칠십육 for 176, with no 일 before a ten, a hundred or a thousand. */
    private fun upTo9999(number: Int): String = listOf(1000 to "천", 100 to "백", 10 to "십", 1 to "").joinToString("") { (place, unit) ->
        when (val digit = number / place % 10) {
            0 -> ""
            1 -> if (unit.isEmpty()) "일" else unit
            else -> SINO_DIGITS[digit] + unit
        }
    }

    private fun said(word: String): String {
        val starts = word.map { CONSONANTS[(it - '가') / (21 * 28)] }.toMutableList()
        val vowels = word.map { VOWELS[(it - '가') % (21 * 28) / 28] }
        val ends = word.map { ENDS[(it - '가') % 28] }.toMutableList()
        for (i in 1 until word.length) {
            val (end, start) = joined(ends[i - 1], starts[i], vowels[i])
            ends[i - 1] = end
            starts[i] = start
        }
        return word.indices.joinToString("") { i ->
            // ㄹ after ㄹ is an l too.
            val start = if (starts[i] == 'ㄹ' && heard(ends.getOrElse(i - 1) { "" }) == 'ㄹ') "l" else STARTS.getValue(starts[i])
            start + vowels[i] + (heard(ends[i])?.let(LAST_SOUNDS::getValue) ?: "")
        }
    }

    /** The end of one syllable and the start of the next, as they're said together. */
    private fun joined(end: String, start: Char, vowel: String): Pair<String, Char> {
        // ㄷ and ㅌ before an i sound become ㅈ and ㅊ: 굳이 "guji", 같이 "gachi", 닫혀 "dachyeo".
        fun softened(c: Char) = if (vowel == "i" || vowel == "yeo") SOFTER[c] ?: c else c
        if (start == 'ㅇ') {
            // A vowel takes the sound before it, and ㅎ goes quiet: 있어 "isseo", 없이 "eopsi", 좋아 "joa".
            val left = end.removeSuffix("ㅎ")
            if (left.isEmpty() || left == "ㅇ") return left to start
            return left.dropLast(1) to softened(left.last())
        }
        // ㅎ and the sound next to it make one harder sound: 이렇게 "ireoke", 축하 "chuka".
        if (end.endsWith('ㅎ')) HARDER_AFTER_H[start]?.let { return end.dropLast(1) to it }
        if (start == 'ㅎ') end.lastOrNull()?.let(HARDER_BEFORE_H::get)?.let { return end.dropLast(1) to softened(it) }
        // Otherwise ㅎ goes quiet, or is said ㄴ before ㄴ: 놓는 "nonneun", 않는 "anneun".
        var heard = heard(if (end == "ㅎ" && start == 'ㄴ') "ㄴ" else end.removeSuffix("ㅎ"), start) ?: return "" to start
        var next = start
        when {
            // ㄴ and ㄹ together are both l: 신라 "silla", 설날 "seollal".
            next == 'ㄹ' && heard == 'ㄴ' -> heard = 'ㄹ'
            next == 'ㄴ' && heard == 'ㄹ' -> next = 'ㄹ'
            // ㄹ after any other sound is said n: 심리 "simni", 독립 "dongnip".
            next == 'ㄹ' && heard != 'ㄹ' -> next = 'ㄴ'
        }
        // And k, t and p go through the nose before n and m: 국물 "gungmul", 있는 "inneun", 합니다 "hamnida".
        if (next == 'ㄴ' || next == 'ㅁ') heard = NASAL[heard] ?: heard
        return heard.toString() to next
    }

    /** The one sound [end] makes closing a syllable before [next]: ㄺ is a k, but an l before ㄱ, as in 맑게 "malge". */
    private fun heard(end: String, next: Char? = null): Char? {
        val sound = when {
            end.isEmpty() -> return null
            end.length == 2 && end[0] == 'ㄹ' && end[1] in "ㄱㅁㅍ" && !(end[1] == 'ㄱ' && next == 'ㄱ') -> end[1]
            else -> end[0]
        }
        return CLOSED[sound] ?: sound
    }

    private val HANGUL_WORD = Regex("[가-힣]+")

    /** Russian's letters, with Ukrainian's and Belarusian's own, as they're written in English. */
    private val CYRILLIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya", 'є' to "ye", 'і' to "i", 'ї' to "yi", 'ґ' to "g", 'ў' to "u"
    )

    private val INDIC_SCRIPTS = setOf(
        Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.GUJARATI,
        Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM
    )

    /** Hindi's, Punjabi's and Gujarati's, whose words don't say all the a's their consonants are written with; Telugu's and Tamil's do. */
    private val HINDI_LIKE = setOf(Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.GUJARATI)

    /** A vowel and a consonant, with its h if it's said with a breath, as Android writes them. */
    private const val VOWEL = "[aāiīuūeēoō]"
    private const val CONSONANT = "(?:[kgcjṭḍtdpbṛ]h|[kgġṅcjñṭḍṇtdnpbmyrlvśṣshṛqzf])"

    /** A last consonant's own a, after another vowel. */
    private val FINAL_A = Regex("(?<=$VOWEL[^aāiīuūeēoō]{1,6})a$")

    /** A consonant's own a between a vowel's consonant and a consonant with a vowel of its own. */
    private val MEDIAL_A = Regex("(?<=${VOWEL}ṁ?$CONSONANT)a(?=$CONSONANT$VOWEL)")

    /** Digits a word in Hangul goes on from, 1년, but not ones in English, MP3를, or the end of a time or a decimal, 11:11, 3.5배. */
    private val NUMBER_IN_A_WORD = Regex("""(?<![A-Za-z0-9:.,])(?:0|[1-9][0-9]{0,15})(?=[가-힣])""")

    /** What's counted in Korean's own numbers rather than those from Chinese: hours, years of age, people, things. */
    private val COUNTED_IN_KOREAN = listOf("시", "살", "명", "개", "마리", "잔", "병", "사람", "가지", "곡", "송이", "걸음")
    private val KOREAN_TENS = listOf("", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")
    private val KOREAN_ONES = listOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")
    private const val SINO_DIGITS = "영일이삼사오육칠팔구"
    private val SIX_AFTER = Regex("(?<=.)(?:육|여(?=[섯덟]))")
    private const val CONSONANTS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val STARTS = CONSONANTS.toList().zip(listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")).toMap()
    private val VOWELS = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val ENDS = listOf(
        "", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ",
        "ㄹㅍ", "ㄹㅎ", "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
    )

    /** What a consonant sounds like closing a syllable, where only seven are told apart. */
    private val CLOSED = mapOf('ㄲ' to 'ㄱ', 'ㅋ' to 'ㄱ', 'ㅅ' to 'ㄷ', 'ㅆ' to 'ㄷ', 'ㅈ' to 'ㄷ', 'ㅊ' to 'ㄷ', 'ㅌ' to 'ㄷ', 'ㅎ' to 'ㄷ', 'ㅍ' to 'ㅂ')
    private val LAST_SOUNDS = mapOf('ㄱ' to "k", 'ㄴ' to "n", 'ㄷ' to "t", 'ㄹ' to "l", 'ㅁ' to "m", 'ㅂ' to "p", 'ㅇ' to "ng")
    private val SOFTER = mapOf('ㄷ' to 'ㅈ', 'ㅌ' to 'ㅊ')
    private val HARDER_AFTER_H = mapOf('ㄱ' to 'ㅋ', 'ㄷ' to 'ㅌ', 'ㅈ' to 'ㅊ', 'ㅅ' to 'ㅆ')
    private val HARDER_BEFORE_H = mapOf(
        'ㄱ' to 'ㅋ', 'ㄲ' to 'ㅋ', 'ㅋ' to 'ㅋ', 'ㄷ' to 'ㅌ', 'ㅅ' to 'ㅌ', 'ㅆ' to 'ㅌ', 'ㅌ' to 'ㅌ',
        'ㅈ' to 'ㅊ', 'ㅊ' to 'ㅊ', 'ㅂ' to 'ㅍ', 'ㅍ' to 'ㅍ'
    )
    private val NASAL = mapOf('ㄱ' to 'ㅇ', 'ㄷ' to 'ㄴ', 'ㅂ' to 'ㅁ')
}
