package com.astrovm.crosstune

/**
 * Each reader's time on the long titles that took longest, at three lengths, on the engine it runs on: on an emulator,
 * ICU, as on a phone. A time that grows four times as the title doubles is time squared. `baseline.py --long` runs it,
 * on main or on any commit, as LongTitlesTest can't: on the JVM the patterns are written out and run slower.
 */
object LongTitlesProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        println("engine=${System.getProperty("java.vm.name")} phoneRegex=$ICU")
        if (args.firstOrNull() == "matcher-input") {
            matcherInputs()
            return
        }
        val readers = listOf<Pair<String, (String) -> Any?>>(
            "musicVideo" to { t -> TitleProbes.musicVideo(t, "Channel") },
            "youtubeVideo" to { t -> TitleProbes.youtubeVideo(t, "Channel") },
            "playerSong" to { t -> TitleProbes.playerSong(t, "") },
            "bracketedSong" to { t -> TitleProbes.bracketedSong(t) },
            "coverOf" to { t -> TitleProbes.coverOf(t) },
            "bareTitle" to { t -> TitleProbes.bareTitle(t) },
            "withoutEditionTag" to { t -> TitleProbes.withoutEditionTag(t) },
            "withoutFeaturing" to { t -> TitleProbes.withoutFeaturing(t) },
            "chordWikiTitles" to { t -> TitleProbes.chordWikiTitles(t) },
            "outsideBrackets" to { t -> outsideBrackets(t) },
        )
        if (args.firstOrNull() == "instrumental") {
            // Whether a title of tags says it's an instrumental, by an artist named first and by one named in each tag.
            val says = listOf("Channel", "a").map { artist -> artist to { t: String -> TitleProbes.saysInstrumental(t, artist) } }
            for (piece in INSTRUMENTAL) {
                for (length in listOf(2_500, 5_000, 10_000)) {
                    val title = piece.first + piece.second.repeat(length / piece.second.length) + piece.third
                    says.forEach { (_, read) -> read(title.take(500)) }
                    println("'${piece.first}'+'${piece.second}'+'${piece.third}' $length " + says.joinToString(" ") { (artist, read) ->
                        val began = System.nanoTime(); read(title); "$artist=${(System.nanoTime() - began) / 1_000_000}"
                    })
                }
            }
            return
        }
        if (args.firstOrNull() == "all") {
            allInputs(readers)
            matcherInputs()
            return
        }
        for ((piece, end) in listOf(" - " to " | Spotify", " - Live" to " | Spotify", " HD" to "", " Lyrics" to "", "(" to ")")) {
            for (length in listOf(2_500, 5_000, 10_000)) {
                val title = piece.repeat(length / piece.length) + end
                readers.forEach { (_, read) -> read(title.take(500)) }
                println("'$piece'+'$end' $length " + readers.joinToString(" ") { (name, read) ->
                    val began = System.nanoTime(); read(title); "$name=${(System.nanoTime() - began) / 1_000_000}"
                })
            }
        }
    }

    private const val LONG = 10_000
    private const val SLOW_MS = 50

    /** LongTitlesTest's titles of an instrumental's tags: what starts them, what's repeated, and what ends them. */
    private val INSTRUMENTAL = listOf(
        Triple("Channel", " - Instrumental", ""), Triple("a", " - Instrumental & a", ""), Triple("a", " - Instrumental and a", ""),
        Triple("Song", " (Instrumental)", " ft. a"), Triple("Song", " ~Instrumental", " ft. a"), Triple("", " - ", ""),
    )

    /** Uses the guard's inputs, including the readers and shapes the old copied list missed. */
    private fun allInputs(readers: List<Pair<String, (String) -> Any?>>) {
        var cases = 0
        var calls = 0
        var longest = 0L
        var longestTotal = 0L
        var longestCpu = 0L
        var longestTotalCpu = 0L
        fun read(group: String, title: String, operations: List<Pair<String, (String) -> Any?>>) {
            var total = 0L
            var totalCpu = 0L
            for ((name, operation) in operations) {
                operation(title.take(500))
                val beforeCpu = android.os.Debug.threadCpuTimeNanos()
                val start = System.nanoTime()
                operation(title)
                val took = System.nanoTime() - start
                val cpu = android.os.Debug.threadCpuTimeNanos() - beforeCpu
                check(cpu >= 0) { "Thread CPU time is unavailable" }
                totalCpu += cpu
                longestCpu = maxOf(longestCpu, cpu)
                total += took
                longest = maxOf(longest, took)
                calls++
                if (took > SLOW_MS * 1_000_000L) println("$group case=$cases reader=$name length=${title.length} ns=$took cpu_ns=$cpu")
            }
            longestTotal = maxOf(longestTotal, total)
            longestTotalCpu = maxOf(longestTotalCpu, totalCpu)
            cases++
        }
        LongTitleInputs.titles(LONG).forEach { read("title", it, readers) }
        val says = listOf("Channel", "a").map { artist ->
            "instrumental:$artist" to { title: String -> TitleProbes.saysInstrumental(title, artist) }
        }
        LongTitleInputs.instrumentals(LONG).forEach { read("instrumental", it, says) }
        LongTitleInputs.comparisons(LONG).forEach { title ->
            read("comparison", title, listOf("wordsMatch" to { text -> SongNames.wordsMatch(text, text.replaceFirst("Hello", "Hello World")) }))
        }
        LongTitleInputs.shares(LONG).forEach { read("share", it, listOf("parse" to MusicLinks::parse)) }
        println("summary cases=$cases calls=$calls length=$LONG max_reader_ns=$longest max_case_total_ns=$longestTotal max_reader_cpu_ns=$longestCpu max_case_cpu_ns=$longestTotalCpu")
    }

    /** Reproduces native input-copy work that the JDK's matcher preparation omits. No input leaves the device. */
    private fun matcherInputs() {
        val pattern = java.util.regex.Pattern.compile("^a")
        for (mode in listOf("new", "reset", "pattern", "region", "reuse")) {
            for (length in listOf(2_500, 10_000)) {
                val text = "a".repeat(length)
                fun read() {
                    val matcher = pattern.matcher(text)
                    repeat(text.length) {
                        val prepared = when (mode) {
                            "new" -> pattern.matcher(text)
                            "reset" -> matcher.reset(text)
                            "pattern" -> matcher.usePattern(pattern)
                            "region" -> matcher.region(0, text.length)
                            else -> matcher
                        }
                        check(prepared.lookingAt())
                    }
                }
                repeat(3) { read() }
                val times = List(3) {
                    val start = System.nanoTime()
                    read()
                    System.nanoTime() - start
                }.sorted()
                println("mode=$mode length=$length median_ns=${times[1]}")
            }
        }
    }
}
