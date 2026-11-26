package com.astrovm.crosstune

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Only the exact-video evidence of a Play that is still waiting. No reader writes observations here. */
internal class YouTubePlayEvidence {
    class Request(val app: String, val videoId: String)
    private data class Exact(val request: Request, val durationMs: Long?)

    private val active = MutableStateFlow<Exact?>(null)
    val updates = active.map { Unit }

    fun start(app: String, videoId: String, durationMs: Long?): Request =
        Request(app, videoId).also { active.value = Exact(it, durationMs) }

    /** Replace even an old cached duration with no evidence if the fresh request could not verify it. */
    fun remember(request: Request, durationMs: Long?) {
        active.update { current -> if (current != null && current.request === request) current.copy(durationMs = durationMs) else current }
    }

    fun clear(request: Request) {
        active.update { current -> current?.takeUnless { it.request === request } }
    }

    fun adDuration(app: String, reportedVideo: String?, rawDuration: Long): Long? {
        val exact = active.value ?: return null
        if (app != exact.request.app || reportedVideo != exact.request.videoId || rawDuration !in MIN_SHORT_AD_MS..MEASURED_SHORT_AD_MS) return null
        return exact.durationMs?.takeIf { it >= AD_DURATION_HINT_MS }
    }
}

/** Leave subsecond metadata and long edits alone. Samsung short reports include 10,133 to 60,046 ms. */
internal const val MIN_SHORT_AD_MS = 5_000L
internal const val MEASURED_SHORT_AD_MS = 65_000L
