package com.astrovm.crosstune

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.IntentCompat

/**
 * Opens a song in its app, unseen, then closes. Android lets Crosstune open an app from behind only for 10 s after one of
 * its screens started or closed, which a screen staying open behind that app doesn't do: 8 s after **Open** on a song's card,
 * Qobuz's other number was blocked each time. This restarts those 10 s as the song opens (allowed 9.5 s later, blocked 10.5 s later).
 */
class OpenSongActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IntentCompat.getParcelableExtra(intent, EXTRA_OPEN, Intent::class.java)?.let { if (intent.getBooleanExtra(EXTRA_TWICE, false)) startTwice(it) else tryStartActivity(it) }
        finish()
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val EXTRA_TWICE = "twice"
    }
}

/** [open] started through [OpenSongActivity], [twice] if the app needs it, see [startTwice]; false, as when started directly, if no app takes it. */
internal fun Context.openLeavingTimeToOpenAgain(open: Intent, twice: Boolean = false): Boolean =
    open.resolveActivityInfo(packageManager, 0) != null &&
        tryStartActivity(Intent(this, OpenSongActivity::class.java).putExtra(OpenSongActivity.EXTRA_OPEN, open).putExtra(OpenSongActivity.EXTRA_TWICE, twice).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
