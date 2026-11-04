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
        assertEquals("We walk with Mira through", LyricCase(listOf("We walk\nWith Mira through the night")).apply("We walk with mira through"))
        assertEquals("We walk with mira", LyricCase(listOf("WE WALK WITH MIRA THROUGH THE NIGHT")).apply("We walk with mira"))
        assertEquals("We walk with mira", LyricCase(emptyList()).apply("We walk with mira"))
        for (gap in listOf("\r", "\r\n", "\n", ". ", "! ", "? ", ": ", "; ", " (", " \"", " - ", " — ", "… ")) {
            assertEquals("We walk with mira", LyricCase(listOf("We walk${gap}With mira")).apply("We walk with mira"))
        }
        assertEquals("", casing.apply(""))
    }

    @Test fun capitalsUseTheOtherCopysLetterRatherThanAnEnglishUppercaseMapping() {
        assertEquals("We visit İnara beside the sea", LyricCase(listOf("We visit İnara beside the sea")).apply("We visit inara beside the sea"))
    }

    @Test fun wordsAtTheEndAndUnicodeAndPunctuationKeepTheirPlaces() {
        val text = "We visit élver, then meet o’nera!"
        val result = LyricCase(listOf("We visit Élver, then meet O'Nera!")).apply(text)
        assertEquals("We visit Élver, then meet O’Nera!", result)
        assertEquals(text.length, result.length)
        assertEquals(text.lowercase(), result.lowercase())
        assertEquals("Mira", LyricCase(listOf("Mira")).apply("Mira"))
    }

    @Test fun aCopyInTitleCaseOrCapitalsSaysNothing() {
        val text = "we walk with mira through the night"
        assertEquals(text, LyricCase(listOf("We Walk With Mira Through The Night")).apply(text))
        assertEquals(text, LyricCase(listOf("We Walk with Mira through the Night")).apply(text))
        assertEquals(text, LyricCase(listOf("WE WALK WITH MIRA THROUGH THE NIGHT")).apply(text))
    }

    @Test fun aSongFullOfCapitalNounsStillCounts() {
        val german = "wir gehen mit der Mira durch die Nacht und sehen den Mond"
        assertEquals("wir gehen mit der Mira durch die Nacht und sehen den Mond", LyricCase(listOf(german)).apply(german.lowercase()))
    }

    @Test fun commasAndHyphensLeaveANameInsideItsSentence() {
        assertEquals("Oh, Mira-Lou and more of the night", LyricCase(listOf("Oh, Mira-Lou and more of the night")).apply("Oh, mira-lou and more of the night"))
    }

    @Test fun eachLineStaysAsLongAsItWasWhateverItHolds() {
        val casing = LyricCase(listOf("We walk with Mira through the night"))
        assertEquals(listOf("We walk with", "Mira through", "", "the\rnight"), casing.apply(listOf("We walk with", "mira through", "", "the\rnight")))
        assertEquals(emptyList<String>(), casing.apply(emptyList<String>()))
    }

    @Test fun aNameSpelledOtherwiseTakesOnlyItsCapital() {
        val casing = LyricCase(listOf("Caught between the Scylla and Charybdis\nHypnotized by you"))
        assertEquals("Caught between the Scylla and Charibdes\nhypnotized by you", casing.apply("Caught between the scylla and charibdes\nhypnotized by you"))
    }

    @Test fun anotherWordBetweenTheSameNeighborsTakesNothing() {
        val text = "we walk to the river now"
        // Starting with another letter, or too many letters apart, it's another word.
        assertEquals(text, LyricCase(listOf("we walk to the Kiver now")).apply(text))
        assertEquals(text, LyricCase(listOf("we walk to the Rivulet now")).apply(text))
        // A word that short can't be told from another by a letter.
        assertEquals("I sat there and more", LyricCase(listOf("I Sam there and more")).apply("I sat there and more"))
        // Copies that disagree, on the word or on its capital, say nothing.
        assertEquals(text, LyricCase(listOf("we walk to the Rivers now", "we walk to the Riverr now")).apply(text))
        assertEquals(text, LyricCase(listOf("we walk to the River now", "we walk to the river now")).apply(text))
        // Nor does a copy with that very word in lower case, whatever else it has there.
        assertEquals(text, LyricCase(listOf("we walk to the rivers now")).apply(text))
    }

    @Test fun aNameSpelledTheSameNeedsNoOtherLetters() {
        assertEquals("We meet McBride here", LyricCase(listOf("We meet McBride here")).apply("We meet mcbride here"))
        // Spelled otherwise, a capital further in says nothing about where this copy has it.
        assertEquals("We meet Macbride here", LyricCase(listOf("We meet McBride here")).apply("We meet macbride here"))
    }
}
