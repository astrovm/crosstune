package com.astrovm.crosstune

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * An app that names a song playing nearby, and how to start it listening. [listensHere] for
 * Crosstune itself, which listens on its own screen.
 */
internal data class SongRecognizer(val packageName: String, val label: String, val intent: Intent, val listensHere: Boolean = false)

/**
 * What names a song playing nearby: Crosstune itself, or Shazam or Google, whose songs the user
 * shares back to it.
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

    /**
     * Crosstune first, so it's the one used unless another is picked in Settings, then those
     * installed: Shazam, listening right away or else just opened, and Google.
     */
    fun available(context: Context): List<SongRecognizer> = listOf(
        SongRecognizer(
            context.packageName,
            context.getString(R.string.app_name),
            Intent(MainActivity.ACTION_LISTEN).setClassName(context, MainActivity.LISTEN_ALIAS),
            listensHere = true
        )
    ) + others(context.packageManager)

    private fun others(packageManager: PackageManager): List<SongRecognizer> = listOfNotNull(
        listOfNotNull(Intent(SHAZAM_LISTEN).setPackage(SHAZAM), packageManager.getLaunchIntentForPackage(SHAZAM))
            .firstOrNull { it.resolveActivity(packageManager) != null }
            ?.let { SongRecognizer(SHAZAM, label(packageManager, SHAZAM, "Shazam"), it) },
        Intent(GOOGLE_SONG_SEARCH).setPackage(GOOGLE).takeIf { it.resolveActivity(packageManager) != null }
            ?.let { SongRecognizer(GOOGLE, label(packageManager, GOOGLE, "Google"), it) }
    )

    /** The one the Recognize buttons use: the pick in Settings, or else Crosstune. */
    fun chosen(context: Context, pick: String?): SongRecognizer {
        val all = available(context)
        return all.firstOrNull { it.packageName == pick } ?: all.first()
    }

    private fun label(packageManager: PackageManager, packageName: String, fallback: String): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
}

/**
 * Starts the song recognition app from the widget, then closes, unseen. The widget can't start it
 * itself: Glance gives every intent its own data, which the recognition apps' actions don't take.
 * Picking the app here, at the tap, also follows a change in Settings. Crosstune itself listens
 * on its own screen.
 */
class RecognizeSongActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pick = getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE).getString(SongRecognizers.KEY_PICK, null)
        // An app uninstalled since it was looked up has nothing to open.
        runCatching { startActivity(SongRecognizers.chosen(this, pick).intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }
}
