package com.astrovm.crosstune

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FakeSpotifyTest {
    @Test
    fun aHandlerReadsItsOwnRequestsBodyEvenWhenAnotherArrivesMeanwhile() {
        val fake = FakeSpotify()
        val postHandled = CountDownLatch(1)
        val getArrived = CountDownLatch(1)
        var seen: String? = null
        fake.handler = { request ->
            if (request.method == "POST") {
                postHandled.countDown()
                assertTrue(getArrived.await(5, TimeUnit.SECONDS))
                seen = FakeSpotify.bodyOf(request)
            } else {
                getArrived.countDown()
            }
            FakeSpotify.html(request, "")
        }
        val client = fake.client()
        val post = thread {
            client.newCall(Request.Builder().url("https://music.youtube.com/search").post("videos".toRequestBody()).build()).execute().close()
        }
        assertTrue(postHandled.await(5, TimeUnit.SECONDS))
        client.newCall(Request.Builder().url("https://itunes.apple.com/lookup").build()).execute().close()
        post.join(5_000)

        // The GET came in last, but the POST's handler still sees what the POST sent.
        assertEquals("", fake.requestBodies.last())
        assertEquals("videos", seen)
    }

    @Test
    fun eachRequestsBodyIsAtItsUrlsPlaceEvenWhenRequestsRunAtOnce() {
        val fake = FakeSpotify()
        val client = fake.client()
        val start = CountDownLatch(1)
        val threads = (0 until 8).map { t ->
            thread {
                start.await()
                repeat(200) { n ->
                    val id = "$t-$n"
                    client.newCall(Request.Builder().url("https://music.youtube.com/next?id=$id").post(id.toRequestBody()).build()).execute().close()
                }
            }
        }
        start.countDown()
        threads.forEach { it.join(30_000) }

        assertEquals(1_600, fake.requestedUrls.size)
        // A test finds a request by its URL, then reads its body at the same place.
        fake.requestedUrls.forEachIndexed { i, url -> assertEquals(url, url.substringAfter("id="), fake.requestBodies[i]) }
    }
}
