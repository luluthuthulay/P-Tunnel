package com.zaneschepke.wireguardautotunnel.ptunnel

import com.wgtunnel.parser.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.8.9 fast-connect port tests (JVM unit tests, run in CI before the
 * assemble step). The parser test is the R2 gate: if the wgtunnel
 * parser does not honour ListenPort, the walk must ship R1-only.
 */
class PTunnelFastConnectTest {
    // Valid 32-byte keys, test fixtures only — never real credentials.
    private val testPrivateKey = "NoAaafhfmDk8saJT8/5yeBzf5hthviZ4llGFkh87kGw="
    private val testPublicKey = "SXAsNFEnD4D+Dwl55KgchfXGjkA/Nsor9entSHmHwDU="

    private fun testConf(listenPort: Int? = null): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $testPrivateKey")
        if (listenPort != null) appendLine("ListenPort = $listenPort")
        appendLine("Address = 10.0.0.2/32")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $testPublicKey")
        appendLine("AllowedIPs = 0.0.0.0/0")
        appendLine("Endpoint = 162.159.192.28:500")
    }

    @Test
    fun `parser honours ListenPort from parseQuickString`() {
        val config = Config.parseQuickString(testConf(listenPort = 54321))
        assertEquals("54321", config.`interface`.listenPort?.toString())
    }

    @Test
    fun `setListenPort inserts and replaces`() {
        val inserted = PTunnelConnect.setListenPort(testConf(), 54321)
        assertTrue(inserted.contains("ListenPort = 54321"))
        val replaced = PTunnelConnect.setListenPort(inserted, 12345)
        assertTrue(replaced.contains("ListenPort = 12345"))
        assertEquals(1, "ListenPort".toRegex().findAll(replaced).count())
        // Out-of-range ports leave the conf unchanged.
        assertEquals(testConf(), PTunnelConnect.setListenPort(testConf(), 0))
        assertEquals(testConf(), PTunnelConnect.setListenPort(testConf(), 70000))
        // A conf with no [Interface] section is returned unchanged.
        assertEquals(
            "nonsense",
            PTunnelConnect.setListenPort("nonsense", 54321),
        )
    }

    @Test
    fun `peer key extraction from base conf`() {
        val key = PTunnelProber.peerPublicKeyFromConf(testConf())
        assertNotNull(key)
        assertEquals(32, key!!.size)
        // Missing PublicKey -> null (prober cannot run; walk falls back).
        assertNull(
            PTunnelProber.peerPublicKeyFromConf(
                "[Interface]\nPrivateKey = $testPrivateKey\n",
            ),
        )
        // Malformed base64 -> null.
        assertNull(
            PTunnelProber.peerPublicKeyFromConf(
                "[Peer]\nPublicKey = !!!not-base64!!!\n",
            ),
        )
    }

    @Test
    fun `buildInitiation produces a 148-byte type-1 packet`() {
        val key = PTunnelProber.peerPublicKeyFromConf(testConf())!!
        val prober = PTunnelProber(key)
        val a = prober.buildInitiation()
        val b = prober.buildInitiation()
        assertEquals(148, a.size)
        // a[0] is a Byte: compare as Int, or JUnit's Object overload
        // compares Integer(1) with Byte(1) and fails on the type.
        assertEquals(1, a[0].toInt())
        // Fresh ephemeral key per initiation: two packets differ.
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `effectiveList falls back to the bundled trio`() {
        assertEquals(PTunnelConnect.BUNDLED, PTunnelConnect.effectiveList(emptyList()))
        val cached = listOf(EndpointInfo("1.2.3.4", 500))
        assertEquals(cached, PTunnelConnect.effectiveList(cached))
    }
}
