package com.astrovm.crosstune

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether the phone can reach the internet, as it changes. */
internal interface Connection {
    fun online(): Flow<Boolean>
}

/**
 * Android's own word on it: online while it has a network meant to reach the internet. Not only once
 * Android has checked it does, as its check fails on some networks that work, e.g. where Google is out
 * of reach: there, every part would wait for the internet that's already there.
 */
internal class AndroidConnection(context: Context) : Connection {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun online(): Flow<Boolean> = callbackFlow {
        fun reaches(capabilities: NetworkCapabilities?) = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(reaches(capabilities))
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        // With a network, Android tells the callback about it as soon as it's registered; with none,
        // it says nothing at all, so that's said here.
        if (connectivity.activeNetwork == null) trySend(false)
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
