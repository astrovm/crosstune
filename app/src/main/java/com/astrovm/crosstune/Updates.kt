package com.astrovm.crosstune

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

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
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun newer(): Update? {
        val latest = if (now() - preferences.getLong(KEY_CHECKED, 0L) in 0 until DAY_MS) saved() else latest()?.also(::save)
        return latest?.takeIf { isNewer(it.version, installed) }
    }

    /** The latest release, with its APK; null offline, or with none. */
    private suspend fun latest(): Update? = try {
        val request = Request.Builder().url(LATEST_URL).header("Accept", "application/vnd.github+json").build()
        client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) return@use null
            val release = JSONObject(withContext(Dispatchers.IO) { response.body.stringAtMost() })
            val version = release.optString("tag_name").removePrefix("v").ifEmpty { return@use null }
            val apk = release.optJSONArray("assets").objects().firstOrNull { it.optString("name").endsWith(".apk") } ?: return@use null
            Update(version, apk.optString("browser_download_url").ifEmpty { return@use null })
        }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    private fun saved(): Update? {
        val version = preferences.getString(KEY_VERSION, null) ?: return null
        return Update(version, preferences.getString(KEY_APK, null) ?: return null)
    }

    private fun save(update: Update) = preferences.edit {
        putLong(KEY_CHECKED, now()).putString(KEY_VERSION, update.version).putString(KEY_APK, update.apkUrl)
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/astrovm/crosstune/releases/latest"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val KEY_CHECKED = "update_checked"
        private const val KEY_VERSION = "update_version"
        private const val KEY_APK = "update_apk"

        /** Whether [version] comes after [installed], both as X.Y.Z; a build's own suffix, "-dev", is left out. */
        fun isNewer(version: String, installed: String): Boolean {
            val new = parts(version) ?: return false
            val old = parts(installed) ?: return false
            return new.zip(old).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
        }

        private fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.size == 3 }
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
