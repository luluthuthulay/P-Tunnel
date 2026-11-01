package com.zaneschepke.wireguardautotunnel.ptunnel

import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-wide single-walk ownership for P-Tunnel connects.
 *
 * Two walkers exist: the ViewModel walk ([runPTunnelConnect] in
 * SharedAppViewModel) and the boot walk ([PTunnelRestoreHandler.tryBootAuto]).
 * Both drive the same transient tunnel (-1) through
 * TunnelCoordinator.startTunnel, which stops any active tunnel first
 * (startExclusive) — so two concurrent walks would fight each other,
 * each candidate start killing the other's tunnel mid-handshake. The
 * guard serializes them: a walk that cannot acquire skips instead of
 * fighting. The boot walk runs at startup (before any ViewModel exists);
 * the ViewModel walk runs on switch-ON / auto-observer triggers.
 *
 * [exhausted] is shared for the same reason: a walk that exhausted both
 * passes (no endpoint connected) blocks auto restarts until a manual ON,
 * no matter which walker exhausted. It is in-memory only (per process):
 * a fresh process re-evaluates auto from scratch, which is the desired
 * boot/app-open behavior — exhaustion is a within-session stop, not a
 * permanent ban.
 */
object PTunnelWalkGuard {
    private val busy = AtomicBoolean(false)

    @Volatile var exhausted: Boolean = false

    val isBusy: Boolean get() = busy.get()

    fun tryAcquire(): Boolean = busy.compareAndSet(false, true)

    fun release() {
        busy.set(false)
    }
}
