package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Each of the app's patterns finds in [TitleFuzz]'s titles here, written out by [phoneRegex], what it found on an
 * emulator, where `Regex` is ICU as on a phone. `scripts/regextable/regextable.py patterns` measured those there, on
 * the newest Android and on the oldest the app runs on, Android 8. Each table holds every pattern the app has and no
 * other, and Android 8 finds what the newest finds, but where [ANDROID_8_READS_OTHERWISE] says how the app handles it.
 */
class PatternsOnThePhoneTest {
    @Test
    fun everyPatternFindsWhatItFindsOnThePhone() {
        val titles = PatternsProbe.titles()
        val found = AppPatterns.ofApp().found
        assertEquals(true, found.size > 100)
        val app = found.associate { pattern ->
            val options = PatternsProbe.options(pattern.pattern.flags())
            val text = pattern.pattern.pattern()
            PatternsProbe.key(text, options) to Here(pattern.where, PatternsProbe.digest(phoneRegex(text, options, onIcu = false), titles))
        }
        assertEquals(emptyList<String>(), misread(app, table(NEWEST), table(OLDEST), ANDROID_8_READS_OTHERWISE))
    }

    @Test
    fun aTableIsHeldToEachPatternTheAppHas() {
        val app = mapOf("k1" to Here("A.kt:1", "d1"), "k2" to Here("A.kt:2", "d2"), "k3" to Here("A.kt:3", "d3"))
        val newest = Table("API 37", mapOf("k1" to "d1", "k2" to "d2", "k3" to "d3"))
        assertEquals(emptyList<String>(), misread(app, newest, newest.copy(api = "API 26"), emptyMap()))
        val wrong = misread(
            app,
            Table("API 37", mapOf("k1" to "d1", "k2" to "other", "gone" to "d9")),
            Table("API 26", mapOf("k1" to "other", "k2" to "other", "gone" to "d9")),
            mapOf("A.kt:2" to "read the same", "B.kt:9" to "gone"),
        )
        assertEquals(
            listOf(
                "A.kt:2: finds otherwise than on API 37 (other)",
                "A.kt:3: not measured on API 37, run regextable.py patterns",
                "gone: on API 37, a pattern the app no longer has, run regextable.py patterns",
                "A.kt:1: finds otherwise on API 26 than on API 37 (other), say in ANDROID_8_READS_OTHERWISE how the app handles it",
                "A.kt:3: not measured on API 26, run regextable.py patterns",
                "gone: on API 26, a pattern the app no longer has, run regextable.py patterns",
                "A.kt:2: no pattern there finds otherwise on API 26 than on API 37, take it out of ANDROID_8_READS_OTHERWISE",
                "B.kt:9: no pattern there finds otherwise on API 26 than on API 37, take it out of ANDROID_8_READS_OTHERWISE",
            ),
            wrong,
        )
        // A pattern Android 8 reads otherwise is right where it says how the app handles it.
        val throwsOn8 = Table("API 26", mapOf("k1" to "d1", "k2" to "d2", "k3" to PatternsProbe.THROWS))
        assertEquals(emptyList<String>(), misread(app, newest, throwsOn8, mapOf("A.kt:3" to "built another way on Android 8")))
    }

    @Test
    fun everyPatternIsReadOnTheOldestAndroid() {
        // Android 8's ICU throws on a script named as "\p{IsHan}", where "\p{sc=Han}" reads on every version.
        val throwing = table(OLDEST).digests.filterValues { it == PatternsProbe.THROWS }
        assertEquals(emptyMap<String, String>(), throwing)
        val named = AppPatterns.ofApp().found.filter { SCRIPT_BY_IS.containsMatchIn(it.pattern.pattern()) }.map { it.where }
        assertEquals(emptyList<String>(), named)
    }

    @Test
    fun aPatternIsKeptWithItsOptionsAsItIs() {
        val line = PatternsProbe.line("A.kt:1", "a\\\tb\nc\r", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        assertEquals("a\\\tb\nc\r" to setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE), PatternsProbe.read(line))
        assertEquals("a" to emptySet<RegexOption>(), PatternsProbe.read(PatternsProbe.line("A.kt:1", "a", emptySet())))
    }

    /** A pattern of the app's: where it is, and what it finds here, written out by [phoneRegex]. */
    private data class Here(val where: String, val digest: String)

    /** An emulator's digests by pattern, measured on [api]. */
    private data class Table(val api: String, val digests: Map<String, String>)

    /**
     * What's wrong in [newest] and [oldest] against the [app]'s patterns, by key: a pattern either has not, one it has
     * that the app has not, the newest finding otherwise than [phoneRegex] does here, and the oldest finding otherwise
     * than the newest, but where [handled] says how the app handles it. A place in [handled] with no pattern the two
     * find otherwise is wrong too, so that what it says stays true.
     */
    private fun misread(app: Map<String, Here>, newest: Table, oldest: Table, handled: Map<String, String>): List<String> {
        fun kept(table: Table, wrong: (String, Here, String) -> String?) =
            app.mapNotNull { (key, here) ->
                when (val there = table.digests[key]) {
                    null -> "${here.where}: not measured on ${table.api}, run regextable.py patterns"
                    else -> wrong(key, here, there)
                }
            } + (table.digests.keys - app.keys).map { "$it: on ${table.api}, a pattern the app no longer has, run regextable.py patterns" }
        val newestWrong = kept(newest) { _, here, there ->
            "${here.where}: finds otherwise than on ${newest.api} ($there)".takeIf { there != here.digest }
        }
        val oldestWrong = kept(oldest) { key, here, there ->
            "${here.where}: finds otherwise on ${oldest.api} than on ${newest.api} ($there), say in ANDROID_8_READS_OTHERWISE how the app handles it"
                .takeIf { there != newest.digests[key] && here.where !in handled }
        }
        val readSame = handled.keys.filter { where ->
            app.none { (key, here) -> here.where == where && oldest.digests[key] != newest.digests[key] }
        }.map { "$it: no pattern there finds otherwise on ${oldest.api} than on ${newest.api}, take it out of ANDROID_8_READS_OTHERWISE" }
        return newestWrong + oldestWrong + readSame
    }

    private fun table(path: String): Table {
        val lines = File(path).readLines()
        val api = "API " + path.substringAfterLast("android-").substringBefore(".tsv")
        // The file is named for the Android it was measured on.
        assertEquals(lines.first(), true, lines.first().startsWith("# engine\tICU ") && lines.first().endsWith(", $api"))
        return Table(api, lines.drop(1).associate { it.substringBefore('\t') to it.substringAfter('\t') })
    }

    private companion object {
        const val NEWEST = "src/test/regextable/patterns-android-37.tsv"
        const val OLDEST = "src/test/regextable/patterns-android-26.tsv"
        val SCRIPT_BY_IS = Regex("""\\[pP]\{Is""")

        /**
         * Each pattern, by where it is, that Android 8 finds otherwise than the newest Android, and how the app handles
         * it there. None does: on the fuzz titles, Android 8's ICU 58 finds what ICU 78 finds with every pattern.
         */
        val ANDROID_8_READS_OTHERWISE = emptyMap<String, String>()
    }
}
