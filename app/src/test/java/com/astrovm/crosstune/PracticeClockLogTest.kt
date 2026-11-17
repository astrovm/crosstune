package com.astrovm.crosstune

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.util.Log
import com.astrovm.crosstune.PracticeClockLog.Companion.clock
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    @Test fun correctedClocksKeepTheirValuesAndDistinguishUnknownDurationFromNoSession() {
        val state = Following(PlaybackClock(1_234, 100, 1.25f), "Fixture", canSeek = false,
            appPackage = "com.example.fixture", durationMs = 56_789, ad = true, session = 7)
        val row = JSONObject().apply { clock(state, 300) }
        assertTrue(row.getBoolean("present"))
        assertEquals(1_234L, row.getLong("position_ms"))
        assertEquals(100L, row.getLong("updated_ms"))
        assertEquals(1_484L, row.getLong("projected_ms"))
        assertEquals("1.25", row.getString("speed"))
        assertTrue(row.getBoolean("playing"))
        assertFalse(row.getBoolean("can_seek"))
        assertTrue(row.getBoolean("ad"))
        assertEquals(56_789L, row.getLong("duration_ms"))
        assertEquals(7, row.getInt("reported_session"))
        val unknown = JSONObject().apply { clock(state.copy(durationMs = null), 300) }
        assertTrue(unknown.getBoolean("present"))
        assertTrue(unknown.has("duration_ms"))
        assertTrue(unknown.isNull("duration_ms"))
        val missing = JSONObject().apply { clock(null, 300) }
        assertFalse(missing.getBoolean("present"))
        assertEquals(setOf("present"), missing.keys().asSequence().toSet())
    }
}
