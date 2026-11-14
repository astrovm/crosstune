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
        return VERSION_MARKS.keys.any { it in letters && it !in normalize(title) && it !in normalize(artist) }
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
     * The same, as other scripts write them, found anywhere in a title, each with the word it says in
     * Latin letters; written as [normalize] leaves them, which takes the marks off ブ and splits 커버 into its letters.
     */
    private val VERSION_MARKS = mapOf(
        "カラオケ" to "karaoke", "ライブ" to "live", "弾き語り" to "acoustic", "歌ってみた" to "cover", "演奏してみた" to "cover",
        "弾いてみた" to "cover", "叩いてみた" to "cover", "ピアノ" to "piano", "ギター" to "guitar", "カバー" to "cover",
        "メドレー" to "medley", "練習" to "practice", "オルゴール" to "musicbox", "アカペラ" to "acappella", "翻唱" to "cover",
        "伴奏" to "instrumental", "伴唱" to "karaoke", "卡拉ok" to "karaoke", "커버" to "cover", "라이브" to "live", "노래방" to "karaoke"
    ).mapKeys { normalize(it.key) }

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

    /** A track number before a file's title, "01 Fake Song" or "1. Fake Song", but not a name's own, "7 Rings". */
    private val trackNumber = Regex("""^(?:0\d|\d{1,2}\.)\s+""")

    /** A tag of a name: in brackets, anywhere in it, or after a dash at its end. "(Live)", "[Explicit]", " - Official Audio". */
    private val anyTag = Regex("""\s*[(\[]([^()\[\]]*)[)\]]|\s+[-–]\s+([^-–()\[\]]*)$""")

    /**
     * What a tag says that never names another recording: "Explicit", "Clean", "Official Audio", "Official Video", "Lyric Video",
     * "Lyrics", or the work it's from, up to its closing quote, "From "Fake Movie"". Any other tag may, "(Part 2)", "(Reprise)",
     * "(Taylor's Version)", "(ライブ)", "(From 'Fake Movie' / Live)", so it stays. Remasters, mono and guests are what
     * [withoutEditionTag] already takes off.
     */
    private val sameRecordingTag = Regex(
        """\s*(?:explicit|clean|lyrics|(?:official\s+)?(?:audio|video|music\s+video|lyric\s+video)|(from\s+["“'‘«「『][^"”'’»」』]*["”'’»」』]))\s*""",
        RegexOption.IGNORE_CASE
    )

    /** The title a file names its song by, without the track number before it: "Fake Song" from "01 Fake Song". */
    fun withoutTrackNumber(title: String): String = title.replace(trackNumber, "").ifBlank { title }

    /**
     * Whether [title], as a file or a store names a song, "01 Fake Song.mp3" or "Fake Song [Explicit]", is [song]'s.
     * Past its track number, its extension and the tags [sameRecordingTag] knows, wherever they are, it's the same name.
     * Or it differs only in the tags at its end, in brackets or after a dash, every one of them kept, as [wordsMatch]
     * takes them: "Fake Song (Live)" is "Fake Song", as Crosstune always took it, but "Hello (Live)" isn't "Hello", nor
     * "Fake Song (Part 2)" "Fake Song (Part 1)".
     */
    private fun sameAsAFileNamesIt(title: String, song: String): Boolean {
        val file = withoutTrackNumber(title.replace(MetadataParsers.audioExtension, ""))
        if (same(withoutSameRecordingTags(file, worksFrom(song)), withoutSameRecordingTags(song, worksFrom(file)))) return true
        return same(bareTitle(file), bareTitle(song)) && wordsMatch(file, song)
    }

    /**
     * The name without the tags [sameRecordingTag] knows, wherever they are: "Song [Explicit] (Live)" is "Song (Live)". The work
     * it's from stays when the other name is from [another][from] one: "Main Theme (From "Fake Movie")" is no other film's.
     */
    private fun withoutSameRecordingTags(title: String, from: Set<String>): String =
        title.replace(anyTag) { tag ->
            val said = sameRecordingTag.matchEntire(tag.groupValues[1] + tag.groupValues[2])
            val anotherWork = said?.groups?.get(1)?.value?.let { from.isNotEmpty() && normalize(it) !in from } == true
            if (said == null || anotherWork) tag.value else ""
        }.trim().ifEmpty { title }

    /** The works a name says it's from, "From "Fake Movie"", as [normalize] writes them. */
    private fun worksFrom(title: String): Set<String> =
        anyTag.findAll(title).mapNotNull { tag -> sameRecordingTag.matchEntire(tag.groupValues[1] + tag.groupValues[2])?.groups?.get(1)?.value?.let(::normalize) }.toSet()

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
     * Audio Flac" by "keyloginhot". [byWords] off, the title is the song's, as a file or a store names it,
     * "01 Fake Song.mp3" or "Fake Song [Explicit]", never one that shares its words: "Hotel California Radio"
     * isn't "Hotel California".
     */
    fun names(title: String, artist: String, song: MusicMetadata, byWords: Boolean = true): Boolean {
        if (title.isBlank()) return false
        // Read as the song it names the way the song playing is named, e.g. "YOASOBI「アイドル」 Official Music Video".
        val named = MetadataParsers.youtubeVideo(title, artist)?.title
        val near = if (byWords) wordsMatch(title, song.title) else sameAsAFileNamesIt(title, song.title)
        if (!(same(title, song.title) || near || named?.let { same(it, song.title) } == true)) return false
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
        // Nor do they have words for a note in brackets: "小幸运 (电影《我的少女时代》主题曲)" is "小幸运", but "曲 (ライブ)" isn't "曲".
        if (words(one).isEmpty() && words(other).isEmpty()) {
            val bare = normalize(bareTitle(one))
            if (bare.isNotEmpty() && bare == normalize(bareTitle(other)) && versionsInScript(one) == versionsInScript(other)) return true
        }
        val ours = words(withoutEditionTag(one))
        val theirs = words(withoutEditionTag(other))
        // A version said in another script, "(ライブ)", tells recordings apart as "(Live)" would; it never names the song, so it
        // takes nothing the title's own words don't: "愛 (カバー)" isn't "恋 (カバー)", and "Fake Song (ライブ)" is "Fake Song (Live)".
        return oneHoldsTheOther(ours, theirs) && oneHoldsTheOther(ours + versionsInScript(one), theirs + versionsInScript(other))
    }

    /** One of these sets of words holds every one of the other, which, of a single word, must be the whole of it. */
    private fun oneHoldsTheOther(ours: Set<String>, theirs: Set<String>): Boolean {
        val (shorter, longer) = if (ours.size <= theirs.size) ours to theirs else theirs to ours
        return shorter.isNotEmpty() && (shorter.size >= 2 || shorter == longer) && longer.containsAll(shorter)
    }

    /** The versions [title] says in another script, as Latin letters say them: "live" for "ライブ", which [words] has no word for. */
    private fun versionsInScript(title: String): Set<String> = normalize(title).let { letters -> VERSION_MARKS.filterKeys { it in letters }.values.toSet() }

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
