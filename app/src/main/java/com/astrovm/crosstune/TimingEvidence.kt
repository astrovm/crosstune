package com.astrovm.crosstune

/** Checks evidence about timings, rather than treating the presence of timestamps as proof. */
internal object TimingEvidence {
    /** Impossible times aren't useful even when the words and recording name match. */
    fun usable(lines: List<LyricLine>, durationMs: Long? = null): Boolean =
        lines.isNotEmpty() && lines.any { it.text.isNotBlank() } && lines.all { line ->
            line.timeMs >= 0 && (durationMs == null || line.text.isBlank() || line.timeMs < durationMs) &&
                (line.endMs == null || line.endMs >= line.timeMs) &&
                line.words.all { it.timeMs >= line.timeMs && (line.endMs == null || it.timeMs <= line.endMs) } &&
                line.words.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs }
        } && lines.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs }

    /** Reuploads of the same timing sheet supply one vote, however many albums list them. */
    fun independent(copies: List<List<LyricLine>>): List<List<LyricLine>> =
        copies.distinctBy { lines -> lines.filter { it.text.isNotBlank() }.map { it.timeMs } }
}
