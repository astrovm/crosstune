package com.astrovm.crosstune

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class ConnectionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private fun capabilities(vararg capabilities: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { made -> capabilities.forEach { shadowOf(made).addCapability(it) } }

    @Test
    fun onlineWhileTheNetworkUsedIsMeantToReachTheInternet() {
        val network = connectivity.activeNetwork!!
        val seen = mutableListOf<Boolean>()
        val watching = CoroutineScope(Dispatchers.Unconfined).launch { AndroidConnection(app).online().collect { seen += it } }
        fun changed(to: NetworkCapabilities) = shadowOf(connectivity).networkCallbacks.toList().forEach { it.onCapabilitiesChanged(network, to) }

        // With a network, nothing's said until Android says what it is. One that only reaches nearby
        // devices, e.g. a camera's own Wi-Fi, isn't the internet.
        assertEquals(emptyList<Boolean>(), seen)
        changed(capabilities())
        // Online once it is, though Android hasn't checked it yet, and saying it again changes nothing.
        changed(capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        changed(capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        // Gone, e.g. in airplane mode, it's offline.
        shadowOf(connectivity).networkCallbacks.toList().forEach { it.onLost(network) }
        assertEquals(listOf(false, true, false), seen)

        // No longer watched, Android isn't asked to say.
        watching.cancel()
        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
    }

    @Test
    fun unmeteredWhileTheInternetCostsNoData() {
        val network = connectivity.activeNetwork!!
        val seen = mutableListOf<Boolean>()
        val watching = CoroutineScope(Dispatchers.Unconfined).launch { AndroidConnection(app).unmetered().collect { seen += it } }
        fun changed(to: NetworkCapabilities) = shadowOf(connectivity).networkCallbacks.toList().forEach { it.onCapabilitiesChanged(network, to) }
        // Mobile data costs; Wi-Fi doesn't, but only once it reaches the internet.
        changed(capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        changed(capabilities(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        changed(capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        assertEquals(listOf(false, true), seen)
        watching.cancel()
    }

    @Test
    fun withNoNetworkAtAllItsOfflineFromTheStart() {
        shadowOf(connectivity).setDefaultNetworkActive(false)
        shadowOf(connectivity).setActiveNetworkInfo(null)
        val seen = mutableListOf<Boolean>()
        CoroutineScope(Dispatchers.Unconfined).launch { AndroidConnection(app).online().collect { seen += it } }.cancel()
        assertEquals(listOf(false), seen)
    }
}
