package com.astrovm.crosstune

import java.util.Random

/**
 * Titles made up at random from what titles are made of, the same ones on each run: words, the tags that videos
 * and stores add, every kind of bracket, emoticons, other scripts and marks, and the spaces, tabs, line breaks and
 * dashes between them. Some are made the way a title ends, a song and the tags after it, each after a dash, a bar or
 * in brackets, as the readers cut them off one at a time. Some have letters that a pattern that ignores case reads
 * as others, "K" (the Kelvin sign) as "k", "ſ" as "s", "ﬃ" as "ffi", and "ı" and "İ", which a phone doesn't read as "i".
 * Some have the spaces, line ends and digits that a phone's ICU reads as such and the JVM doesn't. Each is read by
 * every function that reads titles, through [TitleProbes], and what each gives is put on one line, so that one
 * checkout's readings can be compared with another's.
 *
 * `scripts/titlefuzz/baseline.py` makes main's readings with this file; [TitlesAgainstMainTest] compares them.
 * Change what this file makes, and the baseline is made again.
 */
internal object TitleFuzz {
    const val COUNT = 200_000
    private const val SEED = 933L
    private const val MOST_PIECES = 12

    /** A title, and the channel and artist a player names with it. */
    data class Title(val text: String, val channel: String, val artist: String)

    /** What each reading is, in the order [readings] gives them. */
    val FIELDS = listOf(
        "musicVideo", "youtubeVideo", "playerSong", "coverOf", "bareTitle", "withoutEditionTag", "featuring",
        "bracketedSong", "chordWikiTitles", "outsideWords",
    )

    fun titles(): Sequence<Title> = sequence {
        yieldAll(FIXED)
        val random = Random(SEED)
        repeat(COUNT - FIXED.size) { yield(if (random.nextInt(5) < 2) ending(random) else title(random)) }
    }

    /**
     * Titles first, as reviews found them: where a phone's ICU reads a space, a line end, a digit or a letter ignoring
     * case otherwise than the JVM, and songs in 「」 and 『』 with no name.
     */
    val FIXED = listOf(
        Title("アイドル\u3000feat. X", "Channel", "Artist"), Title("Song（歌：A）\u3000", "Channel", "Artist"),
        Title("Song\u00A0feat. X", "Channel", "Artist"), Title("Song - Live\u000B", "Channel", "Artist"),
        Title("Song (Live)\u000C", "Channel", "Artist"), Title("Song (Lyrıcs)", "Channel", "Artist"),
        Title("Song ft. X (Lİve)", "Channel", "Artist"), Title("Song ٢  ft. X", "Channel", "Artist"),
        Title("Song\u2007-\u2007Remastered", "Channel", "Artist"), Title("Song (Oﬃcial Video)", "Channel", "Artist"),
        Title("Song (Remaﬆered)", "Channel", "Artist"), Title("Song -\u0085", "Channel", "Artist"),
        Title("「|」 Q", "Artist - Topic", "Artist"), Title(" 『\u3000 』", "Channel", "Artist"),
        Title("Band - Song 『[HD] 』", "Channel", "Artist"),
    ) + listOf(
        // TitlesReadAsMainTest's, read by main on a phone.
        "Artist「 」", "Artist『　』", "Artist「 」 Band「Song」", "「x『 」", "「『」」", "A「」「 」『x』", "A「x", "A\n「 」『x』", "A\nB「 」『x』",
        "/ \n 「 」 \n in the style of Q", "Song - Acoustic\nCover", "Song - Acoustic Cover\r\n", "Song - Acoustic Cover\nLive",
        "Song ft. X - Remastered 2011)", "Song feat. X - Remastered)", "Song ft. X - 2011 Remaster]", "Song featuring X - Single Version)",
        "Song ft. X - Remastered 2011)\n", "Song ft. X - Remastered (Live)", "Song 4\u212A", "Song - Lyric\u017F", "Song Lyr\u0131cs",
        "Song Lyr\u0130cs", "Song H\u0131", "Artist - Song | Lyrics |HD|\u0085", "Artist - Song -\u0085", "Artist - Song | Lyrics |HD|\u2028",
        "Artist - Song | Lyrics |HD|\u2029", "Artist - Song | Lyrics |HD|\r\n", "Artist - Song 4K\u2028", "Artist - Song | Lyrics |HD|\u0085x",
        "Song 2  ft. X", "Song 2\t ft. X", "Song 2\n ft. X", "6 ft. Under", "Artist - Song （］「『[］)Live - 」/", "Artist - Song (Live [2019])",
        "Artist - Song [Official Video :(] (feat. X) :)", "夜に駆ける\u3000（歌：YOASOBI）", "夜に駆ける （歌：YOASOBI）\u0085", "Song (A) （歌：B (C）",
        "Song - song and lyrics by Artist | Spotify", "Song - song and lyrics by Artist | Spotify\u0085", "Song - song and lyrics by Artist『x』 | Spotify",
        "Song - song and lyrics by Artist『x』 | Spotify\nmore",
    ).map { Title(it, "Channel", "") } + listOf(
        Title("song and lyrics by Artist | Spotify", "", "Song"), Title("song and lyrics by \nArtist | Spotify", "", "Song"),
        Title("Song - song and lyrics by Artist | Spotify", "", ""), Title("Song - song and lyrics by Artist | Spotify\u0085", "", ""),
    )

    /** What each of [FIELDS] gives for [title]: null when it gives nothing. */
    fun readings(title: Title): List<String?> = listOf(
        TitleProbes.musicVideo(title.text, title.channel)?.let { "${it.title}$PART${it.artist}" },
        TitleProbes.youtubeVideo(title.text, title.channel)?.let { "${it.title}$PART${it.artist}" },
        TitleProbes.playerSong(title.text, title.artist)?.let { "${it.title}$PART${it.artist}" },
        TitleProbes.coverOf(title.text)?.let { "${it.title}$PART${it.artist}" },
        TitleProbes.bareTitle(title.text),
        TitleProbes.withoutEditionTag(title.text),
        TitleProbes.withoutFeaturing(title.text),
        TitleProbes.bracketedSong(title.text)?.joinToString(PART.toString()),
        TitleProbes.chordWikiTitles(title.text).joinToString(PART.toString()),
        TitleProbes.outsideWords(title.text).sorted().joinToString(" "),
    )

    /** Where one part of a reading ends and the next starts: a song's title and artist. No title has it. */
    const val PART = '\u001F'

    /**
     * The baseline's line for [title]: its [readings], then for each of its [cuts], after [CUT], the cut title's
     * readings of [SONG_FIELDS]. `WriteTitlesBaseline` writes it on main.
     */
    fun baselineLine(title: Title): String = withCuts(title) +
        variants(title).joinToString("") { (name, variant) -> "$VARIANT$name$VARIANT${withCuts(variant)}" }

    private fun withCuts(title: Title) = line(title, readings(title)) + cuts(title).joinToString("") { cut -> CUT + line(cut, songReadings(cut)) }

    /** Where a baseline's line goes on with a variant's name, then its readings and its cuts'. No title has it. */
    const val VARIANT = '\u001D'

    /**
     * The title written another way, that main read as the title is read here, by name: with a mark for the brackets
     * in its tags' tags, "Song (Live [2019])" as "Song (Live ¦2019¦)", for each set of brackets a reader nests;
     * and without a 「」 that has only spaces in it. Main's readings of them tell a difference that is better.
     */
    fun variants(title: Title): List<Pair<String, Title>> =
        NESTING.mapNotNull { kinds -> flat(title.text, kinds)?.let { "$FLAT$kinds" to title.copy(text = it) } } +
            listOfNotNull(withoutBlank(title.text)?.let { BLANK to title.copy(text = it) })

    const val FLAT = "flat "
    const val MARK = '¦'
    const val BLANK = "blank"

    /** The brackets each reader nests tags in, by their openers: a video's, a cover's, ChordWiki's, and a song's words. */
    val NESTING = listOf("([", "([【", "（(", "([（【「『［")

    /** Each opener's closer. */
    private val CLOSER = "([（【「『［".zip(")]）】」』］").toMap()

    /**
     * How deep [text]'s tags go, by the brackets [opens] starts: -1 when one of them doesn't close in order, with its
     * own kind.
     */
    fun depth(text: String, opens: String): Int {
        val closes = opens.map { CLOSER.getValue(it) }.joinToString("")
        val open = StringBuilder()
        var deepest = 0
        for (char in text) {
            val kind = opens.indexOf(char)
            if (kind >= 0) {
                open.append(closes[kind])
                deepest = maxOf(deepest, open.length)
            } else if (char in closes) {
                if (open.isEmpty() || open.last() != char) return -1
                open.setLength(open.length - 1)
            }
        }
        return if (open.isEmpty()) deepest else -1
    }

    /**
     * [text] with [MARK] for each bracket [opens] starts where it's in another pair of them, or null where none is: a
     * mark that main read as no letter, space or bracket, as the reader here reads a bracket in a tag.
     */
    private fun flat(text: String, opens: String): String? {
        if (depth(text, opens) < 2) return null
        val closes = opens.map { CLOSER.getValue(it) }.joinToString("")
        var open = 0
        val kept = StringBuilder()
        for (char in text) {
            when (char) {
                in opens -> kept.append(if (open++ == 0) char else MARK)
                in closes -> kept.append(if (--open == 0) char else MARK)
                else -> kept.append(char)
            }
        }
        return kept.toString()
    }

    /**
     * [text] without the 「」 or 『』 that main read its song in, again while that song has only spaces, or null where it
     * isn't one: main's pattern to where the song closes.
     */
    private fun withoutBlank(text: String): String? {
        var kept = text
        while (true) {
            val song = mainSong.find(kept)?.groups?.get(1) ?: break
            if (song.value.drop(1).dropLast(1).isNotBlank()) break
            kept = kept.removeRange(song.range)
        }
        return kept.takeIf { it != text }
    }
    private val mainSong = phoneRegex("""^.*?\s*([「『][^」』]+[」』])""")

    /** Where a baseline's line goes on with the readings of one of the title's [cuts]. No title has it. */
    const val CUT = '\u001E'

    /** The fields read again on each of a title's [cuts], as they read the song a title names in 「」. */
    val SONG_FIELDS = listOf("musicVideo", "youtubeVideo", "playerSong", "bracketedSong")

    /** What each of [SONG_FIELDS] gives for [title]. */
    fun songReadings(title: Title): List<String?> = readings(title).let { all -> SONG_FIELDS.map { all[FIELDS.indexOf(it)] } }

    /**
     * [title] up to each line break after the song it may name in 「」 or 『』, "YOASOBI「アイドル」\nOfficial\nVideo"
     * being "YOASOBI「アイドル」" and "YOASOBI「アイドル」\nOfficial": after the first 「 or 『 with something in it, as
     * main read none where a line followed. None when it names none, or no line follows. The first line needn't lead
     * to it, and the tags before it and after it may go first, so every line it may end on is one.
     */
    fun cuts(title: Title): List<Title> {
        val text = title.text
        val open = text.indices.firstOrNull { text[it] in "「『" && it + 1 < text.length && text[it + 1] !in "」』" } ?: return emptyList()
        val close = text.indexOfAny(charArrayOf('」', '』'), open + 1).takeIf { it >= 0 } ?: return emptyList()
        return (close + 1 until text.length).filter { text[it] in LINE_BREAKS && text[it - 1] !in LINE_BREAKS }.map { title.copy(text = text.substring(0, it)) }
    }

    /** What ends a line on a phone, where ICU's `.` stops at a vertical tab and a form feed too. */
    private const val LINE_BREAKS = "\n\r\u000B\u000C\u0085\u2028\u2029"

    /** [readings] as a line: each as itself, or as where it is in the title, which most are. */
    fun line(title: Title, readings: List<String?>): String = readings.joinToString("\t") { reading ->
        reading?.split(PART)?.joinToString(PART.toString()) { part -> inTitle(title.text, part) } ?: NOTHING
    }

    /** The readings a [line] holds, for [title]. */
    fun readingsIn(title: Title, line: String): List<String?> = line.split('\t').map { reading ->
        if (reading == NOTHING) null else reading.split(PART).joinToString(PART.toString()) { part -> fromTitle(title.text, part) }
    }

    private const val NOTHING = "\\0"

    /** A part that is in the title as "\@start,end", others with tabs, line breaks and backslashes escaped. */
    private fun inTitle(title: String, part: String): String {
        val at = if (part.isEmpty()) -1 else title.indexOf(part)
        return if (at >= 0) "\\@$at,${at + part.length}" else escaped(part)
    }

    private fun fromTitle(title: String, part: String): String = if (part.startsWith("\\@")) {
        val (start, end) = part.removePrefix("\\@").split(',').map(String::toInt)
        title.substring(start, end)
    } else {
        unescaped(part)
    }

    private fun escaped(text: String) = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescaped(text: String): String {
        val out = StringBuilder()
        var at = 0
        while (at < text.length) {
            val char = text[at]
            if (char == '\\' && at + 1 < text.length) {
                out.append(
                    when (text[at + 1]) {
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> text[at + 1]
                    }
                )
                at += 2
            } else {
                out.append(char)
                at++
            }
        }
        return out.toString()
    }

    private fun title(random: Random): Title {
        val text = StringBuilder()
        repeat(1 + random.nextInt(MOST_PIECES)) {
            if (text.isNotEmpty()) text.append(pick(random, BETWEEN))
            val piece = pick(random, POOLS[random.nextInt(POOLS.size)])
            // A piece in a pair of brackets of one kind, as most tags are.
            if (random.nextInt(4) == 0) {
                val (open, close) = PAIRS[random.nextInt(PAIRS.size)]
                text.append(open).append(piece).append(close)
            } else {
                text.append(piece)
            }
        }
        return Title(folded(random, text), pick(random, CHANNELS), pick(random, ARTISTS))
    }

    /** A title made the way one ends: a song, then tags each after a dash, a bar, a space or in a bracket, then how it ends. */
    private fun ending(random: Random): Title {
        val text = StringBuilder(pick(random, WORDS))
        repeat(1 + random.nextInt(MOST_TAGS)) {
            text.append(pick(random, LEADS))
            val piece = pick(random, ENDS)
            if (random.nextInt(3) == 0) {
                val (open, close) = TAG_PAIRS[random.nextInt(TAG_PAIRS.size)]
                text.append(open).append(piece).append(close)
            } else {
                text.append(piece)
            }
        }
        text.append(pick(random, ENDINGS))
        return Title(folded(random, text), pick(random, CHANNELS), pick(random, ARTISTS))
    }

    /**
     * [text], where it's one of a few titles, with some of the letters a pattern that ignores case reads as others,
     * and some letters as the one letter ICU reads them as: "ﬃ" for "ffi", "ß" for "ss".
     */
    private fun folded(random: Random, text: CharSequence): String {
        if (random.nextInt(FOLDED) != 0) return text.toString()
        var folded = text.map { char -> FOLDS[char]?.takeIf { random.nextBoolean() }?.let { pick(random, it) } ?: char.toString() }.joinToString("")
        for ((letters, ligature) in LIGATURES) if (random.nextBoolean()) folded = folded.replace(letters, ligature)
        return folded
    }

    private fun pick(random: Random, from: List<String>) = from[random.nextInt(from.size)]

    private const val MOST_TAGS = 4
    private const val FOLDED = 5

    /** The letters that a pattern that ignores case reads as these: the Kelvin sign, the long s, a dotless i and a dotted I. */
    private val FOLDS = mapOf(
        'k' to listOf("\u212A"), 'K' to listOf("\u212A"), 's' to listOf("\u017F"), 'S' to listOf("\u017F"),
        'i' to listOf("\u0131", "\u0130"), 'I' to listOf("\u0131", "\u0130"),
    )
    /** Letters ICU reads ignoring case as the one letter each folds to. */
    private val LIGATURES = listOf("ffi" to "ﬃ", "ff" to "ﬀ", "fi" to "ﬁ", "st" to "ﬆ", "ss" to "ß", "SS" to "ẞ")
    private val TAG_PAIRS = listOf("()", "[]", "（）", "【】").map { it[0] to it[1] }
    private val LEADS = listOf(
        " ", " ", " - ", " - ", " – ", "-", "  ", "2  ", "2 ", "\t", "\n", "\r\n", "\u0085", "\u2028", " | ", "|", " (", "[", " \u3000", " // ",
        // Spaces, line ends and digits that ICU reads as such and the JVM doesn't.
        "\u00A0", "\u3000", "\u2007", "\u202F", "\u000B", "\u000C", "٢  ", "２ ", " \u00A0- ",
    )
    private val ENDS = listOf(
        "ft. X", "feat. X", "featuring X", "Feat X", "FT. X", "ft X", "feat.", "Remastered 2011", "2011 Remaster", "Remaster", "Mono",
        "Stereo", "Radio Edit", "Album Version", "Original", "OST", "主題歌", "Live", "Remix", "Acoustic", "Demo", "Lyrics", "HD", "4K",
        "HQ", "Official Video", "Official Audio", "Official Music Video", "MV", "M/V", "Audio", "Lyric Video", "Visualizer", "sub español",
        "Sub Esp", ")", "]", "|", "|HD|", "】", "）", "Cover", "Acoustic Cover", "cover of Q", "Topic", "Video", "Song", "x",
    )
    private val ENDINGS = listOf(
        "", "", "", ")", "]", "|", " |", "\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029", " ", "\t", " \u0085",
        "\u000B", "\u000C", "\u3000", "\u00A0", " \u000B", "\u000C\n",
    )
    private val PAIRS = listOf("()", "[]", "{}", "（）", "【】", "「」", "『』", "〈〉", "［］").map { it[0] to it[1] }
    private val WORDS = listOf("Song", "Artist", "Love", "2", "1987", "Song 2", "x", "Q", "YOASOBI", "アイドル", "夜に駆ける", "Band", "Remix")
    private val TAGS = listOf(
        "Official", "Video", "Official Video", "Official Music Video", "Official Audio", "Lyrics", "Lyric Video", "Live",
        "Live at Wembley", "Cover", "Acoustic Cover", "Piano Cover by X", "Acoustic", "Piano", "feat. X", "ft. X", "featuring X",
        "Feat X", "Remastered 2012", "2012 Remaster", "Remaster", "Mono", "Stereo", "Album Version", "Radio Edit", "Original",
        "HD", "4K", "HQ", "Full HD", "Audio", "MV", "M/V", "Visualizer", "in the style of Q", "originally by Q",
        "originally performed by Q", "made famous by Q", "cover of Q", "Covered", "カバー", "歌ってみた", "翻唱", "커버",
        "歌：YOASOBI", "唄：Q", "作詞・作曲：Ayase", "編曲", "原曲", "Sub Español", "Sub Esp", "subtitulado", "Video Oficial",
        "Letra", "Clipe", "OST", "主題歌", "Theme Song", "公式", "歌詞", "가사", "Official Lyric Video", "Music Video",
        "Animated Video", "Videoclip Oficial", "Audio Oficial", "Topic",
    )
    private val MARKS = listOf(
        "(", ")", "[", "]", "{", "}", "（", "）", "【", "】", "「", "」", "『", "』", "〈", "〉", "［", "］",
        ":(", ":)", ";)", ":-(", "(:", "):", "XD", "<3", "!", "?", "\"", "'", "...", "#1", "@",
    )
    private val SCRIPTS = listOf(
        "أغنية", "حب", "שיר", "אהבה", "\u200F", "\u200E", "🎵", "😀", "👍🏽", "Ünïcödé", "Ђорђе", "노래", "\u3000", "\u00A0",
        // Marks and joiners a phone's "\b" looks past, digits that ICU's "\d" reads, and spaces that its "\s" does.
        "Cafe\u0301", "\u0301", "a\u200Db", "\u200D", "١٩٨٧", "２０１１", "\u1680", "\u205F", "\u2007", "\u202F",
    )
    private val SEPARATORS = listOf(" - ", " – ", " ‑ ", "-", " | ", "|", " / ", "/", "//", " // ", ": ", ".", ",", " & ", " x ", " y ", " + ")
    private val SPOTIFY = listOf(
        " | Spotify", "Song, Lyrics, and Music Video by Artist", "song and lyrics by Artist", "Artistの曲、歌詞、ミュージックビデオ",
        "song by Artist", ".mp3", ".flac", "example.com/song", "Artist by Band",
    )
    private val POOLS = listOf(WORDS, WORDS, TAGS, TAGS, MARKS, SCRIPTS, SEPARATORS, SPOTIFY)
    private val BETWEEN = listOf(
        " ", " ", " ", " ", "", "", "  ", "\t", "\n", "\r\n", " - ", "   ", "\n\n", " \u3000", "\r", "\u0085", "\u2028", "\u2029",
        "\u00A0", "\u2007", "\u202F", "\u205F", "\u1680", "\u000B", "\u000C", " \u000B ",
    )
    private val CHANNELS = listOf("Channel", "Artist - Topic", "ArtistVEVO", "", "Artist", "Q")
    private val ARTISTS = listOf("", "Artist", "Unknown", "Various Artists", "example.com", "Q")
}
