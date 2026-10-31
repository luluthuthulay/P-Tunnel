package com.zaneschepke.wireguardautotunnel.ptunnel

import android.content.Context
import java.io.IOException
import timber.log.Timber

/**
 * Fetches the base WireGuard conf for P-Tunnel.
 *
 * Source: https://pguard.val.run (same "conf first" source as Phoenix-VPN).
 * The returned text is stored verbatim by [PTunnelStore] and is used, with
 * only its Endpoint line swapped, as the tunnel's quickConfig (the same way
 * WG Tunnel imports a pasted conf: [com.wgtunnel.parser.Config.parseQuickString]).
 *
 * SECURITY: the conf contains key material. This class never logs, prints,
 * or includes the conf text (or any part of it) in an exception message,
 * a Timber log, a Crashlytics report, or a UI string. Callers must treat
 * the returned string the same way.
 */
object PTunnelConfService {
    const val CONF_URL = "https://pguard.val.run"
    const val USER_AGENT = "PTunnel/1.0 (Android)"

    suspend fun fetch(context: Context): Result<String> {
        return try {
            val body = PTunnelNetwork.getBound(context, CONF_URL, USER_AGENT)
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
