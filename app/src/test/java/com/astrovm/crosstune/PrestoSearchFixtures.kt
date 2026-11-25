package com.astrovm.crosstune

import org.json.JSONArray
import org.json.JSONObject

/** Signed-out Presto search responses, with the unused fields removed from each result. */
internal fun prestoSearchFixture(name: String): String =
    checkNotNull(FakeSpotify::class.java.getResource("/presto/$name.json")).readText()

/** Only the first ten rows of Speak No Evil's search, with the total still 36. */
internal fun prestoShorterFirstPage(): String = JSONObject(prestoSearchFixture("jazz-shorter")).apply {
    val payload = getJSONObject("payload")
    val results = payload.getJSONArray("results")
    payload.put("results", JSONArray((0 until 10).map(results::getJSONObject)))
}.toString()
