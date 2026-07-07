package com.nethal.core.driver.tplink

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TplinkC20OntDriverTest {

    private fun basicCookie(user: String, pass: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    @Test
    fun `rejects public host at construction - never sends credentials outside the LAN`() {
        val transport = FakeTplinkC20HttpTransport()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            TplinkC20OntDriver("8.8.8.8", transport)
        }
        assertTrue(exception.message!!.contains("8.8.8.8"))
    }

    @Test
    fun `rejects other well-known public hosts too - not a single hardcoded exception`() {
        val transport = FakeTplinkC20HttpTransport()

        listOf("1.1.1.1", "203.0.113.10", "142.250.0.1").forEach { publicHost ->
            assertThrows(IllegalArgumentException::class.java) {
                TplinkC20OntDriver(publicHost, transport)
            }
        }
    }

    @Test
    fun `accepts RFC1918 private host at construction`() {
        val transport = FakeTplinkC20HttpTransport()

        listOf("192.168.0.1", "10.0.0.1", "172.16.5.5").forEach { privateHost ->
            TplinkC20OntDriver(privateHost, transport) // não deve lançar
        }
    }

    private fun responsesForSuccessfulSnapshot(): Map<String, TplinkHttpResponse> {
        // login() e a leitura de device info usam exatamente o mesmo bundle de blocos
        // (TplinkC20AuthenticationClient.LOGIN_VALIDATION_SECTIONS) — é o único bundle com prova
        // real de sucesso, por isso as duas chamadas produzem o mesmo request body.
        val deviceInfoRequestBody = TplinkC20ResponseParser.buildRequestBody(
            TplinkC20AuthenticationClient.LOGIN_VALIDATION_SECTIONS,
        )
        val wifiRequestBody = TplinkC20ResponseParser.buildRequestBody(listOf("LAN_WLAN" to listOf("name", "SSID")))
        val clientsRequestBody = TplinkC20ResponseParser.buildRequestBody(
            listOf("LAN_HOST_ENTRY" to listOf("leaseTimeRemaining", "MACAddress", "hostName", "IPAddress")),
        )

        return mapOf(
            deviceInfoRequestBody to deviceInfoBundleResponse(),
            wifiRequestBody to lanWlanResponse(),
            clientsRequestBody to lanHostEntryResponse(),
        )
    }

    @Test
    fun `readSnapshot succeeds on first attempt and parses all confirmed sections`() = runTest {
        val transport = FakeTplinkC20HttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "secret"),
            responsesByRequestBody = responsesForSuccessfulSnapshot(),
        )
        val driver = TplinkC20OntDriver("192.168.0.1", transport, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is TplinkC20DriverResult.Success)
        val snapshot = (result as TplinkC20DriverResult.Success).snapshot
        assertEquals("Archer C20", snapshot.deviceInfo?.modelName)
        assertEquals(4, snapshot.deviceInfo?.numberOfVirtualPorts)
        assertEquals("ETH", snapshot.deviceInfo?.mode)
        assertEquals(2, snapshot.wifi.size)
        assertEquals("Casa-2.4G", snapshot.wifi[0].ssid)
        assertEquals(1, snapshot.connectedClients.size)
        assertEquals("AA:BB:CC:**:**:**", snapshot.connectedClients.first().macAddressMasked)
    }

    @Test
    fun `readSnapshot fails fast on invalid credentials without exhausting retries`() = runTest {
        val transport = FakeTplinkC20HttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "correct-password"),
            responsesByRequestBody = responsesForSuccessfulSnapshot(),
        )
        val driver = TplinkC20OntDriver("192.168.0.1", transport, maxAttempts = 2, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "wrong")

        assertTrue(result is TplinkC20DriverResult.Failure)
        assertEquals(TplinkC20DriverFailureReason.INVALID_CREDENTIALS, (result as TplinkC20DriverResult.Failure).reason)
        assertEquals(1, transport.postCallCount) // sem retry para credencial invalida, so 1 chamada
    }

    @Test
    fun `readSnapshot respects conservative max attempts default of two`() = runTest {
        val transport = FakeTplinkC20HttpTransport(
            defaultResponse = TplinkHttpResponse(500, "", emptyMap(), emptyMap()),
        )
        var backoffCalls = 0
        val driver = TplinkC20OntDriver("192.168.0.1", transport, backoffMillis = { backoffCalls++; 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is TplinkC20DriverResult.Failure)
        assertEquals(1, backoffCalls) // só 1 backoff entre as 2 tentativas (default maxAttempts=2)
    }
}
