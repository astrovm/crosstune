package com.astrovm.crosstune

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject

/** Names a follower in debug logs without recording the song's metadata. */
internal fun Flow<Following?>.forClockLog(reason: String): Flow<Following?> = flowOn(FollowLogReason(reason))

internal val CoroutineContext.followLogReason: String get() = this[FollowLogReason]?.reason ?: "unspecified"

private class FollowLogReason(val reason: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FollowLogReason>
}

/** Opt-in dev evidence. These records are not kept in TimingLog or included in reports. */
internal class PracticeClockLog(private val debuggable: Boolean) {
    val enabled get() = debuggable && Log.isLoggable(TAG, Log.DEBUG)

    companion object {
        const val TAG = "CrosstuneClock"
        private val attempts = AtomicInteger()
        fun nextId(): Int = attempts.incrementAndGet()
        fun of(context: Context) = PracticeClockLog(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)

        /** Speed is text so even a malformed non-finite app speed has a valid JSON representation. */
        fun JSONObject.clock(state: Following?, at: Long) {
            put("present", state != null)
            if (state != null) {
                put("position_ms", state.clock.positionMs)
                put("updated_ms", state.clock.atMs)
                put("projected_ms", state.clock.positionAt(at))
                put("speed", state.clock.speed.toString())
                put("playing", state.clock.playing)
                put("can_seek", state.canSeek)
                put("ad", state.ad)
                put("duration_ms", state.durationMs ?: JSONObject.NULL)
                put("reported_session", state.session)
            }
        }
    }

    /** Neither the clock nor the fields are read while this diagnostic is off. */
    fun note(event: String, now: () -> Long, fields: JSONObject.(Long) -> Unit) {
        if (!enabled) return
        val at = now()
        val record = JSONObject().put("event", event).put("observed_ms", at)
        record.fields(at)
        Log.d(TAG, record.toString())
    }
}
