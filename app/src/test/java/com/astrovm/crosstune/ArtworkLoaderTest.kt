package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Native graphics so undecodable bytes fail the way they do on a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkLoaderTest {

    private val fake = FakeSpotify()
    private val loader = ArtworkLoader(fake.client(), Dispatchers.Unconfined)

    @Test
    fun decodesAndCachesAnImage() = runBlocking {
        fake.handler = { request -> FakeSpotify.image(request, FakeSpotify.png()) }

        val image = loader.load(COVER)
        assertNotNull(image)
        assertEquals(4, image!!.width)
        assertEquals(image, loader.load(COVER))
        assertEquals(listOf(COVER), fake.requestedUrls)
    }

    @Test
    fun failuresMeanNoImage() = runBlocking {
        fake.handler = { request -> FakeSpotify.image(request, FakeSpotify.png(), code = 404) }
        assertNull(loader.load(COVER))

        fake.handler = { request -> FakeSpotify.image(request, "not an image".toByteArray()) }
        assertNull(loader.load(COVER))

        fake.handler = { request -> FakeSpotify.brokenBody(request) }
        assertNull(loader.load(COVER))

        // Nothing is requested for URLs OkHttp can't fetch.
        fake.requestedUrls.clear()
        assertNull(loader.load("data:image/png;base64,AAAA"))
        assertEquals(emptyList<String>(), fake.requestedUrls)
    }

    private companion object {
        const val COVER = "https://img.example/cover.jpg"
    }
}
