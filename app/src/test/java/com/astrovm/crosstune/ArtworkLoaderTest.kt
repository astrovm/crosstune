package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun aCoverKeptAsAFileLoadsOnlyFromWhereCoversAreKept() = runBlocking {
        val dir = createTempDirectory().toFile()
        val covers = File(dir, ARTWORK_DIR).apply { mkdirs() }
        val kept = File(covers, "playing-1.jpg").apply { writeBytes(FakeSpotify.png()) }
        val elsewhere = File(dir, "other.jpg").apply { writeBytes(FakeSpotify.png()) }
        val loader = ArtworkLoader(fake.client(), Dispatchers.Unconfined, covers)
        assertEquals(4, loader.load(kept.toURI().toString())!!.width)
        // Not even through a way around.
        assertNull(loader.load(elsewhere.toURI().toString()))
        assertNull(loader.load("file:${covers.path}/../other.jpg"))
        assertNull(loader.load(File(covers, "gone.jpg").toURI().toString()))
        assertEquals(emptyList<String>(), fake.requestedUrls)
        dir.deleteRecursively()
        Unit
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

    @Test
    fun aCoverFileIsOnlyReadWhereCoversAreKeptAndByAnAddressThatIsOne() = runBlocking {
        // With nowhere to keep covers, no file is one.
        assertNull(loader.load("file:/tmp/playing-1.jpg"))
        val dir = createTempDirectory().toFile()
        val withDir = ArtworkLoader(fake.client(), Dispatchers.Unconfined, dir)
        // Not a way to a file: it has spaces, or isn't a path from the root.
        assertNull(withDir.load("file:/tmp/a b.jpg"))
        assertNull(withDir.load("file:playing-1.jpg"))
        assertEquals(emptyList<String>(), fake.requestedUrls)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun aSongKeptOfflineKeepsItsCoverApartUntilLetGo() = runBlocking {
        fake.handler = { request -> FakeSpotify.image(request, FakeSpotify.png()) }
        val cached = createTempDirectory("artwork").toFile()
        val kept = createTempDirectory("kept").toFile()
        val loader = ArtworkLoader(fake.client(), Dispatchers.Unconfined, cacheDir = cached, keptDir = kept)
        // Already seen, it's copied, not downloaded again.
        loader.load(COVER)
        assertTrue(loader.keep(COVER))
        assertEquals(listOf(COVER), fake.requestedUrls)
        // Not seen yet, it's downloaded, and never pruned with the rest.
        assertTrue(loader.keep("https://img.example/other.jpg"))
        assertTrue(loader.keep("https://img.example/other.jpg"))
        assertEquals(2, fake.requestedUrls.size)
        assertEquals(kept.listFiles()!!.sumOf { it.length() }, loader.keptSize())
        // Android clearing the app's cache leaves it showing.
        cached.deleteRecursively()
        assertNotNull(ArtworkLoader(fake.client(), Dispatchers.Unconfined, cacheDir = cached, keptDir = kept).load(COVER))
        assertEquals(2, fake.requestedUrls.size)
        loader.letGo(COVER)
        loader.letGo("https://img.example/other.jpg")
        assertEquals(0L, loader.keptSize())
        // With nowhere to keep it, it isn't, and one that can't be had isn't either.
        assertFalse(this@ArtworkLoaderTest.loader.keep(COVER))
        fake.handler = { request -> FakeSpotify.image(request, FakeSpotify.png(), code = 404) }
        assertFalse(loader.keep("https://img.example/gone.jpg"))
        assertEquals(0L, ArtworkLoader(fake.client(), Dispatchers.Unconfined).keptSize())
        kept.deleteRecursively()
        Unit
    }

    @Test
    fun aSeenCoverThatCantBeCopiedIsDownloadedForKeeping() = runBlocking {
        fake.handler = { request -> FakeSpotify.image(request, FakeSpotify.png()) }
        val cached = createTempDirectory("artwork").toFile()
        val kept = createTempDirectory("kept").toFile()
        val loader = ArtworkLoader(fake.client(), Dispatchers.Unconfined, cacheDir = cached, keptDir = kept)
        loader.load(COVER)
        // Where the copy would go there's a folder with something in it, which can't be written over.
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(COVER.toByteArray()).joinToString("") { "%02x".format(it) }
        File(kept, name).apply { mkdirs() }.resolve("in-the-way").writeText("x")
        loader.keep(COVER)
        assertEquals(listOf(COVER, COVER), fake.requestedUrls)
        cached.deleteRecursively()
        kept.deleteRecursively()
        Unit
    }

    private companion object {
        const val COVER = "https://img.example/cover.jpg"
    }
}
