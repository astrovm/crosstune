package com.astrovm.crosstune

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.robolectric.Shadows.shadowOf

/** A package as Robolectric should install it, named like the launcher would show it. */
internal fun installedApp(packageName: String, label: String) = PackageInfo().apply {
    this.packageName = packageName
    applicationInfo = ApplicationInfo().apply {
        this.packageName = packageName
        nonLocalizedLabel = label
    }
}

/** Installs [packageName] as an app that opens web links to [hosts], as a patched build Crosstune has no name for does. */
internal fun installLinkOpener(context: Context, packageName: String, label: String, vararg hosts: String) {
    val packages = shadowOf(context.packageManager)
    packages.installPackage(installedApp(packageName, label))
    val opens = ComponentName(packageName, "$packageName.UrlActivity")
    packages.addActivityIfNotPresent(opens)
    packages.addIntentFilterForActivity(
        opens, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
            hosts.forEach { addDataAuthority(it, null) }
        }
    )
}
