package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A job kept in a field, to be stopped or checked later, is kept before it runs, with [launchKept]. Set from what
 * `launch` returns, it's set only after the job has run up to where it first waits, and a job that asks for itself
 * meanwhile finds none and starts another that nothing stops (Issue #1145).
 */
class JobsKeptTest {
    @Test
    fun noJobIsKeptAfterItRuns() {
        val found = AppPatterns.sources("src/main/java").flatMap { file ->
            file.readLines().withIndex().filter { keptAfter(it.value) }.map { "${file.path}:${it.index + 1}" }
        }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun aJobSetFromLaunchIsFound() {
        assertEquals(
            listOf(true, true, true, true, true, false, false, false, false),
            listOf(
                "        alongJob = viewModelScope.launch {",
                "            else soundJob = scope.launch { inYouTube(line, explicit = true) }",
                "        this.job = scope.launch(Dispatchers.IO) { waits() }",
                "        keepingOffline = viewModelScope.launch {",
                "        job=scope.launch { keep(app) }",
                "        val job = scope.launch { keep(app) }",
                "        viewModelScope.launchKept({ alongJob = it }) {",
                "        // alongJob = viewModelScope.launch { was kept too late",
                "        scope.launch { playNext() }"
            ).map(::keptAfter)
        )
    }

    /** Whether [line] sets a field, not a new value, from what `launch` or `async` returns. */
    private fun keptAfter(line: String) = KEPT_AFTER.containsMatchIn(line)

    companion object {
        private val KEPT_AFTER = Regex("""^\s*(?:else\s+)?(?:this\.)?\w+\s*=\s*\w[\w.]*\.(?:launch|async)\b""")
    }
}
