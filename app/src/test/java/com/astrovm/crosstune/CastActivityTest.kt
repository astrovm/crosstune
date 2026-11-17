package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import org.robolectric.shadows.ShadowSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Casting through the screens: the phone's Cast button and its player as the remote, and Crosstune on a TV answering. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class CastActivityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null
    private var phone = Phone.OTHER
    private val cast = FakeCast()
    private val playback = FakePlayback().apply { access = true; running = emptySet() }
    private val link = "https://music.youtube.com/watch?v=sPmul8b17AU"
    private val tvState = CastTvState("Demo", "Band", playing = true, positionMs = 0, durationMs = 60_000, canSeek = true, canSkipNext = true, canSkipPrevious = true)
    private val realPhone = MainActivity.phoneFactory

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.phoneFactory = { phone }
        MainActivity.checksForUpdates = { false }
        MainActivity.shellFactory = { emptyList() }
        MainActivity.playbackFactory = { playback }
        MainActivity.castSenderFactory = { cast }
        MainActivity.castReceiverFactory = { cast }
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().putString("default_target", "YOUTUBE_MUSIC").commit()
        shadowOf(app.packageManager).installPackage(installedApp(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music"))
        shadowOf(app.packageManager).installPackage(installedApp(MusicService.YOUTUBE_TV, "YouTube"))
        ShadowSettings.setCanDrawOverlays(true)
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                if (request.url.host == "lrclib.net") """[{"trackName":"Demo","artistName":"Band","duration":60,"syncedLyrics":"[00:00.00] Demo one\n[00:10.00] Demo two"}]"""
                else """{"title":"Demo","author_name":"Band"}"""
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.phoneFactory = realPhone
        MainActivity.checksForUpdates = ::looksForUpdates
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.castSenderFactory = ::platformCastSender
        MainActivity.castReceiverFactory = ::platformCastReceiver
        FloatingLyricsService.host = null
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(TIMEOUT_MS) { Snapshot.sendApplyNotifications(); shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    private fun nodes(text: String) = composeRule.onAllNodes(hasText(text) or hasContentDescription(text)).fetchSemanticsNodes()

    private fun click(text: String) {
        waitUntil { composeRule.onAllNodes((hasText(text) or hasContentDescription(text)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(hasText(text) or hasContentDescription(text))[0].performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    /** The song's card, shown as a link shared to Crosstune is. */
    private fun launch(): ActivityController<MainActivity> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        return Robolectric.buildActivity(MainActivity::class.java, intent).setup().also {
            controller = it
            composeRule.waitForIdle()
        }
    }

    @Test
    fun theCastButtonShowsOnlyWithATvAroundAndHandsTheSongOverAfterAskingWhich() {
        launch()
        waitUntil { nodes(string(R.string.lyrics_button)).isNotEmpty() }
        assertTrue(nodes(string(R.string.cast_to_tv)).isEmpty())
        cast.around.value = true
        waitUntil { nodes(string(R.string.cast_to_tv)).isNotEmpty() }

        click(string(R.string.cast_to_tv))
        assertEquals(1, cast.chosen)
        cast.target.value = CastTarget("Living room")
        waitUntil { cast.loaded.isNotEmpty() }
        assertEquals(CastSong("Demo", "Band", link), cast.loaded.single().copy(artworkUrl = null, durationMs = null))

        // Lit with the TV's name, and the song's player is its remote.
        val casting = string(R.string.casting_to_tv, "Living room")
        waitUntil { nodes(casting).isNotEmpty() }
        cast.target.value = CastTarget("Living room", tvState)
        waitUntil { nodes(string(R.string.lyrics_pause)).isNotEmpty() }
        click(string(R.string.lyrics_pause))
        click(string(R.string.lyrics_next_song))
        assertEquals(listOf(CastCommand.Pause, CastCommand.Next), cast.sent.toList())

        // Pressed with the song there, it shows the TV rather than casting it again.
        click(casting)
        assertEquals(1, cast.shown)
        assertEquals(1, cast.loaded.size)
    }

    @Test
    fun aPausedTvShowsPlayAndDisablesTheControlsItDoesNotOffer() {
        cast.around.value = true
        cast.target.value = CastTarget("Living room")
        launch()
        click(string(R.string.casting_to_tv, "Living room"))
        waitUntil { cast.loaded.isNotEmpty() }
        cast.target.value = CastTarget("Living room", tvState.copy(playing = false, canSeek = false, canSkipNext = false, canSkipPrevious = false))
        waitUntil { nodes(string(R.string.lyrics_play)).isNotEmpty() }
        composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_next_song)))[0].assertIsNotEnabled()
        composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_previous_song)))[0].assertIsNotEnabled()
        click(string(R.string.lyrics_play))
        assertEquals(listOf(CastCommand.Play), cast.sent.toList())
        assertTrue(playback.plays.isEmpty())
    }

    @Test
    fun stoppingACastOnTheTvClosesItsLyricsAsWellAsItsPlayer() {
        phone = Phone.TV
        launch()
        click(string(R.string.lyrics_button))
        waitUntil { nodes("Demo one").isNotEmpty() }
        app.mainExecutor.execute { requireNotNull(cast.onCommand)(CastCommand.Stop) }
        waitUntil { nodes("Demo one").isEmpty() }
        assertTrue(nodes(string(R.string.lyrics_pause)).isEmpty())
        assertTrue(cast.published.lastOrNull() == null)
    }

    @Test
    fun theCastButtonIsAlsoOnTheWordsOfTheSong() {
        cast.around.value = true
        launch()
        click(string(R.string.lyrics_button))
        waitUntil { nodes("Demo one").isNotEmpty() }
        click(string(R.string.cast_to_tv))
        assertEquals(1, cast.chosen)
    }

    @Test
    fun onATvCrosstuneAnswersThePhonesThatCastToIt() {
        phone = Phone.TV
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        composeRule.waitForIdle()
        assertEquals(1, cast.started)

        // A song cast to it plays, as the one opened there does, and the phones see it.
        app.mainExecutor.execute { requireNotNull(cast.onLoad)(CastSong("Demo", "Band", link)) }
        waitUntil { playback.opened.isNotEmpty() }
        assertEquals(MusicService.YOUTUBE_MUSIC.packageName to link, playback.opened.single())
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube", canSeek = true, appPackage = MusicService.YOUTUBE_TV)
        waitUntil { cast.published.lastOrNull()?.playing == true }
        assertEquals("Demo", cast.published.last()?.title)

        // What the phones ask is what the remote would.
        app.mainExecutor.execute { requireNotNull(cast.onCommand)(CastCommand.Pause) }
        waitUntil { playback.plays.isNotEmpty() }
        assertEquals(listOf(false), playback.plays.toList())
    }

    @Test
    fun recreatingTheTvKeepsOneReceiverUntilTheActivityFinishes() {
        phone = Phone.TV
        val original = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }
        composeRule.waitForIdle()
        assertEquals(1, cast.started)

        val recreated = original.recreate().also { controller = it }
        composeRule.waitForIdle()
        assertEquals(1, cast.started)
        assertEquals(0, cast.stopped)
        val model = ViewModelProvider(recreated.get())[MainViewModel::class.java]
        app.mainExecutor.execute { requireNotNull(cast.onCommand)(CastCommand.Translation(true)) }
        waitUntil { model.uiState.learning.translation }

        recreated.get().finish()
        recreated.destroyAndSettle()
        assertEquals(1, cast.stopped)
        controller = null
    }

    @Test
    fun aStoppedLookupCannotPublishOrPlayAfterItsAnswerArrives() {
        phone = Phone.TV
        val reading = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val normal = fake.handler
        fake.handler = { request ->
            if (request.url.encodedPath == "/oembed") {
                reading.countDown()
                check(answer.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                normal(request).also { answered.countDown() }
            } else normal(request)
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }.get()
        val model = ViewModelProvider(activity)[MainViewModel::class.java]
        try {
            app.mainExecutor.execute { requireNotNull(cast.onLoad)(CastSong("Demo", "Band", link)) }
            waitUntil { reading.count == 0L }
            assertTrue(model.uiState.isLoading)
            app.mainExecutor.execute { requireNotNull(cast.onCommand)(CastCommand.Stop) }
            composeRule.waitForIdle()

            answer.countDown()
            waitUntil { answered.count == 0L && !model.uiState.isLoading }
            assertNull(model.uiState.result)
            assertTrue(cast.published.all { it == null })
            assertTrue(playback.songsAsked.isEmpty() && playback.opened.isEmpty())
        } finally {
            answer.countDown()
        }

        // The next LOAD can still play.
        app.mainExecutor.execute { requireNotNull(cast.onLoad)(CastSong("Demo", "Band", link)) }
        waitUntil { playback.opened.isNotEmpty() }
        assertEquals(MusicService.YOUTUBE_MUSIC.packageName to link, playback.opened.single())
    }

    @Test
    fun aReplacementCastCanUseThePermissionTheStoppedCastWasWaitingFor() {
        phone = Phone.TV
        ShadowSettings.setCanDrawOverlays(false)
        val shell = FakeShell { command ->
            if ("SYSTEM_ALERT_WINDOW" in command) ShadowSettings.setCanDrawOverlays(true)
            true
        }.apply { answer = CompletableDeferred() }
        MainActivity.shellFactory = { listOf(shell) }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }.get()
        val model = ViewModelProvider(activity)[MainViewModel::class.java]
        app.mainExecutor.execute { requireNotNull(cast.onLoad)(CastSong("Demo", "Band", link)) }
        waitUntil { model.uiState.fixingInOneTap == OneTapFix.FLOAT }
        app.mainExecutor.execute { requireNotNull(cast.onCommand)(CastCommand.Stop) }
        waitUntil { model.uiState.result == null }

        val replacement = "https://music.youtube.com/watch?v=castDemo123"
        app.mainExecutor.execute { requireNotNull(cast.onLoad)(CastSong("Another", "Band", replacement)) }
        waitUntil { model.uiState.link?.url == replacement }
        assertTrue(playback.opened.isEmpty())
        shell.answer.complete(Unit)

        waitUntil { playback.opened.isNotEmpty() }
        assertEquals(MusicService.YOUTUBE_MUSIC.packageName to replacement, playback.opened.single())
        assertEquals(1, shell.ran.count { "SYSTEM_ALERT_WINDOW" in it })
    }

    @Test
    fun anIntentThatIsAPhonesCastIsTheReceiversAndAnyOtherIsCrosstunes() {
        phone = Phone.TV
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().also { this.controller = it }
        composeRule.waitForIdle()
        val before = cast.handled.size
        cast.handles = true
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java))
        composeRule.waitForIdle()
        assertEquals(before + 1, cast.handled.size)
        assertTrue(nodes("Demo").isEmpty())
        cast.handles = false
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java))
        waitUntil { nodes("Demo").isNotEmpty() }

        // Left for good, it stops answering.
        controller.get().finish()
        controller.pause().stop().destroy()
        assertEquals(1, cast.stopped)
        this.controller = null
    }

    @Test
    fun aPhoneDoesNotAnswerCastsAndATvDoesNotCastItself() {
        launch()
        waitUntil { nodes(string(R.string.lyrics_button)).isNotEmpty() }
        assertEquals(0, cast.started)
        assertFalse(cast.target.value != null)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
