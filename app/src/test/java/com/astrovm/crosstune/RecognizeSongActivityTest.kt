package com.astrovm.crosstune

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The widget's song recognition button, which picks the app at the tap. */
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
    private fun tap(): Intent? {
        val activity = Robolectric.buildActivity(RecognizeSongActivity::class.java).create().get()
        assertTrue(activity.isFinishing)
        return shadowOf(activity).nextStartedActivity
    }

    @Test
    fun itOpensThePickedAppThenShazamThenGoogle() {
        install(SongRecognizers.GOOGLE, "Google", SongRecognizers.GOOGLE_SONG_SEARCH)
        assertEquals(SongRecognizers.GOOGLE_SONG_SEARCH, tap()!!.action)

        install(SongRecognizers.SHAZAM, "Shazam", SongRecognizers.SHAZAM_LISTEN)
        val shazam = tap()!!
        assertEquals(SongRecognizers.SHAZAM_LISTEN, shazam.action)
        // It's not part of Crosstune's own task.
        assertTrue(shazam.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        // A pick in Settings comes first, from the next tap.
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(SongRecognizers.KEY_PICK, SongRecognizers.GOOGLE).commit()
        assertEquals(SongRecognizers.GOOGLE_SONG_SEARCH, tap()!!.action)
    }

    @Test
    fun withNoAppItJustCloses() {
        assertNull(tap())
    }
}
