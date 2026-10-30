package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineAlphaTest {
    @Test
    fun theLineComingNextIsNearlyAsBrightAsTheOneSungAndBrighterThanTheRestToCome() {
        val sung = lineAlpha(0, waiting = false, coming = false)
        val coming = lineAlpha(1, waiting = false, coming = true)
        val later = lineAlpha(1, waiting = false, coming = false)
        assertTrue(coming < sung)
        assertTrue(coming > later)
        assertTrue(later > lineAlpha(-1, waiting = false, coming = false))
    }

    @Test
    fun beforeTheFirstLineOrWithNothingSayingWhereTheSongIsEveryLineIsAlike() {
        assertEquals(lineAlpha(null, waiting = false, coming = false), lineAlpha(1, waiting = true, coming = true))
        assertEquals(lineAlpha(null, waiting = false, coming = false), lineAlpha(-1, waiting = true, coming = false))
    }
}
