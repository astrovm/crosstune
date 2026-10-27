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
    private val featuring = Regex("""\s*[(\[]\s*(?:feat|ft|featuring)\b[^()\[\]]*[)\]]""", RegexOption.IGNORE_CASE)
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
     */
    fun titleNames(title: String): List<String> =
        listOf(title) + listOfNotNull(MetadataParsers.beforeItInJapanese(title)) +
            title.split(" - ").map(String::trim).filter(String::isNotEmpty).takeIf { it.size == 2 }.orEmpty()

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
    fun withoutEditionTag(title: String): String {
        var stripped = title
        // "Song - 2012 Remaster (Mono)" has two, and a title that's only a tag is left as it is.
        while (true) {
            val unfeatured = stripped.replace(featuring, "")
            val tag = editionTag.find(unfeatured)?.value.orEmpty()
            if (words(tag).any { it in OTHER_RECORDING }) return unfeatured.takeIf { it.isNotBlank() } ?: stripped
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
    private fun distance(one: String, other: String): Int {
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
