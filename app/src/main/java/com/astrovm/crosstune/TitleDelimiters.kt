package com.astrovm.crosstune

/** Delimiters used to read titles. The positions of opens and closes correspond. */
internal object TitleDelimiters {
    const val OPENS = "([【「〔（［"
    const val CLOSES = ")]】」〕）］"
    const val DASHES = "-–—"

    // These older readers intentionally recognize only the ASCII brackets and first two dashes.
    internal val tagOpens = OPENS.take(2)
    internal val tagCloses = CLOSES.take(2)
    internal val tagBrackets = tagOpens + tagCloses
    internal val tagDashes = DASHES.take(2)

    // NFKC folds the last two pairs into the first two before recording marks are read.
    internal val normalizedOpens = OPENS.take(5)
    internal val normalizedCloses = CLOSES.take(5)

    fun regexClass(chars: String) = chars.map { if (it in "[]-\\") "\\$it" else it.toString() }.joinToString("")
}
