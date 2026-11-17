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

    /** A callback that spans close makes the snapshot incomplete. Later lookups own another capture. */
    fun <T> resource(owner: Int, host: String, event: (T) -> Map<String, Any>, request: () -> T): T {
        val observed = synchronized(lock) {
            if (snapshot != null) false else {
                pending++
                activities[owner] = (activities[owner] ?: 0) + 1
                hosts[host] = (hosts[host] ?: 0) + 1
                true
            }
        }
        var decision: Map<String, Any>? = null
        try {
            return request().also { if (observed) decision = event(it) }
        } finally {
            if (observed) synchronized(lock) {
                pending--
                if (decision == null) failed = true else append(requireNotNull(decision))
            }
        }
    }

    fun record(event: Map<String, Any>) = synchronized(lock) { append(event) }

    private fun append(event: Map<String, Any>) {
        if (snapshot != null) return
        if (events.size == limit) truncated = true else events += event.toMap()
    }

    fun close(): Snapshot = synchronized(lock) {
        snapshot ?: Snapshot(events.toList(), activities.toMap(), hosts.toMap(), pending, truncated, failed)
            .also { snapshot = it }
    }
}
