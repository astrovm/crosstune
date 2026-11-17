package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPatternsRuntimeTest {
    @Test
    fun derivedRecordingPatternsAreThePatternsTheAppCompiled() {
        val patterns = AppPatterns.ofApp().found
        for (name in listOf("guestCredit", "editionTag", "coverBy", "coverByOwnName", "singerAfter", "bracketTag", "dashTag")) {
            val found = requireNotNull(patterns.singleOrNull { it.where.startsWith("SongNames.kt:") && it.holds == name })
            val field = SongNames::class.java.getDeclaredField(name)
            check(field.trySetAccessible())
            val actual = field.get(null) as? Regex ?: error("$name is not a Regex")
            val measured = phoneRegex(found.pattern.pattern(), PatternsProbe.options(found.pattern.flags()), onIcu = false)
            assertEquals(name, actual.pattern, measured.pattern)
        }
    }
}
