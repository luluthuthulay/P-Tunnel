package com.zaneschepke.wireguardautotunnel.ptunnel

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber

/**
 * Probe-first walk orchestration shared by the ViewModel walk
 * ([runPTunnelConnect] in SharedAppViewModel) and the boot walk
 * ([PTunnelRestoreHandler.tryBootAuto]) so the two can never diverge
 * (v5.8.9 fast-connect port, Phoenix R1/R2/R4).
 *
 * - With a [PTunnelProber]: probe rounds (800ms + 300ms grace, up to 3
 *   rounds) over ALL candidates in parallel; attempt responders in
 *   arrival order (the start candidate's responder first when it
 *   answered); re-roll with fresh sockets when a round yields no
 *   connection. A responder's tunnel attempt keeps the full 5s
 *   handshake window.
 * - Without a prober (no peer key could be derived from the base conf):
 *   the legacy sequential walk ([PTunnelConnect.orderedWalk]) — the
 *   pre-5.8.9 behaviour, no worse than today. The prober is never
 *   faked with a made-up key.
 *
 * The helper owns ONLY ordering and rounds. Tunnel starts, handshake
 * waits, permission handling, diagnostics and UI updates live in the
 * caller's [attempt] lambda, because the two walkers differ there.
 * [shouldContinue] is checked before every round and every attempt:
 * false stops the walk WITHOUT exhausting (the boot walk's mid-walk
 * network-loss rule).
 */
object PTunnelProbeWalk {

    /** One attempt the helper asks the caller to make. */
    data class WalkAttempt(
        val endpoint: EndpointInfo,
        /** Winning probe flow's local port (R2); null in the legacy fallback. */
        val localPort: Int?,
        /** 0-based index within the current ordering (legacy: orderedWalk index). */
        val attemptIndex: Int,
    )

    enum class AttemptOutcome {
        CONNECTED,
        FAILED,
        /** Stop the walk without exhausting (e.g. permission pause). */
        ABORT,
    }

    sealed interface Result {
        data class Connected(val endpoint: EndpointInfo) : Result

        /** No responder connected after all rounds (caller sets exhausted). */
        data object Exhausted : Result

        /** Stopped early without exhausting (permission pause / network loss). */
        data object Aborted : Result
    }

    suspend fun run(
        candidates: List<EndpointInfo>,
        startId: String?,
        prober: PTunnelProber?,
        shouldContinue: () -> Boolean,
        attempt: suspend (WalkAttempt) -> AttemptOutcome,
    ): Result {
        if (candidates.isEmpty()) return Result.Exhausted
        if (prober == null) {
            return legacyWalk(candidates, startId, shouldContinue, attempt)
        }
        var attemptCounter = 0
        repeat(PTunnelProber.MAX_PROBE_ROUNDS) { round ->
            currentCoroutineContext().ensureActive()
            if (!shouldContinue()) return Result.Aborted
            val responders = prober.probeRound(
                candidates,
                PTunnelProber.PROBE_ROUND_TIMEOUT_MS,
                PTunnelProber.PROBE_RESPONDER_GRACE_MS,
            )
            // Start candidate's responder first when it answered, else
            // arrival order (stable sort keeps responder order otherwise).
            val ordered = responders.sortedWith(
                compareBy { if (it.endpoint.id == startId) 0 else 1 },
            )
            for (responder in ordered) {
                currentCoroutineContext().ensureActive()
                if (!shouldContinue()) return Result.Aborted
                when (
                    attempt(
                        WalkAttempt(
                            endpoint = responder.endpoint,
                            localPort = responder.localPort,
                            attemptIndex = attemptCounter++,
                        ),
                    )
                ) {
                    AttemptOutcome.CONNECTED -> return Result.Connected(responder.endpoint)
                    AttemptOutcome.ABORT -> return Result.Aborted
                    AttemptOutcome.FAILED -> { /* next responder */ }
                }
            }
            Timber.i(
                "PTunnelProbeWalk: probe-first round ${round + 1}/${PTunnelProber.MAX_PROBE_ROUNDS}: " +
                    "${responders.size}/${candidates.size} responders, no connection yet",
            )
        }
        return Result.Exhausted
    }

    private suspend fun legacyWalk(
        candidates: List<EndpointInfo>,
        startId: String?,
        shouldContinue: () -> Boolean,
        attempt: suspend (WalkAttempt) -> AttemptOutcome,
    ): Result {
        val ordered = PTunnelConnect.orderedWalk(candidates, startId)
        for ((index, candidate) in ordered.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!shouldContinue()) return Result.Aborted
            when (attempt(WalkAttempt(candidate, null, index))) {
                AttemptOutcome.CONNECTED -> return Result.Connected(candidate)
                AttemptOutcome.ABORT -> return Result.Aborted
                AttemptOutcome.FAILED -> { /* next candidate */ }
            }
        }
        return Result.Exhausted
    }
}
