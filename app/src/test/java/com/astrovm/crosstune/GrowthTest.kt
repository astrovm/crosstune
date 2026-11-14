package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthTest {
    @Test
    fun aReaderThatLooksOnToTheEndFromEachPlaceIsOutOfStep() {
        val texts = { length: Int -> listOf("ab".repeat(length / 2)) }
        val out = Growth.outOfStep(texts, LENGTH, { it.take(4) }, ::squared)
        assertEquals(1, out.size)
        assertEquals(true, out.single().startsWith("abab "))
    }

    @Test
    fun aReaderInStepIsInStepHoweverQuick() {
        val texts = { length: Int -> listOf("ab".repeat(length / 2), "ba".repeat(length / 2)) }
        assertEquals(emptyList<String>(), Growth.outOfStep(texts, LENGTH, { it }, ::inStep))
        assertEquals(emptyList<String>(), Growth.outOfStep(texts, LENGTH, { it }) { text -> text.count { it == 'a' } })
    }

    @Test
    fun aSlowFirstReadingIsNotOutOfStep() {
        // The first reading of the longer text does far more, as code not yet compiled does, and the others don't.
        val once = "ab".repeat(LENGTH / 2)
        val longer = once.repeat(Growth.TIMES)
        var slowed = false
        val slowOnce = { text: String ->
            if (text == longer && !slowed) {
                slowed = true
                inStep(longer.repeat(SLOWER))
            }
            inStep(text)
        }
        val growth = Growth.growth(once, longer, slowOnce)
        assertTrue("$growth", growth <= Growth.MARGIN)
        assertEquals(true, slowed)
    }

    @Test
    fun aHiccupInEachReadingIsSpreadOverManyRoundsOfAQuickOne() {
        // A runner's hiccup when it turns to the longer text, as a cold cache, costs more than a quick reading itself.
        val once = "ab".repeat(SHORT / 2)
        val longer = once.repeat(Growth.TIMES)
        var last: String? = null
        val hiccupping = { text: String ->
            if (text == longer && last != longer) inStep(longer.repeat(HICCUP))
            last = text
            inStep(text)
        }
        val growth = Growth.growth(once, longer, hiccupping)
        assertTrue("$growth", growth <= Growth.MARGIN)
    }

    @Test
    fun aMachineBusyForAWholeMeasureIsMeasuredAgain() {
        // Work beside the test, as another test on the same core, slows each reading of the longer text 3 times until the
        // measure pauses. Then a pause like the collector's lands in the first reading of the longer text after each pause.
        val once = "ab".repeat(LENGTH / 2)
        val longer = once.repeat(Growth.TIMES)
        var busy = true
        var paused = false
        var last = System.nanoTime()
        val noisy = { text: String ->
            if (System.nanoTime() - last > Growth.PAUSE_MILLIS * 1_000_000 / 2) {
                busy = false
                paused = true
            }
            if (text == longer && busy) inStep(longer.repeat(BUSY - 1))
            if (text == longer && paused) {
                paused = false
                inStep(longer.repeat(COLLECTED))
            }
            inStep(text).also { last = System.nanoTime() }
        }
        val growth = Growth.growth(once, longer, noisy)
        assertTrue("$growth", growth <= Growth.MARGIN)
        assertEquals(false, busy)
    }

    @Test
    fun aReaderOutOfStepIsOutOfStepEachTimeItIsMeasured() {
        // Measured again after each pause, with no noise at all, it is still out of step.
        val once = "ab".repeat(LENGTH / 2)
        assertTrue(Growth.growth(once, once.repeat(Growth.TIMES), ::squared) > Growth.MARGIN)
    }

    @Test
    fun untimedEachShortTextIsReadOnceAndNothingIsJudged() {
        // As in the unit tests, beside other tests: what the texts run counts, and a timing can't fail at random.
        val texts = { length: Int -> listOf("ab".repeat(length / 2), "ba".repeat(length / 2)) }
        val read = mutableListOf<Int>()
        val out = Growth.outOfStep(texts, LENGTH, { it }, timing = false) { text -> read += text.length; squared(text) }
        assertEquals(emptyList<String>(), out)
        assertEquals(listOf(LENGTH, LENGTH), read)
    }

    @Test
    fun onlyTheGrowthTestsTime() {
        assertEquals(true, Growth.TIMING)
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
        val mixed = Growth.outOfStepCounting(texts, LENGTH, { it.take(2) }) { 100L * it.length + it.length.toLong() * it.length / 300 }
        assertEquals(listOf("aa 5.1 times as much"), mixed)
        // A count of in-step work with a constant part grows less than 4 times, and is in step.
        assertEquals(emptyList<String>(), Growth.outOfStepCounting(texts, LENGTH, { it }) { 1_000L + it.length })
    }

    @Test
    fun textsOfEachLengthAreAsMany() {
        val texts = { length: Int -> List(length / LENGTH) { "a" } }
        val failed = runCatching { Growth.outOfStep(texts, LENGTH, { it }) { it } }.exceptionOrNull()
        assertEquals("1 texts at $LENGTH, 4 at 4 times it", failed?.message)
    }

    /** Each letter against each letter after it: time squared. */
    private fun squared(text: String): Int {
        var same = 0
        for (i in text.indices) for (j in i until text.length) if (text[j] == text[i]) same++
        return same
    }

    /** Each letter a few hundred times: in step. */
    private fun inStep(text: String) = (1..ROUNDS).sumOf { round -> text.count { it.code % round == 0 } }

    private companion object {
        const val LENGTH = 3_000
        const val ROUNDS = 400
        const val SLOWER = 8
        const val SHORT = 20
        const val HICCUP = 3
        const val BUSY = 3
        const val COLLECTED = 20
    }
}
