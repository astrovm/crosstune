package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Saved lines practised as cards that play in the music app, and whole songs learned. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class FlashcardsTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private val phone = FakePlayback().apply { access = true }
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.playbackFactory = { phone }
        MainActivity.lyricsBusyPauseMs = 0
        prefs().edit().clear().putBoolean("setup_complete", true).putBoolean("exact_match", false).putBoolean("visuals", false)
            .putBoolean(SongRecognizers.KEY_PICK_RESET, true).commit()
        File(app.cacheDir, "lookups.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, WORDS) else FakeSpotify.html(request, "{}")
        }
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.lyricsBusyPauseMs = LYRICS_BUSY_PAUSE_MS
    }

    // region helpers

    private fun prefs() = app.getSharedPreferences("crosstune_preferences", Context.MODE_PRIVATE)

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun launch() {
        controller = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java)).setup()
        composeRule.waitForIdle()
    }

    private fun model() = androidx.lifecycle.ViewModelProvider(controller!!.get())[MainViewModel::class.java]

    private fun practice() = model().uiState.practice!!

    /** Waits for [condition], the phone's clock moving on a little each time, for what waits on it. */
    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MS)); condition() }

    /** Lets [ms] go by on the phone's clock, running what was waiting for it. */
    private fun pass(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        composeRule.waitForIdle()
    }

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun described(label: String) = composeRule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()

    private fun click(text: String) {
        composeRule.onAllNodes(hasText(text) or hasContentDescription(text)).onLast().performClick()
        composeRule.waitForIdle()
    }

    private fun card() = composeRule.onNodeWithTag(PRACTICE_CARD_TAG)

    /** The music app has [song] at [atMs], playing or not, as a video or not. */
    private fun appHas(song: String = "Demo", atMs: Long = 2_000, playing: Boolean = true, video: Boolean = false, app: String = this.app.packageName, canSeek: Boolean = true) {
        phone.onThePhone = Heard.Song(MusicMetadata(song, "Band"), null, atMs, SystemClock.elapsedRealtime())
        phone.playing.value = Following(PlaybackClock(atMs, SystemClock.elapsedRealtime(), playing = playing), "Music", canSeek = canSeek, appPackage = app, video = video)
    }

    /** Demo plays in the music app, and its words are open. */
    private fun wordsOfDemo() {
        appHas()
        launch()
        waitUntil { described(string(R.string.lyrics_button)) }
        click(string(R.string.lyrics_button))
        waitUntil { shown("Moon") }
    }

    private fun saved(vararg lines: String) = prefs().edit().putString("saved_lines", lines.joinToString(",", "[", "]")).commit()

    private fun savedLine(text: String) = SavedLinesStore(prefs()).load().first { it.text == text }

    /** The main screen's practice button, by what TalkBack says of it. */
    private fun practiceDue(due: Int) = "${string(R.string.saved_practice)}, ${string(R.string.practice_due, due)}"

    private fun readingShown(reading: String) = composeRule.onAllNodes(
        SemanticsMatcher("reads $reading") { reading in it.config.getOrElseNullable(ReadingsShown) { null }.orEmpty() },
        useUnmergedTree = true
    ).fetchSemanticsNodes().isNotEmpty()

    // endregion

    @Test
    fun aLineSavedFromWordsTimedWordByWordEndsWhereTheySay() {
        // Kept from before, as NetEase's words timed word by word are: each line says where it ends.
        runBlocking {
            LookupCache(File(app.cacheDir, "lyrics.json"), Dispatchers.Unconfined).apply {
                put("6\u0000demo\u0000band", """{"words":"Night sky\nStars\nMoon","lines":[[0,"Night sky",[],6000],[10000,"Stars",[],14500],[20000,"Moon"]]}""")
                save()
            }
        }
        wordsOfDemo()
        composeRule.onAllNodes(hasText("Stars")).onLast().performTouchInput { longClick() }
        composeRule.waitForIdle()
        click(string(R.string.lyrics_save_line))
        // Where it ends, not where the next starts.
        assertEquals(10_000L to 14_500L, savedLine("Stars").let { it.startMs to it.endMs })
    }

    @Test
    fun aLineSavedIsAskedAsWrittenAndPlaysInTheMusicAppOnceTurned() {
        wordsOfDemo()
        // Saved from its sheet: where it's sung, to the next line, and the app it plays in.
        composeRule.onAllNodes(hasText("Stars")).onLast().performTouchInput { longClick() }
        composeRule.waitForIdle()
        click(string(R.string.lyrics_save_line))
        val kept = savedLine("Stars")
        assertEquals(10_000L to 20_000L, kept.startMs to kept.endMs)
        assertEquals(app.packageName, kept.app)
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
        composeRule.waitForIdle()

        // The line as written, the app paused where it's sung: heard, it would say how it reads.
        click(string(R.string.lyrics_study))
        val played = phone.plays.size
        click(string(R.string.practice_song))
        waitUntil { model().uiState.practice?.sound == LineSound.PLAYED }
        assertTrue(shown("Stars"))
        assertEquals(10_000L, phone.seeks.last())
        assertEquals(listOf(false), phone.plays.drop(played))
        assertFalse(described(string(R.string.practice_play)))

        // Tapping the card turns it, and TalkBack says so; turned, it plays.
        assertEquals(string(R.string.practice_show), card().fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        card().performClick()
        waitUntil { practice().sound == LineSound.PLAYING }
        assertEquals(true, phone.plays.last())
        // Once the app is there, it pauses a moment past the next line, so the last word isn't cut short, to
        // be played again; turned again, it isn't.
        appHas(atMs = 10_000)
        pass(10_000)
        assertEquals(LineSound.PLAYING, practice().sound)
        pass(500)
        waitUntil { practice().sound == LineSound.PLAYED }
        assertEquals(false, phone.plays.last())
        val seeks = phone.seeks.size
        model().turnPractice()
        pass(1_000)
        assertEquals(seeks, phone.seeks.size)
        click(string(R.string.practice_play))
        waitUntil { practice().sound == LineSound.PLAYING }
        assertEquals(listOf(10_000L, 10_000L), phone.seeks.takeLast(2))
        // Known, it's done.
        click(string(R.string.practice_good))
        waitUntil { shown(string(R.string.practice_done, 1)) }
        assertEquals(1, savedLine("Stars").box)
        // Demo was playing before and still is, so there's nothing to go back to.
        assertFalse(shown(string(R.string.practice_back_to, "Demo")))
        click(string(R.string.floating_close))
        waitUntil { shown("Moon") }
        // None due now: all caught up.
        click(string(R.string.lyrics_study))
        click(string(R.string.lyrics_saved_lines))
        click(string(R.string.saved_practice))
        waitUntil { shown(string(R.string.practice_caught_up)) }
    }

    @Test
    fun aReadingThatIsntHowItsSungIsPutRightByHandOnTheCardsBack() {
        saved(
            """{"text":"夜の空","reading":"よるのそら","romanized":"yoru no sora","title":"Other","artist":"Band",""" +
                """"ruby":[["夜","よる"],["の",null],["空","そら"]]}""",
            """{"text":"Moon","translation":"Luna","title":"Other","artist":"Band","box":1}"""
        )
        launch()
        click(practiceDue(2))
        // Only once turned, with its reading shown.
        assertFalse(described(string(R.string.practice_fix_reading)))
        click(string(R.string.practice_show))
        assertTrue(readingShown("よる"))
        click(string(R.string.practice_fix_reading))
        // Each kanji, with its reading to change.
        assertTrue(shown("夜") && shown("空"))
        composeRule.onNode(hasText("よる") and hasSetTextAction()).performTextReplacement("night")
        // Not kana: it can't be saved.
        composeRule.onNode(hasText(string(R.string.save_button))).assertIsNotEnabled()
        composeRule.onNode(hasText("night") and hasSetTextAction()).performTextReplacement("よ")
        click(string(R.string.save_button))
        // On the card now, and kept, in kana and romaji too.
        waitUntil { readingShown("よ") }
        assertFalse(readingShown("よる"))
        assertTrue(shown("yo no sora"))
        val kept = savedLine("夜の空")
        assertEquals("よのそら", kept.reading)
        assertTrue(kept.readByHand)
        // Cancelled, nothing changes.
        click(string(R.string.practice_fix_reading))
        composeRule.onNode(hasText("そら") and hasSetTextAction()).performTextReplacement("から")
        click(string(R.string.cancel_button))
        assertEquals("よのそら", savedLine("夜の空").reading)
        // A line with no kanji has nothing to put right.
        click(string(R.string.practice_good))
        waitUntil { practice().line?.text == "Moon" }
        click(string(R.string.practice_show))
        assertFalse(described(string(R.string.practice_fix_reading)))
    }

    @Test
    fun aLineInKanjiIsReadThenShownWithItsKanaAndMeaning() {
        saved(
            """{"text":"夜の空","reading":"よるのそら","romanized":"yoru no sora","translation":"Cielo nocturno","title":"Other","artist":"Band","box":1,"start":5000,"end":9000,"app":"${app.packageName}",""" +
                """"ruby":[["夜","よる"],["の",null],["空","そら"]]}""",
            """{"text":"星","translation":"Estrella","title":"Other","artist":"Band","box":2,"start":15000,"end":20000,"app":"${app.packageName}"}"""
        )
        launch()
        // Due, practice is a tap away from the main screen, saying how many.
        click(practiceDue(2))
        // The app's asked for the song by name.
        waitUntil { phone.songsAsked.isNotEmpty() }
        assertEquals(MusicMetadata("Other", "Band") to app.packageName, phone.songsAsked.single())
        assertEquals(LineSound.LOADING, practice().sound)
        // Only the kanji: how it reads, heard or written, and what it means, are the answer.
        val loading = string(R.string.practice_loading, app.packageManager.getApplicationLabel(app.applicationInfo).toString())
        assertTrue(shown("夜の空"))
        assertFalse(shown(loading) || readingShown("よる") || shown("yoru no sora") || shown("Cielo nocturno"))
        // Turned while the app gets there, it says so, and plays once there.
        click(string(R.string.practice_show))
        assertTrue(readingShown("よる") && readingShown("そら") && shown("yoru no sora") && shown("Cielo nocturno"))
        assertTrue(shown(loading))
        pass(1_000)
        appHas("Other", atMs = 0)
        waitUntil { practice().sound == LineSound.PLAYING }
        assertEquals(5_000L, phone.seeks.last())
        assertEquals(true, phone.plays.last())
        // Asked for once: turned, it waited for the app instead of asking again.
        assertEquals(1, phone.songsAsked.size)
        // Missed, it comes back after the rest of its song.
        click(string(R.string.practice_again))
        waitUntil { practice().line?.text == "星" }
        assertEquals(listOf("星", "夜の空"), practice().queue.map { it.text })
        assertFalse(shown("Estrella"))
        click(string(R.string.practice_show))
        assertTrue(shown("Estrella"))
        click(string(R.string.practice_good))
        waitUntil { practice().line?.text == "夜の空" && !practice().turned }
        click(string(R.string.practice_show))
        click(string(R.string.practice_good))
        waitUntil { shown(string(R.string.practice_done, 2)) }
        // Nothing played before, so there's nothing to go back to; closed, it's back to the main screen.
        assertFalse(shown(string(R.string.practice_back_to, "Demo")))
        click(string(R.string.floating_close))
        waitUntil { !described(string(R.string.floating_close)) }
        assertTrue(model().uiState.practice == null)
    }

    @Test
    fun aSongTheAppLostIsAskedForAgainOnTheCardWhileItLoads() {
        saved(
            """{"text":"One","title":"Other","artist":"Band","box":1,"start":5000,"end":9000,"app":"${app.packageName}"}""",
            """{"text":"Two","title":"Other","artist":"Band","box":1,"start":15000,"end":20000,"app":"${app.packageName}"}"""
        )
        appHas("Other", atMs = 0)
        launch()
        click(practiceDue(2))
        waitUntil { practice().sound == LineSound.PLAYED }
        click(string(R.string.practice_show))
        waitUntil { practice().sound == LineSound.PLAYING }
        // The app lets the song go before the next card, of the same song, so it loads again.
        phone.playing.value = null
        waitUntil { practice().sound == LineSound.PLAYED }
        click(string(R.string.practice_good))
        waitUntil { practice().line?.text == "Two" && phone.songsAsked.isNotEmpty() }
        assertEquals(LineSound.LOADING, practice().sound)
        appHas("Other", atMs = 0)
        waitUntil { practice().sound == LineSound.PLAYED }
        assertEquals(15_000L, phone.seeks.last())
    }

    @Test
    fun youTubeMusicIsAskedForTheSongByItsLinkThereAndOtherAppsByName() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        saved(
            // Kept before the app it was in was, so the last one that played is asked: here YouTube Music.
            """{"text":"One","title":"Other","artist":"Band","box":1,"start":5000,"end":9000}""",
            """{"text":"Two","title":"Another","artist":"Band","box":1,"start":5000,"end":9000,"app":"$youTubeMusic"}""",
            """{"text":"Three","title":"Gone","artist":"Band","box":1,"start":5000,"end":9000,"app":"$youTubeMusic"}""",
            """{"text":"Four","title":"Elsewhere","artist":"Band","box":1,"start":5000,"end":9000,"app":"com.spotify.music"}"""
        )
        // YouTube Music has every song but Gone.
        fake.handler = { request ->
            val title = listOf("Another", "Other", "Elsewhere").firstOrNull { it in FakeSpotify.bodyOf(request) }
            if (request.url.host == "music.youtube.com" && title != null) FakeSpotify.html(request, """{"contents":[{"musicResponsiveListItemRenderer":{
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Band • Album • 3:00"}]}}}],
                "playlistItemData":{"videoId":"${title.lowercase().padEnd(11, '0')}"}}}]}""")
            else FakeSpotify.html(request, "{}")
        }
        launch()
        click(practiceDue(4))
        // YouTube Music ignores being asked by name, so it's given the song's link there.
        for ((title, link) in listOf("Other" to "https://music.youtube.com/watch?v=other000000", "Another" to "https://music.youtube.com/watch?v=another0000")) {
            waitUntil { phone.songsAsked.lastOrNull()?.first?.title == title }
            assertEquals(link, phone.linksAsked.last())
            appHas(title, atMs = 0, app = youTubeMusic)
            waitUntil { practice().sound == LineSound.PLAYED }
            click(string(R.string.practice_show))
            click(string(R.string.practice_good))
            phone.playing.value = null
        }
        // A song it doesn't have is asked for by name; another app always is.
        waitUntil { phone.songsAsked.size == 3 }
        pass(MainViewModel.LOAD_SONG_MS + 1_000)
        waitUntil { practice().sound == LineSound.NONE }
        click(string(R.string.practice_show))
        click(string(R.string.practice_good))
        waitUntil { phone.songsAsked.size == 4 }
        assertEquals(listOf("Gone", "Elsewhere"), phone.songsAsked.drop(2).map { it.first.title })
        assertEquals(listOf(null, null), phone.linksAsked.drop(2))
    }

    @Test
    fun aSongTheAppCantFindIsReadInsteadAndPracticeStopsWhatItPlays() {
        saved(
            """{"text":"One","title":"Other","artist":"Band","box":1,"start":5000,"end":9000}""",
            """{"text":"Lost","title":"Gone","artist":"Band","box":1,"start":5000,"end":9000}"""
        )
        phone.canPlaySongs = false
        launch()
        click(practiceDue(2))
        // No app to ask: it's read, with nothing to play.
        waitUntil { practice().line?.text == "One" && practice().sound == LineSound.NONE }
        assertTrue(shown("One"))
        click(string(R.string.practice_show))
        assertFalse(described(string(R.string.practice_play)))
        // Asked, but never found: after a while it's read too.
        click(string(R.string.practice_good))
        phone.canPlaySongs = true
        waitUntil { phone.songsAsked.isNotEmpty() }
        pass(MainViewModel.LOAD_SONG_MS + 1_000)
        waitUntil { practice().sound == LineSound.NONE }
        // There, but where the app can't move it, e.g. during an ad, it isn't asked for again, and it's read.
        val asked = phone.songsAsked.size
        appHas(practice().line!!.title, canSeek = false)
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        click(practiceDue(1))
        waitUntil { model().uiState.practice?.sound == LineSound.NONE }
        assertEquals(asked, phone.songsAsked.size)
        // Stopped while a line plays, the app pauses it.
        val playing = practice().line!!
        appHas(playing.title, atMs = 0)
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        click(practiceDue(1))
        waitUntil { model().uiState.practice?.sound == LineSound.PLAYED }
        click(string(R.string.practice_show))
        waitUntil { practice().sound == LineSound.PLAYING }
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        assertEquals(false, phone.plays.last())
    }

    @Test
    fun theMusicPlayedBeforeComesBackOnceDone() {
        saved("""{"text":"Uno","translation":"One","title":"Other","artist":"Band"}""")
        wordsOfDemo()
        click(string(R.string.lyrics_study))
        click(string(R.string.lyrics_saved_lines))
        click(string(R.string.saved_practice))
        // Not timed, it's read.
        waitUntil { shown("Uno") }
        assertEquals(LineSound.NONE, practice().sound)
        click(string(R.string.practice_show))
        click(string(R.string.practice_good))
        waitUntil { shown(string(R.string.practice_back_to, "Demo")) }
        appHas(atMs = 30_000)
        val seeks = phone.seeks.size
        click(string(R.string.practice_back_to, "Demo"))
        waitUntil { phone.seeks.size > seeks }
        // Where it was, about two seconds in, playing again; practice closed, back to its words.
        assertTrue(phone.seeks.last() in 2_000L..4_000L)
        assertEquals(true, phone.plays.last())
        waitUntil { model().uiState.practice == null }
        assertEquals("Demo", model().uiState.lyricsFor?.title)
    }

    @Test
    fun aWordIsAskedWithItsLineAndPlaysFromWhereTheVideoHasIt() {
        saved("""{"text":"空","reading":"そら","translation":"cielo","title":"Demo","artist":"Band","line":"夜の空","start":0,"end":10000,"lead":3000}""")
        appHas(video = true)
        launch()
        click(practiceDue(1))
        waitUntil { model().uiState.practice?.sound == LineSound.PLAYED }
        assertEquals(3_000L, phone.seeks.last())
        // The word, with the line it's sung in under it; how it reads and what it means once turned.
        assertTrue(shown("空") && shown("夜の空"))
        assertFalse(readingShown("そら") || shown("cielo"))
        click(string(R.string.practice_show))
        assertTrue(readingShown("そら") && shown("cielo"))
        click(string(R.string.practice_good))
        waitUntil { shown(string(R.string.practice_done, 1)) }
    }

    @Test
    fun aWholeSongIsLearnedFromItsLinesEachOnce() {
        saved("""{"text":"Elsewhere","translation":"Otro lado","title":"Other","artist":"Band"}""")
        wordsOfDemo()
        click(string(R.string.lyrics_study))
        click(string(R.string.learn_song))
        // Each line once, how often it's sung said; sounds aren't picked to start with.
        assertTrue(described(string(R.string.learn_song_times, 2)))
        // Each a whole finger tall.
        val tall = 48 * app.resources.displayMetrics.density
        assertTrue(composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes().all { it.size.height >= tall })
        click(string(R.string.learn_song_add, 3))
        // Added, they're practised at once, on their own, without other songs' lines.
        waitUntil { model().uiState.practice != null }
        assertEquals(listOf("Night sky", "Stars", "Moon"), practice().queue.map { it.text })
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        click(string(R.string.lyrics_study))
        click(string(R.string.learn_song))
        // Kept ones stay picked and can't be let go here; a sound can still be picked.
        assertEquals(4, SavedLinesStore(prefs()).load().size)
        click("La la la")
        click(string(R.string.learn_song_add, 1))
        waitUntil { model().uiState.practice != null }
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        val kept = SavedLinesStore(prefs()).load().filter { it.title == "Demo" }
        assertEquals(listOf("La la la", "Night sky", "Stars", "Moon"), kept.map { it.text })
        assertEquals(0L to 10_000L, savedLine("Night sky").startMs to savedLine("Night sky").endMs)
        // The last line has no next one to end at, so it's given a few seconds.
        assertEquals(40_000L to 46_000L, savedLine("Moon").startMs to savedLine("Moon").endMs)
        click(string(R.string.lyrics_study))
        click(string(R.string.learn_song))
        // All kept, there's nothing to add.
        composeRule.onNode(hasText(string(R.string.learn_song_add, 0))).assertExists()
        click("Stars")
        composeRule.onNode(hasText(string(R.string.learn_song_add, 0))).assertExists()
    }

    @Test
    fun aSongIsPractisedOnItsOwnFromItsWordsAllOfItWhenNoneIsDue() {
        val later = System.currentTimeMillis() + 86_400_000L
        saved(
            """{"text":"Elsewhere","translation":"Otro lado","title":"Other","artist":"Band"}""",
            """{"text":"Moon","translation":"Luna","title":"Demo","artist":"Band","box":2,"due":$later}""",
            """{"text":"Stars","translation":"Estrellas","title":"Demo","artist":"Band","box":2,"due":$later}"""
        )
        wordsOfDemo()
        click(string(R.string.lyrics_study))
        // Only this song's lines, none due, so all of them, in the order they're sung.
        click(string(R.string.practice_song))
        waitUntil { model().uiState.practice != null }
        assertEquals(listOf("Stars", "Moon"), practice().queue.map { it.text })
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        // From the main screen, it's every song's lines that are due.
        model().startPractice()
        waitUntil { model().uiState.practice != null }
        assertEquals(listOf("Elsewhere"), practice().queue.map { it.text })
    }

    @Test
    fun linesKeptBeforeTheyCouldBeTimedAreTimedOnceTheirWordsShow() {
        saved(
            """{"text":"Stars","translation":"Estrellas","title":"Demo","artist":"Band"}""",
            """{"text":"sky","translation":"cielo","title":"Demo","artist":"Band","line":"Night sky"}""",
            """{"text":"Not sung","translation":"No cantada","title":"Demo","artist":"Band"}"""
        )
        wordsOfDemo()
        assertEquals(10_000L to 20_000L, savedLine("Stars").let { it.startMs to it.endMs })
        // A word, by the line it's sung in.
        assertEquals(0L, savedLine("sky").startMs)
        assertFalse(savedLine("Not sung").timed)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val STEP_MS = 50L
        const val WORDS = """[{"trackName":"Demo","artistName":"Band","plainLyrics":"Night sky\nStars\nNight sky\nLa la la\nMoon",""" +
            """"syncedLyrics":"[00:00.00] Night sky\n[00:10.00] Stars\n[00:20.00] Night sky\n[00:30.00] La la la\n[00:40.00] Moon"}]"""
    }
}
