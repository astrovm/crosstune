package com.astrovm.crosstune

/**
 * A work by others that Crosstune ships or downloads, as **Settings** → **About** → **Licenses** shows
 * it: its [name], who it's [by], its [license] by name, the files with its full licence [texts], where
 * it is, [url], and a [note] of what Crosstune changed. [covers] says what of the APK it is, for
 * `NoticesTest`, which fails when something in the APK has no notice.
 */
internal data class Notice(
    val name: String,
    val by: String,
    val license: String,
    val texts: List<String>,
    val url: String?,
    val note: String?,
    val covers: List<String>
)

/**
 * What a person reads of a notice in one language: its [name], who it's [by], its [license] by name and
 * its [note]. Crosstune's own words are translated; the names works give themselves and their licence
 * texts stay as published.
 */
internal data class NoticeWords(val name: String, val by: String, val license: String, val note: String?)

/** The notices in `notices/notices.txt`, a block per work, and their licence texts beside it. */
internal object Notices {
    private const val FOLDER = "/com/astrovm/crosstune/notices/"

    /** Every notice, in the file's order. Read when the page first opens. */
    val all: List<Notice> by lazy { parse(read("notices.txt")) }

    /** [notice]'s licence texts and notices, one after the other; some are copied with Windows' line ends. */
    fun text(notice: Notice): String = notice.texts.joinToString("\n\n") { read(it).replace("\r\n", "\n").trimEnd() }

    /** The blocks of [text]: a name, then `key: value` lines; `#` lines are comments. */
    internal fun parse(text: String): List<Notice> =
        text.lines().filterNot { it.startsWith('#') }.joinToString("\n")
            .split(Regex("\n\\s*\n")).map(String::trim).filter(String::isNotEmpty).map(::notice)

    private fun notice(block: String): Notice {
        val lines = block.lines()
        val fields = lines.drop(1).map { it.substringBefore(": ") to it.substringAfter(": ") }
        fun one(key: String) = fields.firstOrNull { it.first == key }?.second
        return Notice(
            name = lines.first(),
            by = requireNotNull(one("by")) { "${lines.first()} has no by:" },
            license = requireNotNull(one("license")) { "${lines.first()} has no license:" },
            texts = one("text")?.split(' ').orEmpty(),
            url = one("url"),
            note = one("note"),
            covers = fields.filter { it.first == "covers" }.map { it.second }
        )
    }

    /**
     * Each notice's words in [tag], one of the app's languages, by the notice's name: from
     * `notices-TAG.txt`, a block for each work with the words it translates, and English for the rest.
     * Read once for each language.
     */
    fun words(tag: String): Map<String, NoticeWords> = synchronized(translated) { translated.getOrPut(tag) { translate(tag) } }

    private val translated = HashMap<String, Map<String, NoticeWords>>()

    private fun translate(tag: String): Map<String, NoticeWords> {
        val file = Notices::class.java.getResourceAsStream("${FOLDER}notices-$tag.txt")?.bufferedReader()?.use { it.readText() }
        val found = file?.let(::translations).orEmpty()
        return all.associate { notice ->
            val words = found[notice.name].orEmpty()
            notice.name to NoticeWords(words["name"] ?: notice.name, words["by"] ?: notice.by, words["license"] ?: notice.license, words["note"] ?: notice.note)
        }
    }

    /** A translation file's blocks: each work's English name, then its translated `key: value` lines. */
    internal fun translations(text: String): Map<String, Map<String, String>> =
        blocks(text).associate { lines -> lines.first() to lines.drop(1).associate { it.substringBefore(": ") to it.substringAfter(": ") } }

    /** [text]'s blocks, apart by blank lines, each as its lines, trimmed; `#` lines are comments. */
    internal fun blocks(text: String): List<List<String>> {
        val blocks = mutableListOf<MutableList<String>>()
        var apart = true
        text.lines().filterNot { it.startsWith('#') }.forEach { line ->
            if (line.isBlank()) {
                apart = true
            } else {
                if (apart) blocks += mutableListOf<String>()
                blocks.last() += line.trim()
                apart = false
            }
        }
        return blocks
    }

    private fun read(name: String): String =
        requireNotNull(Notices::class.java.getResourceAsStream(FOLDER + name)) { "No $name" }.bufferedReader().use { it.readText() }
}
