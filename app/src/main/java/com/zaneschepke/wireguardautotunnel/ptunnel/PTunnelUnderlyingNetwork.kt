package com.zaneschepke.wireguardautotunnel.ptunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phoenix-style underlying-network check (Phoenix WireGuardManager,
 * 2026-11-02). Unlike the WGAT monitor snapshot — which can keep
 * reporting a cached Wifi type with frozen INTERNET caps after Wi-Fi
 * is turned off (VPN is the default network, so the cached branch
 * never checks presence, and the snapshot may not even change, so
 * snapshot observers never fire) — this reads LIVE capabilities
 * from ConnectivityManager at decision time, over non-VPN networks
 * only.
 *
 * Usable == some non-VPN network currently has INTERNET + VALIDATED,
 * exactly Phoenix Rule 1 (stop when !hasInternet || !isValidated).
 */
object PTunnelUnderlyingNetwork {

    fun hasUsableUnderlyingNetwork(context: Context): Boolean {
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
                ?: return false
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Phoenix-style lost watcher: listens ONLY to non-VPN networks
     * (INTERNET + NOT_VPN request), so onLost fires when the real
     * underlying network goes away even while the VPN is up and the
     * monitor snapshot still says Wifi/Cellular. On lost, waits 1s
     * (Phoenix flap protection), then runs [onLostConfirmed]; a new
     * available network, or INTERNET capabilities on any network,
     * cancels the pending check. The callback is unregistered when
     * [scope] is cancelled.
     */
    fun watchUnderlyingLost(
        context: Context,
        scope: CoroutineScope,
        onLostConfirmed: suspend () -> Unit,
    ) {
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
                ?: return
        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
        var pending: Job? = null
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    pending?.cancel()
                    pending =
                        scope.launch {
                            delay(1_000L)
                            onLostConfirmed()
                        }
                }

                override fun onAvailable(network: Network) {
                    pending?.cancel()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        pending?.cancel()
                    }
                }
            }
        cm.registerNetworkCallback(request, callback)
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                pending?.cancel()
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (_: Exception) {
                }
            }
        }
    }
}
