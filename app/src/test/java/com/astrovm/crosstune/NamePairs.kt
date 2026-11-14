package com.astrovm.crosstune

/**
 * A title an app sends, against the title of the song asked for: each a base name with a [tag][titleTag] after it, or
 * [before] it, the app's maybe with a track number and an extension, as a file is named. Plain Kotlin, as it's copied
 * into a checkout of `main` to write what `main` made of each (scripts/namescheck/golden.sh).
 */
internal data class NamePair(
    val titleBase: String, val titleTag: String, val numbered: Boolean, val songBase: String, val songTag: String, val before: Boolean = false
) {
    val title: String get() = (if (numbered) "01 " else "") + (if (before) "$titleTag $titleBase" else "$titleBase $titleTag").trim() +
        if (numbered) ".mp3" else ""
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
        "", "[Explicit]", "- Explicit", "(Live)", "(Acoustic)", "(Cover)", "(Instrumental)", "- Live", "(Part 2)", "(ライブ)", "(カバー)", "(伴奏)", "(커버)",
        "– Live", "— Live", "- Live - Explicit", "(Live) (Acoustic)", "(Live at Wembley)", "- Live at Wembley", "(Remastered 2011)",
        "- 2011 Remaster", "(ピアノ弾いてみた)", "(Piano Cover)", "[MV]", "(Official Video)"
    )

    /**
     * One name on both sides, in several scripts: a one-word title, two words, one with no Latin letters, one that says "ライブ" itself.
     * Only the two-word one is also a file with a track number, as more would take the test too long.
     */
    private val bases = listOf("Hello", "Fake Song", "愛", "東京ライブ")

    /** Names that differ, but share words or a script's version mark. */
    private val otherBases = listOf(
        "愛" to "恋", "Hello" to "Hello World", "Rings" to "7 Rings", "東京ライブ" to "大阪ライブ", "Hotel California" to "Hotel California Radio",
        "Song" to "Song 2", "Fake Song" to "Fake Song Two",
        // Other scripts, a name a dash splits from its band either way round, numbers, a tag's word in a name's own, a hyphen.
        "사랑" to "사랑해", "爱情" to "爱情公寓", "Любовь" to "Любовь моя", "Canción" to "Canción Triste", "Canción" to "Cancion",
        "Fake Band - Fake Song" to "Fake Song", "Fake Song - Fake Band" to "Fake Song", "1999" to "99", "Fake Song 2" to "Fake Song",
        "Live Forever" to "Forever", "Live" to "Live Forever", "Cover Me" to "Cover Me Up", "Fake Song" to "Fake-Song"
    )

    /** One name on both sides, in other scripts and Latin, with its tag after it or before it. */
    private val scriptBases = listOf("사랑", "爱情", "Любовь", "Canción", "Fake Song", "Live Forever")

    /**
     * Whole titles, as review round 12 of PR #999 wrote them: marks in other scripts with a space before them, in
     * full-width brackets or in 【】, and Latin tags in those brackets, each against another name with the same mark.
     */
    private val written = listOf(
        "愛 カバー" to "恋 カバー", "愛【カバー】" to "恋【カバー】", "愛（カバー）" to "恋（カバー）", "愛【歌ってみた】" to "恋【歌ってみた】",
        "愛 (歌ってみた)" to "恋 (歌ってみた)", "사랑 커버" to "이별 커버", "사랑 (커버)" to "이별 (커버)", "愛 ライブ" to "恋 ライブ",
        "愛【Cover】" to "恋【Cover】", "Hello【Live】" to "Hello World【Live】", "Hello（Live）" to "Hello World（Live）",
        "Hello (Live)" to "Hello World (Live)", "愛 (カバー)" to "恋 (カバー)", "ライブ" to "Live", "アイドル" to "アイドル (ライブ)",
        "Fake Song" to "Fake Song (Live)", "Fake Song" to "Fake Song【Live】", "愛「カバー」" to "恋「カバー」", "愛〔カバー〕" to "恋〔カバー〕",
        "愛［カバー］" to "恋［カバー］", "愛 - カバー" to "恋 - カバー", "Fake Song【ライブ】" to "Fake Song (Live)"
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
        val scripts = scriptBases.flatMap { base ->
            someTags.flatMap { theirs ->
                // A tag before a name is in brackets, "[MV] Fake Song"; a dash before one is no tag, and a remaster is said after it.
                val sides = if (theirs.firstOrNull() in listOf('(', '[') && "Remaster" !in theirs) listOf(false, true) else listOf(false)
                someTags.flatMap { ours -> sides.map { before -> NamePair(base, theirs, false, base, ours, before) } }
            }
        }
        val asWritten = written.flatMap { (one, two) -> listOf(one to two, two to one) }.map { (title, song) -> NamePair(title, "", false, song, "") }
        return (same + other + scripts + asWritten).distinct()
    }

    /** What every pair is, line by line, to tell whether a file of answers was made from these pairs. */
    fun fingerprint(pairs: List<NamePair>): String =
        "${pairs.size}\t${pairs.joinToString("\n") { "${it.title}\t${it.song}" }.hashCode()}"
}
