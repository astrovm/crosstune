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

    /** Letters and digits only, no marks and no case: "Édition" and "Edition" are one word. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT)
            .replace(lettersAndDigits, "")

    /**
     * [name]'s words, with what marks or strokes their letters too: "Fake Søng" is "Fake Song", as "Canción" is
     * "Cancion".
     */
    fun unmarkedWords(name: String): Set<String> = words(name.lowercase(Locale.ROOT).map { UNMARKED[it] ?: "$it" }.joinToString(""))

    /** Latin letters with a stroke or joined, which no mark taken off makes plain, as they're written plain. */
    private val UNMARKED = mapOf('ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss", 'đ' to "d", 'ð' to "d", 'ł' to "l", 'ħ' to "h", 'ı' to "i", 'þ' to "th")

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
     * the song's own name says so, "Bzrp Music Sessions" or "Little Mix".
     */
    fun saysAnotherVersion(name: String, title: String, artist: String): Boolean {
        val own = words(title) + words(artist)
        if ((words(name) - own).any { it in VERSION_WORDS }) return true
        val letters = normalize(name)
        return VERSION_MARKS.any { it in letters && it !in normalize(title) && it !in normalize(artist) }
    }

    /**
     * [name], in another script than the song's [titles], says it's another version of the song by [artist]. What
     * the version tags of its names say in Latin letters counts in any script, as they're one recording's: "なごり雪
     * (ライブ)" is "Nagoriyuki (Live)", and "曲 (ライブ)" another version of "Live Forever", whose name says it. A tag
     * that says it's a version, "(TVバージョン)", "(Single ver.)" or "(Inst.)", is the song's own only when all else it
     * says is what the song's own tags say: "Single", or "シングル", the same word in katakana, for "Fake Song (Single
     * Version)"; or as one of its names writes it. A mark that means a word of the song's name counts only in a tag,
     * so "ライヴ・フォーエヴァー (Live Forever)" is the song, where "曲 (ライブ)" isn't. An apostrophe is spelling,
     * so "Directors" is "Director's". It only says no to what main opened, never which row opens.
     */
    fun saysAnotherVersionOf(name: String, titles: Collection<String>, artist: String): Boolean =
        anotherVersion(name.replace(apostrophe, ""), titles.map { it.replace(apostrophe, "") }, artist)

    private val apostrophe = Regex("['’‘＇]")

    private fun anotherVersion(name: String, titles: Collection<String>, artist: String): Boolean {
        val said = titles.flatMap(::words).map(::canon).toSet()
        val tagged = titles.flatMap { title -> ownVersions(title).flatMap(::words) + versionTags(title).flatMap(::words) }.map(::canon).toSet()
        // What it's from, "From "Live Forever"" or "(Theme from …)", names a film, not a version. Its brackets stay, as
        // they tell the tags apart; and the name before them is the song's, "From Me to You" too.
        val stated = name.split(tagEdge).drop(1).filter { "from" in wordList(it) }.fold(name) { left, film -> left.replace(film, "") }
        val versions = versionTags(stated)
        if (versions.any { !allSaid(it, tagged, titles.map(::normalize)) }) return true
        if ((words(stated) - said - words(artist) - versions.flatMap(::words).toSet()).any { it in VERSION_WORDS && canon(it) !in said }) return true
        val tags = stated.removePrefix(bareTitle(stated))
        return MARKS.any { mark ->
            mark.foundIn(if (mark.means.any(said::contains)) tags else stated) && titles.none(mark::foundIn) && !mark.foundIn(artist) && mark.means.none(tagged::contains)
        }
    }

    /**
     * Whether [tag], which says it's a version, says nothing the song's own tags don't: each word, as they [tagged]
     * it, "ver" or "version" alike; each run in katakana, as words katakana writes them with that are all so, never
     * one only near one ("ショート" isn't "Stay"); or anything as one of the song's names has it [written].
     */
    private fun allSaid(tag: String, tagged: Set<String>, written: List<String>): Boolean {
        val latin = words(tag).all { canon(it) in tagged }
        val marked = VERSION_WRITTEN.filterKeys(tag::contains)
        if (!latin || marked.any { (mark, means) -> means !in tagged && written.none { normalize(mark) in it } }) return false
        val rest = marked.keys.fold(tag) { left, mark -> left.replace(mark, " ") }.split(notOwnLetters)
        // An empty run, between two marks, says nothing, so it passes.
        return rest.all { run -> written.any { normalize(run) in it } || inKatakanaSays(run, tagged) }
    }

    /** Whether [run] is words in katakana, each one of [tagged]: "シングルリミックス" for "(Single Remix)". */
    private fun inKatakanaSays(run: String, tagged: Set<String>): Boolean =
        cutInto(run, KATAKANA_WORDS.filterValues { words(it).all { word -> canon(word) in tagged } }.keys)

    /** Whether [run], with ヴ as ブ, is some of [words] one after another, none for an empty one. */
    private fun cutInto(run: String, words: Collection<String>): Boolean =
        Regex(words.joinToString("|", "(?:", ")*") { Regex.escape(withBu(it)) }).matches(withBu(run))

    /**
     * The tags of [title] that say what version it is, "(Live)", "(Single Mix)" or "(Live at Budokan)", not what it's
     * from: "(Theme from Live Forever)" says "live", but of a film's name.
     */
    private fun ownVersions(title: String) = title.removePrefix(bareTitle(title)).split(tagEdge).filter { tag ->
        // A tag with no words in Latin letters adds none, so it may as well count.
        wordList(tag).let { it.all(VERSION_LIKE::contains) || it.first() in VERSION_WORDS }
    }

    /** The parts of [name] that say it's a version: "(Single ver.)", "TVバージョン", or all of "Fake Lullaby Acoustic Guitar Version". */
    private fun versionTags(name: String) = composed(name).split(tagEdge).filter { tag -> words(tag).any(VERSION_SAID::containsKey) || VERSION_WRITTEN.keys.any(tag::contains) }

    /**
     * Whether [half] of a name, apart by " - ", says only that it's another version than the [other] half: "曲 - Live"
     * is a live one, though "Live Forever" is "曲" in Japan, where "なごり雪 (ライブ) - Nagoriyuki (Live)" says one version
     * twice. "From "Live Forever"" says more: what it's from. Only Latin words and numbers are [words], so "曲" says nothing more.
     */
    fun onlyAnotherVersion(half: String, other: String): Boolean =
        saysAnotherVersionOf(half, listOf(other), "") && words(half).all { word -> canon(word) in VERSION_LIKE || word.all(Char::isDigit) }

    /** "ver" as "version", "inst" as "instrumental": one word, written short or not. */
    private fun canon(word: String) = VERSION_SAID[word] ?: word

    /** NFKC and lowercase, so "ＴＶ" is "tv" and "ｳﾞｧｰｼﾞｮﾝ" "ヴァージョン", with its sound marks kept. */
    private fun composed(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /**
     * A word in katakana with ヴ as ブ, as katakana writes a v either way: ライヴ is ライブ. Only for a word already
     * cut out of a version tag, as in a name "アライヴ" holds no "ライブ".
     */
    private fun withBu(word: String) = word.replace("ヴァ", "バ").replace("ヴィ", "ビ").replace("ヴェ", "ベ").replace("ヴォ", "ボ").replace("ヴ", "ブ")

    /** Where one tag of a name ends and another starts: brackets, a tilde, a bar, a slash, an underscore, or a dash with spaces round it. */
    private val tagEdge = Regex("""[()\[\]{}【】「」『』〈〉《》~〜|/_]|\s[-–]\s""")

    /** What isn't a letter of a script but Latin, so what's left is each run of them: "シングル" in "シングル・バージョン". */
    private val notOwnLetters = Regex("""[^\p{L}]+|\p{IsLatin}+""")

    /** Words that say a name is a version, written short or not, and the word they mean. */
    private val VERSION_SAID = mapOf("ver" to "version", "version" to "version", "inst" to "instrumental", "instrumental" to "instrumental")

    /** The same as other scripts write them, which have no spaces to make them words of their own: "TVバージョン". */
    private val VERSION_WRITTEN = mapOf("バージョン" to "version", "ヴァージョン" to "version", "버전" to "version")

    /**
     * Words a version tag says in katakana, with ヴ as ブ (see [withBu]), and the same words in Latin letters: what's
     * the song's own when its tags say that word, "(ショート・バージョン)" for "(Short Version)".
     */
    private val KATAKANA_WORDS = mapOf(
        "シングル" to "single", "アルバム" to "album", "オリジナル" to "original", "ラジオ" to "radio", "エディット" to "edit",
        "ショート" to "short", "ロング" to "long", "フル" to "full", "スロー" to "slow", "ライブ" to "live", "リミックス" to "remix",
        "ミックス" to "mix", "リマスター" to "remaster", "リマスタード" to "remastered", "モノラル" to "mono", "モノ" to "mono",
        "ステレオ" to "stereo", "アコースティック" to "acoustic", "ピアノ" to "piano", "ギター" to "guitar", "オーケストラ" to "orchestra",
        "バンド" to "band", "デモ" to "demo", "インスト" to "instrumental", "インストゥルメンタル" to "instrumental", "カラオケ" to "karaoke",
        "スピードアップ" to "speed up", "エクステンデッド" to "extended", "イングリッシュ" to "english", "ジャパニーズ" to "japanese",
        "ムービー" to "movie", "テレビ" to "tv", "ドラマ" to "drama", "アニメ" to "anime", "アカペラ" to "acapella", "ボーカル" to "vocal",
        "オフ" to "off", "カバー" to "cover", "セルフ" to "self", "スペシャル" to "special", "ニュー" to "new", "スタジオ" to "studio",
        "リレコーディング" to "re recording", "ダンス" to "dance", "アンプラグド" to "unplugged",
        "バラード" to "ballad", "サイズ" to "size", "メドレー" to "medley", "オルゴール" to "orgel"
    )

    /**
     * A mark, as another script writes it, "カラオケ" or "tvサイズ", and the [means] it has in Latin letters. Japanese,
     * Korean and Chinese put words together with no space, "カラオケバージョン" or "피아노커버", so a mark is found
     * in a longer run of letters. In katakana, only when that run is all words a version tag says (see
     * [KATAKANA_WORDS]), so "インストラクター", "アライブ" and "ドライブ" hold none; in Hangul, but for in a longer word
     * that only holds it: see [HOLD_A_MARK]. One in Latin letters only is a word of its own, so "tv size" isn't in "ATV
     * Sizes". Compared composed, so ライブ is never ライフ and one Hangul syllable never part of
     * another, and with widths folded, "ＴＶサイズ". What's between its letters doesn't count, "オフ・ヴォーカル" or "TV-size".
     */
    private class Mark(written: String, vararg val means: String) {
        private val letters = composed(written).filter(Char::isLetterOrDigit)
        private val pattern = Regex(letters.map { Regex.escape("$it") }.joinToString("""[^\p{L}\p{N}]*"""))

        /** The mark, with all the katakana on either side of it: the run of words it's in. */
        private val inItsRun = Regex("(?<!$KATAKANA)$KATAKANA*(?:${pattern.pattern})$KATAKANA*(?!$KATAKANA)")

        // Whether it's a word is looked at on each name, not as it's made: marks are made once, as the app starts.
        fun foundIn(name: String): Boolean {
            val text = composed(name).replace(HOLD_A_MARK, " ")
            if (Regex(KATAKANA).containsMatchIn(letters)) return inItsRun.findAll(text).any { run -> run.value.split(notKatakana).all(::versionRun) }
            val aWord = letters.all { scriptOf(it) == Character.UnicodeScript.LATIN }
            return pattern.findAll(text).any { found -> !aWord || !goesOn(text.getOrNull(found.range.first - 1)) && !goesOn(text.getOrNull(found.range.last + 1)) }
        }

        companion object {
            /**
             * Longer words in Hangul that hold a mark and are another word: drive, library, discover, coverage,
             * accompanist. Only where a word starts: after more Hangul, "밴드라이브" is a band live.
             */
            private val HOLD_A_MARK = Regex("(?<![\\u1100-\\u11ff\\u3130-\\u318f\\uac00-\\ud7af])(?:드라이브|라이브러리|디스커버|커버리지|반주자)")

            /** A katakana letter or ー, what a word in katakana is made of. */
            private const val KATAKANA = "[\\u30a1-\\u30fa\\u30fc-\\u30ff]"

            private val notKatakana = Regex("[^\\u30a1-\\u30fa\\u30fc-\\u30ff]+")

            /** Whether [next], beside a word in Latin letters, is more of it: a Latin letter, a digit, or a mark over the last letter. */
            private fun goesOn(next: Char?): Boolean =
                next != null && (scriptOf(next) == Character.UnicodeScript.LATIN || next.isDigit() || next.category == CharCategory.NON_SPACING_MARK)

            private fun scriptOf(letter: Char): Character.UnicodeScript = Character.UnicodeScript.of(letter.code)
        }
    }

    /** Whether [run] in katakana is words a version tag says, one after another: see [KATAKANA_WORDS] and [VERSION_WRITTEN]. */
    private fun versionRun(run: String) = cutInto(run, KATAKANA_WORDS.keys + VERSION_WRITTEN.keys)

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

    /** [VERSION_MARKS] and more, with what they mean in Latin letters, for [saysAnotherVersionOf]: see [Mark]. */
    private val MARKS = listOf(
        Mark("カラオケ", "karaoke"), Mark("ライブ", "live"), Mark("弾き語り"), Mark("歌ってみた", "cover"), Mark("演奏してみた", "cover"),
        Mark("弾いてみた", "cover"), Mark("叩いてみた", "cover"), Mark("ピアノ", "piano"), Mark("ギター", "guitar"), Mark("カバー", "cover"),
        Mark("メドレー", "medley"), Mark("練習", "practice"), Mark("オルゴール", "orgel", "musicbox"), Mark("アカペラ", "acapella", "cappella"),
        Mark("アコースティック", "acoustic"), Mark("翻唱", "cover"), Mark("伴奏", "instrumental", "karaoke"), Mark("伴唱", "karaoke"),
        Mark("卡拉ok", "karaoke"), Mark("커버", "cover"), Mark("라이브", "live"), Mark("노래방", "karaoke"),
        // Many songs in one: "(メドレー)" above, "(串烧)", "(메들리)".
        Mark("串烧", "medley"), Mark("串燒", "medley"), Mark("메들리", "medley"),
        // Without the singing, or cut for TV: "(Off Vocal)", "(オフ・ヴォーカル)", "(TV Size)", "(インスト)", "(纯音乐)", "(반주)".
        Mark("offvocal", "instrumental", "karaoke"), Mark("tvsize"), Mark("オフボーカル", "instrumental", "karaoke"),
        Mark("オフヴォーカル", "instrumental", "karaoke"), Mark("インスト", "inst", "instrumental"),
        Mark("tvサイズ"), Mark("テレビサイズ"),
        Mark("纯音乐", "instrumental"), Mark("純音樂", "instrumental"), Mark("인스트", "inst", "instrumental"), Mark("반주", "instrumental", "karaoke"),
        // Remixed, or sung live: "(リミックス)", "(混音)", "(리믹스)", "(演唱会)".
        Mark("リミックス", "remix"), Mark("混音", "remix"), Mark("리믹스", "remix"), Mark("演唱会", "live", "concert"), Mark("演唱會", "live", "concert"),
        // ライブ and カバー written with ヴ.
        Mark("ライヴ", "live"), Mark("カヴァー", "cover")
    )

    /** The words a tag that says only what version it is has: see [ownVersions]. */
    private val VERSION_LIKE = VERSION_WORDS + VERSION_SAID.keys + KATAKANA_WORDS.values.flatMap(::words) + "size"

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
     * [one] and [other] are one name, written two ways: as Taiwan and China write it, 小幸運 and 小幸运; in katakana and in
     * hiragana, サクラ and さくら; or in Japanese one in kana and the other with kanji, さくら and 桜, which read alike. Read
     * with its sound marks, so ばら isn't 原, and never kanji against kanji, as 雨 and 飴 read alike and are two words. A
     * long vowel is one however it's written: ユーキ is 勇気, ゆうき, and ノー may be 脳. Half-width kana are kana, "ﾊﾅﾐｽﾞｷ".
     * Reading takes a moment the first time, so it's done off the main thread.
     */
    fun writtenAlike(one: String, other: String): Boolean {
        val names = listOf(one, other).map { Normalizer.normalize(it, Normalizer.Form.NFKC) }
        if (spelled(names.first()) == spelled(names.last())) return true
        val (kana, kanji) = names.partition { name -> name.any(Char::isLetter) && name.filter(Char::isLetter).all(Readings::isKana) }
        if (kana.size != 1 || kanji.single().none(Readings::isHan)) return false
        return readOut(kana.single()) == readOut(kanji.single())
    }

    /** [name] as China writes it, its katakana as hiragana, lowercase and with only its letters and digits, sound marks kept. */
    private fun spelled(name: String) = Readings.hiragana(asChinaWrites(name)).lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    /** How [name] reads, in hiragana, with its sound marks and its long vowels as ー: がっこー, never かっこう. */
    private fun readOut(name: String): String {
        val read = Normalizer.normalize(Readings.of(listOf(name), Script.JAPANESE).single().reading.inHiragana(true), Normalizer.Form.NFKC)
            .filter(Char::isLetterOrDigit)
        return read.mapIndexed { at, kana -> if (at > 0 && lengthens(read[at - 1], kana)) 'ー' else kana }.joinToString("")
    }

    /**
     * Whether [kana] after [before] only makes its vowel long, as ー does: the same vowel, "かあ", or う after an o, "こう",
     * and い after an e, "せい", as Japanese writes them.
     */
    private fun lengthens(before: Char, kana: Char): Boolean {
        val vowel = VOWELS.entries.firstOrNull { before in it.value }?.key ?: return false
        return kana == vowel || vowel == 'お' && kana == 'う' || vowel == 'え' && kana == 'い'
    }

    /** Each vowel, and the kana said with it. */
    private val VOWELS = mapOf(
        'あ' to "あかさたなはまやらわがざだばぱぁゃゎ", 'い' to "いきしちにひみりぎじぢびぴぃ", 'う' to "うくすつぬふむゆるぐずづぶぷぅゅゔ",
        'え' to "えけせてねへめれげぜでべぺぇ", 'お' to "おこそとのほもよろをごぞどぼぽぉょ"
    )

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
        // What follows "Artist - " is the song's name, not a tag; a video's channel is named for the artist.
        val by = artist.replace(channel, "")
        val dashed = dashTag.findAll(name).filterNot { by.isNotBlank() && sameArtist(name.substring(0, it.range.first), by) }
        // A guest named before the tag is the song's, "Song (feat. Guest) [Instrumental]"; after it, they sing in this mix.
        return (bracketTag.findAll(name) + dashed).any { onlyInstrumental(it.groupValues[1]) && !singerAfter.containsMatchIn(name.substring(it.range.first)) } ||
            offVocalAtEnd.containsMatchIn(name)
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
        return instrumentalWord.findAll(name).any { !shortForm.matches(it.value) } || accompaniment.containsMatchIn(name) ||
            (bracketTag.findAll(name) + dashTag.findAll(name)).any { onlyInstrumental(it.groupValues[1]) }
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
    private val coverTags = listOf(
        Regex("""[(\[【][^)\]】]*(?:$COVER_WORDS)[^)\]】]*[)\]】]""", RegexOption.IGNORE_CASE),
        Regex("""\s[-–]\s[^-–]*(?:$COVER_WORDS).*$""", RegexOption.IGNORE_CASE)
    )
    private val coverBy = Regex("""(?:in the style of|originally (?:performed )?by|made famous by|cover of)\s+([^)\]】]+)""", RegexOption.IGNORE_CASE)

    /**
     * A word saying no one sings, not inside another word, nor the end of one joined by a hyphen, "Show-Off Vocal".
     * A word in Chinese or Japanese may say "version" after it, "伴奏版".
     */
    private val instrumentalWord = Regex(
        """(?<![\p{L}\p{N}])(?<![\p{L}\p{N}]-)(?:instrumentale?|inst\.?|off[ -]?vocal|(?-i:MR)|인스트루멘탈|""" +
            """(?:伴奏|オフボーカル|纯音乐|純音樂|純音楽)(?:版本?|バージョン)?)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )
    private val shortForm = Regex("""inst\.?|MR""", RegexOption.IGNORE_CASE)
    private val instrumentals = Regex("""(?<=instrumental)s\b""", RegexOption.IGNORE_CASE)

    /** A voice in the recording, other than in "off vocal": "(Vocal Mix)", "(A Cappella & Instrumental)". */
    private val voiceIn = Regex("""(?<!off )(?<!off-)\bvocals?\b|\ba[ -]?capp?ella\b""", RegexOption.IGNORE_CASE)

    /**
     * What a tag naming a part of a song, or what it's from, or where it was played, says: "(with Instrumental Break)", "(From the film
     * Instrumental)", "- Live at the Instrumental Hall".
     */
    private val PART_WORDS = setOf("with", "intro", "outro", "break", "interlude", "bridge", "from", "film", "movie", "live", "hall")

    /** What says a tag isn't the recording's: "(Not Instrumental)", or names who made it, "(Prod. Instrumental)". */
    private val NOT_SAID = setOf("not", "non", "prod", "produced")

    /** A singer named after a tag, in brackets or not: "[feat. Singer]", "ft. Singer", "with Singer", "x Singer". */
    private val singerAfter = Regex("""(?:^|[\s(\[])(?:feat\.?|ft\.?|featuring|with|x|×)\s+\S""", RegexOption.IGNORE_CASE)

    /** An album of tracks to sing over, "(Karaoke Version)", "(Backing Tracks)", "(Minus One)". */
    private val accompaniment = Regex("""karaoke|カラオケ|\bbacking tracks?\b|\bminus ones?\b""", RegexOption.IGNORE_CASE)

    /** What YouTube adds to an artist's name for their channel: "BandVEVO", "Band - Topic". */
    private val channel = Regex("""VEVO$|\s*-\s*Topic$""")
    private val SAYS_VERSION = setOf("ver", "version", "versao")

    /** What's in brackets, of any kind Chinese and Japanese titles use, once full-width ones are written as others. */
    private val bracketTag = Regex("""[(\[【「〔]([^()\[\]【】「」〔〕]*)[)\]】」〕]""")

    /** What follows a dash, or a tilde, which a Japanese title may put right after its name: "曲〜伴奏〜". */
    private val dashTag = Regex("""(?:(?<=\s)[-–]\s?|[~〜])([^-–~〜]*)""")

    /**
     * "Off vocal" ending the title with nothing around it, after a name in Chinese, Japanese or Korean, "曲 Off Vocal",
     * or with "ver." after it, "Song off vocal ver.": "Kiss Off Vocal" may be the song "Kiss Off" sung.
     */
    private val offVocalAtEnd = Regex(
        """(?:(?<=[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uac00-\ud7af])\s*off[ -]?vocal(?:\s+ver(?:sion|\.)?)?|\soff[ -]?vocal\s+ver(?:sion|\.)?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    /**
     * A track made to sing along to, as karaoke labels name theirs, "Song (Made Popular By Singer) [Instrumental Version]"
     * by "Party Tyme Karaoke", or one shown with the words, "(Instrumental + Lyrics)", or to sing over, "(Minus One)",
     * "(Backing Track)". These show the words of the song they play, as before; whether they should is still to be decided (Issue #922).
     */
    private val singAlong = Regex("""karaoke|カラオケ|\bmade popular by\b|\blyrics?\b|\bsing[- ]?along\b|\bminus one\b|\bbacking track\b""", RegexOption.IGNORE_CASE)

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
