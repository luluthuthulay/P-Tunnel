package com.zaneschepke.wireguardautotunnel.ptunnel

import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig

/**
 * P-Tunnel tunnel construction.
 *
 * The P-Tunnel tunnel is TRANSIENT, not a Room tunnel row: it is built from
 * the stored base conf at connect time and is never saved through
 * TunnelRepository. Consequences, all deliberate:
 * - The home tunnel list is not polluted with a synthetic row, and the
 *   single endpoint card (not a list row) is the only P-Tunnel control.
 * - [com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator]
 *   restart/restore paths look tunnels up by id in the repository and use
 *   mapNotNull, so this id is ignored there. v2 auto-restore (WG rule)
 *   therefore does NOT use a Room row: PTunnelRestoreHandler resolves
 *   the last-active P-Tunnel tunnel from PTunnelStore's stored connected
 *   endpoint, and StartupCoordinator consults it under WG's exact gates
 *   (AutoTunnel first, then restore-on-boot for boot / last-active for
 *   sticky/package-replace, revoked means clear-and-skip). The home list
 *   is never polluted, because no Room row is created for P-Tunnel.
 * - AutoTunnel never triggers this tunnel: it starts repository tunnels only.
 *
 * The negative id is chosen so it can never collide with a Room row id.
 */
object PTunnelConnect {
    const val PTUNNEL_TUNNEL_ID = -1
    const val PTUNNEL_TUNNEL_NAME = "P Tunnel"

    private val endpointLineRegex = Regex("(?m)^\\s*Endpoint\\s*=.*$")

    /**
     * Returns the base conf with ONLY its Endpoint line(s) replaced by
     * [endpoint]. PrivateKey, PublicKey, DNS, MTU and AllowedIPs are left
     * untouched. Throws if the conf has no Endpoint line rather than
     * inventing one.
     *
     * NOTE: the pguard conf has a single peer, so in practice exactly one
     * line is replaced. Multiple endpoints are NEVER put into one conf as
     * simultaneous peers (identical AllowedIPs 0.0.0.0/0 would break
     * routing); failover tries candidates one at a time with separate
     * tunnel starts instead.
     */
    fun swapEndpoint(baseConf: String, endpoint: EndpointInfo): String {
        require(endpointLineRegex.containsMatchIn(baseConf)) {
            "Stored conf has no Endpoint line"
        }
        return endpointLineRegex.replace(baseConf, "Endpoint = ${endpoint.id}")
    }

    fun buildConfig(baseConf: String, endpoint: EndpointInfo): TunnelConfig {
        return TunnelConfig(
            id = PTUNNEL_TUNNEL_ID,
            name = PTUNNEL_TUNNEL_NAME,
            quickConfig = swapEndpoint(baseConf, endpoint),
        )
    }

    /**
     * Two-pass failover order over [candidates] in JSON order: pass 1
     * from the [startId] candidate to the end, pass 2 the whole list
     * once more. Success at any attempt stops the walk at once; the
     * second pass ending with no success stops, never wraps again.
     * A missing or unknown [startId] starts from JSON #1. Shared by the
     * ViewModel walk and the boot walk so the order can never diverge.
     */
    fun orderedWalk(candidates: List<EndpointInfo>, startId: String?): List<EndpointInfo> {
        val startIndex =
            startId?.let { wanted -> candidates.indexOfFirst { it.id == wanted } }
                ?.takeIf { it >= 0 } ?: 0
        return candidates.subList(startIndex, candidates.size) + candidates
    }
}
