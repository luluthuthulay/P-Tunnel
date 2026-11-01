package com.zaneschepke.wireguardautotunnel.ptunnel

import android.content.Context
import java.io.IOException
import timber.log.Timber

/**
 * Fetches the base WireGuard conf for P-Tunnel.
 *
 * Source: https://pguard.val.run/ (same "conf first" source as Phoenix-VPN;
 * trailing slash matches Phoenix ConfigService.GENERATE_URL exactly).
 * The returned text is stored verbatim by [PTunnelStore] and is used, with
 * only its Endpoint line swapped, as the tunnel's quickConfig (the same way
 * WG Tunnel imports a pasted conf: [com.wgtunnel.parser.Config.parseQuickString]).
 *
 * v3: conf timeouts are Phoenix ConfigService's own (connect 25s / read
 * 25s / call 30s), NOT the feed's 20s/20s/15s that v1/v2 used for both.
 * The fetch itself is unbound-only, exactly like Phoenix ConfigService's
 * plain default-network client — NO physical-network binding at all
 * (correction from Hsu 2026-11-01 09:46; the earlier v3 draft's
 * bound-first conf path was removed). The feed, not the conf, is the
 * one that uses bound-first-then-unbound.
 *
 * SECURITY: the conf contains key material. This class never logs, prints,
 * or includes the conf text (or any part of it) in an exception message,
 * a Timber log, a Crashlytics report, or a UI string. Callers must treat
 * the returned string the same way.
 */
object PTunnelConfService {
    const val CONF_URL = "https://pguard.val.run/"
    const val USER_AGENT = "PTunnel/1.0 (Android)"

    // Context is kept in the signature so existing call sites are
    // unchanged; the unbound fetch needs no network lookup from it.
    @Suppress("UNUSED_PARAMETER")
    suspend fun fetch(context: Context): Result<String> {
        return try {
            val body =
                PTunnelNetwork.getUnbound(
                    CONF_URL,
                    USER_AGENT,
                    PTunnelNetwork.CONF_CONNECT_TIMEOUT_MS,
                    PTunnelNetwork.CONF_READ_TIMEOUT_MS,
                    PTunnelNetwork.CONF_CALL_TIMEOUT_MS,
                )
            if (!body.contains("[Interface]") || !body.contains("[Peer]")) {
                return Result.failure(
                    IOException("conf response did not look like a WireGuard conf")
                )
            }
            Result.success(body)
        } catch (e: Exception) {
            Timber.w(e, "PTunnel conf fetch failed")
            Result.failure(e)
        }
    }
}
