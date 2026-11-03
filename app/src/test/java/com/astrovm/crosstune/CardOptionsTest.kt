package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardOptionsTest {
    private val prefs = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("card_options_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    fun existingCardPreferencesKeepTheirMeaningOnTheTwoSides() {
        assertEquals(CardOptions(), CardOptions.load(prefs))
        prefs.edit().putString("card_front", "LATIN").putBoolean("card_readings", false)
            .putBoolean("card_latin", false).putBoolean("card_translation", false).commit()
        assertEquals(CardOptions(CardSide(wordsAs = WordsAs.LATIN), CardSide(sound = true)), CardOptions.load(prefs))
        // As written was where every side started, so kept so, it follows the lyrics now.
        prefs.edit().putString("card_front", "WRITTEN").commit()
        assertNull(CardOptions.load(prefs).front.wordsAs)
        prefs.edit().putString("card_front", "unknown").commit()
        assertNull(CardOptions.load(prefs).front.wordsAs)
        prefs.edit().putString("card_front_words_as", "KANA").putString("card_back_words_as", "WRITTEN").commit()
        assertEquals(WordsAs.KANA, CardOptions.load(prefs).front.wordsAs)
        assertNull(CardOptions.load(prefs).back.wordsAs)
    }

    @Test
    fun aSideKeptAsWrittenBeforeFollowsTheLyricsAndOnlyAChangeStaysPicked() {
        fun read(json: String, defaults: CardSide = CardSide()) = CardSide.fromJson(JSONObject(json), defaults).wordsAs
        assertNull(read("""{"wordsAs":"WRITTEN","romajiTyped":true}"""))
        assertEquals(WordsAs.KANA, read("""{"wordsAs":"KANA"}"""))
        assertEquals(WordsAs.LATIN, read("""{"wordsAs":"LATIN"}"""))
        assertNull(read("""{"wordsAs":"unknown"}""", CardSide(wordsAs = WordsAs.KANA)))
        assertEquals(WordsAs.KANA, read("{}", CardSide(wordsAs = WordsAs.KANA)))
        // Picked now, as written stays picked, and following stays following whatever the kind started as.
        assertEquals(WordsAs.WRITTEN, CardSide.fromJson(CardSide(wordsAs = WordsAs.WRITTEN).toJson(), CardSide()).wordsAs)
        assertNull(CardSide.fromJson(CardSide().toJson(), CardSide(wordsAs = WordsAs.KANA)).wordsAs)
    }

    @Test
    fun aLineInKanaHasOnlyItsKanjiChangedAndKeepsItsKatakana() {
        val door = SavedLine(
            "胸のドアを叩いても", reading = "むねのどあをたたいても", title = "Thesis", artist = "Band",
            ruby = listOf(Ruby("胸", "むね"), Ruby("のドアを"), Ruby("叩", "たた"), Ruby("いても"))
        )
        assertEquals("むねのドアをたたいても", door.shownAs(WordsAs.KANA, typed = false))
        // Kept before it had its parts, it's the reading as it is.
        assertEquals("むねのどあをたたいても", door.copy(ruby = null).shownAs(WordsAs.KANA, typed = false))
    }

    @Test
    fun aSideWritesTheWordsAsTheLyricsDoUnlessPickedInWaysTheLineCanBeWritten() {
        val japanese = SavedLine("夜空", reading = "よぞら", romanized = "yozora", title = "Night", artist = "Band")
        val chinese = SavedLine("夜空", romanized = "yè kōng", title = "Night", artist = "Band")
        val english = SavedLine("Night sky", title = "Night", artist = "Band")
        assertEquals(WordsAs.KANA, CardSide().writes(japanese, WordsAs.KANA))
        assertEquals(WordsAs.LATIN, CardSide(wordsAs = WordsAs.LATIN).writes(japanese, WordsAs.KANA))
        assertEquals(WordsAs.WRITTEN, CardSide(wordsAs = WordsAs.WRITTEN).writes(japanese, WordsAs.LATIN))
        // Kana only where there's kana, Latin letters only where there's another script.
        assertEquals(WordsAs.WRITTEN, CardSide().writes(chinese, WordsAs.KANA))
        assertEquals(WordsAs.LATIN, CardSide().writes(chinese, WordsAs.LATIN))
        assertEquals(WordsAs.WRITTEN, CardSide(wordsAs = WordsAs.LATIN).writes(english, WordsAs.KANA))
    }

    @Test
    fun bothSidesRoundTripAllLayersAndSizesIndependently() {
        val random = Random(42)
        repeat(30) {
            fun side() = CardSide(
                words = random.nextBoolean(), wordsAs = (WordsAs.entries + null).random(random),
                readings = random.nextBoolean(), latin = random.nextBoolean(), translation = random.nextBoolean(),
                wordsScale = MIN_WORDS_SCALE + random.nextFloat() * (MAX_WORDS_SCALE - MIN_WORDS_SCALE),
                translationScale = MIN_WORDS_SCALE + random.nextFloat() * (MAX_WORDS_SCALE - MIN_WORDS_SCALE),
                readingScale = MIN_READING_SCALE + random.nextFloat() * (MAX_READING_SCALE - MIN_READING_SCALE),
                sound = random.nextBoolean()
            )
            val options = CardOptions(side(), side())
            val type = CardType("type1", "Mine", true, options)
            saveCardTypes(prefs, listOf(type))
            assertEquals(listOf(type) + DEFAULT_CARD_TYPES.map { it.copy(on = false) }, loadCardTypes(prefs))
            val front = options.front.copy(words = !options.front.words, wordsAs = if (options.front.wordsAs == null) WordsAs.KANA else null)
            saveCardTypes(prefs, listOf(type.copy(options = options.copy(front = front))))
            assertEquals(options.back, loadCardTypes(prefs).first().options.back)
            assertEquals(front, loadCardTypes(prefs).first().options.front)
        }
        assertFalse(prefs.contains("lyrics_words_as"))
        assertFalse(prefs.contains("lyrics_romaji_typed"))
    }

    @Test
    fun invalidSavedSizesStayWithinTheReadableSliderRanges() {
        prefs.edit().putFloat("card_front_words_scale", -5f).putFloat("card_front_reading_scale", 50f)
            .putFloat("card_back_words_scale", 50f).putFloat("card_back_reading_scale", -5f).commit()
        val cards = CardOptions.load(prefs)
        assertEquals(MIN_WORDS_SCALE, cards.front.wordsScale, 0f)
        assertEquals(MAX_READING_SCALE, cards.front.readingScale, 0f)
        assertEquals(MAX_WORDS_SCALE, cards.back.wordsScale, 0f)
        assertEquals(MIN_READING_SCALE, cards.back.readingScale, 0f)
        assertTrue(cards.front.words && cards.back.words)
    }

    @Test
    fun aTranslationKeptBeforeItHadASizeOfItsOwnIsAsBigAsTheWordsWere() {
        val old = org.json.JSONObject(CardSide(wordsScale = 1.5f).toJson().toString().replace(Regex(",?\"translationScale\":[0-9.]+"), ""))
        assertFalse(old.has("translationScale"))
        assertEquals(1.5f, CardSide.fromJson(old, CardSide()).translationScale, 0f)
        assertEquals(2f, CardSide.fromJson(old.put("translationScale", 2.0), CardSide()).translationScale, 0f)
        prefs.edit().putFloat("card_front_words_scale", 2f).commit()
        assertEquals(2f, CardOptions.load(prefs).front.translationScale, 0f)
    }

    @Test
    fun aSideKeptBeforeItHadASoundPlaysTheLineOnlyIfItShowedNothing() {
        val old = org.json.JSONObject(CardSide().toJson().toString().replace(Regex(",?\"sound\":(true|false)"), ""))
        assertFalse(old.has("sound"))
        assertFalse(CardSide.fromJson(old, CardSide()).sound)
        assertTrue(CardSide.fromJson(old, CardSide(sound = true)).sound)
        assertTrue(CardSide.fromJson(old.put("words", false), CardSide()).sound)
        // The back plays it, as it did, and the front only when it showed nothing.
        assertTrue(CardOptions.load(prefs).back.sound)
        assertFalse(CardOptions.load(prefs).front.sound)
        prefs.edit().putBoolean("card_front_words", false).commit()
        assertTrue(CardOptions.load(prefs).front.sound)
    }
}
