package com.astrovm.crosstune

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Whether the phone can reach the internet, as it changes. */
internal interface Connection {
    fun online(): Flow<Boolean>

    /** Whether it can on a network that doesn't cost the user data, e.g. Wi-Fi, for big downloads. */
    fun unmetered(): Flow<Boolean>
}

/**
 * Android's own word on it: online while it has a network meant to reach the internet. Not only once
 * Android has checked it does, as its check fails on some networks that work, e.g. where Google is out
 * of reach: there, every part would wait for the internet that's already there.
 */
internal class AndroidConnection(context: Context) : Connection {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun online(): Flow<Boolean> = watch(false, NetworkCapabilities.NET_CAPABILITY_INTERNET)

    override fun unmetered(): Flow<Boolean> = watch(true, NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

    /** Whether the network the phone uses has all of [capabilities], as it changes. */
    private fun watch(trackNetwork: Boolean, vararg capabilities: Int): Flow<Boolean> = callbackFlow {
        var current: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, has: NetworkCapabilities) {
                current = network
                val usable = capabilities.all(has::hasCapability)
                trySend(usable to network.takeIf { usable && trackNetwork })
            }

            override fun onLost(network: Network) {
                if (current == null || current == network) {
                    current = null
                    trySend(false to null)
                }
            }
        }
        // With a network, Android tells the callback about it as soon as it's registered; with none,
        // it says nothing at all, so that's said here.
        if (connectivity.activeNetwork == null) trySend(false to null)
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().map { it.first }
}
