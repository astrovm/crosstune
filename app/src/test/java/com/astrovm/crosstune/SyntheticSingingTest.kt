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

    /** Within a second and further off, of 400 lines, for each pace in [SyntheticSinging.PACES]. */
    private val floors = mapOf(
        Writing.LATIN to listOf(388 to 0, 392 to 0, 391 to 1, 390 to 0, 382 to 3),
        Writing.JAPANESE to listOf(396 to 2, 394 to 5, 395 to 5, 399 to 1, 397 to 2),
        Writing.KOREAN to listOf(395 to 3, 398 to 0, 398 to 0, 400 to 0, 396 to 2),
        Writing.CHINESE to listOf(397 to 1, 398 to 0, 398 to 0, 399 to 0, 399 to 1),
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
