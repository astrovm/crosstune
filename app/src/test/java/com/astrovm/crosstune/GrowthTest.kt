package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun aReaderInStepIsInStepAndOneTooQuickToTellIsToo() {
        val texts = { length: Int -> listOf("ab".repeat(length / 2), "ba".repeat(length / 2)) }
        assertEquals(emptyList<String>(), Growth.outOfStep(texts, LENGTH, { it }, ::inStep))
        assertEquals(emptyList<String>(), Growth.outOfStep(texts, LENGTH, { it }) { text -> text.count { it == 'a' } })
    }

    @Test
    fun aSlowFirstReadingIsNotOutOfStep() {
        // The first reading of the longer text does far more, as code not yet compiled does, and the others don't.
        val once = "ab".repeat(LENGTH / 2)
        val twice = once + once
        var slowed = false
        val slowOnce = { text: String ->
            if (text == twice && !slowed) {
                slowed = true
                inStep(twice.repeat(SLOWER))
            }
            inStep(text)
        }
        assertNull(Growth.outOfStep(once, twice, slowOnce))
        assertEquals(true, slowed)
        assertNotNull(Growth.outOfStep(once, twice, ::squared))
    }

    @Test
    fun textsOfEachLengthAreAsMany() {
        val texts = { length: Int -> List(length / LENGTH) { "a" } }
        val failed = runCatching { Growth.outOfStep(texts, LENGTH, { it }) { it } }.exceptionOrNull()
        assertEquals("1 texts at $LENGTH, 2 at twice it", failed?.message)
    }

    /** Each letter against each letter after it: time squared. */
    private fun squared(text: String): Int {
        var same = 0
        for (i in text.indices) for (j in i until text.length) if (text[j] == text[i]) same++
        return same
    }

    /** Each letter a few hundred times, some milliseconds in all: in step. */
    private fun inStep(text: String) = (1..ROUNDS).sumOf { round -> text.count { it.code % round == 0 } }

    private companion object {
        const val LENGTH = 6_000
        const val ROUNDS = 400
        const val SLOWER = 8
    }
}
