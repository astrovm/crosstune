package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UniDicTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir get() = File(folder.root, "unidic")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun serving(bytes: ByteArray = jar, code: Int = 200) = FakeSpotify().apply { handler = { FakeSpotify.image(it, bytes, code) } }

    private fun uniDic(fake: FakeSpotify, mayFetch: Boolean = true, sha256: String = UniDic.SHA256, room: Long = 0) =
        UniDic(fake.client(), dir, { mayFetch }, Dispatchers.Unconfined, "https://maven.test/unidic.jar", sha256, room)

    @Test
    fun itsMavenCentralsJarOfTheVersionTheAppIsBuiltWith() {
        assertEquals("https://repo1.maven.org/maven2/com/atilika/kuromoji/kuromoji-unidic/0.9.0/kuromoji-unidic-0.9.0.jar", UniDic.URL)
        assertEquals(sha256(jar), UniDic.SHA256)
    }

    @Test
    fun itsFetchedOnceAndOnlyItsFilesAreKept() = runBlocking {
        val fake = serving()
        val tokenizer = uniDic(fake).tokenizer(fetch = true)!!
        assertEquals("コボシ", tokenizer.tokenize("溢した").first().pronunciation)
        assertEquals(listOf("https://maven.test/unidic.jar"), fake.requestedUrls)
        // The jar's gone once unpacked, and its classes, which the app has, were left in it.
        assertEquals(listOf("files"), dir.list()!!.toList())
        assertEquals(NAMES, File(dir, "files").list()!!.sorted())
        // Kept, it's read from there, without fetching it again, even where it couldn't be.
        assertNotNull(uniDic(fake, mayFetch = false).tokenizer(fetch = false))
        assertEquals(1, fake.requestedUrls.size)
        // Read once, it's the same one after.
        val kept = uniDic(fake, mayFetch = false)
        assertSame(kept.tokenizer(fetch = false), kept.tokenizer(fetch = true))
    }

    @Test
    fun itsOnlyFetchedWhenAskedForWhereItMayBeAndThereIsRoom() = runBlocking {
        val fake = serving()
        assertNull(uniDic(fake).tokenizer(fetch = false))
        assertNull(uniDic(fake, mayFetch = false).tokenizer(fetch = true))
        assertNull(uniDic(fake, room = Long.MAX_VALUE).tokenizer(fetch = true))
        assertEquals(emptyList<String>(), fake.requestedUrls)
    }

    @Test
    fun oneNotAsTriedOrNotThereIsntKept() = runBlocking {
        assertNull(uniDic(serving(), sha256 = "0".repeat(64)).tokenizer(fetch = true))
        assertNull(uniDic(serving(code = 404)).tokenizer(fetch = true))
        assertNull(uniDic(FakeSpotify().apply { handler = { FakeSpotify.brokenBody(it) } }).tokenizer(fetch = true))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun aJarWithoutTheDictionarysFilesWhereTheyBelongIsNoUse() = runBlocking {
        // Each name it has, but none where UniDic keeps them; one that would land outside, and one deeper.
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                (NAMES + "com/atilika/kuromoji/unidic/../../outside.bin" + "com/atilika/kuromoji/unidic/deeper/connectionCosts.bin").forEach {
                    zip.putNextEntry(ZipEntry(it))
                    zip.write(1)
                }
            }
        }.toByteArray()
        assertNull(uniDic(serving(bytes), sha256 = sha256(bytes)).tokenizer(fetch = true))
        assertFalse(File(folder.root, "outside.bin").exists())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun readFromItsFilesWhereTheyAreItReadsAsKuromojiReadingThemWhole() {
        val own = com.atilika.kuromoji.unidic.Tokenizer()
        // Rare kanji, words, kana, Latin letters, digits, marks, an emoji, and a kanji neither knows.
        val lines = listOf("うつむいて君が溢した 儚くなまぬるい涙", "微笑みをくれたのは誰？", "Yeah-yeah, 2人で 400円 🎵 龘", "ザッツ！超常", "")
        for (line in lines) {
            assertEquals(own.tokenize(line).map { "${it.position} ${it.surface} ${it.allFeatures}" }, TestUniDic.tokenizer.tokenize(line).map { "${it.position} ${it.surface} ${it.allFeatures}" })
        }
    }

    private companion object {
        val jar: ByteArray by lazy { TestUniDic.jar.readBytes() }
        val NAMES = listOf(
            "characterDefinitions.bin", "connectionCosts.bin", "doubleArrayTrie.bin", "tokenInfoDictionary.bin",
            "tokenInfoFeaturesMap.bin", "tokenInfoPartOfSpeechMap.bin", "tokenInfoTargetMap.bin", "unknownDictionary.bin"
        )
    }
}
