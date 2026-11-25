package com.astrovm.crosstune

/** Signed-out Presto search responses, with the unused fields removed from each result. */
internal fun prestoSearchFixture(name: String): String =
    checkNotNull(FakeSpotify::class.java.getResource("/presto/$name.json")).readText()
