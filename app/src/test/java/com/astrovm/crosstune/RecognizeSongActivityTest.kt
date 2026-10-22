package com.astrovm.crosstune

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The widget's song recognition button, which starts Crosstune listening. */
@RunWith(RobolectricTestRunner::class)
class RecognizeSongActivityTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    private fun install(packageName: String, label: String, action: String) {
        val pm = shadowOf(app.packageManager)
        pm.installPackage(installedApp(packageName, label))
        val component = ComponentName(packageName, action)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) })
    }

    /** Taps the button, and returns what it started, closing either way. */
    private fun tap(): Intent {
        val activity = Robolectric.buildActivity(RecognizeSongActivity::class.java).create().get()
        assertTrue(activity.isFinishing)
        return shadowOf(activity).nextStartedActivity
    }

    private fun assertListens(started: Intent) {
        assertEquals(MainActivity.ACTION_LISTEN, started.action)
        assertEquals(MainActivity.LISTEN_ALIAS, started.component!!.className)
        // It starts from the home screen, outside any task of Crosstune's.
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun crosstuneListensEvenWithOtherAppsOrAnOldPick() {
        assertListens(tap())
        install(SongRecognizers.GOOGLE, "Google", SongRecognizers.GOOGLE_SONG_SEARCH)
        install(SongRecognizers.SHAZAM, "Shazam", SongRecognizers.SHAZAM_LISTEN)
        assertListens(tap())
        // Picked in Settings back when there was a choice, Shazam still isn't opened.
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString("song_recognizer", SongRecognizers.SHAZAM).commit()
        assertListens(tap())
    }
}
