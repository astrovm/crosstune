// Runs the app's SungTiming on what was heard of each recording, with the words' times taken off,
// so `syncreplay.py untimed` can hold where it puts each line against when it's really sung.
// Plain Kotlin, compiled with the app's SungTiming.kt alone, in seconds.
//
// Each input file has a row per thing, tab-separated:
//   N <ms>               how long the recording is
//   L <text>             a line of the words, in order
//   S <from> <to>        a while heard, in song ms; the W rows after it are heard in it
//   W <ms> <text>        a word heard, where in the song it starts
// It writes FILE.placed beside it: after each while, "R <n>", then a row "<line> <ms>" for each line put in time.
package com.astrovm.crosstune

import java.io.File

fun main(args: Array<String>) {
    for (path in args) {
        val lines = mutableListOf<String>()
        val heard = mutableListOf<HeardStretch>()
        var lengthMs = 0L
        File(path).readLines().forEach { row ->
            val cells = row.split('\t')
            when (cells[0]) {
                "N" -> lengthMs = cells[1].toLong()
                "L" -> lines += cells.getOrElse(1) { "" }
                "S" -> heard += HeardStretch(cells[1].toLong(), cells[2].toLong(), emptyList())
                "W" -> heard[heard.lastIndex] = heard.last().let { it.copy(words = it.words + (cells[2] to cells[1].toLong())) }
            }
        }
        val out = StringBuilder()
        for (n in 1..heard.size) {
            out.append("R\t$n\n")
            SungTiming.linesMs(lines, heard.take(n), lengthMs).forEach { (line, ms) -> out.append("$line\t$ms\n") }
        }
        File("$path.placed").writeText(out.toString())
    }
}
