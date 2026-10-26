package com.astrovm.crosstune

import com.atilika.kuromoji.buffer.BufferEntry
import com.atilika.kuromoji.buffer.StringValueMapBuffer
import com.atilika.kuromoji.buffer.TokenInfoBuffer
import com.atilika.kuromoji.buffer.WordIdMap
import com.atilika.kuromoji.dict.CharacterDefinitions
import com.atilika.kuromoji.dict.ConnectionCosts
import com.atilika.kuromoji.dict.InsertedDictionary
import com.atilika.kuromoji.dict.TokenInfoDictionary
import com.atilika.kuromoji.dict.UnknownDictionary
import com.atilika.kuromoji.trie.DoubleArrayTrie
import com.atilika.kuromoji.unidic.Tokenizer
import com.atilika.kuromoji.util.ResourceResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.TreeMap
import java.util.zip.ZipFile

/**
 * UniDic, a Japanese dictionary that knows far more than the IPADIC shipped in the app: the rare
 * kanji lyrics love, 溢 or 踠, and words IPADIC breaks up, 微笑み. Too big to ship, it's fetched
 * the first time Japanese words show, only while [mayFetch], e.g. on Wi-Fi, as it's about 46 MB,
 * and kept in [dir]. Unpacked it's 150 MB, so it's read from its files where they are rather than
 * copied into memory.
 */
internal class UniDic(
    private val client: OkHttpClient,
    private val dir: File,
    private val mayFetch: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val url: String = URL,
    private val sha256: String = SHA256,
    /** The room it needs on the phone: the jar and its files unpacked, with some to spare. */
    private val room: Long = 250L * 1024 * 1024
) {
    private val files get() = File(dir, "files")

    /** One fetch at a time, as both write the same files. */
    private val fetching = Mutex()

    private var loaded: Tokenizer? = null

    /** The dictionary when it's on the phone, fetched first when [fetch] and it may be; null when it isn't here. */
    suspend fun tokenizer(fetch: Boolean): Tokenizer? = withContext(ioDispatcher) {
        fetching.withLock {
            loaded ?: if (files.isDirectory || (fetch && mayFetch() && fetched())) MappedUniDic(files).build().also { loaded = it } else null
        }
    }

    /** Fetches the jar Maven Central has UniDic in, and unpacks its files, when there's room for them. */
    private fun fetched(): Boolean {
        if (dir.apply { mkdirs() }.usableSpace < room) return false
        val jar = File(dir, "unidic.jar")
        val unpacking = File(dir, "files.part")
        try {
            if (!client.fetchChecked(url, sha256, jar)) return false
            unpacking.mkdirs()
            ZipFile(jar).use { zip ->
                for (entry in zip.entries()) {
                    // Only the dictionary's own files, each by its name alone, so nothing lands elsewhere.
                    val name = entry.name.removePrefix(FILES_IN_JAR)
                    if (name != entry.name && name.endsWith(".bin") && '/' !in name) {
                        zip.getInputStream(entry).use { input -> File(unpacking, name).outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            // A jar without all of them is no use.
            return NAMES.all { File(unpacking, it).isFile } && unpacking.renameTo(files)
        } catch (_: IOException) {
            return false
        } finally {
            jar.delete()
            unpacking.deleteRecursively()
        }
    }

    companion object {
        const val URL = "https://repo1.maven.org/maven2/com/atilika/kuromoji/kuromoji-unidic/0.9.0/kuromoji-unidic-0.9.0.jar"
        const val SHA256 = "f4ed14657ac41752e658cef40f98cfd1ac46772df7d037d4ed698f066930871b"
        private const val FILES_IN_JAR = "com/atilika/kuromoji/unidic/"
        private val NAMES = listOf(
            "characterDefinitions.bin", "connectionCosts.bin", "doubleArrayTrie.bin", "tokenInfoDictionary.bin",
            "tokenInfoFeaturesMap.bin", "tokenInfoPartOfSpeechMap.bin", "tokenInfoTargetMap.bin", "unknownDictionary.bin"
        )
    }
}

/**
 * UniDic's tokenizer with its files read from [dir] where they are: the big ones mapped rather than
 * copied into memory, which only takes in the parts a line needs. Each stands in for kuromoji's own,
 * which reads the same files whole.
 */
internal class MappedUniDic(private val dir: File) : Tokenizer.Builder() {
    init {
        resolver = ResourceResolver { File(dir, it).inputStream() }
    }

    override fun loadDictionaries() {
        doubleArrayTrie = MappedTrie(mapped(DoubleArrayTrie.DOUBLE_ARRAY_TRIE_FILENAME))
        connectionCosts = mapped(ConnectionCosts.CONNECTION_COSTS_FILENAME).let { ConnectionCosts(it.getInt(0), it.from(8).asShortBuffer()) }
        tokenInfoDictionary = MappedWords(dir)
        characterDefinitions = CharacterDefinitions.newInstance(resolver)
        unknownDictionary = UnknownDictionary.newInstance(resolver, characterDefinitions, totalFeatures)
        insertedDictionary = InsertedDictionary(totalFeatures)
    }

    private fun mapped(name: String) = map(File(dir, name))
}

private fun map(file: File): ByteBuffer = RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

/** What's in this from [offset] on, as a buffer of its own. */
private fun ByteBuffer.from(offset: Int): ByteBuffer = duplicate().apply { position(offset) }.slice()

/** The words the dictionary has, with what's known of each, in its four files. */
private class MappedWords(dir: File) : TokenInfoDictionary() {
    init {
        // Each but the parts of speech, a few hundred names, is a buffer of bytes after its size.
        tokenInfoBuffer = MappedEntries(map(File(dir, TOKEN_INFO_DICTIONARY_FILENAME)).from(4))
        stringValues = MappedStrings(map(File(dir, FEATURE_MAP_FILENAME)).from(4))
        posValues = StringValueMapBuffer(File(dir, POS_MAP_FILENAME).inputStream())
        wordIdMap = MappedWordIds(map(File(dir, TARGETMAP_FILENAME)))
    }
}

/**
 * Each word's costs, parts of speech and features, in entries of one size after a header of five
 * numbers, which the base class is given alone to read how big each entry is.
 */
private class MappedEntries(private val buffer: ByteBuffer) : TokenInfoBuffer(ByteArrayInputStream(ByteArray(4 + HEADER).also { buffer.duplicate().get(it, 4, HEADER); it[3] = HEADER.toByte() })) {
    private val costCount = buffer.getInt(8)
    private val partCount = buffer.getInt(12)
    private val featureCount = buffer.getInt(16)
    private val size = costCount * 2 + partCount + featureCount * 4

    private fun at(word: Int) = HEADER + word * size

    override fun lookupEntry(offset: Int) = BufferEntry().apply {
        val at = at(offset)
        tokenInfos = ShortArray(costCount) { buffer.getShort(at + it * 2) }
        posInfos = ByteArray(partCount) { buffer.get(at + costCount * 2 + it) }
        featureInfos = IntArray(featureCount) { buffer.getInt(at + costCount * 2 + partCount + it * 4) }
    }

    override fun lookupTokenInfo(offset: Int, i: Int) = buffer.getShort(at(offset) + i * 2).toInt()

    override fun lookupPartOfSpeechFeature(offset: Int, i: Int) = buffer.get(at(offset) + costCount * 2 + i).toInt() and 0xff

    override fun lookupFeature(offset: Int, i: Int) = buffer.getInt(at(offset) + costCount * 2 + partCount + (i - partCount) * 4)

    private companion object {
        const val HEADER = 20
    }
}

/** The words' features, readings and forms, each where an index of them says, as its length and UTF-8 bytes. */
private class MappedStrings(private val buffer: ByteBuffer) : StringValueMapBuffer(TreeMap()) {
    override fun get(i: Int): String {
        val at = buffer.getInt(4 + i * 4)
        val bytes = ByteArray(buffer.getShort(at).toInt())
        buffer.duplicate().apply { position(at + 2) }.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}

/** The words each entry of the trie stands for: an index into a list of runs, each its length and then its words. */
private class MappedWordIds(private val buffer: ByteBuffer) : WordIdMap(ByteArrayInputStream(ByteArray(8))) {
    private val runs = 4 + buffer.getInt(0) * 4 + 4

    override fun lookUp(sourceId: Int): IntArray {
        val run = buffer.getInt(4 + sourceId * 4)
        if (run == -1) return IntArray(0)
        return IntArray(buffer.getInt(runs + run * 4)) { buffer.getInt(runs + (run + 1 + it) * 4) }
    }
}

/**
 * The trie the dictionary's words are found in: a flag, its size and its tail's, then its base,
 * check and tail arrays. UniDic's is a compact one, the only kind read here.
 */
private class MappedTrie(buffer: ByteBuffer) : DoubleArrayTrie(true) {
    private val size = buffer.getInt(1)
    private val base = buffer.from(9).asIntBuffer()
    private val check = buffer.from(9 + size * 4).asIntBuffer()
    private val tail = buffer.from(9 + size * 8).asCharBuffer()

    override fun lookup(key: String, index: Int, j: Int): Int {
        var at = index
        var next = if (at != 0) base.get(at) else 1
        for (i in j until key.length) {
            val previous = at
            at = next + key[i].code
            if (at >= size) return -1
            next = base.get(at)
            if (next == 0 || check.get(at) != previous) return -1
            if (next >= TAIL_OFFSET) return inTail(next - TAIL_OFFSET, at, key.substring(i + 1))
        }
        return if (check.get(next + TERMINATING_CHARACTER.code) == at) at else 0
    }

    private fun inTail(from: Int, at: Int, rest: String): Int {
        if (rest.indices.any { rest[it] != tail.get(from + it) }) return -1
        return if (tail.get(from + rest.length) == TERMINATING_CHARACTER) at else 0
    }

    private companion object {
        const val TAIL_OFFSET = 100_000_000
    }
}
