package com.astrovm.crosstune

/**
 * [pattern] read as a phone reads it. On Android, [Regex] runs on ICU, which reads `\s`, `\w`, `\d`, `\b`, `.`, `$` and
 * case otherwise than the JVM that tests run on: U+3000 is a space, "２" a digit, and "İ" no "i". On a phone this is
 * [Regex] itself. Elsewhere it's the same pattern written out in what ICU reads, by the table in
 * `app/src/test/regextable/` that [PHONE_ENGINE] made, so a test reads a title as a phone does: see [JVM_WRITER].
 */
internal fun phoneRegex(pattern: String, vararg options: RegexOption): Regex = phoneRegex(pattern, options.toSet(), ICU)

/** [pattern] as [Regex] reads it on ICU when [onIcu], or else written out for the JVM in what ICU reads. */
internal fun phoneRegex(pattern: String, options: Set<RegexOption>, onIcu: Boolean): Regex =
    if (onIcu) Regex(pattern, options) else Regex(JVM_WRITER.written(pattern, options))

/** What writes a pattern out for the JVM in what ICU reads. */
internal fun interface PatternWriter {
    fun written(pattern: String, options: Set<RegexOption>): String
}

/**
 * The tests' [PatternWriter], `IcuPattern.kt` in their source set, found by its name: only tests run on the JVM, so a
 * phone never looks for it, and the app holds neither it nor the sets it writes from.
 */
private val JVM_WRITER by lazy { Class.forName("com.astrovm.crosstune.IcuPatternWriter").getDeclaredConstructor().newInstance() as PatternWriter }

/** Whether [Regex] runs on ICU here: only ICU reads U+3000 as a space. A field, read as it was set: on the JVM it's always false. */
@JvmField
internal val ICU = Regex("""\s""").matches("\u3000")

/** Code points, from runs in hex as [PHONE_SPACE] has them: "9-d,20" is U+0009 to U+000D, and U+0020. */
internal class CodeRuns(text: String) {
    /** Each run's first and last code point, one run after another. */
    val runs = text.split(',').filter(String::isNotEmpty).flatMap { run ->
        listOf(run.substringBefore('-').toInt(HEX), run.substringAfter('-').toInt(HEX))
    }.toIntArray()

    operator fun contains(code: Int): Boolean {
        var low = 0
        var high = runs.size / 2 - 1
        while (low <= high) {
            val middle = (low + high) / 2
            when {
                code < runs[2 * middle] -> high = middle - 1
                code > runs[2 * middle + 1] -> low = middle + 1
                else -> return true
            }
        }
        return false
    }
}

/** How a pattern that ignores case reads letters on a phone: by ICU's case folds, from [PHONE_SIMPLE_FOLDS] and [PHONE_FULL_FOLDS]. */
internal object CaseFolds {
    /** The letter each folds to by itself. */
    val simpleFolds = PHONE_SIMPLE_FOLDS.split(',').associate { it.substringBefore(':').toInt(HEX) to it.substringAfter(':').toInt(HEX) }

    /** What each letter that folds to more than one folds to in a word. */
    val fullFolds = PHONE_FULL_FOLDS.split(',').associate { fold ->
        fold.substringBefore(':').toInt(HEX) to fold.substringAfter(':').split(' ').map { it.toInt(HEX) }.toIntArray()
    }

    /** The letter [code] folds to by itself: "K" (the Kelvin sign) to "k". */
    fun simple(code: Int) = simpleFolds[code] ?: code

    /** Whether [code] folds to more than one letter in a word. */
    fun foldsLonger(code: Int) = code in fullFolds
}

private const val HEX = 16
