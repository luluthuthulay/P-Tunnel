package com.zaneschepke.wireguardautotunnel.ptunnel

import android.content.Context
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Fetches the published endpoint feed, Phoenix-style: Lovable relay first,
 * GitHub raw as fallback. The relay is reachable on networks where GitHub is
 * not, and it serves the same file, so callers see no difference between the
 * two sources. A failure of both sources is reported to the caller, which
 * keeps the previously cached list instead of failing the app.
 *
 * The relay URL is the existing Phoenix relay (ipmh.lovable.app), per the
 * user's decision: P-Tunnel calls that relay first and the new Lovable
 * account is only used if a new relay is needed later. No new Lovable
 * project is created by this code.
 */
object PTunnelFeedService {
    /** Lovable feed relay (existing Phoenix relay, used first). */
    const val RELAY_URL = "https://ipmh.lovable.app/api/test-relay"

    /** Fallback: tugyix05 Warp-IP-Scanner endpoints.json, shared with Phoenix. */
    const val RAW_URL =
        "https://raw.githubusercontent.com/tugyix05/Warp-IP-Scanner/main/endpoints.json"

    const val USER_AGENT = "PTunnel/1.0 (Android)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(context: Context): Result<FetchedEndpoints> {
        val relay = attempt(context, RELAY_URL)
        if (relay.isSuccess) return relay
        return attempt(context, RAW_URL)
    }

    private suspend fun attempt(context: Context, url: String): Result<FetchedEndpoints> {
        return try {
            val body = PTunnelNetwork.getBound(context, url, USER_AGENT)
            Result.success(parse(body))
        } catch (e: Exception) {
            // Never log the body here: on a relay misconfiguration it could
            // theoretically echo unrelated content. Exception class/message
            // only (the caller adds diagnostics custom keys, no secrets).
            Timber.w(e, "PTunnel feed fetch failed")
            Result.failure(e)
        }
    }

    /** Serialises an endpoint list for the cache (order preserved). */
    fun serialize(endpoints: List<EndpointInfo>): String {
        return json.encodeToString(EndpointsFeedJson(endpoints = endpoints))
    }

    /** Visible for tests. Parses and validates, preserving published order. */
    fun parse(body: String): FetchedEndpoints {
        val parsed = json.decodeFromString<EndpointsFeedJson>(body)
        val list = parsed.endpoints.filter { it.ip.isNotBlank() }
        if (list.isEmpty()) throw IOException("empty endpoints array")
        return FetchedEndpoints(updatedAt = parseIso8601(parsed.updatedAt), endpoints = list)
    }

    private fun parseIso8601(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
