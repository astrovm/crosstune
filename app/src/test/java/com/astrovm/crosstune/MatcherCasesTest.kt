package com.astrovm.crosstune

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every made-up input the reviews of PR #947 tried on YouTube Music, with what `origin/main` opened for it
 * ([Case.main]) and what opens now ([Case.now]): the same, or better. Better is nothing where main opened another
 * song or another version, or the song where main opened nothing. Never worse: a wrong open is the worst there is.
 * To check the main column, run this class on main with [checked] set to [Case.main].
 */
@RunWith(RobolectricTestRunner::class)
class MatcherCasesTest {

    private val checked: (Case) -> String? = Case::now

    /** Set on main, where the matrix and the made-up searches write what main opens, to be kept as [MATRIX] and [FUZZ]. */
    private val onMain = false

    /**
     * Fake Band's "Fake Song", 1:30, on "Fake Album", looked for on YouTube Music, where its search finds [songs]
     * and [videos], each "name | details". Apple has it, as long as [lengthMs], and names it [japan] in Japan's
     * store and [korea] in Korea's; with no [lengthMs], Deezer has it, [deezerSeconds] long. ChatGPT answers
     * [picked]: null when it can't say, as without it, or too late when [slow]. What opens is "song0" for the
     * first song, "video1" for the second video, or null.
     */
    data class Case(
        val what: String,
        val main: String?,
        val now: String? = main,
        val title: String = "Fake Song",
        val artist: String = "Fake Band",
        val album: String = "Fake Album",
        val lengthMs: Long? = 90_000,
        val deezerSeconds: Long? = null,
        val japan: Pair<String, String>? = null,
        val korea: Pair<String, String>? = null,
        val songs: List<String> = emptyList(),
        val videos: List<String> = emptyList(),
        val picked: Int? = null,
        val slow: Boolean = false,
        val chooses: ((List<String>) -> Int?)? = null
    )

    private fun row(name: String, album: String = "Fake Album", artist: String = "Fake Band", length: String = "1:30") = "$name | $artist • $album • $length"

    private fun video(name: String, length: String = "1:30", channel: String = "Fake Band") = "$name | $channel • 1M views • $length"

    private fun japanese(title: String) = title to "Fake Band"

    // The sixth review, of 36adbf4f: PR comment 6839603318. Each opened another row or a video than main's, without ChatGPT.
    private val sixth = listOf("Live" to "ライブ", "Acoustic" to "アコースティック", "Remix" to "リミックス", "Live at Budokan" to "ライブ・アット・武道館").map { (tag, written) ->
        // 1. The store names the song with its tag in Latin letters, the row in katakana: main's row, not the studio take.
        Case("1 $tag", "song0", title = "Fake Song ($tag)", japan = japanese("偽の歌 ($tag)"), songs = listOf(row("偽の歌 ($written)"), row("偽の歌")))
    } + listOf(
        // 2. A video names a part of another song's name.
        Case("2 part of a name", "song0", songs = listOf(row("偽の歌"), row("歌")), videos = listOf(video("Fake Song (歌詞付き)")))
    ) + listOf("Fake Song - English Version", "Fake Song - Full Version", "Fake Song - Version Español", "Fake Song (日本語)").map { name ->
        // 3. The artist's video says what no bracket does.
        Case("3 $name", "song0", songs = listOf(row("偽の歌")), videos = listOf(video(name)))
    } + listOf(
        // 4. The artist's live video, for a song whose tag says "live" of a film.
        Case("4 theme", "song0", title = "Sakura (Theme from Live Forever)", japan = japanese("さくら"), songs = listOf(row("サクラ")), videos = listOf(video("さくら (Live)"))),
        // 5. The right take, named with what it's from, and the artist's teaser.
        Case("5 teaser", "song0", songs = listOf(row("Fake Song - From \"Live Forever\"")), videos = listOf(video("Fake Song (Teaser)", length = "0:30"))),
        // 6. The store's name in hiragana, the row in katakana, and a homophone in kanji.
        Case("6 ame", "song0", title = "Ame", japan = japanese("あめ"), songs = listOf(row("アメ"), row("飴"))),
        // 7. ChatGPT picks another, but main opened its row without asking.
        Case("7 asked", "song0", songs = listOf(row("偽の歌"), row("別の歌")), picked = 1),
        // 9. A live take named by a half, and another song: nothing without ChatGPT, as neither is it.
        Case("9 live half", "song1", now = null, title = "Live Forever", japan = japanese("曲"), songs = listOf(row("別の歌"), row("曲 - Live"))),
        // The replay of 151 songs: one version named twice, in two scripts, or with an apostrophe left out, is the song.
        Case("r1 live twice", "song0", title = "Fake Song (Live)", songs = listOf(row("偽の歌 (ライブ) - Fake Song (Live)"))),
        Case("r2 acoustic twice", "song0", title = "Fake Song Acoustic Guitar Version", songs = listOf(row("偽の歌_Acoustic Guitar Ver. - Fake Song Acoustic Guitar Version"))),
        Case(
            "r3 apostrophe", "song0", title = "Fake Song (Director's Edit Version)", japan = japanese("偽の歌<Director's Edit. Version>"),
            songs = listOf(row("偽の歌 (Directors Edit Version) - The Fake Song(Directors Edit Version)"))
        )
    )

    // The fifth review, of 875d3aa1.
    private val fifth = listOf(
        // 1. The artist's own video says another version, or is cut short: main's row opens before it, as on main.
        Case("1 teaser", "song0", songs = listOf(row("偽の歌", "Live Best")), videos = listOf(video("Fake Song (Teaser)", length = "0:30"))),
        Case("1 english", "song0", songs = listOf(row("偽の歌", "Live Best")), videos = listOf(video("Fake Song (English Version)"))),
        Case("1 short", "song0", songs = listOf(row("偽の歌", "Live Best")), videos = listOf(video("Fake Song (Short Version)"))),
        Case("1 orchestra", "song0", songs = listOf(row("偽の歌", "Live Best")), videos = listOf(video("Fake Song (Orchestra Version)"))),
        Case("1 first take", "song0", songs = listOf(row("偽の歌", "Live Best")), videos = listOf(video("Fake Song (THE FIRST TAKE)"))),
        Case("1 teaser none", "song0", songs = listOf(row("偽の歌", "Live Best")), videos = listOf(video("Fake Song (Teaser)", length = "0:30")), picked = SAYS_NONE),
        // 2. Main's row, spelled in other kana than the store's name or in other Latin letters, is still the song: it opens.
        Case("2 tv edit", "song0", title = "Sakura", japan = japanese("さくら"), songs = listOf(row("サクラ"), row("さくら (TV Edit)"))),
        Case("2 short", "song0", japan = japanese("偽の歌"), songs = listOf(row("Fake Søng"), row("偽の歌 (Short)"))),
        Case("2 theme", "song0", title = "Sakura (Theme from Live Forever)", japan = japanese("さくら"), songs = listOf(row("サクラ"), row("さくら (ライブ)"))),
        // A Latin half that's only a word of the song's name, and no reading of the rest, proves nothing; and saying
        // another version, it isn't the song, which main opened by the other half.
        Case("2 live half", "song1", now = null, title = "Live Forever", japan = japanese("曲"), songs = listOf(row("別の歌"), row("曲 - Live"))),
        // 3. A katakana word no list has, and ヴ in a word that only holds a mark: main's row is shown first.
        Case("3 ballad", "song0", title = "Fake Song (Ballad Version)", songs = listOf(row("偽の歌 (バラードバージョン)"), row("偽の歌")), picked = 0),
        Case("3 alive", "song0", title = "Alive", songs = listOf(row("アライヴ"), row("別の歌")), picked = 0)
    )

    // The fourth review, of 637db13e.
    private val fourth = listOf(
        // 1. All the rows one name, the first kept out by its album only: it opens, as main had it, not the live one.
        Case("1 live best", "song0", songs = listOf(row("偽の歌", "Live Best"), row("偽の歌 (ライヴ)", "Another Album"))),
        Case("1 live best slow", "song0", songs = listOf(row("偽の歌", "Live Best"), row("偽の歌 (ライヴ)", "Another Album")), picked = 1, slow = true),
        Case("1 karaoke hits", "song0", songs = listOf(row("偽の歌", "Karaoke Hits"), row("偽の歌 (ライヴ)", "Another Album"))),
        // 3. The row main opened is in Latin letters past ASCII, and its letters are the song's: it opens.
        Case("3 fake søng", "song0", songs = listOf(row("Fake Søng"), row("別の歌"), row("偽の歌 (Live)"))),
        // 4. ライヴ, カヴァー: another version, where main opened it.
        Case("4 ライヴ", "song0", now = null, songs = listOf(row("偽の歌 (ライヴ)"))),
        Case("4 1999 ライヴ", "song0", now = null, title = "1999", songs = listOf(row("1999 (ライヴ)"))),
        Case("4 カヴァー", "song0", now = null, songs = listOf(row("偽の歌 (カヴァー)"))),
        // 5. The first kept out by its album only, with ChatGPT: it opens, as main, whatever ChatGPT would say.
        Case("5 none", "song0", songs = listOf(row("偽の歌", "Live Best"), row("別の歌", "Another Album")), picked = SAYS_NONE),
        Case("5 first shown", "song0", songs = listOf(row("偽の歌", "Live Best"), row("別の歌", "Another Album")), picked = 0),
        // 7. Main's row opens, before the artist's own video, as on main.
        Case("7 own video", "song0", songs = listOf(row("別の歌"), row("偽の歌 (Live)")), videos = listOf(video("Fake Song")))
    ) +
        // 2. A katakana version word near a word of the title, by chance: the first, as main had it, never the second.
        listOf(
            "Love Song" to "ライヴ", "Stay" to "ショート", "Sorry" to "スロー", "Wrong Way" to "ロング", "Remix Me" to "リミックス",
            "Adam" to "デモ", "Free" to "フル", "Speed Up" to "スピードアップ"
        ).map { (title, word) ->
            Case("2 $word", "song0", title = "$title (Single Version)", songs = listOf(row("偽の歌", "Live Best"), row("偽の歌 (${word}バージョン)", "Another Album")))
        } +
        // 6. Read alike only with a long vowel folded: main's row opens without asking, as on main.
        listOf("脳" to "ノー", "賞" to "ショー", "王" to "オー", "夕" to "ユー", "英" to "エー", "頬" to "ほう", "氷" to "こうり", "通り" to "とうり", "大きい" to "おうきい").flatMap { (kanji, kana) ->
            listOf(
                Case("6 $kanji $kana none", "song0", title = "Fake Song", japan = japanese(kanji), songs = listOf(row(kana)), picked = SAYS_NONE),
                Case("6 $kanji $kana alone", "song0", title = "Fake Song", japan = japanese(kanji), songs = listOf(row(kana)))
            )
        }

    // The third review, of d84db22d: PR comment 6833825483.
    private val third = listOf(
        // 1. A Japanese name that's Latin once its tag is cut, so a Latin row was "written alike".
        Case("1 orchestra", null, japan = japanese("Fake Song (ドラマ「偽物」主題歌)"), songs = listOf(row("Fake Song (Orchestra Version)"))),
        Case("1 english", null, japan = japanese("Fake Song (「偽物」オープニングテーマ)"), songs = listOf(row("Fake Song (English Version)"))),
        Case("1 japanese version", null, title = "Fake Song (Japanese Version)", japan = japanese("Fake Song (日本語版)"), songs = listOf(row("Fake Song"))),
        Case("1 re-recording", null, japan = japanese("Fake Song (ドラマ「偽物」主題歌)"), songs = listOf(row("Fake Song (2023 Re-recording)"))),
        // 3. The first is another version, so two names are left, and nothing tells which.
        Case("3 tv size first", "song0", now = null, songs = listOf(row("偽の歌 (TV Size)"), row("別の歌", "Another Album"))),
        // 4. What the song's names say together: "Single" in English, "バージョン" in Japanese.
        Case("4 single ver", "song0", title = "Fake Song (Single Version)", japan = japanese("偽の歌 (シングル・バージョン)"), songs = listOf(row("偽の歌 (Single ver.)"))),
        Case("4 speed-up in katakana", "song0", title = "Fake Song (Speed-Up Version)", songs = listOf(row("偽の歌（スピードアップバージョン）"))),
        // 6. Read alike, with widths folded and a long vowel as its vowel.
        Case("6 yuki", "song0", title = "Yuki", japan = japanese("ユーキ"), songs = listOf(row("勇気"))),
        Case("6 hanamizuki", "song0", title = "Hanamizuki", japan = japanese("ﾊﾅﾐｽﾞｷ"), songs = listOf(row("花水木"))),
        Case("6 tokyo", "song0", title = "Tokyo", japan = japanese("トーキョー"), songs = listOf(row("東京"))),
        // 7. "Xバージョン" is "X ver.".
        Case("7 tv", "song0", now = null, title = "Fake Song (Single Version)", songs = listOf(row("偽の歌 (TVバージョン)"))),
        Case("7 english", "song0", now = null, title = "Fake Song (Single Version)", songs = listOf(row("偽の歌 (英語バージョン)"))),
        Case("7 own", "song0", title = "Fake Song (Single Version)", japan = japanese("偽の歌 (シングル・バージョン)"), songs = listOf(row("偽の歌 (シングル・バージョン)"))),
        // 2. The first in the song's own script isn't it, as its words are another's, so it's never what's left either.
        Case("2 first in its own script", "song0", now = null, japan = japanese("偽物"), songs = listOf(row("別の歌"), row("Фальшивая песня"), row("Другая песня (Live)"))),
        // 2. A version kept out, the first opens as main had it, whichever song it is: Issue #983.
        Case("2 own first", "song0", songs = listOf(row("偽の歌"), row("別の歌 (Live)"))),
        Case("2 own after", "song0", songs = listOf(row("別の歌"), row("偽の歌 (Live)")))
    ) +
        // 2. The first is the song on a compilation its album's name makes look like another version; main opened it.
        listOf("Live Best", "ライブ・ベスト", "Karaoke Hits", "Acoustic Covers Collection").map { album ->
            Case("2 $album", "song0", songs = listOf(row("偽の歌", album), row("別の歌", "Another Album")))
        } +
        // 5. Alone on one, as main opened it; and on a live album, the same as main, as no album's name tells the two apart.
        listOf("Live Best", "Studio Sessions", "Tribute", "Piano Ballads", "Instrumental Works", "Remix Best", "Live at Budokan").map { album ->
            Case("5 $album", "song0", songs = listOf(row("偽の歌", album)))
        }

    // The second review, of 965dcc85.
    private val second = listOf(
        // 1. A video saying "ver." for "(Single Version)".
        Case("1 video", "video0", title = "Fake Song (Single Version)", videos = listOf(video("Fake Song")))
    ) + listOf("TV", "English", "Short", "Dance", "Live").map { tag ->
        Case("1 $tag ver", null, title = "Fake Song (Single Version)", videos = listOf(video("Fake Song ($tag ver.)")))
    } + listOf("バンドライブ", "サウンドライブ", "ワールドライブ", "밴드라이브").map { live ->
        // 2. A band live.
        Case("2 $live", null, videos = listOf(video("Fake Song ($live)")))
    } + listOf("ばら" to "原", "きんか" to "銀河", "かっこう" to "学校", "雨" to "飴").map { (kana, kanji) ->
        // 4. Read alike only with the sound marks, and never kanji against kanji: ChatGPT is asked, and says none.
        Case("4 $kana $kanji", "song0", now = null, title = "Fake Song", japan = japanese(kana), songs = listOf(row(kanji)), picked = SAYS_NONE)
    } + listOf(
        // 4. Homophones, kana against kanji, as main: Issue #983.
        Case("4 あめ 飴", "song0", japan = japanese("あめ"), songs = listOf(row("飴")), picked = SAYS_NONE),
        // 5. "Live Forever" is "曲" in Japan, so "曲 (ライブ)" is a live one.
        Case("5 live forever", "video0", title = "Live Forever", japan = japanese("曲"), videos = listOf(video("曲"))),
        Case("5 live forever live", null, title = "Live Forever", japan = japanese("曲"), videos = listOf(video("曲 (ライブ)"))),
        // 5. The song's own album says "& Instrumental", not the row.
        Case("5 own album instrumental", "song0", now = null, album = "Naughty Boys & Instrumental", songs = listOf(row("偽の歌 (Instrumental)", "Naughty Boys & Instrumental"))),
        Case("5 own album", "song0", album = "Naughty Boys & Instrumental", songs = listOf(row("偽の歌", "Naughty Boys & Instrumental"))),
        // The artist's only song as long, in kana, with nothing to say it isn't: as main, Issue #983.
        Case("issue kimini mune kyun", "song0", title = "Kimini Mune Kyun", songs = listOf(row("さよなら")))
    )

    private val angel = Case("", null, title = "A Cruel Angel's Thesis", artist = "Yoko Takahashi", lengthMs = 245_093)
    private val angelInJapan = angel.copy(japan = "残酷な天使のテーゼ" to "高橋洋子")
    private val springDay = Case("", null, title = "Spring Day", artist = "BTS", lengthMs = 274_000, korea = "봄날" to "방탄소년단")
    private val named = "残酷な天使のテーゼ - Zankoku na Tenshi no Thesis"

    // The first review, of d776d0f8.
    private val first = listOf("カラオケバージョン", "ピアノカバー", "ライブバージョン", "ギターソロ", "アコースティックカバー", "オルゴールアレンジ", "歌ってみたよ").map { tag ->
        // 1. A fan's video saying it's another version, with a word run on.
        angelInJapan.copy(what = "1 $tag", main = null, now = null, videos = listOf(video("残酷な天使のテーゼ / 高橋洋子 ($tag)", "4:06", "Fan")))
    } + listOf("피아노커버", "커버곡", "라이브영상", "노래방버전", "라이브클립").map { tag ->
        springDay.copy(what = "1 $tag", main = null, now = null, videos = listOf(video("봄날 - BTS ($tag)", "4:34", "Fan")))
    } + listOf(
        angelInJapan.copy(what = "1 fan video", main = "video0", now = "video0", videos = listOf(video("残酷な天使のテーゼ / 高橋洋子", "4:06", "Fan"))),
        springDay.copy(what = "1 spring day", main = "video0", now = "video0", videos = listOf(video("봄날 - BTS", "4:34", "Fan"))),
        // 2a. PR #797's song, with nothing to name it, and no ChatGPT.
        angel.copy(what = "2a pr 797", main = "song1", now = "song1", songs = listOf(row("Fake Song", "Fake Album", "Yoko Takahashi", "3:00"), row(named, "Zankoku na Tenshi no Thesis", "Yoko Takahashi", "4:06"))),
        // 2b. One name written two ways, with Deezer's length only.
        Case("2b xiaoxingyun", "song1", title = "小幸運", artist = "田馥甄", lengthMs = null, deezerSeconds = 266, songs = listOf(row("Fake Song", "Fake Album", "田馥甄", "3:00"), row("小幸运", "小幸运", "田馥甄", "4:26"))),
        Case("2b sakura", "song1", title = "さくら", lengthMs = null, deezerSeconds = 266, songs = listOf(row("Fake Song", length = "3:00"), row("桜", "桜", length = "4:26"))),
        Case("2b sakura kanji", "song1", title = "桜", lengthMs = null, deezerSeconds = 266, songs = listOf(row("Fake Song", length = "3:00"), row("さくら", "さくら", length = "4:26"))),
        // 3. A sign is no letters.
        Case("3 trademark", "song0", title = "Fake Song™", songs = listOf(row("Fake Song")))
    )

    // The review of 61c74c9c, PR comment 6812563715, and the defect the PR was opened for, Issue #945.
    private val before = listOf(
        Case("945 accent", "song0", now = null, artist = "Fake Singer", lengthMs = 245_000, songs = listOf(row("Canción Falsa", "Otro Álbum", "Fake Singer", "4:06"))),
        angel.copy(what = "945 karaoke", main = "song0", now = null, lengthMs = 245_000, songs = listOf(row("残酷な天使のテーゼ (オフ・ヴォーカル)", "残酷な天使のテーゼ", "Yoko Takahashi", "4:06"))),
        // Another song by the artist as long, with nothing to say it isn't, opens as on main, asked or not (Issue #983).
        angel.copy(what = "945 other song", main = "song0", now = "song0", lengthMs = 245_000, songs = listOf(row("魂のルフラン", "魂のルフラン", "Yoko Takahashi", "4:04"))),
        angel.copy(what = "945 other song asked", main = "song0", now = "song0", lengthMs = 245_000, songs = listOf(row("魂のルフラン", "魂のルフラン", "Yoko Takahashi", "4:04")), picked = SAYS_NONE),
        // 1. Two songs as long, with no store name in Japanese to tell them apart: main's opens (Issue #983).
        angel.copy(what = "f1 two songs", main = "song0", now = "song0", songs = listOf(row("魂のルフラン", "B", "Yoko Takahashi", "4:05"), row("残酷な天使のテーゼ", "A", "Yoko Takahashi", "4:05"))),
        // 12. Written in full-width letters: nothing opens, as on main.
        Case("f12 full width", null, title = "Ｆａｋｅ Ｓｏｎｇ", songs = listOf(row("Fake Song")))
    )

    @Test
    fun everyInputTheReviewsTriedOpensWhatMainDidOrBetter() {
        val cases = sixth + fifth + fourth + third + second + first + before
        val wrong = cases.map { it to find(it) }.filter { (case, found) -> found != checked(case) }
            .map { (case, found) -> "${case.what}: ${checked(case)}, not $found" }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun theNamesTheReviewsTriedReadAsMainReadThemOrBetter() {
        // The first review, 3: a sign is no letter, and 4: many songs in one, with a word run on.
        assertEquals(true, SongNames.same("Fake Song™", "Fake Song"))
        assertEquals(false, SongNames.same("№1", "No 1"))
        assertEquals(false, SongNames.same("36℃", "36C"))
        for (title in listOf("ディズニーメドレー", "ヒットメドレー2020", "메들리곡")) {
            assertEquals(title, true, SongNames.manySongs(MusicMetadata(title, "Fake Band")))
        }
        // The review of 61c74c9c, 5: longer words that hold a mark, which main took for one, say no other version of
        // what main opened; nor does ヴ in a word that only holds a mark's letters (the fifth review, 4), nor a mark in
        // the name that means a word of the song's (the sixth, 8).
        fun other(name: String, title: String = "Fake Song") = SongNames.saysAnotherVersionOf(name, listOf(title), "Fake Band")
        for (name in listOf("Fake Song (インストール)", "Fake Song (ATV Sizes)", "Fake Song (ライフ)", "偽の歌 (ライヴァル)", "インストラクター")) {
            assertEquals(name, false, other(name))
        }
        assertEquals(false, other("アライヴ (Alive)", "Alive"))
        assertEquals(false, other("ライヴ・フォーエヴァー (Live Forever)", "Live Forever"))
        // Written with ヴ as a word of its own, it's live, or a cover, as ライブ and カバー are.
        for (name in listOf("偽の歌 (ライヴ)", "偽の歌 (カヴァー)", "偽の歌 ライヴ・ヴァージョン")) {
            assertEquals(name, true, other(name))
        }
    }

    /**
     * A song looked for in the matrix: its [title], its name in Japan's store, the [version] it is, its name in
     * another script [base], and the same name spelled otherwise, [alt].
     */
    private class Song(val key: String, val title: String, val japan: String?, val version: String, val base: String, val alt: String)

    /** A row or video of the matrix: its name, the version it is ([OTHER] for another song), its album, length and channel. */
    private class Kind(val key: String, val name: String, val version: String, val album: String = "Fake Album", val length: String = "1:30", val channel: String = "Fake Band")

    private val songsOfTheMatrix = listOf(
        Song("plain", "Fake Song", null, "", "偽の歌", "にせのうた"),
        Song("japan", "Fake Song", "偽の歌", "", "偽の歌", "にせのうた"),
        Song("single", "Love Song (Single Version)", null, "single", "偽の歌", "にせのうた"),
        Song("speedup", "Fake Song (Speed-Up Version)", null, "speed up", "偽の歌", "にせのうた"),
        Song("ballad", "Fake Song (Ballad Version)", null, "ballad", "偽の歌", "にせのうた"),
        Song("brain", "Brain", "脳", "", "脳", "のう"),
        Song("sakura", "Sakura", "さくら", "", "さくら", "サクラ"),
        // Its tag says "live", but it's no version: a song from a film.
        Song("theme", "Sakura (Theme from Live Forever)", "さくら", "", "さくら", "サクラ"),
        Song("alive", "Alive", null, "", "アライヴ", "アライブ")
    )

    private fun rowsOf(song: Song): List<Kind> = listOf(
        Kind("fit", song.base, ""),
        Kind("alt", song.alt, ""),
        Kind("single", "${song.base} (シングル・バージョン)", "single"),
        Kind("best", song.base, "", album = "Live Best"),
        Kind("budokan", song.base, "live", album = "Live at Budokan"),
        Kind("live", "${song.base} (ライヴ)", "live"),
        Kind("livejp", "${song.base} (ライブ)", "live"),
        Kind("short", "${song.base} (ショートバージョン)", "short"),
        Kind("speedup", "${song.base} (スピードアップバージョン)", "speed up"),
        Kind("ballad", "${song.base} (バラードバージョン)", "ballad"),
        // Tags in Latin letters that are no version word.
        Kind("tvedit", "${song.base} (TV Edit)", "tv edit"),
        Kind("shortlatin", "${song.base} (Short)", "short"),
        Kind("other", "別の歌", OTHER),
        // In Latin letters past ASCII, which main took for another script.
        Kind("latin", "Fake Søng", if (song.title == "Fake Song") "" else OTHER)
    ) + listOfNotNull(Kind("homophone", "ノー", OTHER).takeIf { song.japan == "脳" })

    private fun videosOf(song: Song): List<Kind?> = listOf(
        null,
        Kind("own", song.title, song.version),
        Kind("teaser", "${song.title} (Teaser)", "teaser", length = "0:30"),
        Kind("english", "${song.title} (English Version)", "english"),
        Kind("firsttake", "${song.title} (THE FIRST TAKE)", "first take")
    )

    /** Whether a row or video of [kind] is [song]: the same version, or the plain one of its single. */
    private fun isIt(kind: Kind, song: Song) = kind.version != OTHER && (kind.version == song.version || kind.version == "" && song.version == "single")

    /**
     * Every song of the matrix, with no rows, one or two, each of every kind, with one video of each kind or none, and
     * ChatGPT signed out, picking the right one it's shown, picking the first, saying none, or too slow: nothing opens
     * wrong where main opened nothing or the right one. What main opened is [MATRIX], written by this test on
     * main (see [onMain]).
     */
    @Test
    fun everyCellOfTheMatrixOpensNothingWrongThatMainDoesnt() {
        val opened = mutableListOf<String>()
        val main = if (onMain) emptyMap() else stored(MATRIX)
        val worse = mutableListOf<String>()
        val counts = sortedMapOf<String, Int>()
        for (song in songsOfTheMatrix) {
            val kinds = rowsOf(song)
            for (first in kinds + null) for (second in kinds + null) for (video in videosOf(song)) {
                if (first == null && second != null) continue
                val rows = listOfNotNull(first, second)
                val shownRows = rows.map { row(it.name, it.album, length = it.length) }
                val shownVideos = listOfNotNull(video).map { video(it.name, it.length, it.channel) }
                val right = rows.filter { isIt(it, song) }.map { row(it.name, it.album, length = it.length) } +
                    listOfNotNull(video).filter { isIt(it, song) }.map { video(it.name, it.length, it.channel) }
                val case = Case("", null, title = song.title, japan = song.japan?.let(::japanese), songs = shownRows, videos = shownVideos)
                val modes = listOf(
                    case, case.copy(chooses = { shown -> shown.indexOfFirst { it in right } }), case.copy(picked = 0),
                    case.copy(picked = SAYS_NONE), case.copy(picked = 0, slow = true)
                )
                val key = listOf(song.key, first?.key ?: "-", second?.key ?: "-", video?.key ?: "-").joinToString(" ")
                val results = modes.map(::found)
                val found = results.map { it.id ?: "-" }
                opened += "$key\t${found.joinToString(" ")}"
                if (onMain) continue
                val was = requireNotNull(main[key]) { key }
                worse += broken(key, modes, results, was.first().takeIf { it != "-" }, MODES)
                fun verdict(id: String) = when {
                    id == "-" -> "nothing"
                    isIt(if (id.startsWith("video")) requireNotNull(video) else rows[id.removePrefix("song").toInt()], song) -> "right"
                    else -> "wrong"
                }
                found.zip(was).forEachIndexed { mode, (now, then) ->
                    counts.merge("${MODES[mode]}: ${verdict(then)} -> ${verdict(now)}", 1, Int::plus)
                    if (verdict(now) == "wrong" && verdict(then) != "wrong") worse += "$key ${MODES[mode]}: $now, main $then"
                }
            }
        }
        written(MATRIX, opened)
        println("Main -> now, cells:\n" + counts.entries.joinToString("\n"))
        assertEquals("${worse.size} worse: " + worse.take(WORSE_SHOWN).joinToString("\n"), 0, worse.size)
    }

    /**
     * Made-up searches, the same each run (see [fuzzed]), each signed out, picking the first, picking another, saying
     * none and too slow, break no rule of [broken]. What main opened is [FUZZ], written by this test on main (see [onMain]).
     */
    @Test
    fun madeUpSearchesOpenWhatMainOpenedOrNothingButForAPick() {
        val main = if (onMain) emptyMap() else stored(FUZZ)
        val opened = mutableListOf<String>()
        val broken = mutableListOf<String>()
        val counts = sortedMapOf<String, Int>()
        fuzzed().forEachIndexed { index, modes ->
            val found = modes.map(::found)
            opened += "$index\t${found.joinToString(" ") { it.id ?: "-" }}"
            if (onMain) return@forEachIndexed
            val was = requireNotNull(main["$index"]) { "$index" }
            val key = "$index ${modes.first().title}: ${modes.first().songs} ${modes.first().videos}"
            broken += broken(key, modes, found, was.first().takeIf { it != "-" }, FUZZ_MODES)
            found.zip(was).forEachIndexed { mode, (now, then) ->
                counts.merge("${FUZZ_MODES[mode]}: ${if (then == "-") "nothing" else "opens"} -> ${if (now.id == then) "the same" else now.id ?: "nothing"}".replace(Regex("(song|video)\\d"), "$1"), 1, Int::plus)
            }
        }
        written(FUZZ, opened)
        println("Main -> now, made-up searches:\n" + counts.entries.joinToString("\n"))
        assertEquals("${broken.size} broken: " + broken.take(WORSE_SHOWN).joinToString("\n"), 0, broken.size)
    }

    /**
     * What breaks the rule for one search, made in each of [modes], the first signed out, that [found] what it did: without
     * a pick, what opens is what main opened, [mainOpened], or nothing, as only ChatGPT may say it's another; and what
     * opens signed out opens in every mode, without asking, as on main.
     */
    private fun broken(key: String, modes: List<Case>, found: List<Found>, mainOpened: String?, names: List<String>): List<String> {
        val alone = found.first().id
        return modes.zip(found).mapIndexedNotNull { mode, (case, now) ->
            val picked = now.answered.flatMap { shown -> ids(case.songs, "song", shown) + ids(case.videos, "video", shown) }
            when {
                now.id != null && now.id != mainOpened && now.id !in picked -> "$key, ${names[mode]}: ${now.id}, main $mainOpened"
                alone != null && now.id != alone -> "$key, ${names[mode]}: ${now.id}, but $alone signed out"
                else -> null
            }
        }
    }

    /** The ids of those of [rows] shown as [shown]. */
    private fun ids(rows: List<String>, kind: String, shown: String) = rows.indices.filter { rows[it] == shown }.map { "$kind$it" }

    /**
     * [FUZZ_CASES] made-up searches, the same each run, as the seed is: one of [FUZZ_SONGS], 1 to 4 rows and up to 2
     * videos, named from [FUZZ_NAMES], maybe with one of [FUZZ_TAGS], by the artist or another, on any album, as long as
     * the song or not; each in every one of [FUZZ_MODES].
     */
    private fun fuzzed(): List<List<Case>> {
        val random = kotlin.random.Random(FUZZ_SEED)
        fun <T> List<T>.drawn(): T = this[random.nextInt(size)]
        fun named(base: String) = base + if (random.nextBoolean()) FUZZ_TAGS.drawn() else ""
        return List(FUZZ_CASES) { index ->
            val (title, japan) = FUZZ_SONGS.drawn()
            val songs = List(1 + random.nextInt(FUZZ_ROWS)) {
                row(named(FUZZ_NAMES.drawn()), FUZZ_ALBUMS.drawn(), if (random.nextInt(5) == 0) "Other Band" else "Fake Band", FUZZ_LENGTHS.drawn())
            }
            val videos = List(random.nextInt(FUZZ_VIDEOS + 1)) {
                val name = named(if (random.nextBoolean()) title else FUZZ_NAMES.drawn())
                video((if (random.nextInt(4) == 0) "Fake Band - " else "") + name, FUZZ_LENGTHS.drawn(), FUZZ_CHANNELS.drawn())
            }
            val case = Case("fuzz $index", null, title = title, japan = japan?.let(::japanese), songs = songs, videos = videos)
            listOf(case, case.copy(picked = 0), case.copy(picked = 1 + random.nextInt(FUZZ_ROWS - 1)), case.copy(picked = SAYS_NONE), case.copy(picked = 0, slow = true))
        }
    }

    /** What main opened, kept as [written] wrote it on main: each line a key, then what opened in each mode. */
    private fun stored(name: String): Map<String, List<String>> = requireNotNull(javaClass.getResourceAsStream("/$name-main.tsv.gz")) { name }.use { stream ->
        java.util.zip.GZIPInputStream(stream).bufferedReader().readLines()
    }.filter { it.isNotBlank() }.associate { line -> line.substringBefore("\t") to line.substringAfter("\t").split(" ") }

    /** What opened, to keep in the test's resources when on main, or to look at otherwise. */
    private fun written(name: String, lines: List<String>) {
        val text = lines.joinToString("\n", postfix = "\n")
        if (onMain) {
            java.util.zip.GZIPOutputStream(java.io.File("build/$name-main.tsv.gz").outputStream()).use { it.write(text.toByteArray()) }
        } else {
            java.io.File("build/$name-now.tsv").writeText(text)
        }
    }

    private fun find(case: Case): String? = found(case).id

    /** What opened for a case, "song0" or null, and the rows ChatGPT [answered], each as it was shown them. */
    private class Found(val id: String?, val answered: List<String>)

    private fun found(case: Case): Found {
        // A fake of its own, as each keeps every request, and the matrix makes tens of thousands.
        val fake = FakeSpotify()
        fake.handler = { request ->
            val url = request.url
            fun results(vararg song: JSONObject) = FakeSpotify.html(request, JSONObject().put("results", JSONArray(song.toList())).toString())
            when {
                url.host == "itunes.apple.com" && url.encodedPath == "/search" ->
                    case.lengthMs?.let { results(appleSong(case.title, case.artist, case.album, it)) } ?: results()
                url.host == "itunes.apple.com" -> mapOf("JP" to case.japan, "KR" to case.korea)[url.queryParameter("country")]
                    ?.let { (title, artist) -> results(appleSong(title, artist, case.album, case.lengthMs ?: 0)) } ?: results()
                url.host == "api.deezer.com" -> FakeSpotify.html(request, JSONObject().put("data", JSONArray().apply {
                    case.deezerSeconds?.let { put(JSONObject().put("title", case.title).put("artist", JSONObject().put("name", case.artist)).put("duration", it)) }
                }).toString())
                else -> {
                    val videos = FakeSpotify.bodyOf(request).contains(VIDEOS)
                    val rows = (if (videos) case.videos else case.songs).mapIndexed { index, row -> page(row, id(if (videos) "video" else "song", index)) }
                    FakeSpotify.html(request, youTubeMusicPage(rows))
                }
            }
        }
        val answered = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pick: suspend (MusicMetadata, List<String>, Boolean) -> Int? = { _, rows, _ ->
            if (case.slow) delay(LATE_MS)
            (case.chooses?.invoke(rows) ?: case.picked)?.also { index -> rows.getOrNull(index)?.let(answered::add) }
        }
        val matcher = ExactMatcher(fake.client(), "AR", pick = pick, pickTimeoutMs = if (case.slow) SLOW_PICK_MS else PICK_MS)
        val url = runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, MusicMetadata(case.title, case.artist)) }
        return Found(url?.substringAfter("v=")?.trimEnd('x'), answered.toList())
    }

    private fun id(kind: String, index: Int) = "$kind$index".padEnd(11, 'x')

    private fun appleSong(title: String, artist: String, album: String, lengthMs: Long) = JSONObject()
        .put("trackId", 7).put("trackName", title).put("artistName", artist).put("collectionName", album).put("trackTimeMillis", lengthMs)

    /** A row, "name | details", as YouTube Music writes it. */
    private fun page(row: String, videoId: String): JSONObject {
        val (name, details) = row.split(" | ")
        fun column(text: String) = JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", text)))))
        return JSONObject().put("musicResponsiveListItemRenderer", JSONObject()
            .put("flexColumns", JSONArray().put(column(name)).put(column(details)))
            .put("playlistItemData", JSONObject().put("videoId", videoId)))
    }

    private fun youTubeMusicPage(rows: List<JSONObject>) = JSONObject().put("contents", JSONObject().put("tabbedSearchResultsRenderer", JSONObject().put("tabs", JSONArray().put(
        JSONObject().put("tabRenderer", JSONObject().put("content", JSONObject().put("sectionListRenderer", JSONObject().put("contents", JSONArray().put(
            JSONObject().put("musicShelfRenderer", JSONObject().put("contents", JSONArray(rows)))
        ))))))))).toString()

    private companion object {
        const val VIDEOS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="
        const val OTHER = "another song"
        val MODES = listOf("signed out", "picks the right one", "picks the first", "says none", "too slow")
        const val PICK_MS = 1_000L
        const val SLOW_PICK_MS = 1L
        const val MATRIX = "matcher-matrix"
        const val FUZZ = "matcher-fuzz"
        const val SAYS_NONE = -1
        const val FUZZ_SEED = 947
        const val FUZZ_CASES = 2_000
        const val FUZZ_ROWS = 4
        const val FUZZ_VIDEOS = 2
        val FUZZ_MODES = listOf("signed out", "picks the first", "picks another", "says none", "too slow")
        val FUZZ_SONGS = listOf(
            "Fake Song" to null, "Fake Song" to "偽の歌", "Ame" to "あめ", "Sakura" to "さくら", "Sakura (Theme from Live Forever)" to "さくら",
            "Live Forever" to "曲", "Fake Song (Live)" to "偽の歌 (Live)", "Brain" to "脳", "Alive" to null, "Fake Song (Ballad Version)" to null,
            "Love Song (Single Version)" to null
        )
        val FUZZ_NAMES = listOf(
            "偽の歌", "にせのうた", "ニセノウタ", "別の歌", "歌", "サクラ", "さくら", "桜", "アメ", "あめ", "飴", "Fake Song", "Fake Søng", "Canción Falsa",
            "ノー", "脳", "のう", "ライヴ・フォーエヴァー", "アライヴ", "曲", "曲 - Live", "Live Forever - 曲", "偽の歌 - Fake Song", "Sakura", "Ame"
        )
        val FUZZ_TAGS = listOf(
            " (ライブ)", " (Live)", " (ライヴ)", " (TV Edit)", " (Short)", " (バラードバージョン)", " (シングル・バージョン)", " (Remix)", " (Acoustic)",
            " (オフ・ヴォーカル)", " (インストラクター)", " - Live", " - From \"Live Forever\"", " (Teaser)", " (English Version)", " - English Version",
            " (日本語)", " (歌詞付き)", " (THE FIRST TAKE)", " (Official Video)", " [2019]"
        )
        val FUZZ_ALBUMS = listOf("Fake Album", "Live Best", "Live at Budokan", "Karaoke Picks")
        val FUZZ_LENGTHS = listOf("1:30", "1:32", "1:34", "0:30", "3:00")
        val FUZZ_CHANNELS = listOf("Fake Band", "Fan", "Fake Band - Topic")
        const val WORSE_SHOWN = 60
        const val LATE_MS = 60_000L
    }
}
