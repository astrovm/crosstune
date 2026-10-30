package com.astrovm.crosstune

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The apps offered to name a song when Crosstune can't reach Shazam. */
@RunWith(RobolectricTestRunner::class)
class SongRecognizersTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun listens(packageName: String, action: String) {
        val component = ComponentName(packageName, "Listen")
        shadowOf(app.packageManager).addActivityIfNotPresent(component)
        shadowOf(app.packageManager).addIntentFilterForActivity(component, IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) })
    }

    private fun labels() = SongRecognizers.available(app).map { it.label }

    @Test
    fun withNoOtherAppCrosstuneIsTheOnlyOne() {
        assertEquals(listOf(app.getString(R.string.app_name)), labels())
    }

    @Test
    fun anAppIsNamedAsItsInstalledNameOrElseAsItsOwnWords() {
        // Its name is blank.
        shadowOf(app.packageManager).installPackage(installedApp(SongRecognizers.GOOGLE, ""))
        listens(SongRecognizers.GOOGLE, SongRecognizers.GOOGLE_SONG_SEARCH)
        assertEquals(listOf(app.getString(R.string.app_name), "Google"), labels())

        shadowOf(app.packageManager).installPackage(installedApp(SongRecognizers.GOOGLE, "Google App"))
        shadowOf(app.packageManager).installPackage(installedApp(SongRecognizers.SHAZAM, "Shazam Music"))
        listens(SongRecognizers.GOOGLE, SongRecognizers.GOOGLE_SONG_SEARCH)
        listens(SongRecognizers.SHAZAM, SongRecognizers.SHAZAM_LISTEN)
        assertEquals(listOf(app.getString(R.string.app_name), "Shazam Music", "Google App"), labels())
    }
}
