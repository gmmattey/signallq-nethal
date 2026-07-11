package com.nethal.core.driver.tplink

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TplinkOntDriverTest {

    @Test
    fun `rejects public host at construction - never sends credentials outside the LAN`() {
        val transport = FakeTplinkHttpTransport(
            getParmResponse = sampleGetParmResponse(),
            loginResponses = mutableListOf(),
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            TplinkOntDriver("8.8.8.8", transport)
        }
        assertTrue(exception.message!!.contains("8.8.8.8"))
    }

    @Test
    fun `rejects other well-known public hosts too - not a single hardcoded exception`() {
        val transport = FakeTplinkHttpTransport(getParmResponse = sampleGetParmResponse(), loginResponses = mutableListOf())

        listOf("1.1.1.1", "203.0.113.10", "142.250.0.1").forEach { publicHost ->
            assertThrows(IllegalArgumentException::class.java) {
                TplinkOntDriver(publicHost, transport)
            }
        }
    }

    @Test
    fun `accepts RFC1918 private host at construction`() {
        val transport = FakeTplinkHttpTransport(getParmResponse = sampleGetParmResponse(), loginResponses = mutableListOf())

        listOf("192.168.0.1", "10.0.0.1", "172.16.5.5").forEach { privateHost ->
            TplinkOntDriver(privateHost, transport) // não deve lançar
        }
    }

    private val samplePages = mapOf(
        "/cgi/getDeviceInfo" to """{"model":"Archer C6","hardwareVersion":"V4","firmwareVersion":"1.2.3","uptime":3600}""",
        "/cgi/getWanStatus" to """{"connectionType":"PPPoE","externalIp":"203.0.113.10","gateway":"203.0.113.1","primaryDns":"8.8.8.8","secondaryDns":"8.8.4.4","connectionStatus":"Connected"}""",
        "/cgi/getWifiStatus" to """[{"band":"2.4GHz","enabled":"true","ssid":"MinhaRede","channel":"6"},{"band":"5GHz","enabled":"true","ssid":"MinhaRede_5G","channel":"36"}]""",
        "/cgi/getConnectedClients" to """[{"hostname":"notebook","ip":"192.168.0.10","mac":"AA:BB:CC:11:22:33","type":"wifi"}]""",
    )

    @Test
    fun `readSnapshot succeeds on first attempt and parses all endpoints`() = runTest {
        val transport = FakeTplinkHttpTransport(
            getParmResponse = sampleGetParmResponse(),
            loginResponses = mutableListOf(successfulTplinkLoginResponse()),
            authenticatedPages = samplePages,
        )
        val driver = TplinkOntDriver("192.168.0.1", transport, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is TplinkDriverResult.Success)
        val snapshot = (result as TplinkDriverResult.Success).snapshot
        assertEquals("Archer C6", snapshot.deviceInfo?.model)
        assertEquals("203.0.113.10", snapshot.wan?.externalIp)
        assertEquals(2, snapshot.wifi.size)
        assertEquals(1, snapshot.connectedClients.size)
        assertEquals("AA:BB:CC:**:**:**", snapshot.connectedClients.first().macAddressMasked)
    }

    @Test
    fun `readSnapshot fails fast on invalid credentials without exhausting retries`() = runTest {
        val transport = FakeTplinkHttpTransport(
            getParmResponse = sampleGetParmResponse(),
            loginResponses = mutableListOf(invalidCredentialsTplinkResponse()),
            authenticatedPages = samplePages,
        )
        val driver = TplinkOntDriver("192.168.0.1", transport, maxAttempts = 2, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "wrong")

        assertTrue(result is TplinkDriverResult.Failure)
        assertEquals(TplinkDriverFailureReason.INVALID_CREDENTIALS, (result as TplinkDriverResult.Failure).reason)
        // getParm (1) + cgi_gdpr (1) = 2 chamadas POST no total, sem repetir por retry
        assertEquals(2, transport.postCallCount)
    }

    @Test
    fun `readSnapshot fails fast on session in use without exhausting retries`() = runTest {
        val transport = FakeTplinkHttpTransport(
            getParmResponse = sampleGetParmResponse(),
            loginResponses = mutableListOf(sessionInUseTplinkResponse()),
            authenticatedPages = samplePages,
        )
        val driver = TplinkOntDriver("192.168.0.1", transport, maxAttempts = 2, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is TplinkDriverResult.Failure)
        assertEquals(TplinkDriverFailureReason.SESSION_IN_USE, (result as TplinkDriverResult.Failure).reason)
    }

    @Test
    fun `readSnapshot respects conservative max attempts default of two, not three like Nokia`() = runTest {
        val transport = FakeTplinkHttpTransport(
            getParmResponse = sampleGetParmResponse(),
            loginResponses = mutableListOf(
                TplinkHttpResponse(500, "", emptyMap(), emptyMap()),
                TplinkHttpResponse(500, "", emptyMap(), emptyMap()),
            ),
            authenticatedPages = samplePages,
        )
        var backoffCalls = 0
        val driver = TplinkOntDriver("192.168.0.1", transport, backoffMillis = { backoffCalls++; 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is TplinkDriverResult.Failure)
        assertEquals(1, backoffCalls) // só 1 backoff entre as 2 tentativas (default maxAttempts=2)
    }
}
