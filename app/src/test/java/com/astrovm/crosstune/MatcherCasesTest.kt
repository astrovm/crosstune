package com.astrovm.crosstune

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

    private val fake = FakeSpotify()

    private val checked: (Case) -> String? = Case::now

    /**
     * Fake Band's "Fake Song", 1:30, on "Fake Album", looked for on YouTube Music, where its search finds [songs]
     * and [videos], each "name | details". Apple has it, as long as [lengthMs], and names it [japan] in Japan's
     * store and [korea] in Korea's; with no [lengthMs], Deezer has it, [deezerSeconds] long. ChatGPT answers
     * [picked]: null when it can't say, as without it. What opens is "song0" for the first song, "video1" for
     * the second video, or null.
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
        val picked: Int? = null
    )

    private fun row(name: String, album: String = "Fake Album", artist: String = "Fake Band", length: String = "1:30") = "$name | $artist • $album • $length"

    private fun video(name: String, length: String = "1:30", channel: String = "Fake Band") = "$name | $channel • 1M views • $length"

    private fun japanese(title: String) = title to "Fake Band"

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
        Case("4 $kana $kanji", "song0", now = null, title = "Fake Song", japan = japanese(kana), songs = listOf(row(kanji)), picked = NONE_PICKED)
    } + listOf(
        // 4. Homophones, kana against kanji, as main: Issue #983.
        Case("4 あめ 飴", "song0", japan = japanese("あめ"), songs = listOf(row("飴")), picked = NONE_PICKED),
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
        // Another song by the artist as long, with nothing to say it isn't, opens as on main without ChatGPT (Issue #983), and not when it says none.
        angel.copy(what = "945 other song", main = "song0", now = "song0", lengthMs = 245_000, songs = listOf(row("魂のルフラン", "魂のルフラン", "Yoko Takahashi", "4:04"))),
        angel.copy(what = "945 other song asked", main = "song0", now = null, lengthMs = 245_000, songs = listOf(row("魂のルフラン", "魂のルフラン", "Yoko Takahashi", "4:04")), picked = NONE_PICKED),
        // 1. Two songs as long, without ChatGPT: either.
        angel.copy(what = "f1 two songs", main = "song0", now = null, songs = listOf(row("魂のルフラン", "B", "Yoko Takahashi", "4:05"), row("残酷な天使のテーゼ", "A", "Yoko Takahashi", "4:05"))),
        // 12. Written in full-width letters.
        Case("f12 full width", null, now = "song0", title = "Ｆａｋｅ Ｓｏｎｇ", songs = listOf(row("Fake Song")))
    )

    @Test
    fun everyInputTheReviewsTriedOpensWhatMainDidOrBetter() {
        val cases = third + second + first + before
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
        // The review of 61c74c9c, 5: longer words that hold a mark, which main took for one.
        for (name in listOf("Fake Song (インストール)", "Fake Song (ATV Sizes)", "Fake Song (ライフ)")) {
            assertEquals(name, false, SongNames.saysAnotherVersion(name, "Fake Song", "Fake Band"))
        }
    }

    private fun find(case: Case): String? {
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
        val matcher = ExactMatcher(fake.client(), "AR", pick = { _, _, _ -> case.picked })
        val url = runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, MusicMetadata(case.title, case.artist)) }
        return url?.substringAfter("v=")?.trimEnd('x')
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
    }
}
