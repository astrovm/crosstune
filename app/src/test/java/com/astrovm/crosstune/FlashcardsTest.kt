package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** Whether [text] is on the practice card, not in the settings open over it. */
    private fun onCard(text: String) = composeRule.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(PRACTICE_CARD_TAG)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Taps the eye of a layer, on the front's rows, or on the back's, which come after them. */
    private fun eye(label: String, back: Boolean = false) = tag(label, back)

    /** Taps what [label] says, on the front's row, or on the back's, which comes after it. */
    private fun tag(label: String, back: Boolean = false) {
        click(string(if (back) R.string.practice_back else R.string.practice_front))
        composeRule.onAllNodes(hasContentDescription(label)).onFirst().performClick()
        composeRule.waitForIdle()
    }

    /** Opens a kind of card in the list, to set its sides; the switch beside its name is another tap. */
    private fun openKind(name: String) {
        // The trailing chevron is part of the same target as the name.
        composeRule.onNode(hasText(name)).performTouchInput { click(Offset(width - 4f, centerY)) }
        composeRule.waitForIdle()
    }

    /** A kind of card is open to be set: its name can be changed. */
    private fun inEditor() = composeRule.onNode(hasSetTextAction()).assertExists()

    private fun pressBack() {
        controller!!.get().onBackPressedDispatcher.onBackPressed()
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
        // The bar for the song playing opens its player, and the player has the words.
        waitUntil { composeRule.onAllNodesWithTag(NOW_PLAYING_TAG).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(NOW_PLAYING_TAG).performClick()
        waitUntil { described(string(R.string.lyrics_button)) }
        click(string(R.string.lyrics_button))
        waitUntil { shown("Moon") }
    }

    private fun saved(vararg lines: String) = prefs().edit().putString("saved_lines", lines.joinToString(",", "[", "]")).commit()

    private fun savedLine(text: String) = SavedLinesStore(prefs()).load().first { it.text == text }

    /** A line to check or not among those to choose. */
    private fun line(text: String) = composeRule.onNode(isToggleable() and hasText(text))

    /** The main screen's practice button, by what TalkBack says of it. */
    private fun practiceDue(due: Int) = "${string(R.string.saved_practice)}, ${string(R.string.practice_due, due)}"

    /** From the main screen, or the list it opens, practises every song's [due] lines. */
    private fun practiseAll(due: Int) {
        if (!shown(string(R.string.practice_all_songs))) click(practiceDue(due))
        assertTrue(shown(string(R.string.practice_due, due)))
        click(string(R.string.practice_all_songs))
    }

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
                put("8\u0000demo\u0000band", """{"words":"Night sky\nStars\nMoon","lines":[[0,"Night sky",[],6000],[10000,"Stars",[],14500],[20000,"Moon"]]}""")
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
        pass(10_700)
        assertEquals(LineSound.PLAYING, practice().sound)
        pass(300)
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
        assertEquals(1, savedLine("Stars").step)
        // Demo was playing before and still is, so there's nothing to go back to.
        assertFalse(shown(string(R.string.practice_back_to, "Demo")))
        click(string(R.string.floating_close))
        waitUntil { shown("Moon") }
        // None due now, the song says none is.
        click(string(R.string.lyrics_study))
        assertTrue(shown(string(R.string.practice_song)))
        assertFalse(shown(string(R.string.practice_due, 1)))
    }

    @Test
    fun aReadingThatIsntHowItsSungIsPutRightFromTheCardMenu() {
        saved(
            """{"text":"夜の空","reading":"よるのそら","romanized":"yoru no sora","title":"Other","artist":"Band",""" +
                """"ruby":[["夜","よる"],["の",null],["空","そら"]]}""",
            """{"text":"Moon","translation":"Luna","title":"Other","artist":"Band","box":1}"""
        )
        launch()
        practiseAll(2)
        // Correction stays in the menu, only after the answer is shown.
        click(string(R.string.practice_card_options))
        assertFalse(shown(string(R.string.practice_fix_reading)))
        click(string(R.string.practice_card_options))
        click(string(R.string.practice_show))
        assertTrue(readingShown("よる"))
        assertFalse(described(string(R.string.practice_fix_reading)))
        click(string(R.string.practice_card_options))
        click(string(R.string.practice_fix_reading))
        // Each kanji, with its reading to change.
        assertTrue(shown("夜") && shown("空"))
        composeRule.onNode(hasText("よる") and hasSetTextAction()).performTextReplacement("night")
        // Not kana: it can't be saved.
        composeRule.onNode(hasText(string(R.string.save_button))).assertIsNotEnabled()
        composeRule.onNode(hasText("night") and hasSetTextAction()).performTextReplacement("よ")
        click(string(R.string.save_button))
        assertTrue(shown(string(R.string.card_type_new)))
        click(string(R.string.practice_card_options))
        // On the card now, and kept, in kana and romaji too.
        waitUntil { readingShown("よ") }
        assertFalse(readingShown("よる"))
        assertTrue(shown("yo no sora"))
        val kept = savedLine("夜の空")
        assertEquals("よのそら", kept.reading)
        assertTrue(kept.readByHand)
        // Cancel and Back return to the menu without changing the saved reading.
        click(string(R.string.practice_card_options))
        click(string(R.string.practice_fix_reading))
        composeRule.onNode(hasText("そら") and hasSetTextAction()).performTextReplacement("から")
        click(string(R.string.cancel_button))
        assertEquals("よのそら", savedLine("夜の空").reading)
        assertTrue(shown(string(R.string.card_type_new)))
        click(string(R.string.practice_fix_reading))
        click(string(R.string.back_button))
        assertTrue(shown(string(R.string.card_type_new)))
        click(string(R.string.practice_fix_reading))
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(shown(string(R.string.card_type_new)))
        assertEquals("よのそら", savedLine("夜の空").reading)
        click(string(R.string.practice_card_options))
        // A line with no kanji has nothing to put right.
        click(string(R.string.practice_good))
        waitUntil { practice().line?.text == "Moon" }
        click(string(R.string.practice_show))
        assertFalse(described(string(R.string.practice_fix_reading)))
        click(string(R.string.practice_card_options))
        assertFalse(shown(string(R.string.practice_fix_reading)))
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
        practiseAll(2)
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
    fun theCardsShowWhatsPickedForThemAndKeepItSo() {
        saved(
            """{"text":"東京へ","reading":"とうきょうへ","romanized":"tōkyō e","typed":"toukyou he","translation":"To Tokyo","title":"Other","artist":"Band","box":1,"ruby":[["東京","とうきょう"],["へ",null]]}""",
            """{"text":"Moon","translation":"Luna","title":"Other","artist":"Band","box":1}"""
        )
        launch()
        practiseAll(2)
        assertTrue(onCard("東京へ"))
        assertFalse(readingShown("とうきょう") || onCard("To Tokyo") || onCard("tōkyō e"))
        click(string(R.string.practice_card_options))
        val menu = composeRule.onNodeWithTag("practice-card-options").getBoundsInRoot()
        assertTrue(menu.bottom <= card().getBoundsInRoot().top)
        // The kinds of card are listed, Read it on and Say it off; Read it opens to set its two sides.
        composeRule.onNode(hasContentDescription(string(R.string.card_type_read))).assertIsOn()
        composeRule.onNode(hasContentDescription(string(R.string.card_type_translate))).assertIsOff()
        openKind(string(R.string.card_type_read))
        // The back first: the words as written, then in kana, then in Latin letters, where nothing else is spelled that way.
        tag(string(R.string.lyrics_display_original), back = true)
        click(string(R.string.lyrics_display_latin))
        // The front in kana, with what it means and its Latin letters as a keyboard spells them.
        tag(string(R.string.lyrics_display_original))
        click(string(R.string.lyrics_display_kana))
        assertTrue(onCard("とうきょうへ"))
        eye(string(R.string.lyrics_translation))
        assertTrue(onCard("To Tokyo"))
        eye(string(R.string.lyrics_romanized))
        assertTrue(onCard("tōkyō e"))
        tag(string(R.string.lyrics_romaji_typed))
        assertTrue(onCard("toukyou he"))
        assertFalse(prefs().getBoolean("lyrics_romaji_typed", false))
        assertEquals(
            CardOptions(
                front = CardSide(wordsAs = WordsAs.KANA, latin = true, translation = true, romajiTyped = true),
                back = CardSide(wordsAs = WordsAs.LATIN, readings = true, latin = true, translation = true, sound = true)
            ), loadCardTypes(prefs()).first { it.id == READ_TYPE }.options
        )
        pressBack()
        composeRule.onNode(hasContentDescription(string(R.string.card_type_read))).assertIsOn()
        pressBack()
        composeRule.onNodeWithTag("practice-card-options").assertDoesNotExist()
        // Turned, the back comes in below the front: the words in Latin letters, and what they mean.
        click(string(R.string.practice_show))
        assertTrue(onCard("tōkyō e"))
        assertEquals(2, composeRule.onAllNodes(hasText("To Tokyo") and hasAnyAncestor(hasTestTag(PRACTICE_CARD_TAG)), useUnmergedTree = true).fetchSemanticsNodes().size)
        click(string(R.string.practice_good))
        waitUntil { practice().line?.text == "Moon" }
        composeRule.onNodeWithTag("practice-card-options").assertDoesNotExist()
        // A line with nothing to read or spell has only the words and its meaning to show.
        click(string(R.string.practice_card_options))
        openKind(string(R.string.card_type_read))
        assertFalse(described(string(R.string.lyrics_readings)) || described(string(R.string.lyrics_romanized)) || described(string(R.string.lyrics_romaji_typed)))
        pressBack()
        pressBack()
        click(string(R.string.practice_show))
        assertTrue(onCard("Moon") && onCard("Luna"))
    }

    @Test
    fun eachSideCanShowOnlyTheTextLayersPickedForIt() {
        saved("""{"text":"夜の空","reading":"よるのそら","romanized":"yoru no sora","translation":"Night sky","title":"Demo","artist":"Band","ruby":[["夜","よる"],["の",null],["空","そら"]]}""")
        launch()
        practiseAll(1)
        click(string(R.string.practice_card_options))
        openKind(string(R.string.card_type_read))
        // The answer is what the words mean, and nothing else.
        eye(string(R.string.visuals_show_words), back = true)
        eye(string(R.string.lyrics_readings), back = true)
        eye(string(R.string.lyrics_romanized), back = true)
        pressBack()
        pressBack()
        // The front still asks the Japanese words alone.
        assertTrue(onCard("夜の空"))
        assertFalse(onCard("Night sky"))
        click(string(R.string.practice_show))
        assertTrue(onCard("Night sky"))
        assertFalse(onCard("yoru no sora") || readingShown("よる"))
        assertEquals(1, composeRule.onAllNodes(hasText("夜の空") and hasAnyAncestor(hasTestTag(PRACTICE_CARD_TAG)), useUnmergedTree = true).fetchSemanticsNodes().size)
        // The front with the readings over its words, and the back without its meaning.
        click(string(R.string.practice_card_options))
        openKind(string(R.string.card_type_read))
        eye(string(R.string.lyrics_translation), back = true)
        eye(string(R.string.lyrics_readings))
        pressBack()
        pressBack()
        assertFalse(onCard("Night sky"))
        assertTrue(readingShown("よる"))
        val read = model().uiState.cardTypes.first { it.id == READ_TYPE }.options
        assertFalse(read.back.words || read.back.translation || read.back.readings || read.back.latin)
        assertTrue(read.front.words && read.front.readings)
    }

    @Test
    fun eachSideKeepsItsOwnWordAndReadingSizesPinchedOrSteppedBiggerAndSmaller() {
        saved("""{"text":"夜の空","reading":"よるのそら","romanized":"yoru no sora","translation":"Night sky","title":"Demo","artist":"Band","ruby":[["夜","よる"],["の",null],["空","そら"]]}""")
        launch()
        practiseAll(1)
        click(string(R.string.practice_card_options))
        openKind(string(R.string.card_type_read))
        // Each side has four rows: words, readings, Latin letters and meaning.
        val rows = { composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)) }
        assertEquals(4, rows().fetchSemanticsNodes().size)
        fun step(row: Int, label: Int) = composeRule.runOnIdle {
            rows()[row].fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == string(label) }.action()
        }.also { composeRule.waitForIdle() }
        val read = { model().uiState.cardTypes.first { it.id == READ_TYPE }.options }
        step(0, R.string.practice_bigger)
        assertEquals(1.15f, read().front.wordsScale, 0.001f)
        step(1, R.string.practice_bigger)
        assertEquals(0.55f * 1.15f, read().front.readingScale, 0.001f)
        click(string(R.string.practice_back))
        step(1, R.string.practice_smaller)
        assertEquals(0.55f / 1.15f, read().back.readingScale, 0.001f)
        // Smaller than the least stays at the least.
        step(1, R.string.practice_smaller)
        step(1, R.string.practice_smaller)
        assertEquals(MIN_READING_SCALE, read().back.readingScale, 0f)
        step(2, R.string.practice_bigger)
        assertEquals(1.15f, read().back.wordsScale, 0.001f)
        // The meaning has a size of its own, apart from the words.
        step(3, R.string.practice_bigger)
        assertEquals(1.15f, read().back.translationScale, 0.001f)
        assertEquals(1.15f, read().back.wordsScale, 0.001f)
        assertEquals(1f, read().front.translationScale, 0f)
        // Two fingers pulling apart make the back's words bigger, and it's kept once they let go.
        rows()[0].performTouchInput { pinch(Offset(centerX - 40f, centerY), Offset(centerX - 90f, centerY), Offset(centerX + 40f, centerY), Offset(centerX + 90f, centerY)) }
        composeRule.waitForIdle()
        assertTrue(read().back.wordsScale > 1.5f)
        assertEquals(read().back.wordsScale, loadCardTypes(prefs()).first { it.id == READ_TYPE }.options.back.wordsScale, 0f)
        // The lyrics keep their own sizes.
        assertEquals(1f, model().uiState.learning.wordsScale, 0f)
        assertEquals(READING_SCALE, model().uiState.learning.readingScale, 0f)
    }

    @Test
    fun linesMakeACardOfEachKindThatIsOnAndTheCardSaysWhichItIs() {
        saved("""{"text":"夜の空","reading":"よるのそら","romanized":"yoru no sora","translation":"Night sky","title":"Demo","artist":"Band","interval":3,"due":1,""" +
                """"ruby":[["夜","よる"],["の",null],["空","そら"]]}""")
        launch()
        click(practiceDue(1))
        click(string(R.string.practice_all_songs))
        // With one kind on, a card doesn't say what kind it is.
        assertFalse(onCard(string(R.string.card_type_read)))
        click(string(R.string.practice_card_options))
        // Say it is turned on, and the one kind left can't be turned off, so there's always a card.
        composeRule.onNode(hasContentDescription(string(R.string.card_type_read))).assertIsNotEnabled()
        composeRule.onNode(hasContentDescription(string(R.string.card_type_translate))).performClick()
        composeRule.onNode(hasContentDescription(string(R.string.card_type_translate))).assertIsOn()
        composeRule.onNode(hasContentDescription(string(R.string.card_type_read))).assertIsEnabled()
        assertEquals(listOf(true, false, true), model().uiState.cardTypes.map { it.on })
        pressBack()
        assertTrue(onCard(string(R.string.card_type_read)))
        click(string(R.string.practice_show))
        click(string(R.string.practice_good))
        waitUntil { shown(string(R.string.practice_done, 1)) }
        assertEquals(0, savedLine("夜の空").reviews.size)
        assertTrue(savedLine("夜の空").interval > 3)
        // Its other card is next, asking for the words from what they mean.
        click(string(R.string.floating_close))
        waitUntil { !described(string(R.string.floating_close)) }
        practiseAll(1)
        assertTrue(onCard(string(R.string.card_type_translate)))
        assertTrue(onCard("Night sky"))
        assertFalse(onCard("夜の空"))
        click(string(R.string.practice_show))
        assertTrue(readingShown("よる"))
        click(string(R.string.practice_good))
        waitUntil { shown(string(R.string.practice_done, 1)) }
        assertEquals(setOf(TRANSLATE_TYPE), savedLine("夜の空").reviews.keys)
        assertEquals(1, savedLine("夜の空").reviews.getValue(TRANSLATE_TYPE).step)
    }

    @Test
    fun aHearCardPlaysItsLineAtOnceAndOnlyTheAnswerShowsTheWords() {
        saved("""{"text":"One","translation":"Uno","title":"Other","artist":"Band","box":1,"start":5000,"end":9000,"app":"${app.packageName}"}""")
        saveCardTypes(prefs(), DEFAULT_CARD_TYPES.map { it.copy(on = it.id == HEAR_TYPE) })
        appHas("Other", atMs = 0)
        launch()
        practiseAll(1)
        // Heard, not read: it plays before the card is turned, and the words aren't there.
        waitUntil { practice().sound == LineSound.PLAYING }
        assertEquals(true, phone.plays.last())
        assertFalse(onCard("One"))
        appHas("Other", atMs = 5_000)
        pass(4_700)
        pass(300)
        waitUntil { practice().sound == LineSound.PLAYED }
        // Turned, it says what was sung, and doesn't play again.
        val seeks = phone.seeks.size
        click(string(R.string.practice_show))
        pass(1_000)
        assertTrue(onCard("One"))
        assertTrue(onCard("Uno"))
        assertEquals(seeks, phone.seeks.size)
    }

    @Test
    fun theEditorsSoundPreviewPlaysWithoutTurningTheCard() {
        saved("""{"text":"One","translation":"Uno","title":"Other","artist":"Band","start":5000,"end":9000,"app":"${app.packageName}"}""")
        appHas("Other", atMs = 0)
        launch()
        practiseAll(1)
        waitUntil { practice().sound == LineSound.PLAYED }
        click(string(R.string.practice_card_options))
        openKind(string(R.string.card_type_read))
        val seeks = phone.seeks.size
        click(string(R.string.lyrics_play))
        waitUntil { phone.seeks.size > seeks }
        assertFalse(practice().turned)
        assertFalse(model().uiState.cardTypes.first().options.front.sound)
    }

    @Test
    fun aSidePlaysTheLineOnlyWhenItHasItsSound() {
        saved("""{"text":"One","translation":"Uno","title":"Other","artist":"Band","box":1,"start":5000,"end":9000,"app":"${app.packageName}"}""")
        appHas("Other", atMs = 0)
        launch()
        practiseAll(1)
        waitUntil { practice().sound == LineSound.PLAYED }
        // Read it plays the line once it's turned: its back has the sound, its front doesn't.
        assertFalse(described(string(R.string.practice_play)))
        click(string(R.string.practice_card_options))
        openKind(string(R.string.card_type_read))
        val read = { model().uiState.cardTypes.first { it.id == READ_TYPE }.options }
        assertTrue(read().back.sound && !read().front.sound)
        // The sound moves from the back to the front.
        tag(string(R.string.practice_play))
        tag(string(R.string.practice_play), back = true)
        assertTrue(read().front.sound && !read().back.sound)
        pressBack()
        pressBack()
        assertEquals(1, composeRule.onAllNodes(hasContentDescription(string(R.string.practice_play))).fetchSemanticsNodes().size)
        val seeks = phone.seeks.size
        click(string(R.string.practice_show))
        pass(1_000)
        assertEquals(seeks, phone.seeks.size)
        assertEquals(1, composeRule.onAllNodes(hasContentDescription(string(R.string.practice_play))).fetchSemanticsNodes().size)
    }

    @Test
    fun aHearCardWithNoWayToPlayShowsTheWordsInstead() {
        saved("""{"text":"One","translation":"Uno","title":"Other","artist":"Band","box":1,"start":5000,"end":9000}""")
        saveCardTypes(prefs(), DEFAULT_CARD_TYPES.map { it.copy(on = it.id == HEAR_TYPE) })
        phone.access = false
        launch()
        practiseAll(1)
        assertEquals(LineSound.NONE, practice().sound)
        assertTrue(onCard("One"))
    }

    @Test
    fun aKindOfCardIsMadeRenamedAndDeleted() {
        saved("""{"text":"夜の空","reading":"よるのそら","romanized":"yoru no sora","translation":"Night sky","title":"Demo","artist":"Band"}""")
        launch()
        practiseAll(1)
        click(string(R.string.practice_card_options))
        click(string(R.string.card_type_new))
        // It starts as the first kind is, and opens to be named.
        assertEquals(listOf(READ_TYPE, HEAR_TYPE, TRANSLATE_TYPE, "type1"), model().uiState.cardTypes.map { it.id })
        assertEquals(model().uiState.cardTypes[0].options, model().uiState.cardTypes[3].options)
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Mine")
        composeRule.waitForIdle()
        assertEquals("Mine", loadCardTypes(prefs()).last().name)
        pressBack()
        composeRule.onNode(hasContentDescription("Mine")).assertIsOn()
        // Deleted, it's gone; the three the app starts with stay.
        openKind("Mine")
        click(string(R.string.card_type_delete))
        assertEquals(listOf(READ_TYPE, HEAR_TYPE, TRANSLATE_TYPE), model().uiState.cardTypes.map { it.id })
        click(string(R.string.undo_button))
        assertEquals("Mine", model().uiState.cardTypes.last().name)
        openKind("Mine")
        click(string(R.string.card_type_delete))
        click(string(R.string.floating_close))
        listOf(R.string.card_type_translate, R.string.card_type_hear, R.string.card_type_read).forEach {
            openKind(string(it))
            assertFalse(described(string(R.string.card_type_delete)))
            pressBack()
        }
        // With none on, the first is.
        composeRule.runOnIdle { model().setCardTypes(model().uiState.cardTypes.map { it.copy(on = false) }, true) }
        assertEquals(listOf(true, false, false), model().uiState.cardTypes.map { it.on })
    }

    @Test
    fun aPresetKindComesBackAsItWasWhenItHasBeenChanged() {
        saved("""{"text":"夜の空","reading":"よるのそら","romanized":"yoru no sora","translation":"Night sky","title":"Demo","artist":"Band"}""")
        launch()
        practiseAll(1)
        click(string(R.string.practice_card_options))
        openKind(string(R.string.card_type_translate))
        // As it starts there's nothing to put back.
        assertFalse(described(string(R.string.card_type_reset)))
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Mine")
        composeRule.waitForIdle()
        assertEquals("Mine", loadCardTypes(prefs())[2].name)
        // Changed, it can go back to its name and options, and stays as on or off as it was.
        click(string(R.string.card_type_reset))
        assertEquals(TRANSLATE_IT, model().uiState.cardTypes[2])
        assertFalse(described(string(R.string.card_type_reset)))
        composeRule.onNode(hasSetTextAction()).assertTextContains(string(R.string.card_type_translate))
        click(string(R.string.undo_button))
        assertEquals("Mine", model().uiState.cardTypes[2].name)
    }

    @Test
    fun aSessionHasTheCardsOfEveryKindThatIsOn() {
        saved("""{"text":"夜の空","reading":"よるのそら","romanized":"yoru no sora","translation":"Night sky","title":"Demo","artist":"Band","interval":3,"due":1}""")
        saveCardTypes(prefs(), DEFAULT_CARD_TYPES.map { it.copy(on = it.id != HEAR_TYPE) })
        launch()
        click(practiceDue(2))
        click(string(R.string.practice_all_songs))
        // Both cards of the line are in the one session, the one due longest first.
        assertTrue(onCard(string(R.string.card_type_translate)))
        click(string(R.string.practice_show))
        click(string(R.string.practice_good))
        assertTrue(onCard(string(R.string.card_type_read)))
        click(string(R.string.practice_show))
        click(string(R.string.practice_good))
        waitUntil { shown(string(R.string.practice_done, 2)) }
    }

    @Test
    fun aSongTheAppLostIsAskedForAgainOnTheCardWhileItLoads() {
        saved(
            """{"text":"One","title":"Other","artist":"Band","box":1,"start":5000,"end":9000,"app":"${app.packageName}"}""",
            """{"text":"Two","title":"Other","artist":"Band","box":1,"start":15000,"end":20000,"app":"${app.packageName}"}"""
        )
        appHas("Other", atMs = 0)
        launch()
        practiseAll(2)
        waitUntil { practice().sound == LineSound.PLAYED }
        click(string(R.string.practice_show))
        waitUntil { practice().sound == LineSound.PLAYING }
        // The app lets the song go before the next card, of the same song, so it loads again.
        phone.playing.value = null
        waitUntil { practice().sound == LineSound.PLAYED }
        // Four grades, as Anki has them.
        listOf(R.string.practice_again, R.string.practice_hard, R.string.practice_good, R.string.practice_easy).forEach { assertTrue(shown(string(it))) }
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
            // Kept before the app it was in was, so YouTube Music, which has it, is asked.
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
        practiseAll(4)
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
    fun aSongKeptFromNoAppInParticularIsAskedOfYouTubeMusicWhenItHasIt() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        saved("""{"text":"One","title":"Other","artist":"Band","box":1,"start":5000,"end":9000}""")
        fake.handler = { request ->
            if (request.url.host == "music.youtube.com" && "Other" in FakeSpotify.bodyOf(request)) FakeSpotify.html(request, """{"contents":[{"musicResponsiveListItemRenderer":{
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Other"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Band • Album • 3:00"}]}}}],
                "playlistItemData":{"videoId":"other000000"}}}]}""")
            else FakeSpotify.html(request, "{}")
        }
        // YouTube Music can be asked, and a video plays in YouTube, which wouldn't play the song if asked.
        phone.running = setOf(youTubeMusic, "com.google.android.youtube")
        launch()
        practiseAll(1)
        waitUntil { phone.songsAsked.isNotEmpty() }
        assertEquals(MusicMetadata("Other", "Band") to youTubeMusic, phone.songsAsked.single())
        assertEquals("https://music.youtube.com/watch?v=other000000", phone.linksAsked.single())
        appHas("Other", atMs = 0, app = youTubeMusic)
        waitUntil { practice().sound == LineSound.PLAYED }
    }

    @Test
    fun youTubeMusicThatCantBeWokenIsOpenedOnTheSongAndLeftOnceItHasIt() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        saved(
            """{"text":"One","title":"Another","artist":"Band","box":1,"start":5000,"end":9000,"app":"$youTubeMusic"}""",
            // Kept before the app it was in was, with no app at all to ask.
            """{"text":"Two","title":"Other","artist":"Band","box":2,"start":5000,"end":9000}"""
        )
        fake.handler = { request ->
            val title = listOf("Another", "Other").firstOrNull { it in FakeSpotify.bodyOf(request) }
            if (request.url.host == "music.youtube.com" && title != null) FakeSpotify.html(request, """{"contents":[{"musicResponsiveListItemRenderer":{
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Band • Album • 3:00"}]}}}],
                "playlistItemData":{"videoId":"${title.lowercase().padEnd(11, '0')}"}}}]}""")
            else FakeSpotify.html(request, "{}")
        }
        // YouTube Music doesn't let Crosstune wake it, and another app isn't asked for a song kept from it.
        phone.running = setOf("com.spotify.music")
        launch()
        practiseAll(2)
        waitUntil { phone.opened.isNotEmpty() }
        assertEquals(youTubeMusic to "https://music.youtube.com/watch?v=another0000", phone.opened.single())
        assertTrue(phone.songsAsked.isEmpty())
        appHas("Another", atMs = 0, app = youTubeMusic)
        // Once it has the song, Crosstune's back in front, with the line ready.
        waitUntil { practice().sound == LineSound.PLAYED }
        assertEquals(1, phone.cameBack)
        click(string(R.string.practice_show))
        click(string(R.string.practice_good))
        phone.playing.value = null
        // With no app to ask at all, it's opened too.
        phone.canPlaySongs = false
        phone.running = emptySet()
        waitUntil { phone.opened.size == 2 }
        assertEquals(youTubeMusic to "https://music.youtube.com/watch?v=other000000", phone.opened.last())
        // Never getting there, Crosstune still comes back, with nothing to play.
        pass(MainViewModel.LOAD_SONG_MS + 1_000)
        waitUntil { practice().sound == LineSound.NONE }
        assertEquals(2, phone.cameBack)
    }

    @Test
    fun aSongTheAppCantFindIsReadInsteadAndPracticeStopsWhatItPlays() {
        saved(
            """{"text":"One","title":"Other","artist":"Band","box":1,"start":5000,"end":9000}""",
            """{"text":"Lost","title":"Gone","artist":"Band","box":1,"start":5000,"end":9000}"""
        )
        phone.canPlaySongs = false
        launch()
        practiseAll(2)
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
        practiseAll(1)
        waitUntil { model().uiState.practice?.sound == LineSound.NONE }
        assertEquals(asked, phone.songsAsked.size)
        // Stopped while a line plays, the app pauses it.
        val playing = practice().line!!
        appHas(playing.title, atMs = 0)
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        practiseAll(1)
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
        appHas()
        launch()
        practiseAll(1)
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
        // Where it was, about two seconds in, playing again; practice closed, back to the list.
        assertTrue(phone.seeks.last() in 2_000L..4_000L)
        assertEquals(true, phone.plays.last())
        waitUntil { model().uiState.practice == null }
        assertTrue(shown(string(R.string.practice_all_songs)))
    }

    @Test
    fun aWordIsAskedWithItsLineAndPlaysFromWhereTheVideoHasIt() {
        saved("""{"text":"空","reading":"そら","translation":"cielo","title":"Demo","artist":"Band","line":"夜の空","start":0,"end":10000,"lead":3000}""")
        appHas(video = true)
        launch()
        practiseAll(1)
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
    fun aSongsLinesAreChosenFromItsWordsEachOnce() {
        saved("""{"text":"Elsewhere","translation":"Otro lado","title":"Other","artist":"Band"}""")
        wordsOfDemo()
        click(string(R.string.lyrics_study))
        // With none of its lines kept, there's only practising it, which has them chosen first.
        assertFalse(shown(string(R.string.choose_lines)))
        click(string(R.string.practice_song))
        // Each line once, how often it's sung said; with none kept yet, all but sounds are checked.
        assertTrue(described(string(R.string.learn_song_times, 2)))
        line("Night sky").assertIsOn()
        line("La la la").assertIsOff()
        // Each a whole finger tall.
        val tall = 48 * app.resources.displayMetrics.density
        assertTrue(composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes().all { it.size.height >= tall })
        click(string(R.string.saved_practice))
        assertEquals(listOf("Night sky", "Stars", "Moon"), SavedLinesStore(prefs()).load().filter { it.title == "Demo" }.map { it.text })
        assertEquals(0L to 10_000L, savedLine("Night sky").startMs to savedLine("Night sky").endMs)
        // The last line has no next one to end at, so it's given a few seconds.
        assertEquals(40_000L to 46_000L, savedLine("Moon").startMs to savedLine("Moon").endMs)
        // Practised, it's them on their own, without other songs' lines.
        waitUntil { model().uiState.practice != null }
        assertEquals(listOf("Night sky", "Stars", "Moon"), practice().queue.map { it.text })
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        // Chosen again, the kept ones are checked: one more picked, one let go. The menu says how many are kept.
        click(string(R.string.lyrics_study))
        assertTrue(shown("3"))
        click(string(R.string.choose_lines))
        line("Stars").assertIsOn()
        // Nothing changed, there's nothing to save.
        composeRule.onNode(hasText(string(R.string.save_button))).assertIsNotEnabled()
        line("La la la").performClick()
        line("Stars").performClick()
        click(string(R.string.save_button))
        val kept = SavedLinesStore(prefs()).load().filter { it.title == "Demo" }
        assertEquals(listOf("La la la", "Night sky", "Moon"), kept.map { it.text })
        assertEquals("Elsewhere", SavedLinesStore(prefs()).load().first { it.title == "Other" }.text)
    }

    @Test
    fun aWordKeptIsListedApartFromItsLineToLetGo() {
        // A word that's a whole line: kept as a word, not as the line.
        saved("""{"text":"Moon","translation":"Luna","title":"Demo","artist":"Band","line":"Moon"}""")
        wordsOfDemo()
        click(string(R.string.lyrics_study))
        click(string(R.string.choose_lines))
        val moons = composeRule.onAllNodes(isToggleable() and hasText("Moon"))
        moons.assertCountEquals(2)
        // The line isn't kept; the word, after the lines, is.
        moons[0].assertIsOff()
        moons[1].assertIsOn().performClick()
        click(string(R.string.save_button))
        assertTrue(SavedLinesStore(prefs()).load().isEmpty())
    }

    @Test
    fun aSongWithNoLinesKeptHasThemChosenFirstThenPractised() {
        wordsOfDemo()
        click(string(R.string.lyrics_study))
        click(string(R.string.practice_song))
        // Nothing to practise yet, so its lines to choose, practised once chosen.
        line("Moon").performClick()
        click(string(R.string.saved_practice))
        waitUntil { model().uiState.practice != null }
        assertEquals(listOf("Night sky", "Stars"), practice().queue.map { it.text })
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        // None checked, there's nothing to practise.
        click(string(R.string.lyrics_study))
        click(string(R.string.choose_lines))
        line("Night sky").performClick()
        line("Stars").performClick()
        click(string(R.string.save_button))
        assertTrue(SavedLinesStore(prefs()).load().isEmpty())
        click(string(R.string.lyrics_study))
        click(string(R.string.practice_song))
        listOf("Night sky", "Stars", "Moon").forEach { line(it).performClick() }
        composeRule.onNode(hasText(string(R.string.saved_practice))).assertIsNotEnabled()
        line("Moon").performClick()
        composeRule.onNode(hasText(string(R.string.saved_practice))).assertIsEnabled()
    }

    @Test
    fun theCountOfLinesDueIsShownWhole() {
        saved(*Array(48) { """{"text":"Line $it","translation":"Línea $it","title":"Demo","artist":"Band"}""" })
        launch()
        assertTrue(described(practiceDue(48)))
        // Nothing of it is cut off: a button clips what's in it to its circle, so it's over the button, not in it.
        var node = composeRule.onNodeWithTag(PRACTICE_BADGE_TAG, useUnmergedTree = true).fetchSemanticsNode().parent
        while (node != null) {
            assertFalse(SemanticsActions.OnClick in node.config)
            node = node.parent
        }
    }

    @Test
    fun thePracticeListPractisesEverySongOrOneAndLetsItsLinesGo() {
        val later = System.currentTimeMillis() + 86_400_000L
        saved(
            """{"text":"Moon","translation":"Luna","title":"Demo","artist":"Band","box":2,"due":$later}""",
            """{"text":"Elsewhere","translation":"Otro lado","title":"Other","artist":"Band"}""",
            """{"text":"Stars","translation":"Estrellas","title":"Demo","artist":"Band","box":2,"due":$later}"""
        )
        launch()
        click(practiceDue(1))
        // Every song's due at once, then each song, those with most due first.
        assertTrue(shown(string(R.string.practice_all_songs)) && shown("Other") && shown("Demo"))
        val rows = composeRule.onAllNodes(hasClickAction() and (hasText("Other") or hasText("Demo"))).fetchSemanticsNodes()
        assertEquals(listOf("Other", "Demo"), rows.map { it.config[SemanticsProperties.Text].first().text })
        // A song on its own: all of it, none due.
        click("Demo")
        waitUntil { model().uiState.practice != null }
        assertEquals(setOf("Moon", "Stars"), practice().queue.map { it.text }.toSet())
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        // Done, it's back to the list.
        click(string(R.string.practice_all_songs))
        waitUntil { model().uiState.practice != null }
        assertEquals(listOf("Elsewhere"), practice().queue.map { it.text })
        click(string(R.string.floating_close))
        waitUntil { model().uiState.practice == null }
        // A song's lines let go from the list.
        composeRule.onAllNodes(hasContentDescription(string(R.string.choose_lines))).onFirst().performClick()
        composeRule.waitForIdle()
        line("Elsewhere").assertIsOn().performClick()
        click(string(R.string.save_button))
        waitUntil { !shown("Other") }
        assertEquals(listOf("Moon", "Stars"), SavedLinesStore(prefs()).load().map { it.text })
        // None due, there's nothing to practise of every song.
        composeRule.onNode(hasText(string(R.string.practice_all_songs)) and hasClickAction()).assertIsNotEnabled()
        // None left, it's back to the main screen, with nothing to practise.
        click(string(R.string.choose_lines))
        line("Moon").performClick()
        line("Stars").performClick()
        click(string(R.string.save_button))
        waitUntil { !shown(string(R.string.practice_all_songs)) }
        assertFalse(described(string(R.string.saved_practice)))
    }

    @Test
    fun aSongSwipedFromThePracticeListIsLetGoUntilUndone() {
        val later = System.currentTimeMillis() + 86_400_000L
        saved(
            """{"text":"Moon","translation":"Luna","title":"Demo","artist":"Band","box":2,"due":$later}""",
            """{"text":"Elsewhere","translation":"Otro lado","title":"Other","artist":"Band"}"""
        )
        launch()
        click(practiceDue(1))
        fun song(title: String) = composeRule.onNode(hasClickAction() and hasText(title))
        // TalkBack, which can't swipe, removes it with an action of its own.
        fun removeByAction(title: String) {
            val row = song(title).fetchSemanticsNode()
            composeRule.runOnUiThread { row.config[SemanticsActions.CustomActions].single { it.label == string(R.string.remove_button) }.action() }
            composeRule.waitForIdle()
        }
        // Swiped away, all its lines go, until undone.
        song("Other").performTouchInput { swipeLeft() }
        waitUntil { !shown("Other") }
        assertEquals(listOf("Moon"), SavedLinesStore(prefs()).load().map { it.text })
        click(string(R.string.undo_button))
        waitUntil { shown("Other") }
        // Kept again, each is practised as far as it had been.
        removeByAction("Demo")
        waitUntil { !shown("Demo") }
        click(string(R.string.undo_button))
        waitUntil { shown("Demo") }
        assertEquals(3, savedLine("Moon").interval)
        assertEquals(later, savedLine("Moon").due)
        // A second removal's offer replaces the first's; with none left, the list stays while it shows.
        song("Other").performTouchInput { swipeRight() }
        waitUntil { !shown("Other") }
        removeByAction("Demo")
        waitUntil { !shown("Demo") }
        assertTrue(shown(string(R.string.practice_all_songs)))
        click(string(R.string.undo_button))
        waitUntil { shown("Demo") }
        assertFalse(shown("Other"))
        // Not undone, it's back to the main screen once the offer's gone.
        removeByAction("Demo")
        composeRule.mainClock.advanceTimeBy(5_000)
        assertTrue(shown(string(R.string.practice_all_songs)))
        composeRule.mainClock.advanceTimeBy(10_000)
        waitUntil { !shown(string(R.string.practice_all_songs)) }
        assertEquals(emptyList<SavedLine>(), SavedLinesStore(prefs()).load())
    }

    @Test
    fun aSongWithNothingToRecallIsAllCaughtUp() {
        val later = System.currentTimeMillis() + 86_400_000L
        saved(
            """{"text":"Moon","translation":"Luna","title":"Demo","artist":"Band","due":$later}""",
            """{"text":"Hum","title":"Other","artist":"Band"}"""
        )
        launch()
        click(string(R.string.saved_practice))
        // Kept before it could be read or played, there's nothing of it to practise.
        click("Other")
        waitUntil { shown(string(R.string.practice_caught_up)) }
        assertEquals(0, practice().total)
    }

    @Test
    fun withNoneDueThePracticeButtonStillOpensTheList() {
        val later = System.currentTimeMillis() + 86_400_000L
        saved("""{"text":"Moon","translation":"Luna","title":"Demo","artist":"Band","due":$later}""")
        launch()
        // Nothing due, so no count; just its icon, with no words beside it.
        assertFalse(described(practiceDue(1)))
        assertFalse(shown(string(R.string.saved_practice)))
        click(string(R.string.saved_practice))
        assertTrue(shown(string(R.string.practice_all_songs)) && shown("Demo"))
        // Back, it's the main screen.
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertFalse(shown(string(R.string.practice_all_songs)))
        assertTrue(described(string(R.string.saved_practice)))
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
