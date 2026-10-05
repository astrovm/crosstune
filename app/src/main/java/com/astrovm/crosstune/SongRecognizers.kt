package com.astrovm.crosstune

import android.content.Intent
import android.content.pm.PackageManager

/**
 * Apps that name a song playing nearby. Crosstune doesn't listen itself, which would need the
 * microphone; it opens one of these, and the user shares the song they find back to it.
 */
internal object SongRecognizers {
    const val SHAZAM = "com.shazam.android"
    /** Starts listening right away, as Shazam's own widget does. */
    const val SHAZAM_LISTEN = "com.shazam.android.intent.actions.START_TAGGING"
    /** The Google app's song search, which also listens right away. */
    const val GOOGLE_SONG_SEARCH = "com.google.android.googlequicksearchbox.MUSIC_SEARCH"

    /** The first one there is: Shazam listening, Shazam itself, then Google's song search. */
    fun intent(packageManager: PackageManager): Intent? = listOfNotNull(
        Intent(SHAZAM_LISTEN).setPackage(SHAZAM),
        packageManager.getLaunchIntentForPackage(SHAZAM),
        Intent(GOOGLE_SONG_SEARCH)
    ).firstOrNull { it.resolveActivity(packageManager) != null }
}
