package com.astrovm.crosstune

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.random.Random

/** Looking for a newer Crosstune on GitHub, for a copy installed from a file. */
@RunWith(RobolectricTestRunner::class)
class UpdatesTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val fake = FakeSpotify()
    private val prefs = app.getSharedPreferences("updates_test", Context.MODE_PRIVATE)
    private var clock = 1_000_000_000L

    private fun checker(installed: String = "2.8.16") = UpdateChecker(fake.client(), prefs, installed, now = { clock })

    /** GitHub's latest release, [tag], with [assets] by name. */
    private fun release(tag: String, vararg assets: String) {
        fake.handler = { request ->
            val list = assets.joinToString(",") { """{"name":"$it","browser_download_url":"https://github.com/astrovm/crosstune/releases/download/$tag/$it"}""" }
            FakeSpotify.html(request, """{"tag_name":"$tag","assets":[$list]}""")
        }
    }

    @Test
    fun aNewerReleaseIsTheUpdateWithItsApk() = runBlocking {
        release("v2.9.0", "notes.txt", "Crosstune-v2.9.0.apk")
        assertEquals(Update("2.9.0", "https://github.com/astrovm/crosstune/releases/download/v2.9.0/Crosstune-v2.9.0.apk"), checker().newer())
        assertEquals(listOf("https://api.github.com/repos/astrovm/crosstune/releases/latest"), fake.requestedUrls)
    }

    @Test
    fun theSameOrAnOlderReleaseIsNone() = runBlocking {
        release("v2.8.16", "Crosstune-v2.8.16.apk")
        assertNull(checker().newer())
        clock += DAY_MS
        release("v2.8.15", "Crosstune-v2.8.15.apk")
        assertNull(checker().newer())
    }

    @Test
    fun githubIsAskedAtMostOnceADay() = runBlocking {
        release("v2.9.0", "Crosstune-v2.9.0.apk")
        assertEquals("2.9.0", checker().newer()?.version)
        // Within the day, what it said is kept, and still says so once this copy's updated.
        clock += DAY_MS - 1
        assertEquals("2.9.0", checker().newer()?.version)
        assertNull(checker(installed = "2.9.0").newer())
        assertEquals(1, fake.requestedUrls.size)
        // A day on, it's asked again.
        clock += 1
        release("v2.10.0", "Crosstune-v2.10.0.apk")
        assertEquals("2.10.0", checker().newer()?.version)
        assertEquals(2, fake.requestedUrls.size)
    }

    @Test
    fun noAnswerIsNoUpdateAndIsAskedAgainNextTime() = runBlocking {
        fake.handler = { request -> FakeSpotify.html(request, "", code = 503) }
        assertNull(checker().newer())
        fake.handler = { request -> FakeSpotify.html(request, "not json") }
        assertNull(checker().newer())
        fake.handler = { throw java.io.IOException("offline") }
        assertNull(checker().newer())
        // A release with no APK, or no version, isn't one to update to.
        release("v2.9.0", "notes.txt")
        assertNull(checker().newer())
        release("", "Crosstune.apk")
        assertNull(checker().newer())
        assertEquals(5, fake.requestedUrls.size)
        release("v2.9.0", "Crosstune-v2.9.0.apk")
        assertEquals("2.9.0", checker().newer()?.version)
    }

    @Test
    fun aVersionIsNewerByItsFirstNumberThatDiffers() {
        assertTrue(UpdateChecker.isNewer("2.8.17", "2.8.16"))
        assertTrue(UpdateChecker.isNewer("2.10.0", "2.9.9"))
        assertTrue(UpdateChecker.isNewer("3.0.0", "2.99.99"))
        assertTrue(UpdateChecker.isNewer("2.8.17", "2.8.16-dev"))
        assertFalse(UpdateChecker.isNewer("2.8.16", "2.8.16"))
        assertFalse(UpdateChecker.isNewer("2.8.15", "2.8.16"))
        // What isn't X.Y.Z is never newer.
        assertFalse(UpdateChecker.isNewer("2.9", "2.8.16"))
        assertFalse(UpdateChecker.isNewer("2.9.0", "beta"))
        assertFalse(UpdateChecker.isNewer("next", "2.8.16"))
    }

    @Test
    fun ofAnyTwoDifferentVersionsExactlyOneIsNewer() {
        val random = Random(17)
        repeat(500) {
            val one = List(3) { random.nextInt(0, 12) }
            val other = List(3) { random.nextInt(0, 12) }
            val (a, b) = one.joinToString(".") to other.joinToString(".")
            if (one == other) assertFalse(UpdateChecker.isNewer(a, b))
            else assertTrue("$a $b", UpdateChecker.isNewer(a, b) != UpdateChecker.isNewer(b, a))
        }
    }

    @Test
    fun onlyACopyInstalledFromAFileLooksForUpdates() {
        val packages = shadowOf(app.packageManager)
        fun installedBy(installer: String?) = packages.setInstallSourceInfo(app.packageName, null, null, null, installer, null, PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED)
        // Over USB, or by Android's own installer, it's from a file.
        installedBy(null)
        assertTrue(installedFromAFile(app))
        installedBy("com.google.android.packageinstaller")
        assertTrue(installedFromAFile(app))
        // An app store updates its own.
        for (store in listOf("com.android.vending", "org.fdroid.fdroid", "dev.imranr.obtainium")) {
            installedBy(store)
            assertFalse(store, installedFromAFile(app))
        }
    }

    @Test
    @Config(sdk = [29])
    @Suppress("DEPRECATION")
    fun beforeAndroid11TheInstallerIsAskedTheOldWay() {
        app.packageManager.setInstallerPackageName(app.packageName, "com.android.vending")
        assertFalse(installedFromAFile(app))
        app.packageManager.setInstallerPackageName(app.packageName, "com.android.packageinstaller")
        assertTrue(installedFromAFile(app))
    }

    @Test
    fun onlyTheReleasedAppLooksForUpdates() {
        // Tests run a debug build, which GitHub doesn't have.
        assertEquals("com.astrovm.crosstune", app.packageName)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        assertFalse(isRelease(app))
        app.applicationInfo.flags = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        assertTrue(isRelease(app))
        // And only from a file.
        shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, null, null, null, "com.android.vending", null, PackageInstaller.PACKAGE_SOURCE_STORE)
        assertFalse(looksForUpdates(app))
        shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, null, null, null, null, null, PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED)
        assertTrue(looksForUpdates(app))
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        assertFalse(looksForUpdates(app))
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
