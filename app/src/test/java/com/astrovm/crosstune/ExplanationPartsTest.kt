package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplanationPartsTest {

    @Test
    fun anExplanationIsSplitIntoItsParts() {
        assertEquals(listOf("What it means.", "A note."), explanationParts("What it means.\n---\nA note."))
        // Spaces or more dashes around the break, and a part left empty.
        assertEquals(listOf("Story.", "", "Refs."), explanationParts("Story.\n  ----  \n\n---\nRefs."))
        assertEquals(listOf("Story.", "Mood.", ""), explanationParts("Story.\n---\nMood.\n---\n"))
        // Without a break, it's all one part; dashes in a sentence aren't one.
        assertEquals(listOf("All of it -- in one."), explanationParts("All of it -- in one."))
    }

    @Test
    fun whileItsWrittenAHalfWrittenBreakIsntShown() {
        assertEquals(listOf("What it means."), explanationParts("What it means.\n-"))
        assertEquals(listOf("What it means."), explanationParts("What it means.\n--"))
        assertEquals(listOf("What it means.", ""), explanationParts("What it means.\n---"))
        assertEquals(listOf(""), explanationParts(""))
    }
}
