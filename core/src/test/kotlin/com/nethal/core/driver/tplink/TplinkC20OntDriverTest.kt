package com.nethal.core.driver.tplink

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TplinkC20OntDriverTest {

    @Test
    fun `rejects public host at construction - never sends credentials outside the LAN`() {
        val transport = FakeTplinkC20HttpTransport(loginResponses = mutableListOf())

        val exception = assertThrows(IllegalArgumentException::class.java) {
            TplinkC20OntDriver("8.8.8.8", transport)
        }
        assertTrue(exception.message!!.contains("8.8.8.8"))
    }

    @Test
    fun `rejects other well-known public hosts too - not a single hardcoded exception`() {
        val transport = FakeTplinkC20HttpTransport(loginResponses = mutableListOf())

        listOf("1.1.1.1", "203.0.113.10", "142.250.0.1").forEach { publicHost ->
            assertThrows(IllegalArgumentException::class.java) {
                TplinkC20OntDriver(publicHost, transport)
            }
        }
    }

    @Test
    fun `accepts RFC1918 private host at construction`() {
        val transport = FakeTplinkC20HttpTransport(loginResponses = mutableListOf())

        listOf("192.168.0.1", "10.0.0.1", "172.16.5.5").forEach { privateHost ->
            TplinkC20OntDriver(privateHost, transport) // não deve lançar
        }
    }

    private val samplePages = mapOf(
        "/cgi/getDeviceInfo" to """{"model":"Archer C20","hardwareVersion":"V4","firmwareVersion":"0.9.1","uptime":1800}""",
        "/cgi/getWanStatus" to """{"connectionType":"PPPoE","externalIp":"203.0.113.20","gateway":"203.0.113.1","primaryDns":"8.8.8.8","secondaryDns":"8.8.4.4","connectionStatus":"Connected"}""",
        "/cgi/getWifiStatus" to """[{"band":"2.4GHz","enabled":"true","ssid":"MinhaRedeC20","channel":"6"}]""",
        "/cgi/getConnectedClients" to """[{"hostname":"celular","ip":"192.168.0.20","mac":"AA:BB:CC:44:55:66","type":"wifi"}]""",
    )

    @Test
    fun `readSnapshot succeeds on first attempt and parses all endpoints`() = runTest {
        val transport = FakeTplinkC20HttpTransport(
            loginResponses = mutableListOf(successfulTplinkC20LoginResponse()),
            authenticatedPages = samplePages,
        )
        val driver = TplinkC20OntDriver("192.168.0.1", transport, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is TplinkC20DriverResult.Success)
        val snapshot = (result as TplinkC20DriverResult.Success).snapshot
        assertEquals("Archer C20", snapshot.deviceInfo?.model)
        assertEquals("203.0.113.20", snapshot.wan?.externalIp)
        assertEquals(1, snapshot.wifi.size)
        assertEquals(1, snapshot.connectedClients.size)
        assertEquals("AA:BB:CC:**:**:**", snapshot.connectedClients.first().macAddressMasked)
    }

    @Test
    fun `readSnapshot fails fast on invalid credentials without exhausting retries`() = runTest {
        val transport = FakeTplinkC20HttpTransport(
            loginResponses = mutableListOf(invalidCredentialsTplinkC20Response()),
            authenticatedPages = samplePages,
        )
        val driver = TplinkC20OntDriver("192.168.0.1", transport, maxAttempts = 2, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "wrong")

        assertTrue(result is TplinkC20DriverResult.Failure)
        assertEquals(TplinkC20DriverFailureReason.INVALID_CREDENTIALS, (result as TplinkC20DriverResult.Failure).reason)
        assertEquals(1, transport.postCallCount) // sem RSA handshake: só 1 POST de login, sem repetir por retry
    }

    @Test
    fun `readSnapshot respects conservative max attempts default of two`() = runTest {
        val transport = FakeTplinkC20HttpTransport(
            loginResponses = mutableListOf(
                TplinkHttpResponse(500, "", emptyMap(), emptyMap()),
                TplinkHttpResponse(500, "", emptyMap(), emptyMap()),
            ),
            authenticatedPages = samplePages,
        )
        var backoffCalls = 0
        val driver = TplinkC20OntDriver("192.168.0.1", transport, backoffMillis = { backoffCalls++; 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is TplinkC20DriverResult.Failure)
        assertEquals(1, backoffCalls) // só 1 backoff entre as 2 tentativas (default maxAttempts=2)
    }
}
