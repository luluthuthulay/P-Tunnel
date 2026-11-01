package com.zaneschepke.wireguardautotunnel.ptunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * P-Tunnel endpoint model. Mirrors the published endpoints.json schema:
 * {"v":1,"updated_at":"<ISO8601>","isp":"MPT",
 *  "endpoints":[{"ip":..,"port":500,"ms":..,"jitter_ms":..}]}
 *
 * Publisher order is significant and is preserved end to end: the feed service
 * returns endpoints in file order, the store persists them in that order, and
 * the UI shows a single endpoint (JSON #1 before connecting, the actually
 * connected endpoint while connected) rather than re-sorting them.
 */
@Serializable
data class EndpointInfo(
    val ip: String,
    val port: Int = 500,
    val ms: Long? = null,
    @SerialName("jitter_ms") val jitterMs: Long? = null,
) {
    val id: String get() = "$ip:$port"
}

@Serializable
data class EndpointsFeedJson(
    val v: Int? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val isp: String? = null,
    val endpoints: List<EndpointInfo> = emptyList(),
)

data class FetchedEndpoints(
    /** updated_at from the file, epoch millis. 0 if unparseable. */
    val updatedAt: Long,
    /** In published JSON order. Never re-sorted by the app. */
    val endpoints: List<EndpointInfo>,
)

/**
 * UI state for the single P-Tunnel endpoint card on home. This is deliberately
 * separate from [com.zaneschepke.wireguardautotunnel.ui.state.TunnelsUiState]:
 * the P-Tunnel tunnel is transient (see PTunnelConnect) and is not a Room
 * tunnel row, so it must not flow through the tunnel-list rows.
 */
data class PTunnelUiState(
    /** Cached endpoints in JSON order (empty until the first feed fetch). */
    val endpoints: List<EndpointInfo> = emptyList(),
    /**
     * The single endpoint shown in the card: JSON #1 before connecting, and
     * the actually connected endpoint while connected (see SharedAppViewModel
     * for how this is derived from [connectedEndpoint]).
     */
    val displayedEndpoint: EndpointInfo? = null,
    /** Non-null only while a P-Tunnel tunnel is up. */
    val connectedEndpoint: EndpointInfo? = null,
    /**
     * One-time manual choice from tapping the card (v2, #3). In-memory only,
     * never persisted: it is consumed when the next connect starts, cleared
     * by a manual Refresh, and naturally gone on app reopen (new ViewModel).
     * Null means "follow the feed": displayed candidate is JSON #1.
     */
    val manualEndpoint: EndpointInfo? = null,
    /** True only while a user-initiated manual Refresh fetch is in flight. */
    val isFeedRefreshing: Boolean = false,
    val isConnecting: Boolean = false,
    val connectingEndpoint: EndpointInfo? = null,
    /** User-safe, generic error text for the card. Detail goes to Crashlytics, not here. */
    val lastError: String? = null,
    /**
     * v3: short, non-secret feed-failure line, shown on the home card
     * ONLY when there is no endpoint at all to show (fresh install, feed
     * failed, cache empty). It is never set by the background poll and
     * never replaces a cached or connected endpoint; a later successful
     * feed clears it. Never a PrivateKey, conf text, user IP, or URL.
     */
    val feedError: String? = null,
)

/**
 * v3: maps a feed failure to a short user-safe line for the home card.
 * Only the failure KIND is surfaced (timeout / DNS / HTTP code); the
 * exception message is used solely when it is exactly "HTTP <code>",
 * so no URL, host detail, or other message content can leak into UI.
 */
fun describeFeedFailure(error: Throwable): String {
    return when (error) {
        is java.net.SocketTimeoutException,
        is kotlinx.coroutines.TimeoutCancellationException ->
            "Feed timed out — tap Refresh to retry"
        is java.net.UnknownHostException ->
            "Feed: network unavailable — tap Refresh to retry"
        is java.io.IOException -> {
            val httpCode = Regex("^HTTP (\\d+)$").find(error.message ?: "")
                ?.groupValues?.getOrNull(1)
            if (httpCode != null) {
                "Feed failed (HTTP $httpCode) — tap Refresh to retry"
            } else {
                "Feed failed — tap Refresh to retry"
            }
        }
        else -> "Feed failed — tap Refresh to retry"
    }
}
