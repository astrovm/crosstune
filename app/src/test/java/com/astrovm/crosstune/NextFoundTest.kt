package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Looking along a page once: what's found is the answer till a place past it is asked. */
class NextFoundTest {
    @Test
    fun whatsFoundIsTheAnswerTillAPlacePastItIsAsked() {
        val next = NextFound("a-b-", "-")
        assertEquals(listOf(1, 1, 3, 3, 4, 4), listOf(0, 1, 2, 3, 4, 4).map(next::from))
        // From 0, 2 and 4: at 1 and 3 it's where it was found, and at the end there's none to look for again.
        assertEquals(3, next.looks)
    }

    @Test
    fun caseIsKeptOrNot() {
        assertEquals(2, NextFound("abAB", "A").from(0))
        assertEquals(0, NextFound("abAB", "A", ignoreCase = true).from(0))
    }

    @Test
    fun aLookOfItsOwnSaysNoneAsIndexOfDoes() {
        val next = NextFound("ab") { -1 }
        assertEquals(2, next.from(0))
        assertEquals(1, NextFound("ab") { it }.from(1))
    }

    @Test
    fun noneIsNull() {
        assertNull((-1).orNone())
        assertEquals(0, 0.orNone())
        assertEquals(3, "abc-".indexOf('-').orNone())
    }

    @Test
    fun theFirstBoxTagFromAPlaceIsTheOneThereOrAfterIt() {
        val tags = BoxTags("<div>a</div><div>")
        assertEquals(listOf(0, 1, 1, 1, 2, 3), listOf(0, 1, 5, 6, 12, 13).map(tags::firstFrom))
    }

    @Test
    fun anIAfterALetterIsNoWordI() {
        assertEquals("Hi there, I said", inSentenceCase("HI THERE, I SAID"))
    }
}
