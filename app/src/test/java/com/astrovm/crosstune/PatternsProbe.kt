package com.astrovm.crosstune

import java.io.File
import java.security.MessageDigest

/**
 * Each of the app's patterns read over [TitleFuzz]'s first titles, as a digest of where it finds what. On an emulator
 * `Regex` is ICU, as on a phone; on the JVM it's the JVM's, or [phoneRegex] writes it out in what ICU reads.
 * `scripts/regextable/regextable.py patterns` keeps the emulator's in `app/src/test/regextable/`, lists the patterns the
 * JVM reads otherwise, and [PatternsOnThePhoneTest] holds [phoneRegex] to the emulator's.
 *
 * - `list SOURCES OUT`: the patterns in the Kotlin files under SOURCES, a line each: where, options, the pattern.
 * - `digest PATTERNS OUT`: each pattern's key and digest, on the engine it runs on, the engine first.
 */
object PatternsProbe {
    /** Titles read: enough for each kind of letter and line end, and quick on the JVM for every pattern. */
    const val TITLES = 5_000

    fun titles() = TitleFuzz.titles().take(TITLES).map { it.text }.toList()

    /** What names a pattern and its options, whatever line it's on. */
    fun key(pattern: String, options: Set<RegexOption>): String =
        hex(MessageDigest.getInstance("SHA-1").digest((options.map { it.name }.sorted().joinToString(",") + "\n" + pattern).toByteArray())).take(KEY)

    /**
     * Where [regex] finds what in each of [titles], as a digest. It's one search on to the end, as `replace`, `split`
     * and `containsMatchIn` read a title. Not `findAll`'s: it starts a new search after each find, and ICU's `\G` then
     * holds only at the title's start, where the JVM's holds where the last find ended.
     */
    fun digest(regex: Regex, titles: List<String>): String {
        val sha = MessageDigest.getInstance("SHA-1")
        for (title in titles) {
            val found = regex.toPattern().matcher(title)
            while (found.find()) sha.update("${found.start()},${found.end()};".toByteArray())
            sha.update('|'.code.toByte())
        }
        return hex(sha.digest()).take(KEY)
    }

    /** The options a [java.util.regex.Pattern]'s flags name, as Kotlin's [Regex] takes them. */
    fun options(flags: Int): Set<RegexOption> = RegexOption.entries.filter { it.value and flags != 0 && it.value != java.util.regex.Pattern.UNICODE_CASE }.toSet()

    fun line(where: String, pattern: String, options: Set<RegexOption>) = "$where\t${options.joinToString(",") { it.name }}\t${escaped(pattern)}"

    /** A [line]'s pattern and options. */
    fun read(line: String): Pair<String, Set<RegexOption>> {
        val (_, options, pattern) = line.split('\t')
        return unescaped(pattern) to options.split(',').filter(String::isNotEmpty).map(RegexOption::valueOf).toSet()
    }

    private fun escaped(text: String) = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescaped(text: String) = Regex("""\\(.)""").replace(text) { found ->
        when (val letter = found.groupValues[1]) {
            "t" -> "\t"
            "n" -> "\n"
            "r" -> "\r"
            else -> letter
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private const val KEY = 16

    /** The digest of a pattern the engine throws on. */
    const val THROWS = "throws"

    @JvmStatic
    fun main(args: Array<String>) {
        val (command, from, to) = args
        File(to).bufferedWriter().use { out ->
            if (command == "list") {
                val files = File(from).walk().filter { it.extension == "kt" }.sortedBy { it.path }.map { it.name to it.readText() }.toList()
                AppPatterns(files).found.forEach { out.write(line(it.where, it.pattern.pattern(), options(it.pattern.flags())) + "\n") }
            } else {
                val titles = titles()
                out.write("# engine\t${RegexTableProbe.engine()}\n")
                File(from).readLines().forEach { line ->
                    val (pattern, options) = read(line)
                    // A pattern an engine can't read is kept as one, as Android 8's ICU reads no "\p{IsHan}".
                    val read = runCatching { digest(Regex(pattern, options), titles) }.getOrElse { THROWS }
                    out.write("${key(pattern, options)}\t$read\n")
                }
            }
        }
    }
}
