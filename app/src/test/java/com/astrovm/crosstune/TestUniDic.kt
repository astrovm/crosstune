package com.astrovm.crosstune

import java.io.File
import java.util.zip.ZipFile

/**
 * UniDic as the app gets it, from the jar the tests have it in: the jar, as Maven Central serves
 * it, and its files unpacked once, read as the app reads them.
 */
internal object TestUniDic {
    val jar: File = File(
        TestUniDic::class.java.classLoader!!.getResource("com/atilika/kuromoji/unidic/connectionCosts.bin")!!.path.removePrefix("file:").substringBefore("!")
    )

    /** Unpacked once for all the tests run here, which may each have it loaded again. */
    val files: File by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "crosstune-unidic-${ProcessHandle.current().pid()}")
        if (!dir.isDirectory) {
            val unpacking = File(dir.path + ".part").apply { deleteRecursively(); mkdirs() }
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { it.name.startsWith("com/atilika/kuromoji/unidic/") && it.name.endsWith(".bin") }.forEach { entry ->
                    zip.getInputStream(entry).use { input -> File(unpacking, entry.name.substringAfterLast('/')).outputStream().use { input.copyTo(it) } }
                }
            }
            unpacking.renameTo(dir)
            Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
        }
        dir
    }

    val tokenizer by lazy { MappedUniDic(files).build() }

    /** Readings worked out with UniDic too, as once it's on the phone. */
    fun <T> reading(block: () -> T): T {
        Readings.uniDic = tokenizer
        return try {
            block()
        } finally {
            Readings.uniDic = null
        }
    }
}
