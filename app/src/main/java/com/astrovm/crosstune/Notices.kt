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

/** The notices in `notices/notices.txt`, a block per work, and their licence texts beside it. */
internal object Notices {
    private const val FOLDER = "/com/astrovm/crosstune/notices/"

    /** Every notice, in the file's order. Read when the page first opens. */
    val all: List<Notice> by lazy { parse(read("notices.txt")) }

    /** [notice]'s licence texts and notices, one after the other. */
    fun text(notice: Notice): String = notice.texts.joinToString("\n\n") { read(it).trimEnd() }

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

    private fun read(name: String): String =
        requireNotNull(Notices::class.java.getResourceAsStream(FOLDER + name)) { "No $name" }.bufferedReader().use { it.readText() }
}
