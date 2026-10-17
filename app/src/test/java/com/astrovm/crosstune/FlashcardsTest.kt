package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
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
import java.io.File
import java.time.Duration

/** Saved lines practised as cards that play in the music app, whole songs learned, and lines quizzed while listening. */
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

    // endregion

    @Test
    fun aLineSavedPlaysInTheMusicAppAsACardThatsHeardFirst() {
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

        // With no meaning, it's heard: the app goes to the line and plays it.
        click(string(R.string.lyrics_study))
        click(string(R.string.saved_practice))
        waitUntil { model().uiState.practice?.sound == LineSound.PLAYING }
        assertEquals(Face.LISTEN, practice().face)
        assertEquals(10_000L, phone.seeks.last())
        assertEquals(true, phone.plays.last())
        assertFalse(shown("Stars"))
        // Once the app is there, at the next line it pauses, to be played again.
        appHas(atMs = 10_000)
        pass(10_500)
        waitUntil { practice().sound == LineSound.PLAYED }
        assertEquals(false, phone.plays.last())
        click(string(R.string.practice_play))
        waitUntil { practice().sound == LineSound.PLAYING }
        assertEquals(listOf(10_000L, 10_000L), phone.seeks.takeLast(2))

        // Turned, it says what it was; swiped right, it was known.
        click(string(R.string.practice_show))
        assertTrue(shown("Stars"))
        card().performTouchInput { swipeRight() }
        waitUntil { described(string(R.string.practice_done, 1)) }
        assertEquals(1, savedLine("Stars").box)
        assertTrue(described(string(R.string.practice_tally, 0, 1, 0)) && described(string(R.string.practice_streak, 1)))
        // Demo was playing before and still is, so there's nothing to go back to.
        assertFalse(shown(string(R.string.practice_back_to, "Demo")))
        click(string(R.string.floating_close))
        waitUntil { shown("Moon") }
    }

    @Test
    fun anotherSongIsFoundInTheMusicAppAndALineMissedComesBackAfterItsSong() {
        saved(
            """{"text":"One","translation":"Uno","title":"Other","artist":"Band","box":1,"start":5000,"end":9000,"app":"${app.packageName}"}""",
            """{"text":"Two","translation":"Dos","title":"Other","artist":"Band","box":2,"start":15000,"end":20000,"app":"${app.packageName}"}"""
        )
        launch()
        // Nothing plays: practice starts from the main screen, where the lines due are a tap away.
        click(string(R.string.practice_due, 2))
        // The song comes in on its own while its app is asked for it.
        waitUntil { phone.songsAsked.isNotEmpty() }
        assertEquals(MusicMetadata("Other", "Band") to app.packageName, phone.songsAsked.single())
        assertTrue(practice().intro && shown("Other"))
        assertTrue(described(string(R.string.practice_loading, app.packageManager.getApplicationLabel(app.applicationInfo).toString())))
        appHas("Other", atMs = 0, app = app.packageName)
        waitUntil { practice().sound == LineSound.PLAYING }
        assertEquals(5_000L, phone.seeks.last())
        assertFalse(practice().intro)
        appHas("Other", atMs = 5_000)
        pass(4_500)
        waitUntil { practice().sound == LineSound.PLAYED }

        // Heard, its meaning can be peeked at; turned by a tap, it says what it was.
        click(string(R.string.practice_hint))
        assertTrue(shown("Uno"))
        // Tapping the card turns it, and TalkBack says so.
        assertEquals(string(R.string.practice_show), card().fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        card().performClick()
        composeRule.waitForIdle()
        assertTrue(shown("One"))
        // Swiped left, it was missed, and comes back after the rest of its song.
        card().performTouchInput { swipeLeft() }
        waitUntil { practice().line?.text == "Two" }
        assertEquals(listOf("Two", "One"), practice().queue.map { it.text })

        // Thought of from its meaning, it doesn't play until it's turned.
        waitUntil { practice().sound == LineSound.PLAYED }
        assertEquals(Face.MEANING, practice().face)
        assertEquals(15_000L, phone.seeks.last())
        assertEquals(false, phone.plays.last())
        assertTrue(shown("Dos") && !shown("Two"))
        click(string(R.string.practice_show))
        waitUntil { practice().sound == LineSound.PLAYING }
        assertTrue(shown("Two"))
        // Dragged a little, it goes back; it was easy.
        card().performTouchInput { swipe(center, center.copy(x = center.x + 40f)) }
        composeRule.waitForIdle()
        assertEquals("Two", practice().line?.text)
        click(string(R.string.practice_easy))

        // The one missed, again: read this time, and known.
        waitUntil { practice().line?.text == "One" && !practice().turned }
        assertEquals(Face.LINE, practice().face)
        click(string(R.string.practice_show))
        click(string(R.string.practice_good))
        waitUntil { described(string(R.string.practice_done, 2)) }
        assertTrue(described(string(R.string.practice_tally, 1, 1, 1)))
        // Nothing played before, so there's nothing to go back to; closed, it's back to the main screen.
        assertFalse(composeRule.onAllNodes(hasText(string(R.string.practice_back_to, "Demo"))).fetchSemanticsNodes().isNotEmpty())
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
        click(string(R.string.practice_due, 2))
        waitUntil { practice().sound == LineSound.PLAYING }
        // The app lets the song go before the next card, of the same song, so it loads on the card itself.
        phone.playing.value = null
        waitUntil { practice().sound == LineSound.PLAYED }
        click(string(R.string.practice_show))
        click(string(R.string.practice_good))
        waitUntil { practice().line?.text == "Two" && phone.songsAsked.isNotEmpty() }
        assertTrue(practice().sound == LineSound.LOADING && !practice().intro)
        assertTrue(described(string(R.string.practice_play)))
        appHas("Other", atMs = 0)
        waitUntil { practice().sound == LineSound.PLAYING }
        assertEquals(15_000L, phone.seeks.last())
    }

    @Test
    fun aSongTheAppCantFindIsReadInsteadAndPracticeStopsWhatItPlays() {
        saved(
            """{"text":"One","title":"Other","artist":"Band","box":1,"start":5000,"end":9000}""",
            """{"text":"Lost","title":"Gone","artist":"Band","box":1,"start":5000,"end":9000}"""
        )
        phone.canPlaySongs = false
        launch()
        click(string(R.string.practice_due, 2))
        // No app to ask: it's read rather than heard.
        waitUntil { practice().line?.text == "One" && practice().sound == LineSound.NONE }
        assertTrue(shown("One"))
        assertFalse(practice().intro)
        // Asked, but never found: after a while it's read too.
        click(string(R.string.practice_show))
        click(string(R.string.practice_good))
        phone.canPlaySongs = true
        waitUntil { phone.songsAsked.isNotEmpty() }
        pass(MainViewModel.LOAD_SONG_MS + 1_000)
        waitUntil { practice().sound == LineSound.NONE }
        assertFalse(practice().intro)
        // There, but where the app can't move it, e.g. during an ad, it isn't asked for again, and it's read.
        val asked = phone.songsAsked.size
        appHas(practice().line!!.title, canSeek = false)
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        click(string(R.string.practice_due, 1))
        waitUntil { model().uiState.practice?.sound == LineSound.NONE }
        assertEquals(asked, phone.songsAsked.size)
        // Stopped while a line plays, the app pauses it.
        val playing = practice().line!!
        appHas(playing.title, atMs = 0)
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        click(string(R.string.practice_due, 1))
        waitUntil { model().uiState.practice?.sound == LineSound.PLAYING }
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        assertEquals(false, phone.plays.last())
    }

    @Test
    fun theMusicPlayedBeforeComesBackOnceDone() {
        saved("""{"text":"Uno","translation":"One","title":"Other","artist":"Band"}""")
        wordsOfDemo()
        click(string(R.string.lyrics_study))
        click(string(R.string.saved_practice))
        // Not timed, it's read.
        waitUntil { shown("Uno") }
        assertEquals(LineSound.NONE, practice().sound)
        click(string(R.string.practice_show))
        click(string(R.string.practice_easy))
        waitUntil { shown(string(R.string.practice_back_to, "Demo")) }
        appHas(atMs = 30_000)
        val seeks = phone.seeks.size
        click(string(R.string.practice_back_to, "Demo"))
        waitUntil { phone.seeks.size > seeks }
        // Where it was, about two seconds in, playing again; practice closed, back to its words.
        assertTrue(phone.seeks.last() in 2_000L..4_000L)
        assertEquals(true, phone.plays.last())
        waitUntil { shown("Moon") }
    }

    @Test
    fun aWordIsAGapInItsLinePlayedFromWhereTheVideoHasIt() {
        saved("""{"text":"sky","translation":"cielo","title":"Demo","artist":"Band","line":"Night sky","start":0,"end":10000,"lead":3000}""")
        appHas(video = true)
        launch()
        click(string(R.string.practice_due, 1))
        waitUntil { model().uiState.practice?.sound == LineSound.PLAYING }
        assertEquals(Face.GAP, practice().face)
        assertEquals(3_000L, phone.seeks.last())
        assertTrue(shown("Night $GAP") && shown("cielo"))
        click(string(R.string.practice_show))
        assertTrue(shown("sky") && shown("Night sky"))
        click(string(R.string.practice_easy))
        waitUntil { described(string(R.string.practice_done, 1)) }
    }

    @Test
    fun aWholeSongIsLearnedFromItsLinesEachOnce() {
        wordsOfDemo()
        click(string(R.string.lyrics_study))
        click(string(R.string.learn_song))
        // Each line once, how often it's sung said; sounds aren't picked to start with.
        assertTrue(described(string(R.string.learn_song_times, 2)))
        // Each a whole finger tall.
        val tall = 48 * app.resources.displayMetrics.density
        assertTrue(composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes().all { it.size.height >= tall })
        click(string(R.string.learn_song_add, 3))
        click(string(R.string.lyrics_study))
        click(string(R.string.learn_song))
        // Kept ones stay picked and can't be let go here; a sound can still be picked.
        assertEquals(3, SavedLinesStore(prefs()).load().size)
        click("La la la")
        click(string(R.string.learn_song_add, 1))
        val kept = SavedLinesStore(prefs()).load()
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
    fun linesKeptHideUntilSungToBeSaidFirst() {
        saved(
            """{"text":"Stars","translation":"Estrellas","title":"Demo","artist":"Band"}""",
            """{"text":"Moon","translation":"Luna","title":"Demo","artist":"Band"}"""
        )
        wordsOfDemo()
        // Kept before they could be timed, they're timed now.
        assertEquals(10_000L, savedLine("Stars").startMs)
        click(string(R.string.quiz_listen))
        assertTrue(model().uiState.quiz)
        assertFalse(shown("Stars") || shown("Moon"))
        // Still there to TalkBack, as lines hidden.
        assertEquals(2, composeRule.onAllNodes(hasContentDescription(string(R.string.quiz_hidden))).fetchSemanticsNodes().size)
        // Sung, it's said whether it was known, and shows.
        appHas(atMs = 10_000)
        waitUntil { described(string(R.string.quiz_knew)) }
        click(string(R.string.quiz_knew))
        waitUntil { shown("Stars") }
        assertEquals(1, savedLine("Stars").box)
        appHas(atMs = 40_000)
        waitUntil { described(string(R.string.quiz_missed)) }
        click(string(R.string.quiz_missed))
        waitUntil { shown("Moon") }
        assertEquals(0, savedLine("Moon").box)
        assertTrue(savedLine("Moon").due > System.currentTimeMillis())
        click(string(R.string.quiz_listen))
        assertFalse(model().uiState.quiz)
        // Both practised, none's due.
        click(string(R.string.lyrics_study))
        click(string(R.string.saved_practice))
        waitUntil { shown(string(R.string.practice_caught_up)) }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val STEP_MS = 50L
        const val WORDS = """[{"trackName":"Demo","artistName":"Band","plainLyrics":"Night sky\nStars\nNight sky\nLa la la\nMoon",""" +
            """"syncedLyrics":"[00:00.00] Night sky\n[00:10.00] Stars\n[00:20.00] Night sky\n[00:30.00] La la la\n[00:40.00] Moon"}]"""
    }
}
