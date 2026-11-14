package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.util.Arrays
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.CRC32C
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import kotlin.concurrent.thread

/** How [Growth] counts the work of a reading, and judges how it grows: run in the growthTests task, with the work-count agent. */
class GrowthTest {
    @Test
    fun onlyTheGrowthTestsCount() {
        assertEquals(true, Growth.COUNTING)
    }

    @Test
    fun aReaderThatLooksOnToTheEndFromEachPlaceIsOutOfStep() {
        val out = Growth.outOfStep(::abs, LENGTH, { it.take(4) }, ::squared)
        assertEquals(1, out.size)
        assertEquals(true, out.single().startsWith("abab "))
    }

    @Test
    fun aReaderSquaredOnSomeTextsIsOutOfStepOnThoseAlone() {
        val texts = { length: Int -> listOf("ab".repeat(length / 2), "ba".repeat(length / 2), "bb".repeat(length / 2)) }
        val out = Growth.outOfStep(texts, LENGTH, { it.take(4) }) { text -> if (text.startsWith("ba")) squared(text) else inStep(text) }
        assertEquals(listOf("baba"), out.map { it.substringBefore(' ') })
    }

    @Test
    fun aReaderInStepIsInStep() {
        val texts = { length: Int -> listOf("ab".repeat(length / 2), "ba".repeat(length / 2)) }
        assertEquals(emptyList<String>(), Growth.outOfStep(texts, LENGTH, { it }, ::inStep))
        assertEquals(emptyList<String>(), Growth.outOfStep(texts, LENGTH, { it }) { text -> text.count { it == 'a' } })
    }

    @Test
    fun theJdksOwnWorkCounts() {
        // Each the JDK's work, done again from each place to the end: a copy, a look for a letter, a pattern and a move.
        val copies = Growth.outOfStep(::abs, LENGTH, { it.take(4) }) { text -> text.indices.sumOf { text.substring(it).length } }
        val looks = Growth.outOfStep(::abs, LENGTH, { it.take(4) }) { text -> text.indices.sumOf { text.indexOf('c', it) } }
        val opens = { length: Int -> listOf("<".repeat(length)) }
        val pattern = Growth.outOfStep(opens, SHORT, { it.take(4) }) { text -> Regex("<[^>]*>").findAll(text).count() }
        // Each letter put first, which moves the rest along: an array copy, counted by what it moves.
        val moves = Growth.outOfStep(::abs, LENGTH, { it.take(4) }) { text -> StringBuilder().apply { text.forEach { insert(0, it) } }.length }
        // A look on text outside Latin-1, which the JDK keeps in a class of its own, StringUTF16.
        val wide = Growth.outOfStep({ length -> listOf("歌a".repeat(length / 2)) }, LENGTH, { it.take(4) }) { text -> text.indices.sumOf { text.indexOf('c', it) } }
        assertEquals(listOf(1, 1, 1, 1, 1), listOf(copies, looks, pattern, moves, wide).map { it.size })
        // The same work once along the text is in step.
        assertEquals(emptyList<String>(), Growth.outOfStep(::abs, LENGTH, { it }) { text -> text.substring(1).indexOf('c') })
        assertEquals(emptyList<String>(), Growth.outOfStep(opens, SHORT, { it }) { text -> Regex("<[^<>]*+>").findAll(text).count() })
        assertEquals(emptyList<String>(), Growth.outOfStep(::abs, LENGTH, { it }) { text -> StringBuilder().apply { text.forEach { append(it) } }.length })
    }

    @Test
    fun aCopyOfTheWholeTextForEachLetterIsOutOfStep() {
        // The JVM fills a new array, clones one and copies one in code of its own, with no steps: the elements count.
        val copies = mapOf<String, (String) -> Any?>(
            "toList" to { text -> val letters = text.toList(); text.indices.sumOf { letters.toList().size } },
            "toMutableList" to { text -> val letters = text.toList(); text.indices.sumOf { letters.toMutableList().size } },
            "toTypedArray" to { text -> val letters = text.toList(); text.indices.sumOf { letters.toTypedArray().size } },
            "copyOf" to { text -> val letters = text.toCharArray(); text.indices.sumOf { letters.copyOf().size } },
            "clone" to { text -> val letters = text.toCharArray(); text.indices.sumOf { letters.clone().size } },
            "IntArray" to { text -> text.indices.sumOf { IntArray(text.length).size } },
            "ArrayList" to { text -> val letters = text.toList(); text.indices.sumOf { ArrayList(letters).size } },
            "toArray" to { text -> val letters = ArrayList(text.toList()); text.indices.sumOf { letters.toArray().size } },
            "Arrays.copyOf" to { text -> val letters = text.toList().toTypedArray(); text.indices.sumOf { Arrays.copyOf(letters, letters.size).size } },
            "CopyOnWriteArrayList.add" to { text -> CopyOnWriteArrayList<Char>().apply { text.forEach { add(it) } }.size },
            "String of Latin-1" to { text -> val bytes = text.toByteArray(Charsets.ISO_8859_1); text.indices.sumOf { String(bytes, Charsets.ISO_8859_1).length } },
        )
        assertEquals(copies.keys.toList(), outOfStep(copies))
    }

    @Test
    fun theJvmsOwnWorkOnTheWholeTextForEachLetterIsOutOfStep() {
        val own = mapOf<String, (String) -> Any?>(
            // Made by reflection, and as an array of arrays: what each level holds counts.
            "a new array by reflection" to { text -> text.indices.sumOf { (java.lang.reflect.Array.newInstance(String::class.java, text.length) as Array<*>).size } },
            "a grid" to { text -> text.indices.sumOf { Grids.of(text.length, 1).size } },
            // Copied by a class that doesn't count, into memory outside the heap, and kept once by the JVM.
            "a copy to a direct buffer" to { text ->
                val from = ByteBuffer.wrap(text.toByteArray())
                val to = ByteBuffer.allocateDirect(text.length)
                text.indices.sumOf {
                    to.clear()
                    to.put(from.duplicate()).position()
                }
            },
            "a string kept once" to { text -> text.indices.sumOf { text.intern().length } },
            // A loop in java.nio, which calls nothing else: every class counts, but those that run the JVM and the agent.
            "a buffer's hash" to { text -> val letters = CharBuffer.wrap(text.toCharArray()); text.indices.sumOf { letters.hashCode() } },
            // Checksums and zlib, in the JVM's own code: what goes in and comes out counts.
            "a checksum" to { text -> val bytes = text.toByteArray(); text.indices.sumOf { CRC32().apply { update(bytes) }.value.toInt() and 1 } },
            "a checksum in Java" to { text -> val bytes = text.toByteArray(); text.indices.sumOf { CRC32C().apply { update(bytes) }.value.toInt() and 1 } },
            "a squeeze" to { text ->
                val bytes = text.toByteArray()
                val squeezed = ByteArray(bytes.size + SQUEEZE_ROOM)
                text.indices.sumOf { Deflater().run { setInput(bytes); finish(); deflate(squeezed).also { end() } } }
            },
            "an unsqueeze" to { text ->
                val bytes = text.toByteArray()
                val squeezed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(bytes) } }.toByteArray()
                val unsqueezed = ByteArray(bytes.size)
                text.indices.sumOf { Inflater().run { setInput(squeezed); inflate(unsqueezed).also { end() } } }
            },
        )
        assertEquals(own.keys.toList(), outOfStep(own))
    }

    @Test
    fun aReadingThatHandsItsWorkToAnotherThreadFails() {
        // Work on another thread doesn't count: a reading that starts, wakes or tells another fails, by its text's name.
        val started = runCatching { Growth.outOfStep(::abs, LENGTH, { it.take(4) }) { text -> thread { squared(text) }.join() } }
        val pool = Executors.newSingleThreadExecutor()
        // The pool's thread is started before, and waits: the reading wakes it.
        pool.submit { }.get()
        val woken = runCatching { Growth.outOfStep(::abs, LENGTH, { it.take(4) }) { text -> pool.submit<Int> { squared(text) }.get() } }
        pool.shutdown()
        val told = runCatching { Growth.outOfStep(::abs, LENGTH, { it.take(4) }) { text -> Told.everyone(text) } }
        assertEquals(List(3) { IllegalStateException::class }, listOf(started, woken, told).map { it.exceptionOrNull()?.let { failed -> failed::class } })
        assertEquals(List(3) { "abab" }, listOf(started, woken, told).map { it.exceptionOrNull()?.message?.substringBefore(':') })
    }

    @Test
    fun aReadingThatSkipsLettersIsInStep() {
        // A pattern looks for a word by skipping as many letters as it has where they can't be it: fewer steps than letters.
        val word = Regex(Regex.escape("};</script>"))
        val start = "var ytInitialData = {"
        val texts = { length: Int -> listOf(start.repeat(length / start.length + 1)) }
        assertEquals(emptyList<String>(), Growth.outOfStep(texts, LENGTH, { it.take(4) }) { text -> word.find(text) })
        val longer = texts(Growth.TIMES * LENGTH).single()
        assertTrue(Growth.stepsOf(longer) { text: String -> word.find(text) } < longer.length)
    }

    @Test
    fun aReadingCountsTheSameEachTime() {
        val text = "ab".repeat(LENGTH / 2)
        val read = { page: String -> LyricsFinder.geniusBoxes(page).count() + Regex("b+").findAll(page).count() }
        // Once first, as Growth does, for the classes it loads and sets up the first time.
        read(text.drop(1))
        val counts = List(3) { Growth.stepsOf(text, read) }
        assertEquals("$counts", 1, counts.toSet().size)
        assertTrue("$counts", counts.first() > LENGTH)
    }

    @Test
    fun workBesideItOnOtherThreadsDoesNotCount() {
        val text = "ab".repeat(LENGTH / 2)
        val alone = Growth.stepsOf(text, ::inStep)
        val busy = Thread { repeat(BUSY) { inStep(text) } }
        busy.start()
        val beside = Growth.stepsOf(text, ::inStep)
        busy.join()
        assertEquals(alone, beside)
    }

    @Test
    fun uncountedEachTextIsReadOnceShorterAndNothingIsJudged() {
        // As in the unit tests: what the texts run counts for Kover, and nothing is judged.
        val texts = { length: Int -> listOf("ab".repeat(length / 2), "ba".repeat(length / 2)) }
        val read = mutableListOf<Int>()
        val out = Growth.outOfStep(texts, LENGTH, { it }, counting = false) { text -> read += text.length; squared(text) }
        assertEquals(emptyList<String>(), out)
        assertEquals(listOf(LENGTH / Growth.UNCOUNTED_SHORTER, LENGTH / Growth.UNCOUNTED_SHORTER), read)
    }

    @Test
    fun workThatIsCountedIsHeldToTheSameLine() {
        val texts = { length: Int -> listOf("a".repeat(length)) }
        val squared = Growth.outOfStepCounting(texts, LENGTH, { it.take(2) }) { it.length.toLong() * it.length }
        assertEquals(listOf("aa 16.0 times as much"), squared)
        assertEquals(emptyList<String>(), Growth.outOfStepCounting(texts, LENGTH, { it }) { it.length.toLong() })
    }

    @Test
    fun countedWorkWithASmallPartSquaredIsOutOfStep() {
        // In step, with a part squared a tenth the size of the rest at the shorter length: 5.1 times as much.
        val texts = { length: Int -> listOf("a".repeat(length)) }
        val mixed = Growth.outOfStepCounting(texts, MIXED, { it.take(2) }) { 100L * it.length + it.length.toLong() * it.length / 300 }
        assertEquals(listOf("aa 5.1 times as much"), mixed)
        // A count of in-step work with a constant part grows less than 4 times, and is in step.
        assertEquals(emptyList<String>(), Growth.outOfStepCounting(texts, MIXED, { it }) { 1_000L + it.length })
    }

    @Test
    fun textsOfEachLengthAreAsMany() {
        val texts = { length: Int -> List(length / LENGTH) { "a" } }
        val failed = runCatching { Growth.outOfStepCounting(texts, LENGTH, { it }) { 1L } }.exceptionOrNull()
        assertEquals("1 texts at $LENGTH, 4 at 4 times it", failed?.message)
    }

    private fun abs(length: Int) = listOf("ab".repeat(length / 2))

    /** Of [readers] by name, those out of step on [abs]: each name is its text's in `build/growth/TASK.tsv`. */
    private fun outOfStep(readers: Map<String, (String) -> Any?>) =
        readers.filter { (name, read) -> Growth.outOfStep(::abs, LENGTH, { name }, read).isNotEmpty() }.keys.toList()

    /** Each letter against each letter after it: time squared. */
    private fun squared(text: String): Int {
        var same = 0
        for (i in text.indices) for (j in i until text.length) if (text[j] == text[i]) same++
        return same
    }

    /** Each letter a few times: in step. */
    private fun inStep(text: String) = (1..ROUNDS).sumOf { round -> text.count { it.code % round == 0 } }

    private companion object {
        const val LENGTH = 1_000
        const val SHORT = 500
        const val MIXED = 3_000
        const val ROUNDS = 4
        const val BUSY = 50

        /** Room past the text for what zlib adds to text that doesn't squeeze. */
        const val SQUEEZE_ROOM = 64
    }
}
