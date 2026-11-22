package com.zaneschepke.wireguardautotunnel.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v5.8.10 pre-download update check tests (JVM unit tests, run in CI
 * before the assemble step). The decision is pure: the route serves
 * the expected filename -> route; anything else -> GitHub direct.
 */
class PTunnelUpdatePrecheckTest {
    private val route = "https://ipmh.lovable.app/api/public/apk/ptunnel"
    private val github =
        "https://github.com/luluthuthulay/P-Tunnel/releases/download/v5.8.9/wgtunnel-standalone-v5.8.9.apk"
    private val expected = "wgtunnel-standalone-v5.8.9.apk"

    @Test
    fun `filename parser reads quoted and unquoted values`() {
        assertEquals(
            expected,
            parseContentDispositionFilename("attachment; filename=\"$expected\""),
        )
        assertEquals(
            expected,
            parseContentDispositionFilename("attachment; filename=$expected"),
        )
        // Trailing parameters after the filename are ignored.
        assertEquals(
            expected,
            parseContentDispositionFilename("attachment; filename=\"$expected\"; size=123"),
        )
    }

    @Test
    fun `filename parser returns null without a filename`() {
        assertNull(parseContentDispositionFilename(null))
        assertNull(parseContentDispositionFilename(""))
        assertNull(parseContentDispositionFilename("attachment"))
        assertNull(parseContentDispositionFilename("attachment; filename=\"\""))
        // RFC 5987 filename* is not used by the route; ignore it.
        assertNull(parseContentDispositionFilename("attachment; filename*=UTF-8''$expected"))
    }

    @Test
    fun `route match downloads from the route`() {
        assertEquals(
            route,
            choosePrecheckDownloadUrl(route, github, expected, expected),
        )
    }

    @Test
    fun `route mismatch or unknown goes GitHub direct`() {
        assertEquals(
            github,
            choosePrecheckDownloadUrl(
                route,
                github,
                expected,
                "wgtunnel-standalone-v5.8.6.apk",
            ),
        )
        assertEquals(
            github,
            choosePrecheckDownloadUrl(route, github, expected, null),
        )
    }

    @Test
    fun `blank GitHub URL keeps the route`() {
        assertEquals(
            route,
            choosePrecheckDownloadUrl(
                route,
                null,
                expected,
                "wgtunnel-standalone-v5.8.6.apk",
            ),
        )
        assertEquals(
            route,
            choosePrecheckDownloadUrl(route, "", expected, null),
        )
    }
}
