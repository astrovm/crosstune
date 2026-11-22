package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** A repeated preview end learned through the card's visible controls, with the app's real view model. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class PracticeStopsTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val phone = FakePlayback().apply { access = true; obeys = true; clock = SystemClock::elapsedRealtime }
    private val player = FakeLinePlayer(app)
    private val sites = FakeSpotify()
    private val realPlayer = MainActivity.linePlayerFactory
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { sites.client() }
        MainActivity.playbackFactory = { phone }
        MainActivity.linePlayerFactory = { player }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        val prefs = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("setup_complete", true).putBoolean("visuals", false).putBoolean(KEY_ASKED_RETURN, true).commit()
        saveCardTypes(prefs, listOf(READ_IT.copy(on = false), HEAR_IT.copy(on = true), TRANSLATE_IT))
        SavedLinesStore(prefs).addAll(listOf(SavedLine("Night sky", translation = "Cielo", title = "Demo", artist = "Band", startMs = 27_000, endMs = 33_000)))
        sites.handler = { request ->
            if (request.url.host == "music.youtube.com") FakeSpotify.html(request, """{"contents":[{"musicResponsiveListItemRenderer":{
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Demo"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Band • Album • 3:20"}]}}}],
                "playlistItemData":{"videoId":"demo0000000"}}}]}""")
            else FakeSpotify.html(request, "{}")
        }
    }

    @After
    fun tearDown() {
        controller?.destroyAndSettle()
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.linePlayerFactory = realPlayer
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
    }

    private fun waitUntil(condition: () -> Boolean) = composeRule.waitUntil(60_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        condition()
    }

    @Test
    fun repeatedCardsUseThePlayerOnceTheAppsFixedEndIsConfirmed() {
        val tidal = MusicService.TIDAL.packageName
        phone.playing.value = Following(PlaybackClock(27_000, SystemClock.elapsedRealtime()), "TIDAL", canSeek = true, appPackage = tidal, durationMs = 200_000)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val model = ViewModelProvider(checkNotNull(controller).get())[MainViewModel::class.java]
        composeRule.runOnIdle { model.startPractice() }
        repeat(3) { attempt ->
            if (attempt < 2) {
                try {
                    waitUntil { phone.plays.count { it } == attempt + 1 && model.uiState.practice?.sound == LineSound.PLAYING && model.uiState.practice?.video == null }
                } catch (error: Exception) {
                    throw AssertionError("Attempt $attempt: ${model.uiState.practice}, plays=${phone.plays}, seeks=${phone.seeks}, clock=${phone.playing.value}", error)
                }
                phone.playing.value = checkNotNull(phone.playing.value).copy(clock = PlaybackClock(29_952, SystemClock.elapsedRealtime(), playing = false))
            }
            waitUntil { player.shown.size == attempt + 1 }
            composeRule.runOnIdle { player.end(LineEnd.ENDED) }
            if (attempt < 2) {
                composeRule.onNodeWithText(app.getString(R.string.practice_show)).assertIsEnabled().performClick()
                composeRule.onNodeWithText(app.getString(R.string.practice_again)).assertIsEnabled().performClick()
            }
        }
        assertEquals(2, phone.plays.count { it })
        assertEquals(listOf(27_000L, 27_000L), phone.seeks.toList())
        assertEquals(emptyList<Pair<String, String>>(), phone.opened.toList())
    }
}
