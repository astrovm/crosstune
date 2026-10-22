package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** The kind of card whose schedule is the line's own, the one every line has had. */
internal const val READ_TYPE = "read"

/** The kind that plays the line and asks what it says. */
internal const val HEAR_TYPE = "hear"

/** The kind that asks for the words from what they mean. */
internal const val TRANSLATE_TYPE = "translate"

/**
 * A kind of card: what each side shows. Every line makes one card of each kind that's [on], each on
 * its own schedule. [name] is null for the three the app starts with, which are named in the app's language
 * and stay.
 */
internal data class CardType(val id: String, val name: String?, val on: Boolean, val options: CardOptions) {
    /** Whether the line plays in front, as the question. */
    val hears: Boolean get() = options.front.sound

    /** Whether [line] has something to ask with: this kind's front plays a line that can be, or shows a layer it has. A front with nothing on shows the words. */
    fun fits(line: SavedLine): Boolean = (hears && line.timed) || options.front.run {
        words || (bare && !sound) || (translation && line.translation != null) || (latin && line.romanized != null) || (readings && line.reading != null)
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("on", on)
        .put("front", options.front.toJson()).put("back", options.back.toJson())
}

/** Read it: the words in front, what they mean and how they read behind. */
internal val READ_IT = CardType(READ_TYPE, null, true, CardOptions())

/** Hear it: the line plays in front, the words, how they read and what they mean behind; off until it's asked for. */
internal val HEAR_IT = CardType(HEAR_TYPE, null, false, CardOptions(CardSide(words = false, sound = true), CardSide(readings = true, latin = true, translation = true)))

/** Translate it: what it means in front, the words and how they read, in kana and in Latin letters, behind; off until it's asked for. */
internal val TRANSLATE_IT = CardType(TRANSLATE_TYPE, null, false, CardOptions(CardSide(words = false, translation = true), CardSide(readings = true, latin = true, sound = true)))

/** The kinds the app starts with, which stay. */
internal val DEFAULT_CARD_TYPES = listOf(READ_IT, HEAR_IT, TRANSLATE_IT)

/** The kinds of card kept, or, never picked, the three the app starts with, the first as the card used to be set. Any of them missing is back, off. */
internal fun loadCardTypes(preferences: SharedPreferences): List<CardType> {
    val array = preferences.getString(CARD_TYPES_KEY, null)?.let { runCatching { JSONArray(it) }.getOrNull() }
        ?: return listOf(READ_IT.copy(options = CardOptions.load(preferences))) + DEFAULT_CARD_TYPES.drop(1)
    val kept = (0 until array.length()).mapNotNull { index ->
        val json = array.optJSONObject(index) ?: return@mapNotNull null
        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val preset = DEFAULT_CARD_TYPES.firstOrNull { it.id == id }?.options ?: READ_IT.options
        CardType(
            id, json.optString("name").takeIf { json.has("name") && !json.isNull("name") }, json.optBoolean("on"),
            CardOptions(CardSide.fromJson(json.optJSONObject("front") ?: JSONObject(), preset.front), CardSide.fromJson(json.optJSONObject("back") ?: JSONObject(), preset.back))
        )
    }
    // The ones that went missing come back off, unless that leaves no card at all.
    return kept + DEFAULT_CARD_TYPES.filter { preset -> kept.none { it.id == preset.id } }.map { if (kept.any { it.on }) it.copy(on = false) else it }
}

internal fun saveCardTypes(preferences: SharedPreferences, types: List<CardType>) {
    preferences.edit { putString(CARD_TYPES_KEY, JSONArray(types.map { it.toJson() }).toString()) }
}

private const val CARD_TYPES_KEY = "card_types"

/** [line]'s cards that are due at [now], one of each kind in use that suits it, as those cards. */
internal fun SavedLine.cardsDue(now: Long, types: List<CardType>): List<SavedLine> =
    if (!practisable) emptyList() else types.filter { it.on && it.fits(this) }.map { forType(it.id) }.filter { it.due <= now }

/** How many cards of [lines] are due at [now]. */
internal fun List<SavedLine>.cardsDueCount(now: Long, types: List<CardType>) = sumOf { it.cardsDue(now, types).size }
