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
}
