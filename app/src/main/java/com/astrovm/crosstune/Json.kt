package com.astrovm.crosstune

import org.json.JSONArray
import org.json.JSONObject

/** The objects in this list, skipping anything else in it; none when there's no list. */
internal fun JSONArray?.objects(): List<JSONObject> = this?.let { array -> (0 until array.length()).mapNotNull(array::optJSONObject) }.orEmpty()

/** Each item in this list as text, as [JSONArray.optString] reads it; none when there's no list. */
internal fun JSONArray?.strings(): List<String> = this?.let { array -> (0 until array.length()).map(array::optString) }.orEmpty()

/** The text at [key], when it's text with something in it; null for none, an empty one or JSON's null. */
internal fun JSONObject.nonBlankString(key: String): String? = (opt(key) as? String)?.takeIf(String::isNotBlank)
