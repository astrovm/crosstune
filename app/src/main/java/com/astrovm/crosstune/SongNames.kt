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
    private val leadingArticle = Regex("""^the\s+""", RegexOption.IGNORE_CASE)
    private val artistSeparator = Regex(
        """\s*(?:,|&|\+|/|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b)\s*""",
        RegexOption.IGNORE_CASE
    )
    private val marks = Regex("""\p{M}+""")
    private val lettersAndDigits = Regex("""[^\p{L}\p{N}]+""")
    private val wordSplit = Regex("""[^a-z0-9]+""")

    /** "Zion and Lennox" and "Zion & Lennox" are two names, whichever language says so. */
    private val conjunction = Regex("""\s+(?:and|y|und|et)\s+""", RegexOption.IGNORE_CASE)

    /** A guest credited in the title, as "(feat. Emel)", which some services leave out. */
    private val featuring = Regex(
        // Or written out, as a video's title has it, "Despacito ft. Daddy Yankee", though not "6 ft. Under".
        """\s*[(\[]\s*(?:feat|ft|featuring)\b[^()\[\]]*[)\]]|(?<!\d)\s+(?:feat\.?|ft\.?|featuring)\s+[^()\[\]]*?(?=\s*[(\[]|$)""",
        RegexOption.IGNORE_CASE
    )
    /** What marks a title as a music video's, as K-pop does, which no store has it under. */
    private val videoMark = Regex("""\s*[(\[]\s*M/V\s*[)\]]|\s+M/V\b""", RegexOption.IGNORE_CASE)
    private val guestCredit = Regex("""[(\[]\s*(?:feat|ft|featuring)\b\.?\s*([^()\[\]]*)[)\]]""", RegexOption.IGNORE_CASE)

    /**
     * A trailing tag that names no other recording: "Remastered 2012" and "Mono" anywhere in
     * it, a whole "Album Version" or "Original", or what film or show it's the song of,
     * "(電影《我的少女時代》主題曲)". "Live", "Acoustic" or "Remix" are other recordings, so they stay.
     */
    private val editionTag = Regex(
        """(?:\s+[-–]\s+|\s*[(\[])\s*(?:$EDITION_WORDS|$IN_TAG*\b(?:remaster(?:ed|iz(?:ed|ado|ada))?|mono|stereo)\b$IN_TAG*|$IN_TAG*(?:$TIE_IN)$IN_TAG*)\s*[)\]]?$""",
        RegexOption.IGNORE_CASE
    )
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

    /**
     * Letters and digits only, no marks, no case and no width: "Édition", "Edition" and "Ｅｄｉｔｉｏｎ" are one word, and
     * "ｶﾗｵｹ" is "カラオケ". Symbols stay no letters, so "Song™" is "Song".
     */
    fun normalize(text: String): String = folded(widthsFolded(text))

    /**
     * [text] as what's kept for a song is found by: as [normalize], but with widths as written, as it was before
     * it folded them, so timing, lengths and reports kept under a name in full-width letters are still found.
     */
    fun keyOf(text: String): String = folded(text)

    private fun folded(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT).replace(lettersAndDigits, "")

    /** Full-width letters and half-width kana as their usual width, and nothing else: NFKC on all of it makes "™" "tm". */
    private fun widthsFolded(text: String): String =
        text.replace(otherWidth) { Normalizer.normalize(it.value, Normalizer.Form.NFKC) }

    private val otherWidth = Regex("[\uff00-\uffef]+")

    /** "The" only counts as an article when it is a word of its own, so not in "Thelonious". */
    fun withoutArticle(name: String): String = name.replace(leadingArticle, "")

    /** The words written in letters and digits: another script's are what the other title spells out. */
    fun words(title: String): Set<String> = wordList(title).toSet()

    private fun wordList(title: String): List<String> =
        Normalizer.normalize(widthsFolded(title), Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT)
            .split(wordSplit).filter { it.isNotEmpty() }

    /**
     * "Song - Remastered 2012", "Song (2003 Remaster)" and "Song (feat. A)" are the song itself; "Song
     * (Live; 2013 Remaster)" and "Song - Stereo MC's Remix" are other recordings, so they keep their tags.
     */
    fun withoutEditionTag(title: String): String {
        var stripped = title
        // "Song - 2012 Remaster (Mono)" has two, and a title that's only a tag is left as it is.
        while (true) {
            val unfeatured = stripped.replace(featuring, "").replace(videoMark, "")
            val tag = editionTag.find(unfeatured)?.value.orEmpty()
            if (words(tag).any { it in OTHER_RECORDING }) return unfeatured
            val shorter = unfeatured.replace(editionTag, "")
            if (shorter == stripped || shorter.isBlank()) return stripped
            stripped = shorter
        }
    }

    /**
     * [name] says it's another version of [title] by [artist]: a cover, karaoke, live, a remix… unless
     * the song's own name says so, "Bzrp Music Sessions" or "Little Mix". With [inOtherWords], what the song's
     * title says in Latin letters counts in any script, "なごり雪 (ライブ)" for "Nagoriyuki (Live)", and written short in
     * a tag that says nothing else, "(シングルver.)" or "(Single ver.)" for "(Single Version)", but not "(TV ver.)": only for
     * a song in another script, as nothing else tells those apart.
     * A name in the song's own script says what it says: "曲 (ライブ)" is another version of "Live Forever".
     */
    fun saysAnotherVersion(name: String, title: String, artist: String, inOtherWords: Boolean = false): Boolean {
        val said = if (inOtherWords) words(title) else emptySet()
        val own = words(title) + words(artist) + said.mapNotNull(SHORT_FOR::get).filter { onlySaid(name, it, said) }
        if ((words(name) - own).any { it in VERSION_WORDS }) return true
        return VERSION_MARKS.any { it.foundIn(name) && !it.foundIn(title) && !it.foundIn(artist) && it.means.none(said::contains) }
    }

    /**
     * A mark, as another script writes it, "カラオケ" or "tvサイズ", and the [means] it has in Latin letters. Japanese,
     * Korean and Chinese put words together with no space, "カラオケバージョン" or "피아노커버", so a mark is found
     * anywhere in a name, but for in a longer word that only holds it, as ドライブ holds ライブ: see [HOLD_A_MARK].
     * One in Latin letters only is a word of its own, so "tv size" isn't in "ATV Sizes". Compared composed, so
     * ライブ is never ライフ and one Hangul syllable never part of another, and with widths folded, "ＴＶサイズ".
     * What's between its letters doesn't count, "オフ・ヴォーカル" or "TV-size".
     */
    private class Mark(written: String, vararg val means: String) {
        private val letters = composed(written).filter(Char::isLetterOrDigit)
        private val pattern = Regex(letters.map { Regex.escape("$it") }.joinToString("""[^\p{L}\p{N}]*"""))

        // Whether it's a word is looked at on each name, not as it's made: marks are made once, as the app starts.
        fun foundIn(name: String): Boolean {
            val text = composed(name).replace(HOLD_A_MARK, " ")
            val aWord = letters.all { scriptOf(it) == Character.UnicodeScript.LATIN }
            return pattern.findAll(text).any { !aWord || !goesOn(text.getOrNull(it.range.first - 1)) && !goesOn(text.getOrNull(it.range.last + 1)) }
        }

        companion object {
            private fun composed(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

            /**
             * Longer words that hold a mark and are another word: install, drive, library, discover, recover, coverage,
             * accompanist. Only where a word starts: after more katakana or Hangul, "バンドライブ" is a band live.
             */
            private val HOLD_A_MARK =
                Regex("(?<![\\u30a1-\\u30fa\\u30fc-\\u30ff\\u1100-\\u11ff\\u3130-\\u318f\\uac00-\\ud7af])" +
                    "(?:インストール|ドライブ|ライブラリ|ディスカバー|リカバー|드라이브|라이브러리|디스커버|커버리지|반주자)")

            /** Whether [next], beside a word in Latin letters, is more of it: a Latin letter, a digit, or a mark over the last letter. */
            private fun goesOn(next: Char?): Boolean =
                next != null && (scriptOf(next) == Character.UnicodeScript.LATIN || next.isDigit() || next.category == CharCategory.NON_SPACING_MARK)

            private fun scriptOf(letter: Char): Character.UnicodeScript = Character.UnicodeScript.of(letter.code)
        }
    }

    /** Words a video's title has when it's another version of the song, or not the song at all. */
    private val VERSION_WORDS = setOf(
        "cover", "covers", "covered", "karaoke", "live", "unplugged", "mtv", "vivo", "directo", "session", "sessions",
        "concert", "tour", "remix", "mix", "instrumental", "inst", "acoustic", "acustico", "piano", "guitar",
        "bass", "drum", "drums", "chords", "tab", "tabs", "tutorial", "lesson", "practice", "reaction", "slowed",
        "reverb", "sped", "nightcore", "8d", "demo", "medley", "mashup", "amv", "mmd", "ver", "tribute", "ktv",
        "acapella", "acappella", "cappella", "capella", "8bit", "orgel", "musicbox"
    )

    /** What a name writes short for the song's own word: "(シングルver.)" for "(Single Version)". */
    private val SHORT_FOR = mapOf("version" to "ver", "instrumental" to "inst")

    /** Whether each tag of [name] with [short] in it says nothing but what the song's title [said]: "(Single ver.)", not "(TV ver.)". */
    private fun onlySaid(name: String, short: String, said: Set<String>): Boolean =
        Normalizer.normalize(widthsFolded(name), Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT).split(notInATag)
            .map(::words).all { tag -> short !in tag || tag.all { it == short || it in said } }

    /** What ends a tag in Latin letters: a bracket, a sign, or a letter in another script, as in "(シングルver.)". */
    private val notInATag = Regex("""[^a-z0-9\s.'-]+""")

    /** The same, as other scripts write them, with what they mean in Latin letters: see [Mark]. */
    private val VERSION_MARKS = listOf(
        Mark("カラオケ", "karaoke"), Mark("ライブ", "live"), Mark("弾き語り"), Mark("歌ってみた", "cover"), Mark("演奏してみた", "cover"),
        Mark("弾いてみた", "cover"), Mark("叩いてみた", "cover"), Mark("ピアノ", "piano"), Mark("ギター", "guitar"), Mark("カバー", "cover"),
        Mark("メドレー", "medley"), Mark("練習", "practice"), Mark("オルゴール", "orgel", "musicbox"), Mark("アカペラ", "acapella", "cappella"),
        Mark("アコースティック", "acoustic"), Mark("翻唱", "cover"), Mark("伴奏", "instrumental", "karaoke"), Mark("伴唱", "karaoke"),
        Mark("卡拉ok", "karaoke"), Mark("커버", "cover"), Mark("라이브", "live"), Mark("노래방", "karaoke"),
        // Many songs in one: "(メドレー)" above, "(串烧)", "(메들리)".
        Mark("串烧", "medley"), Mark("串燒", "medley"), Mark("메들리", "medley"),
        // Without the singing, or cut for TV: "(Off Vocal)", "(オフ・ヴォーカル)", "(TV Size)", "(インスト)", "(纯音乐)", "(반주)".
        Mark("offvocal", "instrumental", "karaoke"), Mark("tvsize"), Mark("オフボーカル", "instrumental", "karaoke"),
        Mark("オフヴォーカル", "instrumental", "karaoke"), Mark("インスト", "inst", "instrumental"), Mark("tvサイズ"), Mark("テレビサイズ"),
        Mark("纯音乐", "instrumental"), Mark("純音樂", "instrumental"), Mark("인스트", "inst", "instrumental"), Mark("반주", "instrumental", "karaoke"),
        // Remixed, or sung live: "(リミックス)", "(混音)", "(리믹스)", "(演唱会)".
        Mark("リミックス", "remix"), Mark("混音", "remix"), Mark("리믹스", "remix"), Mark("演唱会", "live", "concert"), Mark("演唱會", "live", "concert")
    )

    /**
     * Whether [song] is many songs in one, which has no one song's words to find: a medley, a mashup,
     * a whole concert or album played as one, a playlist or compilation, "50 Viral Audios", or three or
     * more songs named apart by slashes.
     */
    fun manySongs(song: MusicMetadata): Boolean {
        val names = "${song.title} ${song.artist}"
        val words = wordList(names)
        if (words.any { it in MANY_SONGS_WORDS } || MANY_SONGS_MARKS.any { it.foundIn(names) } || countsSongs(words)) return true
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
    private val MANY_SONGS_MARKS = listOf(Mark("メドレー"), Mark("串烧"), Mark("串燒"), Mark("메들리"))

    /** A whole show or album as one, in the languages its uploads are named in: "Full Concert", "(Recital Completo)". */
    private val wholeShow = Regex(
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
     * [one] and [other] are one name, written two ways: as Taiwan and China write it, 小幸運 and 小幸运, or in
     * Japanese one in kana and the other with kanji, さくら and 桜, which read alike. Read with its sound marks,
     * so ばら isn't 原, and never kanji against kanji, as 雨 and 飴 read alike and are two words. Reading takes
     * a moment the first time, so it's done off the main thread.
     */
    fun writtenAlike(one: String, other: String): Boolean {
        if (normalize(asChinaWrites(one)) == normalize(asChinaWrites(other))) return true
        val (kana, kanji) = listOf(one, other).partition { name -> name.any(Char::isLetter) && name.filter(Char::isLetter).all(Readings::isKana) }
        if (kana.size != 1 || kanji.single().none(Readings::isHan)) return false
        return readOut(kana.single()) == readOut(kanji.single())
    }

    /** How [name] reads, in hiragana, with its sound marks: がっこう, never かっこう. */
    private fun readOut(name: String): String =
        Normalizer.normalize(Readings.of(listOf(name), Script.JAPANESE).single().reading.inHiragana(true), Normalizer.Form.NFKC)
            .filter(Char::isLetterOrDigit)

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
        val tag = coverTags.firstNotNullOfOrNull { it.find(title) } ?: return null
        val song = bareTitle(title.substring(0, tag.range.first)).ifEmpty { return null }
        return Covered(song, coverBy.find(tag.value)?.groupValues?.get(1)?.trim()?.ifEmpty { null })
    }

    /** A cover's song: its [title], and its [artist] when the cover's title names them. */
    data class Covered(val title: String, val artist: String?)

    private const val COVER_WORDS = """\bcover(?:ed)?\b|\bin the style of\b|\boriginally (?:performed )?by\b|\bmade famous by\b|カバー|歌ってみた|翻唱|커버"""
    private val coverTags = listOf(
        Regex("""[(\[【][^)\]】]*(?:$COVER_WORDS)[^)\]】]*[)\]】]""", RegexOption.IGNORE_CASE),
        Regex("""\s[-–]\s[^-–]*(?:$COVER_WORDS).*$""", RegexOption.IGNORE_CASE)
    )
    private val coverBy = Regex("""(?:in the style of|originally (?:performed )?by|made famous by|cover of)\s+([^)\]】]+)""", RegexOption.IGNORE_CASE)

    /** The name without what it adds in brackets or after a dash: "Song (2018 Remix) (2024 Remaster)" is "Song". */
    fun bareTitle(title: String): String {
        var bare = title.trim()
        while (true) {
            val shorter = bare.replace(trailingTag, "").trim()
            if (shorter == bare || shorter.isEmpty()) return bare
            bare = shorter
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
        return if (said.length < FUZZY_SOUNDS) said == written else distance(said, written) <= 1
    }

    /** The consonants [text] is said with, those that sound alike as one: "Plastic Love" is "prstkrb". */
    private fun consonants(text: String): String {
        var said = text.lowercase().filter { it in 'a'..'z' }
        for ((from, to) in sounds) said = said.replace(from, to)
        return said.filter { it !in "aeiouhwy" }.fold("") { kept, c -> if (kept.lastOrNull() == c) kept else kept + c }
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
        Regex("th") to "s", Regex("ph") to "f", Regex("ck|qu?") to "k",
        Regex("x") to "ks", Regex("c(?=[eiy])") to "s", Regex("c") to "k", Regex("r(?![aeiouy])") to "",
        Regex("l") to "r", Regex("v") to "b", Regex("z") to "s", Regex("m(?=[bp])") to "n"
    )

    /** Fewer consonants than this say too little to tell names apart; under [FUZZY_SOUNDS], all must agree. */
    private const val MIN_SOUNDS = 3
    private const val FUZZY_SOUNDS = 5

    /** What a name adds at its end, in brackets or after a dash: "(2018 Remix)", "[Live]", " - Remastered". */
    private val trailingTag = Regex("""(?:\s*[(\[][^()\[\]]*[)\]]|\s+[-–]\s+[^-–]*)$""")

    /** Two credits for one artist, near enough: "Randy" is in "Randy Nota Loca". */
    fun sameArtist(one: String, other: String): Boolean {
        val theirs = artists(other)
        if (theirs.isEmpty()) return true
        return artists(one).any { candidate ->
            theirs.any { wanted ->
                wanted == candidate || (minOf(candidate.length, wanted.length) >= MIN_PARTIAL_ARTIST &&
                    (candidate in wanted || wanted in candidate))
            }
        }
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
        (listOf(artist) + guestCredit.findAll(title).map { it.groupValues[1].trim() }).joinToString(" feat. ")

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
