package com.zaneschepke.wireguardautotunnel.ptunnel

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Structured, non-fatal diagnostics for P-Tunnel network failures.
 *
 * Every feed, conf, and connect failure is reported to Crashlytics as a
 * recorded (non-fatal) exception with custom keys, so the developer can see
 * the exact stage and cause instead of a generic "network error":
 * stage, endpoint tried, exception class, network type, and handshake
 * elapsed time where applicable.
 *
 * NEVER reported: the conf text or any key material, the user's real IP,
 * DNS query contents, or traffic content. Exception messages are scrubbed
 * for key material before reporting, and reporting itself is guarded so a
 * missing/uninitialised Firebase (e.g. a build without google-services.json)
 * can never crash the app.
 */
object PTunnelDiagnostics {

    fun report(
        context: Context,
        stage: String,
        endpoint: EndpointInfo?,
        error: Throwable,
        handshakeElapsedMs: Long? = null,
    ) {
        val safeMessage = scrub(error.message)
        Timber.w(error, "PTunnel failure stage=$stage endpoint=${endpoint?.id}")
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("ptunnel_stage", stage)
            crashlytics.setCustomKey("ptunnel_endpoint", endpoint?.id ?: "none")
            crashlytics.setCustomKey("ptunnel_error_class", error.javaClass.simpleName)
            crashlytics.setCustomKey("ptunnel_network", PTunnelNetwork.networkTypeName(context))
            if (handshakeElapsedMs != null) {
                crashlytics.setCustomKey("ptunnel_handshake_ms", handshakeElapsedMs)
            }
            crashlytics.recordException(
                PTunnelNetworkException(
                    "PTunnel $stage failure: ${error.javaClass.simpleName}: $safeMessage"
                )
            )
        } catch (_: Exception) {
            // Diagnostics must never break the app (e.g. Firebase not initialised).
            Timber.w("PTunnel diagnostics unavailable, skipping Crashlytics report")
        }
    }

    private fun scrub(message: String?): String {
        if (message.isNullOrBlank()) return "unknown"
        if (message.contains("PrivateKey", ignoreCase = true)) return "<redacted>"
        return message.take(300)
    }

    private class PTunnelNetworkException(message: String) : Exception(message)
}
