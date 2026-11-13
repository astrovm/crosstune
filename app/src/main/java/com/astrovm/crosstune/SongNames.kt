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
     * Whether [name], a song or a video YouTube Music shows, on [album], credited to [credit], is another recording than
     * the one by [artists] named [titles]: two versions (see [versionsDiffer]). A tag says the version: the row's, that
     * the song's names don't say, or the song's, that the row's name and album never say. Its name never does, nor does a
     * name in another script or spelled otherwise, a word, a tag or a spelling none of these knows: they can't tell, so
     * they never count. It only takes away what main opened, or what ChatGPT picks in its place.
     */
    fun saysAnother(name: String, album: String, titles: Collection<String>, artists: Collection<String>, credit: String = ""): Boolean =
        versionsDiffer(name, album, titles, artists + artistNames(credit).flatMap { it.split(conjunction) })

    /**
     * Whether [name] and the song's [titles] say two versions: a word one of them says a version with, "live", "ライブ"
     * or "(TVバージョン)", that the other doesn't say. Only [name]'s tags say one, never the name before them: "Live
     * Wire" may be "Livewire". What says no version is taken off both first: an edition, "(Album Version)" or
     * "(シングル・バージョン)", what it's from or the song of, "(Theme from …)" or "(TVアニメ「偽物」オープニングテーマ)", a
     * credit, "(with Live Crew)", and a subtitle, "～…～" (see [versionParts]). A word no list has says nothing, so
     * "(Christmas Version)" is "(クリスマス・バージョン)"; nor does "version" alone, so "(Smooth Rap Version)" may be
     * "smooth rap". A tag that says "version" says which in any word a list has, in Latin letters too, "(TV ver.)". The
     * names of the artists, the song's and the row's own, [artists], say nothing, "(Fake Band Version)", nor does a tag
     * that's one of the song's names. What all the song's names say is missing only where [name] and its [album] never
     * say it, even in a word in katakana or Hangul in a longer one, or a word [IN_KATAKANA] has in Latin letters.
     */
    private fun versionsDiffer(name: String, album: String, titles: Collection<String>, artists: Collection<String>): Boolean {
        val named = (titles + titles.map(::bareTitle) + artists).map { normalize(inLatin(it)) }.toSet()
        val credited = artists.map(::inLatin).filter(String::isNotBlank)
        // In Latin letters, "Band" may be an artist's name: "Fake Band" says no band version, but "Band Versions" does.
        fun uncredited(text: String) = credited.fold(text) { rest, artist -> rest.replace(artist, " ") }
        // A tag that says "version" says which in any word a list has, "(TV ver.)" or "(Speed-Up Version)".
        fun said(tag: String, loosely: Boolean) = versionWords(uncredited(tag), loosely || "version" in words(tag))
        val says = versionParts(name).second.filterNot { normalize(it) in named }.flatMap { said(it, loosely = false) }.toSet()
        // What a name of the song says: its tags, and its own words where they say "version".
        fun saidBy(title: String, loosely: Boolean) = versionParts(title).let { (bare, own) ->
            own.flatMap { said(it, loosely) }.toSet() + versionWords(bare, loosely).takeIf { "version" in it }.orEmpty()
        }
        val written = inLatin("$name $album")
        val mentioned = versionWords(written) + versionWords(uncredited(written), loosely = true) +
            KATAKANA.filterKeys(withBu(composed(name))::contains).values.flatMap(::words) + MARKS.filter { it.inside(written) }.flatMap(Mark::says)
        return differs(says, titles.flatMap { saidBy(it, loosely = true) }.toSet()) || differs(titles.map { saidBy(it, loosely = false) }.reduce(Set<String>::intersect), mentioned)
    }

    /** Whether [said] has a version word [other] doesn't, but "version", which doesn't say which. */
    private fun differs(said: Set<String>, other: Set<String>): Boolean = (said - "version" - other).isNotEmpty()

    /**
     * [name] as [inLatin] writes it, without a subtitle, "～偽りの夜～", apart at its tags (see [tagEdge]): what's before
     * them, then each tag but one that says no version (see [saysNoVersion]), an edition (see [withoutEditionTag]), or
     * "Full Version". A tag in brackets or after a dash that says no version goes whole, before it's apart, as all in
     * it is a name: "(TVアニメ「ライブ」オープニングテーマ)".
     */
    private fun versionParts(name: String): Pair<String, List<String>> {
        val halves = composed(name).replace(subtitle, " ").replace(aside) { if (saysNoVersion(it.value)) " " else it.value }.split(dash)
        val kept = listOf(halves.first()) + halves.drop(1).filterNot(::saysNoVersion)
        val parts = withoutEditionTag(inLatin(kept.joinToString(" - "))).split(tagEdge).map(String::trim)
        return parts.first() to parts.drop(1).filter { withoutEditionTag("x ($it)") != "x" && !FULL.matches(it) && !saysNoVersion(it) }
    }

    /**
     * Whether [tag] says what film or show it's from or the song of, "Theme from Live Forever", "(TVアニメ「偽物」
     * オープニングテーマ)", or who it's with, "(with Live Crew)": no version, whatever words it holds.
     */
    private fun saysNoVersion(tag: String) = tieIn.containsMatchIn(tag) || withSomeone.containsMatchIn(tag)

    /** A dash with spaces round it, before another part of the name: "偽の歌 - From "Live Forever"". */
    private val dash = Regex("""\s[-–]\s""")

    /**
     * The version words in [part]: its own (see [VOCAB]), what katakana in it says (see [IN_KATAKANA]), and what [MARKS]
     * in it say. [loosely], also a word of [IN_KATAKANA] in Latin letters, as it may be what the other name says in katakana.
     */
    private fun versionWords(part: String, loosely: Boolean = false): Set<String> {
        val inKatakana = if (loosely) words(part) else saidInKatakana.findAll(part).flatMap { words(it.value) }.toSet()
        return words(part).filter(VOCAB::contains).toSet() + inKatakana.filter(IN_KATAKANA::contains) + marksIn(part)
    }

    /** What [inLatin] wrote for katakana, in capitals, as all else is lowercase: "(バラードバージョン)" is "(BALLAD VERSION)". */
    private val saidInKatakana = Regex("\\p{Lu}+")

    private fun marksIn(text: String) = MARKS.filter { it.foundIn(text) }.flatMap(Mark::says)

    /**
     * [text] composed (see [composed]), with what it says as words in Latin letters: a run in katakana that's words of
     * [KATAKANA] as those words, in capitals, "ver." as "version" and "inst." as "instrumental", and ・ as the space it
     * is, so "シングル・バージョン" is "SINGLE VERSION". An apostrophe is spelling, so "Director's" is "Directors".
     */
    private fun inLatin(text: String): String = composed(text).replace(apostrophe, "")
        .replace(KATAKANA_RUN) { run -> inWords(withBu(run.value))?.let { " ${it.uppercase(Locale.ROOT)} " } ?: run.value }
        .replace(shortened) { if (it.groupValues[1] == "ver") "version" else "instrumental" }.replace('・', ' ')

    /** [run] in katakana as the words of [KATAKANA] it is, one after another, or null where it's more: "アライブ". */
    private fun inWords(run: String): String? {
        val found = KNOWN_RUN.findAll(run).toList()
        return found.takeIf { it.sumOf { word -> word.value.length } == run.length }?.joinToString(" ") { KATAKANA.getValue(it.value) }
    }

    private val apostrophe = Regex("['’‘＇]")
    private val shortened = Regex("""\b(ver|inst)\b\.?""")

    /** A subtitle between two tildes, "～偽りの夜～", which a store may leave out. */
    private val subtitle = Regex("[~～〜][^~～〜]*[~～〜]")

    /** A tag in brackets, which may say what it's from: "(映画「ライブ・フォーエバー」主題歌)". */
    private val aside = Regex("""[(\[【][^()\[\]【】]*[)\]】]""")

    /**
     * What says a film, a show or an ad has it, or what it's from: a name in 「」 or 『』, an opening, an ending, a theme,
     * a song of one, as Japanese, Chinese, Korean and English say them, written as [composed] or [inLatin] leaves them.
     */
    private val tieIn = Regex(
        """[「」『』]|オープニング|エンディング|テーマ|ソング|タイアップ|$TIE_IN|(?<![a-z0-9])(?:op|ed|opening|ending|theme|from|ost|soundtrack|cm|insert)(?![a-z0-9])""",
        RegexOption.IGNORE_CASE
    )

    /** Who it's with, a credit as "feat." is: "(with Live Crew)", "(w/ Live Crew)". */
    private val withSomeone = Regex("""^[(\[【]?\s*(?:with|w/)\s""", RegexOption.IGNORE_CASE)

    /** The whole song, where a TV cut is shorter: no other version. */
    private val FULL = Regex("full(?: (?:version|size|length))?", RegexOption.IGNORE_CASE)

    /** NFKC and lowercase, so "ＴＶ" is "tv" and "ｳﾞｧｰｼﾞｮﾝ" "ヴァージョン", with its sound marks kept. */
    private fun composed(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /**
     * A word in katakana with ヴ as ブ, as katakana writes a v either way: ライヴ is ライブ. Only for a word already
     * cut out of a version tag, as in a name "アライヴ" holds no "ライブ".
     */
    private fun withBu(word: String) = word.replace("ヴァ", "バ").replace("ヴィ", "ビ").replace("ヴェ", "ベ").replace("ヴォ", "ボ").replace("ヴ", "ブ")

    /**
     * Where one tag of a name ends and another starts: brackets, a tilde, a bar, an underscore, or a dash with spaces
     * round it. Not a slash, which may join two names, "曲 / Live Forever".
     */
    private val tagEdge = Regex("""[()\[\]{}【】「」『』〈〉《》~〜|_]|\s[-–]\s""")

    /**
     * Words katakana writes a version with, with ヴ as ブ (see [withBu]), and the same words in Latin letters:
     * "(ショート・バージョン)" is "(Short Version)". Only a run of them all counts, "シングルリミックス": in a run with
     * another word, "アライヴ" or "インストラクター", it's part of that word.
     */
    private val KATAKANA = mapOf(
        "シングル" to "single", "アルバム" to "album", "オリジナル" to "original", "ラジオ" to "radio", "エディット" to "edit",
        "ショート" to "short", "ロング" to "long", "フル" to "full", "スロー" to "slow", "ライブ" to "live", "リミックス" to "remix",
        "ミックス" to "mix", "リマスター" to "remaster", "リマスタード" to "remastered", "モノラル" to "mono", "モノ" to "mono",
        "ステレオ" to "stereo", "アコースティック" to "acoustic", "ピアノ" to "piano", "ギター" to "guitar", "オーケストラ" to "orchestra",
        "バンド" to "band", "デモ" to "demo", "インスト" to "instrumental", "インストゥルメンタル" to "instrumental", "カラオケ" to "karaoke",
        "スピードアップ" to "speed up", "エクステンデッド" to "extended", "イングリッシュ" to "english", "ジャパニーズ" to "japanese",
        "ムービー" to "movie", "テレビ" to "tv", "ドラマ" to "drama", "アニメ" to "anime", "アカペラ" to "acapella", "ボーカル" to "vocal",
        "オフ" to "off", "カバー" to "cover", "セルフ" to "self", "スペシャル" to "special", "ニュー" to "new", "スタジオ" to "studio",
        "リレコーディング" to "re recording", "ダンス" to "dance", "アンプラグド" to "unplugged",
        "バラード" to "ballad", "サイズ" to "size", "メドレー" to "medley", "オルゴール" to "orgel", "バージョン" to "version"
    )

    /** One of [KATAKANA], the longest first, so "モノラル" is never "モノ" and more. */
    private val KNOWN_RUN = Regex(KATAKANA.keys.sortedByDescending(String::length).joinToString("|"))

    /** A run of katakana, ー and all: "シングル" and "バージョン" in "シングル・バージョン". */
    private val KATAKANA_RUN = Regex("[\\u30a1-\\u30fa\\u30fc-\\u30ff]+")

    /**
     * A mark another script than katakana says a version with, "翻唱" or "커버", and the words it [says] in Latin
     * letters, or itself where it has none. Chinese runs words together, "钢琴翻唱", so one in kanji counts in a longer
     * run. One in Hangul counts only in a run of marks alone, "라이브버전", as katakana's words do (see [KATAKANA]): in
     * a longer word, "얼라이브" or "러브드라이브", it can't tell. One in Latin letters, "off vocal", is a word of its own, so
     * "tv size" isn't in "ATV Sizes". What's between its letters doesn't count, "TV-size".
     */
    private class Mark(written: String, vararg means: String) {
        val says = means.toList()
        val letters = composed(written).filter(Char::isLetterOrDigit)
        private val pattern = Regex(letters.map { Regex.escape("$it") }.joinToString("""[^\p{L}\p{N}]*"""), RegexOption.IGNORE_CASE)
        private val script = scriptOf(letters.first())

        // Whether it's a word is looked at on each name, not as it's made: marks are made once, as the app starts.
        /** Whether it's in [text], as [inLatin] writes it, as a word of its own where its script has them. */
        fun foundIn(text: String): Boolean = pattern.findAll(text).any { found ->
            when (script) {
                Character.UnicodeScript.LATIN -> !goesOn(text.getOrNull(found.range.first - 1)) && !goesOn(text.getOrNull(found.range.last + 1))
                Character.UnicodeScript.HANGUL -> HANGUL_RUN.findAll(text).any { run -> found.range.first in run.range && HANGUL_MARKS.matches(run.value) }
                else -> true
            }
        }

        /** Whether it's anywhere in [text], in a longer word too, as a name that holds it may still say it. */
        fun inside(text: String): Boolean = pattern.containsMatchIn(text)

        companion object {
            /** Whether [next], beside a word in Latin letters, is more of it: a Latin letter, a digit, or a mark over the last letter. */
            private fun goesOn(next: Char?): Boolean =
                next != null && (scriptOf(next) == Character.UnicodeScript.LATIN || next.isDigit() || next.category == CharCategory.NON_SPACING_MARK)

            private fun scriptOf(letter: Char): Character.UnicodeScript = Character.UnicodeScript.of(letter.code)

            /** A run of Hangul. */
            private val HANGUL_RUN = Regex("[\\u1100-\\u11ff\\u3130-\\u318f\\uac00-\\ud7af]+")
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

    /**
     * The same, as other scripts write them, found anywhere in a title; written as [normalize]
     * leaves them, which takes the marks off ブ and splits 커버 into its letters.
     */
    private val VERSION_MARKS = listOf(
        "カラオケ", "ライブ", "弾き語り", "歌ってみた", "演奏してみた", "弾いてみた", "叩いてみた", "ピアノ", "ギター",
        "カバー", "メドレー", "練習", "オルゴール", "アカペラ", "翻唱", "伴奏", "伴唱", "卡拉ok", "커버", "라이브", "노래방"
    ).map(::normalize)

    /** [VERSION_MARKS] and more, but those katakana writes (see [KATAKANA]), with what they say in Latin letters: see [Mark]. */
    private val MARKS = listOf(
        Mark("弾き語り", "弾き語り"), Mark("歌ってみた", "cover"), Mark("演奏してみた", "cover"), Mark("弾いてみた", "cover"), Mark("叩いてみた", "cover"),
        Mark("練習", "practice"), Mark("翻唱", "cover"), Mark("伴奏", "instrumental", "karaoke"), Mark("伴唱", "karaoke"), Mark("卡拉ok", "karaoke"),
        Mark("커버", "cover"), Mark("라이브", "live"), Mark("노래방", "karaoke"), Mark("버전", "version"),
        // Many songs in one: "(串烧)", "(메들리)".
        Mark("串烧", "medley"), Mark("串燒", "medley"), Mark("메들리", "medley"),
        // Without the singing, or cut for TV: "(Off Vocal)", "(オフ・ヴォーカル)" once in Latin letters, "(TV Size)", "(纯音乐)", "(반주)".
        Mark("offvocal", "instrumental", "karaoke"), Mark("tvsize", "tvsize"), Mark("纯音乐", "instrumental"), Mark("純音樂", "instrumental"),
        Mark("인스트", "instrumental"), Mark("반주", "instrumental", "karaoke"),
        // Remixed, or sung live: "(混音)", "(리믹스)", "(演唱会)".
        Mark("混音", "remix"), Mark("리믹스", "remix"), Mark("演唱会", "live", "concert"), Mark("演唱會", "live", "concert")
    )

    /** A run of [MARKS] in Hangul and nothing else, "라이브버전". */
    private val HANGUL_MARKS = Regex(MARKS.map(Mark::letters).filter { it.first() in '\uac00'..'\ud7af' }.joinToString("|", "(?:", ")+"))

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
     * The words that say a version, as [inLatin] writes them: [VERSION_WORDS], [OTHER_RECORDING], what [MARKS] say, a
     * language, and "version". Other words [KATAKANA] has say one only in katakana (see [IN_KATAKANA]), as "TV" or
     * "Slow" in Latin letters may be anything.
     */
    private val VOCAB = VERSION_WORDS + OTHER_RECORDING + MARKS.flatMap(Mark::says) + setOf("version", "english", "japanese", "extended") - setOf("ver", "inst")

    /**
     * The words [KATAKANA] says a version with, "テレビ" or "バラード", but those an edition says, "シングル" or "リマスター"
     * (see [withoutEditionTag]), and those that say none alone, "ニュー", "スタジオ" or "オフ".
     */
    private val IN_KATAKANA = KATAKANA.values.flatMap(::words).toSet() - setOf(
        "single", "album", "original", "remaster", "remastered", "mono", "stereo", "full", "off", "vocal", "size", "up", "re", "studio", "new",
        "special", "self", "edit", "radio"
    )

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
     * Whether [name] is one of the song's [titles], as main's own steps can't tell: one of its names (see [namesIn]) is
     * one of the song's, written in the same script (see [alike]). Only to take what ChatGPT picks, never to doubt a row.
     */
    fun namesTheSong(name: String, titles: Collection<String>): Boolean {
        val own = titles.flatMap(::namesIn).map(::Name)
        return namesIn(name).map(::Name).any { part -> own.any { alike(part, it) } }
    }

    /** The names [title] goes by, as [titleNames] has them, each without its tags or a subtitle: both of "偽の歌 - Fake Song". */
    private fun namesIn(title: String): List<String> =
        titleNames(title).drop(1).ifEmpty { listOf(title) }.map { bareTitle(withoutEditionTag(it.replace(subtitle, " "))) }

    /**
     * Whether [one] and [other] are one name, written in one script: the same once folded, one of its [Name.ways]; or,
     * in kanji, read the same, as 桜 and 櫻 are, with every kanji's reading known. Never in two scripts, as 明日 and
     * あす, which can't be told from 明日 and あした.
     */
    private fun alike(one: Name, other: Name): Boolean {
        if (one.script != other.script) return false
        if (one.ways.any(other.ways::contains)) return true
        return one.script == Character.UnicodeScript.HAN && readOut(one.text).let { it == readOut(other.text) && it.none(Readings::isHan) }
    }

    /**
     * A name as it's compared: its [ways], NFKC, lowercase and only letters and digits, a Latin letter without its marks
     * or strokes, "ü" also as German spells it out, "ue", so "Müde" is "Muede"; kana as hiragana, ヴ as ブ, ぢ as じ, づ as
     * ず, a repeat mark as what it repeats and no long vowel (see [withoutLongVowels]), so "トーキョー" is "とうきょう"; and
     * kanji as China writes them, so 小幸運 is 小幸运. Its [script], kanji wherever there's one, as Japanese writes kana
     * with them, else kana, else its first letter's.
     */
    private class Name(val text: String) {
        val ways = listOf(GERMAN, emptyMap()).map { folded(text, it) }.distinct()
        val script: Character.UnicodeScript = ways.first().let { way ->
            when {
                way.any(Readings::isHan) -> Character.UnicodeScript.HAN
                way.any(Readings::isKana) -> Character.UnicodeScript.HIRAGANA
                else -> way.firstOrNull(Char::isLetter)?.let { Character.UnicodeScript.of(it.code) } ?: Character.UnicodeScript.COMMON
            }
        }
    }

    /** [text] folded as [Name] says, with some letters [spelled] out. */
    private fun folded(text: String, spelled: Map<Char, String>): String {
        val letters = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).map { spelled[it] ?: UNMARKED[it] ?: unmarked(it) }
        val kana = Readings.hiragana(withBu(letters.joinToString(""))).replace('ぢ', 'じ').replace('づ', 'ず')
        return asChinaWrites(withoutLongVowels(repeated(kana).filter(Char::isLetterOrDigit)))
    }

    /** [kana] with each repeat mark written as what it repeats: "こゝろ" is "こころ", "いすゞ" "いすず", "時々" "時時". */
    private fun repeated(kana: String): String = kana.fold(StringBuilder()) { done, letter ->
        val before = done.lastOrNull()?.toString().orEmpty()
        done.append(
            when (letter) {
                'ゝ', 'ヽ', '々' -> before
                'ゞ', 'ヾ' -> Normalizer.normalize(before + '\u3099', Normalizer.Form.NFC)
                else -> letter
            }
        )
    }.toString()

    /** [letter] without its marks when it's a Latin one, "é" as "e", where "が" keeps its sound mark. */
    private fun unmarked(letter: Char): String =
        if (Character.UnicodeScript.of(letter.code) == Character.UnicodeScript.LATIN) Normalizer.normalize("$letter", Normalizer.Form.NFD).replace(marks, "") else "$letter"

    /** How German spells out a letter with an umlaut, as a name in Latin letters may write it. */
    private val GERMAN = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue")

    /** How [name] reads, in hiragana, with no long vowel; a kanji no reading knows stays as it is. */
    private fun readOut(name: String): String =
        withoutLongVowels(Normalizer.normalize(Readings.of(listOf(name), Script.JAPANESE).single().reading.inHiragana(true), Normalizer.Form.NFKC).filter(Char::isLetterOrDigit))

    /** [kana] with no long vowel, written ー or as the vowel again (see [lengthens]): "とうきょう" and "とーきょー" are "ときょ". */
    private fun withoutLongVowels(kana: String): String = kana.filterIndexed { at, letter -> letter != 'ー' && (at == 0 || !lengthens(kana[at - 1], letter)) }

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
