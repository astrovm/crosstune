package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A job kept in a field, to be stopped or checked later, is kept before it runs, with [launchKept] or [asyncKept]. Set
 * from what `launch` returns, it's set only after the job has run up to where it first waits, and a job that asks for
 * itself meanwhile finds none and starts another that nothing stops (Issue #1145).
 */
class JobsKeptTest {
    @Test
    fun noJobIsKeptAfterItRuns() {
        val sources = listOf("src/main/java", "src/play/java", "src/foss/java").flatMap(AppPatterns::sources)
        val found = sources.flatMap { file ->
            val lines = file.readLines()
            lines.indices.filter { keptAfter(lines, it) }.map { "${file.path}:${it + 1}" }
        }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun aJobSetFromLaunchIsFound() {
        val kept = listOf(
            "        alongJob = viewModelScope.launch {",
            "            else soundJob = scope.launch { inYouTube(line, explicit = true) }",
            "        this.job = scope.launch(Dispatchers.IO) { waits() }",
            "        keepingOffline = viewModelScope.launch {",
            "        job=scope.launch { keep(app) }",
            "        byEarLook = lyricsFinder?.let { finder -> viewModelScope.launch { finder.firstLook(song) } }",
            "        job = scope?.launch { keep(app) }",
            "        job = launch { keep(app) }",
            "        lookingAhead[key] = viewModelScope.async { finder.firstLook(song) }",
            "        if (playing) job = scope.launch { keep(app) }",
            "        job = updates.onEach { keep(it) }.launchIn(scope)",
        )
        val notKept = listOf(
            "        val job = scope.launch { keep(app) }",
            "        viewModelScope.launchKept({ alongJob = it }) {",
            "        // alongJob = viewModelScope.launch { was kept too late",
            "        scope.launch { playNext() }",
            "            onDragCancel = { scope.launch { swiped.animateTo(0f, spring()) } }",
            "        val look = lookingAhead[key] ?: lookUpAhead(finder, key, asked)",
        )
        assertEquals(kept.map { true } + notKept.map { false }, (kept + notKept).map { keptAfter(listOf(it), 0) })
        // Split after the `=`, it's still one line.
        assertEquals(listOf(true, false), listOf(0, 1).map { keptAfter(listOf("        job =", "            scope.launch { keep(app) }"), it) })
    }

    @Test
    fun aJobIsKeptBeforeItRunsEvenWhenItRunsAtOnce() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var job: Job? = null
        var seen: Job? = null
        val launched = scope.launchKept({ job = it }) { seen = job }
        assertSame(launched, seen)
        var look: Deferred<Int>? = null
        val held = HashMap<String, Deferred<Int>>()
        val asked = scope.asyncKept({ answer -> look = answer; held["song"] = answer; answer.invokeOnCompletion { held.remove("song") } }) { if (look != null) 1 else 0 }
        // Over at once, it was held first and dropped once over, not dropped first and held for good.
        assertTrue(asked.isCompleted)
        assertEquals(1, runBlocking { asked.await() })
        assertEquals(emptyMap<String, Deferred<Int>>(), held)
    }

    companion object {
        /**
         * A field, or a map's entry, set from what `launch`, `async` or `launchIn` returns, on [lines]'s line [at] or,
         * when that ends with `=`, the next one. A lambda (`= {`) launches when it's called, so it isn't one.
         */
        private val KEPT_AFTER = Regex("""^\s*(?:else\s+|if\s*\(.*\)\s*)?(?:this\.)?\w+(?:\[[^\]]*])?\s*=(?!\s*\{)\s*(?:.*\W)?(?:launch|async|launchIn)\s*[({]""")

        private fun keptAfter(lines: List<String>, at: Int): Boolean {
            val line = lines[at]
            val whole = if (line.trimEnd().endsWith("=")) line + " " + lines.getOrElse(at + 1) { "" }.trim() else line
            return KEPT_AFTER.containsMatchIn(whole)
        }
    }
}
