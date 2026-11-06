package com.astrovm.crosstune

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.net.toUri

/** Starts [intent], or says it couldn't, where no app on the phone opens it. */
internal fun Context.tryStartActivity(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** A song gets its own task; TIDAL must leave its old search before it will take a new query. */
internal fun Context.tryStartMusicActivity(intent: Intent): Boolean {
    cancelPendingMusicOpen()
    val song = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return if (song.isTidalSearch()) openLeavingTimeToOpenAgain(song) else tryStartActivity(song)
}

internal fun Intent.isTidalSearch(): Boolean =
    `package` == MusicService.TIDAL.packageName && data?.scheme == "tidal" && data?.host == "search"

private val musicStarts = Handler(Looper.getMainLooper())
private var pendingMusicOpen: Runnable? = null

/** A newer song replaces a search still waiting for TIDAL to leave its previous screen. */
internal fun cancelPendingMusicOpen() {
    pendingMusicOpen?.let { musicStarts.removeCallbacks(it) }
    pendingMusicOpen = null
}

/** TIDAL deduplicates search screens without comparing their queries, so leave that screen first. */
internal fun Context.startTidalSearch(intent: Intent) {
    tryStartActivity(Intent(intent).setData("tidal://my-collection".toUri()))
    val app = applicationContext
    val later = Runnable {
        pendingMusicOpen = null
        app.tryStartActivity(intent)
    }
    pendingMusicOpen = later
    // Both links navigate inside TIDAL after its activity opens. Back to back, the old search still wins.
    musicStarts.postDelayed(later, TIDAL_SEARCH_MS)
}

internal const val TIDAL_SEARCH_MS = 3_000L

/**
 * Starts [intent] twice, see [startTwice], from a screen of its own: without it, a second start from a song's card was blocked
 * once the app had come to the front, 0.7 s in with an app already up. Both go in a task of the app's own: in Crosstune's, the
 * task keeps the link that began it, so that link again only brings back whatever the app last searched.
 */
internal fun Context.tryStartActivityTwice(intent: Intent): Boolean =
    openLeavingTimeToOpenAgain(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), twice = true)

/**
 * Starts [intent], then again after [SECOND_START_MS]. Bandcamp's app loses the first search it's sent while it starts,
 * and takes the one sent once it is up; sent back to back, both land in its start and are lost. Already up, it takes either.
 */
internal fun Context.startTwice(intent: Intent) {
    tryStartActivity(intent)
    val app = applicationContext
    Handler(Looper.getMainLooper()).postDelayed({ runCatching { app.startActivity(intent) } }, SECOND_START_MS)
}

internal const val SECOND_START_MS = 700L

/** Crosstune's App info: where the microphone is allowed, and restricted settings. */
internal fun Context.openAppInfo() {
    tryStartActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
}

/**
 * Android's page for letting Crosstune see what music apps play, at its own entry where Android has one.
 * A [tv] opens its list of apps instead: Google TV's page, opened straight away, can leave the remote
 * stuck outside its list, as on the Chromecast, but reached from there it doesn't.
 */
internal fun Context.openNotificationAccess(tv: Boolean) {
    if (tv && tryStartActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val own = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, NowPlayingListener::class.java).flattenToString())
        if (tryStartActivity(own)) return
    }
    tryStartActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

/** Opens Android's link settings for one app: Crosstune's to allow links, another's to turn its links off. */
internal fun Context.openLinkSettingsOf(appPackage: String) {
    val packageUri = "package:$appPackage".toUri()
    val openedDefaults = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        tryStartActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri))
    if (!openedDefaults) {
        tryStartActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    }
}

/** Android's page for letting Crosstune show over other apps: its own before Android 11, the list of apps after. */
internal fun Context.openOverlaySettings() {
    tryStartActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
}
