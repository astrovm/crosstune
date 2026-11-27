package com.astrovm.crosstune.songcheck

import com.astrovm.crosstune.TestUniDic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.URL
import java.net.URLClassLoader

/** Reads an audit corpus with both versions in one JVM. Its input and timings stay in the audit cache. */
@RunWith(RobolectricTestRunner::class)
class ReadingsSpeed {
    @Test
    fun corpus() {
        val dir = File(System.getProperty("songcheck.dir") ?: error("Missing songcheck.dir"))
        val job = JSONObject(File(dir, "speed-job.json").readText())
        val lines = job.getJSONArray("lines").let { a -> (0 until a.length()).map(a::getString) }
        val main = File(job.getString("main"))
        val branch = checkNotNull(File(checkNotNull(System.getProperty("user.dir"))).canonicalFile.parentFile)
        Reader(main, checkNotNull(javaClass.classLoader)).use { before ->
            Reader(branch, checkNotNull(javaClass.classLoader)).use { after ->
                val results = JSONObject()
                for (unidic in listOf(false, true)) {
                    val dictionary = if (unidic) TestUniDic.tokenizer else null
                    before.setDictionary(dictionary)
                    after.setDictionary(dictionary)
                    val readers: List<(String) -> Any?> = listOf(before::read, after::read)
                    // Warm both versions on the same lines and alternate their order, as for the timed runs.
                    repeat(5) { round ->
                        val order = if (round % 2 == 0) listOf(0, 1) else listOf(1, 0)
                        order.forEach { side -> lines.forEach { readers[side](it) } }
                    }
                    val timings = Array(2) { mutableListOf<LongArray>() }
                    repeat(3) { round ->
                        val order = if (round % 2 == 0) listOf(0, 1) else listOf(1, 0)
                        for (side in order) {
                            timings[side] += LongArray(lines.size) { i ->
                                val start = System.nanoTime()
                                readers[side](lines[i])
                                System.nanoTime() - start
                            }
                        }
                    }
                    results.put(if (unidic) "unidic" else "ipadic", JSONArray(timings.map { runs ->
                        JSONArray(runs.map { run -> JSONArray(run.toList()) })
                    }))
                }
                File(dir, "speed-answer.json").writeText(results.toString())
                println("readings speed: ${lines.size} lines, two versions, two dictionaries, three timed runs")
            }
        }
    }

    private class Reader(root: File, parent: ClassLoader) : AutoCloseable {
        private val task = if (File(root, "app/src/play").isDirectory) "PlayDebug" else "Debug"
        private val variant = task.replaceFirstChar(Char::lowercaseChar)
        private val built = File(root, "app/build/intermediates")
        private val loader = MainLoader(listOf(
            File(built, "built_in_kotlinc/$variant/compile${task}Kotlin/classes"),
            File(built, "built_in_kotlinc/${variant}UnitTest/compile${task}UnitTestKotlin/classes"),
            File(built, "javac/$variant/compile${task}JavaWithJavac/classes"),
            File(root, "app/src/main/resources")
        ).map { it.toURI().toURL() }.toTypedArray(), parent)
        private val type = loader.loadClass("com.astrovm.crosstune.Readings")
        private val script = loader.loadClass("com.astrovm.crosstune.Script")
        private val instance = type.getField("INSTANCE").get(null)
        private val method = type.getMethod("of", List::class.java, script)
        private val japanese = script.getField("JAPANESE").get(null)
        private val dictionary = type.getMethod("setUniDic", com.atilika.kuromoji.unidic.Tokenizer::class.java)

        fun read(line: String): Any? = method.invoke(instance, listOf(line), japanese)
        fun setDictionary(tokenizer: com.atilika.kuromoji.unidic.Tokenizer?) { dictionary.invoke(instance, tokenizer) }
        override fun close() = loader.close()
    }

    private class MainLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith("com.astrovm.crosstune.")) return super.loadClass(name, resolve)
            synchronized(this) {
                val type = findLoadedClass(name) ?: findClass(name)
                if (resolve) resolveClass(type)
                return type
            }
        }

        override fun getResource(name: String): URL? =
            if (name.startsWith("com/astrovm/crosstune/")) findResource(name) else super.getResource(name)
    }
}
