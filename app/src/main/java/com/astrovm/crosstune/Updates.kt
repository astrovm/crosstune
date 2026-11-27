package com.astrovm.crosstune

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** A newer Crosstune on GitHub: its [version], and its APK's [apkUrl]. */
internal data class Update(val version: String, val apkUrl: String)

/**
 * Asks GitHub for Crosstune's latest release, at most once a day, and says when it's newer than
 * [installed]. Only for a copy installed from a file: an app store updates its own.
 */
internal class UpdateChecker(
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
    private val installed: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val flavor: String = BuildConfig.FLAVOR
) {
    suspend fun newer(): Update? {
        val latest = if (now() - preferences.getLong(KEY_CHECKED, 0L) in 0 until DAY_MS) saved() else latest()?.also(::save)
        return latest?.takeIf { isNewer(it.version, installed) }
    }

    /** The latest release, with its APK; null offline, or with none. */
    private suspend fun latest(): Update? = answeredOr(null) {
        val request = Request.Builder().url(LATEST_URL).header("Accept", "application/vnd.github+json").build()
        client.newCall(request).answer(Dispatchers.IO) { response ->
            if (!response.isSuccessful) return@answer null
            val release = JSONObject(response.body.stringAtMost())
            val tag = release.optString("tag_name").ifEmpty { return@answer null }
            val version = tag.removePrefix("v").ifEmpty { return@answer null }
            val name = apkName(tag)
            val apk = release.optJSONArray("assets").objects().firstOrNull { it.optString("name") == name } ?: return@answer null
            val url = apk.optString("browser_download_url")
            if (url != apkUrl(tag)) return@answer null
            Update(version, url)
        }
    }

    private fun saved(): Update? {
        val version = preferences.getString(KEY_VERSION, null) ?: return null
        val url = preferences.getString(KEY_APK, null) ?: return null
        if (url != apkUrl("v$version") && url != apkUrl(version)) return null
        return Update(version, url)
    }

    private fun apkName(tag: String) = "Crosstune-$tag${if (flavor == "foss") "-foss" else ""}.apk"

    private fun apkUrl(tag: String) = "https://github.com/astrovm/crosstune/releases/download/$tag/${apkName(tag)}"

    private fun save(update: Update) = preferences.edit {
        putLong(KEY_CHECKED, now()).putString(KEY_VERSION, update.version).putString(KEY_APK, update.apkUrl)
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/astrovm/crosstune/releases/latest"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val KEY_CHECKED = "update_checked"
        private const val KEY_VERSION = "update_version"
        private const val KEY_APK = "update_apk"

        /**
         * Whether [version] comes after [installed], both as X.Y.Z. One with a suffix, as a test version
         * (2.27.0-rc.1, 2.27.0.1) or a build's own "-dev", comes before X.Y.Z itself, so a tester gets 2.27.0.
         */
        fun isNewer(version: String, installed: String): Boolean {
            val new = key(version) ?: return false
            val old = key(installed) ?: return false
            return new > old
        }

        /**
         * X, Y and Z at ten digits each, so two keys compare as text, then 1 for X.Y.Z itself or 0 for one with a
         * suffix. Null for what isn't a version: it's never newer, and nothing is newer than it.
         */
        private fun key(version: String): String? {
            val match = VERSION.matchEntire(version) ?: return null
            val numbers = match.groupValues.subList(1, 4).map { it.toIntOrNull() ?: return null }
            return numbers.joinToString("") { it.toString().padStart(10, '0') } +
                if (match.groupValues[4].isEmpty()) "1" else "0"
        }

        private val VERSION = Regex("""(\d+)\.(\d+)\.(\d+)([-.].+)?""")
    }
}

/** Whether this copy looks for a newer Crosstune: a release installed from a file. */
internal fun looksForUpdates(context: Context): Boolean = isRelease(context) && installedFromAFile(context)

/** Whether this is the released Crosstune, not Crosstune Dev, which GitHub doesn't have. */
internal fun isRelease(context: Context): Boolean =
    context.packageName == RELEASE_PACKAGE && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0

private const val RELEASE_PACKAGE = "com.astrovm.crosstune"

/**
 * Whether Crosstune was installed from an APK file, e.g. from GitHub, by Android's own installer or
 * over USB, rather than by an app store, such as Google Play, F-Droid or Obtainium, which updates it itself.
 */
internal fun installedFromAFile(context: Context): Boolean {
    val installer = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrElse { return false }
    return installer == null || installer.endsWith(".packageinstaller")
}
