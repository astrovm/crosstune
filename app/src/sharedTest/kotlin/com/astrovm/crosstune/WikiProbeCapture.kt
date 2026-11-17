package com.astrovm.crosstune

/** One lookup's observation window. Closing it seals all fields under the same lock. */
internal class WikiProbeCapture(private val limit: Int = 500) {
    private val lock = Any()
    private val events = mutableListOf<Map<String, Any>>()
    private val activities = mutableMapOf<Int, Int>()
    private val hosts = mutableMapOf<String, Int>()
    private var pending = 0
    private var truncated = false
    private var failed = false
    private var snapshot: Snapshot? = null

    data class Snapshot(
        val events: List<Map<String, Any>>,
        val activities: Map<Int, Int>,
        val hosts: Map<String, Int>,
        val pending: Int,
        val truncated: Boolean,
        val failed: Boolean
    ) {
        val complete: Boolean get() = pending == 0 && !truncated && !failed
    }

    /** Enter before reading callback metadata. A callback across close makes the snapshot incomplete. */
    fun <T> resource(owner: Int, host: () -> String, event: (T) -> Map<String, Any>, request: () -> T): T {
        val observed = begin()
        var decision: Map<String, Any>? = null
        try {
            val name = host()
            if (observed) synchronized(lock) {
                if (snapshot == null) {
                    activities[owner] = (activities[owner] ?: 0) + 1
                    hosts[name] = (hosts[name] ?: 0) + 1
                }
            }
            return request().also { if (observed) decision = event(it) }
        } finally {
            if (observed) finish(decision)
        }
    }

    fun record(event: () -> Map<String, Any>) {
        val observed = begin()
        var built: Map<String, Any>? = null
        try {
            built = event()
        } finally {
            if (observed) finish(built)
        }
    }

    private fun begin(): Boolean = synchronized(lock) {
        if (snapshot != null) false else {
            pending++
            true
        }
    }

    private fun finish(event: Map<String, Any>?) = synchronized(lock) {
        pending--
        if (event == null) failed = true else append(event)
    }

    private fun append(event: Map<String, Any>) {
        if (snapshot != null) return
        if (events.size == limit) truncated = true else events += event.toMap()
    }

    fun close(): Snapshot = synchronized(lock) {
        snapshot ?: Snapshot(events.toList(), activities.toMap(), hosts.toMap(), pending, truncated, failed)
            .also { snapshot = it }
    }
}
