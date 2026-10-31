package com.zaneschepke.wireguardautotunnel.ptunnel

import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import timber.log.Timber

/**
 * Restores the P-Tunnel tunnel under WG Tunnel's StartupCoordinator policy,
 * without inventing an always-on rule and without a Room row for P-Tunnel
 * (so the home tunnel list is never polluted with a synthetic row).
 *
 * Why a handler is needed at all: the P-Tunnel tunnel is transient
 * (id -1, see [PTunnelConnect]). WG Tunnel's sticky/package-replace path
 * resolves last-active ids through TunnelRepository.getById with
 * mapNotNull (StartupCoordinator.kt, restoreRuntimeState), so -1 would be
 * dropped and then pruned from the stored list. This handler therefore
 * resolves P-Tunnel from its own app-private store instead of Room, while
 * StartupCoordinator keeps every WG gate and precedence rule intact
 * (see StartupCoordinator.kt: AutoTunnel first, then the restore gate,
 * revoked means clear-and-skip).
 *
 * Last-active record: PTunnelStore's stored connected endpoint. It is
 * saved on a successful connect and cleared on a manual disconnect
 * (SharedAppViewModel.stopPTunnel), so it survives a reboot / process
 * death exactly when P-Tunnel was up at shutdown, and is absent when the
 * user had disconnected. No separate flag is needed, and no new
 * persistence is introduced.
 *
 * Restored endpoint: the stored connected endpoint (the last-active
 * tunnel, as WG restores last-active tunnels), NOT a fresh feed #1 and
 * NOT a network fetch — boot restore must not depend on the network
 * being ready. If the stored endpoint is no longer in the cached feed,
 * it is still used as-is (ip:port from the stored id), because the
 * endpoint itself is what was last active.
 *
 * Returns true only when a restore start was actually attempted (a
 * config could be built from a stored conf + endpoint). A false return
 * means "P-Tunnel was not restorable here" and the caller should fall
 * through to the normal WG path (Room default / Room last-active).
 * Note: an attempted start may still no-op inside TunnelCoordinator if
 * VPN permission is gone — that mirrors WG's boot path, which also
 * calls startTunnel and returns without trying another tunnel.
 *
 * Never logs the conf text or key material; only ids and presence.
 */
class PTunnelRestoreHandler(
    private val ptunnelStore: PTunnelStore,
    private val tunnelCoordinator: TunnelCoordinator,
) {

    /** True when P-Tunnel was last-active at process death (stored endpoint present). */
    suspend fun isLastActive(): Boolean {
        return ptunnelStore.getConnectedEndpoint() != null &&
            ptunnelStore.getBaseConf() != null
    }

    /** Clears the P-Tunnel last-active record (mirrors WG's clear on VPN revoke). */
    suspend fun clearLastActive() {
        ptunnelStore.clearConnectedEndpoint()
    }

    suspend fun tryRestore(): Boolean {
        val storedId = ptunnelStore.getConnectedEndpoint() ?: return false
        val baseConf = ptunnelStore.getBaseConf() ?: return false
        val endpoint = parseStoredEndpoint(storedId) ?: return false
        val config =
            try {
                PTunnelConnect.buildConfig(baseConf, endpoint)
            } catch (_: Exception) {
                // Stored conf has no Endpoint line: cannot restore, and
                // must not invent one. Fall through to the normal path.
                return false
            }
        Timber.d("P-Tunnel restore: starting last-active endpoint ${endpoint.id}")
        tunnelCoordinator.startTunnel(config)
        return true
    }

    private fun parseStoredEndpoint(storedId: String): EndpointInfo? {
        val trimmed = storedId.trim()
        if (trimmed.isEmpty()) return null
        val lastColon = trimmed.lastIndexOf(':')
        if (lastColon <= 0) return null
        val ip = trimmed.substring(0, lastColon)
        val port = trimmed.substring(lastColon + 1).toIntOrNull() ?: 500
        if (ip.isBlank()) return null
        return EndpointInfo(ip = ip, port = port)
    }
}
