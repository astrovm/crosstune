package com.astrovm.crosstune

import android.os.Looper
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
