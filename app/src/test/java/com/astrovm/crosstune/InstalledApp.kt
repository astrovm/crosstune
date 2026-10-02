package com.astrovm.crosstune

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo

/** A package as Robolectric should install it, named like the launcher would show it. */
internal fun installedApp(packageName: String, label: String) = PackageInfo().apply {
    this.packageName = packageName
    applicationInfo = ApplicationInfo().apply {
        this.packageName = packageName
        nonLocalizedLabel = label
    }
}
