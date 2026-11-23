package com.astrovm.crosstune

import java.util.Base64

internal data class HeardWord(val text: String, val atMs: Long)
internal data class Sung(val startedAtMs: Long, val words: List<HeardWord>)

/** Runs the app's actual alignment on one saved hearing, without starting Android. */
fun main(args: Array<String>) {
    fun decode(text: String) = String(Base64.getDecoder().decode(text), Charsets.UTF_8)
    val rows = java.io.File(args[0]).readLines().map { it.split('\t') }
    val start = rows.first { it[0] == "N" }[1].toLong()
    val lines = rows.filter { it[0] == "L" }.map { LyricLine(it[1].toLong(), decode(it[2]), endMs = it.getOrNull(3)?.toLongOrNull()) }.toMutableList()
    rows.filter { it[0] == "W" }.groupBy { it[1].toInt() }.forEach { (index, words) ->
        lines[index] = lines[index].copy(words = words.map { TimedWord(it[2].toLong(), decode(it[3])) })
    }
    val heard = rows.filter { it[0] == "H" }.map { HeardWord(decode(it[2]), it[1].toLong()) }
    val sung = Sung(start, heard)
    val shift = SungAlignment.shiftMs(sung, { it }, lines)
    println("shiftMs=$shift")
    if (shift != null) {
        val placed = SungAlignment.linesMs(sung, { it }, lines, shift)
        println("linesMs=$placed")
        println("distinctLines=${placed.keys.filter { SungAlignment.canPlaceAlone(lines[it], lines) }}")
    }
}
