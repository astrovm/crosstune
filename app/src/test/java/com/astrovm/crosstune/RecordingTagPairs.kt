package com.astrovm.crosstune

/**
 * Fake song names for Issue 1009: a recording tag ("Live", "Cover", "カバー") is no part of a song's
 * name, so it can't give one name the Latin word, or the letter, the other lacks. Each pair is two
 * different songs, whichever is asked for and whichever is found. All data is made up.
 */
internal object RecordingTagPairs {
    const val ARTIST = "Fake Utaite"

    /** Two different songs whose names differ only before the tag: no service may take one for the other. */
    val different: List<Pair<String, String>> = listOf(
        "愛 カバー" to "恋 カバー", "愛【カバー】" to "恋【カバー】", "愛（カバー）" to "恋（カバー）",
        "愛【歌ってみた】" to "恋【歌ってみた】", "愛 (歌ってみた)" to "恋 (歌ってみた)", "사랑 커버" to "이별 커버",
        "사랑 (커버)" to "이별 (커버)", "愛 ライブ" to "恋 ライブ", "愛【Cover】" to "恋【Cover】",
        "Hello【Live】" to "Hello World【Live】", "Hello（Live）" to "Hello World（Live）",
        "Hello (Live)" to "Hello World (Live)", "愛 (カバー)" to "恋 (カバー)", "ライブ" to "Live",
        "愛「カバー」" to "恋「カバー」", "愛〔カバー〕" to "恋〔カバー〕", "愛［カバー］" to "恋［カバー］",
        "愛 - カバー" to "恋 - カバー", "Hello – Live" to "Hello World – Live", "Hello — Live" to "Hello World — Live"
    )

    /** The same song, named with or without a recording tag: the one asked for is the one found. */
    val same: List<Pair<String, String>> = listOf(
        "アイドル" to "アイドル (ライブ)", "Fake Song" to "Fake Song (Live)", "Fake Song" to "Fake Song【Live】"
    )

    /** The wrong pairs, each both ways: the song asked for, then the other song a service has instead. */
    val wrongPicks: List<Pair<String, String>> = different.flatMap { (one, other) -> listOf(one to other, other to one) }

    /** Every tagged name written exactly as asked: the right song, which must still be found. */
    val exactNames: List<String> = different.flatMap { it.toList() }.distinct()
}
