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
 * What names a song playing nearby: Crosstune itself, or, when it can't reach Shazam, Shazam's or
 * Google's app, whose songs the user shares back to it.
 */
internal object SongRecognizers {
    const val SHAZAM = "com.shazam.android"
    /** Starts listening right away, as Shazam's own widget does. */
    const val SHAZAM_LISTEN = "com.shazam.android.intent.actions.START_TAGGING"
    const val GOOGLE = "com.google.android.googlequicksearchbox"
    /** The Google app's song search, which also listens right away. */
    const val GOOGLE_SONG_SEARCH = "com.google.android.googlequicksearchbox.MUSIC_SEARCH"

    /** Crosstune itself, which the Recognize buttons always use. */
    fun crosstune(context: Context) = SongRecognizer(
        context.packageName,
        context.getString(R.string.app_name),
        Intent(MainActivity.ACTION_LISTEN).setClassName(context, MainActivity.LISTEN_ALIAS),
        listensHere = true
    )

    /**
     * Crosstune first, then those installed, for when it can't reach Shazam: Shazam, listening right
     * away or else just opened, and Google.
     */
    fun available(context: Context): List<SongRecognizer> = listOf(crosstune(context)) + others(context.packageManager)

    private fun others(packageManager: PackageManager): List<SongRecognizer> = listOfNotNull(
        listOfNotNull(Intent(SHAZAM_LISTEN).setPackage(SHAZAM), packageManager.getLaunchIntentForPackage(SHAZAM))
            .firstOrNull { it.resolveActivity(packageManager) != null }
            ?.let { SongRecognizer(SHAZAM, label(packageManager, SHAZAM, "Shazam"), it) },
        Intent(GOOGLE_SONG_SEARCH).setPackage(GOOGLE).takeIf { it.resolveActivity(packageManager) != null }
            ?.let { SongRecognizer(GOOGLE, label(packageManager, GOOGLE, "Google"), it) }
    )

    private fun label(packageManager: PackageManager, packageName: String, fallback: String): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
}

/**
 * Starts Crosstune listening from the widget, on its own screen, then closes, unseen. The widget's
 * intents each get their own data from Glance, so it starts this one, which starts listening.
 */
class RecognizeSongActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(SongRecognizers.crosstune(this).intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
