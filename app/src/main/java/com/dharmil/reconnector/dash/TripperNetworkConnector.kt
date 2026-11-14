package com.dharmil.reconnector.dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import android.os.Build
import java.io.Closeable

sealed interface TripperNetworkState {
    data object Requesting : TripperNetworkState
    data class Connected(val network: Network) : TripperNetworkState
    data object Unavailable : TripperNetworkState
    data object Lost : TripperNetworkState
}

/** Requests the bike Wi-Fi as a secondary network; only sockets bound to its Network use it. */
@RequiresApi(Build.VERSION_CODES.Q)
class TripperNetworkConnector(context: Context) : Closeable {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @MainThread
    fun request(ssid: String, listener: (TripperNetworkState) -> Unit) {
        check(callback == null) { "A Tripper Wi-Fi request is already active" }
        requireSsid(ssid)
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid.trim()).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val activeCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = listener(TripperNetworkState.Connected(network))
            override fun onUnavailable() {
                release(this)
                listener(TripperNetworkState.Unavailable)
            }
            override fun onLost(network: Network) = listener(TripperNetworkState.Lost)
        }
        callback = activeCallback
        listener(TripperNetworkState.Requesting)
        try {
            connectivityManager.requestNetwork(request, activeCallback, REQUEST_TIMEOUT_MILLIS)
        } catch (error: Throwable) {
            callback = null
            throw error
        }
    }

    @MainThread
    override fun close() {
        callback?.let(::release)
    }

    private fun release(active: ConnectivityManager.NetworkCallback) {
        if (callback === active) callback = null
        runCatching { connectivityManager.unregisterNetworkCallback(active) }
    }

    private fun requireSsid(value: String) {
        val normalized = value.trim()
        require(normalized.isNotEmpty() && normalized.toByteArray(Charsets.UTF_8).size <= MAX_SSID_BYTES) {
            "Enter a valid Tripper Wi-Fi name"
        }
    }

    private companion object {
        const val MAX_SSID_BYTES = 32
        const val REQUEST_TIMEOUT_MILLIS = 30_000
    }
}
