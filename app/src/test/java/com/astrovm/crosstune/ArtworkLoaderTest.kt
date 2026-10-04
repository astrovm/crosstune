package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun largeCoversAreSampledDown() = runBlocking {
        fake.handler = { request -> FakeSpotify.image(request, FakeSpotify.png(size = 1000)) }
        // Deezer's 1000px covers only ever show at ~100dp, so half size is plenty.
        assertEquals(500, loader.load(COVER)!!.width)
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

    @Test
    fun coversKeptOnDiskLoadWithoutTheNetworkAfterARestart() = runBlocking {
        val dir = createTempDirectory("artwork").toFile()
        fake.handler = { request -> FakeSpotify.image(request, FakeSpotify.png()) }
        assertNotNull(ArtworkLoader(fake.client(), Dispatchers.Unconfined, cacheDir = dir).load(COVER))

        // A new loader, like after the app restarts, finds it on disk.
        fake.requestedUrls.clear()
        assertNotNull(ArtworkLoader(fake.client(), Dispatchers.Unconfined, cacheDir = dir).load(COVER))
        assertEquals(emptyList<String>(), fake.requestedUrls)

        // Only the newest are kept.
        val many = ArtworkLoader(fake.client(), Dispatchers.Unconfined, cacheDir = dir)
        repeat(610) { many.load("https://img.example/$it.jpg") }
        assertTrue(dir.listFiles()!!.size <= 600)

        // A cover that can't be saved still shows.
        val notADirectory = File(dir, "file").apply { writeText("x") }
        assertNotNull(ArtworkLoader(fake.client(), Dispatchers.Unconfined, cacheDir = File(notADirectory, "artwork")).load(COVER))
    }

    private companion object {
        const val COVER = "https://img.example/cover.jpg"
    }
}
