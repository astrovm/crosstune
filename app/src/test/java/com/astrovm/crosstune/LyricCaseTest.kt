package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricCaseTest {
    @Test fun neighborsMatchAcrossDifferentLinesAndMissingWords() {
        val casing = LyricCase(listOf("We walk\nWith Mira through the night\nAnd visit Elver by the sea"))
        assertEquals("We walk with Mira through the night\nThen visit Elver by the sea", casing.apply("We walk with mira through the night\nThen visit elver by the sea"))
    }

    @Test fun lineAndSentenceStartsAndConflictingCopiesDoNotSupplyCapitals() {
        val casing = LyricCase(listOf("We walk. With Mira through the night", "We walk. With mira through the night"))
        assertEquals("We walk with mira through the night", casing.apply("We walk with mira through the night"))
        assertEquals("We walk with Mira", LyricCase(listOf("We walk\nWith Mira")).apply("We walk with mira"))
        assertEquals("We walk with mira", LyricCase(listOf("WE WALK WITH MIRA THROUGH THE NIGHT")).apply("We walk with mira"))
        assertEquals("We walk with mira", LyricCase(emptyList()).apply("We walk with mira"))
        for (gap in listOf("\r", "\r\n", "\n", ". ", "! ", "? ", ": ")) {
            assertEquals("We walk with mira", LyricCase(listOf("We walk${gap}With mira")).apply("We walk with mira"))
        }
        assertEquals("", casing.apply(""))
    }

    @Test fun wordsAtTheEndAndUnicodeAndPunctuationKeepTheirPlaces() {
        val text = "We visit élver, then meet o’nera!"
        val result = LyricCase(listOf("We visit Élver, then meet O'Nera!")).apply(text)
        assertEquals("We visit Élver, then meet O’Nera!", result)
        assertEquals(text.length, result.length)
        assertEquals(text.lowercase(), result.lowercase())
        assertEquals("Mira", LyricCase(listOf("Mira")).apply("Mira"))
    }
}
