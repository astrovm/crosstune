package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SongNamesSmallWordsTest {

    @Test
    fun aTitleWrittenWithOtherSmallWordsIsTheSame() {
        assertTrue(SongNames.sameButSmallWords("Vahos de ayer", "Vahos del Ayer"))
        assertTrue(SongNames.sameButSmallWords("Canción para el Sol", "Cancion para la sol"))
        assertTrue(SongNames.sameButSmallWords("Vahos de ayer - Remastered 2010", "Vahos del Ayer"))
        // What already matched still does.
        assertTrue(SongNames.sameButSmallWords("River Road (Live)", "River Road"))
    }

    @Test
    fun anotherTitleIsntTheSame() {
        // A long word apart.
        assertFalse(SongNames.sameButSmallWords("Vahos de hoy", "Vahos del Ayer"))
        // A word more, or the words in another order.
        assertFalse(SongNames.sameButSmallWords("Vahos de un ayer", "Vahos del Ayer"))
        assertFalse(SongNames.sameButSmallWords("Ayer de vahos", "Vahos del Ayer"))
        // One long word says too little.
        assertFalse(SongNames.sameButSmallWords("El Bamba", "La Bamba"))
        // Numbers aren't small words.
        assertFalse(SongNames.sameButSmallWords("Vuelo 23 de Noche", "Vuelo 24 de Noche"))
        assertFalse(SongNames.sameButSmallWords("Song 2", "Song 3"))
        // Nothing in letters to compare.
        assertFalse(SongNames.sameButSmallWords("", ""))
    }

    @Test
    fun itsTheSameEitherWayRoundAndForWhatWordsMatchAlready() {
        val random = Random(7)
        val words = listOf("de", "del", "la", "el", "y", "2", "24", "vahos", "ayer", "noche", "river", "road", "corazón", "corazon")
        repeat(5_000) {
            val one = List(random.nextInt(0, 5)) { words.random(random) }.joinToString(" ")
            val other = List(random.nextInt(0, 5)) { words.random(random) }.joinToString(" ")
            assertEquals("$one / $other", SongNames.sameButSmallWords(one, other), SongNames.sameButSmallWords(other, one))
            if (SongNames.wordsMatch(one, other)) assertTrue("$one / $other", SongNames.sameButSmallWords(one, other))
            // Swapping a small word for another keeps it the same.
            val swapped = one.split(" ").joinToString(" ") { if (it == "de") "del" else it }
            if (one.split(" ").count { it.length > 3 } >= 2) assertTrue("$one / $swapped", SongNames.sameButSmallWords(one, swapped))
        }
    }
}
