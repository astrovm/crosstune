package com.astrovm.crosstune

/**
 * A title an app sends, against the title of the song asked for: each a base name with a [tag][titleTag] after it,
 * the app's maybe with a track number and an extension, as a file is named. Plain Kotlin, as it's copied into a
 * checkout of `main` to write what `main` made of each (scripts/namescheck/golden.sh).
 */
internal data class NamePair(val titleBase: String, val titleTag: String, val numbered: Boolean, val songBase: String, val songTag: String) {
    val title: String get() = (if (numbered) "01 " else "") + "$titleBase $titleTag".trim() + if (numbered) ".mp3" else ""
    val song: String get() = "$songBase $songTag".trim()
}

internal object NamePairs {
    /** Who the asked song is by, where an artist is named. */
    const val ARTIST = "Fake Band"

    /** Tags that never name another recording. */
    val SAME_RECORDING = listOf(
        "[Explicit]", "(Clean)", "(Official Audio)", "(Official Video)", "(Lyric Video)", "(Lyrics)", "(From \"Fake Movie\")",
        "- Explicit", "- Official Audio", "(feat. Fake Guest)", "(2011 Remaster)", "(Radio Edit)", "(Mono)"
    )

    /** Tags that may: another recording, another part, or another work it's from. */
    val OTHER_RECORDING = listOf(
        "(Live)", "(Acoustic)", "(Remix)", "(Instrumental)", "(Karaoke)", "(Demo)", "(Cover)", "(Part 1)", "(Part 2)", "(Pt. 2)",
        "(Reprise)", "(Interlude)", "(Spanish Version)", "(English Version)", "(Versión Acústica)", "(En Vivo)", "(Ao Vivo)",
        "(Akustik Version)", "(Stripped)", "(Re-Recorded)", "(Taylor's Version)", "(Alternate Take)", "(Extended)", "(VIP)",
        "(Sped Up)", "(Slowed + Reverb)", "(Japanese Ver.)", "(Club Mix)", "(A Cappella)", "[Live]", "- Live", "- Remix",
        "(Live at Wembley)", "(From 'Fake Movie' / Live)", "(From \"Other Movie\")",
        "(ライブ)", "(カバー)", "(伴奏)", "(カラオケ)", "(라이브)", "(커버)", "(翻唱)", "(ライブ) [Explicit]", "(Live) [Explicit]",
        "[Explicit] (Live)"
    )

    private val tags = listOf("") + SAME_RECORDING + OTHER_RECORDING

    /** Fewer, for names that only differ in their base. */
    private val someTags = listOf(
        "", "[Explicit]", "- Explicit", "(Live)", "(Acoustic)", "(Cover)", "(Instrumental)", "- Live", "(Part 2)", "(ライブ)", "(カバー)", "(伴奏)", "(커버)"
    )

    /**
     * One name on both sides, in several scripts: a one-word title, two words, one with no Latin letters, one that says "ライブ" itself.
     * Only the two-word one is also a file with a track number, as more would take the test too long.
     */
    private val bases = listOf("Hello", "Fake Song", "愛", "東京ライブ")

    /** Names that differ, but share words or a script's version mark. */
    private val otherBases = listOf(
        "愛" to "恋", "Hello" to "Hello World", "Rings" to "7 Rings", "東京ライブ" to "大阪ライブ", "Hotel California" to "Hotel California Radio",
        "Song" to "Song 2", "Fake Song" to "Fake Song Two"
    )

    fun all(): List<NamePair> {
        val same = bases.flatMap { base ->
            val numbered = base == "Fake Song"
            tags.flatMap { theirs ->
                listOf(theirs, "$theirs [Explicit]".trim()).distinct().flatMap { tag ->
                    tags.flatMap { ours ->
                        listOfNotNull(NamePair(base, tag, false, base, ours), NamePair(base, tag, true, base, ours).takeIf { numbered })
                    }
                }
            }
        }
        val other = otherBases.flatMap { (one, two) -> listOf(one to two, two to one) }.flatMap { (title, song) ->
            someTags.flatMap { theirs -> someTags.map { ours -> NamePair(title, theirs, false, song, ours) } }
        }
        return (same + other).distinct()
    }

    /** What every pair is, line by line, to tell whether a file of answers was made from these pairs. */
    fun fingerprint(pairs: List<NamePair>): String =
        "${pairs.size}\t${pairs.joinToString("\n") { "${it.title}\t${it.song}" }.hashCode()}"
}
