package com.astrovm.crosstune

import kotlin.random.Random

/**
 * Made-up songs, sung at an even pace and heard a word at a time, as the speech model hears them, put in
 * time by [SungTiming]: how many lines it puts within a second of where they're sung, for each writing and
 * pace. The recordings the replay has sing few lines fast in some writings (Issue #977); these sing each.
 * Plain Kotlin, so `syncreplay.py synthetic` runs it on its own, and `SyntheticSingingTest` holds it to a floor.
 */
internal object SyntheticSinging {
    enum class Writing { LATIN, JAPANESE, KOREAN, CHINESE }

    /** How long a syllable is sung, in ms: from as fast as anyone sings to a slow song. */
    val PACES = listOf(120L, 150L, 200L, 280L, 400L)

    /** As the speech model has it: a word heard a little after it's sung. */
    private const val HEARD_LATE_MS = 550L
    private const val HEARD_WOBBLE_MS = 80
    private const val WHILE_MS = 20_000L
    private const val SONGS = 50
    private const val LINES = 8
    private const val ON_TIME_MS = 1_000L

    /** Of [lines] sung, those put within a second of where they're sung, those put further off, and the rest left dark. */
    class Score(val lines: Int, val within: Int, val off: Int) {
        val dark get() = lines - within - off
        override fun toString() = "$within within, $off off, $dark dark"
    }

    private class Word(val text: String, val syllables: Int)

    private class Song(val lines: List<String>, val starts: List<Long>, val heard: List<HeardStretch>, val lengthMs: Long)

    fun score(writing: Writing, paceMs: Long): Score {
        var within = 0
        var off = 0
        repeat(SONGS) { n ->
            val song = song(writing, paceMs, Random(writing.ordinal * 100_000 + paceMs.toInt() * 100 + n))
            val placed = SungTiming.linesMs(song.lines, song.heard, song.lengthMs)
            song.starts.forEachIndexed { line, start ->
                val ms = placed[line] ?: return@forEachIndexed
                if (kotlin.math.abs(ms - start) <= ON_TIME_MS) within++ else off++
            }
        }
        return Score(SONGS * LINES, within, off)
    }

    /** [LINES] lines of made-up words, each sung right after the last, with a rest of up to 1.5 s, and heard a word at a time, a few missed. */
    private fun song(writing: Writing, paceMs: Long, random: Random): Song {
        val words = List(LINES) { List(3 + random.nextInt(2)) { word(writing, random) } }
        val spaced = writing == Writing.LATIN || writing == Writing.KOREAN
        val lines = words.map { line -> line.joinToString(if (spaced) " " else "") { it.text } }
        val sung = mutableListOf<Long>()
        val heard = mutableListOf<Pair<String, Long>>()
        var ms = 0L
        for (line in words) {
            sung += ms
            for (word in line) {
                if (random.nextDouble() >= MISSED) heard += word.text to ms + HEARD_LATE_MS + random.nextInt(-HEARD_WOBBLE_MS, HEARD_WOBBLE_MS + 1)
                ms += word.syllables * paceMs
            }
            ms += random.nextLong(REST_MS + 1)
        }
        // The words take the middle of the song, as in most: a tenth before them, and a tenth after.
        val lengthMs = ms * 10 / 8
        val intro = lengthMs / 10
        val starts = sung.map { it + intro }
        val whiles = (0 until lengthMs step WHILE_MS).map { from ->
            HeardStretch(from, from + WHILE_MS, heard.map { (text, at) -> text to at + intro }.filter { it.second in from until from + WHILE_MS })
        }
        return Song(lines, starts, whiles, lengthMs)
    }

    private fun word(writing: Writing, random: Random): Word {
        val syllables = if (writing == Writing.LATIN) 1 + random.nextInt(3) else 2 + random.nextInt(3)
        val text = (1..syllables).joinToString("") {
            when (writing) {
                Writing.LATIN -> "${CONSONANTS[random.nextInt(CONSONANTS.length)]}${VOWELS[random.nextInt(VOWELS.length)]}"
                Writing.JAPANESE -> KANA[random.nextInt(KANA.length)].toString()
                // A Hangul syllable, from its first sound and its vowel.
                Writing.KOREAN -> (HANGUL + (random.nextInt(19) * 21 + random.nextInt(21)) * 28).toChar().toString()
                Writing.CHINESE -> (HAN + random.nextInt(HAN_COUNT)).toChar().toString()
            }
        }
        return Word(text, syllables)
    }

    private const val MISSED = 0.15
    private const val REST_MS = 1_500L
    private const val CONSONANTS = "bdfgklmnprstvz"
    private const val VOWELS = "aeiou"
    private const val KANA = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわ"
    private const val HANGUL = 0xAC00
    private const val HAN = 0x4E00
    private const val HAN_COUNT = 0x5000
}

/** The table `syncreplay.py synthetic` prints: each writing and pace, what's put where. */
fun main() {
    for (writing in SyntheticSinging.Writing.entries) {
        for (pace in SyntheticSinging.PACES) {
            val score = SyntheticSinging.score(writing, pace)
            println("${writing.name.lowercase().padEnd(9)} ${pace.toString().padStart(4)} ms  ${score.within.toString().padStart(4)} within  ${score.off.toString().padStart(4)} off  ${score.dark.toString().padStart(4)} dark")
        }
    }
}
