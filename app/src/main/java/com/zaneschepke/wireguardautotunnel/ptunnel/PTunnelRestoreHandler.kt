package com.zaneschepke.wireguardautotunnel.ptunnel

import com.wgtunnel.backend.Tunnel
import com.zaneschepke.networkmonitor.StableNetworkEngine
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.service.ServiceManager
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * Boot auto ([tryBootAuto]) is separate from [tryRestore]: it runs only
 * in the AutoTunnel branch (after the service starts, which holds the
 * process foreground), waits for usable internet, then walks the cached
 * list last-connected-first with the same two-pass failover as the
 * ViewModel walk. The instant single-attempt [tryRestore] stays the
 * path everywhere else, so the default/sticky branches keep their
 * no-network-dependency behavior.
 */
class PTunnelRestoreHandler(
    private val ptunnelStore: PTunnelStore,
    private val tunnelCoordinator: TunnelCoordinator,
    private val serviceManager: ServiceManager,
    private val stableNetworkEngine: StableNetworkEngine,
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

    /**
     * Boot auto (WG-style): on boot, once usable internet is available,
     * auto-connect the LAST-CONNECTED endpoint, walking to the next one
     * on failure exactly like the ViewModel walk (two passes over the
     * cached list in order; success stops at once; exhaustion stops
     * until a manual ON).
     *
     * Gates (all must hold, else false = "not applicable"):
     * - a last-connected record exists (a manual disconnect clears it,
     *   so there is nothing to auto-connect) and a base conf is stored;
     * - VPN permission is granted (auto never requests permission);
     * - no other P-Tunnel walk holds [PTunnelWalkGuard].
     *
     * Network: waits up to [BOOT_NETWORK_WAIT_MS] for usable internet.
     * On timeout it falls back to a single start of the last-connected
     * endpoint (the old tryRestore behavior): the tunnel itself
     * handshakes when the network arrives, instead of burning the walk
     * on 40 futile handshake timeouts with no network at all.
     *
     * Returns true when a start or walk was attempted (the boot slot is
     * claimed; callers must not fall through to a Room default). Runs
     * only where a foreground service holds the process (the AutoTunnel
     * branch, after the service starts); the instant single-attempt
     * [tryRestore] stays the path everywhere else.
     */
    suspend fun tryBootAuto(): Boolean {
        val storedId = ptunnelStore.getConnectedEndpoint() ?: return false
        val baseConf = ptunnelStore.getBaseConf() ?: return false
        if (!serviceManager.hasVpnPermission()) return false
        if (!PTunnelWalkGuard.tryAcquire()) return false
        try {
            val usable =
                withTimeoutOrNull(BOOT_NETWORK_WAIT_MS) {
                    stableNetworkEngine.stableState.mapNotNull { it?.state }
                        .first { it.hasUsableNetwork() }
                }
            if (usable == null) {
                val endpoint = parseStoredEndpoint(storedId) ?: return false
                val config =
                    try {
                        PTunnelConnect.buildConfig(baseConf, endpoint)
                    } catch (_: Exception) {
                        return false
                    }
                Timber.d("P-Tunnel boot auto: no usable network yet, single start of last-connected ${endpoint.id}")
                tunnelCoordinator.startTunnel(config)
                return true
            }
            val cached =
                ptunnelStore.getEndpointsJson()?.let { raw ->
                    runCatching { PTunnelFeedService.parse(raw).endpoints }.getOrNull()
                }.orEmpty()
            val storedEndpoint = parseStoredEndpoint(storedId)
            val candidates =
                when {
                    storedEndpoint == null -> cached
                    cached.any { it.id == storedEndpoint.id } -> cached
                    else -> listOf(storedEndpoint) + cached
                }
            if (candidates.isEmpty()) return false
            val ordered = PTunnelConnect.orderedWalk(candidates, storedId)
            val context = ptunnelStore.appContext
            for (candidate in ordered) {
                val config =
                    try {
                        PTunnelConnect.buildConfig(baseConf, candidate)
                    } catch (e: Exception) {
                        PTunnelDiagnostics.report(context, "connect", candidate, e)
                        continue
                    }
                tunnelCoordinator.startTunnel(config)
                if (awaitBootHandshake()) {
                    ptunnelStore.saveConnectedEndpoint(candidate.id)
                    Timber.d("P-Tunnel boot auto: connected ${candidate.id}")
                    return true
                }
                tunnelCoordinator.stopTunnel(PTunnelConnect.PTUNNEL_TUNNEL_ID)
                PTunnelDiagnostics.report(
                    context,
                    "connect",
                    candidate,
                    TimeoutException("handshake timeout"),
                    handshakeElapsedMs = BOOT_HANDSHAKE_TIMEOUT_MS,
                )
            }
            PTunnelWalkGuard.exhausted = true
            Timber.d("P-Tunnel boot auto: no endpoint connected")
            return true
        } finally {
            PTunnelWalkGuard.release()
        }
    }

    /**
     * Same handshake rule as the ViewModel walk: backend Healthy, or a
     * peer handshake timestamp at or after this attempt started, within
     * [BOOT_HANDSHAKE_TIMEOUT_MS] (must match the ViewModel's
     * PTUNNEL_HANDSHAKE_TIMEOUT_MS).
     */
    private suspend fun awaitBootHandshake(): Boolean {
        val attemptStartSec = System.currentTimeMillis() / 1000
        val deadline = System.currentTimeMillis() + BOOT_HANDSHAKE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val active =
                tunnelCoordinator.backendStatus.value
                    .activeTunnels[PTunnelConnect.PTUNNEL_TUNNEL_ID]
            if (active != null) {
                val healthy = active.transportState is Tunnel.State.Up.Healthy
                val handshakeLanded =
                    active.activeConfig?.peers?.any { peer ->
                        val last = peer.lastHandshakeSeconds
                        last != null && last >= attemptStartSec - 2
                    } == true
                if (healthy || handshakeLanded) return true
            }
            delay(250)
        }
        return false
    }

    private companion object {
        /** Max wait for usable internet at boot before the single-start fallback. */
        const val BOOT_NETWORK_WAIT_MS = 120_000L

        /** Per-candidate handshake wait for the boot walk (same as the ViewModel walk). */
        const val BOOT_HANDSHAKE_TIMEOUT_MS = 5_000L
    }
}
