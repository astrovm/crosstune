package com.astrovm.crosstune

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.net.URLDecoder

/** A drawn-out ending tapped in the app is looked up as the particle it is. */
@RunWith(RobolectricTestRunner::class)
class ReadingsParticleTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null
    private val realUniDic = MainActivity.uniDicFactory

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.shellFactory = { emptyList() }
        MainActivity.checksForUpdates = { false }
        MainActivity.playbackFactory = { FakePlayback().apply { access = true } }
        MainActivity.milkdropFactory = { NoMilkdrop }
        MainActivity.soundTapFactory = { null }
        MainActivity.uniDicFactory = { _, _ -> null }
        MainActivity.lookAheadLyrics = false
        MainActivity.lyricsBusyPauseMs = 0
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear()
            .putBoolean("setup_complete", true).putString("default_target", "YOUTUBE_MUSIC")
            .putBoolean("play_right_away", false).putBoolean("visuals", false)
            .putString("saved_lines", """[{"text":"まってよぉ","reading":"まってよお","title":"Demo","artist":"Band","ruby":[["まってよぉ",null]],"readWith":13}]""")
            .putBoolean("lyrics_translation", true).commit()
        fake.handler = { request ->
            val body = when (request.url.host) {
                "clients5.google.com" -> JSONArray(FakeSpotify.bodyOf(request).split("&").map {
                    JSONArray().put("[${URLDecoder.decode(it.removePrefix("q="), "UTF-8")}]").put("ja")
                }).toString()
                "lrclib.net" -> """[{"trackName":"Demo","artistName":"Band","plainLyrics":"まってよぉ\nまってよぉぉ"}]"""
                else -> """{"title":"Demo","author_name":"Band"}"""
            }
            FakeSpotify.html(request, body)
        }
    }

    @After
    fun tearDown() {
        controller?.destroyAndSettle()
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.checksForUpdates = ::looksForUpdates
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.milkdropFactory = ::NativeMilkdrop
        MainActivity.soundTapFactory = MainActivity.phoneSound::open
        MainActivity.uniDicFactory = realUniDic
        MainActivity.lookAheadLyrics = true
        MainActivity.lyricsBusyPauseMs = LYRICS_BUSY_PAUSE_MS
    }

    @Test
    fun tappingADrawnOutYoLooksUpTheParticle() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=sPmul8b17AU"))
            .setClass(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        // A line read before this fix is read again when the app opens, including its kana spelling.
        waitUntil {
            SavedLinesStore(app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE))
                .load().single().reading == null
        }
        val lyrics = hasText(app.getString(R.string.lyrics_button)) or hasContentDescription(app.getString(R.string.lyrics_button))
        waitUntil { composeRule.onAllNodes(lyrics).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(lyrics)[0].performScrollTo().performClick()
        val line = hasText("まってよぉ") and hasTestTag(LYRIC_LINE_TAG)
        waitUntil { composeRule.onAllNodes(line).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(line).performClick()
        val study = hasText("まってよぉ") and hasTestTag(STUDY_LINE_TAG)
        waitUntil {
            runCatching {
                composeRule.onNode(study).performFirstLinkClick { (it.item as LinkAnnotation.Clickable).tag == "よぉ" }
            }.isSuccess
        }
        waitUntil { composeRule.onAllNodes(hasText("[よ]")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitUntil(condition: () -> Boolean) = composeRule.waitUntil(5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private object NoMilkdrop : Milkdrop {
        override fun open(width: Int, height: Int) = false
        override fun size(width: Int, height: Int) = Unit
        override fun show(preset: String, smooth: Boolean) = false
        override fun hear(samples: ByteArray, count: Int) = Unit
        override fun draw() = Unit
        override fun darken(shade: Float, ceiling: Float) = Unit
        override fun finish() = Unit
        override fun close() = Unit
    }
}
