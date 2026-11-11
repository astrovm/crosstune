package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every reading in readings-golden.tsv still reads the way it did when it was locked, kana and where they go.
 * The file is what `readingsaudit.py golden` wrote: each reading the audit found right, on the shortest
 * stretch of its line that the app reads the same way. A row that fails is printed with what the app gave.
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

    @Test
    fun theShortestStretchThatStillReadsSoIsKept() {
        // A made-up reader: 日 is び after 曜, ひ alone.
        val read = { stretch: String ->
            stretch.mapIndexed { at, c ->
                when {
                    c == '日' -> Ruby("日", if (at > 0 && stretch[at - 1] == '曜') "び" else "ひ")
                    else -> Ruby(c.toString())
                }
            }
        }
        assertEquals("曜日" to null, GoldenReadings.shortest("月曜日に", "日", 2, "日=び", read))
        assertEquals("日" to null, GoldenReadings.shortest("その日に", "日", 2, "日=ひ", read))
        assertNull(GoldenReadings.shortest("その日に", "日", 2, "日=び", read))
        // Another made-up reader: 日 after 曜 is び only in a stretch that starts with 日. The stretch then has 日 twice, so it names which.
        val fromTheStart = { stretch: String ->
            read(stretch).map { part -> if (part.reading == "び" && stretch[0] != '日') Ruby("日", "ひ") else part }
        }
        assertEquals("日曜日" to 1, GoldenReadings.shortest("日曜日", "日", 2, "日=び", fromTheStart))
        // Of two as short, the one that starts first: here 日 is び next to any 曜, before or after it.
        val nextTo = { stretch: String ->
            stretch.mapIndexed { at, c ->
                if (c != '日') Ruby(c.toString()) else Ruby("日", if ("曜" in stretch.substring(maxOf(0, at - 1), minOf(stretch.length, at + 2))) "び" else "ひ")
            }
        }
        assertEquals("曜日" to null, GoldenReadings.shortest("曜日曜", "日", 1, "日=び", nextTo))
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
