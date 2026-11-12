package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Words found with no timing put in time by what's heard sung, a while at a time. Made-up words throughout. */
class SungTimingTest {

    private val lines = listOf(
        "Morning comes over the hill",
        "Coffee steaming by the window",
        "Paper boats along the gutter",
        "We sing the river home tonight",
        "Carry every lantern down",
        "Bicycles rattle past the bakery",
        "Pigeons argue on the station roof",
        "We sing the river home tonight",
        "Carry every lantern down"
    )

    /** [text] sung from [startMs], each word heard where the speech model has it: a little late, at an even pace. */
    private fun sungAt(startMs: Long, text: String): List<Pair<String, Long>> {
        var before = 0
        return text.split(' ').map { word ->
            (word to startMs + SungTiming.HEARD_LATE_MS + before * SungTiming.LETTER_MS).also { before += SpokenWords.pieces(word).sumOf { SpokenWords.sungLetters(it) } }
        }
    }

    private fun heard(fromMs: Long, vararg sung: List<Pair<String, Long>>) = HeardStretch(fromMs, fromMs + 20_000, sung.toList().flatten())

    /** [text] sung from [startMs], heard as [heardAs], a word for each word. */
    private fun heardAs(startMs: Long, text: String, heardAs: String) = sungAt(startMs, text).zip(heardAs.split(' ')) { (_, ms), word -> word to ms }

    @Test
    fun aLineHeardStartsWhereItsSung() {
        val placed = SungTiming.linesMs(lines, listOf(heard(20_000, sungAt(21_000, lines[1]), sungAt(25_000, lines[2]))), 120_000)
        assertEquals(mapOf(1 to 21_000L, 2 to 25_000L), placed)
    }

    @Test
    fun aChorusIsTimedWhereItsSungThisTime() {
        // The chorus is sung twice; heard after the verse between, it's the second time.
        val placed = SungTiming.linesMs(
            lines, listOf(heard(60_000, sungAt(61_000, lines[5]), sungAt(65_000, lines[6]), sungAt(70_000, lines[7]), sungAt(74_000, lines[8]))), 100_000
        )
        assertEquals(mapOf(5 to 61_000L, 6 to 65_000L, 7 to 70_000L, 8 to 74_000L), placed)
    }

    @Test
    fun soundsTheModelTellsOfArentWords() {
        val sounds = listOf("[Music]" to 20_500L, "(upbeat" to 20_700L, "music)" to 20_800L, "♪" to 20_900L)
        val placed = SungTiming.linesMs(lines, listOf(heard(20_000, sounds, sungAt(22_000, lines[1]))), 120_000)
        assertEquals(mapOf(1 to 22_000L), placed)
        // Right before a line, they take none of its words.
        val two = listOf("Amber lanterns glow", "Silver rivers bend")
        val soundsBefore = listOf(listOf("[Music]" to 10_400L), sungAt(10_000, two[0]), listOf("(Music)" to 20_200L), sungAt(20_000, two[1]))
        assertEquals(mapOf(0 to 10_000L, 1 to 20_000L), SungTiming.linesMs(two, listOf(heard(9_000, *soundsBefore.toTypedArray())), 60_000))
    }

    @Test
    fun aLineMisheardHereAndThereIsStillFound() {
        // "Morning" heard as "Mornin", "uh" heard that isn't sung, and "the" not heard at all.
        val at = SungTiming.HEARD_LATE_MS + 10_000
        val misheard = listOf("Mornin" to at, "comes" to at + 7 * SungTiming.LETTER_MS, "uh" to at + 10 * SungTiming.LETTER_MS, "over" to at + 12 * SungTiming.LETTER_MS, "hill" to at + 19 * SungTiming.LETTER_MS)
        assertEquals(mapOf(0 to 10_000L), SungTiming.linesMs(lines, listOf(heard(9_000, misheard)), 120_000))
        // Most words a letter or so off.
        val offByALetter = heardAs(30_000, lines[1], "Coffe steamin by da windo")
        assertEquals(mapOf(1 to 30_000L), SungTiming.linesMs(lines, listOf(heard(29_000, offByALetter)), 120_000))
        // Words as long but with no letter where the line's has it aren't the line's.
        val unlike = heardAs(30_000, lines[4], "bloom sugar pitched mist")
        assertEquals(emptyMap<Int, Long>(), SungTiming.linesMs(lines, listOf(heard(29_000, unlike)), 120_000))
    }

    @Test
    fun wordsHalfHeardAreJustEnoughNearWhereTheLineIsLikelySung() {
        // The letters before put the second line halfway through the song; within a quarter of the song of there is near.
        val words = listOf("Bicycles rattle along bakery", "Oh my we go on", "Lanterns glowing")
        fun timedAt(startMs: Long, heard: String) =
            SungTiming.linesMs(words, listOf(HeardStretch(startMs - 1_000, startMs + 19_000, heardAs(startMs, words[1], heard))), 100_000)
        // Each of five words heard half right is just enough.
        assertEquals(mapOf(1 to 30_000L), timedAt(30_000, "ah me wo ga an"))
        // Four aren't.
        assertEquals(emptyMap<Int, Long>(), timedAt(30_000, "ah me wo ga"))
        // Nor are five further off.
        assertEquals(emptyMap<Int, Long>(), timedAt(20_000, "ah me wo ga an"))
    }

    @Test
    fun wordsInManyLinesOrOfOneLetterSayLess() {
        // Each line is first in its words, so it's likely sung near the start; halfway through the song is far off.
        fun timedAt(startMs: Long, words: List<String>, heard: String) =
            SungTiming.linesMs(words, listOf(HeardStretch(startMs - 1_000, startMs + 19_000, heardAs(startMs, words[0], heard))), 100_000)
        // Heard clearly, a line is sure enough even far off.
        assertEquals(mapOf(0 to 70_000L), timedAt(70_000, listOf("Amber lanterns glow", "Silver rivers bend"), "Amber lanterns glow"))
        // Three words half heard and one of two letters heard clearly are enough far off.
        assertEquals(mapOf(0 to 60_000L), timedAt(60_000, listOf("Oh my we go", "Lanterns glowing"), "ah me wo go"))
        // With a word of one letter instead, they aren't, though near the start they are.
        val oneLetter = listOf("Oh my we a", "Lanterns glowing")
        assertEquals(emptyMap<Int, Long>(), timedAt(60_000, oneLetter, "ah me wo a"))
        assertEquals(mapOf(0 to 30_000L), timedAt(30_000, oneLetter, "ah me wo a"))
        // Nor with a word in four lines.
        val common = listOf("Oh my we the", "The lanterns", "The rivers", "The window")
        assertEquals(emptyMap<Int, Long>(), timedAt(60_000, common, "ah me wo the"))
        assertEquals(mapOf(0 to 30_000L), timedAt(30_000, common, "ah me wo the"))
    }

    @Test
    fun wordsHeardBeforeOnesUnlikeTheLineDontMoveIt() {
        // Half heard and then a word unlike the line's, the first words say where it starts apart from the last.
        val one = listOf("Oh my we go on silver carry every lantern")
        val first = heardAs(10_000, one[0], "ah me wo ga an plum")
        val last = sungAt(13_000, one[0]).drop(6)
        assertEquals(mapOf(0 to 13_000L), SungTiming.linesMs(one, listOf(heard(9_000, first, last)), 60_000))
    }

    @Test
    fun theEarliestWordSaysWhereALineStarts() {
        // Each word heard says where its line starts by the letters before it: here 10 s, 12 s, 12.2 s and 12.1 s.
        val one = listOf("Carry every lantern down")
        val late = SungTiming.HEARD_LATE_MS
        val heardSo = listOf(
            "Carry" to 10_000L + late, "every" to 12_000L + late + 5 * SungTiming.LETTER_MS,
            "lantern" to 12_200L + late + 10 * SungTiming.LETTER_MS, "down" to 12_100L + late + 17 * SungTiming.LETTER_MS
        )
        // The last three agree, but a line held or with a rest in it is sung slower than its letters say, so the first says it.
        assertEquals(mapOf(0 to 10_000L), SungTiming.linesMs(one, listOf(heard(9_000, heardSo)), 60_000))
        // Without the first word, the second says it.
        assertEquals(mapOf(0 to 12_000L), SungTiming.linesMs(one, listOf(heard(9_000, heardSo.drop(1))), 60_000))
        // Any word can be the earliest, the last too: here it says 11.8 s, by its 17 letters before.
        val lastEarliest = heardSo.drop(1).dropLast(1) + ("down" to 11_800L + late + 17 * SungTiming.LETTER_MS)
        assertEquals(mapOf(0 to 11_800L), SungTiming.linesMs(one, listOf(heard(9_000, lastEarliest)), 60_000))
    }

    @Test
    fun aWordHeardOnceCountsForOneLine() {
        // Sung twice with a rest between, the "home" before the rest heard once: the end of the first time or the start of the second, not both.
        val once = listOf("Home is where we go home")
        val again = 10_000 + 15 * SungTiming.LETTER_MS + 2_000
        val heardBoth = sungAt(10_000, once[0]) + sungAt(again, once[0]).drop(1)
        assertEquals(mapOf(0 to 10_000L), SungTiming.linesMs(once, listOf(heard(9_000, heardBoth)), 60_000))
        // With only "go" heard of the first time, the second has it, and starts where that "home" is sung.
        val onlyGo = heardBoth.drop(4)
        assertEquals(mapOf(0 to 10_000L + 15 * SungTiming.LETTER_MS), SungTiming.linesMs(once, listOf(heard(9_000, onlyGo)), 60_000))
    }

    @Test
    fun aLineSungTwiceHeardOnceWithNothingToSayWhichIsntTimed() {
        // The chorus heard alone could be either time it's sung.
        val chorus = SungTiming.linesMs(lines, listOf(heard(40_000, sungAt(41_000, lines[3]), sungAt(45_000, lines[4]))), 100_000)
        assertEquals(emptyMap<Int, Long>(), chorus)
        // With the verse before it heard, it's the second time.
        val withVerse = SungTiming.linesMs(lines, listOf(heard(40_000, sungAt(41_000, lines[6]), sungAt(45_000, lines[7]))), 100_000)
        assertEquals(mapOf(6 to 41_000L, 7 to 45_000L), withVerse)
    }

    @Test
    fun aChorusHeardWellAfterTheVerseCouldBeItsSecondTime() {
        // Lines 3 and 4 are sung again as 7 and 8. Heard 10 s after line 2, the chorus has time to be either,
        // so nothing says which; the verse line is sure.
        fun timedWithVerse(vararg verse: List<Pair<String, Long>>) =
            SungTiming.linesMs(lines, listOf(heard(20_000, *verse, sungAt(35_000, lines[3]), sungAt(39_000, lines[4]))), 100_000)
        assertEquals(mapOf(2 to 25_000L), timedWithVerse(sungAt(25_000, lines[2])))
        // With two verse lines before it, the same: the verse is no surer of the chorus for being longer.
        assertEquals(mapOf(1 to 21_000L, 2 to 25_000L), timedWithVerse(sungAt(21_000, lines[1]), sungAt(25_000, lines[2])))
    }

    @Test
    fun aLineHeardAtOneTimeWithTheNextAfterItCouldBeEither() {
        val two = listOf("Amber lanterns glow over town", "Silver rivers bend away")
        val clear = heard(39_000, sungAt(40_000, two[0]), sungAt(44_000, two[1]))
        // Heard clearly at 40 s, and three of its five words at 20 s: the next line, at 44 s, follows either as well.
        val threeWords = heard(19_000, sungAt(20_000, two[0]).take(3))
        assertEquals(mapOf(1 to 44_000L), SungTiming.linesMs(two, listOf(threeWords, clear), 100_000))
        // Two of them heard at 5 s too don't change it, as three at 20 s are enough.
        val twoWords = heard(4_000, sungAt(5_000, two[0]).take(2))
        assertEquals(mapOf(1 to 44_000L), SungTiming.linesMs(two, listOf(twoWords, threeWords, clear), 100_000))
        // Two alone aren't: half as sure as the line heard clearly is too little to doubt it.
        assertEquals(mapOf(0 to 40_000L, 1 to 44_000L), SungTiming.linesMs(two, listOf(twoWords, clear), 100_000))
    }

    @Test
    fun aLineHeardAtTwoTimesHalfAsSureElsewhereIsntTimed() {
        // Heard whole at 10 s and two of its words at 30 s: exactly half as sure there, so it could be either.
        fun timed(line: String) = SungTiming.linesMs(listOf(line), listOf(heard(9_000, sungAt(10_000, line)), heard(29_000, sungAt(30_000, line).take(2))), 100_000)
        assertEquals(emptyMap<Int, Long>(), timed("Amber lanterns glow over"))
        // Two of five words are less than half: it's where it's heard whole.
        assertEquals(mapOf(0 to 10_000L), timed("Amber lanterns glow over town"))
    }

    @Test
    fun aLineHeardAsSureInTwoPlaysUpTo6sApartIsTimedAtTheFirst() {
        val two = listOf("Amber lanterns glow over town", "Silver rivers bend away")
        fun timedAgainAt(againMs: Long) = SungTiming.linesMs(two, listOf(heard(20_000, sungAt(21_000, two[0])), heard(againMs - 1_000, sungAt(againMs, two[0]))), 100_000)
        // Two plays hear one singing a little apart: up to 6 s, it's one place.
        assertEquals(mapOf(0 to 21_000L), timedAgainAt(27_000))
        // Any further, it could be sung at either.
        assertEquals(emptyMap<Int, Long>(), timedAgainAt(27_001))
        // With the next line after both, the first is taken too.
        val withNext = listOf(heard(20_000, sungAt(21_000, two[0]), sungAt(30_000, two[1])), heard(23_000, sungAt(24_000, two[0])))
        assertEquals(mapOf(0 to 21_000L, 1 to 30_000L), SungTiming.linesMs(two, withNext, 100_000))
    }

    @Test
    fun aLineHeardAtThreeTimesIsWhereItsFarSurer() {
        // Two of five words heard at 40 s and at 60 s each say little against the line heard whole at 21 s.
        val halfHeard = { at: Long -> heard(at - 1_000, heardAs(at, lines[1], "Coffee steaming bah ugh wee")) }
        assertEquals(mapOf(1 to 21_000L), SungTiming.linesMs(lines, listOf(heard(20_000, sungAt(21_000, lines[1])), halfHeard(40_000), halfHeard(60_000)), 120_000))
    }

    @Test
    fun aShortLineNeedsALineBesideIt() {
        val short = listOf("Paper boats along the gutter", "Oh yeah", "Morning comes over the hill")
        // Two words alike can be chance.
        assertEquals(emptyMap<Int, Long>(), SungTiming.linesMs(short, listOf(heard(10_000, sungAt(13_000, short[1]))), 60_000))
        // Right after a line found, they're that line's next.
        assertEquals(mapOf(0 to 10_000L, 1 to 13_000L), SungTiming.linesMs(short, listOf(heard(9_000, sungAt(10_000, short[0]), sungAt(13_000, short[1]))), 60_000))
        // Right before a line found, as long before as its 6 letters can be sung at the slowest, and 2 s more.
        assertEquals(mapOf(1 to 13_000L, 2 to 16_200L), SungTiming.linesMs(short, listOf(heard(12_000, sungAt(13_000, short[1]), sungAt(16_200, short[2]))), 60_000))
        // Longer after the line before than that, it's alone again.
        val late = listOf("Paper boats along the gutter", "Morning comes over the hill", "Oh yeah")
        assertEquals(mapOf(1 to 10_000L), SungTiming.linesMs(late, listOf(heard(9_000, sungAt(10_000, late[1]), sungAt(20_000, late[2]))), 60_000))
        // Three words alike are enough alone.
        val three = sungAt(13_000, short[2]).filter { it.first in setOf("comes", "over", "hill") }
        assertEquals(mapOf(2 to 13_000L), SungTiming.linesMs(short, listOf(heard(12_000, three)), 60_000))
    }

    @Test
    fun aLineNotMadeOutBetweenTwoHeardIsPutBetweenThem() {
        val four = listOf("Hey", "Paper boats along the gutter", "Shimmer shimmer", "Morning comes over the hill")
        // Where its letters put it between, 8 s apart at most.
        val between = 10_000L + 8_000L * 24 / (24 + 14)
        val la = listOf("la" to between + SungTiming.HEARD_LATE_MS + 300, "la" to between + SungTiming.HEARD_LATE_MS + 600)
        assertEquals(mapOf(1 to 10_000L, 2 to between, 3 to 18_000L), SungTiming.linesMs(four, listOf(heard(10_000, sungAt(10_000, four[1]), la, sungAt(18_000, four[3]))), 60_000))
        assertEquals(mapOf(1 to 10_000L, 3 to 18_001L), SungTiming.linesMs(four, listOf(heard(10_000, sungAt(10_000, four[1]), la, sungAt(18_001, four[3]))), 60_000))
        // Only lines between are put: a voice just before the first doesn't put the line before it there.
        val oh = listOf("oh" to 9_700L + SungTiming.HEARD_LATE_MS)
        assertEquals(mapOf(1 to 10_000L, 2 to between, 3 to 18_000L), SungTiming.linesMs(four, listOf(heard(9_000, oh, sungAt(10_000, four[1]), la, sungAt(18_000, four[3]))), 60_000))
        // The two found in two plays, it's put between them as long as one heard all from one to the other.
        val firstPlay = heard(17_500, sungAt(18_000, four[3]))
        val secondPlay = HeardStretch(10_000, 18_000, sungAt(10_000, four[1]) + la)
        assertEquals(mapOf(1 to 10_000L, 2 to between, 3 to 18_000L), SungTiming.linesMs(four, listOf(firstPlay, secondPlay), 60_000))
    }

    @Test
    fun aLineBetweenTwoHeardIsPutOnlyWithAVoiceHeardWhereItdBeSung() {
        val three = listOf("Amber lanterns glow", "Yo", "Silver rivers bend")
        // Its letters put it from 15.4 s to the next line at 16 s.
        val between = 10_000L + 6_000L * 17 / 19
        fun timedWithVoiceAt(vararg voiceMs: Long) = SungTiming.linesMs(
            three, listOf(heard(9_000, sungAt(10_000, three[0]), voiceMs.map { "mm" to it + SungTiming.HEARD_LATE_MS }, sungAt(16_000, three[2]))), 60_000
        )
        // Nothing heard between: no voice says it's sung there.
        assertEquals(mapOf(0 to 10_000L, 2 to 16_000L), timedWithVoiceAt())
        // A voice from where it starts on.
        assertEquals(mapOf(0 to 10_000L, 1 to between, 2 to 16_000L), timedWithVoiceAt(between))
        // Not before it starts, nor once the next line starts.
        assertEquals(mapOf(0 to 10_000L, 2 to 16_000L), timedWithVoiceAt(between - 1))
        // Of two lines between, each needs its own voice.
        val four = listOf("Amber lanterns glow", "Yo", "Hey", "Silver rivers bend")
        val first = 10_000L + 6_000L * 17 / 22
        val second = 10_000L + 6_000L * 19 / 22
        val voiced = listOf("mm" to second + SungTiming.HEARD_LATE_MS)
        assertEquals(
            mapOf(0 to 10_000L, 2 to second, 3 to 16_000L),
            SungTiming.linesMs(four, listOf(heard(9_000, sungAt(10_000, four[0]), voiced, sungAt(16_000, four[3]))), 60_000)
        )
        val beforeSecond = listOf("mm" to second - 1 + SungTiming.HEARD_LATE_MS)
        assertEquals(
            mapOf(0 to 10_000L, 1 to first, 3 to 16_000L),
            SungTiming.linesMs(four, listOf(heard(9_000, sungAt(10_000, four[0]), beforeSecond, sungAt(16_000, four[3]))), 60_000)
        )
    }

    @Test
    fun onlyLinesWithWordsArePutBetweenTwoHeard() {
        // Next to each other, or with only a break or a sound between, there's no line to put, whatever's heard between.
        val mm = listOf("mm" to 13_000L + SungTiming.HEARD_LATE_MS)
        for (between in listOf(emptyList(), listOf(""), listOf("♪", ""))) {
            val lines = listOf("Amber lanterns glow") + between + "Silver rivers bend"
            assertEquals(mapOf(0 to 10_000L, lines.lastIndex to 16_000L), SungTiming.linesMs(lines, listOf(heard(9_000, sungAt(10_000, lines[0]), mm, sungAt(16_000, lines.last()))), 60_000))
        }
    }

    @Test
    fun aLineSungBeforeTheWhileHeardIsntTimedByItsLastWords() {
        fun timedSungAt(startMs: Long, vararg before: HeardStretch) =
            SungTiming.linesMs(lines, listOf(*before, heard(30_000, sungAt(startMs, lines[1]).filter { it.second >= 30_000 })), 120_000)
        // Only "by the window" falls in what was heard; the line started well before.
        assertEquals(emptyMap<Int, Long>(), timedSungAt(28_500))
        // A second before, as its first words may be, it's taken.
        assertEquals(mapOf(1 to 29_000L), timedSungAt(29_000))
        // As it is with the while before heard up to where it starts.
        assertEquals(mapOf(1 to 28_500L), timedSungAt(28_500, HeardStretch(8_500, 28_500, emptyList())))
    }

    @Test
    fun aLineHeardInTwoPlaysIsTimedOnce() {
        val placed = SungTiming.linesMs(lines, listOf(heard(20_000, sungAt(21_000, lines[1])), heard(12_000, sungAt(21_000, lines[1]))), 120_000)
        assertEquals(mapOf(1 to 21_000L), placed)
        // Heard at two times, it's where it's heard far surer.
        val halfHeardLater = heardAs(40_000, lines[1], "Coffee steaming bah ugh wee")
        assertEquals(mapOf(1 to 21_000L), SungTiming.linesMs(lines, listOf(heard(20_000, sungAt(21_000, lines[1])), heard(39_000, halfHeardLater)), 120_000))
        // As sure at both, nothing says which is right.
        val misheardLater = heardAs(40_000, lines[1], "Coffee steaming my the window")
        assertEquals(emptyMap<Int, Long>(), SungTiming.linesMs(lines, listOf(heard(20_000, sungAt(21_000, lines[1])), heard(39_000, misheardLater)), 120_000))
    }

    @Test
    fun twoLinesAreNeverCloserThanAnyoneSings() {
        // The first line's 23 letters take at least 1.38 s.
        fun timedSecondAt(startMs: Long) = SungTiming.linesMs(lines, listOf(heard(9_000, sungAt(10_000, lines[0]), sungAt(startMs, lines[1]))), 120_000)
        assertEquals(mapOf(0 to 10_000L, 1 to 11_380L), timedSecondAt(11_380))
        // Any closer, only one of them is right, and nothing says which.
        assertEquals(emptyMap<Int, Long>(), timedSecondAt(11_379))
    }

    @Test
    fun ofTwoLinesHeardOutOfOrderNeitherIsTimed() {
        // Either could come before the third, and each is as sure.
        val three = listOf("Amber lanterns glow", "Silver rivers bend", "Morning comes over the hill")
        val heardSo = heard(9_000, sungAt(10_000, three[1]), sungAt(12_000, three[0]), sungAt(20_000, three[2]))
        assertEquals(mapOf(2 to 20_000L), SungTiming.linesMs(three, listOf(heardSo), 100_000))
        // Heard with no line after them, the same.
        val alone = heard(9_000, sungAt(10_000, three[1]), sungAt(12_000, three[0]))
        assertEquals(emptyMap<Int, Long>(), SungTiming.linesMs(three, listOf(alone), 100_000))
        // And with no line before them.
        val first = listOf("Morning comes over the hill", "Amber lanterns glow", "Silver rivers bend")
        val after = heard(9_000, sungAt(10_000, first[0]), sungAt(20_000, first[2]), sungAt(22_000, first[1]))
        assertEquals(mapOf(0 to 10_000L), SungTiming.linesMs(first, listOf(after), 100_000))
    }

    @Test
    fun wordsInJapaneseAreMatchedALetterAtATime() {
        val japanese = listOf("君の名前を呼んだ", "夜明けの空に")
        val letterMs = SpokenWords.sungLetters("君") * SungTiming.LETTER_MS
        val heardSo = listOf("君の" to 10_550L, "名前を" to 10_550L + 2 * letterMs, "呼んだ" to 10_550L + 5 * letterMs)
        val placed = SungTiming.linesMs(japanese, listOf(heard(9_000, heardSo)), 60_000)
        // Each letter of a word heard is sung a little after the one before, so each says the line starts where it's sung.
        assertEquals(mapOf(0 to 10_000L), placed)
    }

    @Test
    fun aLetterOfUnspacedWritingIsSungAsLongAsASyllable() {
        assertEquals(5, SpokenWords.sungLetters("every"))
        // The speech model hears a Japanese letter about 300 ms after the one before, not a Latin letter's 70 ms.
        assertTrue(SpokenWords.sungLetters("夜") * SungTiming.LETTER_MS in 250L..450L)
    }

    @Test
    fun linesTimedByEarAreNeverLitTillTheyreHeard() {
        val withBreak = listOf(lines[0], lines[1], "", lines[2], lines[3])
        val timed = SungTiming.timed(withBreak, listOf(heard(9_000, sungAt(10_000, lines[0]), sungAt(14_000, lines[1]))), 120_000)
        assertEquals(withBreak, timed.map { it.text })
        assertEquals(10_000L, timed[0].timeMs)
        // The next line heard, it's lit till then.
        assertNull(timed[0].endMs)
        // The next not heard, it's lit till it's sung through.
        assertEquals(14_000L + SpokenWords.pieces(lines[1]).sumOf { it.length } * 120 + 1_000, timed[1].endMs)
        assertTrue(timed.drop(2).none { it.heard })
        assertEquals(LyricLine.NOT_HEARD, timed[3].timeMs)
    }

    @Test
    fun nothingSungHeardTimesNothing() {
        assertEquals(emptyList<LyricLine>(), SungTiming.timed(lines, listOf(heard(0, listOf("[Music]" to 1_000L))), 120_000))
        assertEquals(emptyList<LyricLine>(), SungTiming.timed(lines, emptyList(), 120_000))
        // No words to time.
        assertEquals(emptyMap<Int, Long>(), SungTiming.linesMs(listOf("", "♪"), listOf(heard(9_000, sungAt(10_000, lines[0]))), 120_000))
    }
}
