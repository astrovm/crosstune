package com.astrovm.crosstune

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Online or not as a test says, changed by setting [online]. */
internal class FakeConnection(online: Boolean = true) : Connection {
    val online = MutableStateFlow(online)

    override fun online(): Flow<Boolean> = online
}
