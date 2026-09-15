package com.inkwell.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Network reachability, abstracted so the send path and the ViewModel can be unit-tested
 * without Android. Offline detection disables Send with an inline state (SPEC §9.5).
 */
interface Connectivity {
    /** Snapshot: true when the device currently has a validated internet network. */
    fun isOnline(): Boolean

    /** Observable stream of the same signal, for reactive UI enablement. */
    val online: StateFlow<Boolean>

    /** A no-op implementation used in tests and as a safe default (always online). */
    object AlwaysOnline : Connectivity {
        override fun isOnline(): Boolean = true
        override val online: StateFlow<Boolean> = MutableStateFlow(true)
    }
}

/**
 * [Connectivity] backed by [ConnectivityManager]. A job is considered sendable only when
 * the active network both has INTERNET capability and is VALIDATED (actually reaches the
 * internet), so a captive Wi-Fi with no route reads as offline. Requires
 * `ACCESS_NETWORK_STATE` (already in the manifest).
 */
class AndroidConnectivity(context: Context) : Connectivity {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _online = MutableStateFlow(currentlyOnline())
    override val online: StateFlow<Boolean> = _online

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _online.value = currentlyOnline() }
        override fun onLost(network: Network) { _online.value = currentlyOnline() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = currentlyOnline()
        }
    }

    init {
        cm.registerDefaultNetworkCallback(callback)
    }

    override fun isOnline(): Boolean = currentlyOnline()

    private fun currentlyOnline(): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
