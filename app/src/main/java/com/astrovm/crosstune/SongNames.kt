package com.astrovm.crosstune

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.Normalizer
import java.util.Locale

/**
 * How two services' names for one song are compared. Every service writes a name its own way,
 * so "Song 2" is "Song 2 (Remastered 2012)", "Zion & Lennox" is "Zion Y Lennox" and
 * "ふたりの夏物語" is "FUTARI NO NATSU MONOGATARI". These say when two names are the same thing
 * anyway, and when they are close enough to be worth taking.
 */
internal object SongNames {
    private const val ARTICLE = "the"
    private val leadingArticle = phoneRegex("""^$ARTICLE\s+""", RegexOption.IGNORE_CASE)
    private val artistSeparator = phoneRegex(
        """$SPACES_START\s*+(?:,|&|\+|/|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b)\s*+""",
        RegexOption.IGNORE_CASE
    )
    private val marks = phoneRegex("""\p{M}+""")
    private val lettersAndDigits = phoneRegex("""[^\p{L}\p{N}]+""")
    private val wordSplit = phoneRegex("""[^a-z0-9]+""")

    /** "Zion and Lennox" and "Zion & Lennox" are two names, whichever language says so. */
    private val conjunction = phoneRegex("""$SPACES_START\s++(?:and|y|und|et)\s++""", RegexOption.IGNORE_CASE)

    /**
     * [title] without the guests it credits, as "(feat. Emel)", which some services leave out. Or written out, as a
     * video's title has it, "Despacito ft. Daddy Yankee", though not "6 ft. Under", up to the spaces before the next tag
     * or the end. Read in code as the pattern `\s*[(\[]\s*(?:feat|ft|featuring)\b[^()\[\]]*[)\]]|(?<!\d)\s+(?:feat\.?|ft\.?|
     * featuring)\s+[^()\[\]]*?(?=\s*[(\[]|$)` read it, from the left, as that pattern read on to the next bracket from
     * each place, and took time squared on a long title.
     */
    fun withoutFeaturing(title: String): String = CutTitle(title).apply { cutEach(CutTitle.ALL) { at, word -> guestEnd(this, at, word) } }.toString()

    /** Where a guest from [at] in [title] ends, [word] being where the spaces from there end, or null. */
    private fun guestEnd(title: CutTitle, at: Int, word: Int): Int? = guestInBrackets(title, word) ?: if (word > at) guestWrittenOut(title, at, word) else null

    /** "(feat. A)", at [open]: where it ends, or null. */
    private fun guestInBrackets(title: CutTitle, open: Int): Int? {
        if (open >= title.end || title[open] !in TAG_OPENS) return null
        val start = title.spacesEnd(open + 1)
        val word = featuringWord.matchAt(title.letters(start, LONGEST_WORD), 0) ?: return null
        val close = title.nextBracket(title.after(start, word.value.length))
        return if (close < title.end && title[close] in TAG_CLOSES) close + 1 else null
    }

    /** " ft. A", from the spaces at [at] before [word]: up to the spaces before the next tag, or to the end. Where it ends, or null. */
    private fun guestWrittenOut(title: CutTitle, at: Int, word: Int): Int? {
        if (title.afterDigit(at)) return null
        val said = featuringSaid.matchAt(title.letters(word, LONGEST_WORD), 0) ?: return null
        val guest = title.spacesEnd(title.after(word, said.value.length - 1))
        val bracket = title.nextBracket(guest)
        return when {
            bracket >= title.end -> title.lineEnd(guest)
            title[bracket] in TAG_CLOSES -> null
            else -> title.spacesStart(bracket, guest)
        }
    }

    private val featuringWord = phoneRegex("""(?:feat|ft|featuring)\b""", RegexOption.IGNORE_CASE)
    private val featuringSaid = phoneRegex("""(?:feat\.?|ft\.?|featuring)\s""", RegexOption.IGNORE_CASE)

    /** The most letters [featuringWord] and [featuringSaid] read: "featuring" and the one after it. */
    private const val LONGEST_WORD = 10
    private const val TAG_OPENS = "(["
    private const val TAG_CLOSES = ")]"

    /**
     * Where what marks [title] as a music video's from [at] ends, [mark] being where the spaces from there end, or null:
     * "M/V" or "(M/V)", as K-pop has it, which no store has it under. Read in code as the pattern `\s*[(\[]\s*M/V\s*[)\]]|
     * \s+M/V\b` read it.
     */
    private fun markEnd(title: CutTitle, at: Int, mark: Int): Int? = markInBrackets(title, mark)
        ?: if (mark > at && videoMarkAt.matchesAt(title.letters(mark, VIDEO_MARK.length + 1), 0)) title.after(mark, VIDEO_MARK.length) else null

    private fun markInBrackets(title: CutTitle, open: Int): Int? {
        if (open >= title.end || title[open] !in TAG_OPENS) return null
        val mark = title.spacesEnd(open + 1)
        val letters = title.letters(mark, VIDEO_MARK.length)
        if (letters.length < VIDEO_MARK.length || !VIDEO_MARK.indices.all { readsAs(letters[it], VIDEO_MARK[it]) }) return null
        val close = title.spacesEnd(title.after(mark, VIDEO_MARK.length))
        return if (close < title.end && title[close] in TAG_CLOSES) close + 1 else null
    }

    private const val VIDEO_MARK = "m/v"
    private val videoMarkAt = phoneRegex("""M/V\b""", RegexOption.IGNORE_CASE)

    private val guestCredit = phoneRegex("""[(\[]\s*(?:feat|ft|featuring)\b\.?\s*([^()\[\]]*)[)\]]""", RegexOption.IGNORE_CASE)

    /**
     * A trailing tag that names no other recording: "Remastered 2012" and "Mono" anywhere in
     * it, a whole "Album Version" or "Original", or what film or show it's the song of,
     * "(電影《我的少女時代》主題曲)". "Live", "Acoustic" or "Remix" are other recordings, so they stay.
     */
    private val editionTag = phoneRegex(
        """(?<!\s)(?:\s++[-–]\s++|\s*+[(\[]\s*+)(?=$IN_TAG*+\s*+[)\]]?$)""" +
            """(?:$EDITION_WORDS|$IN_TAG*\b(?:remaster(?:ed|iz(?:ed|ado|ada))?|mono|stereo)\b$IN_TAG*+|$IN_TAG*(?:$TIE_IN)$IN_TAG*+)\s*+[)\]]?$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * The [editionTag] that ends [title], as main's pattern found it, and where it was looked for from. A tag starts at
     * the last bracket that opens, or after it at one of the last two dashes, and holds no bracket that closes but one
     * it ends on, so it's looked for from there, and each pass of a long title's loop reads its end only.
     */
    private fun editionTagIn(title: CutTitle): TagLook {
        val first = title.next(0)
        val lastCloser = title.previous(title.lineEnd(first) - 1)
        var dashes = 0
        var at = title.previous(title.end - 1)
        while (at > first) {
            val char = title[at]
            val next = title.next(at + 1)
            val spacedDash = (char == '-' || char == '–') && next < title.end && isPatternSpace(title[title.previous(at - 1)]) && isPatternSpace(title[next])
            // A tag holds no bracket but the one that opens it and the one that closes it at the end.
            val bracket = char in TAG_OPENS || char in TAG_CLOSES && at != lastCloser
            if (bracket || spacedDash && ++dashes == 2) break
            at = title.previous(at - 1)
        }
        val start = title.spacesStart(maxOf(at, first))
        val (end, places) = title.lettersFrom(start)
        val tag = editionTag.find(end)?.range?.let { places[it.first]..places[it.last] }
        return TagLook(start, tag)
    }

    /**
     * What [editionTagIn] found, looking from [start]: none, if it's still looked at, as a tag found is cut or says it's
     * another recording. That holds until a cut takes a letter from [start] on, as all it read but where the spaces before
     * [start] begin is from there on, and those say only where a tag would start, not if there's one.
     */
    private class TagLook(val start: Int, val tag: IntRange?)

    private const val EDITION_WORDS = """original|(?:album|single|short|original|radio)\s+(?:version|edit|mix)"""

    /** The song a film or show opens, ends or plays it in, in Chinese, Japanese or English, or its soundtrack, as K-pop says. */
    private const val TIE_IN = """主[題题][曲歌]|挿入歌|插曲|片[頭头尾]曲|\bOST\b|\btheme\s+song\b"""

    /** A letter of a tag: none of a bracket, nor the dash before another part of the name, "Band - Song - Remastered". */
    private const val IN_TAG = """(?:(?!\s[-–]\s)[^()\[\]])"""

    /**
     * The small words a title may say either way, "de" or "del", "el" or "la": articles and
     * prepositions, not short words with a meaning of their own, so "Blue Sky" is never "Blue Sun".
     */
    private val smallWords = setOf(
        "a", "al", "an", "and", "au", "d", "da", "das", "de", "del", "der", "des", "di", "do", "dos", "du", "e", "el", "en", "et",
        "il", "in", "l", "la", "las", "le", "les", "lo", "los", "of", "the", "un", "una", "une", "uno", "y"
    )

    /** A shorter name has to be at least this long to count when it's inside another's. */
    private const val MIN_PARTIAL_ARTIST = 5

    /**
     * The names a song may go by: its title, then, for one that's two names joined by a dash, as
     * YouTube Music gives "ミステリー・ガール - Mystery Girl", each of them. A video's whole title, said
     * again in Japanese, as Recent kept it before it was read better, goes by the song before that.
     * And a name written both ways apart by a wide space, as "悲しみがとまらない　I CAN'T STOP THE
     * LONELINESS", goes by each way.
     */
    fun titleNames(title: String): List<String> {
        val halves = title.split(" - ").map(String::trim).filter(String::isNotEmpty).takeIf { it.size == 2 }.orEmpty()
        return listOf(title) + listOfNotNull(MetadataParsers.beforeItInJapanese(title)) + halves + (listOf(title) + halves).flatMap(::bothWays)
    }

    /** Its two ways, when it's written in Chinese, Japanese or Korean and then in Latin letters, apart by a wide space. */
    private fun bothWays(name: String): List<String> {
        val ways = name.split(WIDE_SPACE).map(String::trim).filter(String::isNotEmpty)
        return ways.takeIf { it.size == 2 && it.first().any(::isEastAsian) && it.last().none(::isEastAsian) && it.last().any(Char::isLetter) }.orEmpty()
    }

    private fun isEastAsian(letter: Char) = Character.UnicodeScript.of(letter.code) in EAST_ASIAN

    private const val WIDE_SPACE = '\u3000'
    private val EAST_ASIAN = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)

    /** Letters and digits only, no marks and no case: "Édition" and "Edition" are one word. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT)
            .replace(lettersAndDigits, "")

    /** "The" only counts as an article when it is a word of its own, so not in "Thelonious". */
    fun withoutArticle(name: String): String = name.replace(leadingArticle, "")

    /** The words written in letters and digits: another script's are what the other title spells out. */
    fun words(title: String): Set<String> = wordList(title).toSet()

    private fun wordList(title: String): List<String> =
        Normalizer.normalize(title, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT)
            .split(wordSplit).filter { it.isNotEmpty() }

    /**
     * "Song - Remastered 2012", "Song (2003 Remaster)" and "Song (feat. A)" are the song itself; "Song
     * (Live; 2013 Remaster)" and "Song - Stereo MC's Remix" are other recordings, so they keep their tags.
     */
    fun withoutEditionTag(title: String): String = withoutEditionTag(CutTitle(title))

    /** [withoutEditionTag] on [cut], which counts the steps it takes. */
    internal fun withoutEditionTag(cut: CutTitle): String {
        var guestsSince = CutTitle.ALL
        var marksSince = CutTitle.ALL
        var look: TagLook? = null
        // How many cuts there were when [look] was last found to hold.
        var lookedAt = 0
        // "Song - 2012 Remaster (Mono)" has two, and a title that's only a tag is left as it is.
        while (true) {
            val pass = cut.cuts
            cut.cutEach(guestsSince) { at, word -> guestEnd(cut, at, word) }
            guestsSince = pass
            val marks = cut.cuts
            cut.cutEach(marksSince) { at, mark -> markEnd(cut, at, mark) }
            marksSince = marks
            val tag = look?.takeUnless { cut.cutSince(lookedAt, it.start) } ?: editionTagIn(cut)
            lookedAt = cut.cuts
            look = tag
            val range = tag.tag
            if (range != null && words(cut.letters(range)).any { it in OTHER_RECORDING }) return cut.toString()
            if (range != null) cut.cut(range.first, range.last + 1)
            if (cut.cuts == pass || cut.isBlank()) return cut.before(pass)
        }
    }

    /**
     * [name] says it's another version of [title] by [artist]: a cover, karaoke, live, a remix… unless
     * the song's own name says so, "Bzrp Music Sessions" or "Little Mix".
     */
    fun saysAnotherVersion(name: String, title: String, artist: String): Boolean {
        val own = words(title) + words(artist)
        if ((words(name) - own).any { it in VERSION_WORDS }) return true
        val letters = normalize(name)
        return VERSION_MARKS.any { it in letters && it !in normalize(title) && it !in normalize(artist) }
    }

    /** Words a video's title has when it's another version of the song, or not the song at all. */
    private val VERSION_WORDS = setOf(
        "cover", "covers", "covered", "karaoke", "live", "unplugged", "mtv", "vivo", "directo", "session", "sessions",
        "concert", "tour", "remix", "mix", "instrumental", "inst", "acoustic", "acustico", "piano", "guitar",
        "bass", "drum", "drums", "chords", "tab", "tabs", "tutorial", "lesson", "practice", "reaction", "slowed",
        "reverb", "sped", "nightcore", "8d", "demo", "medley", "mashup", "amv", "mmd", "ver", "tribute", "ktv",
        "acapella", "acappella", "cappella", "capella", "8bit", "orgel", "musicbox"
    )

    /**
     * The same, as other scripts write them, found anywhere in a title; written as [normalize]
     * leaves them, which takes the marks off ブ and splits 커버 into its letters.
     */
    private val VERSION_MARKS = listOf(
        "カラオケ", "ライブ", "弾き語り", "歌ってみた", "演奏してみた", "弾いてみた", "叩いてみた", "ピアノ", "ギター",
        "カバー", "メドレー", "練習", "オルゴール", "アカペラ", "翻唱", "伴奏", "伴唱", "卡拉ok", "커버", "라이브", "노래방"
    ).map(::normalize)

    /**
     * Whether [song] is many songs in one, which has no one song's words to find: a medley, a mashup,
     * a whole concert or album played as one, a playlist or compilation, "50 Viral Audios", or three or
     * more songs named apart by slashes.
     */
    fun manySongs(song: MusicMetadata): Boolean {
        val names = "${song.title} ${song.artist}"
        val words = wordList(names)
        if (words.any { it in MANY_SONGS_WORDS } || MANY_SONGS_MARKS.any { it in normalize(names) } || countsSongs(words)) return true
        return wholeShow.containsMatchIn(names) || song.title.split(" / ").count { it.isNotBlank() } >= MIN_SONGS_IN_A_TITLE
    }

    /** Whether [words] count songs, as "Top 20 Hits" or "50 Viral TikTok Audios" do: many of them, a few words on. */
    private fun countsSongs(words: List<String>): Boolean = words.indices.any { at ->
        (words[at].toIntOrNull() ?: 0) >= MIN_SONGS_COUNTED && words.subList(at + 1, minOf(words.size, at + 1 + COUNTED_WITHIN)).any { it in SONGS_COUNTED }
    }

    private val MANY_SONGS_WORDS = setOf(
        "medley", "medleys", "mashup", "megamix", "potpourri", "popurri", "popurris", "enganchados",
        "playlist", "playlists", "compilation", "compilacion", "compilado", "compilacao", "recopilacion"
    )

    /** What a count of many songs counts. */
    private val SONGS_COUNTED = setOf("songs", "hits", "audios", "tracks", "canciones", "temas", "exitos", "musicas", "cancoes", "chansons", "titres", "lieder")
    private const val MIN_SONGS_COUNTED = 10
    private const val COUNTED_WITHIN = 3
    private val MANY_SONGS_MARKS = listOf("メドレー", "串烧", "串燒", "메들리").map(::normalize)

    /** A whole show or album as one, in the languages its uploads are named in: "Full Concert", "(Recital Completo)". */
    private val wholeShow = phoneRegex(
        """(?<![\p{L}\p{N}])(?:(?:full|complete|whole|entire)\s+(?:concert|show|album|live\s+show|set)|""" +
            """(?:concierto|recital|show|[áa]lbum|disco|concerto|konzert|concert)\s+(?:completo|completa|entero|inteiro|integral|komplett|complet|intero))(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )

    private const val MIN_SONGS_IN_A_TITLE = 3

    /** Words in a tag that make it another recording, though it also says it's remastered or in mono. */
    private val OTHER_RECORDING = setOf("live", "remix", "acoustic", "demo", "instrumental", "unplugged", "karaoke", "cover", "session", "sessions")

    /**
     * "A & B feat. C" is credited as just "A" on some services, so each name counts on its own,
     * and "The" doesn't count. The whole credit counts too, for a name that holds a separator:
     * "125, Rue Montmartre" is one artist, not a "125" and a "Rue Montmartre".
     */
    fun artists(credit: String): Set<String> =
        (artistNames(credit).flatMap { it.split(conjunction) } + credit)
            .map { name -> normalize(name.replace(leadingArticle, "")) }.filter { it.isNotEmpty() }.toSet()

    /** Each name in a credit, split on the separators services use between them. */
    fun artistNames(credit: String): List<String> =
        credit.split(artistSeparator).map { it.trim() }.filter { it.isNotEmpty() }

    /** The same name, whatever punctuation, spacing, accents or case write it. */
    fun same(one: String, other: String): Boolean =
        normalize(withoutEditionTag(one)) == normalize(withoutEditionTag(other))

    /**
     * [name] with its kanji written as China writes them, so a name NetEase writes so reads as the
     * same; as it is before Android 10, which can't.
     */
    fun asChinaWrites(name: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && name.any(Readings::isHan)) ChinasKanji.write(name) else name

    /**
     * Android's way of writing kanji as China does, 终 for 終 and 仆 for 僕, where it has one.
     * Kanji Japan simplified its own way, 楽 for 樂, it leaves.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private object ChinasKanji {
        private val kanji: Transliterator? = runCatching { Transliterator.getInstance("Hant-Hans") }.getOrNull()

        fun write(name: String): String = kanji?.let { synchronized(it) { it.transliterate(name) } } ?: name
    }

    /**
     * [one] and [other] are one name but for some kanji, as NetEase writes Japanese titles with
     * China's characters, "终わらない歌" for "終わらない歌": each kanji where the other has one,
     * and most of the name the same.
     */
    fun sameButKanji(one: String, other: String): Boolean {
        val first = normalize(withoutEditionTag(one))
        val second = normalize(withoutEditionTag(other))
        if (first.length != second.length) return false
        val differ = first.indices.filter { first[it] != second[it] }
        return differ.all { Readings.isHan(first[it]) && Readings.isHan(second[it]) } && differ.size * 2 < first.length
    }

    /**
     * [candidate] is the recording [title] names, also when it says what version that is other
     * than in its name: "Song (Remix)" is "Song (2022 Remix)", or "Song" on the album "NEVER
     * ENDING SUMMER REMIX". The original "Song", on an album that doesn't say so, is another.
     */
    fun sameVersion(title: String, candidate: String, album: String = ""): Boolean {
        if (same(title, candidate)) return true
        val named = withoutEditionTag(title).trim()
        val bare = bareTitle(named)
        if (bare == named || normalize(bare) != normalize(bareTitle(withoutEditionTag(candidate)))) return false
        // What the tags say, "(Remix)", has to be said there too.
        val version = words(named.removePrefix(bare))
        return version.isNotEmpty() && (words(candidate) + words(album)).containsAll(version)
    }

    /**
     * The song a cover covers, when its title says it's one: "Song (Cyberpunk Shamisen Cover)", "Song -
     * Acoustic Cover", "曲 (歌ってみた)". A karaoke track's may name whose it is, "Song (In the Style of
     * Artist)". Null for a title that doesn't say so, which is the artist's own song.
     */
    fun coverOf(title: String): Covered? {
        val tag = bracketTags(title, COVER_OPENS, COVER_CLOSES).firstOrNull { coverWords.containsMatchIn(title.substring(it)) }
            ?: coverAfterDash(title) ?: return null
        val song = bareTitle(title.substring(0, tag.first)).ifEmpty { return null }
        // Where tags hold tags, the name ends at one, "(In the Style of Artist (Live))"; where not, at the tag's end.
        val by = if (bracketsInOrder(title, COVER_OPENS, COVER_CLOSES)) coverByOwnName else coverBy
        return Covered(song, by.find(title.substring(tag))?.groupValues?.get(1)?.trim()?.ifEmpty { null })
    }

    /**
     * "Song - Acoustic Cover": a dash between spaces, then a cover's words on the last line with no dash between, to the
     * end. Read in code as the pattern `\s[-–]\s[^-–]*(?:words).*$` read it, which tried each dash, and took time squared
     * on a long title: here each of the words on the last line is read once, with the last dash before it, by one look
     * along it, as on a phone each new look copies the title first.
     */
    private fun coverAfterDash(title: String): IntRange? {
        val end = lineEnd(title)
        var lineStart = end
        while (lineStart > 0 && !isLineBreak(title[lineStart - 1])) lineStart--
        // Words on the last line end before its line break, if any: what's after [end] is that line break alone.
        val marks = TrailingMarks(title)
        val words = coverWords.toPattern().matcher(title)
        var found = words.find(lineStart)
        while (found) {
            // The space after the dash comes before the words, which start with a letter.
            val dash = marks.dashBefore(words.start())
            if (isBetweenSpaces(title, dash)) return dash - 1 until end
            found = words.find()
        }
        return null
    }

    /**
     * Whether [title] says no one sings in the recording: "Song (Instrumental)", "Song - Inst.", "曲 (伴奏版)",
     * "曲 off vocal ver.". Not a karaoke track's or a cover's, which is sung along to, by its title or a karaoke label's name, nor one that says a voice
     * is in it too, "Song (Vocal & Instrumental)", or names a singer after the tag, "Song (Instrumental Mix) [feat. Singer]". Nor a tag that names a
     * part of it, "(with Instrumental Break)", or something else, "(Live at MIT Inst.)", "- Live at the Instrumental Hall", nor a song of that name,
     * "Band - Instrumental" by Band, or by their channel, "BandVEVO", nor one that says it isn't, "(Not Instrumental)", or names who
     * made it, "(Prod. Instrumental)", nor one with a singer after it, "Song (Instrumental) ft. Singer", "... with Singer", "... x Singer".
     * Of 2,551 song names the iTunes Store gave, all that say so are instrumentals (Issue #861).
     */
    fun saysInstrumental(title: String, artist: String): Boolean {
        // Written in full-width or half-width letters, "（Ｉｎｓｔ）" or "ｵﾌﾎﾞｰｶﾙ", it says the same.
        val name = Normalizer.normalize(title, Normalizer.Form.NFKC)
        if (sungAlong(name, artist) || coverOf(name) != null || voiceIn.containsMatchIn(name)) return false
        // A guest named before the tag is the song's, "Song (feat. Guest) [Instrumental]"; after it, they sing in this mix.
        val lastSinger = singerAfter.matchesIn(name).lastOrNull()?.range?.first ?: -1
        fun says(tag: MatchResult) = onlyInstrumental(tag.groupValues[1]) && tag.range.first > lastSinger
        // What follows "Artist - " is the song's name, not a tag; a video's channel is named for the artist.
        val by = artist.replace(channel, "")
        val before = if (by.isBlank()) null else ArtistBefore(name, by)
        return bracketTag.matchesIn(name).any(::says) || dashTag.matchesIn(name).any { says(it) && before?.isTheirs(it.range.first) != true } ||
            offVocalAtEnd.containsMatchIn(name)
    }

    /**
     * Whether what's before each dash of [name] is by the artist [by], as [sameArtist] says of [name] up to it, asked of its
     * dashes in order, those after a space and the tildes that [dashTag] finds. On a phone each look copies what it reads,
     * and reading the title up to each of many dashes again took time squared: this reads each part of it once. A dash or
     * a tilde starts no letter, mark or word of what's before it, so what's before one reads as the start of what's before
     * the next: the names that end before it stay, and the last name and the whole only go on.
     */
    internal class ArtistBefore(private val name: String, by: String) {
        private val theirs = artists(by).toList()
        private val longest = theirs.maxOfOrNull { it.length } ?: 0

        /** Once a name ended by a separator or a conjunction is theirs, it's before each later dash too. */
        private var settled = theirs.isEmpty()
        private val whole = Letters(leadingArticle.find(name)?.range?.last?.plus(1) ?: 0)
        private val separators = artistSeparator.matchesIn(name).iterator()
        private var separator = separators.nextOrNull()
        private var piece = Piece(0)

        /** Whether what's before [dash], the next dash asked of, is by them. */
        fun isTheirs(dash: Int): Boolean {
            var ended = separator
            while (!settled && ended != null && ended.range.last < dash) {
                settled = partsAreTheirs(name.substring(piece.start, ended.range.first).trim())
                separator = separators.nextOrNull()
                piece = Piece(ended.range.last + 1)
                ended = separator
            }
            if (settled) return true
            whole.readTo(dash)
            return whole.areTheirs(0) || piece.isTheirs(dash)
        }

        /** Whether a name between separators, or one of the names a conjunction joins in it, is theirs, as [artists] splits it. */
        private fun partsAreTheirs(piece: String) = piece.isNotEmpty() && piece.split(conjunction).any(::nameIsTheirs)

        private fun nameIsTheirs(part: String) = normalize(part.replace(leadingArticle, "")).let { it.isNotEmpty() && isOneOf(it, theirs) }

        /** The name from the last separator on, as [artistNames] trims it, and the names a conjunction joins in it. */
        private inner class Piece(val start: Int) {
            private val end = separator?.range?.first ?: name.length
            private val trimmed = (start until end).firstOrNull { !name[it].isWhitespace() } ?: end
            private val joins = conjunction.matchesIn(name.substring(trimmed, end)).iterator()
            private var join = joins.nextOrNull()
            private var partStart = trimmed
            private var part = Letters(trimmed)

            fun isTheirs(dash: Int): Boolean {
                var until = dash
                while (until > trimmed && name[until - 1].isWhitespace()) until--
                if (until <= trimmed) return false
                var next = join
                while (!settled && next != null && trimmed + next.range.last < until) {
                    settled = nameIsTheirs(name.substring(partStart, trimmed + next.range.first))
                    partStart = trimmed + next.range.last + 1
                    part = Letters(partStart)
                    next = joins.nextOrNull()
                    join = next
                }
                if (settled) return true
                // A conjunction that ends past the trimmed name joins there too if its word and a space are in it: then the last name is empty.
                if (next != null && trimmed + next.range.first < until && wordEnd(trimmed + next.range.last + 1) < until) {
                    return nameIsTheirs(name.substring(partStart, trimmed + next.range.first))
                }
                part.readTo(dash)
                val article = leadingArticle.containsMatchIn(name.substring(partStart, minOf(until, partStart + ARTICLE.length + 1)))
                return part.areTheirs(if (article) normalize(name.substring(partStart, partStart + ARTICLE.length)).length else 0)
            }

            /** Where the word of a conjunction ending at [end] ends, before the spaces after it. */
            private fun wordEnd(end: Int): Int {
                var at = end
                while (isPatternSpace(name[at - 1])) at--
                return at
            }
        }

        /** The letters of [name] from [from] on, as [normalize] reads them, read on to each dash, and which of theirs are in them. */
        private inner class Letters(from: Int) {
            private val letters = StringBuilder()
            private var read = from
            private var drop = 0
            private val lookFrom = IntArray(theirs.size)
            private val found = BooleanArray(theirs.size)

            fun readTo(dash: Int) {
                if (dash <= read) return
                letters.append(normalize(name.substring(read, dash)))
                read = dash
            }

            /** Whether these letters but the first [drop], which an article took, are a name of theirs, as [isOneOf] says. */
            fun areTheirs(drop: Int): Boolean {
                if (drop != this.drop) {
                    this.drop = drop
                    lookFrom.fill(drop)
                    found.fill(false)
                }
                val length = letters.length - drop
                if (length <= longest) return length > 0 && isOneOf(letters.substring(drop), theirs)
                // Longer than each of theirs, they are theirs only with one of theirs in them, which stays as they go on.
                return theirs.indices.any(::holds)
            }

            private fun holds(index: Int): Boolean {
                val wanted = theirs[index]
                if (!found[index] && wanted.length >= MIN_PARTIAL_ARTIST) {
                    found[index] = letters.indexOf(wanted, lookFrom[index]) >= 0
                    lookFrom[index] = maxOf(drop, letters.length - wanted.length + 1)
                }
                return found[index]
            }
        }

        private fun <T> Iterator<T>.nextOrNull() = if (hasNext()) next() else null
    }

    /** Whether [title] or [artist] says it's a track to sing along to, which shows the song's words: see [singAlong]. */
    fun sungAlong(title: String, artist: String): Boolean = listOf(Normalizer.normalize(title, Normalizer.Form.NFKC), artist).any(singAlong::containsMatchIn)

    /**
     * Whether an [album]'s name says it's of instrumentals, "Album (Instrumentals)", "Album (Off Vocal Ver.)", "专辑 (伴奏版)",
     * "Album (Karaoke Version)", "Album (Backing Tracks)": its recordings are named as the sung ones. "Inst." and "MR", short
     * for other words too, say so only as a whole tag, "Album (Inst.)", "Album (MR)".
     */
    fun albumOfInstrumentals(album: String): Boolean {
        val name = Normalizer.normalize(album, Normalizer.Form.NFKC).replace(instrumentals, "")
        return instrumentalWord.matchesIn(name).any { !shortForm.matches(it.value) } || accompaniment.containsMatchIn(name) ||
            (bracketTag.matchesIn(name) + dashTag.matchesIn(name)).any { onlyInstrumental(it.groupValues[1]) }
    }

    /** Whether a [tag], what's in brackets or after a dash, says the whole recording is an instrumental. */
    private fun onlyInstrumental(tag: String): Boolean {
        val said = instrumentalWord.find(tag) ?: return false
        val rest = words(tag.removeRange(said.range))
        if (rest.any { it in PART_WORDS || it in NOT_SAID }) return false
        // "Inst." and "MR" are short for other words too, so they say it alone, or with "Ver." only.
        return !shortForm.matches(said.value) || rest.all { it in SAYS_VERSION }
    }

    /** A cover's song: its [title], and its [artist] when the cover's title names them. */
    data class Covered(val title: String, val artist: String?)

    private const val COVER_WORDS = """\bcover(?:ed)?\b|\bin the style of\b|\boriginally (?:performed )?by\b|\bmade famous by\b|カバー|歌ってみた|翻唱|커버"""
    private val coverWords = phoneRegex("""(?:$COVER_WORDS)""", RegexOption.IGNORE_CASE)

    private const val COVER_OPENS = "([【"
    private const val COVER_CLOSES = ")]】"
    private const val DASHES = "-–"

    /** Whose song it is, up to the end of the tag. */
    private val coverBy = phoneRegex("""(?:$COVER_BY)\s+([^)\]】]+)""", RegexOption.IGNORE_CASE)

    /** The same, up to the start of a tag inside it too, "(In the Style of Artist (Live))". */
    private val coverByOwnName = phoneRegex("""(?:$COVER_BY)\s+([^(\[【)\]】]+)""", RegexOption.IGNORE_CASE)
    private const val COVER_BY = """in the style of|originally (?:performed )?by|made famous by|cover of"""

    /**
     * A word saying no one sings, not inside another word, nor the end of one joined by a hyphen, "Show-Off Vocal".
     * A word in Chinese or Japanese may say "version" after it, "伴奏版".
     */
    private val instrumentalWord = phoneRegex(
        """(?<![\p{L}\p{N}])(?<![\p{L}\p{N}]-)(?:instrumentale?|inst\.?|off[ -]?vocal|(?-i:MR)|인스트루멘탈|""" +
            """(?:伴奏|オフボーカル|纯音乐|純音樂|純音楽)(?:版本?|バージョン)?)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )
    private val shortForm = phoneRegex("""inst\.?|MR""", RegexOption.IGNORE_CASE)
    private val instrumentals = phoneRegex("""(?<=instrumental)s\b""", RegexOption.IGNORE_CASE)

    /** A voice in the recording, other than in "off vocal": "(Vocal Mix)", "(A Cappella & Instrumental)". */
    private val voiceIn = phoneRegex("""(?<!off )(?<!off-)\bvocals?\b|\ba[ -]?capp?ella\b""", RegexOption.IGNORE_CASE)

    /**
     * What a tag naming a part of a song, or what it's from, or where it was played, says: "(with Instrumental Break)", "(From the film
     * Instrumental)", "- Live at the Instrumental Hall".
     */
    private val PART_WORDS = setOf("with", "intro", "outro", "break", "interlude", "bridge", "from", "film", "movie", "live", "hall")

    /** What says a tag isn't the recording's: "(Not Instrumental)", or names who made it, "(Prod. Instrumental)". */
    private val NOT_SAID = setOf("not", "non", "prod", "produced")

    /**
     * Where a singer may be named, in brackets or not: "[feat. Singer]", "ft. Singer", "with Singer", "x Singer". Each
     * place, as it reads no letter: one after a tag names a singer after it. A tag starts with a bracket, a dash or a
     * tilde, which no singer's word is, so one isn't named at the start of what follows it.
     */
    private val singerAfter = phoneRegex("""(?=[\s(\[](?:feat\.?|ft\.?|featuring|with|x|×)\s+\S)""", RegexOption.IGNORE_CASE)

    /** An album of tracks to sing over, "(Karaoke Version)", "(Backing Tracks)", "(Minus One)". */
    private val accompaniment = phoneRegex("""karaoke|カラオケ|\bbacking tracks?\b|\bminus ones?\b""", RegexOption.IGNORE_CASE)

    /** What YouTube adds to an artist's name for their channel: "BandVEVO", "Band - Topic". */
    private val channel = phoneRegex("""VEVO$|$SPACES_START\s*+-\s*+Topic$""")
    private val SAYS_VERSION = setOf("ver", "version", "versao")

    /** What's in brackets, of any kind Chinese and Japanese titles use, once full-width ones are written as others. */
    private val bracketTag = phoneRegex("""[(\[【「〔]([^()\[\]【】「」〔〕]*)[)\]】」〕]""")

    /** What follows a dash, or a tilde, which a Japanese title may put right after its name: "曲〜伴奏〜". */
    private val dashTag = phoneRegex("""(?:(?<=\s)[-–]\s?|[~〜])([^-–~〜]*)""")

    /**
     * "Off vocal" ending the title with nothing around it, after a name in Chinese, Japanese or Korean, "曲 Off Vocal",
     * or with "ver." after it, "Song off vocal ver.": "Kiss Off Vocal" may be the song "Kiss Off" sung.
     */
    private val offVocalAtEnd = phoneRegex(
        """(?:(?<=[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uac00-\ud7af])\s*off[ -]?vocal(?:\s+ver(?:sion|\.)?)?|\soff[ -]?vocal\s+ver(?:sion|\.)?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    /**
     * A track made to sing along to, as karaoke labels name theirs, "Song (Made Popular By Singer) [Instrumental Version]"
     * by "Party Tyme Karaoke", or one shown with the words, "(Instrumental + Lyrics)", or to sing over, "(Minus One)",
     * "(Backing Track)". These show the words of the song they play, as before; whether they should is still to be decided (Issue #922).
     */
    private val singAlong = phoneRegex("""karaoke|カラオケ|\bmade popular by\b|\blyrics?\b|\bsing[- ]?along\b|\bminus one\b|\bbacking track\b""", RegexOption.IGNORE_CASE)

    /** The name without what it adds in brackets or after a dash: "Song (2018 Remix) (2024 Remaster)" is "Song". */
    fun bareTitle(title: String): String {
        val text = title.trim()
        // It's text up to [end], and the line break that ended it if a tag ended before that, as "$" may. Kept in
        // place as each tag goes, with where each bracket and dash before a place is, so a long title is read once.
        val marks = TrailingMarks(text)
        var end = beforeLineBreak(text, text.length)
        var lineBreak = text.substring(end)
        while (true) {
            val (start, afterDash) = marks.tagStart(end, lineBreak) ?: break
            // A tag that runs to the very end takes the spaces before it, and leaves the line break before those, if any.
            val toTheEnd = afterDash || lineBreak.isEmpty()
            if (!toTheEnd) {
                end = start
                continue
            }
            val kept = trimmedEnd(text, start)
            if (kept == 0) break
            end = beforeLineBreak(text, kept)
            lineBreak = text.substring(end, kept)
        }
        return text.substring(0, end) + lineBreak
    }

    /** [end], or right before the line break that [text] has there. */
    private fun beforeLineBreak(text: String, end: Int) = if (end > 0 && isLineBreak(text[end - 1])) end - 1 else end

    private fun trimmedEnd(text: String, end: Int): Int {
        var at = end
        while (at > 0 && text[at - 1].isWhitespace()) at--
        return at
    }

    /**
     * Where a [bareTitle]'s last tag starts, as the pattern `(?:\s*[(\[][^()\[\]]*[)\]]|\s+[-–]\s+[^-–]*)$` found it in
     * the text up to a place: a tag in brackets ends right before it, or before the line break that ends it, and a part
     * after a dash runs from the last dash to the very end, line break and all. With whether it's after a dash.
     */
    private class TrailingMarks(private val text: String) {
        private val lastBracket = IntArray(text.length + 1)
        private val lastDash = IntArray(text.length + 1)

        init {
            var bracket = -1
            var dash = -1
            for (at in text.indices) {
                lastBracket[at] = bracket
                lastDash[at] = dash
                if (text[at] in "()[]") bracket = at
                if (text[at] in DASHES) dash = at
            }
            lastBracket[text.length] = bracket
            lastDash[text.length] = dash
        }

        /** The last dash before [place], or -1. */
        fun dashBefore(place: Int) = lastDash[place]

        /** In the text up to [end], then [lineBreak]: what's left once tags that ended before that line break went. */
        fun tagStart(end: Int, lineBreak: String): Pair<Int, Boolean>? {
            val open = if (end > 0 && text[end - 1] in TAG_CLOSES) lastBracket[end - 1] else -1
            val inBrackets = if (open >= 0 && text[open] in TAG_OPENS) spacesStart(text, open) else Int.MAX_VALUE
            val dash = lastDash[end]
            // The space after the dash is in the text up to [end], not one a tag in brackets after it had, or it's the
            // line break: on a phone `\s` reads U+0085 as a space, which trimming leaves.
            val after = if (dash < end - 1) text.getOrNull(dash + 1) else lineBreak.firstOrNull()
            val spaced = dash > 0 && isPatternSpace(text[dash - 1]) && after?.let(::isPatternSpace) == true
            val afterDash = if (spaced) spacesStart(text, dash) else Int.MAX_VALUE
            return when {
                afterDash < inBrackets -> afterDash to true
                inBrackets < Int.MAX_VALUE -> inBrackets to false
                else -> null
            }
        }
    }

    /**
     * Whether [one] and [other] are releases of one song, as Shazam calls the song an app plays "Song (Live
     * at Home)": by one artist, and one name but for what each adds after it. Another song isn't, however
     * near where the song is it's heard.
     */
    fun releasesOfOneSong(one: MusicMetadata, other: MusicMetadata): Boolean =
        same(bareTitle(one.title), bareTitle(other.title)) && (sameArtist(one.artist, other.artist) || sameArtist(other.artist, one.artist))

    /**
     * Whether what a player names by [title] and [artist] is [song]. A video's title names more, e.g.
     * "Artist - Song (Official Video)", and its channel may be named "Artist - Topic", or not be the
     * artist at all, when the title names them: "Police, The   Every Little Thing She Does Is Magic
     * Audio Flac" by "keyloginhot".
     */
    fun names(title: String, artist: String, song: MusicMetadata): Boolean {
        if (title.isBlank()) return false
        // Read as the song it names the way the song playing is named, e.g. "YOASOBI「アイドル」 Official Music Video".
        val named = MetadataParsers.youtubeVideo(title, artist)?.title
        if (!(same(title, song.title) || wordsMatch(title, song.title) || named?.let { same(it, song.title) } == true)) return false
        return artist.isBlank() || artistInside(artist, song.artist) || sameArtist(song.artist, artist) ||
            artistNames(song.artist).map(::words).any { it.isNotEmpty() && words(title).containsAll(it) }
    }

    /**
     * Whether [loanword], a name in katakana, says [latin], as "プラスティック・ラブ" does "Plastic
     * Love". Japanese puts vowels between consonants, reads l as r, v as b, and leaves out an r
     * that ends a sound, so the two are compared by the consonants they're said with, one off.
     * Said with fewer than [fewest] consonants, a name is too short to tell.
     */
    fun soundsAlike(latin: String, loanword: String, fewest: Int = MIN_SOUNDS): Boolean {
        val katakana = bareTitle(loanword)
        if (!katakana.all { it in '゠'..'ヿ' || it.isWhitespace() }) return false
        val said = consonants(Readings.romaji(katakana))
        val written = consonants(bareTitle(latin))
        if (said.length < fewest) return false
        return if (said.length < FUZZY_SOUNDS) said == written else oneEditApart(said, written)
    }

    /** The consonants [text] is said with, those that sound alike as one: "Plastic Love" is "prstkrb". */
    private fun consonants(text: String): String {
        var said = text.lowercase().filter { it in 'a'..'z' }
        for ((from, to) in sounds) said = said.replace(from, to)
        val kept = StringBuilder()
        for (c in said) if (c !in "aeiouhwy" && kept.lastOrNull() != c) kept.append(c)
        return kept.toString()
    }

    /** Whether [one] becomes [other] with one letter changed, added or taken away at most: [distance] at most 1, in time in step with them. */
    internal fun oneEditApart(one: String, other: String): Boolean {
        if (kotlin.math.abs(one.length - other.length) > 1) return false
        val (shorter, longer) = listOf(one, other).sortedBy { it.length }
        val same = shorter.indices.firstOrNull { shorter[it] != longer[it] } ?: return true
        return if (shorter.length == longer.length) shorter.regionMatches(same + 1, longer, same + 1, shorter.length - same - 1)
        else shorter.regionMatches(same, longer, same + 1, shorter.length - same)
    }

    /** How many letters to change to make [one] into [other]. */
    internal fun distance(one: String, other: String): Int {
        var previous = IntArray(other.length + 1) { it }
        for (i in one.indices) {
            val row = IntArray(other.length + 1).also { it[0] = i + 1 }
            for (j in other.indices) {
                row[j + 1] = minOf(previous[j + 1] + 1, row[j] + 1, previous[j] + if (one[i] == other[j]) 0 else 1)
            }
            previous = row
        }
        return previous[other.length]
    }

    /** Spellings of one sound, in order: "ch" before "c", and an r that ends a sound before l is read as r. */
    private val sounds = listOf(
        phoneRegex("th") to "s", phoneRegex("ph") to "f", phoneRegex("ck|qu?") to "k",
        phoneRegex("x") to "ks", phoneRegex("c(?=[eiy])") to "s", phoneRegex("c") to "k", phoneRegex("r(?![aeiouy])") to "",
        phoneRegex("l") to "r", phoneRegex("v") to "b", phoneRegex("z") to "s", phoneRegex("m(?=[bp])") to "n"
    )

    /** Fewer consonants than this say too little to tell names apart; under [FUZZY_SOUNDS], all must agree. */
    private const val MIN_SOUNDS = 3
    private const val FUZZY_SOUNDS = 5

    /** Two credits for one artist, near enough: "Randy" is in "Randy Nota Loca". */
    fun sameArtist(one: String, other: String): Boolean {
        val theirs = artists(other)
        if (theirs.isEmpty()) return true
        return artists(one).any { isOneOf(it, theirs) }
    }

    /** Whether [candidate] is one of [theirs], or one of them is in it, or it's in one of them, when both are long enough to say so. */
    private fun isOneOf(candidate: String, theirs: Collection<String>) = theirs.any { wanted ->
        wanted == candidate || (minOf(candidate.length, wanted.length) >= MIN_PARTIAL_ARTIST && (candidate in wanted || wanted in candidate))
    }

    /**
     * One of them contains every word of the other, so "Love Me Do" is never "Love Me Tender".
     * A single word only matches the whole of the other title: one word on its own says too
     * little, but two titles reduced to the same word are the same title with a tag left off.
     */
    fun wordsMatch(one: String, other: String): Boolean {
        // Entirely non-Latin titles have no words below. Two empty sets say nothing about
        // whether they name the same song; compare their actual letters first.
        if (same(one, other) && normalize(one).isNotEmpty()) return true
        // Nor do they have words for a note in brackets: "小幸运 (电影《我的少女时代》主题曲)" is "小幸运".
        if (words(one).isEmpty() && words(other).isEmpty()) {
            val bare = normalize(bareTitle(one))
            if (bare.isNotEmpty() && bare == normalize(bareTitle(other))) return true
        }
        val ours = words(withoutEditionTag(one))
        val theirs = words(withoutEditionTag(other))
        val (shorter, longer) = if (ours.size <= theirs.size) ours to theirs else theirs to ours
        return shorter.isNotEmpty() && (shorter.size >= 2 || shorter == longer) && longer.containsAll(shorter)
    }

    /**
     * The same title but for its small words, as sites write them down either way: "Vahos de ayer"
     * for "Vahos del Ayer". Word for word, in order, with at least two other words alike.
     */
    fun sameButSmallWords(one: String, other: String): Boolean {
        if (wordsMatch(one, other)) return true
        val ours = wordList(withoutEditionTag(one))
        val theirs = wordList(withoutEditionTag(other))
        return ours.size == theirs.size && ours.count { it !in smallWords } >= 2 &&
            ours.zip(theirs).all { (our, their) -> our == their || (our in smallWords && their in smallWords) }
    }

    /**
     * [artist] with the guests [title] credits, "Matheus & Kauan feat. Gusttavo Lima" for
     * "Paraquedas (feat. Gusttavo Lima)": some sites have a song under a guest's name only.
     */
    fun withGuests(artist: String, title: String): String =
        (listOf(artist) + guestCredit.matchesIn(title).map { it.groupValues[1].trim() }).joinToString(" feat. ")

    /**
     * The iTunes Store of the country that writes names as [names] are, which names its songs as
     * given there when another store translates them: Korea's for Hangul, Japan's for kana, Taiwan's
     * for Chinese characters alone. Null for names in none of those.
     */
    fun homeStore(names: String): String? {
        val scripts = names.map { Character.UnicodeScript.of(it.code) }.toSet()
        return homeStores.entries.firstOrNull { it.key in scripts }?.value
    }

    private val homeStores = linkedMapOf(
        Character.UnicodeScript.HANGUL to "KR", Character.UnicodeScript.HIRAGANA to "JP", Character.UnicodeScript.KATAKANA to "JP", Character.UnicodeScript.HAN to "TW"
    )

    /** [name] is one of [credit]'s artists, or is written inside one of them. */
    fun artistInside(name: String, credit: String): Boolean {
        val theirs = artists(name)
        if (theirs.isEmpty()) return false
        val ours = artists(credit)
        return ours.isEmpty() || ours.any { wanted ->
            theirs.any { candidate ->
                wanted == candidate || (minOf(candidate.length, wanted.length) >= MIN_PARTIAL_ARTIST &&
                    (candidate in wanted || wanted in candidate))
            }
        }
    }
}
