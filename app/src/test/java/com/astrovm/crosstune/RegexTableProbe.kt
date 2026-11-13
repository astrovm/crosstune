package com.astrovm.crosstune

import java.io.File

/**
 * What a [Regex] reads each character as, on the engine it runs on: the JVM's in tests, ICU on a phone. They differ,
 * and the readers that took the place of main's patterns copy the phone's. `scripts/regextable/regextable.py` runs
 * [main] on an emulator, keeps its table in `app/src/test/regextable/`, and lists where the JVM's differs.
 *
 * Each line is tab-separated: a kind, a name, then what it found. The first says which engine it is.
 * - `class`, a pattern, the code points it matches whole, as ranges in hex: "9-d,20,85".
 * - `fold`, a letter, the code points of the basic plane that match it whole ignoring case.
 * - `ends`, a text in hex code points, then where `$`, `(?m)$`, `(?m)^` and `\b` match in it, by place.
 */
object RegexTableProbe {
    /** Each construct the app's patterns use that reads a letter by itself, with the options it's used with. */
    private val classes = listOf(
        """\s""", """\S""", """\w""", """\W""", """\d""", """\D""", ".", """\p{Zs}""", """\p{L}""", """\p{M}""", """\p{Mn}""",
        """\p{N}""", """\p{sc=Han}""", """\p{sc=Hangul}""", """\p{sc=Hiragana}""", """\p{sc=Katakana}""", """\p{sc=Latin}""", """\p{Cf}""", """\p{Me}""",
    ).map { it to Regex(it) } + listOf(
        "(?s)." to Regex(".", RegexOption.DOT_MATCHES_ALL),
        "(?i)[a-z]" to Regex("[a-z]", RegexOption.IGNORE_CASE),
        "(?i)[^a-z]" to Regex("[^a-z]", RegexOption.IGNORE_CASE),
        """(?i)\s""" to Regex("""\s""", RegexOption.IGNORE_CASE),
        """(?i)\w""" to Regex("""\w""", RegexOption.IGNORE_CASE),
    ) + listOf(
        """\S""", """\W""", """\d""", """\p{L}""", """\p{M}""", """\p{N}""", """\p{Zs}""", """[\p{L}\p{N}]""", """[^\p{L}\p{N}]""", """[^\s]""",
    ).map { "(?i)$it" to Regex(it, RegexOption.IGNORE_CASE) }

    /** Where a word starts for `\b`: at the start of the code point alone, after an "a", and after a space. */
    private val boundaries = listOf("\\b@0" to "", "\\b@a" to "a", "\\b@ " to " ")

    /** The letters main's patterns read ignoring case, and those case folds may reach. */
    private val foldedLetters = ('a'..'z') + "ñáéíóúüçãõâêôàèìòùäëïößåæøœşğıİклипофцаьнрзыеσςǅ".toList()

    /** Line ends, a letter, the spaces a pattern's `$` or `\b` may treat as one, and a mark and a format letter that `\b` skips. */
    private val endPieces = listOf("a", "\n", "\r", "\u0085", "\u2028", "\u2029", "\u000B", "\u000C", " ", "\u3000", "\u0301", "\u200F")

    fun lines(): Sequence<String> = sequence {
        yield("# engine\t${engine()}")
        for ((name, regex) in classes) yield("class\t$name\t${ranges(0x10FFFF) { regex.toPattern().matcher("").let { m -> { text: String -> m.reset(text).matches() } } }}")
        for ((name, before) in boundaries) {
            yield("class\t$name\t${ranges(0x10FFFF) { val m = Regex("\\b").toPattern().matcher(""); { text: String -> m.reset(before + text).find(before.length) && m.start() == before.length } }}")
        }
        for (letter in foldedLetters) {
            yield("fold\t$letter\t${ranges(0xFFFF) { val m = Regex(Regex.escape(letter.toString()), RegexOption.IGNORE_CASE).toPattern().matcher(""); { text: String -> m.reset(text).matches() } }}")
        }
        yieldAll(icuFolds())
        val ends = listOf("$" to Regex("$"), "(?m)$" to Regex("$", RegexOption.MULTILINE), "(?m)^" to Regex("^", RegexOption.MULTILINE), "\\b" to Regex("\\b"))
        for (one in endPieces) for (two in endPieces + "") for (three in endPieces + "") {
            val text = "a$one$two$three"
            val found = ends.joinToString("\t") { (name, regex) -> "$name=" + text.indices.plus(text.length).filter { regex.find(text, it)?.range?.first == it }.joinToString(",") }
            yield("ends\t${text.map { "%x".format(it.code) }.joinToString(" ")}\t$found")
        }
    }

    /**
     * On Android, ICU's own case folds, by which its patterns ignore case, and none on the JVM, which has no ICU.
     * - `sfold`, then each code point that folds to another alone, as "code>fold" in hex.
     * - `multi`, a code point that folds to more than one, its fold, and whether a pattern of the fold ignoring case
     *   matches it, and a pattern of it matches the fold: "ß", "ss", true, true.
     */
    private fun icuFolds(): Sequence<String> = sequence {
        val icu = runCatching { Class.forName("android.icu.lang.UCharacter") }.getOrNull() ?: return@sequence
        val foldCode = icu.getMethod("foldCase", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val foldText = icu.getMethod("foldCase", String::class.java, Boolean::class.javaPrimitiveType)
        val simple = (0..0x10FFFF).mapNotNull { code -> (foldCode.invoke(null, code, true) as Int).takeIf { it != code }?.let { "%x>%x".format(code, it) } }
        yield("sfold	-	${simple.joinToString(",")}")
        for (code in 0..0x10FFFF) {
            val text = String(Character.toChars(code))
            val fold = foldText.invoke(null, text, true) as String
            if (fold.codePointCount(0, fold.length) < 2) continue
            val foldMatches = Regex(Regex.escape(fold), RegexOption.IGNORE_CASE).matches(text)
            val matchesFold = Regex(Regex.escape(text), RegexOption.IGNORE_CASE).matches(fold)
            yield("multi	%x	${fold.codePoints().toArray().joinToString(" ") { "%x".format(it) }}	$foldMatches	$matchesFold".format(code))
        }
    }

    /** The engine: ICU's version and Android's on a phone, the JVM's elsewhere. */
    fun engine(): String {
        val icu = runCatching { Class.forName("android.icu.util.VersionInfo").getField("ICU_VERSION").get(null) }.getOrNull()
            ?: return "JVM ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"
        val build = Class.forName("android.os.Build\$VERSION")
        return "ICU $icu, Android ${build.getField("RELEASE").get(null)}, API ${build.getField("SDK_INT").get(null)}"
    }

    /** The code points to [last] that [matcher] takes, as runs. A half of a surrogate pair is read alone. */
    private fun ranges(last: Int, matcher: () -> (String) -> Boolean): String {
        val matches = matcher()
        val runs = StringBuilder()
        var start = -1
        for (code in 0..last + 1) {
            val taken = code <= last && matches(String(Character.toChars(code)))
            if (taken && start < 0) start = code
            if (!taken && start >= 0) {
                if (runs.isNotEmpty()) runs.append(',')
                runs.append(if (start == code - 1) "%x".format(start) else "%x-%x".format(start, code - 1))
                start = -1
            }
        }
        return runs.toString()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        File(args[0]).bufferedWriter().use { out -> lines().forEach { out.write(it); out.write("\n") } }
    }
}
