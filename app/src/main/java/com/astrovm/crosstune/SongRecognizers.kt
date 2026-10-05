package com.astrovm.crosstune

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** An app that names a song playing nearby, and how to start it listening. */
internal data class SongRecognizer(val packageName: String, val label: String, val intent: Intent)

/**
 * Apps that name a song playing nearby. Crosstune doesn't listen itself, which would need the
 * microphone; it opens one of these, and the user shares the song they find back to it.
 */
internal object SongRecognizers {
    const val SHAZAM = "com.shazam.android"
    /** Starts listening right away, as Shazam's own widget does. */
    const val SHAZAM_LISTEN = "com.shazam.android.intent.actions.START_TAGGING"
    const val GOOGLE = "com.google.android.googlequicksearchbox"
    /** The Google app's song search, which also listens right away. */
    const val GOOGLE_SONG_SEARCH = "com.google.android.googlequicksearchbox.MUSIC_SEARCH"
    /** The app picked in Settings, by package name. */
    const val KEY_PICK = "song_recognizer"

    /** Those installed, Shazam first: listening right away, or else just opened. */
    fun available(packageManager: PackageManager): List<SongRecognizer> = listOfNotNull(
        listOfNotNull(Intent(SHAZAM_LISTEN).setPackage(SHAZAM), packageManager.getLaunchIntentForPackage(SHAZAM))
            .firstOrNull { it.resolveActivity(packageManager) != null }
            ?.let { SongRecognizer(SHAZAM, label(packageManager, SHAZAM, "Shazam"), it) },
        Intent(GOOGLE_SONG_SEARCH).setPackage(GOOGLE).takeIf { it.resolveActivity(packageManager) != null }
            ?.let { SongRecognizer(GOOGLE, label(packageManager, GOOGLE, "Google"), it) }
    )

    /** The one the Recognize buttons open: the pick in Settings, or else the first there is. */
    fun chosen(packageManager: PackageManager, pick: String?): SongRecognizer? {
        val all = available(packageManager)
        return all.firstOrNull { it.packageName == pick } ?: all.firstOrNull()
    }

    private fun label(packageManager: PackageManager, packageName: String, fallback: String): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
}

/**
 * Starts the song recognition app from the widget, then closes, unseen. The widget can't start it
 * itself: Glance gives every intent its own data, which the recognition apps' actions don't take.
 * Picking the app here, at the tap, also follows a change in Settings.
 */
class RecognizeSongActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pick = getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE).getString(SongRecognizers.KEY_PICK, null)
        // An app uninstalled since the widget last looked has nothing to open.
        SongRecognizers.chosen(packageManager, pick)?.let { runCatching { startActivity(it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        finish()
    }
}
