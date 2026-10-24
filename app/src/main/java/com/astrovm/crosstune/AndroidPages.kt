package com.astrovm.crosstune

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri

/** Starts [intent], or says it couldn't, where no app on the phone opens it. */
internal fun Context.tryStartActivity(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

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
