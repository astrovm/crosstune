package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** The kind of card whose schedule is the line's own, the one every line has had. */
internal const val READ_TYPE = "read"

/** The kind that asks for the words from what they mean. */
internal const val SAY_TYPE = "say"

/**
 * A kind of card: what each side shows. Every line makes one card of each kind that's [on], each on
 * its own schedule. [name] is null for the two the app starts with, which are named in the app's language.
 */
internal data class CardType(val id: String, val name: String?, val on: Boolean, val options: CardOptions) {
    /** Whether [line] has something to ask with: this kind's front shows a layer it has. */
    fun fits(line: SavedLine): Boolean = options.front.run {
        words || (translation && line.translation != null) || (latin && line.romanized != null) || (readings && line.reading != null)
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("on", on)
        .put("front", options.front.toJson()).put("back", options.back.toJson())
}

/** Read it: the words in front, what they mean and how they read behind. */
internal val READ_IT = CardType(READ_TYPE, null, true, CardOptions())

/** Say it: what it means in front, the words and how they read behind; off until it's asked for. */
internal val SAY_IT = CardType(SAY_TYPE, null, false, CardOptions(CardSide(words = false, translation = true), CardSide(readings = true)))

internal val DEFAULT_CARD_TYPES = listOf(READ_IT, SAY_IT)

/** The kinds of card kept, or, never picked, the two the app starts with, the first as the card used to be set. */
internal fun loadCardTypes(preferences: SharedPreferences): List<CardType> {
    val array = preferences.getString(CARD_TYPES_KEY, null)?.let { runCatching { JSONArray(it) }.getOrNull() }
        ?: return listOf(READ_IT.copy(options = CardOptions.load(preferences)), SAY_IT)
    return (0 until array.length()).mapNotNull { index ->
        val json = array.optJSONObject(index) ?: return@mapNotNull null
        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val preset = DEFAULT_CARD_TYPES.firstOrNull { it.id == id }?.options ?: READ_IT.options
        CardType(
            id, json.optString("name").takeIf { json.has("name") && !json.isNull("name") }, json.optBoolean("on"),
            CardOptions(CardSide.fromJson(json.optJSONObject("front") ?: JSONObject(), preset.front), CardSide.fromJson(json.optJSONObject("back") ?: JSONObject(), preset.back))
        )
    }.ifEmpty { listOf(READ_IT) }
}

internal fun saveCardTypes(preferences: SharedPreferences, types: List<CardType>) {
    preferences.edit { putString(CARD_TYPES_KEY, JSONArray(types.map { it.toJson() }).toString()) }
}

private const val CARD_TYPES_KEY = "card_types"

/** [line]'s cards that are due at [now], one of each kind in use that suits it, as those cards. */
internal fun SavedLine.cardsDue(now: Long, types: List<CardType>): List<SavedLine> =
    if (!practisable) emptyList() else types.filter { it.on && it.fits(this) }.map { forType(it.id) }.filter { it.due <= now }
