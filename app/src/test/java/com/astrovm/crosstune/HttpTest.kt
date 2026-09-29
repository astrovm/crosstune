package com.astrovm.crosstune

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class HttpTest {

    @Test
    fun appClientLimitsHowLongACallTakes() {
        assertEquals(20_000, httpClient().callTimeoutMillis)
    }

    @Test
    fun bodiesUpToTheLimitAreRead() {
        assertArrayEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3).toResponseBody().bytesAtMost(3))
        assertEquals("héllo", "héllo".toByteArray(Charsets.ISO_8859_1).toResponseBody("text/html; charset=iso-8859-1".toMediaType()).stringAtMost())
        assertEquals("héllo", "héllo".toResponseBody().stringAtMost())
    }

    @Test
    fun biggerBodiesFailInsteadOfFillingMemory() {
        assertThrows(IOException::class.java) { byteArrayOf(1, 2, 3, 4).toResponseBody().bytesAtMost(3) }
    }
}
