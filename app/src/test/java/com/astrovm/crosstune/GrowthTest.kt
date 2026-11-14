package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How [Growth] counts the work of a reading, and judges how it grows: run in the growthTests task, with the work-count agent. */
class GrowthTest {
    @Test
    fun onlyTheGrowthTestsCount() {
        assertEquals(true, Growth.COUNTING)
    }

    @Test
    fun aReaderThatLooksOnToTheEndFromEachPlaceIsOutOfStep() {
        val out = Growth.outOfStep(::abs, LENGTH, { it.take(4) }, ::squared)
        assertEquals(1, out.size)
        assertEquals(true, out.single().startsWith("abab "))
    }

    @Test
    fun aReaderSquaredOnSomeTextsIsOutOfStepOnThoseAlone() {
        val texts = { length: Int -> listOf("ab".repeat(length / 2), "ba".repeat(length / 2), "bb".repeat(length / 2)) }
        val out = Growth.outOfStep(texts, LENGTH, { it.take(4) }) { text -> if (text.startsWith("ba")) squared(text) else inStep(text) }
        assertEquals(listOf("baba"), out.map { it.substringBefore(' ') })
    }

    @Test
    fun aReaderInStepIsInStep() {
        val texts = { length: Int -> listOf("ab".repeat(length / 2), "ba".repeat(length / 2)) }
        assertEquals(emptyList<String>(), Growth.outOfStep(texts, LENGTH, { it }, ::inStep))
        assertEquals(emptyList<String>(), Growth.outOfStep(texts, LENGTH, { it }) { text -> text.count { it == 'a' } })
    }

    @Test
    fun theJdksOwnWorkCounts() {
        // Each the JDK's work, done again from each place to the end: a copy, a look for a letter, a pattern and a move.
        val copies = Growth.outOfStep(::abs, LENGTH, { it.take(4) }) { text -> text.indices.sumOf { text.substring(it).length } }
        val looks = Growth.outOfStep(::abs, LENGTH, { it.take(4) }) { text -> text.indices.sumOf { text.indexOf('c', it) } }
        val opens = { length: Int -> listOf("<".repeat(length)) }
        val pattern = Growth.outOfStep(opens, SHORT, { it.take(4) }) { text -> Regex("<[^>]*>").findAll(text).count() }
        // Each letter put first, which moves the rest along: an array copy, counted by what it moves.
        val moves = Growth.outOfStep(::abs, LENGTH, { it.take(4) }) { text -> StringBuilder().apply { text.forEach { insert(0, it) } }.length }
        // A look on text outside Latin-1, which the JDK keeps in a class of its own, StringUTF16.
        val wide = Growth.outOfStep({ length -> listOf("歌a".repeat(length / 2)) }, LENGTH, { it.take(4) }) { text -> text.indices.sumOf { text.indexOf('c', it) } }
        assertEquals(listOf(1, 1, 1, 1, 1), listOf(copies, looks, pattern, moves, wide).map { it.size })
        // The same work once along the text is in step.
        assertEquals(emptyList<String>(), Growth.outOfStep(::abs, LENGTH, { it }) { text -> text.substring(1).indexOf('c') })
        assertEquals(emptyList<String>(), Growth.outOfStep(opens, SHORT, { it }) { text -> Regex("<[^<>]*+>").findAll(text).count() })
        assertEquals(emptyList<String>(), Growth.outOfStep(::abs, LENGTH, { it }) { text -> StringBuilder().apply { text.forEach { append(it) } }.length })
    }

    @Test
    fun aReadingCountsTheSameEachTime() {
        val text = "ab".repeat(LENGTH / 2)
        val read = { page: String -> LyricsFinder.geniusBoxes(page).count() + Regex("b+").findAll(page).count() }
        // Once first, as Growth does, for the classes it loads and sets up the first time.
        read(text.drop(1))
        val counts = List(3) { Growth.stepsOf(text, read) }
        assertEquals("$counts", 1, counts.toSet().size)
        assertTrue("$counts", counts.first() > LENGTH)
    }

    @Test
    fun workBesideItOnOtherThreadsDoesNotCount() {
        val text = "ab".repeat(LENGTH / 2)
        val alone = Growth.stepsOf(text, ::inStep)
        val busy = Thread { repeat(BUSY) { inStep(text) } }
        busy.start()
        val beside = Growth.stepsOf(text, ::inStep)
        busy.join()
        assertEquals(alone, beside)
    }

    @Test
    fun uncountedEachTextIsReadOnceShorterAndNothingIsJudged() {
        // As in the unit tests: what the texts run counts for Kover, and nothing is judged.
        val texts = { length: Int -> listOf("ab".repeat(length / 2), "ba".repeat(length / 2)) }
        val read = mutableListOf<Int>()
        val out = Growth.outOfStep(texts, LENGTH, { it }, counting = false) { text -> read += text.length; squared(text) }
        assertEquals(emptyList<String>(), out)
        assertEquals(listOf(LENGTH / Growth.UNCOUNTED_SHORTER, LENGTH / Growth.UNCOUNTED_SHORTER), read)
    }

    @Test
    fun workThatIsCountedIsHeldToTheSameLine() {
        val texts = { length: Int -> listOf("a".repeat(length)) }
        val squared = Growth.outOfStepCounting(texts, LENGTH, { it.take(2) }) { it.length.toLong() * it.length }
        assertEquals(listOf("aa 16.0 times as much"), squared)
        assertEquals(emptyList<String>(), Growth.outOfStepCounting(texts, LENGTH, { it }) { it.length.toLong() })
    }

    @Test
    fun countedWorkWithASmallPartSquaredIsOutOfStep() {
        // In step, with a part squared a tenth the size of the rest at the shorter length: 5.1 times as much.
        val texts = { length: Int -> listOf("a".repeat(length)) }
        val mixed = Growth.outOfStepCounting(texts, MIXED, { it.take(2) }) { 100L * it.length + it.length.toLong() * it.length / 300 }
        assertEquals(listOf("aa 5.1 times as much"), mixed)
        // A count of in-step work with a constant part grows less than 4 times, and is in step.
        assertEquals(emptyList<String>(), Growth.outOfStepCounting(texts, MIXED, { it }) { 1_000L + it.length })
    }

    @Test
    fun textsOfEachLengthAreAsMany() {
        val texts = { length: Int -> List(length / LENGTH) { "a" } }
        val failed = runCatching { Growth.outOfStepCounting(texts, LENGTH, { it }) { 1L } }.exceptionOrNull()
        assertEquals("1 texts at $LENGTH, 4 at 4 times it", failed?.message)
    }

    private fun abs(length: Int) = listOf("ab".repeat(length / 2))

    /** Each letter against each letter after it: time squared. */
    private fun squared(text: String): Int {
        var same = 0
        for (i in text.indices) for (j in i until text.length) if (text[j] == text[i]) same++
        return same
    }

    /** Each letter a few times: in step. */
    private fun inStep(text: String) = (1..ROUNDS).sumOf { round -> text.count { it.code % round == 0 } }

    private companion object {
        const val LENGTH = 1_000
        const val SHORT = 500
        const val MIXED = 3_000
        const val ROUNDS = 4
        const val BUSY = 50
    }
}
