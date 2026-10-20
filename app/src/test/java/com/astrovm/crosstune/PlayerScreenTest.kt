package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerScreenTest {

    @Test
    fun timesReadAsAClockDoes() {
        assertEquals("0:00", clockText(0))
        assertEquals("0:00", clockText(-500))
        assertEquals("0:09", clockText(9_999))
        assertEquals("3:07", clockText(187_000))
        assertEquals("59:59", clockText(3_599_000))
        assertEquals("1:02:07", clockText(3_727_000))
    }
}
