package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The kinds of practice card, how they're kept, and what each makes of a line. Robolectric, for SharedPreferences. */
@RunWith(RobolectricTestRunner::class)
class CardTypesTest {
    private val prefs = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("card_types_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val minute = 60 * 1000L
    private val day = 24 * 60 * minute

    private fun line(text: String, title: String = "Demo", translation: String? = "Meaning", reading: String? = null, romanized: String? = null) =
        SavedLine(text, reading = reading, romanized = romanized, translation = translation, title = title, artist = "Band")

    @Test
    fun withNothingPickedThereAreThreeKindsAndTheFirstIsTheCardAsItWasSet() {
        assertEquals(listOf(READ_IT, HEAR_IT, TRANSLATE_IT), loadCardTypes(prefs))
        assertEquals(listOf(true, false, false), loadCardTypes(prefs).map { it.on })
        prefs.edit().putString("card_front", "LATIN").putBoolean("card_translation", false).commit()
        val read = loadCardTypes(prefs).first()
        assertEquals(WordsAs.LATIN, read.options.front.wordsAs)
        assertFalse(read.options.back.translation)
        assertEquals(TRANSLATE_IT, loadCardTypes(prefs).last())
    }

    @Test
    fun kindsKeepTheirNamesSwitchesAndSidesAndSkipWhatIsBroken() {
        val mine = CardType("type1", "Mine", true, CardOptions(CardSide(words = false, translation = true), CardSide(readings = true, wordsScale = 2f)))
        val kinds = listOf(READ_IT.copy(on = false), HEAR_IT, TRANSLATE_IT.copy(on = true), mine)
        saveCardTypes(prefs, kinds)
        assertEquals(kinds, loadCardTypes(prefs))
        assertEquals(null, loadCardTypes(prefs).first().name)
        // What can't be read goes, and a kind that's missing a side takes the side it started with.
        prefs.edit().putString("card_types", """[3, {"name":"No id"}, {"id":"translate","on":true}, {"id":"x","name":"","on":true,"front":{"words":false}}]""").commit()
        val loaded = loadCardTypes(prefs)
        // The kinds the app starts with that went missing are back, off.
        assertEquals(listOf(TRANSLATE_TYPE, "x", READ_TYPE, HEAR_TYPE), loaded.map { it.id })
        assertEquals(listOf(true, true, false, false), loaded.map { it.on })
        assertEquals(TRANSLATE_IT.options.front, loaded[0].options.front)
        assertEquals(TRANSLATE_IT.options.back, loaded[0].options.back)
        assertEquals("", loaded[1].name)
        assertFalse(loaded[1].options.front.words)
        assertEquals(READ_IT.options.back, loaded[1].options.back)
        // Nothing readable left, or not even JSON, it's the three it started with.
        prefs.edit().putString("card_types", "[]").commit()
        assertEquals(DEFAULT_CARD_TYPES, loadCardTypes(prefs))
        prefs.edit().putString("card_types", "not json").commit()
        assertEquals(DEFAULT_CARD_TYPES, loadCardTypes(prefs))
    }

    @Test
    fun aKindIsAskedOnlyOfLinesWithSomethingToAskWith() {
        val bare = line("Moon", translation = null)
        val meaning = line("Moon")
        assertTrue(READ_IT.fits(bare))
        // Hear it asks with the sound, so the line has to be one that plays.
        assertTrue(HEAR_IT.hears)
        assertFalse(READ_IT.hears)
        assertFalse(HEAR_IT.fits(meaning))
        assertTrue(HEAR_IT.fits(bare.copy(startMs = 1_000, endMs = 2_000)))
        // Translate it asks with the meaning.
        assertFalse(TRANSLATE_IT.fits(bare))
        assertTrue(TRANSLATE_IT.fits(meaning))
        val latin = CardType("x", null, true, CardOptions(CardSide(words = false, latin = true)))
        assertFalse(latin.fits(meaning))
        assertTrue(latin.fits(line("月", romanized = "tsuki")))
        val readings = CardType("y", null, true, CardOptions(CardSide(words = false, readings = true)))
        assertFalse(readings.fits(meaning))
        assertTrue(readings.fits(line("月", reading = "つき")))
    }

    @Test
    fun aLineIsAnotherCardOfEachKindWithItsOwnScheduleAndTheFirstKindUsesTheLinesOwn() {
        val review = Review(interval = 5, ease = 200, step = 1, due = 77)
        val saved = line("Moon").copy(interval = 2, due = 10, reviews = mapOf(TRANSLATE_TYPE to review))
        assertEquals(saved.copy(cardType = READ_TYPE), saved.forType(READ_TYPE))
        val say = saved.forType(TRANSLATE_TYPE)
        assertEquals(TRANSLATE_TYPE, say.cardType)
        assertEquals(review, Review(say.interval, say.ease, say.step, say.due))
        // A kind it hasn't made a card of yet is new.
        val other = saved.forType("type1")
        assertEquals(Review(), Review(other.interval, other.ease, other.step, other.due))
    }

    @Test
    fun aCardPractisedKeepsItsScheduleApartFromTheLinesOtherCards() {
        val store = SavedLinesStore(prefs)
        val moon = line("Moon")
        store.toggle(moon)
        val say = store.load().single().forType(TRANSLATE_TYPE)
        val afterSay = store.practised(say, Grade.GOOD, now = 0).single()
        // Say it's schedule is kept on the line; the first kind's is as it was.
        assertEquals(0, afterSay.interval)
        assertEquals(0L, afterSay.due)
        assertEquals(Review(interval = 0, step = 1, due = 10 * minute), afterSay.reviews.getValue(TRANSLATE_TYPE))
        // It's kept through a save and a load.
        assertEquals(afterSay.reviews, store.load().single().reviews)
        store.practised(store.load().single().forType(TRANSLATE_TYPE), Grade.GOOD, now = 10 * minute)
        assertEquals(Review(interval = 1, due = 10 * minute + day), store.load().single().reviews.getValue(TRANSLATE_TYPE))
        val afterRead = store.practised(store.load().single(), Grade.GOOD, now = 0).single()
        assertEquals(10 * minute, afterRead.due)
        assertEquals(1, afterRead.step)
        assertEquals(1, afterRead.reviews.getValue(TRANSLATE_TYPE).interval)
    }

    @Test
    fun aSessionHasOneCardForALineTheKindDueLongestAndNoneOfAKindThatIsOff() {
        val both = listOf(READ_IT, TRANSLATE_IT.copy(on = true))
        val lines = listOf(
            line("Later", "A").copy(due = 50, reviews = mapOf(TRANSLATE_TYPE to Review(due = 10))),
            line("Sooner", "A").copy(due = 5, reviews = mapOf(TRANSLATE_TYPE to Review(due = 20))),
            line("Not yet", "A").copy(due = 500, reviews = mapOf(TRANSLATE_TYPE to Review(due = 500))),
            line("No meaning", "A", translation = null, reading = "よむ").copy(due = 1)
        )
        val session = sessionOf(lines, now = 100, types = both)
        assertEquals(listOf("No meaning", "Sooner", "Later"), session.map { it.text })
        // Each is the card of the kind due longest: Say it for Later; Read it for Sooner, and for the line Say it has nothing to ask.
        assertEquals(listOf(READ_TYPE, READ_TYPE, TRANSLATE_TYPE), session.map { it.cardType })
        assertEquals(10L, session.last().due)
        // With Say it off there are only first cards.
        assertEquals(listOf(READ_TYPE, READ_TYPE, READ_TYPE), sessionOf(lines, now = 100).map { it.cardType })
        assertEquals(3, sessionOf(lines, now = 100, types = both).size)
        assertTrue(lines[0].cardsDue(5, both).isEmpty())
        assertEquals(listOf(READ_TYPE, TRANSLATE_TYPE), lines[0].cardsDue(100, both).map { it.cardType })
        assertTrue(line("Nothing", translation = null).cardsDue(Long.MAX_VALUE, both).isEmpty())
    }
}
