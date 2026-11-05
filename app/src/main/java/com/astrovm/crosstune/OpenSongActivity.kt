package com.astrovm.crosstune

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Opens a song in its app, unseen, then closes. Android lets Crosstune open an app from behind only for 10 s after one of
 * its screens started or closed, which a screen staying open behind that app doesn't do: 8 s after **Open** on a song's card,
 * Qobuz's other number was blocked each time. This restarts those 10 s as the song opens (allowed 9.5 s later, blocked 10.5 s later).
 */
class OpenSongActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tryStartActivity(Intent(Intent.ACTION_VIEW, intent.data).setPackage(intent.getStringExtra(EXTRA_APP)))
        finish()
    }

    companion object {
        const val EXTRA_APP = "app"
    }
}

/** [app] opened on [link] through [OpenSongActivity]; false, as with opening it directly, when [app] doesn't open that link. */
internal fun Context.openLeavingTimeToOpenAgain(app: String, link: Uri): Boolean =
    Intent(Intent.ACTION_VIEW, link).setPackage(app).resolveActivity(packageManager) != null &&
        tryStartActivity(Intent(this, OpenSongActivity::class.java).setData(link).putExtra(OpenSongActivity.EXTRA_APP, app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
