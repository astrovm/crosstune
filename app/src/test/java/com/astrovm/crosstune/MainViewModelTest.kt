package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Dispatchers
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
        phone: Phone = Phone.OTHER
    ): MainViewModel {
        val client = fake.client()
        return MainViewModel(
            LinkResolver(client, Dispatchers.Unconfined),
            ExactMatcher(client, ioDispatcher = Dispatchers.Unconfined),
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
            phone = phone
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
}
