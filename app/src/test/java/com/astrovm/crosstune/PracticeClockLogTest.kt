package com.astrovm.crosstune

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class PracticeClockLogTest {
    private fun context(debuggable: Boolean) = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getApplicationInfo() = ApplicationInfo().apply {
            flags = if (debuggable) ApplicationInfo.FLAG_DEBUGGABLE else 0
        }
    }

    @Test fun aReleaseIgnoresTheDiagnosticTagWithoutReadingClockOrFields() {
        ShadowLog.setLoggable(PracticeClockLog.TAG, Log.DEBUG)
        val logger = PracticeClockLog.of(context(false))
        var reads = 0
        logger.note("fixture", { reads++; 42 }) { reads++ }
        assertEquals(0, reads)
        assertTrue(ShadowLog.getLogsForTag(PracticeClockLog.TAG).isEmpty())
    }

    @Test fun aDebugBuildAlsoNeedsTheTagAndUsesOneClockReading() {
        val logger = PracticeClockLog.of(context(true))
        var reads = 0
        ShadowLog.setLoggable(PracticeClockLog.TAG, Log.INFO)
        logger.note("off", { reads++; 42 }) { reads++ }
        assertEquals(0, reads)
        ShadowLog.setLoggable(PracticeClockLog.TAG, Log.DEBUG)
        logger.note("fixture", { reads++; 42 }) { at -> put("copied_time", at) }
        assertEquals(1, reads)
        val row = JSONObject(ShadowLog.getLogsForTag(PracticeClockLog.TAG).single().msg)
        assertEquals("fixture", row.getString("event"))
        assertEquals(42L, row.getLong("observed_ms"))
        assertEquals(42L, row.getLong("copied_time"))
    }
}
