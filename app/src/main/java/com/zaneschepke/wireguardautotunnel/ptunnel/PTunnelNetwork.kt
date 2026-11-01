package com.zaneschepke.wireguardautotunnel.ptunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * HTTP helper for P-Tunnel's feed and conf fetches.
 *
 * v3 fix (2026-11-01): the v1/v2 implementation was bound-ONLY — it used
 * [Network.openConnection] on the physical network and had no fallback, so
 * on a phone where that bound connection cannot reach the server, BOTH the
 * conf and the feed failed even though the phone browser (default network)
 * loaded the same URLs fine. Phoenix-VPN does not have this failure mode:
 * its EndpointService tries bound first and then retries unbound
 * (bound OkHttp socketFactory + DNS via net.getAllByName, then the plain
 * httpClient), and its ConfigService uses the plain default network.
 *
 * This helper mirrors that bound-first-then-unbound order with
 * [HttpURLConnection] for both attempts, so no new HTTP dependency is
 * needed (WG Tunnel version rule: no unverified dependency versions).
 * The physical-network binding code and [underlyingNetwork] are kept
 * unchanged; the only change is that a bound failure no longer ends the
 * fetch — the same URL is retried once, unbound, before failure is
 * returned. A dead tunnel can therefore never strangle a refresh via the
 * bound path, and a broken bound path can never strangle it via the
 * unbound path either: whichever route works, wins.
 *
 * CORRECTION (v3, Hsu 2026-11-01 09:46): that bound-first-then-unbound
 * order applies to the FEED only. The CONF fetch must follow Phoenix
 * ConfigService EXACTLY — plain default-network client, NO binding at
 * all — so conf uses [getUnbound], never [getBound]. Do not re-add a
 * bound attempt to the conf path.
 *
 * Timeouts (Phoenix, verified from tugyix05/Phoenix-VPN):
 * - Feed (EndpointService): connect 20s / read 20s / call 15s.
 * - Conf (ConfigService): connect 25s / read 25s / call 30s.
 * Each attempt gets its own call deadline: a bound attempt that times out
 * does not eat the unbound attempt's deadline.
 */
object PTunnelNetwork {
    /** Feed timeouts (Phoenix EndpointService). */
    const val CONNECT_TIMEOUT_MS = 20_000
    const val READ_TIMEOUT_MS = 20_000
    const val CALL_TIMEOUT_MS = 15_000L

    /** Conf timeouts (Phoenix ConfigService). */
    const val CONF_CONNECT_TIMEOUT_MS = 25_000
    const val CONF_READ_TIMEOUT_MS = 25_000
    const val CONF_CALL_TIMEOUT_MS = 30_000L

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
     * GET [urlString] with feed timeouts: bound to the physical network
     * first, then unbound on any bound failure. Returns the response body
     * on HTTP 2xx, else throws the unbound attempt's failure (or the
     * bound attempt's failure when no unbound attempt ran, which cannot
     * happen — the unbound attempt always runs unless the caller was
     * cancelled, in which case cancellation propagates).
     * The caller must never log the returned body for conf fetches (it
     * contains key material); this helper itself never logs bodies.
     */
    suspend fun getBound(context: Context, urlString: String, userAgent: String): String {
        return getBound(
            context,
            urlString,
            userAgent,
            CONNECT_TIMEOUT_MS,
            READ_TIMEOUT_MS,
            CALL_TIMEOUT_MS,
        )
    }

    /**
     * GET [urlString] with caller-supplied timeouts, bound first then
     * unbound on any bound failure. This is the FEED path only.
     */
    suspend fun getBound(
        context: Context,
        urlString: String,
        userAgent: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        callTimeoutMs: Long,
    ): String {
        val network = underlyingNetwork(context)
        if (network != null) {
            val bound = attemptOnce(
                network,
                urlString,
                userAgent,
                connectTimeoutMs,
                readTimeoutMs,
                callTimeoutMs,
            )
            if (bound.isSuccess) return bound.getOrThrow()
            // Any bound failure (exception, non-2xx, timeout) falls
            // through to the unbound retry below; the bound error is
            // only surfaced if the unbound attempt also fails, via the
            // unbound error, which is the more relevant one.
        }
        // Unbound: the default network, exactly what the phone browser
        // uses. Its failure is the fetch's failure.
        return attemptOnce(
            null,
            urlString,
            userAgent,
            connectTimeoutMs,
            readTimeoutMs,
            callTimeoutMs,
        ).getOrThrow()
    }

    /**
     * GET [urlString] on the default network ONLY, with caller-supplied
     * timeouts. This is the CONF path, exactly like Phoenix
     * ConfigService's plain OkHttp client: no physical-network binding
     * at all, no bound attempt first. Used only by
     * [PTunnelConfService]; the feed must use [getBound] instead.
     */
    suspend fun getUnbound(
        urlString: String,
        userAgent: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        callTimeoutMs: Long,
    ): String {
        return attemptOnce(
            null,
            urlString,
            userAgent,
            connectTimeoutMs,
            readTimeoutMs,
            callTimeoutMs,
        ).getOrThrow()
    }

    /**
     * One GET attempt on [network] (null = unbound/default network).
     * A per-attempt call deadline is enforced with [withTimeoutOrNull] so
     * a timeout is a plain failure (and therefore triggers the fallback)
     * rather than a [CancellationException]; a genuine parent
     * cancellation still propagates and is never converted into a
     * fallback attempt.
     */
    private suspend fun attemptOnce(
        network: Network?,
        urlString: String,
        userAgent: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        callTimeoutMs: Long,
    ): Result<String> {
        // FIX (2026-11-01): blocking HttpURLConnection I/O runs on
        // Dispatchers.IO. Callers run on viewModelScope (Main), so without
        // this Android throws NetworkOnMainThreadException and EVERY
        // feed/conf fetch fails, bound and unbound alike.
        return withContext(Dispatchers.IO) {
            try {
                val body = withTimeoutOrNull(callTimeoutMs) {
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
                        connection.connectTimeout = connectTimeoutMs
                        connection.readTimeout = readTimeoutMs
                        connection.useCaches = false
                        val code = connection.responseCode
                        if (code !in 200..299) {
                            throw IOException("HTTP $code")
                        }
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        connection.disconnect()
                    }
                } ?: return@withContext Result.failure(
                    SocketTimeoutException("call timed out after ${callTimeoutMs}ms"),
                )
                Result.success(body)
            } catch (e: CancellationException) {
                // Parent cancellation (e.g. ViewModel cleared): never swallow
                // it into a fallback attempt.
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
