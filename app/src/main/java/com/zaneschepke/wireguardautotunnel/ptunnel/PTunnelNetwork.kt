package com.zaneschepke.wireguardautotunnel.ptunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.withTimeout

/**
 * Physical-network HTTP helper for P-Tunnel's feed and conf fetches.
 *
 * Both fetches must be bound to the underlying physical network (cellular /
 * Wi-Fi / ethernet), never routed through a VPN tunnel: plain internet is
 * enough to receive a new feed or conf, so a dead tunnel can never strangle
 * a refresh. This mirrors Phoenix-VPN's EndpointService binding, using the
 * platform [Network.openConnection] API instead of an OkHttp socket factory
 * so no new HTTP dependency is needed.
 *
 * Timeouts follow the Phoenix pattern: connect 20s, read 20s, with an overall
 * call deadline of 15s enforced by [withTimeout] (so the effective ceiling is
 * 15s, exactly as in Phoenix's connect 20s / read 20s / call 15s setup).
 */
object PTunnelNetwork {
    const val CONNECT_TIMEOUT_MS = 20_000
    const val READ_TIMEOUT_MS = 20_000
    const val CALL_TIMEOUT_MS = 15_000L

    /** The physical network underneath any VPN, or null when none is available. */
    fun underlyingNetwork(context: Context): Network? {
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    /** Transport name for diagnostics custom keys only. Never a user address. */
    fun networkTypeName(context: Context): String {
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unknown"
        val network = underlyingNetwork(context) ?: return "unknown"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    /**
     * GET [urlString] bound to the physical network when one exists, otherwise
     * unbound. Returns the response body on HTTP 2xx, else throws [IOException].
     * The caller must never log the returned body for conf fetches (it contains
     * key material); this helper itself never logs bodies.
     */
    suspend fun getBound(context: Context, urlString: String, userAgent: String): String {
        return withTimeout(CALL_TIMEOUT_MS) {
            val network = underlyingNetwork(context)
            val connection =
                if (network != null) {
                    network.openConnection(URL(urlString)) as HttpURLConnection
                } else {
                    URL(urlString).openConnection() as HttpURLConnection
                }
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.useCaches = false
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IOException("HTTP $code")
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
