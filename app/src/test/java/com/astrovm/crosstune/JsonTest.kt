package com.astrovm.crosstune

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Runs under Robolectric for the real org.json implementation. [answeredOr] lives in Http.kt but needs it too. */
@RunWith(RobolectricTestRunner::class)
class JsonTest {

    @Test
    fun aListsObjectsAreReadAndAnythingElseInItIsSkipped() {
        assertEquals(listOf("a", "b"), JSONArray("""[{"n":"a"}, 1, "x", null, {"n":"b"}]""").objects().map { it.getString("n") })
        assertEquals(emptyList<Any>(), (null as JSONArray?).objects())
    }

    @Test
    fun aListsItemsAreReadAsText() {
        assertEquals(listOf("a", "1", "true"), JSONArray("""["a", 1, true]""").strings())
        assertEquals(emptyList<String>(), (null as JSONArray?).strings())
    }

    @Test
    fun aLookupFallsBackOnlyWhenTheNetworkFailsOrTheAnswerIsntJson() {
        assertEquals("found", answeredOr("none") { "found" })
        assertEquals("none", answeredOr("none") { throw IOException("offline") })
        assertEquals("none", answeredOr("none") { JSONObject("not json").toString() })
        // A lookup that's cancelled still stops, and any other error still shows.
        assertThrows(CancellationException::class.java) { answeredOr("none") { throw CancellationException("gone") } }
        assertThrows(IllegalStateException::class.java) { answeredOr("none") { error("a bug") } }
    }
}
