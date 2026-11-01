package com.astrovm.crosstune

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Online or not, and on Wi-Fi or not, as a test says, changed by setting [online] and [unmetered]. */
internal class FakeConnection(online: Boolean = true, unmetered: Boolean = online) : Connection {
    val online = MutableStateFlow(online)
    val unmetered = MutableStateFlow(unmetered)

    override fun online(): Flow<Boolean> = online

    override fun unmetered(): Flow<Boolean> = unmetered
}
