package com.astrovm.crosstune.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures a cold start to the main screen without any ahead-of-time compilation and with the
 * baseline profile, to see what the profile is worth. Run with
 * `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.astrovm.crosstune.baselineprofile.StartupBenchmark`.
 * Emulator timings are noisy, so compare numbers from a real phone.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    // Setup only has to be finished once, before the first measured start.
    private var setupDone = false

    @Test
    fun startupWithoutCompilation() = startup(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            if (!setupDone) {
                startActivityAndWait()
                finishSetupIfShown()
                killProcess()
                setupDone = true
            }
            pressHome()
        }
    ) {
        startActivityAndWait()
    }
}
