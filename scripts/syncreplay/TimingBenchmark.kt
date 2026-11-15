package com.astrovm.crosstune
import java.io.File
fun main(args: Array<String>) {
    for (path in args) {
        val lines = mutableListOf<String>()
        val heard = mutableListOf<HeardStretch>()
        var lengthMs = 0L
        File(path).forEachLine { row ->
            val cells = row.split('\t')
            when (cells[0]) {
                "N" -> lengthMs = cells[1].toLong()
                "L" -> lines += cells.getOrElse(1) { "" }
                "S" -> heard += HeardStretch(cells[1].toLong(), cells[2].toLong(), emptyList())
                "W" -> heard[heard.lastIndex] = heard.last().let { it.copy(words = it.words + (cells[2] to cells[1].toLong())) }
            }
        }
        SungTiming.linesMs(lines, heard, lengthMs)
        var placed = 0
        val times = List(3) {
            val start = System.nanoTime()
            placed = SungTiming.linesMs(lines, heard, lengthMs).size
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        println("${File(path).name} medianMs=${times[1]} maxMs=${times.last()} placed=$placed")
    }
}
