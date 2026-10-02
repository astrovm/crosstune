package com.astrovm.crosstune.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which code Crosstune runs on startup and on the main screen, so Android compiles it ahead
 * of time. Run with `./gradlew :app:generateBaselineProfile` on an English device or emulator with
 * Android 9 or newer, then commit the profile it writes to app/src/main/generated/baselineProfiles.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** Cold start to the main screen; also the startup profile, which lays this code out first in the APK. */
    @Test
    fun startup() = rule.collect(packageName = PACKAGE_NAME, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        finishSetupIfShown()
    }

    /** Looking up links, scrolling to Recent and visiting the settings. */
    @Test
    fun mainScreen() = rule.collect(packageName = PACKAGE_NAME) {
        pressHome()
        startActivityAndWait()
        finishSetupIfShown()
        useMainScreen()
    }
}
