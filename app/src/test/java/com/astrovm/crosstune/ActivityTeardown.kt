package com.astrovm.crosstune

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Closes the screen and waits for what its view model was still doing, e.g. the end of a sign-in the
 * test didn't wait for. A cancelled coroutine finishes later, and by posting to the main thread: left
 * for the next test, it ran while Robolectric was setting that one up, and failed it with an
 * exception that was none of its own (Issue #805).
 */
internal fun ActivityController<MainActivity>.destroyAndSettle(timeoutMs: Long = 10_000L) {
    val work: Job? = runCatching { ViewModelProvider(get())[MainViewModel::class.java].viewModelScope.coroutineContext[Job] }.getOrNull()
    // Already closed by the test is fine.
    runCatching { pause().stop().destroy() }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (work?.isCompleted == false && System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10L)
    }
    assertTrue("the screen's work was still going ${timeoutMs}ms after it closed", work?.isCompleted != false)
}

/**
 * The main thread's work run, and Compose's too while [activity] is resumed. Compose waits only for a screen that is,
 * and with none it waits 2 s for one to come: before a screen opens, and once one is stopped. On CI that was 4 minutes
 * of each run, measured with JFR in the test forks.
 */
internal fun ComposeTestRule.settle(activity: ComponentActivity?) {
    if (activity?.lifecycle?.currentState == Lifecycle.State.RESUMED) waitForIdle() else shadowOf(Looper.getMainLooper()).idle()
}
