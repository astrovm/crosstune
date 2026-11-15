package com.astrovm.crosstune

import com.astrovm.crosstune.SyntheticSinging.Writing
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Made-up songs in each writing, sung at each pace and heard a word at a time, held to what [SungTiming] does
 * with them now: no fewer lines within a second of where they're sung, and no more put further off. The
 * recordings the replay has can't tell this, as few of their lines are sung fast in Japanese or Korean (Issue #977).
 * A change that does better raises the numbers here; one that does worse has to say why it's worth it.
 */
class SyntheticSingingTest {

    /**
     * Within a second and further off, of 400 lines, for each pace in [SyntheticSinging.PACES]. Here the first
     * word of each while is heard where it's sung, but the speech model often puts it near the while's start: in
     * the recordings, a line's first word heard first in a while is a median 0.34 s early, and 3 times as often over
     * a second early as over a second late; heard later in a while, it's 0.17 s early, and late about as often as
     * early (Issue #892). So [SungTiming] doesn't time a line by it, which costs up to 4 lines a row here.
     */
    private val floors = mapOf(
        Writing.LATIN to listOf(386 to 0, 391 to 0, 387 to 1, 388 to 0, 378 to 6),
        Writing.JAPANESE to listOf(395 to 1, 394 to 5, 395 to 3, 399 to 1, 398 to 1),
        Writing.KOREAN to listOf(395 to 3, 398 to 0, 398 to 0, 400 to 0, 395 to 3),
        Writing.CHINESE to listOf(395 to 3, 396 to 0, 398 to 0, 399 to 0, 399 to 1),
    )

    @Test
    fun eachWritingIsTimedAsWellAsBeforeAtEachPace() {
        val worse = Writing.entries.flatMap { writing ->
            SyntheticSinging.PACES.zip(floors.getValue(writing)).mapNotNull { (pace, floor) ->
                val score = SyntheticSinging.score(writing, pace)
                "$writing at $pace ms a syllable: $score, was ${floor.first} within, ${floor.second} off"
                    .takeIf { score.within < floor.first || score.off > floor.second }
            }
        }
        assertTrue(worse.joinToString("\n"), worse.isEmpty())
    }
}
