package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Duration
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The view model on its own, with only the parts a test hands it: what it does when a part is
 * missing, as the ones "nullable so tests need none" are, and the corners the screens never reach.
 */
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private val made = mutableListOf<MainViewModel>()

    @Before
    fun setUp() {
        prefs().edit().clear().putBoolean("setup_complete", true).commit()
        File(app.cacheDir, "lookups.json").delete()
    }

    @After
    fun tearDown() {
        made.forEach { runCatching { it.onClearedForTest() } }
    }

    private fun MainViewModel.onClearedForTest() {
        MainViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(this)
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lets [ms] pass for what waits on the clock. */
    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun model(
        playback: PlaybackSource? = null,
        oneTap: OneTapSetup? = null,
        chatGpt: ChatGpt? = null,
        tutor: ChatGptTutor? = null,
        updates: UpdateChecker? = null,
        keptOnDisk: LookupCache? = null,
        sleepTimer: SleepTimer? = null,
        phone: Phone = Phone.OTHER,
        lyricsFinder: LyricsFinder? = null,
        songSearcher: SongSearcher? = null,
        reports: ReportSender? = null
    ): MainViewModel {
        val client = fake.client()
        return MainViewModel(
            LinkResolver(client, Dispatchers.Unconfined),
            ExactMatcher(client, ioDispatcher = Dispatchers.Unconfined),
            lyricsFinder = lyricsFinder,
            playback = playback,
            preferences = prefs(),
            interception = LinkInterception(app),
            artwork = ArtworkLoader(client, Dispatchers.Unconfined),
            listener = SongHearing { Heard.Nothing },
            systemDispatcher = Dispatchers.Unconfined,
            chatGpt = chatGpt,
            tutor = tutor,
            oneTap = oneTap,
            updates = updates,
            keptOnDisk = keptOnDisk,
            sleepTimer = sleepTimer,
            phone = phone,
            songSearcher = songSearcher,
            reports = reports
        ).also { made += it }
    }

    @Test
    fun aModelWithoutTheOptionalPartsLeavesTheirFeaturesAlone() {
        val model = model()
        model.setUpInOneTap()
        assertFalse(model.uiState.settingUpInOneTap)
        assertFalse(model.fixInOneTap(OneTapFix.LINKS))
        assertFalse(model.uiState.canSetUpInOneTap || model.uiState.oneTapRoot || model.uiState.shizukuStopped)
        assertFalse(model.uiState.canFollowApps || model.uiState.followRestricted)
        var done = 0
        model.signInWithChatGpt { done++ }
        model.cancelChatGptSignIn()
        model.pickChatGptModel("gpt-6")
        model.signOutOfChatGpt()
        idle()
        assertEquals(0, done)
        assertEquals(ChatGptState(), model.uiState.chatGpt)
        assertNull(prefs().getString("chatgpt_model", null))
    }

    @Test
    fun aFixThatFailsLeavesTheStepToDoByHandAndKeepsTheListOpen() {
        val shell = FakeShell(does = { false })
        val model = model(oneTap = OneTapSetup(app, listOf(shell)))
        assertTrue(model.uiState.canSetUpInOneTap)
        // From the list of fixes, a failed one still comes back to it.
        model.openFixes()
        for ((fix, need) in listOf(OneTapFix.LINKS to Need.Links, OneTapFix.FOLLOW to Need.Follow, OneTapFix.FLOAT to Need.Float)) {
            assertTrue(model.fixInOneTap(fix))
            idle()
            pass(2_000)
            assertEquals(Fixes(list = true, steps = listOf(need), failed = true), model.uiState.fixes)
            assertNull(model.uiState.fixingInOneTap)
        }
        // Closed, it is not listed again by the next failure.
        model.closeFixes()
        assertTrue(model.fixInOneTap(OneTapFix.LINKS))
        idle()
        assertEquals(Fixes(list = false, steps = listOf(Need.Links), failed = true), model.uiState.fixes)
    }

    @Test
    fun aFixAskedWhileOneRunsIsTheSameFixAndRunsOnce() {
        val shell = FakeShell()
        shell.answer = kotlinx.coroutines.CompletableDeferred()
        val model = model(oneTap = OneTapSetup(app, listOf(shell)))
        var then = 0
        assertTrue(model.fixInOneTap(OneTapFix.LINKS) { then++ })
        assertEquals(OneTapFix.LINKS, model.uiState.fixingInOneTap)
        assertTrue(model.fixInOneTap(OneTapFix.LINKS) { then++ })
        shell.answer.complete(Unit)
        idle()
        assertEquals(1, then)
        assertNull(model.uiState.fixingInOneTap)
    }

    @Test
    fun settingUpInOneTapWaitsForAndroidToLetItFollowMusicAppsButNotForever() {
        val shell = FakeShell()
        val playback = FakePlayback()
        val model = model(playback = playback, oneTap = OneTapSetup(app, listOf(shell)))
        model.setUpInOneTap()
        // A second ask while one runs does nothing more.
        model.setUpInOneTap()
        assertTrue(model.uiState.settingUpInOneTap)
        idle()
        assertTrue(model.uiState.settingUpInOneTap)
        // Android never lets it: it stops waiting after a second, and says it worked.
        pass(1_500)
        assertFalse(model.uiState.settingUpInOneTap)
        assertFalse(model.uiState.oneTapFailed)
        assertEquals(1, shell.ran.count { "allow_listener" in it })
    }

    @Test
    fun settingUpInOneTapStopsWaitingOnceAndroidLetsItFollow() {
        val shell = FakeShell()
        val playback = FakePlayback()
        val model = model(playback = playback, oneTap = OneTapSetup(app, listOf(shell)))
        model.setUpInOneTap()
        idle()
        pass(250)
        playback.access = true
        pass(300)
        assertFalse(model.uiState.settingUpInOneTap)
        assertTrue(model.uiState.canFollowApps)
    }

    @Test
    fun shizukuStartingAndStoppingChangesWhatOneTapOffers() {
        val shell = FakeShell(ready = false)
        val model = model(oneTap = OneTapSetup(app, listOf(shell)))
        assertFalse(model.uiState.canSetUpInOneTap)
        shell.ready = true
        shell.changed!!.invoke()
        assertTrue(model.uiState.canSetUpInOneTap)
        // Cleared, it stops listening.
        model.onClearedForTest()
        assertNull(shell.changed)
    }

    private val song = MusicMetadata("Hush", "Band")

    private fun following(playing: Boolean = true, app: String = "com.spotify", skipNext: Boolean = false, skipPrevious: Boolean = false, seek: Boolean = true, shuffle: Boolean? = null, repeat: Repeat? = null, like: AppAction? = null) =
        Following(PlaybackClock(1_000, android.os.SystemClock.elapsedRealtime(), playing = playing), "Spotify", seek, app, canSkipNext = skipNext, canSkipPrevious = skipPrevious, shuffle = shuffle, repeat = repeat, like = like)

    /** A model whose music app plays [song], offered as playing now, with the player open on it when [open]. */
    private fun playingModel(playback: FakePlayback, open: Boolean = true, sleepTimer: SleepTimer? = null, phone: Phone = Phone.OTHER, lyricsFinder: LyricsFinder? = null, keptOnDisk: LookupCache? = null, reports: ReportSender? = null): MainViewModel {
        playback.access = true
        playback.onThePhone = Heard.Song(song, null, app = "com.spotify")
        val model = model(playback = playback, sleepTimer = sleepTimer, phone = phone, lyricsFinder = lyricsFinder, keptOnDisk = keptOnDisk, reports = reports)
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        if (open) {
            model.peekPlayingNow()
            idle()
        }
        return model
    }

    @Test
    fun theControlsOfAPlayerNotOpenDoNothing() {
        val playback = FakePlayback()
        val model = model(playback = playback)
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        model.openPlayerApp()
        model.playerMenu()
        model.playerPlayPause()
        model.playerSkip(next = true)
        model.playerShuffle()
        model.playerRepeat()
        model.playerLike()
        model.playerPlayQueued(1)
        model.playerPlayLater(0)
        model.playerMoveQueued(0, 1)
        model.playerCard()
        model.playerSeek(500)
        model.playerLyrics()
        model.playerSleep(null)
        model.playerSleep(5)
        model.playerSleepOff()
        idle()
        assertNull(model.uiState.player)
        assertTrue(effects.isEmpty())
        assertTrue(playback.songsAsked.isEmpty() && playback.plays.isEmpty() && playback.skips.isEmpty() && playback.seeks.isEmpty())
        assertTrue(playback.shuffled.isEmpty() && playback.repeated.isEmpty() && playback.queuedPlayed.isEmpty() && playback.liked == 0)
        assertNull(model.uiState.sleep)
    }

    @Test
    fun theSongPlayingNowIsOfferedAndItsButtonsReachItsApp() {
        val playback = FakePlayback()
        val model = model(playback = playback)
        // Nothing playing: its buttons and the player have nothing to act on.
        model.openPlayingNow()
        model.peekPlayingNow()
        model.playingNowPlayPause()
        model.playingNowSkip()
        assertNull(model.uiState.player)
        assertTrue(playback.plays.isEmpty() && playback.skips.isEmpty())

        playback.access = true
        playback.onThePhone = Heard.Song(song, null, app = "com.spotify")
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        assertEquals(song, model.uiState.playingNow?.song)
        // The app doesn't say where it is yet, so only that something was asked.
        model.playingNowPlayPause()
        assertEquals(1, playback.plays.size)
        playback.playing.value = following(playing = true)
        idle()
        playback.plays.clear()
        model.playingNowPlayPause()
        assertEquals(listOf(false), playback.plays.toList())
        playback.playing.value = following(playing = false)
        idle()
        model.playingNowPlayPause()
        assertEquals(listOf(false, true), playback.plays.toList())
        model.playingNowSkip()
        assertEquals(listOf(true), playback.skips.toList())
    }

    @Test
    fun openingTheSongPlayingNowShowsItsCardAndItsPlayerOnce() {
        val playback = FakePlayback()
        playback.playing.value = following()
        val model = playingModel(playback, open = false)
        model.openPlayingNow()
        idle()
        assertEquals(song, model.uiState.player?.song)
        assertEquals("com.spotify", model.uiState.player?.following?.appPackage)
        // Opened again, the same player stays as it is.
        val player = model.uiState.player
        model.openPlayingNow()
        idle()
        assertEquals(player, model.uiState.player)
        // The card of the player's song is under it already; closing it leaves the card.
        model.playerMenu()
        model.playerCard()
        assertNull(model.uiState.player)
    }

    @Test
    fun aPlayerWithAnAppSendsItsButtonsThere() {
        val playback = FakePlayback()
        val sleep = SleepTimer(playback, MainScope())
        playback.playing.value = following(playing = true, skipNext = true, shuffle = false, repeat = Repeat.OFF, like = AppAction("like", "Like", 0))
        val model = playingModel(playback, sleepTimer = sleep)
        assertEquals(true, model.uiState.player?.following?.canSkipNext)
        model.playerPlayPause()
        assertEquals(listOf(false), playback.plays.toList())
        // The previous song can't be asked for.
        model.playerSkip(next = false)
        assertTrue(playback.skips.isEmpty())
        model.playerSkip(next = true)
        assertEquals(listOf(true), playback.skips.toList())
        model.playerShuffle()
        assertEquals(listOf(true), playback.shuffled.toList())
        model.playerRepeat()
        assertEquals(listOf(Repeat.ALL), playback.repeated.toList())
        model.playerLike()
        assertEquals(1, playback.liked)
        model.playerPlayQueued(7)
        assertEquals(listOf(7L), playback.queuedPlayed.toList())
        model.playerSeek(4_000)
        assertEquals(listOf(4_000L), playback.seeks.toList())
        // Nothing queued to move, and no queue of Crosstune's to play on from.
        model.playerMoveQueued(0, 1)
        model.playerPlayLater(0)
        idle()
        model.playerSleep(10)
        assertTrue(model.uiState.sleep?.atMs != null)
        model.playerSleep(null)
        assertNull(model.uiState.sleep?.atMs)
        assertTrue(model.uiState.sleep != null)
        model.playerSleepOff()
        assertNull(model.uiState.sleep)
    }

    @Test
    fun aPlayerWhoseAppSaysNothingOfTheseButtonsLeavesThemBe() {
        val playback = FakePlayback()
        playback.playing.value = following(seek = false)
        val model = playingModel(playback)
        model.playerShuffle()
        model.playerRepeat()
        model.playerLike()
        model.playerSeek(3_000)
        model.playerSkip(next = true)
        assertTrue(playback.shuffled.isEmpty() && playback.repeated.isEmpty() && playback.liked == 0 && playback.seeks.isEmpty() && playback.skips.isEmpty())
        // A sleep timer needs none of that, and without one nothing is set.
        model.playerSleep(null)
        assertNull(model.uiState.sleep)
    }

    @Test
    fun aPlayerWhoseAppLetGoIsAskedToPlayTheSongAgain() {
        val playback = FakePlayback()
        val model = playingModel(playback)
        assertNull(model.uiState.player?.following)
        model.playerPlayPause()
        idle()
        // No app followed it, so it is asked of whichever plays.
        assertEquals(listOf<Pair<MusicMetadata, String?>>(song to null), playback.songsAsked.toList())
    }

    @Test
    fun theSongAppsMoveOnToIsTheNextOneOnceThePlayerPlayed() {
        val playback = FakePlayback()
        playback.playing.value = following()
        val model = playingModel(playback)
        val next = MusicMetadata("Calm", "Band")
        playback.playing.value = null
        playback.onThePhone = Heard.Song(next, null, app = "com.spotify")
        idle()
        assertEquals(next, model.uiState.player?.song)
    }

    private fun teachingCalls(model: MainViewModel) {
        model.explainLine(0)
        model.askAboutLine(0, "why")
        model.explainSong()
        model.askAboutSong("why")
        model.findWordsToKnow()
        model.aboutArtist()
        model.findExamples(0)
        model.lookUpWord(Word("a"), 0)
        model.toggleSavedLine(0)
        model.chooseLines(listOf(0), emptyList(), practise = true)
        model.practiseSong()
        model.toggleSavedWord(KeyWord("a", null, "b"))
        model.seekLyrics(1_000)
        model.syncLine(0)
        model.playPause()
        model.nudgeSync(100)
        model.resetSync()
        model.repeatLine(0)
        model.lyricsToPlayer()
        idle()
    }

    @Test
    fun theTeachingButtonsDoNothingWithoutWordsShownOrWithoutChatGpt() {
        val before = model().uiState.learning
        val chatGpt = ChatGpt(fake.client(), File(app.cacheDir, "chatgpt-vm.json"), "com.example.crosstune", Dispatchers.Unconfined)
        val tutor = ChatGptTutor(chatGpt, LookupCache(File(app.cacheDir, "answers-vm.json"), Dispatchers.Unconfined))
        for (made in listOf(model(), model(chatGpt = chatGpt, tutor = tutor))) {
            teachingCalls(made)
            assertEquals(before, made.uiState.learning)
            assertNull(made.uiState.lyricsFor)
            assertTrue(made.uiState.savedLines.isEmpty())
            assertNull(made.uiState.practice)
        }
        assertTrue(fake.requestedUrls.isEmpty())
    }

    private val timed = """[{"trackName":"Hush","artistName":"Band","duration":60,"syncedLyrics":"[00:00.00] One\n[00:10.00] Two\n[00:20.00] Three"}]"""

    /** The words of [song] shown over its player, timed as LRCLIB has them, followed in a music app that plays them. */
    private fun lyricsModel(
        playback: FakePlayback,
        answer: String? = timed,
        app: Following = following(),
        keptOnDisk: LookupCache? = null,
        reports: ReportSender? = null,
        words: Boolean = true
    ): MainViewModel {
        fake.handler = { request ->
            // No answer at all is a service that can't be reached.
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, answer ?: throw java.io.IOException("offline")) else FakeSpotify.html(request, "")
        }
        playback.playing.value = app
        val finder = LyricsFinder(fake.client(), "test", Dispatchers.Unconfined, busyPauseMs = 0)
        val model = playingModel(playback, lyricsFinder = finder, keptOnDisk = keptOnDisk, reports = reports)
        model.playerLyrics()
        if (words) until("found the words") { model.uiState.lyrics.isNotEmpty() }
        else until("looked for the words") { model.uiState.lyricsFor != null && !model.uiState.isLoadingLyrics }
        return model
    }

    private fun lrclibAsked() = fake.requestedUrls.count { "lrclib.net" in it }

    @Test
    fun timedWordsFollowTheAppAndItsButtonsMoveIt() {
        val playback = FakePlayback()
        val model = lyricsModel(playback)
        assertEquals(listOf("One", "Two", "Three"), model.uiState.lyricLines.map { it.text })
        model.seekLyrics(10_000)
        assertEquals(listOf(10_000L), playback.seeks.toList())
        model.playPause()
        assertEquals(listOf(false), playback.plays.toList())
        // Another line sung now puts the words in time, and putting them back undoes it.
        model.syncLine(1)
        assertTrue(model.uiState.lyricsSynced)
        model.nudgeSync(500)
        assertTrue(model.uiState.lyricsSynced)
        model.resetSync()
        assertFalse(model.uiState.lyricsSynced)
        model.repeatLine(1)
        assertEquals(1, model.uiState.repeating)
        assertEquals(10_000L, playback.seeks.last())
        model.stopRepeating()
        assertNull(model.uiState.repeating)
        // Back to the player: the song's words go, and its player stays.
        model.lyricsToPlayer()
        assertNull(model.uiState.lyricsFor)
        assertEquals(song, model.uiState.player?.song)
    }

    @Test
    fun linesOfTheWordsAreKeptAndLetGo() {
        val playback = FakePlayback()
        val model = lyricsModel(playback)
        model.toggleSavedLine(1)
        assertEquals(listOf("Two"), model.uiState.savedLines.map { it.text })
        assertEquals(10_000L, model.uiState.savedLines.single().startMs)
        model.toggleSavedLine(1)
        assertTrue(model.uiState.savedLines.isEmpty())
        // A line that isn't there is no line to keep.
        model.toggleSavedLine(9)
        model.chooseLines(listOf(2, 0), emptyList(), practise = false)
        assertEquals(listOf("One", "Three"), model.uiState.savedLines.map { it.text }.sorted())
        val one = model.uiState.savedLines.first { it.text == "One" }
        model.chooseLines(emptyList(), listOf(one), practise = false)
        assertEquals(listOf("Three"), model.uiState.savedLines.map { it.text })
        model.keepAgain(listOf(one))
        assertEquals(2, model.uiState.savedLines.size)
        model.toggleSavedWord(KeyWord("Two", null, "dos"))
        assertTrue(model.uiState.savedLines.any { it.text == "Two" && it.translation == "dos" })
        model.toggleSavedWord(KeyWord("Absent", null, "ausente"))
        assertTrue(model.uiState.savedLines.any { it.text == "Absent" })
        model.dismissLyrics()
        assertNull(model.uiState.lyricsFor)
    }

    @Test
    fun wordsWithoutTimingCantBeMovedOrRepeated() {
        val playback = FakePlayback()
        val model = lyricsModel(playback, answer = """[{"trackName":"Hush","artistName":"Band","duration":60,"plainLyrics":"Plain one\nPlain two"}]""")
        assertEquals("Plain one\nPlain two", model.uiState.lyrics)
        assertTrue(model.uiState.lyricLines.isEmpty())
        model.syncLine(0)
        model.nudgeSync(500)
        model.repeatLine(0)
        assertFalse(model.uiState.lyricsSynced)
        assertNull(model.uiState.repeating)
        model.toggleSavedLine(1)
        assertEquals(listOf("Plain two"), model.uiState.savedLines.map { it.text })
        assertNull(model.uiState.savedLines.single().startMs)
    }

    /** Idles until [done] holds, since the account is read and the browser answered on real threads. */
    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Never $what" }
            idle()
            Thread.sleep(10)
        }
    }

    private fun accountFile() = File(app.noBackupFilesDir, "chatgpt.json").apply { delete() }

    /** An account [openAi] answers for, signed in already when [signedIn]. */
    private fun account(openAi: FakeOpenAi, file: File, signedIn: Boolean): ChatGpt {
        fake.handler = { request -> openAi.answer(request) ?: FakeSpotify.html(request, "") }
        val account = ChatGpt(fake.client(), file, app.packageName)
        if (signedIn) {
            val result = kotlinx.coroutines.runBlocking {
                account.signIn(open = { url ->
                    openAi.authorize = url.toHttpUrl()
                    kotlin.concurrent.thread { openAi.comeBack() }
                })
            }
            assertEquals(ChatGptSignInResult.SIGNED_IN, result)
        }
        return account
    }

    @Test
    fun anAccountSignedInBeforeIsShownWithItsModelsAndTheOnePicked() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val account = account(openAi, accountFile(), signedIn = true)
        prefs().edit().putString("chatgpt_model", "gpt-6-terra").commit()
        val model = model(chatGpt = account)
        until("read the account") { model.uiState.chatGpt.models.isNotEmpty() }
        assertEquals(FakeOpenAi.EMAIL, model.uiState.chatGpt.email)
        assertEquals(listOf("gpt-6.1-sol", "gpt-6-terra"), model.uiState.chatGpt.models.map { it.slug })
        assertEquals("gpt-6-terra", account.picked)
        model.pickChatGptModel("gpt-6.1-sol")
        assertEquals("gpt-6.1-sol", model.uiState.chatGpt.model)
        assertEquals("gpt-6.1-sol", prefs().getString("chatgpt_model", null))
        assertEquals("gpt-6.1-sol", account.picked)
        model.signOutOfChatGpt()
        assertEquals(ChatGptState(), model.uiState.chatGpt)
        until("revoked") { fake.requestedUrls.any { it.startsWith(ChatGpt.REVOKE_URL) } }
    }

    @Test
    fun anAccountThatCantBeReadIsLeftSignedOut() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = model(chatGpt = account(openAi, accountFile(), signedIn = false))
        idle()
        Thread.sleep(200)
        idle()
        assertEquals(ChatGptState(), model.uiState.chatGpt)
    }

    @Test
    fun signingInWithChatGptOpensTheBrowserThenWelcomesOnceAndAsksTheModels() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = model(chatGpt = account(openAi, accountFile(), signedIn = false))
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        var done = 0
        model.signInWithChatGpt { done++ }
        assertTrue(model.uiState.chatGpt.signingIn)
        until("opened the browser") { effects.isNotEmpty() }
        val open = effects.single() as Effect.Open
        openAi.authorize = open.url.toHttpUrl()
        kotlin.concurrent.thread { openAi.comeBack() }
        until("signed in") { model.uiState.chatGpt.email != null && model.uiState.chatGpt.models.isNotEmpty() }
        assertEquals(FakeOpenAi.EMAIL, model.uiState.chatGpt.email)
        assertTrue(model.uiState.chatGpt.welcome)
        assertFalse(model.uiState.chatGpt.signingIn)
        assertEquals(1, done)
        model.dismissChatGptWelcome()
        assertFalse(model.uiState.chatGpt.welcome)
        assertTrue(prefs().getBoolean("chatgpt_welcomed", false))
    }

    @Test
    fun aSignInTheBrowserNeverAnswersEndsWhenCancelledAndOneThatReplacesItWinsTheEnd() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = model(chatGpt = account(openAi, accountFile(), signedIn = false))
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        var first = 0
        model.signInWithChatGpt { first++ }
        until("opened the browser") { effects.isNotEmpty() }
        model.cancelChatGptSignIn()
        assertFalse(model.uiState.chatGpt.signingIn)
        until("stopped") { first == 1 }
        // A second sign-in replacing a first one is the only one to say it's over.
        var second = 0
        var replaced = 0
        model.signInWithChatGpt { replaced++ }
        model.signInWithChatGpt { second++ }
        until("opened the browser again") { effects.size >= 2 }
        model.cancelChatGptSignIn()
        until("the second stopped") { second == 1 }
        assertEquals(0, replaced)
        assertEquals(ChatGptState(), model.uiState.chatGpt)
    }

    @Test
    fun aSignInTheBrowserRefusesShowsWhyAndSignsNobodyIn() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = model(chatGpt = account(openAi, accountFile(), signedIn = false))
        val effects = mutableListOf<Effect>()
        model.viewModelScope.launch { model.effects.collect { effects += it } }
        model.signInWithChatGpt { }
        until("opened the browser") { effects.isNotEmpty() }
        openAi.authorize = (effects.single() as Effect.Open).url.toHttpUrl()
        kotlin.concurrent.thread { openAi.comeBack("error=access_denied&state=${openAi.authorize.queryParameter("state")}") }
        until("failed") { model.uiState.chatGpt.problem != null }
        assertNull(model.uiState.chatGpt.email)
        assertFalse(model.uiState.chatGpt.welcome)
        assertFalse(model.uiState.chatGpt.signingIn)
    }

    private val catalogue = mapOf("Hush" to "Band", "Moon" to "Luna", "Stars" to "Orbit")

    /** Deezer holding the [catalogue] songs a search names, Apple nothing, and ChatGPT the account's. */
    private fun searchableCatalogue(openAi: FakeOpenAi) {
        fake.handler = { request ->
            val asked = request.url.queryParameter("q").orEmpty()
            when {
                openAi.answer(request) != null -> openAi.answer(request)!!
                request.url.host == "api.deezer.com" -> {
                    val tracks = catalogue.filter { (title, _) -> title in asked }.entries.joinToString(",") { (title, artist) ->
                        """{"id":${title.length},"title":"$title","link":"https://www.deezer.com/track/${title.length}","artist":{"name":"$artist"},"album":{"cover_big":"https://cdn/$title.jpg"}}"""
                    }
                    FakeSpotify.html(request, """{"data":[$tracks]}""")
                }
                request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                else -> FakeSpotify.html(request, "")
            }
        }
    }

    /** A model signed in with ChatGPT, which [openAi] answers for, searching [catalogue]. */
    private fun searchingModel(openAi: FakeOpenAi): MainViewModel {
        val account = account(openAi, accountFile(), signedIn = true)
        searchableCatalogue(openAi)
        val tutor = ChatGptTutor(account, LookupCache(File.createTempFile("answers", ".json", app.cacheDir).apply { delete() }, Dispatchers.Unconfined))
        val model = model(chatGpt = account, tutor = tutor, songSearcher = SongSearcher(fake.client(), country = "AR", ioDispatcher = Dispatchers.Unconfined))
        until("read the account") { model.uiState.chatGpt.email != null }
        return model
    }

    @Test
    fun aSearchWithNothingToSearchByOrNothingSearchedIsAnInvalidLink() {
        val searching = model(songSearcher = SongSearcher(fake.client(), ioDispatcher = Dispatchers.Unconfined))
        for ((model, words) in listOf(model() to "Hush Band", searching to "   ", searching to "https://example.com/song")) {
            model.searchSongs(words)
            idle()
            assertEquals(AppError.INVALID_URL, model.uiState.error)
            assertTrue(model.uiState.songSearch.isEmpty())
            assertFalse(model.uiState.isSearchingSongs)
        }
    }

    @Test
    fun aSongSearchShowsWhatTheCatalogueHoldsAndChoosingOneResolvesItsOwnLink() {
        searchableCatalogue(FakeOpenAi(System::currentTimeMillis))
        val model = model(songSearcher = SongSearcher(fake.client(), country = "AR", ioDispatcher = Dispatchers.Unconfined))
        model.searchSongs("  Hush  ")
        until("found") { model.uiState.songSearch.isNotEmpty() }
        assertEquals("Hush", model.uiState.songSearchQuery)
        assertEquals(listOf("Hush"), model.uiState.songSearch.map { it.title })
        assertFalse(model.uiState.isAskingForSongs)
        assertEquals(0, model.uiState.songsFromChatGpt)
        model.dismissSongSearch()
        assertTrue(model.uiState.songSearch.isEmpty())
        assertEquals("", model.uiState.songSearchQuery)
        // A song with a link of its own is opened as that link, as if it had been pasted.
        model.chooseSong(MusicMetadata("Hush", "Band", url = "https://www.deezer.com/track/4"))
        assertEquals("https://www.deezer.com/track/4", model.uiState.linkText)
        // One without is looked up by its name.
        model.chooseSong(MusicMetadata("Moon", "Luna"))
        assertEquals(MusicLinks.recognizedSong(MusicMetadata("Moon", "Luna")).text, model.uiState.linkText)
        model.openNamedSong("Stars", "Orbit")
        assertEquals(MusicLinks.recognizedSong(MusicMetadata("Stars", "Orbit")).text, model.uiState.linkText)
    }

    @Test
    fun aDescriptionIsAlsoAskedOfChatGptAndItsSongsLeadAsAPlaylist() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = searchingModel(openAi)
        openAi.reply = { _ ->
            200 to FakeOpenAi.stream("""[{"title":"Hush","artist":"Band"},{"title":"Moon","artist":"Luna"},{"title":"Made Up","artist":"Nobody"},{"title":"Stars","artist":"Orbit"},{"title":"hush","artist":"BAND"}]""")
        }
        // Too short a search to be a description: only the catalogue is asked.
        model.searchSongs("Hush")
        until("found") { model.uiState.songSearch.isNotEmpty() }
        assertTrue(openAi.asked.isEmpty())
        // Too few of ChatGPT's songs make no playlist.
        model.playlistFromSearch()
        assertNull(model.uiState.result)

        model.searchSongs("quiet songs about hush and moon")
        until("named") { model.uiState.songsFromChatGpt == 3 }
        assertEquals(listOf("Hush", "Moon", "Stars"), model.uiState.songSearch.map { it.title })
        assertFalse(model.uiState.isAskingForSongs)
        model.playlistFromSearch()
        val playlist = model.uiState.result!!
        assertEquals(ItemType.PLAYLIST, playlist.type)
        assertEquals("quiet songs about hush and moon", playlist.title)
        assertEquals(listOf("Hush", "Moon", "Stars"), playlist.tracks.map { it.title })
        assertEquals("https://cdn/Hush.jpg", playlist.artworkUrl)
        assertTrue(model.uiState.songSearch.isEmpty())
        assertFalse(model.uiState.songsLike)
    }

    @Test
    fun aDescriptionChatGptNamesNothingRealForLeavesTheCatalogueSongsAlone() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = searchingModel(openAi)
        openAi.reply = { _ -> 200 to FakeOpenAi.stream("""[{"title":"Made Up","artist":"Nobody"}]""") }
        model.searchSongs("a song named Hush maybe")
        until("asked") { openAi.asked.isNotEmpty() }
        until("answered") { !model.uiState.isAskingForSongs && model.uiState.songSearchQuery.isNotEmpty() }
        assertEquals(0, model.uiState.songsFromChatGpt)
        assertEquals(listOf("Hush"), model.uiState.songSearch.map { it.title })
    }

    @Test
    fun songsLikeOneAreFoundInTheCatalogueAndSayWhenNoneAre() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val model = searchingModel(openAi)
        openAi.reply = { _ -> 200 to FakeOpenAi.stream("""[{"title":"Moon","artist":"Luna"},{"title":"Made Up","artist":"Nobody"},{"title":"Stars","artist":"Orbit"},{"title":"Swapped","artist":"Moon"}]""") }
        model.songsLike(song)
        until("listed") { model.uiState.songsLike }
        assertFalse(model.uiState.isLoading)
        assertEquals(listOf("Moon", "Stars"), model.uiState.result?.tracks?.map { it.title })
        assertEquals("Hush", model.uiState.result?.title)
        // None of them in the catalogue is an error to read, not an empty list.
        openAi.reply = { _ -> 200 to FakeOpenAi.stream("""[{"title":"Made Up","artist":"Nobody"}]""") }
        model.songsLike(MusicMetadata("Other", "Band"))
        until("failed") { model.uiState.error == AppError.NO_SONGS_LIKE }
        assertFalse(model.uiState.isLoading)
        // Without ChatGPT or a catalogue there is nothing to ask.
        val before = model().uiState
        val bare = model()
        bare.songsLike(song)
        assertEquals(before, bare.uiState)
    }

    @Test
    fun wordsLeftAreKeptAndComeBackWithoutAskingAgain() {
        val model = lyricsModel(FakePlayback())
        val asked = lrclibAsked()
        assertTrue(asked > 0)
        model.keep()
        model.dismissLyrics()
        assertEquals("", model.uiState.lyrics)
        model.playerLyrics()
        idle()
        assertEquals("One\nTwo\nThree", model.uiState.lyrics)
        assertEquals(3, model.uiState.lyricLines.size)
        assertEquals(asked, lrclibAsked())
        assertFalse(model.uiState.isLoadingLyrics)
    }

    @Test
    fun wordsKeptOnDiskShowAtOnceInTheNextSession() {
        val disk = File(app.cacheDir, "kept-vm.json").apply { delete() }
        val first = lyricsModel(FakePlayback(), keptOnDisk = LookupCache(disk, Dispatchers.Unconfined))
        first.keep()
        first.dismissLyrics()
        idle()
        // Another session whose services have nothing shows the words it kept.
        val second = lyricsModel(FakePlayback(), answer = "[]", keptOnDisk = LookupCache(disk, Dispatchers.Unconfined))
        assertEquals("One\nTwo\nThree", second.uiState.lyrics)
    }

    @Test
    fun wordsNoServiceHasOfferToReportAndAreReportedOnceTheUserAllows() {
        val sender = ReportSender(fake.client(), "9.9.9", Dispatchers.Unconfined)
        val model = lyricsModel(FakePlayback(), answer = "[]", reports = sender, words = false)
        assertEquals("", model.uiState.lyrics)
        assertFalse(model.uiState.lyricsFailed)
        assertTrue(model.uiState.reportOffer)
        assertTrue(fake.requestedUrls.none { it.startsWith(ReportSender.URL) })
        model.allowAutoReports(true)
        assertFalse(model.uiState.reportOffer)
        assertTrue(model.uiState.autoReports == true)
        until("reported") { fake.requestedUrls.any { it.startsWith(ReportSender.URL) } }
        until("remembered") { AutoReports(prefs()).reported(song, SongProblem.NO_LYRICS) }
        // Reported once: finding the same again sends nothing more.
        model.dismissLyrics()
        val sent = fake.requestedUrls.count { it.startsWith(ReportSender.URL) }
        model.playerLyrics()
        until("looked again") { model.uiState.lyricsFor != null && !model.uiState.isLoadingLyrics }
        idle()
        assertEquals(sent, fake.requestedUrls.count { it.startsWith(ReportSender.URL) })
    }

    @Test
    fun wordsNoServiceHasAreNotOfferedAgainOnceTheUserRefused() {
        val model = lyricsModel(FakePlayback(), answer = "[]", reports = ReportSender(fake.client(), "9.9.9", Dispatchers.Unconfined), words = false)
        assertTrue(model.uiState.reportOffer)
        model.allowAutoReports(false)
        assertFalse(model.uiState.reportOffer)
        model.dismissLyrics()
        model.playerLyrics()
        until("looked again") { model.uiState.lyricsFor != null && !model.uiState.isLoadingLyrics }
        assertFalse(model.uiState.reportOffer)
        assertTrue(fake.requestedUrls.none { it.startsWith(ReportSender.URL) })
    }

    @Test
    fun wordsThatCantBeLookedUpSayItAndRetryAsksAgain() {
        val playback = FakePlayback()
        val model = lyricsModel(playback, answer = null, words = false)
        assertTrue(model.uiState.lyricsFailed)
        assertEquals("", model.uiState.lyrics)
        fake.handler = { request -> if (request.url.host == "lrclib.net") FakeSpotify.html(request, timed) else FakeSpotify.html(request, "") }
        model.showLyrics()
        until("found the words") { model.uiState.lyrics.isNotEmpty() }
        assertFalse(model.uiState.lyricsFailed)
    }

    @Test
    fun aReportIsSentWithTheCommentAndSaysHowItWent() {
        val model = lyricsModel(FakePlayback(), reports = ReportSender(fake.client(), "9.9.9", Dispatchers.Unconfined))
        model.sendReport("Out of time")
        until("sent") { model.uiState.report == ReportStatus.SENT }
        assertTrue(fake.requestedUrls.any { it.startsWith(ReportSender.URL) })
        model.closeReport()
        assertNull(model.uiState.report)
        val none = model()
        none.sendReport("Nothing to send with")
        assertNull(none.uiState.report)
    }
}
