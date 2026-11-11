package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every reading in readings-golden.tsv still reads the way it did when it was locked, kana and where they go.
 * The file is what `readingsaudit.py golden` wrote: each reading the audit found right, on the shortest
 * stretch of its line that the app reads the same way, alone and with any more of the line around it.
 * A row that fails is printed with what the app gave.
 */
@RunWith(RobolectricTestRunner::class)
class ReadingsGoldenTest {

    @Test
    fun everyLockedReadingStillReadsSo() {
        val text = javaClass.getResourceAsStream(GoldenReadings.RESOURCE)?.bufferedReader()?.use { it.readText() }
            ?: error("readings-golden.tsv is not in the test resources")
        val rows = GoldenReadings.rows(text)
        val started = System.nanoTime()
        // IPADIC alone first. UniDic is on only inside the block, as it is once the user has downloaded it.
        Readings.uniDic = null
        val broken = check(rows, "ipadic")
        TestUniDic.reading { broken += check(rows, "unidic") }
        val ms = (System.nanoTime() - started) / 1_000_000
        println("ReadingsGoldenTest $ms ms, ${rows.size} rows, ${broken.size} broken")
        if (broken.isNotEmpty()) {
            broken.forEach(::println)
            error(broken.joinToString("\n", prefix = "${broken.size} locked readings changed\n"))
        }
    }

    /** Each line is read once, then every row on it is checked. */
    private fun check(rows: List<GoldenReadings.Row>, via: String): MutableList<String> {
        val broken = mutableListOf<String>()
        for ((line, lineRows) in rows.filter { it.readBy(via) }.groupBy { it.line }) {
            val parts = Readings.of(listOf(line), Script.JAPANESE).single().parts
            for (row in lineRows) {
                val got = GoldenReadings.rubyAt(parts, line, row.word, row.index)
                if (got != row.ruby) {
                    broken += "line=${row.line} word=${row.word} index=${row.index ?: ""} dictionary=${row.dictionary} via=$via " +
                        "expected=${row.ruby} got=${got ?: parts.joinToString("|") { it.text }}"
                }
            }
        }
        return broken
    }

    @Test
    fun aFileCutShortOrWithoutItsCountFails() {
        val header = "# Locked readings.\n"
        val row = "夢を\t夢\t\tゆめ\t夢=ゆめ\tboth\n"
        assertEquals(1, GoldenReadings.rows("${header}# rows: 1\n$row").size)
        assertThrows(IllegalStateException::class.java) { GoldenReadings.rows("${header}# rows: 2\n$row") }
        assertThrows(IllegalStateException::class.java) { GoldenReadings.rows("${header}$row") }
        assertThrows(IllegalStateException::class.java) { GoldenReadings.rows("${header}# rows: 0\n") }
        assertThrows(IllegalStateException::class.java) { GoldenReadings.rows("${header}# rows: x\n$row") }
        assertThrows(IllegalStateException::class.java) { GoldenReadings.rows("${header}# rows: 1\n# rows: 1\n$row") }
    }

    @Test
    fun aRowThatIsNotWholeFails() {
        fun rows(row: String) = GoldenReadings.rows("# rows: 1\n$row\n")
        assertEquals(GoldenReadings.Row("夢を", "夢", 1, "ゆめ", "夢=ゆめ", "unidic"), rows("夢を\t夢\t1\tゆめ\t夢=ゆめ\tunidic").single())
        assertThrows(IllegalStateException::class.java) { rows("夢を\t夢\t\tゆめ\t夢=ゆめ") }
        assertThrows(IllegalStateException::class.java) { rows("夢を\t夢\t\tゆめ\t夢=ゆめ\tsudachi") }
        assertThrows(IllegalStateException::class.java) { rows("夢を\t夢\tone\tゆめ\t夢=ゆめ\tboth") }
        // The ruby has to spell the word and read as the kana, so a row edited by hand can't lock something else.
        assertThrows(IllegalStateException::class.java) { rows("夢を\t夢\t\tゆめ\t今=ゆめ\tboth") }
        assertThrows(IllegalStateException::class.java) { rows("夢を\t夢\t\tゆめ\t夢=いま\tboth") }
    }

    @Test
    fun theRubyIsTheWordsOwnParts() {
        val parts = listOf(Ruby("思", "おも"), Ruby("い"), Ruby("出", "で"), Ruby("の", null), Ruby("思い", "おもい"))
        val line = "思い出の思い"
        assertEquals("思=おも|い|出=で", GoldenReadings.rubyAt(parts, line, "思い出", null))
        assertEquals("思い=おもい", GoldenReadings.rubyAt(parts, line, "思い", 1))
        assertEquals("思=おも|い", GoldenReadings.rubyAt(parts, line, "思い", 0))
        // A part that starts before the word or runs past it, an index that isn't there or isn't needed, and parts that spell another line.
        assertNull(GoldenReadings.rubyAt(parts, line, "い", 1))
        assertNull(GoldenReadings.rubyAt(parts, line, "出の思", null))
        assertNull(GoldenReadings.rubyAt(parts, line, "思い", null))
        assertNull(GoldenReadings.rubyAt(parts, line, "思い", 2))
        assertNull(GoldenReadings.rubyAt(parts, line, "出", 0))
        assertNull(GoldenReadings.rubyAt(parts, "思い出の想い", "思い出", null))
    }

    /** A made-up reader: 日 is び after 曜, ひ alone; and ひ on each stretch of [wrong] too. */
    private fun reader(vararg wrong: String) = { stretch: String ->
        stretch.mapIndexed { at, c ->
            when {
                c != '日' -> Ruby(c.toString())
                stretch in wrong -> Ruby("日", "ひ")
                else -> Ruby("日", if (at > 0 && stretch[at - 1] == '曜') "び" else "ひ")
            }
        }
    }

    @Test
    fun aRowIsOnTheShortestStretchThatReadsSoWithAnyMoreOfTheLine() {
        assertEquals(1 to 3, GoldenReadings.Around("月曜日に", "日", 2, "日=び", reader()).shortestStable())
        assertEquals(2 to 3, GoldenReadings.Around("その日に", "日", 2, "日=ひ", reader()).shortestStable())
        assertNull(GoldenReadings.Around("その日に", "日", 2, "日=び", reader()).shortestStable())
        // 今曜日 reads ひ: a row on 曜日 would miss a change that breaks only that. 曜日だ holds, with the line around it too.
        val around = GoldenReadings.Around("今曜日だ", "日", 2, "日=び", reader("今曜日"))
        assertTrue(around.good(1, 3))
        assertFalse(around.good(0, 3))
        assertFalse(around.stable(1, 3))
        assertTrue(around.stable(1, 4))
        assertEquals(1 to 4, around.shortestStable())
        assertEquals("曜日だ" to null, around.stretch(1, 4))
    }

    @Test
    fun aReadingOnlyItsWholeLineHoldsCanFallBackToTheLongestStretchShortOfIt() {
        val line = GoldenReadings.Around("月曜日に", "日", 2, "日=び", reader("月曜日", "曜日に"))
        assertEquals(0 to 4, line.shortestStable())
        assertEquals(1 to 3, line.longestShortOfLine(emptySet()))
        // Never a stretch that is itself a lyric line.
        assertNull(line.longestShortOfLine(setOf("曜日")))
        // A word that is its whole line has nothing shorter.
        assertNull(GoldenReadings.Around("日", "日", 0, "日=ひ", reader()).longestShortOfLine(emptySet()))
    }

    @Test
    fun aStretchNamesWhichOccurrenceAndOfTwoAsShortTheFirst() {
        // 日 after 曜 is び only in a stretch that starts with 日. The stretch then has 日 twice, so it names which.
        val fromTheStart = { stretch: String ->
            reader()(stretch).map { part -> if (part.reading == "び" && stretch[0] != '日') Ruby("日", "ひ") else part }
        }
        val twice = GoldenReadings.Around("日曜日", "日", 2, "日=び", fromTheStart)
        assertEquals(0 to 3, twice.shortestStable())
        assertEquals("日曜日" to 1, twice.stretch(0, 3))
        // 日 is び next to any 曜, before or after it.
        val nextTo = { stretch: String ->
            stretch.mapIndexed { at, c ->
                if (c != '日') Ruby(c.toString()) else Ruby("日", if ("曜" in stretch.substring(maxOf(0, at - 1), minOf(stretch.length, at + 2))) "び" else "ひ")
            }
        }
        assertEquals(0 to 2, GoldenReadings.Around("曜日曜", "日", 1, "日=び", nextTo).shortestStable())
    }

    @Test
    fun aStretchThatSplitsALetterIsNeverReadNorARow() {
        val read = { stretch: String ->
            check(!stretch[0].isLowSurrogate()) { "half a letter read: $stretch" }
            reader()(stretch)
        }
        val around = GoldenReadings.Around("𠮷日", "日", 2, "日=ひ", read)
        assertTrue(around.good(1, 3))
        assertEquals(2 to 3, around.shortestStable())
        assertTrue(around.stable(0, 3))
    }

    @Test
    fun aStretchNeverStartsAsACommentOrSplitsALetterOrALine() {
        val line = "#a𠮷b c"
        assertEquals(false, GoldenReadings.fits(line, 0, 2))
        assertEquals(true, GoldenReadings.fits(line, 1, 4))
        assertEquals(false, GoldenReadings.fits(line, 3, 5))
        assertEquals(false, GoldenReadings.fits(line, 1, 3))
        assertEquals(false, GoldenReadings.fits(line, 4, 7))
        assertEquals(true, GoldenReadings.fits(line, 6, 7))
    }
}
