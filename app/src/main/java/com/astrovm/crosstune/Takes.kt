package com.astrovm.crosstune

import java.text.Normalizer

/**
 * The take of a song a name says it is: live, karaoke, a TV size. YouTube Music lists these beside the studio song, in any
 * script, and a row that says so isn't the song the link names.
 */
internal object Takes {
    /** What a song's tag can say its recording is, when it isn't the studio one. */
    enum class Take { LIVE, OTHER }

    /**
     * The take a tag of [name] says it is: "(ライブ)", "(Live from Budokan)", "- Live", "(オフ・ヴォーカル)", "(TV Size)". Only
     * how a tag starts, or ends in a remix or a cover, counts, so a show's name inside one, "(TVアニメ「ライブ」オープニング
     * テーマ)", and a credit, "(with Live Crew)", say nothing, nor a tag that is an [artists] own name, "(Live Crew)". Null when no
     * tag does.
     */
    fun saysAnotherTake(name: String, artists: Collection<String> = emptyList()): Take? {
        val named = artists.flatMap(SongNames::artistNames).map(SongNames::normalize)
        val takes = tagsOf(name).filter { tag -> SongNames.normalize(tag).let { letters -> named.none { letters == it } } }.mapNotNull(::takeOfTag)
        return takes.firstOrNull { it == Take.LIVE } ?: takes.firstOrNull()
    }

    /**
     * [title] says it's another version, a cover, karaoke, a remix, by a word or a mark anywhere in it, but not for the word
     * "live", which a song's own name may hold, as "Live Forever" does: a live take says so in a tag, which [saysAnotherTake] reads.
     */
    fun saysAnotherVersionBesidesLive(title: String): Boolean = SongNames.saysAnotherVersion(title, "live", "")

    /** Read only a disco note on a normalized copy. The matcher still needs the original title or album. */
    fun namesDisco(name: String): Boolean =
        tagsOf(Normalizer.normalize(name, Normalizer.Form.NFKC)).any { tag ->
            // A dash note or an outer bracket can hold a second tag, such as a remaster note.
            SongNames.normalize(tag.takeWhile { it !in TAG_BRACKET_OPENS }) in DISCO_LABELS
        }

    /** A link can name a disco recording without using the same note delimiters as a search result. */
    fun requestsDisco(name: String): Boolean {
        val written = Normalizer.normalize(name, Normalizer.Form.NFKC)
        val words = SongNames.writtenWords(written)
        val joined = words.any { it == "discoversion" || it == "discomix" }
        if (joined || words.zipWithNext().any { (first, second) -> first == "disco" && second in DISCO_LATIN_ENDINGS }) return true
        val letters = SongNames.normalize(written)
        return DISCO_JAPANESE_LABELS.any { it in letters }
    }

    private val DISCO_LATIN_ENDINGS = setOf("version", "mix", "ver")
    private val DISCO_JAPANESE_LABELS = listOf(
        "DISCOバージョン", "プロモーション用DISCOバージョン",
        "ディスコバージョン", "ディスコヴァージョン", "ディスコ版",
        "プロモーション用ディスコバージョン", "プロモーション用ディスコヴァージョン", "プロモーション用ディスコ版"
    ).map(SongNames::normalize).toSet()
    private val DISCO_LABELS = DISCO_LATIN_ENDINGS.map { "disco$it" }.toSet() + DISCO_JAPANESE_LABELS

    /** The text of each tag in [name]: what's inside brackets, and what follows a dash set apart by spaces. */
    private fun tagsOf(name: String): List<String> {
        val tags = mutableListOf<String>()
        var depth = 0
        var start = 0
        name.forEachIndexed { at, char ->
            when {
                char in TAG_BRACKET_OPENS -> if (depth++ == 0) start = at + 1
                char in TAG_BRACKET_CLOSES && depth > 0 -> if (--depth == 0) tags += name.substring(start, at)
            }
        }
        return tags + afterSpacedDashes(name)
    }

    /** The brackets that hold a tag; 「」 hold a show's name, or a quote. */
    private const val TAG_BRACKET_OPENS = "([【〔（［"
    private const val TAG_BRACKET_CLOSES = ")]】〕）］"

    /** What starts at each dash set apart by spaces in [name], up to the next one. */
    private fun afterSpacedDashes(name: String): List<String> {
        val cuts = (1 until name.lastIndex).filter { name[it] in TitleDelimiters.DASHES && name[it - 1].isWhitespace() && name[it + 1].isWhitespace() }
        return cuts.zip(cuts.drop(1) + name.length) { from, to -> name.substring(from, to) }
    }

    private fun takeOfTag(tag: String): Take? {
        val letters = SongNames.normalize(tag).trimStart { it.isDigit() }
        val words = SongNames.wordList(tag).dropWhile { it.all(Char::isDigit) }
        val first = words.firstOrNull()
        val spoken = words.take(2).joinToString("")
        return when {
            letters.isEmpty() -> null
            first in LIVE_FIRST_WORDS || spoken in LIVE_PAIRS || LIVE_MARKS.any(letters::startsWith) -> Take.LIVE
            first in OTHER_FIRST_WORDS || spoken in OTHER_PAIRS || words.lastOrNull() in OTHER_LAST_WORDS ||
                OTHER_MARKS.any(letters::startsWith) -> Take.OTHER
            else -> null
        }
    }

    private val LIVE_FIRST_WORDS = setOf("live", "vivo", "directo")
    private val LIVE_PAIRS = setOf("envivo", "aovivo")
    private val LIVE_MARKS = listOf("ライブ", "ライヴ", "现场", "現場", "演唱会", "演唱會", "라이브").map(SongNames::normalize)
    private val OTHER_FIRST_WORDS = setOf(
        "karaoke", "instrumental", "inst", "acoustic", "unplugged", "demo", "cover", "piano", "guitar", "remix", "orgel", "musicbox",
        "ktv", "acapella", "acappella", "cappella", "short"
    )

    /** "Off Vocal", "TV Size": no word of its own says it, but both together do. */
    private val OTHER_PAIRS = setOf("offvocal", "tvsize", "tvedit", "tvver", "opsize", "edsize", "offvoice")
    private val OTHER_LAST_WORDS = setOf("remix", "cover", "karaoke", "instrumental")

    /** How a tag of another take starts, written as [SongNames.normalize] leaves it, which takes the marks off ブ and splits 커버 into its letters. */
    private val OTHER_MARKS = listOf(
        "カラオケ", "弾き語り", "歌ってみた", "演奏してみた", "弾いてみた", "叩いてみた", "ピアノ", "ギター", "カバー", "メドレー", "練習", "オルゴール", "アカペラ",
        "翻唱", "伴奏", "伴唱", "卡拉ok", "커버", "노래방", "オフボーカル", "オフヴォーカル", "オフボ", "インスト", "リミックス", "カヴァー", "アコースティック",
        "アンプラグド", "ショートバージョン", "ショートver", "ショートサイズ", "tvサイズ", "纯音乐", "純音樂", "伴奏版", "인스트", "리믹스", "어쿠스틱", "混音"
    ).map(SongNames::normalize)
}
