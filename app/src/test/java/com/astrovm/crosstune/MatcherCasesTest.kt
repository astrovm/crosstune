package com.astrovm.crosstune

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowCloseGuard

/**
 * Holds the YouTube Music matcher to what `origin/main` opens, so a change that takes a right row away, or opens
 * a wrong one or nothing in its place, fails here first. Every made-up input the reviews of PR #947 tried, with
 * what main opened for it ([Case.main]); and a matrix and made-up searches, each in every one of [MODES], held to
 * what main opened for each, kept in the test's resources (see [onMain]).
 *
 * A change to the matcher that changes what opens has to make those again on its own code, with [onMain], and
 * show in its PR the counts the matrix and the searches print, main against it, as the judge here only guesses
 * which row is right.
 */
@RunWith(RobolectricTestRunner::class)
class MatcherCasesTest {

    /** Set on main, where the matrix and the made-up searches write what main opens, to be kept as [MATRIX] and [FUZZ]. */
    private val onMain = false

    /**
     * Each how many cells of the matrix and made-up searches run: one in [SAMPLED], as all of them take minutes, but all
     * where asked for, with -PmatcherFull, as CI does when the matcher, this test or what main opened changes, and
     * each week (see the matcher job in .github/workflows/ci.yml and mutants-weekly.yml), and when writing what main opens.
     */
    private val every = if (onMain || System.getProperty("crosstune.matcherFull") == "true") 1 else SAMPLED

    /** Set in PIT's runs, where each change that lives runs every test that reaches it: there the matrix and the made-up searches, over a minute, don't. */
    private val mutationRun = System.getProperty("crosstune.mutationRun") == "true"

    /**
     * The keys of the matrix's songs to look for, with -PmatcherSongs=plain,japan: all of them where none are named. So
     * what main opens can be written in parts, each in a Gradle run short enough, and the parts' lines put together in
     * the matrix's order.
     */
    private val only = System.getProperty("crosstune.matcherSongs").orEmpty().split(",").filter(String::isNotBlank).toSet()

    /**
     * Fake Band's "Fake Song", 1:30, on "Fake Album", looked for on YouTube Music, where its search finds [songs]
     * and [videos], each "name | details". The link says it's [linkMs] long, or not. Apple has it, as long as
     * [lengthMs], with its other [editions] of the same name, each an album and a length, and names it [japan] in
     * Japan's store, the song and the artist, and [korea] in Korea's; with no [lengthMs], Apple has nothing and
     * Deezer has it, [deezerSeconds] long. ChatGPT answers [picked]: null when it can't say, as without it, or too
     * late when [slow]. What opens is "song0" for the first song, "video1" for the second video, or null. Where
     * [main] opens a wrong row, [knownWrong] names the issue that holds it, so a fix for it reads as one, not as a break.
     */
    data class Case(
        val what: String,
        val main: String?,
        val title: String = "Fake Song",
        val artist: String = "Fake Band",
        val album: String = "Fake Album",
        val lengthMs: Long? = 90_000,
        val linkMs: Long? = null,
        val editions: List<Pair<String, Long>> = emptyList(),
        val deezerSeconds: Long? = null,
        val japan: Pair<String, String>? = null,
        val korea: Pair<String, String>? = null,
        val songs: List<String> = emptyList(),
        val videos: List<String> = emptyList(),
        val picked: Int? = null,
        val slow: Boolean = false,
        val chooses: ((List<String>) -> Int?)? = null,
        val knownWrong: Int? = null
    )

    private fun row(name: String, album: String = "Fake Album", artist: String = "Fake Band", length: String = "1:30") = "$name | $artist • $album • $length"

    private fun video(name: String, length: String = "1:30", channel: String = "Fake Band") = "$name | $channel • 1M views • $length"

    private fun japanese(title: String) = title to "Fake Band"

    // The tenth review, of 02d8288f: PR comment 6859673587. The shapes the harness couldn't see: the link's own length,
    // two editions of one name at Apple, the artist named in another script, and a version said without brackets.
    private val tenth = listOf(
        // 1. The link says how long it is, and no store has the song.
        Case("1 link alone", "video0", lengthMs = null, linkMs = 240_000, videos = listOf(video("Fake Song (演唱会)", "4:00"), video("Fake Song", "3:30"))),
        Case("1 link alone kanji", null, lengthMs = null, linkMs = 240_000, songs = listOf(row("偽の歌 (ライブ)", length = "4:00"), row("偽の歌", length = "3:30"))),
        Case("1 link alone latin", "song1", lengthMs = null, linkMs = 240_000, songs = listOf(row("Fake Song", length = "3:30"), row("Fake Song", length = "4:00"))),
        Case("1 link and apple", "video0", lengthMs = 240_000, linkMs = 240_000, videos = listOf(video("Fake Song (演唱会)", "4:00"), video("Fake Song", "3:30"))),
        // 2. Apple has two editions named alike, a live album's and the studio's, at other lengths. Main opens the studio's
        // row for the live one's link in Latin letters, and the live row for the studio's link: Issue #997.
        Case(
            "2 two editions", "song1", lengthMs = 240_000, linkMs = 240_000, album = "Live at Budokan", editions = listOf("Fake Album" to 210_000),
            songs = listOf(row("Fake Song - Live", "Live at Budokan", length = "4:00"), row("Fake Song", length = "3:30")), knownWrong = 997
        ),
        Case(
            "2 two editions kanji", "song0", lengthMs = 240_000, linkMs = 240_000, album = "Live at Budokan", editions = listOf("Fake Album" to 210_000),
            songs = listOf(row("偽の歌 (ライブ)", "Live at Budokan", length = "4:00"), row("偽の歌", length = "3:30"))
        ),
        Case(
            "2 two editions, the studio's linked", "song0", linkMs = 210_000, lengthMs = 240_000, editions = listOf("Fake Album" to 210_000),
            songs = listOf(row("偽の歌 (ライブ)", length = "4:00"), row("偽の歌", length = "3:30")), knownWrong = 997
        ),
        Case(
            "2 two editions, no link length", "song0", lengthMs = 240_000, editions = listOf("Fake Album" to 210_000),
            songs = listOf(row("偽の歌 (ライブ)", length = "4:00"), row("偽の歌", length = "3:30"))
        ),
        // 4. The artist named in Japan's store, and on the rows, in another script.
        Case("4 artist in kanji", "song0", japan = "偽の歌" to "偽バンド", songs = listOf(row("偽の歌", artist = "偽バンド"))),
        Case("4 artist in kanji, live first", "song1", japan = "偽の歌" to "偽バンド", songs = listOf(row("偽の歌 (ライブ)", artist = "偽バンド"), row("偽の歌", artist = "偽バンド"))),
        Case("4 artist in kanji, store latin", null, songs = listOf(row("偽の歌", artist = "偽バンド"))),
        Case("4 artist in kanji, video", "video0", japan = "偽の歌" to "偽バンド", videos = listOf(video("偽の歌", channel = "偽バンド"))),
        Case("4 artist in kanji, another's row", "song1", japan = "偽の歌" to "偽バンド", songs = listOf(row("偽の歌", artist = "Other Band"), row("偽の歌", artist = "偽バンド")))
    ) + listOf(
        // 3. A version or a part said without brackets, beside a tagged row and a plain one. Main opens the plain row
        // for a live link where it comes first, and a row of part 1 for part 2: Issue #997.
        Triple("Fake Song Live ver.", "偽の歌 (ライブ)", listOf("song0", "song0", "song2", "song1", null)),
        Triple("Fake Song: Live", "偽の歌 (ライブ)", listOf("song0", "song0", "song0", "song1", null)),
        Triple("Fake Song «Live»", "偽の歌 (ライブ)", listOf("song0", "song0", "song0", "song1", null)),
        Triple("Fake Song, Pt. 2", "偽の歌 Pt.2", listOf("song0", "song0", "song2", "song1", null))
    ).flatMap { (title, inJapan, main) ->
        listOf(
            Case("3 $title", main[0], title = title, songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌")), knownWrong = 997.takeIf { "Pt." in title }),
            Case("3 $title plain first", main[1], title = title, songs = listOf(row("偽の歌"), row("偽の歌 (ライブ)")), knownWrong = 997),
            Case("3 $title latin", main[2], title = title, songs = listOf(row("Fake Song (Live)"), row("Fake Song"), row(title))),
            Case("3 $title in japan", main[3], title = title, japan = japanese(inJapan), songs = listOf(row("偽の歌"), row(inJapan))),
            Case("3 $title video", main[4], title = title, videos = listOf(video("Fake Song (演唱会)"), video("Fake Song")))
        )
    }

    // The ninth review, of ab27254b: PR comment 6855452759. Each opened main's right row on main, and nothing, or a wrong
    // pick, on ab27254b. Each opens main's row, with a plain row of the same name beside it too, and ChatGPT unasked.
    private val ninth = listOf(
        // 1. A pick's tag: ChatGPT is never asked where main opens a row.
        Case("1 reprise", "song0", songs = listOf(row("偽の歌"), row("Fake Song (Reprise)")), picked = 1),
        Case("1 part 2", "song0", songs = listOf(row("偽の歌"), row("Fake Song (Part 2)")), picked = 1),
        Case("1 re-recorded", "song0", songs = listOf(row("偽の歌"), row("Fake Song (Re-Recorded)")), picked = 1),
        Case("1 year", "song0", songs = listOf(row("偽の歌"), row("Fake Song (2020)")), picked = 1),
        // 2. The song's tag missing from the row's name in another script, or the song's subtitle.
        Case("2 live in chinese", "song0", title = "Fake Song (Live)", songs = listOf(row("假歌 (现场版)"), row("假歌"))),
        Case("2 subtitle", "song0", title = "Fake Song (Piano Man)", japan = japanese("偽の歌"), songs = listOf(row("偽の歌"), row("偽の歌 (Piano Man)"))),
        // 3. One version said two ways.
        Case("3 en vivo", "song0", title = "Fake Song (En Vivo)", songs = listOf(row("偽の歌 (Live)"), row("偽の歌"))),
        Case("3 acustico", "song0", title = "Fake Song (Acústico)", songs = listOf(row("偽の歌 (Acoustic)"), row("偽の歌"))),
        Case("3 off vocal", "song0", title = "Fake Song (Instrumental)", songs = listOf(row("偽の歌 (Off Vocal)"), row("偽の歌")))
    ) + listOf("(prod. Live Crew)", "(Fake Band x Live Crew)", "[English Subtitles]", "(Lyrics in English)", "(アニメMV)").map { tag ->
        // 4. A version word in a tag that names no version.
        Case("4 $tag", "song0", songs = listOf(row("偽の歌 $tag"), row("偽の歌")))
    } + listOf(
        // 5. A credit of the song's in the row's tag.
        Case("5 credit", "song0", artist = "Fake Band & Live Crew", songs = listOf(row("偽の歌 (Live Crew)"), row("偽の歌"))),
        // 8. A language is no version: as on main, whichever it is.
        Case("8 english", "song0", songs = listOf(row("偽の歌 (English Version)"), row("偽の歌"))),
        Case("8 japanese", "song0", songs = listOf(row("偽の歌 (Japanese Version)"), row("偽の歌"))),
        Case("8 korean", "song0", songs = listOf(row("偽の歌 (Korean Version)"), row("偽の歌"))),
        // 9. More than a version in the tag: as on main, Issue #992.
        Case("9 budokan", "song0", songs = listOf(row("偽の歌 (Live from Budokan)"), row("偽の歌")), knownWrong = 992),
        Case("9 op size", "song0", songs = listOf(row("偽の歌 (OP Size)"), row("偽の歌")), knownWrong = 992)
    ).flatMap { case -> listOf(case, case.copy(what = "${case.what} picked", picked = case.songs.size - 1)) }

    // A row whose tag says a version, beside the same name with no tag: main's opens, whichever it is. PR #947 tried to
    // open the plain one in its place, and the tenth review found it opened a wrong row where main opened the right one.
    private val plain = listOf(
        Case("live", "song0", songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌")), knownWrong = 992),
        Case("live asked", "song0", songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌")), picked = 0, knownWrong = 992),
        Case("first plain", "song0", songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌"), row("偽の歌", "Other Album")), knownWrong = 992),
        Case("plain first", "song0", songs = listOf(row("偽の歌"), row("偽の歌 (Live)"))),
        Case("video", "video0", videos = listOf(video("Fake Song (演唱会)"), video("Fake Song"))),
        Case("video with no lengths", "video0", lengthMs = null, videos = listOf(video("Fake Song (演唱会)"), video("Fake Song"))),
        Case("video's length unknown", "video0", videos = listOf(video("Fake Song (演唱会)"), video("Fake Song", length = ""))),
        // The song with a tag, a tag that says more, a row that isn't plain or as close.
        Case("song's subtitle", "song0", japan = japanese("偽の歌 ～偽りの夜～"), songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌"))),
        Case("song's credit", "song0", title = "Fake Song feat. Live Crew", songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌"))),
        Case("tagged", "song0", songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌 (Single Version)"))),
        Case("other script", "song0", songs = listOf(row("偽の歌 (ライブ)"), row("にせのうた"))),
        Case("live album", "song0", songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌", "Live at Budokan"))),
        Case("live in name", "song0", songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌ライブ"))),
        Case("not as close", "song0", songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌", length = "1:31"))),
        Case("not as close though shorter", "song0", songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌", length = "1:28"))),
        Case("closer though longer", "song0", songs = listOf(row("偽の歌 (ライブ)", length = "1:27"), row("偽の歌", length = "1:32"))),
        Case("no length", null, lengthMs = null, deezerSeconds = 90, songs = listOf(row("偽の歌 (ライブ)", length = ""), row("偽の歌", length = ""))),
        // In Latin letters, main opens the plain one itself.
        Case("no lengths", "song1", lengthMs = null, songs = listOf(row("Fake Song (Live)"), row("Fake Song"))),
        Case("latin", "song1", songs = listOf(row("Fake Song (Live)"), row("Fake Song"))),
        Case("plain's length unknown", "song1", songs = listOf(row("Fake Song (Live)"), row("Fake Song", length = ""))),
        Case("plain's length unknown in kanji", "song0", songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌", length = "")))
    ).map { it.copy(what = "plain ${it.what}") }

    // The eighth review, of 3fdcf9b6: PR comment 6849140218. Each opened main's right row, and nothing there: "can't tell"
    // doubted it. Each opens main's row, signed out, and with ChatGPT, which isn't asked, set to pick another row.
    private val eighth = listOf(
        // 2. Romaji, a stroke, a translation.
        Case("2 ryuusei", "song0", title = "Ryuusei", songs = listOf(row("Ryūsei"))),
        Case("2 toukyou", "song0", title = "Toukyou", songs = listOf(row("Tōkyō"))),
        Case("2 soeren", "song0", title = "Soeren", songs = listOf(row("Søren"))),
        Case("2 shooting star", "song0", title = "Shooting Star", japan = japanese("流星"), songs = listOf(row("Ryūsei"))),
        // 3. Kana for kanji, a numeral, と for &, Pt for Part.
        Case("3 asu", "song0", title = "Asu no Uta", japan = japanese("明日の歌"), songs = listOf(row("あすの歌"))),
        Case("3 numeral", "song0", title = "Sankagetsu", japan = japanese("三ヶ月"), songs = listOf(row("3ヶ月"))),
        Case("3 and", "song0", title = "Kimi to Boku", japan = japanese("君と僕"), songs = listOf(row("君&僕"))),
        Case("3 part", "song0", title = "Fake Song Pt.2", japan = japanese("偽の歌 Pt.2"), songs = listOf(row("偽の歌 Part 2"))),
        // 4. ぢ for じ, a repeat mark.
        Case("4 chijimu", "song0", title = "Chijimu", japan = japanese("ちぢむ"), songs = listOf(row("ちじむ"))),
        Case("4 kokoro", "song0", title = "Kokoro", japan = japanese("こころ"), songs = listOf(row("こゝろ"))),
        // 5. What a show has it as: no version, whatever words it holds.
        Case("5 opening", "song0", songs = listOf(row("偽の歌 (TVアニメ「ライブ」オープニングテーマ)"))),
        Case("5 as reviewed", "song0", songs = listOf(row("偽の歌 (TVアニメ「偽物」オープニングテーマ)"))),
        Case("5 ending", "song0", songs = listOf(row("偽の歌 - TVアニメ「ライブ」エンディングテーマ"))),
        Case("5 op", "song0", songs = listOf(row("偽の歌 (アニメ「ライブ」OP)"))),
        Case("5 opening in english", "song0", songs = listOf(row("偽の歌 (TV Anime \"Live Forever\" Opening Theme)"))),
        Case("5 drama", "song0", songs = listOf(row("偽の歌 (ドラマ「ライブ」主題歌)"))),
        // 6. A mark in Hangul inside a longer word.
        Case("6 alive", "song0", title = "Alive", songs = listOf(row("얼라이브"))),
        Case("6 love drive", "song0", title = "Love Drive", songs = listOf(row("러브드라이브"))),
        Case("6 in a tag", "song0", title = "Alive", japan = japanese("偽の歌"), songs = listOf(row("偽の歌 (얼라이브)"))),
        // 7. A loanword in katakana.
        Case("7 ballade", "song0", title = "Ballade", songs = listOf(row("バラード"))),
        Case("7 television", "song0", title = "Television", songs = listOf(row("テレビ"))),
        // 8. A credit: who it's with.
        Case("8 with", "song0", songs = listOf(row("偽の歌 (with Live Crew)", artist = "Fake Band & Live Crew"))),
        Case("8 w/", "song0", songs = listOf(row("偽の歌 (w/ Live Crew)", artist = "Fake Band & Live Crew"))),
        Case("8 credited", "song0", songs = listOf(row("偽の歌 (Live Crew)", artist = "Fake Band & Live Crew")))
    ).flatMap { case ->
        // 1. ChatGPT is never asked, as main opens a row.
        listOf(case, case.copy(what = "${case.what} picked", songs = case.songs + row("別の歌") + row("Другая песня"), picked = 1))
    } + listOf(
        // 1. Main's row, of another version: never another song in its place, picked or not.
        Case("1 rightly doubted", "song0", songs = listOf(row("偽の歌 (Live)"), row("別の歌"), row("Другая песня")), picked = 1),
        Case("1 rightly doubted cyrillic", "song0", songs = listOf(row("偽の歌 (Live)"), row("別の歌"), row("Другая песня")), picked = 2),
        // 1. Nor a row named otherwise, picked or not: only the same name with the tag cut.
        Case("1 pick in its script", "song0", japan = japanese("にせのうた"), songs = listOf(row("偽の歌 (ライブ)"), row("ニセノウタ")), picked = 1)
    )

    // The seventh review, of dc6e8a5f: PR comment 6843592618. Each took away main's right row, signed out or for a wrong pick.
    private val seventh = listOf(
        // 1. Main's row wrongly doubted, ChatGPT's wrong pick opened. Now ChatGPT isn't asked where main opens a row.
        Case("1 tokyo", "song1", title = "Tokyo", japan = japanese("トーキョー"), songs = listOf(row("別の歌", length = "1:35"), row("とうきょう")), picked = 0),
        Case("1 muede", "song1", title = "Muede", songs = listOf(row("Other Song"), row("Müde")), picked = 0),
        Case("1 album version", "song0", songs = listOf(row("偽の歌 (Album Version)"), row("別の歌")), picked = 1),
        // 1. Main's row of another version, beside the same name without its tag: main's opens, whatever ChatGPT would pick.
        Case("1 pick passes", "song0", songs = listOf(row("偽の歌 (ライブ)"), row("偽の歌")), picked = 1),
        Case("1 pick fails", "song0", songs = listOf(row("偽の歌 (ライブ)"), row("別の歌 (Live)")), picked = 1),
        Case("1 pick another song", "song0", japan = japanese("偽の歌"), songs = listOf(row("偽の歌 (ライブ)"), row("別の歌")), picked = 1)
    ) + listOf(
        // 2. One name written two ways: a long vowel, ヴ, an old kanji, kana against kanji, German's "ue".
        Triple("Tokyo", "トーキョー", "とうきょう"), Triple("Sayonara", "さよなら", "さようなら"), Triple("Sakura", "桜", "櫻"),
        Triple("Asu", "明日", "あす"), Triple("Violin", "ヴァイオリン", "バイオリン"), Triple("Sakura", "さくら", "桜")
    ).map { (title, store, name) ->
        Case("2 $store $name", "song0", title = title, japan = japanese(store), songs = listOf(row(name)))
    } + listOf(
        Case("2 muede", "song0", title = "Muede", songs = listOf(row("Müde"))),
        // 5. A word no list has says no version: "Christmas" is "クリスマス".
        Case("5 christmas", "song0", title = "Fake Song (Christmas Version)", songs = listOf(row("偽の歌 (クリスマス・バージョン)"))),
        // 6. A subtitle a store leaves out, either way.
        Case("6 subtitle", "song0", japan = japanese("偽の歌"), songs = listOf(row("偽の歌 ～偽りの夜～"))),
        Case("6 subtitle in the store", "song0", japan = japanese("偽の歌 ～偽りの夜～"), songs = listOf(row("偽の歌")))
    ) + listOf("Album Version", "Single Version", "Original Version", "映画「ライブ・フォーエバー」主題歌", "Full Version").map { tag ->
        // 3 and 4. An edition, or what film it's the song of, says no version.
        Case("3 $tag", "song0", songs = listOf(row("偽の歌 ($tag)")))
    }

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
        Case("9 live half", "song1", title = "Live Forever", japan = japanese("曲"), songs = listOf(row("別の歌"), row("曲 - Live"))),
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
        Case("2 live half", "song1", title = "Live Forever", japan = japanese("曲"), songs = listOf(row("別の歌"), row("曲 - Live"))),
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
        Case("4 ライヴ", "song0", songs = listOf(row("偽の歌 (ライヴ)"))),
        Case("4 1999 ライヴ", "song0", title = "1999", songs = listOf(row("1999 (ライヴ)"))),
        Case("4 カヴァー", "song0", songs = listOf(row("偽の歌 (カヴァー)"))),
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
        Case("3 tv size first", "song0", songs = listOf(row("偽の歌 (TV Size)"), row("別の歌", "Another Album"))),
        // 4. What the song's names say together: "Single" in English, "バージョン" in Japanese.
        Case("4 single ver", "song0", title = "Fake Song (Single Version)", japan = japanese("偽の歌 (シングル・バージョン)"), songs = listOf(row("偽の歌 (Single ver.)"))),
        Case("4 speed-up in katakana", "song0", title = "Fake Song (Speed-Up Version)", songs = listOf(row("偽の歌（スピードアップバージョン）"))),
        // 6. Read alike, with widths folded and a long vowel as its vowel.
        Case("6 yuki", "song0", title = "Yuki", japan = japanese("ユーキ"), songs = listOf(row("勇気"))),
        Case("6 hanamizuki", "song0", title = "Hanamizuki", japan = japanese("ﾊﾅﾐｽﾞｷ"), songs = listOf(row("花水木"))),
        Case("6 tokyo", "song0", title = "Tokyo", japan = japanese("トーキョー"), songs = listOf(row("東京"))),
        // 7. "Xバージョン" is "X ver.".
        Case("7 tv", "song0", title = "Fake Song (Single Version)", songs = listOf(row("偽の歌 (TVバージョン)"))),
        // But not in a word no list has, which can't tell: the seventh review.
        Case("7 english", "song0", title = "Fake Song (Single Version)", songs = listOf(row("偽の歌 (英語バージョン)"))),
        Case("7 own", "song0", title = "Fake Song (Single Version)", japan = japanese("偽の歌 (シングル・バージョン)"), songs = listOf(row("偽の歌 (シングル・バージョン)"))),
        // 2. The first in the song's own script, named otherwise: it can't tell, so it opens as on main (the eighth review).
        Case("2 first in its own script", "song0", japan = japanese("偽物"), songs = listOf(row("別の歌"), row("Фальшивая песня"), row("Другая песня (Live)"))),
        // 2. A version kept out, the first opens as main had it, whichever song it is: Issue #983.
        Case("2 own first", "song0", songs = listOf(row("偽の歌"), row("別の歌 (Live)"))),
        Case("2 own after", "song0", songs = listOf(row("別の歌"), row("偽の歌 (Live)")), knownWrong = 983)
    ) +
        // 2. The first is the song on a compilation its album's name makes look like another version; main opened it.
        listOf("Live Best", "ライブ・ベスト", "Karaoke Hits", "Acoustic Covers Collection").map { album ->
            Case("2 $album", "song0", songs = listOf(row("偽の歌", album), row("別の歌", "Another Album")))
        } +
        // 5. Alone on one, as main opened it; and on a live album, the same as main, as no album's name tells the two apart.
        listOf("Live Best", "Studio Sessions", "Tribute", "Piano Ballads", "Instrumental Works", "Remix Best", "Live at Budokan").map { album ->
            Case("5 $album", "song0", songs = listOf(row("偽の歌", album)), knownWrong = 983.takeIf { album == "Live at Budokan" })
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
        // 4. Kana against kanji can't be told apart, and kanji that read alike may be one name: main's row opens, as
        // only what says it's another takes it away (the seventh review).
        Case("4 $kana $kanji", "song0", title = "Fake Song", japan = japanese(kana), songs = listOf(row(kanji)), picked = SAYS_NONE)
    } + listOf(
        // 4. Homophones, kana against kanji, as main: Issue #983.
        Case("4 あめ 飴", "song0", japan = japanese("あめ"), songs = listOf(row("飴")), picked = SAYS_NONE),
        // 5. "Live Forever" is "曲" in Japan, so "曲 (ライブ)" is a live one.
        Case("5 live forever", "video0", title = "Live Forever", japan = japanese("曲"), videos = listOf(video("曲"))),
        Case("5 live forever live", null, title = "Live Forever", japan = japanese("曲"), videos = listOf(video("曲 (ライブ)"))),
        // 5. The song's own album says "& Instrumental", not the row.
        Case("5 own album instrumental", "song0", album = "Naughty Boys & Instrumental", songs = listOf(row("偽の歌 (Instrumental)", "Naughty Boys & Instrumental"))),
        Case("5 own album", "song0", album = "Naughty Boys & Instrumental", songs = listOf(row("偽の歌", "Naughty Boys & Instrumental"))),
        // The artist's only song as long, in kana, with nothing to say it isn't: as main, Issue #983.
        Case("issue kimini mune kyun", "song0", title = "Kimini Mune Kyun", songs = listOf(row("さよなら")), knownWrong = 983)
    )

    private val angel = Case("", null, title = "A Cruel Angel's Thesis", artist = "Yoko Takahashi", lengthMs = 245_093)
    private val angelInJapan = angel.copy(japan = "残酷な天使のテーゼ" to "高橋洋子")
    private val springDay = Case("", null, title = "Spring Day", artist = "BTS", lengthMs = 274_000, korea = "봄날" to "방탄소년단")
    private val named = "残酷な天使のテーゼ - Zankoku na Tenshi no Thesis"

    // The first review, of d776d0f8.
    private val first = listOf("カラオケバージョン", "ピアノカバー", "ライブバージョン", "ギターソロ", "アコースティックカバー", "オルゴールアレンジ", "歌ってみたよ").map { tag ->
        // 1. A fan's video saying it's another version, with a word run on.
        angelInJapan.copy(what = "1 $tag", main = null, videos = listOf(video("残酷な天使のテーゼ / 高橋洋子 ($tag)", "4:06", "Fan")))
    } + listOf("피아노커버", "커버곡", "라이브영상", "노래방버전", "라이브클립").map { tag ->
        springDay.copy(what = "1 $tag", main = null, videos = listOf(video("봄날 - BTS ($tag)", "4:34", "Fan")))
    } + listOf(
        angelInJapan.copy(what = "1 fan video", main = "video0", videos = listOf(video("残酷な天使のテーゼ / 高橋洋子", "4:06", "Fan"))),
        springDay.copy(what = "1 spring day", main = "video0", videos = listOf(video("봄날 - BTS", "4:34", "Fan"))),
        // 2a. PR #797's song, with nothing to name it, and no ChatGPT.
        angel.copy(what = "2a pr 797", main = "song1", songs = listOf(row("Fake Song", "Fake Album", "Yoko Takahashi", "3:00"), row(named, "Zankoku na Tenshi no Thesis", "Yoko Takahashi", "4:06"))),
        // 2b. One name written two ways, with Deezer's length only.
        Case("2b xiaoxingyun", "song1", title = "小幸運", artist = "田馥甄", lengthMs = null, deezerSeconds = 266, songs = listOf(row("Fake Song", "Fake Album", "田馥甄", "3:00"), row("小幸运", "小幸运", "田馥甄", "4:26"))),
        Case("2b sakura", "song1", title = "さくら", lengthMs = null, deezerSeconds = 266, songs = listOf(row("Fake Song", length = "3:00"), row("桜", "桜", length = "4:26"))),
        Case("2b sakura kanji", "song1", title = "桜", lengthMs = null, deezerSeconds = 266, songs = listOf(row("Fake Song", length = "3:00"), row("さくら", "さくら", length = "4:26"))),
        // 3. A sign is no letters.
        Case("3 trademark", "song0", title = "Fake Song™", songs = listOf(row("Fake Song")))
    )

    // The review of 61c74c9c, PR comment 6812563715, and the defect the PR was opened for, Issue #945.
    private val before = listOf(
        // Another name may be the song's, translated: it can't tell, so it opens as on main (the eighth review, 2).
        Case("945 accent", "song0", artist = "Fake Singer", lengthMs = 245_000, songs = listOf(row("Canción Falsa", "Otro Álbum", "Fake Singer", "4:06"))),
        angel.copy(what = "945 karaoke", main = "song0", lengthMs = 245_000, songs = listOf(row("残酷な天使のテーゼ (オフ・ヴォーカル)", "残酷な天使のテーゼ", "Yoko Takahashi", "4:06"))),
        // Another song by the artist as long, with nothing to say it isn't, opens as on main, asked or not (Issue #983).
        angel.copy(what = "945 other song", main = "song0", lengthMs = 245_000, songs = listOf(row("魂のルフラン", "魂のルフラン", "Yoko Takahashi", "4:04")), knownWrong = 983),
        angel.copy(what = "945 other song asked", main = "song0", lengthMs = 245_000, songs = listOf(row("魂のルフラン", "魂のルフラン", "Yoko Takahashi", "4:04")), picked = SAYS_NONE, knownWrong = 983),
        // 1. Two songs as long, with no store name in Japanese to tell them apart: main's opens (Issue #983).
        angel.copy(what = "f1 two songs", main = "song0", songs = listOf(row("魂のルフラン", "B", "Yoko Takahashi", "4:05"), row("残酷な天使のテーゼ", "A", "Yoko Takahashi", "4:05")), knownWrong = 983),
        // 12. Written in full-width letters: nothing opens, as on main.
        Case("f12 full width", null, title = "Ｆａｋｅ Ｓｏｎｇ", songs = listOf(row("Fake Song")))
    )

    @Test
    fun everyInputTheReviewsTriedOpensWhatMainDid() {
        val cases = tenth + ninth + plain + eighth + seventh + sixth + fifth + fourth + third + second + first + before
        val wrong = cases.map { it to find(it) }.filter { (case, found) -> found != case.main }
            .map { (case, found) -> "${case.what}: ${case.main}, not $found" + (case.knownWrong?.let { ", where main is wrong: Issue #$it" } ?: "") }
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
    }

    /**
     * A song looked for in the matrix: its [title], its name in Japan's store, the [version] it is, its name in
     * another script [base], and the same name spelled otherwise, [alt]. One only [spelled] otherwise is looked for
     * with fewer videos, as the rows are what's new. Its [artist] is named so in Japan's store and on the rows and
     * videos; Apple has its other [editions] of the same name too, and its live rows are [liveLength] long; and the
     * rows of [others] are looked for with it alone.
     */
    private class Song(
        val key: String,
        val title: String,
        val japan: String?,
        val version: String,
        val base: String,
        val alt: String,
        val spelled: Boolean = false,
        val artist: String = "Fake Band",
        val editions: List<Pair<String, Long>> = emptyList(),
        val liveLength: String = "1:30",
        val others: List<Kind> = emptyList()
    )

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
        Song("alive", "Alive", null, "", "アライヴ", "アライブ"),
        // The ninth review's: a version in the song's tag, said otherwise by the rows, and a subtitle.
        Song("live", "Fake Song (Live)", null, "live", "偽の歌", "にせのうた"),
        Song("envivo", "Fake Song (En Vivo)", null, "live", "偽の歌", "にせのうた"),
        Song("pianoman", "Fake Song (Piano Man)", "偽の歌", "", "偽の歌", "にせのうた"),
        // The tenth review's: a version or a part said without brackets, the artist in another script, and two
        // editions at Apple, a live album's as long as the live rows, and the studio's.
        Song("livever", "Fake Song Live ver.", null, "live", "偽の歌", "にせのうた"),
        Song("colon", "Fake Song: Live", null, "live", "偽の歌", "にせのうた"),
        Song("guillemets", "Fake Song «Live»", null, "live", "偽の歌", "にせのうた"),
        Song("part2", "Fake Song, Pt. 2", "偽の歌 Pt.2", "", "偽の歌 Pt.2", "偽の歌 Part 2", others = listOf(Kind("part1", "偽の歌", OTHER))),
        Song("jpartist", "Fake Song", "偽の歌", "", "偽の歌", "にせのうた", artist = "偽バンド"),
        Song("editions", "Fake Song", "偽の歌", "", "偽の歌", "にせのうた", editions = listOf("Live at Budokan" to 240_000), liveLength = "4:00")
    ) + listOf(
        // The eighth review's: one song written otherwise, by romaji, a stroke, a translation, kana for kanji, a numeral,
        // & for と, Part for Pt, じ for ぢ, a repeat mark, a loanword, Hangul.
        Song("ryusei", "Ryuusei", null, "", "Ryūsei", "Shooting Star", spelled = true),
        Song("toukyou", "Toukyou", "東京", "", "Tōkyō", "とうきょう", spelled = true),
        Song("soren", "Soeren", null, "", "Søren", "Soren", spelled = true),
        Song("asu", "Asu no Uta", "明日の歌", "", "あすの歌", "Tomorrow Song", spelled = true),
        Song("months", "Sankagetsu", "三ヶ月", "", "3ヶ月", "Three Months", spelled = true),
        Song("kimiboku", "Kimi to Boku", "君と僕", "", "君&僕", "You and Me", spelled = true),
        Song("part", "Fake Song Pt.2", "偽の歌 Pt.2", "", "偽の歌 Part 2", "偽の歌 Ⅱ", spelled = true),
        Song("chijimu", "Chijimu", "ちぢむ", "", "ちじむ", "チヂム", spelled = true),
        Song("kokoro", "Kokoro", "こころ", "", "こゝろ", "ココロ", spelled = true),
        Song("ballade", "Ballade", null, "", "バラード", "Ballade", spelled = true),
        Song("lovedrive", "Love Drive", null, "", "러브드라이브", "ラブドライブ", spelled = true),
        Song("alivekr", "Alive", null, "", "얼라이브", "アライヴ", spelled = true)
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
        // The eighth review's: what a show has it as, who it's with, a run of marks in Hangul, another song in Cyrillic.
        Kind("tiein", "${song.base} (TVアニメ「ライブ」オープニングテーマ)", ""),
        Kind("tiedash", "${song.base} - TVアニメ「ライブ」エンディングテーマ", ""),
        Kind("with", "${song.base} (with Live Crew)", ""),
        Kind("hangul", "${song.base} (라이브버전)", "live"),
        Kind("cyrillic", "Другая песня", OTHER),
        // In Latin letters past ASCII, which main took for another script.
        Kind("latin", "Fake Søng", if (song.title == "Fake Song") "" else OTHER)
    ) + listOfNotNull(Kind("homophone", "ノー", OTHER).takeIf { song.japan == "脳" }) + song.others + listOf(
        // The ninth review's: a version in Chinese and in Spanish, synonyms, a version word in a tag that names none, a pick's tag.
        Kind("chinese", "${song.base} (现场版)", "live"),
        Kind("envivo", "${song.base} (En Vivo)", "live"),
        Kind("latinlive", "${song.base} (Live)", "live"),
        Kind("acoustic", "${song.base} (Acoustic)", "acoustic"),
        Kind("offvocal", "${song.base} (Off Vocal)", "instrumental"),
        Kind("prod", "${song.base} (prod. Live Crew)", ""),
        Kind("subs", "${song.base} [English Subtitles]", ""),
        Kind("mv", "${song.base} (アニメMV)", ""),
        Kind("reprise", "${song.base} (Reprise)", "reprise")
    ).takeIf { !song.spelled }.orEmpty()

    private fun videosOf(song: Song): List<Kind?> = listOf(
        null,
        Kind("own", song.title, song.version),
        Kind("teaser", "${song.title} (Teaser)", "teaser", length = "0:30"),
        Kind("english", "${song.title} (English Version)", "english"),
        Kind("firsttake", "${song.title} (THE FIRST TAKE)", "first take")
    ).take(if (song.spelled) 2 else 5)

    /** Whether a row or video of [kind] is [song]: the same version, or the plain one of its single. */
    private fun isIt(kind: Kind, song: Song) = kind.version != OTHER && (kind.version == song.version || kind.version == "" && song.version == "single")

    /**
     * Every song of the matrix, with no rows, one or two, each of every kind, with one video of each kind or none, in each
     * of [MODES]: nothing breaks the rules of [broken], against what main opened, [MATRIX], written by this test on main
     * (see [onMain]). So main's right row never gives way to a wrong one or to nothing; how each cell is judged (see
     * [isIt]), main against now, is only counted, for the PR that changes what opens.
     */
    @Test
    fun everyCellOfTheMatrixOpensWhatMainDid() {
        assumeFalse("Not in a mutation run", mutationRun)
        val opened = mutableListOf<String>()
        val main = if (onMain) emptyMap() else stored(MATRIX)
        val worse = mutableListOf<String>()
        val counts = sortedMapOf<String, Int>()
        var cells = 0
        val drawn = songsOfTheMatrix.flatMap { song ->
            val kinds = rowsOf(song)
            (kinds + null).flatMap { first -> (kinds + null).flatMap { second -> videosOf(song).map { video -> Triple(first, second, video) } } }
                .filter { (first, second) -> (first != null || second == null) && cells++ % every == 0 && (only.isEmpty() || song.key in only) }
                .map { song to it }
        }
        assertEquals("Songs -PmatcherSongs names that the matrix doesn't have", emptySet<String>(), only - songsOfTheMatrix.map { it.key }.toSet())
        assertTrue("No cell of the matrix drawn", drawn.isNotEmpty())
        val searched = drawn.inParallel { (song, cell) ->
            val (first, second, video) = cell
            val rows = listOfNotNull(first, second)
            fun length(kind: Kind) = if (kind.version == "live") song.liveLength else kind.length
            fun shownRow(kind: Kind) = row(kind.name, kind.album, song.artist, length(kind))
            fun shownVideo(kind: Kind) = video(kind.name, length(kind), song.artist)
            val right = rows.filter { isIt(it, song) }.map(::shownRow) + listOfNotNull(video).filter { isIt(it, song) }.map(::shownVideo)
            val case = Case(
                "", null, title = song.title, japan = song.japan?.let { it to song.artist }, editions = song.editions, songs = rows.map(::shownRow),
                videos = listOfNotNull(video).map(::shownVideo)
            )
            val modes = listOf(
                case, case.copy(chooses = { shown -> shown.indexOfFirst { it in right } }), case.copy(picked = 0),
                case.copy(chooses = { shown -> shown.indexOfFirst { it !in right } }), case.copy(picked = SAYS_NONE), case.copy(picked = 0, slow = true),
                alone(case)
            )
            modes.map(::found)
        }
        for ((at, results) in drawn.zip(searched)) {
            val (song, cell) = at
            val (first, second, video) = cell
            val rows = listOfNotNull(first, second)
            val key = listOf(song.key, first?.key ?: "-", second?.key ?: "-", video?.key ?: "-").joinToString(" ")
            opened += "$key\t${results.joinToString(" ") { it.id ?: "-" }}"
            if (onMain) continue
            val was = requireNotNull(main[key]) { key }.map { it.takeIf { id -> id != "-" } }
            worse += broken(key, results, was)
            fun verdict(id: String?) = when {
                id == null -> NOTHING
                isIt(if (id.startsWith("video")) requireNotNull(video) else rows[id.removePrefix("song").toInt()], song) -> RIGHT
                else -> WRONG
            }
            results.zip(was).forEachIndexed { mode, (now, then) ->
                val (before, after) = verdict(then) to verdict(now.id)
                counts.merge("${MODES[mode]}: $before -> $after", 1, Int::plus)
            }
        }
        written(MATRIX, opened)
        println("Main -> now, cells:\n" + counts.entries.joinToString("\n"))
        assertEquals("${worse.size} worse: " + worse.take(WORSE_SHOWN).joinToString("\n"), 0, worse.size)
    }

    /**
     * Made-up searches, the same each run (see [fuzzed]), each in every one of [MODES], break no rule of [broken], against
     * what main opened, [FUZZ], written by this test on main (see [onMain]); and as the search knows which it made right
     * (see [Fuzzed.verdict]), how each is judged, main against now, is counted, for the PR that changes what opens.
     */
    @Test
    fun madeUpSearchesOpenWhatMainDid() {
        assumeFalse("Not in a mutation run", mutationRun)
        val main = if (onMain) emptyMap() else stored(FUZZ)
        val opened = mutableListOf<String>()
        val broken = mutableListOf<String>()
        val counts = sortedMapOf<String, Int>()
        val drawn = fuzzed().withIndex().filter { it.index % every == 0 }
        val searched = drawn.inParallel { it.value.modes.map(::found) }
        drawn.zip(searched).forEach { (indexed, found) ->
            val (index, fuzzed) = indexed
            opened += "$index\t${found.joinToString(" ") { it.id ?: "-" }}"
            if (onMain) return@forEach
            val was = requireNotNull(main["$index"]) { "$index" }.map { it.takeIf { id -> id != "-" } }
            val key = "$index ${fuzzed.song.title}: ${fuzzed.modes.first().songs} ${fuzzed.modes.first().videos}"
            broken += broken(key, found, was)
            found.zip(was).forEachIndexed { mode, (now, then) ->
                val (before, after) = fuzzed.verdict(then) to fuzzed.verdict(now.id)
                counts.merge("${MODES[mode]}: $before -> $after", 1, Int::plus)
            }
        }
        written(FUZZ, opened)
        println("Main -> now, made-up searches:\n" + counts.entries.joinToString("\n"))
        assertEquals("${broken.size} broken: " + broken.take(WORSE_SHOWN).joinToString("\n"), 0, broken.size)
    }

    /**
     * What breaks the rules for one search, [found] in each of [MODES], where main opened [was] in each, with no judge of
     * which row is right: what opens in each mode is main's, and where main opened a row signed out, ChatGPT isn't asked.
     */
    private fun broken(key: String, found: List<Found>, was: List<String?>): List<String> = found.indices.mapNotNull { mode ->
        val (now, then, signedOut) = Triple(found[mode], was[mode], was[SIGNED_OUT[mode]])
        when {
            now.id != then -> "$key, ${MODES[mode]}: ${now.id ?: "nothing"}, main ${then ?: "nothing"}"
            signedOut != null && now.asked -> "$key, ${MODES[mode]}: ChatGPT asked, but main opened $signedOut without"
            else -> null
        }
    }

    /** A song written many ways: its [title], its name in [japan]'s store, which song it is, [key], and [others] it goes by. */
    private class Spelled(val title: String, val japan: String?, val key: String, vararg val others: String) {
        /** Every way it's named: each name, and each written otherwise (see [spellings]). */
        fun names(): List<String> = (listOfNotNull(title, japan) + others).flatMap { listOf(it) + spellings(it) }.distinct()
    }

    /**
     * A song the made-up searches look for: its [title], its name in Japan's store, which song it is, [name], its
     * [version], and its artist's name in Japan's store, [japanArtist].
     */
    private class FuzzSong(val title: String, val japan: String?, val name: String, val version: String = "", val japanArtist: String = "Fake Band")

    /** A name a row or a video is given, [text], and the song it names, [name], in its [version]. */
    private class FuzzName(val text: String, val name: String, val version: String = "")

    /**
     * A row or a video made up, as it's [shown], naming the song [name], the versions its name, tag and album [said], and
     * whether a tag said what can't be told, [unknown], and whether it's [off]: by another artist, or not as long. One a
     * fan put up may be anything.
     */
    private class Drawn(val shown: String, val name: String, val said: Set<String>, val unknown: Boolean, val off: Boolean, val fan: Boolean = false)

    /** A made-up search for [song], in each of [modes], with the [rows] and [videos] it finds. */
    private class Fuzzed(val song: FuzzSong, val modes: List<Case>, val rows: List<Drawn>, val videos: List<Drawn>) {
        /** Whether what opened, "song0", "video1" or null, is the song: [RIGHT], [WRONG], [UNKNOWN] or [NOTHING]. */
        fun verdict(id: String?): String {
            if (id == null) return NOTHING
            val drawn = if (id.startsWith("video")) videos[id.removePrefix("video").toInt()] else rows[id.removePrefix("song").toInt()]
            return when {
                drawn.off || drawn.name != song.name -> WRONG
                // A version said, and none of them the song's: two versions, neither the song's, is wrong too.
                drawn.said.isNotEmpty() && song.version !in drawn.said -> WRONG
                drawn.unknown || drawn.said.size > 1 || drawn.fan -> UNKNOWN
                // Nothing said, of a song that's a version: the plain one.
                drawn.said.isEmpty() && song.version.isNotEmpty() -> WRONG
                else -> RIGHT
            }
        }
    }

    /**
     * [FUZZ_CASES] made-up searches, the same each run, as the seed is: one of [FUZZ_SONGS], maybe with another edition
     * at Apple, [FUZZ_EDITION_LENGTH] long, and a link that says how long it is or not; 1 to 4 rows and up to 2 videos,
     * named from [FUZZ_NAMES], maybe with one of [FUZZ_TAGS], by the artist, by the artist named in Japanese, or by
     * another, on any album, as long as the song or not; each in every one of [MODES].
     */
    private fun fuzzed(): List<Fuzzed> {
        val random = kotlin.random.Random(FUZZ_SEED)
        fun <T> List<T>.drawn(): T = this[random.nextInt(size)]
        fun tag(): Pair<String, String?>? = if (random.nextBoolean()) FUZZ_TAGS.drawn() else null
        return List(FUZZ_CASES) { index ->
            val song = FUZZ_SONGS.drawn()
            val edition = if (random.nextInt(3) == 0) FUZZ_EDITION_ALBUMS.drawn() to lengthMs(FUZZ_EDITION_LENGTH) else null
            val link = if (random.nextBoolean()) lengthMs(FUZZ_LENGTHS.drawn()) else null
            // A length no recording has, but where Apple has an edition as long.
            fun off(length: String) = length in FUZZ_OFF && (edition == null || length != FUZZ_EDITION_LENGTH)
            val rows = List(1 + random.nextInt(FUZZ_ROWS)) {
                val name = FUZZ_NAMES.drawn()
                val tag = tag()
                val (album, artist, length) = Triple(FUZZ_ALBUMS.drawn(), FUZZ_ARTISTS.drawn(), FUZZ_LENGTHS.drawn())
                val said = versionsOf(name.version, tag?.second, FUZZ_ALBUMS_SAY[album])
                // The artist in Japanese, where no store says so, may be the artist or not.
                val unknown = tag != null && tag.second == null || artist == JAPANESE_ARTIST && song.japanArtist != JAPANESE_ARTIST
                Drawn(row(name.text + tag?.first.orEmpty(), album, artist, length), name.name, said, unknown, artist == "Other Band" || off(length))
            }
            val videos = List(random.nextInt(FUZZ_VIDEOS + 1)) {
                val name = if (random.nextBoolean()) FuzzName(song.title, song.name, song.version) else FUZZ_NAMES.drawn()
                val tag = tag()
                val (byArtist, length, channel) = Triple(random.nextInt(4) == 0, FUZZ_LENGTHS.drawn(), FUZZ_CHANNELS.drawn())
                val said = versionsOf(name.version, tag?.second)
                val shown = video((if (byArtist) "Fake Band - " else "") + name.text + tag?.first.orEmpty(), length, channel)
                Drawn(shown, name.name, said, tag != null && tag.second == null, off(length), fan = channel == "Fan")
            }
            val case = Case(
                "fuzz $index", null, title = song.title, japan = song.japan?.let { it to song.japanArtist }, linkMs = link, editions = listOfNotNull(edition),
                songs = rows.map(Drawn::shown), videos = videos.map(Drawn::shown)
            )
            val judged = Fuzzed(song, emptyList(), rows, videos)
            val right = rows.indices.filter { judged.verdict("song$it") == RIGHT }.map { rows[it].shown } +
                videos.indices.filter { judged.verdict("video$it") == RIGHT }.map { videos[it].shown }
            val modes = listOf(
                case, case.copy(chooses = { shown -> shown.indexOfFirst { it in right } }), case.copy(picked = 0),
                case.copy(picked = 1 + random.nextInt(FUZZ_ROWS - 1)), case.copy(picked = SAYS_NONE), case.copy(picked = 0, slow = true),
                alone(case)
            )
            Fuzzed(song, modes, rows, videos)
        }
    }

    /** How long "1:30" is. */
    private fun lengthMs(length: String): Long = length.split(":").let { (minutes, seconds) -> (minutes.toLong() * 60 + seconds.toLong()) * MS }

    /** The versions [said] by a name, a tag and an album, each null or "" where it says none. */
    private fun versionsOf(vararg said: String?): Set<String> = said.filterNotNull().filter(String::isNotEmpty).toSet()

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

    /**
     * [case] as the link alone tells it, signed out: its length, the song's or Deezer's where it says none, and no
     * store has the song, so the matcher has only the link's name, artist and length to go by.
     */
    private fun alone(case: Case) =
        case.copy(lengthMs = null, deezerSeconds = null, editions = emptyList(), linkMs = case.linkMs ?: case.lengthMs ?: case.deezerSeconds?.times(MS) ?: LINK_MS)

    /**
     * [work] on each, on every core at once, as each search is its own; in order. A few thousand at a time: OkHttp
     * opens a CloseGuard for each call and leaves it to the garbage collector, but Robolectric keeps every open one
     * until the test ends, and the full matrix's 600,000 searches filled the heap with them.
     */
    private fun <T, R> List<T>.inParallel(work: (T) -> R): List<R> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        try {
            return chunked(AT_A_TIME).flatMap { chunk -> chunk.map { pool.submit<R> { work(it) } }.map { it.get() }.also { ShadowCloseGuard.reset() } }
        } finally {
            pool.shutdownNow()
        }
    }

    /** What opened for a case, "song0" or null, and whether ChatGPT was [asked]. */
    private class Found(val id: String?, val asked: Boolean)

    private fun found(case: Case): Found {
        // A fake of its own, as each keeps every request, and the matrix makes tens of thousands.
        val fake = FakeSpotify()
        fake.handler = { request ->
            val url = request.url
            fun results(vararg song: JSONObject) = FakeSpotify.html(request, JSONObject().put("results", JSONArray(song.toList())).toString())
            when {
                url.host == "itunes.apple.com" && url.encodedPath == "/search" ->
                    case.lengthMs?.let { length ->
                        results(appleSong(case.title, case.artist, case.album, length), *case.editions.map { (album, ms) -> appleSong(case.title, case.artist, album, ms) }.toTypedArray())
                    } ?: results()
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
        val asked = java.util.concurrent.atomic.AtomicBoolean()
        val pick: suspend (MusicMetadata, List<String>, Boolean) -> Int? = { _, rows, _ ->
            asked.set(true)
            if (case.slow) delay(LATE_MS)
            case.chooses?.invoke(rows) ?: case.picked
        }
        val matcher = ExactMatcher(fake.client(), "AR", pick = pick, pickTimeoutMs = if (case.slow) SLOW_PICK_MS else PICK_MS)
        val url = runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, MusicMetadata(case.title, case.artist, lengthMs = case.linkMs)) }
        return Found(url?.substringAfter("v=")?.trimEnd('x'), asked.get())
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
        /**
         * [name] written otherwise, one way at a time, as stores and uploaders write the same song: romaji with a macron
         * or without the long vowel, a stroke or a joined letter, katakana for hiragana and back, a numeral, & for と and
         * "and", Part for Pt, じ for ぢ and ず for づ, and a repeat mark.
         */
        fun spellings(name: String): List<String> = listOf(
            name.replace("ou", "ō"), name.replace("ou", "o"), name.replace("uu", "ū"), name.replace("uu", "u"),
            name.replace("oe", "ø"), name.replace("ae", "æ"), name.replace("Ae", "Æ"),
            name.map { if (it in 'ぁ'..'ゖ') it + 0x60 else it }.joinToString(""), name.map { if (it in 'ァ'..'ヶ') it - 0x60 else it }.joinToString(""),
            name.replace("三", "3"), name.replace("3", "三"), name.replace("2", "Ⅱ"), name.replace("2", "二"), name.replace("ヶ", "か"),
            name.replace("と", "&"), name.replace(" and ", " & "), name.replace("Pt.", "Part "), name.replace('ぢ', 'じ'), name.replace('づ', 'ず'),
            repeatMarked(name)
        ).filter { it != name }

        /** [name] with a kana written again, or again with its sound mark, as a repeat mark: "こころ" as "こゝろ", "いすず" as "いすゞ". */
        fun repeatMarked(name: String): String = name.indices.drop(1).firstOrNull { at ->
            name[at] in 'ぁ'..'ゖ' && (name[at] == name[at - 1] || java.text.Normalizer.normalize("${name[at - 1]}\u3099", java.text.Normalizer.Form.NFC) == "${name[at]}")
        }?.let { at -> name.substring(0, at) + (if (name[at] == name[at - 1]) 'ゝ' else 'ゞ') + name.substring(at + 1) } ?: name

        const val VIDEOS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="
        const val OTHER = "another song"
        /** Each way the matrix and the made-up searches are asked: signed out, then as ChatGPT answers. */
        val MODES = listOf("signed out", "picks the right one", "picks the first", "picks another", "says none", "too slow", "link alone")

        /** The mode each is signed out in: the first, but for the link alone, which is its own. */
        val SIGNED_OUT = listOf(0, 0, 0, 0, 0, 0, 6)

        /** How long the link says the song is, where nothing else says. */
        const val LINK_MS = 90_000L

        const val PICK_MS = 1_000L
        const val SLOW_PICK_MS = 1L
        const val MATRIX = "matcher-matrix"
        const val FUZZ = "matcher-fuzz"
        const val SAYS_NONE = -1
        const val MS = 1_000L
        const val FUZZ_SEED = 947
        const val FUZZ_CASES = 4_000
        const val SAMPLED = 20
        const val AT_A_TIME = 2_000
        const val FUZZ_ROWS = 4
        const val FUZZ_VIDEOS = 2
        /**
         * Songs whose names are written many ways, each the same song (the eighth review): its title, its name in Japan's
         * store, which song it is, and its other names, a translation or a reading, that no rule spells out.
         */
        val SPELLED = listOf(
            Spelled("Ryuusei", "流星", "ryusei", "Shooting Star", "りゅうせい"), Spelled("Toukyou", "東京", "tokyo", "とうきょう"),
            Spelled("Soeren", null, "soren"), Spelled("Daemon", null, "daemon"), Spelled("Cancion Falsa", null, "cancion", "Canción Falsa"),
            Spelled("Asu no Uta", "明日の歌", "asunouta", "あすの歌", "Tomorrow Song"), Spelled("Sankagetsu", "三ヶ月", "months", "Three Months"),
            Spelled("Kimi to Boku", "君と僕", "kimiboku", "You and Me"), Spelled("Fake Song Pt.2", "偽の歌 Pt.2", "fake2"),
            Spelled("Chijimu", "ちぢむ", "chijimu"), Spelled("Tsuzuku", "つづく", "tsuzuku"), Spelled("Kokoro", "こころ", "kokoro", "心"),
            Spelled("Isuzu", "いすず", "isuzu"), Spelled("Ballade", null, "ballade", "バラード"), Spelled("Television", null, "television", "テレビ"),
            Spelled("Alive", null, "alive", "얼라이브", "アライヴ"), Spelled("Love Drive", null, "lovedrive", "러브드라이브", "ラブドライブ"),
            Spelled("Live Wire", null, "livewire", "Livewire")
        )

        val FUZZ_SONGS = listOf(
            FuzzSong("Fake Song", null, "fake"), FuzzSong("Fake Song", "偽の歌", "fake"), FuzzSong("Ame", "あめ", "ame"), FuzzSong("Sakura", "さくら", "sakura"),
            FuzzSong("Sakura (Theme from Live Forever)", "さくら", "sakura"), FuzzSong("Live Forever", "曲", "forever"),
            FuzzSong("Fake Song (Live)", "偽の歌 (Live)", "fake", "live"), FuzzSong("Brain", "脳", "brain"), FuzzSong("Alive", null, "alive"),
            FuzzSong("Fake Song (Ballad Version)", null, "fake", "ballad"), FuzzSong("Love Song (Single Version)", null, "love"),
            // The seventh review's: one name written two ways, a subtitle, and a version no list has.
            FuzzSong("Tokyo", "トーキョー", "tokyo"), FuzzSong("Muede", null, "muede"), FuzzSong("Sayonara", "さよなら", "sayonara"),
            FuzzSong("Asu", "明日", "asu"), FuzzSong("Violin", "ヴァイオリン", "violin"), FuzzSong("Fake Song", "偽の歌 ～偽りの夜～", "fake"),
            FuzzSong("Fake Song (Christmas Version)", null, "fake", "christmas"),
            // The ninth review's: a version said otherwise than the rows say it, and a subtitle.
            FuzzSong("Fake Song (En Vivo)", null, "fake", "live"), FuzzSong("Fake Song (Acústico)", null, "fake", "acoustic"),
            FuzzSong("Fake Song (Instrumental)", null, "fake", "instrumental"), FuzzSong("Fake Song (Piano Man)", "偽の歌", "fake"),
            // The tenth review's: a version or a part said without brackets, and the artist named in Japanese in Japan's store.
            FuzzSong("Fake Song Live ver.", null, "fake", "live"), FuzzSong("Fake Song: Live", null, "fake", "live"),
            FuzzSong("Fake Song «Live»", null, "fake", "live"), FuzzSong("Fake Song, Pt. 2", "偽の歌 Pt.2", "fake2"),
            FuzzSong("Fake Song", "偽の歌", "fake", japanArtist = JAPANESE_ARTIST)
        ) + SPELLED.map { FuzzSong(it.title, it.japan, it.key) }
        val FUZZ_NAMES = listOf(
            FuzzName("偽の歌", "fake"), FuzzName("にせのうた", "fake"), FuzzName("ニセノウタ", "fake"), FuzzName("別の歌", "other"), FuzzName("歌", "other"),
            FuzzName("サクラ", "sakura"), FuzzName("さくら", "sakura"), FuzzName("桜", "sakura"), FuzzName("アメ", "ame"), FuzzName("あめ", "ame"),
            FuzzName("飴", "candy"), FuzzName("Fake Song", "fake"), FuzzName("Fake Søng", "fake"), FuzzName("Canción Falsa", "other"),
            FuzzName("ノー", "no"), FuzzName("脳", "brain"), FuzzName("のう", "brain"), FuzzName("ライヴ・フォーエヴァー", "forever"),
            FuzzName("アライヴ", "alive"), FuzzName("曲", "forever"), FuzzName("曲 - Live", "forever", "live"), FuzzName("Live Forever - 曲", "forever"),
            FuzzName("偽の歌 - Fake Song", "fake"), FuzzName("Sakura", "sakura"), FuzzName("Ame", "ame"),
            FuzzName("とうきょう", "tokyo"), FuzzName("東京", "tokyo"), FuzzName("Tokyo", "tokyo"), FuzzName("Müde", "muede"), FuzzName("Muede", "muede"),
            FuzzName("さようなら", "sayonara"), FuzzName("櫻", "sakura"), FuzzName("あす", "asu"), FuzzName("明日", "asu"), FuzzName("バイオリン", "violin"),
            FuzzName("偽の歌 ～偽りの夜～", "fake"), FuzzName("Other Song", "other"),
            // The eighth review's: another song by the artist, in yet another script.
            FuzzName("Другая песня", "other"), FuzzName("다른 노래", "other")
        ) + SPELLED.flatMap { song -> song.names().map { FuzzName(it, song.key) } }
        /** Each tag, and the version it says; null where it can't be told. */
        val FUZZ_TAGS = listOf(
            " (ライブ)" to "live", " (Live)" to "live", " (ライヴ)" to "live", " (TV Edit)" to "tv edit", " (Short)" to "short",
            " (バラードバージョン)" to "ballad", " (シングル・バージョン)" to "", " (Remix)" to "remix", " (Acoustic)" to "acoustic",
            " (オフ・ヴォーカル)" to "karaoke", " (インストラクター)" to null, " - Live" to "live", " - From \"Live Forever\"" to "", " (Teaser)" to "teaser",
            " (English Version)" to "english", " - English Version" to "english", " (日本語)" to null, " (歌詞付き)" to "", " (THE FIRST TAKE)" to "first take",
            " (Official Video)" to "", " [2019]" to null,
            // The seventh review's: editions, a film, a subtitle, a version no list has, the whole song, and in Spanish.
            " (Album Version)" to "", " (Single Version)" to "", " (Original Version)" to "", " (映画「ライブ・フォーエバー」主題歌)" to "",
            " (Christmas Version)" to "christmas", " (クリスマス・バージョン)" to "christmas", " ～偽りの夜～" to "", " (Full Version)" to "",
            " - Version Español" to "spanish",
            // The eighth review's: what a show has it as, who it's with, and marks in Hangul, in a longer word or not.
            " (TVアニメ「ライブ」オープニングテーマ)" to "", " - TVアニメ「ライブ」エンディングテーマ" to "", " (アニメ「ライブ」OP)" to "",
            " (TV Anime \"Live Forever\" Opening Theme)" to "", " (ドラマ「ライブ」主題歌)" to "", " (with Live Crew)" to "", " (w/ Live Crew)" to "",
            " (feat. Live Crew)" to "", " (라이브버전)" to "live", " (피아노커버)" to "cover", " (밴드라이브)" to "live", " (얼라이브)" to "",
            // The ninth review's: a version in Chinese and in Spanish, synonyms, a version word in a tag that names none,
            // a credit, the tags of picks, more than a version, and a language.
            " (现场版)" to "live", " (En Vivo)" to "live", " (Acústico)" to "acoustic", " (Off Vocal)" to "karaoke", " (Instrumental)" to "instrumental",
            " (prod. Live Crew)" to "", " (Fake Band x Live Crew)" to "", " [English Subtitles]" to "", " (Lyrics in English)" to "", " (アニメMV)" to "",
            " (Live Crew)" to "", " (Reprise)" to "reprise", " (Part 2)" to null, " (Re-Recorded)" to "re-recorded", " (2020)" to null,
            " (Live from Budokan)" to "live", " (OP Size)" to "tv size", " (Japanese Version)" to "japanese", " (Korean Version)" to "korean"
        )
        val FUZZ_ALBUMS = listOf("Fake Album", "Live Best", "Live at Budokan", "Karaoke Picks")

        /** What version an album says its songs are: a compilation, "Live Best", says none. */
        val FUZZ_ALBUMS_SAY = mapOf("Live at Budokan" to "live", "Karaoke Picks" to "karaoke")
        val FUZZ_LENGTHS = listOf("1:30", "1:32", "1:34", "0:30", "3:00")

        /** Lengths no recording of the song has: a teaser, or another song. */
        val FUZZ_OFF = setOf("0:30", "3:00")
        val FUZZ_CHANNELS = listOf("Fake Band", "Fan", "Fake Band - Topic", JAPANESE_ARTIST)

        /** Who a row is by: the artist most often, sometimes as Japan's store names it, sometimes another. */
        val FUZZ_ARTISTS = listOf("Fake Band", "Fake Band", "Fake Band", "Fake Band", "Other Band", JAPANESE_ARTIST)
        const val JAPANESE_ARTIST = "偽バンド"

        /** Another edition Apple may have of the song, as a live album's or a long one's, and how long it is. */
        val FUZZ_EDITION_ALBUMS = listOf("Live at Budokan", "Fake Album")
        const val FUZZ_EDITION_LENGTH = "3:00"
        const val WORSE_SHOWN = 60
        const val RIGHT = "right"
        const val WRONG = "wrong"
        const val UNKNOWN = "can't tell"
        const val NOTHING = "nothing"
        const val LATE_MS = 60_000L
    }
}
