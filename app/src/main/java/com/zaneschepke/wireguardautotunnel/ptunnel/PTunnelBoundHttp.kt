package com.zaneschepke.wireguardautotunnel.ptunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Phoenix-style underlying-network HTTP (Phoenix UpdateService,
 * 2026-11-02). The update check must work even while the tunnel is
 * up with a dead endpoint: a request sent through the VPN (the
 * default network) would die with it, so the check is bound to the
 * first non-VPN network that currently has INTERNET, socket and DNS
 * both. Any failure building or using the bound client falls back to
 * the plain client — binding must never be the reason a check fails.
 */
object PTunnelBoundHttp {

    fun underlyingNetwork(context: Context): Network? {
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
                ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return network
            }
        }
        return null
    }

    private fun boundOkHttpClient(context: Context): OkHttpClient? {
        val network = underlyingNetwork(context) ?: return null
        return try {
            OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .dns(NetworkDns(network))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        } catch (e: Exception) {
            Timber.w(e, "Could not build bound HTTP client, using unbound")
            null
        }
    }

    /**
     * A minimal client whose engine runs on the underlying physical
     * network, or [client] unchanged when no usable underlying network
     * exists. Identity-comparable: `bound === client` means unbound.
     * The bound client is a fresh [HttpClient] built only for the
     * header-only update check (no plugins copied from [client]) —
     * close it when done unless it is [client] itself. A derived
     * client via `client.config { engine { ... } }` does NOT work:
     * the engine config type is erased to the base type there, so
     * OkHttp's `preconfigured` does not resolve.
     */
    fun boundClient(client: HttpClient, context: Context): HttpClient {
        val bound = boundOkHttpClient(context) ?: return client
        return try {
            HttpClient(OkHttp) {
                engine { preconfigured = bound }
                followRedirects = false
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not derive bound HTTP client, using unbound")
            client
        }
    }

    /**
     * DNS resolved on the bound network itself (the network's own
     * resolvers), IPv4 first. Falls back to the system resolver on
     * any failure, so a bad lookup never fails the request outright.
     */
    private class NetworkDns(private val network: Network) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                val all = network.getAllByName(hostname)
                all.sortedBy { it is Inet6Address }.distinct()
            } catch (_: Exception) {
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }
}
