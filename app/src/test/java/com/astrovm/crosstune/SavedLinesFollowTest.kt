package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Where a line kept from a song's words is once its words change. */
class SavedLinesFollowTest {

    private val song = listOf("Night sky above", "Stars shine so bright", "", "Under the moon")

    @Test
    fun theSameWordsSpacedPunctuatedOrCasedOtherwiseAreTheSameLine() {
        assertEquals(1, lineNow("stars, shine  so BRIGHT!", null, song, null))
        // Written with China's kanji, it's the line written with Japan's.
        assertEquals(0, lineNow("真実の瞬间", null, listOf("真実の瞬間", "夜"), null))
    }

    @Test
    fun aLineChangedALittleIsTheLineMuchTheSame() {
        assertEquals(1, lineNow("Stars shine bright", null, song, null))
        assertEquals(3, lineNow("Under the full moon", null, song, null))
        // One quite unlike any line isn't any of them.
        assertEquals(-1, lineNow("Rain on the roof", null, song, null))
        assertEquals(-1, lineNow("...", null, song, null))
    }

    @Test
    fun twoLinesAsAlikeAreNeither() {
        assertEquals(-1, lineNow("Stars shine", null, listOf("Stars shine on", "Stars shine in"), null))
    }

    @Test
    fun aTimedLineIsTheOneSungAtTheSameMomentWhateverItsWords() {
        val times = listOf(0L, 1_000L, 20_000L, 30_000L)
        // Romaji written in Japanese: nothing alike but when it's sung.
        assertEquals(1, lineNow("hoshi ga hikaru", 1_200, listOf("夜に君は", "星が光る", "", "明日の歌"), times))
        // Not a line sung a while after, nor the blank between lines, nor when two are sung then.
        assertEquals(-1, lineNow("hoshi ga hikaru", 2_000, listOf("夜に君は", "星が光る", "", "明日の歌"), times))
        assertEquals(-1, lineNow("hoshi ga hikaru", 20_000, listOf("夜に君は", "星が光る", "", "明日の歌"), times))
        assertEquals(-1, lineNow("hoshi ga hikaru", 1_000, listOf("夜に君は", "星が光る", "僕も"), listOf(0L, 1_000L, 1_300L)))
        // Untimed, its time says nothing.
        assertEquals(-1, lineNow("hoshi ga hikaru", 1_000, listOf("夜に君は", "星が光る"), null))
    }

    @Test
    fun anyLineIsFoundAgainWrittenOtherwiseAndNothingIsEverOutOfTheWords() {
        val random = Random(248)
        val letters = "abcdefghijklmnopqrstuvwxyz"
        repeat(300) {
            val lines = List(random.nextInt(1, 12)) { List(random.nextInt(2, 6)) { String(CharArray(random.nextInt(2, 8)) { letters[random.nextInt(letters.length)] }) }.joinToString(" ") }.distinctBy { it.replace(" ", "") }
            val index = random.nextInt(lines.size)
            val respelled = lines[index].split(" ").joinToString(listOf(" ", "  ", ", ", "! ").random(random)) { if (random.nextBoolean()) it.uppercase() else it } + listOf("", "!", "...", "?").random(random)
            assertEquals(respelled, index, lineNow(respelled, null, lines, null))
            val any = String(CharArray(random.nextInt(0, 30)) { (letters + " ,!").let { all -> all[random.nextInt(all.length)] } })
            assertTrue(any, lineNow(any, random.nextLong(0, 60_000), lines, lines.indices.map { it * 5_000L }) in -1..lines.lastIndex)
        }
    }
}
