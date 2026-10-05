package com.astrovm.crosstune

import android.content.Intent
import android.content.pm.PackageManager

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

    /** Those installed, Shazam first: listening right away, or else just opened. */
    fun available(packageManager: PackageManager): List<SongRecognizer> = listOfNotNull(
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
