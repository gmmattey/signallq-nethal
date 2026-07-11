package com.nethal.core.driver.nokia

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

class NokiaOntDriverTest {

    @Test
    fun `rejects public host at construction - never sends credentials outside the LAN`() {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = "",
            loginResponses = mutableListOf(),
            authenticatedPages = emptyMap(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            NokiaOntDriver("8.8.8.8", transport)
        }
    }

    private fun loginPageWithGeneratedKey(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val pubkeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        return """
            var pubkey = '-----BEGIN PUBLIC KEY-----$pubkeyBase64-----END PUBLIC KEY-----';
            var nonce = "test-nonce";
            var token = "test-csrf-token";
        """.trimIndent()
    }

    private val samplePages = mapOf(
        "/wan_status.cgi?gpon" to """var GponConnectionStat = 1; var RXPower = "251189"; var TXPower = "158489";""",
        "/show_wan_status.cgi?ipv4" to """
            var wan_conns = {ConnectionStatus:'Connected',ExternalIPAddress:'203.0.113.10',RemoteIPAddress:'198.51.100.1',DNSServers:'8.8.8.8,8.8.4.4',Uptime:60,ConnectionType:'IP_Routed'};
        """.trimIndent(),
        "/index.cgi?getppp" to """{"ppp_status":[{"ConnectionStatus":"Connected","ConnectionType":"PPPoE","Name":"s","LastConnectionError":""}]}""",
        "/device_status.cgi" to """{"ModelName":"G-1425G-B","Manufacturer":"Nokia","SerialNumber":"ALCLXXXXXXXX","SoftwareVersion":"v1","HardwareVersion":"1.0","UpTime":100}""",
        "/lan_status.cgi?wlan" to sampleHomeNetworkingHtml(),
    )

    @Test
    fun `readSnapshot succeeds on first attempt and parses all four endpoints`() = runTest {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(successfulLoginResponse()),
            authenticatedPages = samplePages,
        )
        val driver = NokiaOntDriver("192.168.1.1", transport, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is NokiaDriverResult.Success)
        val snapshot = (result as NokiaDriverResult.Success).snapshot
        assertTrue(snapshot.gpon?.isUp == true)
        assertEquals("203.0.113.10", snapshot.wan?.externalIp)
        assertTrue(snapshot.ppp?.isConnected == true)
        assertEquals("G-1425G-B", snapshot.deviceInfo?.model)
        assertEquals(2, snapshot.connectedClients.size)
        assertEquals("08:b4:d2:**:**:**", snapshot.connectedClients.first().macAddressMasked)
    }

    @Test
    fun `readSnapshot retries on token expired and succeeds on second attempt`() = runTest {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(errorLoginResponse(errT = 2), successfulLoginResponse()),
            authenticatedPages = samplePages,
        )
        val driver = NokiaOntDriver("192.168.1.1", transport, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is NokiaDriverResult.Success)
        assertEquals(2, transport.postCallCount)
    }

    @Test
    fun `readSnapshot fails fast on invalid credentials without exhausting retries`() = runTest {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(errorLoginResponse(errT = 1)),
            authenticatedPages = samplePages,
        )
        val driver = NokiaOntDriver("192.168.1.1", transport, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "wrong")

        assertTrue(result is NokiaDriverResult.Failure)
        assertEquals(NokiaDriverFailureReason.INVALID_CREDENTIALS, (result as NokiaDriverResult.Failure).reason)
        assertEquals(1, transport.postCallCount) // não tentou de novo — credencial errada não se resolve com retry
    }

    @Test
    fun `readSnapshot fails fast on session in use without exhausting retries`() = runTest {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(errorLoginResponse(errT = 0)),
            authenticatedPages = samplePages,
        )
        val driver = NokiaOntDriver("192.168.1.1", transport, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is NokiaDriverResult.Failure)
        assertEquals(NokiaDriverFailureReason.SESSION_IN_USE, (result as NokiaDriverResult.Failure).reason)
        assertEquals(1, transport.postCallCount)
    }

    @Test
    fun `readSnapshot exhausts all attempts and reports failure when token keeps expiring`() = runTest {
        val transport = FakeNokiaHttpTransport(
            loginPageBody = loginPageWithGeneratedKey(),
            loginResponses = mutableListOf(
                errorLoginResponse(errT = 2),
                errorLoginResponse(errT = 2),
                errorLoginResponse(errT = 2),
            ),
            authenticatedPages = samplePages,
        )
        var backoffCalls = 0
        val driver = NokiaOntDriver("192.168.1.1", transport, maxAttempts = 3, backoffMillis = { backoffCalls++; 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is NokiaDriverResult.Failure)
        assertEquals(3, transport.postCallCount)
        assertEquals(2, backoffCalls) // backoff só entre tentativas (attempt 1 e 2), não antes da primeira
    }
}
