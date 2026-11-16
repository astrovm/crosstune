package com.astrovm.crosstune

import android.app.Instrumentation
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Observe the app's real follower while VLC plays one invented local fixture. It sends no controls or network requests. */
class RecordingNameCheck : Instrumentation() {
    private var asked = ""
    private var playing = ""
    private var expected = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        asked = arguments?.getString("asked").orEmpty()
        playing = arguments?.getString("playing").orEmpty()
        expected = arguments?.getString("matches").orEmpty() == "true"
        start()
    }

    override fun onStart() {
        try {
            check(asked in FIXTURE_TITLES && playing in FIXTURE_TITLES) { "Use the invented recording-name fixtures only" }
            val manager = targetContext.getSystemService(MediaSessionManager::class.java)
            val vlc = manager.getActiveSessions(ComponentName(targetContext, NowPlayingListener::class.java))
                .singleOrNull { it.packageName == VLC }
            check(vlc?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == playing) { "VLC must hold the requested fixture before observation" }
            val source = MediaSessionPlayback(targetContext)
            var emissions = 0
            var matched = false
            runBlocking {
                val job = launch(Dispatchers.Main) {
                    source.follow(MusicMetadata(asked, ARTIST)).collect {
                        emissions++
                        check(it == null || it.appPackage == VLC) { "An unexpected app matched the fixture" }
                        matched = matched || it != null
                    }
                }
                delay(OBSERVE_MS)
                job.cancelAndJoin()
            }
            check(emissions > 0) { "The follower emitted no observation" }
            finish(if (matched == expected) 0 else 1, Bundle().apply {
                putString("result", if (matched == expected) "pass" else "wrong_match")
                putBoolean("matched", matched)
                putBoolean("expected_match", expected)
                putInt("emissions", emissions)
                putBoolean("vlc_fixture_verified", true)
            })
        } catch (error: Exception) {
            finish(1, Bundle().apply { putString("error", error.toString()) })
        }
    }

    private companion object {
        const val VLC = "org.videolan.vlc"
        const val ARTIST = "Fixture Utaite"
        const val OBSERVE_MS = 3_000L
        val FIXTURE_TITLES = setOf(
            "Paperkite (Live)", "Paperkite Meadow (Live)", "Paperkite【Live】", "Paperkite Meadow【Live】",
            "愛【Cover】", "恋【Cover】", "01 Live Forever", "Live Forever",
            "Paperkite - English Version", "Paperkite Meadow - English Version", "Piano Paperkite", "The Piano Paperkite"
        )
    }
}
