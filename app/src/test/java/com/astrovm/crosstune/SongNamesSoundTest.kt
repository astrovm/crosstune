package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

class SongNamesSoundTest {

    @Test
    fun aNameInKatakanaSoundsLikeTheOneInLatinLettersItSays() {
        val alike = listOf(
            "Plastic Love" to "プラスティック・ラブ",
            "Miracle Love" to "ミラクル・ラブ",
            "Manhattan Kiss" to "マンハッタン・キス",
            "Single Again" to "シングル・アゲイン",
            // An r that ends a sound isn't said, and m before p is n.
            "Heart Beat" to "ハートビート",
            "Simple" to "シンプル",
            // What it adds in brackets is left out, and one sound off is still the name.
            "Synchronicity" to "シンクロニシティ(素敵な偶然)",
            "Quick Six" to "クイック・シックス",
            "Kiss Me" to "キスミー",
            "Plastic Love" to "プラスティック・ラブズ",
            // Five sounds, the fewest that may be one off.
            "Sweet Memory" to "スイート・メモリーズ",
            // A ch said k, a th said s, a ph said f, a v said b and an s said z.
            "Christmas Eve" to "クリスマス・イブ",
            "Thank You" to "サンキュー",
            "Telephone" to "テレフォン",
            "Venus" to "ビーナス",
            "Dreams" to "ドリームズ",
            // Its name in Latin letters has its own tag left out too.
            "Plastic Love (Extended Club Mix)" to "プラスティック・ラブ"
        )
        alike.forEach { (latin, katakana) -> assertEquals("$latin / $katakana", true, SongNames.soundsAlike(latin, katakana)) }
    }

    @Test
    fun otherNamesOrTooFewSoundsDontSoundAlike() {
        val other = listOf(
            "Plastic Love" to "プラスチック・ドール",
            // Two sounds off is another name.
            "Plastic Love" to "プラスティック・ドール",
            // Not in katakana: kanji, hiragana or Latin letters say nothing of how it sounds.
            "Plastic Love" to "けんかをやめて",
            "Plastic Love" to "Plastic Love",
            "Plastic Love" to "Majiでkoiする5秒前",
            "Plastic Love" to "プラスティック・Love",
            "Plastic Love" to "・ー",
            // Too short to tell, and short ones must agree in full.
            "Love" to "ラブ",
            "Party" to "パーティー",
            "Miss Me" to "キスミー"
        )
        other.forEach { (latin, katakana) -> assertEquals("$latin / $katakana", false, SongNames.soundsAlike(latin, katakana)) }
    }

    @Test
    fun oneEditApartIsADistanceOfOneAtMost() {
        // Every pair of words of up to four letters of two kinds: each place a letter can be changed, added or taken away.
        val words = (0..4).flatMap { length -> (0 until (1 shl length)).map { bits -> String(CharArray(length) { if (bits shr it and 1 == 1) 'b' else 'a' }) } }
        for (one in words) for (other in words) {
            assertEquals("$one / $other", SongNames.distance(one, other) <= 1, SongNames.oneEditApart(one, other))
        }
    }
}
